package com.hulian.transfer

import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections

/**
 * 传输协议（TCP，一次连接传一条消息）：
 *   [4字节头长度][JSON头][文件内容(size字节)]  →  对方返回 1 字节确认(1=成功)
 * 头：{v, dev, devName, port, type(hello|text|file), ...}
 */
object Net {
    private const val BUF = 256 * 1024

    fun send(peer: Peer, header: JSONObject, open: (() -> InputStream)?, onProgress: (Long) -> Unit) {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.sendBufferSize = 1 shl 20
            s.connect(InetSocketAddress(InetAddress.getByName(peer.host), peer.port), 6000)
            s.soTimeout = 60000
            val out = s.getOutputStream()
            header.put("v", 1).put("dev", Store.deviceId).put("devName", Store.deviceName).put("port", Hub.port)
            val hb = header.toString().toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(hb.size).array())
            out.write(hb)
            if (open != null) {
                open().use { ins ->
                    val buf = ByteArray(BUF)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
            }
            out.flush()
            if (s.getInputStream().read() != 1) throw java.io.IOException("对方未确认接收")
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
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

object Server {
    private var ss: ServerSocket? = null

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
                launch { handle(c) }
            }
        }
    }

    @Synchronized
    fun stop() {
        try { ss?.close() } catch (_: Exception) {}
        ss = null
    }

    private fun handle(c: Socket) {
        try {
            c.use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 60000
                val inp = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val len = inp.readInt()
                if (len !in 1..4_000_000) return
                val hb = ByteArray(len)
                inp.readFully(hb)
                val h = JSONObject(String(hb, Charsets.UTF_8))
                val pid = h.getString("dev")
                if (pid == Store.deviceId) return
                val host = s.inetAddress.hostAddress ?: return
                Hub.upsertPeer(pid, h.optString("devName"), host, h.getInt("port"))
                when (h.getString("type")) {
                    "hello" -> {
                        Hub.hellos.tryEmit(pid)
                        out.write(1)
                    }
                    "text" -> {
                        Hub.addMsg(Msg(newId(), pid, false, Kind.TEXT, now(), text = h.getString("text")))
                        out.write(1)
                    }
                    "file" -> receiveFile(pid, h, inp, out)
                }
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun receiveFile(pid: String, h: JSONObject, inp: DataInputStream, out: java.io.OutputStream) {
        val fname = Saver.sanitize(h.getString("fname"))
        val size = h.getLong("size")
        val isApp = h.optString("kind") == "app"
        val id = newId()
        val title = if (isApp) h.optString("app").ifEmpty { fname } else fname
        Hub.addMsg(
            Msg(
                id, pid, false, if (isApp) Kind.APP else Kind.FILE, now(),
                name = title, file = fname, size = size, state = MsgState.RECEIVING,
                pkg = h.optString("pkg"), ver = h.optString("ver")
            )
        )
        var saved: Saver.Out? = null
        try {
            saved = Saver.create(Hub.app, fname)
            val buf = ByteArray(256 * 1024)
            var got = 0L
            var last = 0L
            saved.stream.use { os ->
                while (got < size) {
                    val want = minOf(buf.size.toLong(), size - got).toInt()
                    val n = inp.read(buf, 0, want)
                    if (n < 0) throw EOFException()
                    os.write(buf, 0, n)
                    got += n
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
            out.write(1)
        } catch (e: Exception) {
            saved?.let { Saver.delete(Hub.app, it.uri) }
            Hub.patch(id) { it.copy(state = MsgState.FAILED, error = "接收中断") }
        }
    }
}
