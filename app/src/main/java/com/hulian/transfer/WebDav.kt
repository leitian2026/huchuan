package com.hulian.transfer

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** s3=true：这是 Cloudflare R2（S3 接口）：url=桶地址，user=Access Key ID，pass=Secret，dir=对象名前缀 */
class DavConfig(val url: String, val user: String, val pass: String, val dir: String, val s3: Boolean = false)

object Http {
    private fun base(dns: okhttp3.Dns, http1: Boolean = false): OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply { if (http1) protocols(listOf(okhttp3.Protocol.HTTP_1_1)) }
        .build()

    /** Cloudflare 用：Host 映射 → 系统 DNS（开了“使用系统 DNS”就直接系统 DNS），见 NetDns */
    val client: OkHttpClient = base(NetDns.cf)

    /**
     * 网盘 WebDAV 用：Host 映射 → 自建 DoH → 系统 DNS（开了“使用系统 DNS”就直接系统 DNS），见 NetDns。
     * 固定用 HTTP/1.1：大文件分段并行上传时，每一段要各自占一条 TCP 连接才能叠加速度；
     * 如果协商成 HTTP/2，几段会挤在同一条连接里，单连接的速度上限就没有被突破
     */
    val dav: OkHttpClient = base(NetDns.dav, http1 = true)

    /** WebSocket 长连接：不设读超时（心跳由应用自己发） */
    val ws: OkHttpClient by lazy { client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
}

/** 最小的 WebDAV 客户端（坚果云 / InfiniCLOUD / 其他支持 WebDAV 的网盘通用）：建文件夹、上传、下载、删除、列目录 */
object WebDav {
    /**
     * blocked：服务器说“请求太频繁，被临时封了”（BlockedTemporarily）：这时重试只会封得更久，不能重试。
     * waitMs：被限速（429）时建议等多久再发请求（毫秒），0 表示没有。
     * queued：true 表示这个请求根本没发出去（正在退避中，本软件自己拦下了），重试时不算一次失败
     */
    class DavException(
        val code: Int, msg: String, val blocked: Boolean = false,
        val waitMs: Long = 0L, val queued: Boolean = false
    ) : IOException(msg)

    /** 下载时连接被中途断开、内容没收完整（OkHttp 的 unexpected end of stream、读到空文件等）。收消息时头部阶段遇到它会自动重试 */
    class StreamCutException(msg: String, cause: Throwable? = null) : IOException(msg, cause)

    /** 这个异常（或它的原因）是不是“下载被中途断开 / 读到的比声明的短” */
    private fun isCut(e: Throwable): Boolean {
        var t: Throwable? = e
        var n = 0
        while (t != null && n < 4) {
            if (t is java.net.ProtocolException || t is java.io.EOFException) return true
            val m = t.message.orEmpty()
            if (m.contains("unexpected end of stream", ignoreCase = true) || m.contains("中转文件是空的")) return true
            t = t.cause
            n++
        }
        return false
    }

    // ---------- 429 通用退避（所有网盘都用，不依赖坚果云的额度统计） ----------
    // 任何网盘回 429（请求太多）：先停一段时间不发请求，免得一边被限速一边继续猛发、被限得更久。
    // 服务器给了 Retry-After 就照它等，没给就 30 秒起步、连续碰到就翻倍，最多 10 分钟；成功一次后清零。

    private const val BACKOFF_BASE_MS = 30_000L
    private const val BACKOFF_MIN_MS = 5_000L
    private const val BACKOFF_MAX_MS = 10L * 60_000L
    private val backoffLock = Any()
    private var backoffUntil = 0L
    private var backoffStreak = 0

    /** 还要退避多久（毫秒），不在退避期返回 0 */
    fun backoffLeftMs(): Long = synchronized(backoffLock) { (backoffUntil - System.currentTimeMillis()).coerceAtLeast(0L) }

    /** Retry-After：秒数或日期，换算成毫秒；没有或看不懂返回 null */
    private fun retryAfterMs(h: okhttp3.Headers?, now: Long): Long? {
        val v = h?.get("Retry-After")?.trim() ?: return null
        v.toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1000L }
        return h.getDate("Retry-After")?.time?.minus(now)
    }

    /** 碰到一次 429：开始（或延长）退避，返回现在还要等多少毫秒 */
    private fun noteRateLimited(h: okhttp3.Headers?): Long {
        val now = System.currentTimeMillis()
        val ra = retryAfterMs(h, now)?.coerceIn(BACKOFF_MIN_MS, BACKOFF_MAX_MS)
        return synchronized(backoffLock) {
            if (now < backoffUntil) {
                // 已经在退避期：同时在飞的别的请求也吃到了 429，不算新的一次，也不翻倍；只有服务器给了更长的等待才延长
                if (ra != null) backoffUntil = maxOf(backoffUntil, now + ra)
            } else {
                backoffStreak = (backoffStreak + 1).coerceAtMost(5)
                backoffUntil = now + (ra ?: (BACKOFF_BASE_MS shl (backoffStreak - 1)).coerceAtMost(BACKOFF_MAX_MS))
            }
            backoffUntil - now
        }
    }

    /** 成功了一次：连续 429 的计数清零（还在退避期内的不清，那是退避之前就发出去的请求） */
    private fun noteRequestOk() {
        synchronized(backoffLock) {
            if (backoffStreak != 0 && System.currentTimeMillis() >= backoffUntil) backoffStreak = 0
        }
    }

    private val ensured = ConcurrentHashMap.newKeySet<String>()
    private val xml = "application/xml".toMediaType()
    private const val PROPFIND_BODY = """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><resourcetype/><getlastmodified/><getcontentlength/></prop></propfind>"""

    /** 网盘上的文件夹被删了之后，缓存的“已建好”记录要作废 */
    fun forgetDirs() = ensured.clear()

    private fun root(c: DavConfig) = c.url.trim().trimEnd('/') + "/" + c.dir.trim().trim('/')

    private fun url(c: DavConfig, sub: String) = root(c) + if (sub.isEmpty()) "" else "/" + sub.trimStart('/')

    private fun builder(c: DavConfig, u: String): Request.Builder = try {
        UrlRules.davError(u, Store.davName)?.let { throw IOException(it) }   // 账号密码只走 https
        Request.Builder().url(u).header("Authorization", Credentials.basic(c.user, c.pass, Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
        throw IOException("${Store.davName}地址格式不对：$u（应该像 ${DavProviders.get(Store.davProvider).urlExample}）", e)
    }

    private fun why(code: Int): String = if (Store.davProvider == "r2") whyR2(code) else when (code) {
        401 -> if (Store.davProvider == "jgy") "账号或应用密码不对（坚果云要用“应用密码”，不是登录密码；账号是注册邮箱）"
            else "账号或密码不对（要用网盘里单独生成的 WebDAV / 应用密码，不是登录密码）"
        403 -> "被网盘拒绝（可能是本月上传 / 下载流量用完了、请求太频繁被限制，或没有写入权限）"
        404 -> "网盘上找不到这个文件或文件夹"
        405 -> "网盘不允许这个操作"
        409 -> "上级文件夹不存在"
        413 -> "文件太大，超过了网盘的单文件上限"
        423 -> "文件被锁定"
        429 -> "请求太频繁，被网盘限速了，请稍后再试"
        507 -> "网盘空间不足"
        in 500..599 -> "网盘服务器出错"
        else -> "网盘返回了意外的状态"
    }

    private fun whyR2(code: Int): String = when (code) {
        400 -> "请求格式不对（检查地址是不是 https://账号ID.r2.cloudflarestorage.com/桶名）"
        401, 403 -> "R2 拒绝了：密钥不对、没有这个桶的读写权限，或手机时间和标准时间差了 15 分钟以上（签名会失效）"
        404 -> "桶或文件不存在（检查地址最后的桶名对不对）"
        429 -> "请求太频繁，被 R2 限速了，请稍后再试"
        in 500..599 -> "R2 服务器出错"
        else -> "R2 返回了意外的状态"
    }

    /** 出错响应里的正文（坚果云会在里面写具体原因，比如 BlockedTemporarily），最多取 2000 字符 */
    private fun bodyOf(r: Response): String = try {
        r.body?.string()?.take(2000) ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun fail(op: String, code: Int, body: String = "", headers: okhttp3.Headers? = null): Nothing {
        val blocked = body.contains("BlockedTemporarily", ignoreCase = true)
        if (blocked) DavQuota.noteBlocked()
        val wait = if (code == 429 && !blocked) noteRateLimited(headers) else 0L
        val detail = Regex("<(?:\\w+:)?message>([^<]*)<", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val text = if (blocked) "请求太频繁，被坚果云临时封了（一般持续几小时），本软件会先暂停发请求"
            else if (wait > 0L) "请求太频繁，被${Store.davName}限速了，本软件会先暂停发请求约 ${(wait + 999L) / 1000L} 秒再试"
            else why(code)
        val more = if (detail.isNotEmpty() && !blocked) "，服务器说：$detail" else ""
        throw DavException(code, "${Store.davName}${op}失败：$text（HTTP $code$more）", blocked, wait)
    }

    /** 正在被坚果云限流封着：不再发请求（每多发一个请求都可能让封锁多持续一会儿） */
    private fun checkNotBlocked(op: String) {
        val left = DavQuota.blockedLeftMs()
        if (left > 0L) {
            throw DavException(
                503,
                "${Store.davName}${op}失败：坚果云正在限流（请求太频繁被临时封了），约 ${(left + 59_999L) / 60_000L} 分钟后再试（HTTP 503）",
                true
            )
        }
        // 刚被限速（429）：这段时间不发请求，请求没发出去，调用方等一会儿再来（不算失败）
        val wait = backoffLeftMs()
        if (wait > 0L) {
            throw DavException(
                429,
                "${Store.davName}${op}暂缓：刚被限速（HTTP 429），约 ${(wait + 999L) / 1000L} 秒后再试",
                false, wait, queued = true
            )
        }
    }

    private fun call(req: Request, op: String): Response {
        checkNotBlocked(op)
        DavQuota.noteRequest()
        val r = Http.dav.newCall(req).execute()
        if (!r.isSuccessful) {
            val code = r.code
            val body = bodyOf(r)
            val hdr = r.headers
            r.close()
            fail(op, code, body, hdr)
        }
        noteRequestOk()
        return r
    }

    private fun mkcol(c: DavConfig, sub: String) {
        checkNotBlocked("创建文件夹")
        DavQuota.noteRequest()
        val r = Http.dav.newCall(builder(c, url(c, sub)).method("MKCOL", null).build()).execute()
        r.use {
            // 201 新建；405 已经存在；有的服务器对已存在的文件夹回 301/302
            if (it.code !in setOf(200, 201, 204, 301, 302, 405)) fail("创建文件夹", it.code, bodyOf(it), it.headers)
        }
    }

    /** 确保 根文件夹/sub 存在（同一次运行里只建一次） */
    fun ensureDir(c: DavConfig, sub: String = "") {
        if (c.s3) return   // R2 没有文件夹，对象名带前缀即可
        val key = root(c) + "|" + sub + "|" + c.user
        if (!ensured.add(key)) return
        try {
            mkcol(c, "")
            if (sub.isNotEmpty()) mkcol(c, sub)
        } catch (e: Exception) {
            ensured.remove(key)
            throw e
        }
    }

    /** length 必须是准确的字节数（服务器要 Content-Length）；writer 往里写刚好这么多 */
    fun put(c: DavConfig, sub: String, length: Long, tag: Any? = null, writer: (OutputStream) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) { writer(sink.outputStream()) }
        }
        val req = if (c.s3) S3.request(c, "PUT", S3.objUrl(c, sub), body, tag)
            else builder(c, url(c, sub)).put(body).tag(tag).build()
        call(req, "上传").close()
    }

    private fun getReq(c: DavConfig, sub: String, tag: Any?): Request =
        if (c.s3) S3.request(c, "GET", S3.objUrl(c, sub), null, tag)
        else builder(c, url(c, sub)).get().tag(tag).build()

    /** 服务器响应里排查用得上的几项，写进“下载中途断开”的报错里 */
    private fun respInfo(r: Response): String {
        val h = r.headers
        val keys = listOf("Content-Length", "Content-Encoding", "Transfer-Encoding", "Content-Type", "Connection", "Server")
        val parts = keys.mapNotNull { k -> h[k]?.let { "$k: $it" } }
        return "HTTP ${r.code} ${r.protocol}" + if (parts.isEmpty()) "" else "；" + parts.joinToString("；")
    }

    fun <T> get(c: DavConfig, sub: String, tag: Any? = null, block: (InputStream) -> T): T {
        return call(getReq(c, sub, tag), "下载").use { r ->
            val total = r.body!!.contentLength()   // 服务器声明的大小，-1 表示没给
            val cin = CountingInputStream(r.body!!.byteStream())
            try {
                block(cin)
            } catch (e: IOException) {
                if (!isCut(e)) throw e
                // 把“中途断开”单独标出来，并写清楚收到多少 / 声明多少，方便判断是一开始就断还是断在中间
                val declared = if (total >= 0L) "${fmtSize(total)}（$total 字节）" else "没有声明"
                throw StreamCutException(
                    "${Store.davName}下载时连接被中途断开，内容没收完整：已收到 ${fmtSize(cin.count)}（${cin.count} 字节），服务器声明共 $declared。" +
                        "常见原因：对方刚传完、网盘那边还没写好，或网络不稳；一般过几秒再收就好。" +
                        "服务器响应：${respInfo(r)}",
                    e
                )
            }
        }
    }

    /** 中断所有带这个标记的网盘请求（取消传输时用）：正在上传 / 下载的会马上抛出 IOException */
    fun cancelCalls(tag: Any) {
        for (call in Http.dav.dispatcher.runningCalls()) if (call.request().tag() === tag) call.cancel()
        for (call in Http.dav.dispatcher.queuedCalls()) if (call.request().tag() === tag) call.cancel()
    }

    /** 下载一个不大的文件到内存；超过 max 字节就报错（免得对方 / 服务器塞个超大文件把内存撑爆） */
    fun getBytes(c: DavConfig, sub: String, max: Long, tag: Any? = null): ByteArray =
        call(getReq(c, sub, tag), "下载").use { r ->
            val src = r.body!!.source()
            if (src.request(max + 1)) throw IOException("下载的文件比预期的大")
            src.readByteArray()
        }

    fun delete(c: DavConfig, sub: String) {
        val req = if (c.s3) S3.request(c, "DELETE", S3.objUrl(c, sub), null, null)
            else builder(c, url(c, sub)).delete().build()
        call(req, "删除").close()
    }

    /** 文件夹里的一个文件：名字、大小（字节）、最后修改时间（毫秒时间戳，服务器没给或解析不了是 0） */
    class Entry(val name: String, val size: Long, val modified: Long)

    /** 列出 sub 文件夹里的所有文件（不含子文件夹）；文件夹不存在返回空。清理残留用 */
    fun listEntries(c: DavConfig, sub: String): List<Entry> {
        if (c.s3) return listS3(c, sub)
        val req = builder(c, url(c, sub)).header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(xml)).build()
        checkNotBlocked("查看文件夹")
        DavQuota.noteRequest()
        return Http.dav.newCall(req).execute().use { r ->
            if (r.code == 404) return@use emptyList<Entry>()
            if (!r.isSuccessful) fail("查看文件夹", r.code, bodyOf(r), r.headers)
            val text = r.body!!.string()
            val href = Regex("<(?:\\w+:)?href>\\s*([^<]+?)\\s*</(?:\\w+:)?href>", RegexOption.IGNORE_CASE)
            val len = Regex("<(?:\\w+:)?getcontentlength>\\s*(\\d+)\\s*<", RegexOption.IGNORE_CASE)
            val mod = Regex("<(?:\\w+:)?getlastmodified>\\s*([^<]+?)\\s*<", RegexOption.IGNORE_CASE)
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            Regex("<(?:\\w+:)?response[\\s>][\\s\\S]*?</(?:\\w+:)?response>", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { m ->
                val b = m.value
                val h = href.find(b)?.groupValues?.get(1) ?: return@mapNotNull null
                if (h.trimEnd().endsWith("/")) return@mapNotNull null   // 文件夹本身
                val name = URLDecoder.decode(h.trimEnd('/'), "UTF-8").substringAfterLast('/')
                val t = mod.find(b)?.groupValues?.get(1)?.let { s ->
                    try { fmt.parse(s)?.time } catch (e: Exception) { null }
                } ?: 0L
                Entry(name, len.find(b)?.groupValues?.get(1)?.toLongOrNull() ?: 0L, t)
            }.toList()
        }
    }

    /** R2：ListObjectsV2，按前缀 + 分隔符 / 只列这一层，翻页取完 */
    private fun listS3(c: DavConfig, sub: String): List<Entry> {
        val prefix = S3.prefixOf(c, sub)
        val all = ArrayList<Entry>()
        var token: String? = null
        var pages = 0
        do {
            val req = S3.request(c, "GET", S3.listUrl(c, prefix, token), null, null)
            val page = call(req, "查看文件夹").use { S3.parseList(it.body!!.string()) }
            all.addAll(page.entries)
            token = page.next
        } while (token != null && ++pages < 100)
        return all
    }

    /** 列出 sub 文件夹里的消息头文件名（.dat，兼容旧版本的 .bin）；文件夹不存在返回空 */
    fun listBins(c: DavConfig, sub: String): List<String> {
        if (c.s3) return listS3(c, sub).map { it.name }.filter { it.endsWith(".dat") || it.endsWith(".bin") }
        val req = builder(c, url(c, sub)).header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(xml)).build()
        checkNotBlocked("查看文件夹")
        DavQuota.noteRequest()
        return Http.dav.newCall(req).execute().use { r ->
            if (r.code == 404) return@use emptyList<String>()
            if (!r.isSuccessful) fail("查看文件夹", r.code, bodyOf(r), r.headers)
            val text = r.body!!.string()
            Regex("<(?:\\w+:)?href>\\s*([^<]+?)\\s*</(?:\\w+:)?href>", RegexOption.IGNORE_CASE).findAll(text)
                .map { URLDecoder.decode(it.groupValues[1].trimEnd('/'), "UTF-8").substringAfterLast('/') }
                .filter { it.endsWith(".dat") || it.endsWith(".bin") }
                .toList()
        }
    }

    /** 测试网盘用的一组条件：放在哪个文件夹、什么扩展名、多大、内容是随机二进制还是文本 */
    private class TestCase(val label: String, val dir: String, val ext: String, val size: Int, val random: Boolean)

    /**
     * 测试网盘：每一组各传一个文件再读回来，互相独立，一组失败不影响后面的，全部跑完列出每组结果。
     * 这三组对应真实收发用到的几种文件：小文件、子文件夹里的消息头文件（.dat）、分段文件（.prt）。
     * 请求次数有限制的网盘（坚果云、Koofr）上每点一次约发 10 个请求，所以不要多加组
     */
    fun test(c: DavConfig) {
        forgetDirs()
        ensureDir(c)
        val tag = newId().take(8)
        val dir = "_selftest"
        val cases = listOf(
            TestCase("根文件夹里的小文件（6 字节）", "", "tmp", 6, false),
            TestCase("子文件夹里的消息文件 .dat（150 字节随机内容）", dir, "dat", 150, true),
            TestCase("子文件夹里的分段文件 .prt（150 字节随机内容）", dir, "prt", 150, true),
        )
        val rnd = java.security.SecureRandom()
        val lines = StringBuilder()
        var failed = 0
        var firstErr: String? = null
        for ((i, t) in cases.withIndex()) {
            val sub = (if (t.dir.isEmpty()) "" else t.dir + "/") + "test-$tag-$i.${t.ext}"
            val data = if (t.random) ByteArray(t.size).also { rnd.nextBytes(it) }
                else ByteArray(t.size) { "hulian-"[it % 7].code.toByte() }
            val err: String? = try {
                if (t.dir.isNotEmpty()) ensureDir(c, t.dir)
                put(c, sub, data.size.toLong()) { it.write(data) }
                val got = get(c, sub) { it.readBytes() }
                if (!got.contentEquals(data)) throw IOException("读回来的内容和写入的不一致（收到 ${got.size} 字节）")
                null
            } catch (e: IOException) {
                e.message ?: e.javaClass.simpleName
            } finally {
                try { delete(c, sub) } catch (e: Exception) { Hub.log("测试网盘", e) }
            }
            lines.append(if (err == null) "✓ " else "✗ ").append(t.label).append('\n')
            if (err != null) {
                failed++
                if (firstErr == null) firstErr = "第 ${i + 1} 组（${t.label}）：$err"
            }
        }
        try { delete(c, dir) } catch (e: Exception) { Hub.log("测试网盘", e) }
        forgetDirs()
        if (failed > 0) {
            throw IOException("${cases.size} 组测试里有 $failed 组失败：\n$lines\n第一个失败的详情：\n$firstErr")
        }
    }
}

/** 可选的网盘（WebDAV）。每家各自记一套地址 / 账号 / 密码，切换不会丢 */
class DavProvider(
    val id: String, val name: String, val defaultUrl: String, val urlExample: String,
    val urlLabel: String, val userLabel: String, val passLabel: String, val note: String
)

object DavProviders {
    val all = listOf(
        DavProvider(
            "jgy", "坚果云", "https://dav.jianguoyun.com/dav/", "https://dav.jianguoyun.com/dav/",
            "坚果云 WebDAV 地址", "坚果云账号（注册邮箱）", "坚果云应用密码（不是登录密码）",
            "国内最稳。免费版流量有限：每月上传约 1GB、下载约 3GB。"
        ),
        DavProvider(
            "infini", "InfiniCLOUD", "", "https://xxx.teracloud.jp/dav/",
            "InfiniCLOUD WebDAV 地址（在 My Page → Apps Connection 里看，每个账号不一样）",
            "InfiniCLOUD 用户名（User ID）", "Apps Connection 里生成的密码",
            "免费 20GB，官网称没有传输限制；服务器在日本，先确认你的网络能打开自己的 teracloud.jp 地址。"
        ),
        DavProvider(
            "r2", "Cloudflare R2", "", "https://<账号ID>.r2.cloudflarestorage.com/<桶名>",
            "R2 地址（S3 接口地址 + /桶名，账号ID 在 R2 概览页右侧）",
            "Access Key ID（用 API Token 时填 Token 的 ID）",
            "Secret Access Key（用 API Token 时直接填 Token 的值，App 会按官方规则自动取 SHA-256）",
            "走 S3 接口，不是 WebDAV。免费 10GB，出站流量免费，没有坚果云那种请求次数 / 月流量限制，所以不显示额度保护。桶要先在 Cloudflare 里建好；手机时间要准（差 15 分钟以上签名会失效）。"
        ),
        DavProvider(
            "custom", "自定义", "", "https://example.com/dav/",
            "WebDAV 地址", "账号", "密码 / 应用密码",
            "任何支持 WebDAV 的网盘或自己搭的服务（要 https）。注意：有些服务有请求频率限制但没公开具体数字（比如 Koofr 的 WebDAV 会回 429“请求太多”）。" +
                "碰到 429 时本软件会自动暂停发请求一会儿再试，但这类网盘没有额度显示、也不会提前暂停；经常遇到的话，请把并行数调低（1～2）。"
        )
    )

    fun get(id: String): DavProvider = all.firstOrNull { it.id == id } ?: all[0]
}
