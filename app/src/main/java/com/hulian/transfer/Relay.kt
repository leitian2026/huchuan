package com.hulian.transfer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.spec.SecretKeySpec

/**
 * 远程中转：两台手机不在同一个网络、也连不上热点时用。
 *
 *  - Cloudflare Worker：只负责“谁在线”和“有新消息”的小通知（WebSocket 长连接，对方上线 / 来消息都是推送，不用轮询）
 *  - 网盘 WebDAV（坚果云 / InfiniCLOUD 等）：存放加密后的内容。发送方上传到“对方的文件夹”，对方收到通知后下载、解密、删除
 *
 * 内容在手机上用配对时交换的密钥加密（见 RelayCrypto），网盘和 Cloudflare 都看不到。
 *
 * 大文件（超过一个分段）会切成若干分段，几段同时上传（突破单条连接的速度上限），
 * 每传完一段就通知对方，对方一边收一边写，不用等整个文件传完。文字和小文件仍是一个文件，和旧版本完全兼容。
 */
object Relay {
    /** 给设置页显示的连接状态（出错时写明原因） */
    val state = MutableStateFlow("未启用")
    val connected = MutableStateFlow(false)

    /** 这次连接上是否已经收到过 Cloudflare 发来的“谁在线”名单（没收到之前，“对方在线 / 不在线”还不能下结论） */
    val presence = MutableStateFlow(false)

    /** 通过 Cloudflare 看到“在线”的设备（不含本机） */
    val online = MutableStateFlow<Set<String>>(emptySet())

    @Volatile
    private var sock: WebSocket? = null
    private var job: Job? = null
    private var backoff = 2000L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val acks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val processing = ConcurrentHashMap.newKeySet<String>()
    /** 已处理过的消息 ID：存在本机，重启后还在（最多记 1500 条，再老的靠消息里的时间戳拦） */
    private val doneMids: MutableSet<String> by lazy { java.util.Collections.synchronizedSet(LinkedHashSet(Store.relayDoneMids)) }
    private const val DONE_MAX = 1500
    /** 消息里（已加密校验）的发送时间比这还早，就当作被人重放的旧文件 */
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    private val midPattern = Regex("[A-Za-z0-9-]{1,64}")

    private const val MB = 1024L * 1024
    /** 超过这个大小的文件才分段；每段的大小（文件很大时每段大一点，减少对网盘的请求次数） */
    private fun partSize(size: Long): Long = if (size <= 256 * MB) 4 * MB else 8 * MB
    /** 接收时接受的每段大小上限（防止对方在消息头里声明一个离谱的值） */
    private const val MAX_PART = 64L * 1024 * 1024
    /** 等一段上传出来的最长时间（期间没有任何新的分段通知） */
    private const val PART_WAIT_MS = 120_000L
    /** 对方暂停发送时，最多等它多久（防止对方的手机没了，这边一直等） */
    private const val SENDER_PAUSE_MAX_MS = 30L * 60 * 1000

    /** 正在接收的消息：对方每传完一段会来一条通知，这里记下来并唤醒等待的下载（事件驱动，不轮询） */
    private class Live(val from: String?) {
        val tick = MutableStateFlow(0)
        val announced = ConcurrentHashMap.newKeySet<Int>()
        @Volatile var aborted = false
        /** 对方的通知可能丢了（本机刚上线 / 重连过）：不等通知，直接去网盘看有没有 */
        @Volatile var optimistic = false
        /** 发送方点了暂停：它没有新分段是正常的，不算超时 */
        @Volatile var senderPaused = false
        @Volatile var pausedSince = 0L
        /** 这条消息是谁发来的（收完后把本机的用量告诉它） */
        @Volatile var peerId: String? = null
        fun poke() = tick.update { it + 1 }
    }

    /** 本机正在发送的分段文件：消息编号 → 发给谁 + 控制柄（对方点了取消时用） */
    private class Outgoing(val peerId: String, val ctl: TransferCtl)

    private val sending = ConcurrentHashMap<String, Outgoing>()

    private val live = ConcurrentHashMap<String, Live>()

    /** 分段文件接收到一半的状态：第 0 段已经写进文件，剩下的几段还要继续收 */
    private class Multi(
        val peer: Peer, val key: SecretKeySpec, val mid: String,
        val job: Inbox.FileJob, val n: Int, val psz: Long, val ctl: TransferCtl
    ) {
        var got = 0L
        var last = 0L
    }

    /** 对方中止了发送，或者很久没有继续上传：消息在聊天里已标成失败，不再弹框 */
    private class PeerGoneException(msg: String, val aborted: Boolean, val n: Int) : IOException(msg)

    private fun markDone(mid: String) {
        synchronized(doneMids) {
            doneMids.add(mid)
            while (doneMids.size > DONE_MAX) doneMids.remove(doneMids.first())
            Store.relayDoneMids = doneMids.toList()
        }
    }

    fun configured() = Store.relayOn && Store.relayUrl.isNotBlank() && Store.relaySecret.isNotBlank()

    /** 不能用远程中转的原因；null 表示可以用 */
    fun notReadyReason(p: Peer?): String? = when {
        !Store.relayOn -> "远程中转没有启用（设置 → 远程中转）"
        Store.relayUrl.isBlank() || Store.relaySecret.isBlank() -> "远程中转还没填 Cloudflare 地址或口令"
        Store.dav == null -> "远程中转还没填${Store.davName}账号"
        p != null && p.rk.isEmpty() -> "这台设备还没有和本机交换过加密密钥（两台手机需要同时在线、直连一次，或者重新扫码配对）"
        else -> null
    }

    fun ready(p: Peer) = notReadyReason(p) == null

    /**
     * 远程中转还没出结果：还没开始连、正在连，或者已连上但还没收到“谁在线”名单。自动连接热点前先等它一小会儿（远程优先）。
     * 只有已经连上并拿到名单，或者明确失败（“未连接：…”）了，才算有结果。
     * 以前只认“正在连接”，App 刚启动、连接还没开始时会误判成“没有远程”，抢先去开 Wi-Fi 和定位
     */
    fun settling() = configured() && !(connected.value && presence.value) && !state.value.startsWith("未连接")

    // ---------- 连接 ----------

    fun start() {
        if (job?.isActive == true) return
        restart()
    }

    fun restart() {
        if (!configured()) goodbye()   // 关掉远程中转 / 清空配置：也算下线，先告诉对方
        stop()
        if (!configured()) {
            state.value = if (Store.relayOn) "还没填 Cloudflare 地址或口令" else "未启用"
            return
        }
        backoff = 2000L
        job = Hub.scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { sock?.close(1000, "bye") } catch (e: Exception) { Hub.log("Relay", e) }
        sock = null
        connected.value = false
        presence.value = false
        online.value = emptySet()
    }

    /**
     * 本机主动下线（退出 app / 关掉远程中转）：先给还在线的对方各发一条“我下线了”，
     * 对方收到后立刻把本机标为离线，不用等 Cloudflare 发现连接断开。必须在 stop() 之前调用；
     * 消息排在关闭帧前面，会先发出去。被系统强杀时来不及发，这种情况只能靠 Cloudflare 自己发现断线
     */
    fun goodbye() {
        val s = sock ?: return
        if (!connected.value) return
        try {
            for (id in online.value) {
                val p = Hub.peers.value[id]?.takeIf { it.paired } ?: continue
                s.send(JSONObject().put("op", "notify").put("to", p.id).put("body", JSONObject().put("bye", true)).toString())
            }
        } catch (e: Exception) { Hub.log("Relay", e) }
    }

    /** 网络变了：别等退避时间，马上重连 */
    fun kick() {
        wake.trySend(Unit)
    }

    private fun wsUrl(): String {
        val u = Store.relayUrl.trim().trimEnd('/')
        UrlRules.relayError(u)?.let { throw IllegalArgumentException(it) }   // 不允许 http:// / ws:// 明文
        val base = when {
            u.startsWith("https://") -> "wss://" + u.removePrefix("https://")
            u.startsWith("wss://") -> u
            else -> "wss://$u"
        }
        return "$base/ws"
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val why = connectOnce()
            connected.value = false
            presence.value = false
            online.value = emptySet()
            Hub.log("远程中转", IOException(why))
            state.value = "未连接：$why（${backoff / 1000} 秒后重试）"
            Hub.reportOnce("relay-conn", "远程中转连不上 Cloudflare", why, "地址：" + Store.relayUrl)
            withTimeoutOrNull(backoff) { wake.receive() }
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    /** 连一次，一直等到断开；返回断开的原因 */
    private suspend fun connectOnce(): String = coroutineScope {
        state.value = "正在连接 Cloudflare…"
        val closed = CompletableDeferred<String>()
        val req = try {
            Request.Builder().url(wsUrl())
                .header("X-Relay-Secret", Store.relaySecret)
                .header("X-Device", Store.deviceId)
                .build()
        } catch (e: IllegalArgumentException) {
            return@coroutineScope UrlRules.relayError(Store.relayUrl) ?: ("Cloudflare 地址格式不对：" + Store.relayUrl)
        }
        val s = Http.ws.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.value = true
                backoff = 2000L
                state.value = if (Store.dav == null) "已连接（但${Store.davName}账号没填，收不到内容）" else "已连接"
                // 断线重连：断开期间可能漏了分段通知，正在接收的文件直接去网盘看一眼
                for (lv in live.values) {
                    lv.optimistic = true
                    lv.poke()
                }
                Hub.scope.launch { sweepInbox() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete("服务器关闭了连接（$code $reason）")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(explain(t, response))
            }
        })
        sock = s
        val hb = launch {
            while (isActive) {
                delay(25_000)
                s.send("ping")
            }
        }
        try {
            closed.await()
        } finally {
            hb.cancel()
            try { s.cancel() } catch (e: Exception) { Hub.log("Relay", e) }
            if (sock === s) sock = null
        }
    }

    private fun explain(t: Throwable, r: Response?): String {
        if (r != null) {
            return when (r.code) {
                401 -> "口令不对（Cloudflare 返回 401）：手机里的“中转口令”要和部署时设置的 RELAY_SECRET 完全一样"
                404 -> "Cloudflare 返回 404：地址可能填错了，或者中转程序还没有部署"
                else -> "Cloudflare 返回了 HTTP ${r.code}"
            }
        }
        netHint(t)?.let { return it + "（" + t.javaClass.simpleName + "）" }   // DNS 被改 / 握手被掐：别再说成“配对身份不一致”
        return friendlyError(t) + "（" + t.javaClass.simpleName + "）"
    }

    private fun onText(text: String) {
        if (text == "pong") return
        try {
            val j = JSONObject(text)
            when (j.optString("op")) {
                "presence" -> {
                    val a = j.getJSONArray("online")
                    online.value = (0 until a.length()).map { a.getString(it) }.filter { it != Store.deviceId }.toSet()
                    presence.value = true
                }
                "notify" -> {
                    val from = j.getString("from")
                    val body = j.getJSONObject("body")
                    if (body.optBoolean("bye")) {   // 对方主动下线：马上标为离线
                        online.value = online.value - from
                        return
                    }
                    val mid = body.getString("mid")
                    body.optJSONObject("q")?.let { DavQuota.peerReport(from, it) }   // 对方带来的坚果云用量
                    if (body.optBoolean("qonly")) return   // 只是报用量
                    if (body.optBoolean("cancel")) {   // 接收方点了取消：发送方停止上传
                        sending[mid]?.takeIf { it.peerId == from }?.ctl?.cancelByPeer()
                        return
                    }
                    if (body.has("pause")) {   // 发送方暂停 / 继续：暂停期间没有新分段不算超时
                        live[mid]?.takeIf { it.from == null || it.from == from }?.let {
                            it.senderPaused = body.optBoolean("pause")
                            it.pausedSince = now()
                            it.poke()
                        }
                        if (!body.optBoolean("poke")) return
                    }
                    if (body.optBoolean("abort")) {   // 发送方失败 / 取消了：正在等分段的接收马上结束
                        live[mid]?.takeIf { it.from == null || it.from == from }?.let {
                            it.aborted = true
                            it.poke()
                        }
                        return
                    }
                    if (body.optBoolean("poke")) {   // 发送方说：本机刚上线，去网盘看一下有没有已经传好的分段
                        live[mid]?.takeIf { it.from == null || it.from == from }?.let {
                            it.optimistic = true
                            it.poke()
                        }
                        return
                    }
                    val p = body.optInt("p", 0)
                    if (p > 0) {   // 第 p 段传完了
                        live[mid]?.takeIf { it.from == null || it.from == from }?.let {
                            it.announced.add(p)
                            it.poke()
                        }
                        return
                    }
                    if (!midPattern.matches(mid) || doneMids.contains(mid)) return
                    // 先登记再启动接收：紧跟在后面的分段通知不会因为接收还没开始而丢掉
                    live.computeIfAbsent(mid) { Live(from) }
                    Hub.scope.launch { receive(from, mid) }
                }
                "sent" -> acks.remove(j.optString("mid"))?.complete(j.optBoolean("online"))
                "error" -> acks.remove(j.optString("mid"))
                    ?.completeExceptionally(IOException("Cloudflare 没有转发这条通知：" + j.optString("error")))
            }
        } catch (e: Exception) {
            Hub.log("远程中转消息", e)
        }
    }

    // ---------- 设置页测试 ----------

    /** 测试 Cloudflare：地址通不通、口令对不对。失败抛出带原因的异常 */
    fun testWorker() {
        val raw = Store.relayUrl.trim().trimEnd('/')
        if (raw.isEmpty()) throw IOException("还没填 Cloudflare 地址")
        if (Store.relaySecret.isEmpty()) throw IOException("还没填中转口令")
        UrlRules.relayError(raw)?.let { throw IOException(it) }
        val base = if (raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
        val req = try {
            Request.Builder().url("$base/test").header("X-Relay-Secret", Store.relaySecret).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Cloudflare 地址格式不对：$raw", e)
        }
        val resp = try {
            Http.client.newCall(req).execute()
        } catch (e: IOException) {
            throw IOException(netHint(e) ?: throw e, e)
        }
        resp.use { r ->
            when (r.code) {
                200 -> Unit
                401 -> throw IOException("口令不对（401）：手机里的“中转口令”要和部署时设置的 RELAY_SECRET 完全一样")
                404 -> throw IOException("Cloudflare 返回 404：地址可能填错了，或者中转程序还没有部署")
                else -> throw IOException("Cloudflare 返回了 HTTP ${r.code}：" + (r.body?.string()?.take(200) ?: ""))
            }
        }
    }

    // ---------- 发送 ----------

    /** 通过坚果云 + Cloudflare 发给 peer。header 和直连时一样（type=text / file …） */
    suspend fun send(peer: Peer, header: JSONObject, size: Long, open: (() -> InputStream)?, onProgress: (Long) -> Unit, ctl: TransferCtl? = null) {
        notReadyReason(peer)?.let { throw IOException(it) }
        val s = sock
        if (s == null || !connected.value) throw IOException("没有连上 Cloudflare 中转服务器：" + state.value)
        val c = Store.dav ?: throw IOException("还没填${Store.davName}账号")
        val mid = newId()
        val psz = partSize(size)
        if (open != null && size > psz) {
            sendParts(peer, header, size, open, onProgress, c, mid, psz, ctl)
            return
        }
        ctl?.check()
        val meta = JSONObject(header.toString()).put("mid", mid).put("from", Store.deviceId).put("ts", now())
            .toString().toByteArray(Charsets.UTF_8)
        val key = RelayCrypto.key(peer.rk)
        val total = RelayCrypto.encryptedSize(meta.size, size)
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                try {
                    WebDav.ensureDir(c, peer.id)
                    WebDav.put(c, "${peer.id}/$mid.bin", total, ctl) { out ->
                        val ins = open?.invoke()
                        try {
                            RelayCrypto.encrypt(key, meta, ins, out, onProgress)
                        } finally {
                            ins?.close()
                        }
                    }
                    break
                } catch (e: WebDav.DavException) {
                    // 网盘上的文件夹被删了：缓存作废，重建后重试一次
                    if (attempt++ == 0 && (e.code == 404 || e.code == 409)) {
                        WebDav.forgetDirs()
                        continue
                    }
                    throw e
                }
            }
        }
        DavQuota.addUp(total)
        try {
            ctl?.check()
        } catch (e: UserCancelException) {
            quietDelete(c, "${peer.id}/$mid.bin")   // 上传完的那一刻点了取消：别把文件留在网盘里
            throw e
        }
        val ack = CompletableDeferred<Boolean>()
        acks[mid] = ack
        try {
            if (!s.send(notifyMsg(peer.id, JSONObject().put("mid", mid)))) throw IOException("内容已上传到${Store.davName}，但通知没发出去：和 Cloudflare 的连接刚刚断开")
            withTimeout(15_000) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("内容已上传到${Store.davName}，但 Cloudflare 15 秒内没有确认通知已发出", e)
        } finally {
            acks.remove(mid)
        }
        reportUsage(peer.id, mid)
    }

    // ---------- 大文件：分段并行上传 ----------

    /**
     * 文件 = 第 0 段（$mid.bin，带消息头）+ 第 1.. 段（$mid-N.prt），每段是一个独立的加密文件，各自带一个被校验的小消息头
     * （消息编号 / 发送者 / 第几段 / 这段多长），所以分段不能被调换、混进别的消息、截断或少收。
     * 几段同时上传；第 0 段传完先通知对方开始收，之后每传完一段再通知一次
     */
    private suspend fun sendParts(
        peer: Peer, header: JSONObject, size: Long, open: () -> InputStream, onProgress: (Long) -> Unit,
        c: DavConfig, mid: String, psz: Long, ctl: TransferCtl?
    ) {
        val n = ((size + psz - 1) / psz).toInt()
        val headMeta = JSONObject(header.toString()).put("mid", mid).put("from", Store.deviceId).put("ts", now())
            .put("parts", n).put("psz", psz).toString().toByteArray(Charsets.UTF_8)
        val key = RelayCrypto.key(peer.rk)
        val sent = AtomicLong(0)
        val lock = Any()
        val uploaded = ConcurrentHashMap.newKeySet<Int>()
        val headSent = CompletableDeferred<Unit>()
        val headNotified = AtomicBoolean(false)
        val par = Store.relayParallel
        if (ctl != null) {
            ctl.canPause = true
            sending[mid] = Outgoing(peer.id, ctl)
        }
        try {
            withContext(Dispatchers.IO) {
                ctl?.check()
                WebDav.ensureDir(c, peer.id)
                coroutineScope {
                    // 对方在传输中途上线：它错过了之前的分段通知，发一条“去网盘看一下”（等在线名单变化，不轮询）
                    val watcher = launch {
                        var was = peer.id in online.value
                        online.collect { set ->
                            val now = peer.id in set
                            if (now && !was && headNotified.get()) {
                                try {
                                    sendNotify(peer.id, JSONObject().put("mid", mid).put("poke", true).put("pause", ctl?.paused == true))
                                } catch (e: Exception) {
                                    Hub.log("Relay", e)
                                }
                            }
                            was = now
                        }
                    }
                    // 本机点了暂停 / 继续：告诉对方，它就不会把“没有新分段”当成超时
                    val pauseWatcher = launch {
                        if (ctl == null) return@launch
                        var last = TransferCtl.RUN
                        ctl.state.collect { st ->
                            if (st != last && st != TransferCtl.CANCELED) {
                                last = st
                                if (headNotified.get() && peer.id in online.value) {
                                    try {
                                        sendNotify(peer.id, JSONObject().put("mid", mid).put("pause", st == TransferCtl.PAUSED))
                                    } catch (e: Exception) {
                                        Hub.log("Relay", e)
                                    }
                                }
                            }
                        }
                    }
                    coroutineScope {
                        val next = AtomicInteger(0)
                        repeat(minOf(par, n)) {
                            launch {
                                while (true) {
                                    ctl?.gate()   // 暂停中就停在这里，不开始新的分段；已经在传的会传完
                                    val i = next.getAndIncrement()
                                    if (i >= n) break
                                    // 坚果云额度快满就在这里自动暂停（只拦新的一段，已经在传的传完）
                                    DavQuota.ensureRoom(ctl, minOf(psz, size - i * psz), 0L, 1)
                                    uploadPart(c, peer, mid, key, headMeta, i, psz, size, open, sent, lock, onProgress, ctl)
                                    uploaded.add(i)
                                    if (i == 0) {
                                        headNotified.set(true)
                                        notifyHead(peer, mid)
                                        headSent.complete(Unit)
                                    } else {
                                        headSent.await()   // 对方先收到第 0 段的通知，再收到后面各段的
                                        // 对方不在线就不发：Cloudflare 会把每条通知都存起来（每台设备最多 200 条），
                                        // 几十条分段通知会挤掉别的消息；对方上线后自己扫描网盘就能收到已传好的分段
                                        if (peer.id in online.value) sendNotify(peer.id, JSONObject().put("mid", mid).put("p", i))
                                    }
                                }
                            }
                        }
                    }
                    watcher.cancel()
                    pauseWatcher.cancel()
                }
            }
        } catch (e: Throwable) {
            // 失败或被取消：通知对方别再等了，并把已经传上去的分段删掉
            withContext(NonCancellable + Dispatchers.IO) {
                if (headNotified.get() && peer.id in online.value && ctl?.byPeer != true) {
                    try {
                        sock?.send(notifyMsg(peer.id, JSONObject().put("mid", mid).put("abort", true)))
                    } catch (x: Exception) {
                        Hub.log("Relay", x)
                    }
                }
                for (i in uploaded) quietDelete(c, "${peer.id}/" + if (i == 0) "$mid.bin" else "$mid-$i.prt")
            }
            throw e
        } finally {
            sending.remove(mid)
        }
    }

    private suspend fun uploadPart(
        c: DavConfig, peer: Peer, mid: String, key: SecretKeySpec, headMeta: ByteArray,
        i: Int, psz: Long, size: Long, open: () -> InputStream,
        sent: AtomicLong, lock: Any, onProgress: (Long) -> Unit, ctl: TransferCtl?
    ) {
        val plain = minOf(psz, size - i * psz)
        val meta = if (i == 0) headMeta else JSONObject().put("mid", mid).put("from", Store.deviceId)
            .put("idx", i).put("len", plain).toString().toByteArray(Charsets.UTF_8)
        val total = RelayCrypto.encryptedSize(meta.size, plain)
        val name = if (i == 0) "${peer.id}/$mid.bin" else "${peer.id}/$mid-$i.prt"
        var attempt = 0
        while (true) {
            var last = 0L
            try {
                WebDav.put(c, name, total, ctl) { out ->
                    val ins = open()
                    try {
                        skipFully(ins, i * psz)
                        RelayCrypto.encrypt(key, meta, ins, out, onProgress = { v ->
                            synchronized(lock) {
                                sent.addAndGet(v - last)
                                last = v
                                onProgress(sent.get())
                            }
                        }, limit = plain)
                    } finally {
                        ins.close()
                    }
                }
                DavQuota.addUp(total)
                return
            } catch (e: WebDav.DavException) {
                ctl?.check()   // 取消造成的失败不要重试
                synchronized(lock) { sent.addAndGet(-last); onProgress(sent.get()) }   // 这一段要重传，进度退回去
                val folderGone = e.code == 404 || e.code == 409
                val busy = e.code == 429 || e.code in 500..599
                if (attempt++ < 2 && (folderGone || busy)) {
                    if (folderGone) {
                        WebDav.forgetDirs()
                        WebDav.ensureDir(c, peer.id)
                    } else {
                        delay(2000L * attempt)
                    }
                    continue
                }
                throw e
            } catch (e: IOException) {
                ctl?.check()   // 取消中断了正在传的请求：不要重试
                synchronized(lock) { sent.addAndGet(-last); onProgress(sent.get()) }
                if (attempt++ < 2) {
                    delay(1500L * attempt)
                    ctl?.check()
                    continue
                }
                throw e
            }
        }
    }

    /** 跳过流的前 n 个字节（有的流 skip 可能一次跳不完，甚至返回 0） */
    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = ins.skip(left)
            if (k > 0) {
                left -= k
            } else {
                if (ins.read() < 0) throw IOException("文件读取不完整")
                left--
            }
        }
    }

    /** 第 0 段传完后的通知：和单文件一样，等 Cloudflare 确认 */
    private suspend fun notifyHead(peer: Peer, mid: String) {
        val ack = CompletableDeferred<Boolean>()
        acks[mid] = ack
        try {
            sendNotify(peer.id, JSONObject().put("mid", mid))
            withTimeout(15_000) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("内容已上传到${Store.davName}，但 Cloudflare 15 秒内没有确认通知已发出", e)
        } finally {
            acks.remove(mid)
        }
    }

    /** 发一条通知。刚好断线的话，等它重连上（等连接状态变化，不是轮询）最多 20 秒再发一次 */
    /** 通知的内容：带上本机的坚果云用量，对方才能算出账号的总用量 */
    private fun notifyMsg(to: String, body: JSONObject): String {
        DavQuota.report()?.let { body.put("q", it) }
        return JSONObject().put("op", "notify").put("to", to).put("body", body).toString()
    }

    /** 传完 / 收完后，把本机最新的用量告诉对方（对方在线才发） */
    private suspend fun reportUsage(peerId: String, mid: String) {
        if (Store.davProvider != "jgy" || peerId !in online.value) return
        try {
            sendNotify(peerId, JSONObject().put("mid", mid).put("qonly", true))
        } catch (e: Exception) {
            Hub.log("Relay", e)
        }
    }

    private suspend fun sendNotify(to: String, body: JSONObject) {
        val msg = notifyMsg(to, body)
        var tries = 0
        while (true) {
            val s = sock
            if (s != null && connected.value && s.send(msg)) return
            if (tries++ >= 1) throw IOException("内容已上传到${Store.davName}，但通知没发出去：和 Cloudflare 的连接断开了")
            withTimeoutOrNull(20_000) { connected.first { it } }
        }
    }

    private fun quietDelete(c: DavConfig, name: String) {
        try {
            WebDav.delete(c, name)
        } catch (e: WebDav.DavException) {
            if (e.code != 404) Hub.log("删除中转文件", e)
        } catch (e: Exception) {
            Hub.log("删除中转文件", e)
        }
    }

    // ---------- 接收 ----------

    /** 连上 Cloudflare 后，把坚果云里留给本机的内容都收一遍（对方发的时候本机可能不在线） */
    private suspend fun sweepInbox() = withContext(Dispatchers.IO) {
        try {
            val c = Store.dav ?: return@withContext
            for (n in WebDav.listBins(c, Store.deviceId)) receive(null, n.removeSuffix(".bin"))
        } catch (e: Exception) {
            Hub.reportOnce("relay-sweep", "检查${Store.davName}里的未收消息失败", friendlyError(e), techDetail(e))
        }
    }

    /** 收一条：fromHint 是通知里的发送者（扫描网盘时未知，null）。从坚果云下载、解密、记入聊天、删除 */
    private suspend fun receive(fromHint: String?, mid: String) = withContext(Dispatchers.IO) {
        if (!midPattern.matches(mid)) return@withContext
        if (doneMids.contains(mid)) {
            live.remove(mid)
            return@withContext
        }
        if (!processing.add(mid)) return@withContext
        val lv = live.computeIfAbsent(mid) { Live(fromHint).also { l -> l.optimistic = fromHint == null } }
        var multi: Multi? = null
        try {
            val c = Store.dav ?: throw IOException("还没填${Store.davName}账号，收不到远程发来的内容")
            val peers = Hub.peers.value.values.filter {
                it.paired && it.rk.isNotEmpty() && (fromHint == null || it.id == fromHint)
            }
            if (peers.isEmpty()) {
                Hub.log("远程中转", IOException("收到来自 ${fromHint ?: "未知设备"} 的内容，但没有对应的已配对设备或加密密钥，已忽略"))
                markDone(mid)
                return@withContext
            }
            val path = "${Store.deviceId}/$mid.bin"
            try {
                WebDav.get(c, path) { ins ->
                    val cin = CountingInputStream(ins)
                    multi = handleStream(mid, peers, cin, lv)
                    DavQuota.addDown(cin.count)
                }
            } catch (e: WebDav.DavException) {
                if (e.code == 404) {
                    markDone(mid) // 已经收过并删除了
                    return@withContext
                }
                throw e
            }
            // 分段文件：第 0 段（上面）收完后，继续收后面的分段
            multi?.let { finishMulti(c, it, lv) }
            markDone(mid)
            multi?.let { deleteParts(c, mid, it.n) }
            try { WebDav.delete(c, path) } catch (e: Exception) { Hub.log("删除已收到的中转文件", e) }
            lv.peerId?.let { reportUsage(it, mid) }
        } catch (e: NoKeyException) {
            markDone(mid)
            if (fromHint == null) {
                // 扫描网盘时已经试过本机所有已配对设备的密钥，第一块完整数据都解不开：多半是重新配对前留下的旧内容，
                // 旧密钥已经没了，以后也不可能解开。删掉它，免得每次重连都再扫到、再弹一次框；只记入错误记录，不打扰
                Hub.log("远程中转：网盘里有一条解不开的旧内容，已删除（$mid）", e)
                try {
                    Store.dav?.let { WebDav.delete(it, "${Store.deviceId}/$mid.bin") }
                } catch (d: Exception) {
                    Hub.log("删除解不开的中转文件", d)
                }
            } else {
                // 收到的是对方刚发来的通知，对方等着这条消息：仍然提示，但不删文件（下次扫描网盘时再判断）
                Hub.report("远程接收失败", e, "消息：$mid")
            }
        } catch (e: UserCancelException) {
            // 本机点了取消：把网盘上这条消息的残留清掉，并告诉发送方别再传了
            markDone(mid)
            val m = multi
            if (m != null) {
                Store.dav?.let { d ->
                    deleteParts(d, mid, m.n)
                    quietDelete(d, "${Store.deviceId}/$mid.bin")
                }
                if (m.peer.id in online.value) {
                    try {
                        sendNotify(m.peer.id, JSONObject().put("mid", mid).put("cancel", true))
                    } catch (x: Exception) {
                        Hub.log("Relay", x)
                    }
                }
            }
        } catch (e: PeerGoneException) {
            markDone(mid)
            Hub.log("远程接收", e)   // 聊天里那条消息已经标成失败并写明了原因，不再弹框
            if (!e.aborted) {
                // 对方很久没有继续上传：把已经传上来的残留清掉，免得每次重连都再扫到
                Store.dav?.let { d ->
                    deleteParts(d, mid, e.n)
                    quietDelete(d, "${Store.deviceId}/$mid.bin")
                }
            }
        } catch (e: StaleException) {
            markDone(mid)
            Hub.report("远程接收：已丢弃过旧的消息", e, "消息：$mid")
            try { Store.dav?.let { WebDav.delete(it, "${Store.deviceId}/$mid.bin") } } catch (d: Exception) { Hub.log("删除过旧的中转文件", d) }
        } catch (e: Exception) {
            markDone(mid) // 失败的也不要在本次运行里反复重试
            Hub.report("远程接收失败", e, "消息：$mid")
        } finally {
            multi?.let { Transfers.end(it.job.id, it.ctl) }
            processing.remove(mid)
            live.remove(mid)
        }
    }

    /** 所有候选密钥都解不开消息头（和下载中断、文件损坏这类错误区分开：那些不会走到这里） */
    private class NoKeyException(msg: String) : IOException(msg)

    /** 消息太旧：不重试，也不留在网盘里 */
    private class StaleException(msg: String) : IOException(msg)

    private fun handleStream(mid: String, peers: List<Peer>, ins: InputStream, lv: Live): Multi? {
        val reader = RelayCrypto.Reader(ins)
        val keys = peers.map { RelayCrypto.key(it.rk) }
        val (idx, metaBytes) = reader.open(keys)
            ?: throw NoKeyException("无法解密：密钥对不上（对方用的不是和本机交换过的密钥，或者这不是发给本机的内容）")
        val peer = peers[idx]
        lv.peerId = peer.id
        val meta = JSONObject(String(metaBytes, Charsets.UTF_8))
        if (meta.optString("from") != peer.id) throw IOException("来源校验失败：消息里的发送者和密钥对应的设备不一致")
        // 旧版本发的没有 ts，照常收；有 ts 但太旧的，当作被重放的旧文件
        val ts = meta.optLong("ts", 0L)
        if (ts > 0 && now() - ts > MAX_AGE_MS) throw StaleException("这条远程消息是 30 天前发的，本机已经不收这么旧的内容（可能是被人重新放回网盘的旧文件）")
        return when (val type = meta.getString("type")) {
            "text" -> {
                val text = meta.getString("text")
                if (text.length > Server.MAX_TEXT_LEN) throw IOException("文字太长")
                Inbox.addText(peer.id, text)
                null
            }
            "file" -> receiveFile(mid, peer, keys[idx], meta, reader)
            else -> throw IOException("不支持的消息类型：$type")
        }
    }

    /** 单文件收完返回 null；分段文件只收完第 0 段，返回还要继续收的状态 */
    private fun receiveFile(mid: String, peer: Peer, key: SecretKeySpec, meta: JSONObject, reader: RelayCrypto.Reader): Multi? {
        val job = Inbox.beginFile(peer.id, meta)
        try {
            val parts = meta.optInt("parts", 1)
            val psz = meta.optLong("psz", 0L)
            if (parts > 1) {
                if (meta.optString("mid") != mid) throw IOException("消息编号校验失败：文件名和消息里的编号对不上")
                if (psz < RelayCrypto.CHUNK || psz > MAX_PART || parts.toLong() != (job.size + psz - 1) / psz) {
                    throw IOException("分段信息无效")
                }
            }
            val want = if (parts > 1) psz else job.size   // 这个文件里应该有多少数据
            var got = 0L
            var last = 0L
            val os = job.saved.stream
            while (got < want) {
                val f = reader.next()
                    ?: throw IOException("远程文件不完整：中途就结束了（已收到 ${fmtSize(got)} / ${fmtSize(job.size)}）")
                if (got + f.size > want) throw IOException("数据与声明的大小不符")
                os.write(f)
                got += f.size
                val t = now()
                if (t - last > 120) {
                    last = t
                    Inbox.progress(job, got)
                }
            }
            if (parts > 1) {
                val ctl = Transfers.begin(job.id)
                ctl.canPause = true
                return Multi(peer, key, mid, job, parts, psz, ctl).also { it.got = got }
            }
            os.close()
            Inbox.finishFile(peer.id, job)
            return null
        } catch (e: Exception) {
            try { job.saved.stream.close() } catch (x: Exception) { Hub.log("Relay", x) }
            Inbox.failFile(job, e)
            throw e
        }
    }

    /**
     * 继续收第 1.. 段：同时下载几段（设置里的并行数），按顺序解密写进文件。
     * 某一段还没传上来就等对方的分段通知（或重连 / 取消 / 超时），不轮询
     */
    private suspend fun finishMulti(c: DavConfig, m: Multi, lv: Live) {
        val ctl = m.ctl
        val par = Store.relayParallel
        ctl.onCancel = { lv.poke() }   // 取消时唤醒正在等分段通知的下载
        try {
            ctl.check()
            coroutineScope {
                val jobs = ArrayList<Deferred<ByteArray>>()
                var launched = 1
                fun launchNext() {
                    if (launched < m.n) {
                        val i = launched++
                        jobs.add(async(Dispatchers.IO) {
                            ctl.gate()   // 暂停中就不开始下载新的分段
                            DavQuota.ensureRoom(ctl, 0L, m.psz, 2)   // 坚果云额度快满就自动暂停；2 = 下载 1 次 + 收完后删除 1 次
                            fetchPart(c, m, i, lv)
                        })
                    }
                }
                repeat(par) { launchNext() }
                for (i in 1 until m.n) {
                    val raw = jobs[i - 1].await()
                    launchNext()
                    consumePart(raw, i, m)
                }
            }
            m.job.saved.stream.close()
            if (m.got != m.job.size) throw IOException("远程文件大小对不上（收到 ${fmtSize(m.got)} / ${fmtSize(m.job.size)}）")
            Inbox.finishFile(m.peer.id, m.job)
        } catch (e: Exception) {
            try { m.job.saved.stream.close() } catch (x: Exception) { Hub.log("Relay", x) }
            if (ctl.cancelled) {
                Inbox.cancelFile(m.job)
                throw UserCancelException()
            }
            Inbox.failFile(m.job, e)
            throw e
        }
    }

    private suspend fun fetchPart(c: DavConfig, m: Multi, i: Int, lv: Live): ByteArray {
        val name = "${Store.deviceId}/${m.mid}-$i.prt"
        val max = m.psz + (m.psz / RelayCrypto.CHUNK + 2) * 20 + 4096
        fun tryGet(): ByteArray? = try {
            WebDav.getBytes(c, name, max, m.ctl).also { DavQuota.addDown(it.size.toLong()) }
        } catch (e: WebDav.DavException) {
            if (e.code == 404) null else throw e
        }
        while (true) {
            m.ctl.gate()
            if (lv.aborted) throw PeerGoneException("对方中止了发送（发送方出错或取消了）", true, m.n)
            val seen = lv.tick.value   // 先记下，再去看：看的时候来的通知不会漏掉
            if (lv.optimistic || i in lv.announced) tryGet()?.let { return it }
            if (withTimeoutOrNull(PART_WAIT_MS) { lv.tick.first { it != seen } } == null) {
                if (m.ctl.paused) continue   // 本机暂停中：不算对方超时，继续后再等
                if (lv.senderPaused && now() - lv.pausedSince < SENDER_PAUSE_MAX_MS) continue   // 对方暂停了
                tryGet()?.let { return it }
                val why = if (lv.senderPaused) "对方暂停发送后超过 ${SENDER_PAUSE_MAX_MS / 60000} 分钟没有继续" else "对方 ${PART_WAIT_MS / 1000} 秒没有继续上传"
                throw PeerGoneException("$why（第 ${i + 1} / ${m.n} 段）", false, m.n)
            }
        }
    }

    private fun consumePart(raw: ByteArray, i: Int, m: Multi) {
        val reader = RelayCrypto.Reader(ByteArrayInputStream(raw))
        val (_, metaBytes) = reader.open(listOf(m.key)) ?: throw IOException("第 ${i + 1} 段解不开：密钥对不上")
        val pm = JSONObject(String(metaBytes, Charsets.UTF_8))
        val want = minOf(m.psz, m.job.size - i * m.psz)
        if (pm.optString("mid") != m.mid || pm.optString("from") != m.peer.id ||
            pm.optInt("idx", -1) != i || pm.optLong("len", -1L) != want
        ) throw IOException("第 ${i + 1} 段校验失败：不是这条消息的分段，或者被调换过")
        val os = m.job.saved.stream
        var got = 0L
        while (got < want) {
            val f = reader.next() ?: throw IOException("第 ${i + 1} 段不完整：中途就结束了")
            if (got + f.size > want) throw IOException("第 ${i + 1} 段的数据与声明的大小不符")
            os.write(f)
            got += f.size
            m.got += f.size
            val t = now()
            if (t - m.last > 120) {
                m.last = t
                Inbox.progress(m.job, m.got)
            }
        }
        if (reader.next() != null) throw IOException("第 ${i + 1} 段的数据比声明的多")
    }

    private suspend fun deleteParts(c: DavConfig, mid: String, n: Int) {
        for (i in 1 until n) {
            DavQuota.awaitRequests(1)   // 请求次数快满就等一等再删，别因为清理把额度用光
            quietDelete(c, "${Store.deviceId}/$mid-$i.prt")
        }
    }
}
