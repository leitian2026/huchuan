package com.hulian.transfer

import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * 传输协议：TLS 1.3 双向认证（只接受已配对设备的证书，见 Tls.java），连接上一次传一条消息：
 *   加密通道建立 → [头 JSON] → 对方应答 [1=可以发 | 0+原因=拒绝]
 *   → （仅文件）[文件内容，分帧] → （仅文件）对方应答 [1=成功]
 * 头：{v, dev, devName, port, type(ping|text|file|pair), ...}
 */
object Net {
    class PairResult(val name: String, val fp: String)

    private fun headerBytes(header: JSONObject): ByteArray {
        header.put("v", 3).put("dev", Store.deviceId).put("devName", Store.deviceName).put("port", Hub.port)
        return header.toString().toByteArray(Charsets.UTF_8)
    }

    /** 对方的应答帧：第一个字节 1 表示同意/成功，0 表示拒绝，后面是原因 */
    private fun expectOk(b: ByteArray) {
        if (b.isNotEmpty() && b[0] == 1.toByte()) return
        throw IOException(if (b.size > 1) String(b, 1, b.size - 1, Charsets.UTF_8) else "对方拒绝接收")
    }

    /** 连到已配对的设备：只接受配对时记下的那张证书，冒充不了 */
    private fun connectTo(p: Peer, connectMs: Int, handshakeMs: Int): SSLSocket {
        if (!p.paired) throw IOException("尚未配对，请扫码重新配对")
        return Tls.connect(
            InetSocketAddress(InetAddress.getByName(p.host), p.port), connectMs, handshakeMs, Identity.tls, p.fp
        )
    }

    fun send(peer: Peer, header: JSONObject, open: (() -> InputStream)?, onProgress: (Long) -> Unit) {
        val s = connectTo(peer, 6000, 10000)
        try {
            s.soTimeout = 60000
            val ch = Wire(s.getInputStream(), s.getOutputStream())
            ch.write(headerBytes(header))
            ch.flush()
            // 对方先表态：存储空间不足等会在这里就被拒绝，不用白传
            expectOk(ch.read())
            if (open != null) {
                open().use { ins ->
                    val buf = ByteArray(Wire.CHUNK)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        ch.write(buf, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
                ch.flush()
                expectOk(ch.read())
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    @Volatile
    private var pairSock: Socket? = null

    /** 用户在"等待对方确认"弹窗里点了取消：直接断开连接 */
    fun cancelPair() {
        try { pairSock?.close() } catch (_: Exception) {}
    }

    /**
     * 扫码配对（本机是扫码的一方）。二维码里有对方的证书指纹，本机直接固定它 —— 中间人冒充不了。
     * 连上后立刻通过 onCode 给出 6 位验证码（界面显示出来，让人和对方手机上的核对），
     * 然后等对方点"同意"。成功后双方各自记住对方的证书指纹。
     */
    fun pair(peerId: String, host: String, port: Int, token: String, hostFp: String, onCode: (String) -> Unit): PairResult {
        val tok = Secure.unb64(token)
        val s = try {
            Tls.connect(InetSocketAddress(InetAddress.getByName(host), port), 6000, 10000, Identity.tls, hostFp)
        } catch (e: SSLException) {
            throw IOException("连上的设备和二维码里的设备不一致，请重新扫码", e)
        }
        pairSock = s
        try {
            val ch = Wire(s.getInputStream(), s.getOutputStream())
            onCode(Tls.sas(tok, Identity.fp, hostFp))
            val msg = JSONObject().put("type", "pair")
                .put("mac", Secure.b64(Tls.pairMac(tok, Identity.fp, hostFp)))
            val reply = try {
                ch.write(headerBytes(msg))
                ch.flush()
                s.soTimeout = 70_000 // 等对方在它的手机上核对验证码并点"同意"
                JSONObject(String(ch.read(), Charsets.UTF_8))
            } catch (e: SSLException) {
                throw IOException("对方没有在等待配对（请让对方重新打开\u201c我的二维码\u201d页面再扫）", e)
            } catch (e: EOFException) {
                throw IOException("对方没有在等待配对（请让对方重新打开\u201c我的二维码\u201d页面再扫）", e)
            } catch (e: SocketException) {
                throw IOException("对方没有在等待配对（请让对方重新打开\u201c我的二维码\u201d页面再扫）", e)
            }
            if (!reply.optBoolean("ok", false)) throw IOException(reply.optString("reason", "对方拒绝了配对"))
            if (reply.optString("id") != peerId) throw IOException("设备不匹配")
            return PairResult(reply.optString("name"), hostFp)
        } finally {
            pairSock = null
            try { s.close() } catch (_: Exception) {}
        }
    }

    /** 轻量探测：能完成 TLS 握手（只有持有配对证书的设备能通过）并得到应答，才算在线 */
    fun ping(p: Peer): Boolean = try {
        if (!p.paired) {
            false
        } else {
            connectTo(p, 900, 2500).use { s ->
                s.soTimeout = 2500
                val ch = Wire(s.getInputStream(), s.getOutputStream())
                ch.write(headerBytes(JSONObject().put("type", "ping")))
                ch.flush()
                val r = ch.read()
                r.isNotEmpty() && r[0] == 1.toByte()
            }
        }
    } catch (e: Exception) {
        false
    }

    /** 通知对方“我退出对话框了”：尽力而为，失败（比如网已经断了）就算了 */
    fun bye(p: Peer) {
        try {
            if (!p.paired) return
            connectTo(p, 900, 2500).use { s ->
                s.soTimeout = 2500
                val ch = Wire(s.getInputStream(), s.getOutputStream())
                ch.write(headerBytes(JSONObject().put("type", "bye")))
                ch.flush()
                ch.read()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 本机当前所在的局域网 IPv4：优先 Wi-Fi 网卡(wlan*)，其次本机热点网卡(ap* / swlan* / softap*，
     * 本机开了个人热点时用)；忽略移动网络和 VPN 网卡。没有则返回 null（开着 VPN 也能正确判断）
     */
    fun wifiIp(): String? = try {
        fun rank(n: String): Int = when {
            n.startsWith("wlan") -> 0
            n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("ap") -> 1
            else -> -1
        }
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && rank(it.name) >= 0 }
            .sortedWith(compareBy({ rank(it.name) }, { it.name }))
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /** 系统里是否已经有热点在运行（手动开的 / 别的软件开的 / 本 app 的本地热点），看热点网卡是否有 IPv4 */
    fun apActive(): Boolean = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).any { n ->
            n.isUp && !n.isLoopback &&
                (n.name.startsWith("swlan") || n.name.startsWith("softap") || n.name.startsWith("ap")) &&
                Collections.list(n.inetAddresses).any { it is Inet4Address }
        }
    } catch (e: Exception) {
        false
    }

    fun localIps(): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filter { it is Inet4Address && !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }
}

/** 接收端保护：存储空间检查、文件名清理 */
object Space {
    /** 接收后至少还要给系统留这么多空间 */
    const val RESERVE = 100L * 1024 * 1024

    /** 默认保存位置所在存储的剩余字节；取不到返回 -1（用户自选了保存目录时无法可靠判断，交给写入失败处理） */
    @Suppress("DEPRECATION")
    fun available(): Long {
        if (Store.saveDir != null) return -1L
        return try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: Exception) {
            -1L
        }
    }

    /** 只保留纯文件名：去掉路径、控制字符、首尾的点和空格（在 Saver.sanitize 之前再加一道保险） */
    fun safeName(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { it >= ' ' && it != '\u007f' }.trim()
        n = n.trim('.', ' ')
        return n.ifEmpty { "file" }.take(200)
    }
}

object Server {
    private var ss: ServerSocket? = null

    /** 同时处理的连接数上限，防止有人狂开连接占满资源 */
    private val slots = Semaphore(16)

    private fun open(port: Int): ServerSocket {
        val s = Tls.server(Identity.tls, Hub.trust)
        s.receiveBufferSize = 1 shl 20
        s.bind(InetSocketAddress(port))
        return s
    }

    @Synchronized
    fun start() {
        if (ss?.isClosed == false) return
        val s = try { open(Store.port) } catch (e: Exception) { open(0) }
        ss = s
        Hub.port = s.localPort
        Store.port = s.localPort
        Hub.scope.launch {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                if (!slots.tryAcquire()) {
                    try { c.close() } catch (_: Exception) {}
                    continue
                }
                launch {
                    try { handle(c) } finally { slots.release() }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        try { ss?.close() } catch (_: Exception) {}
        ss = null
    }

    private fun ok(ch: Wire) {
        ch.write(byteArrayOf(1))
        ch.flush()
    }

    private fun no(ch: Wire, why: String) {
        ch.write(byteArrayOf(0) + why.toByteArray(Charsets.UTF_8))
        ch.flush()
    }

    private fun handle(c: Socket) {
        try {
            c.use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 8000 // 握手和读头必须在 8 秒内完成，防止有人只连不发、占住连接
                val ssl = s as SSLSocket
                ssl.startHandshake() // 对方证书不在信任列表里，握手就会失败
                val fp = Tls.peerFingerprint(ssl)
                val ch = Wire(s.getInputStream(), s.getOutputStream())
                val host = s.inetAddress.hostAddress ?: return
                val h = JSONObject(String(ch.read(), Charsets.UTF_8))
                val type = h.getString("type")
                if (type == "pair") {
                    handlePairing(ch, h, fp, host)
                    return
                }
                val peer = Hub.peerByFp(fp) ?: return // 身份由 TLS 证明，不是靠对方自己声称
                val pid = peer.id
                if (h.optString("dev") != pid) return
                s.soTimeout = 60000
                val port = h.optInt("port", 0)
                if (port in 1..65535) Hub.upsertPeer(pid, h.optString("devName"), host, port)
                when (type) {
                    "ping" -> ok(ch)
                    "bye" -> {
                        ok(ch)
                        Hub.peerLeft.tryEmit(pid) // 对方退出了对话框：本机如果正开着和它的对话框，也一起退出
                    }
                    "text" -> {
                        val text = h.getString("text")
                        if (text.length > MAX_TEXT_LEN) {
                            no(ch, "文字太长")
                        } else {
                            Hub.addMsg(
                                Msg(newId(), pid, false, Kind.TEXT, now(), text = text, read = Hub.isViewing(pid))
                            )
                            Notifier.message(pid, text)
                            ok(ch)
                        }
                    }
                    "file" -> receiveFile(pid, h, ch)
                    else -> no(ch, "不支持的消息类型")
                }
            }
        } catch (_: Exception) {
        }
    }

    private const val MAX_TEXT_LEN = 500_000

    private fun pairReply(ch: Wire, ok: Boolean, reason: String = "") {
        val j = JSONObject().put("ok", ok)
        if (ok) j.put("id", Store.deviceId).put("name", Store.deviceName).put("port", Hub.port)
        else j.put("reason", reason)
        ch.write(j.toString().toByteArray(Charsets.UTF_8))
        ch.flush()
    }

    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")

    /**
     * 对方扫了本机二维码来配对。TLS 握手只说明"这是个有证书的设备"，
     * 还要证明它持有二维码里的一次性口令（凭证绑定了双方证书，转发也没用）。
     * 但口令可能被偷看/拍下，所以还要弹窗显示 6 位验证码，由人和对方手机上的核对，
     * 一致并点"同意"之后才真正配对。
     */
    private fun handlePairing(ch: Wire, h: JSONObject, guestFp: String, host: String) {
        val tok = Hub.peekPairToken() ?: return // 没有在等人配对
        val mac = try { Secure.unb64(h.getString("mac")) } catch (e: Exception) { return }
        if (!Secure.constantTimeEquals(Tls.pairMac(tok, guestFp, Identity.fp), mac)) return // 口令不对
        if (!Hub.consumePairToken(tok)) return // 口令只能成功使用一次，且必须是当前这个
        val id = h.optString("dev")
        val port = h.getInt("port")
        require(port in 1..65535)
        require(idPattern.matches(id) && id != Store.deviceId)
        // 已经和同一个 ID 配对过、但证书不同：可能是有人冒用，必须先手动删除旧的
        val old = Hub.peers.value[id]
        if (old != null && old.paired && old.fp != guestFp) {
            pairReply(ch, false, "对方已经配对过一个相同 ID 但身份不同的设备，请先在设备列表里删除它，再重新配对")
            return
        }
        // 对方自报的名字只用来显示：去掉控制字符并限制长度
        val name = h.optString("devName").filter { it >= ' ' && it != '\u007f' }.trim().take(40).ifEmpty { "未知设备" }
        if (!Hub.pairBusy.compareAndSet(false, true)) {
            pairReply(ch, false, "对方正在处理另一个配对请求，请稍后再试")
            return
        }
        val prompt = PairPrompt(id, name, Tls.sas(tok, guestFp, Identity.fp), true, CompletableFuture())
        val approved = try {
            Hub.pairPrompt.value = prompt
            try { prompt.decision!!.get(60, TimeUnit.SECONDS) } catch (e: Exception) { false }
        } finally {
            if (Hub.pairPrompt.value === prompt) Hub.pairPrompt.value = null
            Hub.pairBusy.set(false)
        }
        if (!approved) {
            pairReply(ch, false, "对方拒绝了配对，或超时没有确认")
            return
        }
        pairReply(ch, true) // 先告诉对方成功；发不出去就抛异常，本机也不记录
        Hub.addPaired(id, name, host, port, guestFp)
        Hub.helloCount++
        Hub.hellos.tryEmit(id)
    }

    private fun receiveFile(pid: String, h: JSONObject, ch: Wire) {
        val size = h.getLong("size")
        if (size < 0) {
            no(ch, "文件大小无效")
            return
        }
        val free = Space.available()
        if (free >= 0 && size + Space.RESERVE > free) {
            Hub.toast("存储空间不足，已拒收文件（" + fmtSize(size) + "）")
            no(ch, "对方存储空间不足，需要 " + fmtSize(size))
            return
        }
        val fname = Saver.sanitize(Space.safeName(h.getString("fname")))
        val isApp = h.optString("kind") == "app"
        val id = newId()
        val title = if (isApp) h.optString("app").ifEmpty { fname } else fname
        Hub.addMsg(
            Msg(
                id, pid, false, if (isApp) Kind.APP else Kind.FILE, now(),
                name = title, file = fname, size = size, state = MsgState.RECEIVING,
                pkg = h.optString("pkg"), ver = h.optString("ver"), read = Hub.isViewing(pid)
            )
        )
        val saved: Saver.Out = try {
            Saver.create(Hub.app, fname)
        } catch (e: Exception) {
            Hub.patch(id) { it.copy(state = MsgState.FAILED, error = "无法保存文件") }
            try { no(ch, "对方无法保存文件") } catch (_: Exception) {}
            return
        }
        try {
            ok(ch) // 告诉对方可以开始发了
            var got = 0L
            var last = 0L
            saved.stream.use { os ->
                while (got < size) {
                    val f = ch.read()
                    if (f.isEmpty() || got + f.size > size) throw IOException("数据与声明的大小不符")
                    os.write(f)
                    got += f.size
                    val t = now()
                    if (t - last > 120) {
                        last = t
                        val g = got
                        Hub.patch(id) { it.copy(done = g) }
                    }
                }
            }
            val u = saved.uri.toString()
            Hub.patch(id) { it.copy(state = MsgState.DONE, done = size, uri = u) }
            Notifier.message(pid, (if (isApp) "收到应用：" else "收到文件：") + title)
            ok(ch)
        } catch (e: Exception) {
            Saver.delete(Hub.app, saved.uri)
            Hub.patch(id) { it.copy(state = MsgState.FAILED, error = "接收中断") }
        }
    }
}
