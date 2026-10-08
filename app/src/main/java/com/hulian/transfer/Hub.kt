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
    /** 后台协程里任何没被处理的异常，都会在这里报告（以前会静悄悄消失） */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> if (e !is CancellationException) report("程序内部出错", e) }
    )

    private val _msgs = MutableStateFlow<List<Msg>>(emptyList())
    val msgs = _msgs.asStateFlow()
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers = _peers.asStateFlow()

    /** 有设备主动向本机"打招呼"（对方扫了本机二维码）时发出其 id */
    val hellos = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 对方退出了和本机的对话框（收到对方的 bye） */
    val peerLeft = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 正在发送的 bye：断开热点前要先等它发完，不然网先断了对方收不到 */
    @Volatile var byeJob: Job? = null

    /** 本机退出和某台设备的对话框：通知对方一起退出（对方不在线就不发） */
    fun sendBye(id: String) {
        val p = _peers.value[id]?.takeIf { it.paired && it.online } ?: return
        byeJob = scope.launch { Net.bye(p) }
    }
    /** 从系统分享菜单收到、等待选择设备的内容 */
    val pendingShare = MutableStateFlow<Share?>(null)

    @Volatile var port = 0
    @Volatile var discovery: Discovery? = null
    @Volatile var openPeer: String? = null      // 当前打开的聊天对象
    @Volatile var appVisible = false            // 应用界面当前是否在前台可见（退到桌面/锁屏后为 false）
    /** 本 app 自己拉起了系统选文件界面：app 会暂时退到后台，但这不算离开，连接要原样保留 */
    @Volatile var picking = false
    /** 界面被系统销毁重建（不是用户退出）：重建过程中界面销毁不能当成“离开对话框”去断开连接 */
    @Volatile var recreating = false
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

    /** 要弹出的错误窗口：标题 + 原因 + 详细信息（可选中、可复制，不会像 Toast 那样被截断） */
    class ErrorInfo(val title: String, val reason: String, val detail: String = "")

    val errorDialog = MutableStateFlow<ErrorInfo?>(null)
    private val pendingErrors = ArrayDeque<ErrorInfo>()
    private val onceKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 弹出错误窗口；已经有一个窗口开着时排队，一个个显示，不会互相覆盖。同时写入错误记录 */
    fun fail(title: String, reason: String, detail: String = "") {
        logLine("$title：$reason" + if (detail.isNotEmpty()) "  [" + detail.replace("\n", " | ") + "]" else "")
        synchronized(pendingErrors) {
            val cur = errorDialog.value
            if (cur == null) {
                errorDialog.value = ErrorInfo(title, reason, detail)
            } else if (!(cur.title == title && cur.reason == reason) &&
                pendingErrors.none { it.title == title && it.reason == reason }
            ) {
                pendingErrors.addLast(ErrorInfo(title, reason, detail))
            }
        }
    }

    /** 关闭当前错误窗口，有排队的就接着显示下一个 */
    fun dismissError() {
        synchronized(pendingErrors) { errorDialog.value = pendingErrors.removeFirstOrNull() }
    }

    /** 由异常生成错误窗口：中文原因 + 技术详情 */
    fun report(title: String, e: Throwable, extra: String = "") {
        fail(title, friendlyError(e), (extra + "\n" + techDetail(e)).trim())
    }

    /** 同一个问题本次运行只弹一次（比如每次网络变化都会重试的后台功能），之后只记入错误记录 */
    fun reportOnce(key: String, title: String, reason: String, detail: String = "") {
        if (onceKeys.add(key)) fail(title, reason, detail) else logLine("$title：$reason")
    }

    /** 只记录、不打扰用户的次要错误（比如某个可有可无的后台动作失败）：在设置里的“错误记录”可以看到 */
    fun log(where: String, e: Throwable) {
        logLine("[$where] " + friendlyError(e) + "  (" + techDetail(e).replace("\n", " | ") + ")")
    }

    private fun logLine(s: String) {
        try {
            if (!::app.isInitialized) return
            val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val f = java.io.File(app.filesDir, "error.log")
            synchronized(this) {
                if (f.length() > 200_000) f.delete()
                f.appendText("$t $s\n")
            }
        } catch (_: Exception) {
        }
    }

    fun errorLogText(): String = try {
        val f = java.io.File(app.filesDir, "error.log")
        if (f.exists()) f.readLines().takeLast(200).joinToString("\n").ifEmpty { "（没有错误记录）" } else "（没有错误记录）"
    } catch (e: Exception) {
        "读取错误记录失败：" + friendlyError(e)
    }

    fun clearErrorLog() {
        try { java.io.File(app.filesDir, "error.log").delete() } catch (_: Exception) {}
    }

    fun toast(s: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, s, Toast.LENGTH_SHORT).show() }
    }

    fun touch() { lastActivity = now() }

    private fun persistSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            try {
                Store.saveMsgs(_msgs.value)
                Store.savePeers(_peers.value)
            } catch (e: Exception) {
                // 存不下去意味着重启后聊天记录 / 已配对设备会丢，必须让用户知道
                reportOnce("save", "聊天记录 / 设备列表保存失败", friendlyError(e), techDetail(e))
            }
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

    /** 配对成功：记住对方，以及它的证书指纹；iHost=本机是开二维码的一方（以后没有 Wi-Fi 时由它建热点） */
    fun addPaired(id: String, name: String, host: String, port: Int, fp: String, iHost: Boolean, rk: String = "") {
        if (id == Store.deviceId) return
        _peers.update { map ->
            val old = map[id]
            val n = name.ifEmpty { old?.name ?: "未知设备" }
            map + (id to Peer(id, n, host, port, true, now(), fp, iHost, rk.ifEmpty { old?.rk ?: "" }))
        }
        persistSoon()
    }

    fun setRelayKey(id: String, key: String, onlyIfEmpty: Boolean = false) {
        _peers.update { map ->
            val p = map[id] ?: return@update map
            if (onlyIfEmpty && p.rk.isNotEmpty()) map else map + (id to p.copy(rk = key))
        }
        persistSoon()
    }

    private val keySharing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 更新前配对的设备没有中转密钥：两台手机直连在线时，ID 小的一方生成并发给对方（走 TLS 加密的直连通道） */
    private fun shareRelayKey(id: String) {
        val p = _peers.value[id] ?: return
        if (!p.paired || p.rk.isNotEmpty() || Store.deviceId >= id) return
        if (!keySharing.add(id)) return
        scope.launch {
            try {
                val k = RelayCrypto.newKey()
                Net.send(p, JSONObject().put("type", "rk").put("key", k), null) { }
                setRelayKey(id, k)
            } catch (e: Exception) {
                log("交换远程中转密钥", e)
            } finally {
                keySharing.remove(id)
            }
        }
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
            fail("配对失败", "不能和自己配对：扫到的是本机自己的二维码")
            return false
        }
        return try {
            val r = withContext(Dispatchers.IO) {
                Net.pair(id, host, port, token, hostFp) { code ->
                    pairPrompt.value = PairPrompt(id, name.ifEmpty { "对方设备" }, code, false, null)
                }
            }
            addPaired(id, r.name.ifEmpty { name }, host, port, r.fp, iHost = false, rk = r.rk)  // 扫码的一方：以后连对方的热点
            true
        } catch (e: Exception) {
            fail("配对失败", friendlyError(e), "对方地址：$host:$port\n" + techDetail(e))
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
        shareRelayKey(id)
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
        Relay.kick()
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
                if ((hotspotOn || (HotspotJoin.active.value && !HotspotJoin.autoMode)) && t - lastActivity > 180_000 &&
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
                transport(peer, h, t, open) { done ->
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

    /**
     * 选择传输方式：能直连就直连（快、不耗网盘额度）；对方离线、直连连不上，且远程中转已设置好，就改走 Cloudflare + 坚果云。
     * 两种都失败时，把两边的原因都告诉用户
     */
    private suspend fun transport(peer: Peer, h: JSONObject, size: Long, open: (() -> InputStream)?, onProgress: (Long) -> Unit) {
        val relayOk = Relay.ready(peer)
        // 同一个 Wi-Fi（或热点已经连上）：局域网直连又快又不占网盘，优先走直连，不管远程中转在不在线
        val local = peer.online && Net.onLan(peer.host)
        // 其余情况远程中转优先：对方也连着 Cloudflare（双方都能走中转）时先走远程；失败了才用原来的直连 / 热点。
        // 对方没连 Cloudflare 的话走远程它也收不到，所以这种情况不走
        if (!local && relayOk && Relay.connected.value && peer.id in Relay.online.value) {
            try {
                Relay.send(peer, h, size, open, onProgress)
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!peer.online) throw IOException("远程中转失败（" + friendlyError(e) + "），对方也没有直连在线", e)
                // 对方直连在线：继续往下走原来的方式
            }
        }
        if (relayOk && !peer.online && Relay.connected.value) {
            Relay.send(peer, h, size, open, onProgress)
            return
        }
        try {
            Net.send(peer, h, open, onProgress)
        } catch (e: IOException) {
            if (!relayOk) throw e
            try {
                Relay.send(peer, h, size, open, onProgress)
            } catch (e2: Exception) {
                throw IOException("直连失败（" + friendlyError(e) + "）；远程中转也失败了（" + friendlyError(e2) + "）", e2)
            }
        }
    }
}
