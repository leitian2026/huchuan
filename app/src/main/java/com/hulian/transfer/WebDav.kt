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

class DavConfig(val url: String, val user: String, val pass: String, val dir: String)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** WebSocket 长连接：不设读超时（心跳由应用自己发） */
    val ws: OkHttpClient by lazy { client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
}

/** 最小的 WebDAV 客户端（坚果云用）：建文件夹、上传、下载、删除、列目录 */
object WebDav {
    class DavException(val code: Int, msg: String) : IOException(msg)

    private val ensured = ConcurrentHashMap.newKeySet<String>()
    private val xml = "application/xml".toMediaType()
    private const val PROPFIND_BODY = """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""

    /** 网盘上的文件夹被删了之后，缓存的“已建好”记录要作废 */
    fun forgetDirs() = ensured.clear()

    private fun root(c: DavConfig) = c.url.trim().trimEnd('/') + "/" + c.dir.trim().trim('/')

    private fun url(c: DavConfig, sub: String) = root(c) + if (sub.isEmpty()) "" else "/" + sub.trimStart('/')

    private fun builder(c: DavConfig, u: String): Request.Builder = try {
        Request.Builder().url(u).header("Authorization", Credentials.basic(c.user, c.pass, Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
        throw IOException("坚果云地址格式不对：$u（应该像 https://dav.jianguoyun.com/dav/）", e)
    }

    private fun why(code: Int): String = when (code) {
        401 -> "账号或应用密码不对（坚果云要用“应用密码”，不是登录密码；账号是注册邮箱）"
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

    private fun fail(op: String, code: Int): Nothing =
        throw DavException(code, "坚果云${op}失败：" + why(code) + "（HTTP $code）")

    private fun call(req: Request, op: String): Response {
        val r = Http.client.newCall(req).execute()
        if (!r.isSuccessful) {
            val code = r.code
            r.close()
            fail(op, code)
        }
        return r
    }

    private fun mkcol(c: DavConfig, sub: String) {
        val r = Http.client.newCall(builder(c, url(c, sub)).method("MKCOL", null).build()).execute()
        r.use {
            // 201 新建；405 已经存在；有的服务器对已存在的文件夹回 301/302
            if (it.code !in setOf(200, 201, 204, 301, 302, 405)) fail("创建文件夹", it.code)
        }
    }

    /** 确保 根文件夹/sub 存在（同一次运行里只建一次） */
    fun ensureDir(c: DavConfig, sub: String = "") {
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
    fun put(c: DavConfig, sub: String, length: Long, writer: (OutputStream) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) { writer(sink.outputStream()) }
        }
        call(builder(c, url(c, sub)).put(body).build(), "上传").close()
    }

    fun <T> get(c: DavConfig, sub: String, block: (InputStream) -> T): T {
        return call(builder(c, url(c, sub)).get().build(), "下载").use { block(it.body!!.byteStream()) }
    }

    fun delete(c: DavConfig, sub: String) {
        call(builder(c, url(c, sub)).delete().build(), "删除").close()
    }

    /** 列出 sub 文件夹里的 .bin 文件名；文件夹不存在返回空 */
    fun listBins(c: DavConfig, sub: String): List<String> {
        val req = builder(c, url(c, sub)).header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(xml)).build()
        return Http.client.newCall(req).execute().use { r ->
            if (r.code == 404) return@use emptyList<String>()
            if (!r.isSuccessful) fail("查看文件夹", r.code)
            val text = r.body!!.string()
            Regex("<(?:\\w+:)?href>\\s*([^<]+?)\\s*</(?:\\w+:)?href>", RegexOption.IGNORE_CASE).findAll(text)
                .map { URLDecoder.decode(it.groupValues[1].trimEnd('/'), "UTF-8").substringAfterLast('/') }
                .filter { it.endsWith(".bin") }
                .toList()
        }
    }

    /** 设置页“测试坚果云”：建文件夹 → 上传 → 下载 → 删除，任何一步失败都抛出带原因的异常 */
    fun test(c: DavConfig) {
        forgetDirs()
        ensureDir(c)
        val name = "test-" + newId().take(8) + ".tmp"
        val data = "hulian".toByteArray()
        put(c, name, data.size.toLong()) { it.write(data) }
        val got = get(c, name) { it.readBytes() }
        delete(c, name)
        if (!got.contentEquals(data)) throw IOException("坚果云读回来的内容和写入的不一致")
    }
}
