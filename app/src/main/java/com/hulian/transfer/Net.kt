package com.hulian.transfer

import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Semaphore

/**
 * 传输协议（TCP，一次连接传一条消息，全程 AES-GCM 加密，握手与加密帧见 Secure.java）：
 *   握手（只有已配对设备能通过）
 *   → 加密帧[头 JSON]
 *   → 对方应答 [1=可以发 | 0+原因=拒绝]
 *   → （仅文件）加密帧[文件内容…]
 *   → （仅文件）对方应答 [1=成功]
 * 头：{v, dev, devName, port, type(ping|text|file), ...}
 */
object Net {
    class PairResult(val name: String, val key: String)

    private fun headerBytes(header: JSONObject): ByteArray {
        header.put("v", 2).put("dev", Store.deviceId).put("devName", Store.deviceName).put("port", Hub.port)
        return header.toString().toByteArray(Charsets.UTF_8)
    }

    private fun keyOf(p: Peer): ByteArray {
        if (!p.paired) throw IOException("尚未配对，请扫码重新配对")
        return Secure.unb64(p.key)
    }

    /** 对方的应答帧：第一个字节 1 表示同意/成功，0 表示拒绝，后面是原因 */
    private fun expectOk(b: ByteArray) {
        if (b.isNotEmpty() && b[0] == 1.toByte()) return
        throw IOException(if (b.size > 1) String(b, 1, b.size - 1, Charsets.UTF_8) else "对方拒绝接收")
    }

    fun send(peer: Peer, header: JSONObject, open: (() -> InputStream)?, onProgress: (Long) -> Unit) {
        val key = keyOf(peer)
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.sendBufferSize = 1 shl 20
            s.connect(InetSocketAddress(InetAddress.getByName(peer.host), peer.port), 6000)
            s.soTimeout = 60000
            val ses = Secure.connect(s.getInputStream(), s.getOutputStream(), Store.deviceId, peer.id, key, false)
            val ch = ses.ch
            ch.write(headerBytes(header))
            ch.flush()
            // 对方先表态：存储空间不足等会在这里就被拒绝，不用白传
            expectOk(ch.read())
            if (open != null) {
                open().use { ins ->
                    val buf = ByteArray(Secure.CHUNK)
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

    /** 扫码配对（本机是扫码的一方）：用二维码里的一次性口令和对方握手，成功后得到双方共享的密钥 */
    fun pair(peerId: String, host: String, port: Int, token: String): PairResult {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(InetAddress.getByName(host), port), 6000)
            s.soTimeout = 10000
            val ses = Secure.connect(s.getInputStream(), s.getOutputStream(), Store.deviceId, peerId, Secure.unb64(token), true)
            val ch = ses.ch
            val me = JSONObject().put("name", Store.deviceName).put("port", Hub.port)
            ch.write(me.toString().toByteArray(Charsets.UTF_8))
            ch.flush()
            val r = JSONObject(String(ch.read(), Charsets.UTF_8))
            if (r.optString("id") != peerId) throw IOException("设备不匹配")
            return PairResult(r.optString("name"), Secure.b64(ses.pairKey()))
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    /** 轻量探测：能完成加密握手并得到应答，才算在线（只有持有配对密钥的设备能通过） */
    fun ping(p: Peer): Boolean = try {
        if (!p.paired) {
            false
        } else {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName(p.host), p.port), 900)
                s.soTimeout = 1500
                val ses = Secure.connect(s.getInputStream(), s.getOutputStream(), Store.deviceId, p.id, Secure.unb64(p.key), false)
                ses.ch.write(headerBytes(JSONObject().put("type", "ping")))
                ses.ch.flush()
                val r = ses.ch.read()
                r.isNotEmpty() && r[0] == 1.toByte()
            }
        }
    } catch (e: Exception) {
        false
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
        val s = ServerSocket()
        s.reuseAddress = true
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

    private fun ok(ch: Secure.Channel) {
        ch.write(byteArrayOf(1))
        ch.flush()
    }

    private fun no(ch: Secure.Channel, why: String) {
        ch.write(byteArrayOf(0) + why.toByteArray(Charsets.UTF_8))
        ch.flush()
    }

    private fun handle(c: Socket) {
        try {
            c.use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 8000 // 握手和读头必须在 8 秒内完成，防止有人只连不发、占住连接
                val ses = Secure.accept(s.getInputStream(), s.getOutputStream(), Store.deviceId, Hub.keys)
                val ch = ses.ch
                val host = s.inetAddress.hostAddress ?: return
                if (ses.pairing) {
                    handlePairing(ses, host)
                    return
                }
                val h = JSONObject(String(ch.read(), Charsets.UTF_8))
                val pid = ses.peerId
                if (h.optString("dev") != pid) return // 头里声称的身份必须和握手认证出来的一致
                s.soTimeout = 60000
                val port = h.optInt("port", 0)
                if (port in 1..65535) Hub.upsertPeer(pid, h.optString("devName"), host, port)
                when (h.getString("type")) {
                    "ping" -> ok(ch)
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

    /** 对方扫了本机二维码来配对：能解开对方的第一帧，就证明对方持有二维码里的口令 */
    private fun handlePairing(ses: Secure.Session, host: String) {
        val ch = ses.ch
        val j = JSONObject(String(ch.read(), Charsets.UTF_8))
        val port = j.getInt("port")
        require(port in 1..65535)
        if (!Hub.consumePairToken(ses.secret)) return // 口令只能成功使用一次，且必须是当前这个
        val key = Secure.b64(ses.pairKey())
        val me = JSONObject().put("id", Store.deviceId).put("name", Store.deviceName).put("port", Hub.port)
        ch.write(me.toString().toByteArray(Charsets.UTF_8))
        ch.flush()
        Hub.addPaired(ses.peerId, j.optString("name"), host, port, key)
        Hub.helloCount++
        Hub.hellos.tryEmit(ses.peerId)
    }

    private fun receiveFile(pid: String, h: JSONObject, ch: Secure.Channel) {
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
