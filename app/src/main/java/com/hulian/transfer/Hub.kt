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

/** 全局状态中心：设备列表、聊天消息、发送逻辑、在线检测 */
object Hub {
    lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _msgs = MutableStateFlow<List<Msg>>(emptyList())
    val msgs = _msgs.asStateFlow()
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers = _peers.asStateFlow()

    /** 有设备主动向本机"打招呼"（对方扫了本机二维码）时发出其 id */
    val hellos = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** 从系统分享菜单收到、等待选择设备的内容 */
    val pendingShare = MutableStateFlow<Share?>(null)

    @Volatile var port = 0
    @Volatile var discovery: Discovery? = null
    @Volatile var openPeer: String? = null      // 当前打开的聊天对象
    @Volatile var appVisible = false            // 应用界面当前是否在前台可见（退到桌面/锁屏后为 false）
    /** 点击通知后要打开的聊天（界面收到后清空） */
    val openChatRequest = MutableStateFlow<String?>(null)

    /** 用户此刻正在看着这个聊天：界面在前台，并且打开的就是它 */
    fun isViewing(peerId: String) = appVisible && openPeer == peerId
    @Volatile var helloCount = 0
    @Volatile var lastActivity = 0L

    private var saveJob: Job? = null
    private var sweepJob: Job? = null

    fun init(c: Context) {
        app = c.applicationContext
        Store.init(app)
        _msgs.value = Store.loadMsgs()
        Identity.init(app)
        _peers.value = Store.loadPeers()
    }

    fun toast(s: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, s, Toast.LENGTH_SHORT).show() }
    }

    fun touch() { lastActivity = now() }

    private fun persistSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            Store.saveMsgs(_msgs.value)
            Store.savePeers(_peers.value)
        }
    }

    // ---------- 配对确认 ----------
    /** 当前要弹出的配对确认窗口（被扫的一方：核对验证码并同意；扫码的一方：显示验证码等待） */
    val pairPrompt = MutableStateFlow<PairPrompt?>(null)

    /** 同一时间只处理一个配对请求 */
    val pairBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    // ---------- 配对口令（二维码里的一次性口令） ----------
    private var tokenBytes: ByteArray? = null
    private var tokenExpire = 0L

    /** 生成一次性配对口令（放进二维码）：15 分钟内有效，且只能被成功使用一次 */
    @Synchronized
    fun newPairToken(): String {
        val t = Secure.random(16)
        tokenBytes = t
        tokenExpire = now() + 15 * 60_000
        return Secure.b64(t)
    }

    @Synchronized
    private fun currentPairToken(): ByteArray? = if (now() < tokenExpire) tokenBytes else null

    /** 口令验证通过后调用：必须仍是当前口令才算数，并立刻作废 */
    @Synchronized
    fun consumePairToken(t: ByteArray): Boolean {
        val cur = currentPairToken() ?: return false
        if (!cur.contentEquals(t)) return false
        tokenBytes = null
        return true
    }

    /** 当前有效的配对口令（没有在等人配对则为 null） */
    fun peekPairToken(): ByteArray? = currentPairToken()

    /** 配对窗口：二维码页面打开期间为 true */
    fun pairingOpen(): Boolean = currentPairToken() != null

    /** 用证书指纹找到已配对的设备（身份靠证书，不靠对方自己声称的 ID） */
    fun peerByFp(fp: String): Peer? = _peers.value.values.firstOrNull { it.paired && it.fp == fp }

    /**
     * 给 TLS 服务端用：只接受已配对设备的证书。
     * "配对窗口"开着时暂时放行陌生证书，由一次性口令 + 人工核对验证码来确认。
     */
    val trust = Tls.Trust { fp -> peerByFp(fp) != null || pairingOpen() }

    // ---------- 设备 ----------
    /** 已通过加密握手认证的对方：更新它的地址和名字（只处理已配对的设备，陌生设备一律忽略） */
    fun upsertPeer(id: String, name: String, host: String, port: Int) {
        if (id == Store.deviceId) return
        var changed = false
        _peers.update { map ->
            val old = map[id] ?: return@update map
            if (!old.paired) return@update map
            val n = name.ifEmpty { old.name }
            changed = old.host != host || old.port != port || old.name != n
            map + (id to old.copy(name = n, host = host, port = port, online = true, lastSeen = now()))
        }
        if (changed) persistSoon()
    }

    /** 配对成功：记住对方，以及它的证书指纹 */
    fun addPaired(id: String, name: String, host: String, port: Int, fp: String) {
        if (id == Store.deviceId) return
        _peers.update { map ->
            val old = map[id]
            val n = name.ifEmpty { old?.name ?: "未知设备" }
            map + (id to Peer(id, n, host, port, true, now(), fp))
        }
        persistSoon()
    }

    private val verifying = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 发现层（mDNS / UDP 广播）报告的设备。广播谁都能伪造，所以：
     * 陌生设备直接忽略；已配对设备地址没变就标为在线；地址变了要先用密钥握手验证，再采用。
     */
    fun discovered(id: String, name: String, host: String, port: Int) {
        if (id == Store.deviceId) return
        val old = _peers.value[id] ?: return
        if (!old.paired) return
        if (old.host == host && old.port == port) {
            markOnline(id)
            return
        }
        if (!verifying.add(id)) return
        scope.launch {
            try {
                if (Net.ping(old.copy(host = host, port = port))) upsertPeer(id, name, host, port)
            } finally {
                verifying.remove(id)
            }
        }
    }

    /** 扫码配对：用二维码里的一次性口令与对方握手；成功后双方互相记住并共享密钥 */
    suspend fun pair(id: String, name: String, host: String, port: Int, token: String, hostFp: String): Boolean {
        if (id == Store.deviceId) {
            toast("不能和自己配对")
            return false
        }
        return try {
            val r = withContext(Dispatchers.IO) {
                Net.pair(id, host, port, token, hostFp) { code ->
                    pairPrompt.value = PairPrompt(id, name.ifEmpty { "对方设备" }, code, false, null)
                }
            }
            addPaired(id, r.name.ifEmpty { name }, host, port, r.fp)
            true
        } catch (e: Exception) {
            toast("配对失败：" + friendlyError(e))
            false
        } finally {
            if (pairPrompt.value?.isHost == false) pairPrompt.value = null
        }
    }

    fun markOnline(id: String) {
        _peers.update { map ->
            val p = map[id] ?: return@update map
            map + (id to p.copy(online = true, lastSeen = now()))
        }
    }

    fun setOffline(id: String) {
        _peers.update { map ->
            val p = map[id] ?: return@update map
            if (!p.online) map else map + (id to p.copy(online = false))
        }
    }

    fun forgetPeer(id: String) {
        _msgs.update { l -> l.filter { it.peerId != id } }
        _peers.update { it - id }
        persistSoon()
    }

    /** 逐个探测已记住但显示离线的设备：能连上就立刻标为在线 */
    fun probeKnown() {
        scope.launch {
            _peers.value.values.filter { !it.online && it.paired && it.host.isNotEmpty() }.forEach { p ->
                launch { if (Net.ping(p)) markOnline(p.id) }
            }
        }
    }

    fun rescan() {
        discovery?.restart()
        probeKnown()
    }

    fun onNetworkChanged() {
        discovery?.restart()
        probeKnown()
    }

    /** 后台巡检：在线设备久未见到就探测一次；定期重试离线设备；热点空闲自动断开 */
    fun startSweeper() {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(5000)
                tick++
                val t = now()
                _peers.value.values.filter { it.online && t - it.lastSeen > 12000 }.forEach { p ->
                    launch { if (Net.ping(p)) markOnline(p.id) else setOffline(p.id) }
                }
                if (tick % 3 == 0) probeKnown()

                val hotspotOn = HotspotHost.payload.value != null
                if (!AutoLink.running && (hotspotOn || HotspotJoin.active.value) && t - lastActivity > 180_000 &&
                    _msgs.value.none { it.state == MsgState.SENDING || it.state == MsgState.RECEIVING }
                ) {
                    HotspotJoin.leave()
                    HotspotHost.stop()
                    if (hotspotOn) HotspotHost.error.value = "热点已因空闲自动关闭，请重新打开"
                    toast("空闲已自动断开热点连接")
                }
            }
        }
    }

    // ---------- 消息 ----------
    fun addMsg(m: Msg) {
        touch()
        _msgs.update { it + m }
        persistSoon()
    }

    fun patch(id: String, f: (Msg) -> Msg) {
        touch()
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

    fun markRead(peerId: String) {
        Notifier.cancel(peerId)
        if (_msgs.value.none { it.peerId == peerId && !it.outgoing && !it.read }) return
        _msgs.update { l -> l.map { if (it.peerId == peerId && !it.outgoing && !it.read) it.copy(read = true) else it } }
        persistSoon()
    }

    fun clearHistory(peerId: String) {
        _msgs.update { l -> l.filter { it.peerId != peerId } }
        persistSoon()
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

    private fun doSend(m: Msg) {
        scope.launch {
            patch(m.id) { it.copy(state = MsgState.SENDING, done = 0, error = "") }
            var cleanup: () -> Unit = {}
            try {
                val peer = _peers.value[m.peerId] ?: throw IllegalStateException("设备不存在")
                if (!peer.paired) throw IllegalStateException("尚未配对，请扫码重新配对")
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
                patch(m.id) { it.copy(state = MsgState.FAILED, error = friendlyError(e)) }
            } finally {
                cleanup()
            }
        }
    }
}
