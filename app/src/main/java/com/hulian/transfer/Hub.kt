package com.hulian.transfer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/** 全局状态中心：设备列表、聊天消息、发送逻辑 */
object Hub {
    lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _msgs = MutableStateFlow<List<Msg>>(emptyList())
    val msgs = _msgs.asStateFlow()
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers = _peers.asStateFlow()

    private val _current = MutableStateFlow<String?>(null)
    val current = _current.asStateFlow()

    fun setCurrent(id: String?) {
        _current.value = id
        Store.currentPeer = id
    }

    /** 有设备主动向本机"打招呼"（扫码连接成功）时发出其 id */
    val hellos = MutableSharedFlow<String>(extraBufferCapacity = 8)

    @Volatile var port = 0
    @Volatile var discovery: Discovery? = null

    private var saveJob: Job? = null

    fun init(c: Context) {
        app = c.applicationContext
        Store.init(app)
        _msgs.value = Store.loadMsgs()
        _peers.value = Store.loadPeers()
        val saved = Store.currentPeer
        _current.value = if (saved != null && _peers.value.containsKey(saved)) saved
        else _msgs.value.lastOrNull { _peers.value.containsKey(it.peerId) }?.peerId
            ?: _peers.value.values.maxByOrNull { it.lastSeen }?.id
    }

    fun toast(s: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, s, Toast.LENGTH_SHORT).show() }
    }

    private fun persistSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            Store.saveMsgs(_msgs.value)
            Store.savePeers(_peers.value)
        }
    }

    // ---------- 设备 ----------
    fun upsertPeer(id: String, name: String, host: String, port: Int) {
        if (id == Store.deviceId) return
        _peers.update { map ->
            val old = map[id]
            val n = name.ifEmpty { old?.name ?: "未知设备" }
            map + (id to Peer(id, n, host, port, true, now()))
        }
        if (_current.value == null) setCurrent(id)
        persistSoon()
    }

    fun setOffline(id: String) {
        _peers.update { map ->
            val p = map[id] ?: return@update map
            map + (id to p.copy(online = false))
        }
    }

    // ---------- 消息 ----------
    fun addMsg(m: Msg) {
        _msgs.update { it + m }
        persistSoon()
    }

    fun patch(id: String, f: (Msg) -> Msg) {
        var terminal = false
        _msgs.update { list ->
            list.map {
                if (it.id == id) {
                    val n = f(it)
                    terminal = n.state == MsgState.DONE || n.state == MsgState.FAILED
                    n
                } else it
            }
        }
        if (terminal) persistSoon()
    }

    // ---------- 发送 ----------
    fun sendText(peerId: String, text: String) {
        val m = Msg(newId(), peerId, true, Kind.TEXT, now(), text = text, state = MsgState.SENDING)
        addMsg(m)
        doSend(m)
    }

    fun sendFile(peerId: String, uri: Uri) {
        val (name, size) = Saver.queryMeta(app, uri)
        val m = Msg(
            newId(), peerId, true, Kind.FILE, now(), name = name, file = name,
            size = size, state = MsgState.SENDING, uri = uri.toString()
        )
        addMsg(m)
        doSend(m)
    }

    fun sendApp(peerId: String, e: AppEntry) {
        val m = Msg(
            newId(), peerId, true, Kind.APP, now(), name = e.label, size = e.size,
            state = MsgState.SENDING, pkg = e.pkg, ver = e.version
        )
        addMsg(m)
        doSend(m)
    }

    fun retry(m: Msg) {
        if (m.outgoing && m.state == MsgState.FAILED) doSend(m)
    }

    suspend fun hello(peerId: String) {
        val p = _peers.value[peerId] ?: return
        try {
            Net.send(p, JSONObject().put("type", "hello"), null) {}
        } catch (_: Exception) {}
    }

    private fun doSend(m: Msg) {
        scope.launch {
            patch(m.id) { it.copy(state = MsgState.SENDING, done = 0, error = "") }
            var cleanup: () -> Unit = {}
            try {
                val peer = _peers.value[m.peerId] ?: throw IllegalStateException("设备不存在")
                val h = JSONObject()
                var total = 0L
                var open: (() -> InputStream)? = null
                when (m.kind) {
                    Kind.TEXT -> {
                        h.put("type", "text").put("text", m.text)
                    }
                    Kind.FILE -> {
                        val uri = Uri.parse(m.uri)
                        total = m.size
                        h.put("type", "file").put("kind", "file").put("fname", m.name).put("size", total)
                        open = { app.contentResolver.openInputStream(uri) ?: throw IOException("无法读取文件") }
                    }
                    Kind.APP -> {
                        val e = Apps.find(app, m.pkg) ?: throw IllegalStateException("应用已被卸载")
                        val p = Apps.prepare(app, e)
                        cleanup = p.cleanup
                        total = p.size
                        patch(m.id) { it.copy(size = p.size, file = p.name) }
                        h.put("type", "file").put("kind", "app").put("fname", p.name).put("size", p.size)
                            .put("app", e.label).put("pkg", e.pkg).put("ver", e.version)
                        open = p.open
                    }
                }
                val t = total
                var last = 0L
                Net.send(peer, h, open) { done ->
                    val n = now()
                    if (n - last > 120 || done == t) {
                        last = n
                        patch(m.id) { it.copy(done = done) }
                    }
                }
                patch(m.id) { it.copy(state = MsgState.DONE, done = t) }
            } catch (e: Exception) {
                patch(m.id) { it.copy(state = MsgState.FAILED, error = e.message ?: "发送失败") }
            } finally {
                cleanup()
            }
        }
    }
}
