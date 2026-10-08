package com.hulian.transfer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程中转：两台手机不在同一个网络、也连不上热点时用。
 *
 *  - Cloudflare Worker：只负责“谁在线”和“有新消息”的小通知（WebSocket 长连接，对方上线 / 来消息都是推送，不用轮询）
 *  - 坚果云 WebDAV：存放加密后的内容。发送方上传到“对方的文件夹”，对方收到通知后下载、解密、删除
 *
 * 内容在手机上用配对时交换的密钥加密（见 RelayCrypto），网盘和 Cloudflare 都看不到。
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
    private val doneMids = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val midPattern = Regex("[A-Za-z0-9-]{1,64}")

    private fun markDone(mid: String) {
        synchronized(doneMids) {
            doneMids.add(mid)
            if (doneMids.size > 500) doneMids.remove(doneMids.first())
        }
    }

    fun configured() = Store.relayOn && Store.relayUrl.isNotBlank() && Store.relaySecret.isNotBlank()

    /** 不能用远程中转的原因；null 表示可以用 */
    fun notReadyReason(p: Peer?): String? = when {
        !Store.relayOn -> "远程中转没有启用（设置 → 远程中转）"
        Store.relayUrl.isBlank() || Store.relaySecret.isBlank() -> "远程中转还没填 Cloudflare 地址或口令"
        Store.dav == null -> "远程中转还没填坚果云账号"
        p != null && p.rk.isEmpty() -> "这台设备还没有和本机交换过加密密钥（两台手机需要同时在线、直连一次，或者重新扫码配对）"
        else -> null
    }

    fun ready(p: Peer) = notReadyReason(p) == null

    /** 远程中转还在出结果：正在连接，或者已连上但还没收到“谁在线”名单。自动连接热点前先等它一小会儿（远程优先） */
    fun settling() = configured() && (state.value.startsWith("正在连接") || (connected.value && !presence.value))

    // ---------- 连接 ----------

    fun start() {
        if (job?.isActive == true) return
        restart()
    }

    fun restart() {
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

    /** 网络变了：别等退避时间，马上重连 */
    fun kick() {
        wake.trySend(Unit)
    }

    private fun wsUrl(): String {
        val u = Store.relayUrl.trim().trimEnd('/')
        val base = when {
            u.startsWith("https://") -> "wss://" + u.removePrefix("https://")
            u.startsWith("http://") -> "ws://" + u.removePrefix("http://")
            u.startsWith("wss://") || u.startsWith("ws://") -> u
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
            return@coroutineScope "Cloudflare 地址格式不对：" + Store.relayUrl
        }
        val s = Http.ws.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.value = true
                backoff = 2000L
                state.value = if (Store.dav == null) "已连接（但坚果云账号没填，收不到内容）" else "已连接"
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
                    val mid = j.getJSONObject("body").getString("mid")
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
        val base = if (raw.startsWith("http")) raw else "https://$raw"
        val req = try {
            Request.Builder().url("$base/test").header("X-Relay-Secret", Store.relaySecret).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Cloudflare 地址格式不对：$raw", e)
        }
        Http.client.newCall(req).execute().use { r ->
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
    suspend fun send(peer: Peer, header: JSONObject, size: Long, open: (() -> InputStream)?, onProgress: (Long) -> Unit) {
        notReadyReason(peer)?.let { throw IOException(it) }
        val s = sock
        if (s == null || !connected.value) throw IOException("没有连上 Cloudflare 中转服务器：" + state.value)
        val c = Store.dav ?: throw IOException("还没填坚果云账号")
        val mid = newId()
        val meta = JSONObject(header.toString()).put("mid", mid).put("from", Store.deviceId)
            .toString().toByteArray(Charsets.UTF_8)
        val key = RelayCrypto.key(peer.rk)
        val total = RelayCrypto.encryptedSize(meta.size, size)
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                try {
                    WebDav.ensureDir(c, peer.id)
                    WebDav.put(c, "${peer.id}/$mid.bin", total) { out ->
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
        val ack = CompletableDeferred<Boolean>()
        acks[mid] = ack
        try {
            val msg = JSONObject().put("op", "notify").put("to", peer.id).put("body", JSONObject().put("mid", mid))
            if (!s.send(msg.toString())) throw IOException("内容已上传到坚果云，但通知没发出去：和 Cloudflare 的连接刚刚断开")
            withTimeout(15_000) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("内容已上传到坚果云，但 Cloudflare 15 秒内没有确认通知已发出", e)
        } finally {
            acks.remove(mid)
        }
    }

    // ---------- 接收 ----------

    /** 连上 Cloudflare 后，把坚果云里留给本机的内容都收一遍（对方发的时候本机可能不在线） */
    private suspend fun sweepInbox() = withContext(Dispatchers.IO) {
        try {
            val c = Store.dav ?: return@withContext
            for (n in WebDav.listBins(c, Store.deviceId)) receive(null, n.removeSuffix(".bin"))
        } catch (e: Exception) {
            Hub.reportOnce("relay-sweep", "检查坚果云里的未收消息失败", friendlyError(e), techDetail(e))
        }
    }

    /** 收一条：fromHint 是通知里的发送者（扫描网盘时未知，null）。从坚果云下载、解密、记入聊天、删除 */
    private suspend fun receive(fromHint: String?, mid: String) = withContext(Dispatchers.IO) {
        if (!midPattern.matches(mid)) return@withContext
        if (doneMids.contains(mid) || !processing.add(mid)) return@withContext
        try {
            val c = Store.dav ?: throw IOException("还没填坚果云账号，收不到远程发来的内容")
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
                WebDav.get(c, path) { ins -> handleStream(peers, ins) }
            } catch (e: WebDav.DavException) {
                if (e.code == 404) {
                    markDone(mid) // 已经收过并删除了
                    return@withContext
                }
                throw e
            }
            markDone(mid)
            try { WebDav.delete(c, path) } catch (e: Exception) { Hub.log("删除已收到的中转文件", e) }
        } catch (e: Exception) {
            markDone(mid) // 失败的也不要在本次运行里反复重试
            Hub.report("远程接收失败", e, "消息：$mid")
        } finally {
            processing.remove(mid)
        }
    }

    private fun handleStream(peers: List<Peer>, ins: InputStream) {
        val reader = RelayCrypto.Reader(ins)
        val (idx, metaBytes) = reader.open(peers.map { RelayCrypto.key(it.rk) })
            ?: throw IOException("无法解密：密钥对不上（对方用的不是和本机交换过的密钥，或者这不是发给本机的内容）")
        val peer = peers[idx]
        val meta = JSONObject(String(metaBytes, Charsets.UTF_8))
        if (meta.optString("from") != peer.id) throw IOException("来源校验失败：消息里的发送者和密钥对应的设备不一致")
        when (val type = meta.getString("type")) {
            "text" -> {
                val text = meta.getString("text")
                if (text.length > Server.MAX_TEXT_LEN) throw IOException("文字太长")
                Inbox.addText(peer.id, text)
            }
            "file" -> receiveFile(peer, meta, reader)
            else -> throw IOException("不支持的消息类型：$type")
        }
    }

    private fun receiveFile(peer: Peer, meta: JSONObject, reader: RelayCrypto.Reader) {
        val job = Inbox.beginFile(peer.id, meta)
        try {
            var got = 0L
            var last = 0L
            job.saved.stream.use { os ->
                while (got < job.size) {
                    val f = reader.next()
                        ?: throw IOException("远程文件不完整：中途就结束了（已收到 ${fmtSize(got)} / ${fmtSize(job.size)}）")
                    if (got + f.size > job.size) throw IOException("数据与声明的大小不符")
                    os.write(f)
                    got += f.size
                    val t = now()
                    if (t - last > 120) {
                        last = t
                        Inbox.progress(job, got)
                    }
                }
            }
            Inbox.finishFile(peer.id, job)
        } catch (e: Exception) {
            Inbox.failFile(job, e)
            throw e
        }
    }
}
