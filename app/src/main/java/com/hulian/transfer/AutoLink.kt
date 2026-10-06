package com.hulian.transfer

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** 系统“定位”开关。只有授予过 WRITE_SECURE_SETTINGS（电脑上 adb 授权一次）才能自己开关；否则只能引导用户去设置里开 */
object LocationSwitch {
    fun isOn(ctx: Context): Boolean = try {
        LocationManagerCompat.isLocationEnabled(ctx.getSystemService(LocationManager::class.java))
    } catch (_: Exception) {
        false
    }

    fun canWrite(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 返回 true 表示开关现在确实是想要的状态，并且是本次调用改成的 */
    @Suppress("DEPRECATION")
    fun set(ctx: Context, on: Boolean): Boolean {
        if (!canWrite(ctx)) return false
        return try {
            Settings.Secure.putInt(
                ctx.contentResolver, Settings.Secure.LOCATION_MODE,
                if (on) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
            )
            isOn(ctx) == on
        } catch (_: Exception) {
            false
        }
    }

    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}

/** 由“主机”的证书指纹算出的固定 Wi-Fi 名称和密码：主机自己算、所有已配对的设备也能算出同一个，不用扫码，一个主机可以同时被多台已配对设备连 */
object LinkCred {
    class Cred(val ssid: String, val pwd: String)

    fun of(hostFp: String): Cred {
        val h = java.security.MessageDigest.getInstance("SHA-256")
            .digest("hulian-link-v2|${hostFp.lowercase()}".toByteArray(Charsets.UTF_8))
        fun hex(from: Int, to: Int) = (from until to).joinToString("") { "%02x".format(h[it]) }
        // Wi-Fi Direct 群组名称必须以 DIRECT-xx 开头；密码 32 位十六进制
        return Cred("DIRECT-hl-" + hex(0, 3), hex(3, 19))
    }
}

/** 本机当“主机”时建的 Wi-Fi Direct 群组（固定名称和密码）。只关本 app 建的 */
object DirectGroup {
    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null

    /** 本 app 建的群组是否在运行 */
    val up = MutableStateFlow(false)

    @Volatile var starting = false
        private set

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "本机不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "Wi-Fi Direct 正忙（可能本机正连着 Wi-Fi 且芯片不能同时用，或有别的 Wi-Fi Direct 连接）"
        else -> "创建热点失败（代码 $r）"
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, cred: LinkCred.Cred, onFail: (String) -> Unit) {
        val app = ctx.applicationContext
        val m = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) { onFail("本机不支持 Wi-Fi Direct"); return }
        val c = m.initialize(app, Looper.getMainLooper(), null)
        mgr = m
        ch = c
        starting = true
        // 系统偶尔一直不回调：15 秒还没出结果就当失败，别让界面一直卡在“正在创建”
        Handler(Looper.getMainLooper()).postDelayed({
            if (starting) {
                starting = false
                onFail("创建热点超时")
            }
        }, 15_000)
        try {
            m.requestGroupInfo(c) { g ->
                if (g != null && g.networkName == cred.ssid) {
                    // 已经是我们的群组（上次没来得及关）：直接沿用
                    up.value = true
                    starting = false
                } else if (g != null) {
                    // 别的软件 / 用户自己建的 Wi-Fi Direct：不动它
                    starting = false
                    onFail("本机已有别的 Wi-Fi Direct 连接，没有改动它")
                } else {
                    create(m, c, cred, onFail)
                }
            }
        } catch (e: SecurityException) {
            starting = false
            onFail("缺少权限：请授予" + hotspotPermName())
        }
    }

    @SuppressLint("MissingPermission")
    private fun create(m: WifiP2pManager, c: WifiP2pManager.Channel, cred: LinkCred.Cred, onFail: (String) -> Unit) {
        try {
            val cfg = WifiP2pConfig.Builder().setNetworkName(cred.ssid).setPassphrase(cred.pwd)
                .enablePersistentMode(false).build()
            m.createGroup(c, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    up.value = true
                    starting = false
                }

                override fun onFailure(reason: Int) {
                    starting = false
                    onFail(reasonText(reason))
                }
            })
        } catch (e: Exception) {
            starting = false
            onFail(if (e is SecurityException) "缺少权限：请授予" + hotspotPermName() else "创建热点失败：" + (e.message ?: ""))
        }
    }

    /** 群组可能被系统收掉（关了 Wi-Fi、被别的功能抢占等）：发现没了就标记，让自动检查重新建 */
    @SuppressLint("MissingPermission")
    fun verify() {
        val m = mgr
        val c = ch
        if (m == null || c == null) { up.value = false; return }
        try {
            m.requestGroupInfo(c) { g -> if (g == null) up.value = false }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val m = mgr
        val c = ch
        if (up.value && m != null && c != null) {
            try { m.removeGroup(c, null) } catch (_: Exception) {}
        }
        up.value = false
        starting = false
    }
}

enum class LinkKind { WORK, OK, WARN }

/** 点开对话框后（不在同一 Wi-Fi 时）由用户选的角色：建热点 / 接收信号（去连对方的热点） */
enum class LinkRole { HOST, RECV }

/** 对话框顶部显示的连接状态。action：""=无，"retry"=点一下重试，"location"=点一下去系统设置打开定位 */
data class LinkStatus(val kind: LinkKind, val text: String, val action: String = "")

/**
 * 点开某台已配对设备的对话框时自动用热点连接，离开对话框（或退出 app）自动断开：
 * - 平时没有任何热点。你点开对话框、对方也点开你的对话框（两边各自点开，不需要通知对方）
 * - 点开后如果已经在同一个 Wi-Fi（对方局域网在线）就不用选；否则弹出“建热点 / 接收信号”，不再先扫描等待
 * - 一边选建热点、一边选接收：直接连上。都选建热点：设备 ID 小的保留，另一台撤掉自己的改为接收
 * - 都选接收：等 3 秒，对方热点还没出现，由设备 ID 小的那台自动改为建热点，ID 大的继续接收
 * - 热点名称和密码由主机自己的证书算出，所有已配对设备都能算出来，不用扫码；不需要选择、不弹窗
 * - 完全由事件触发，没有定时轮询（省电）：点开对话框、Wi-Fi / 定位开关变化、扫描结果、授权完成、设备上线下线、连接断开……才检查。
 *   监听只在 app 在前台时注册。只有“没连上 / 创建失败”时才按 3 秒起逐步拉长（最长 30 秒）的间隔重试，最多 15 次
 * - 只关本 app 自己建的群组、自己改开的定位；手动开的 / 别的软件开的一律不碰
 */
object AutoLink {
    /** 需要用户去系统里手动打开定位（本 app 没有权限自己开） */
    val needLocation = MutableStateFlow(false)

    /** 当前连接状态（对话框顶部显示）；null 表示不显示 */
    val status = MutableStateFlow<LinkStatus?>(null)

    /** 需要弹出“建热点 / 接收信号”的选择框 */
    val needChoice = MutableStateFlow(false)
    @Volatile private var role: LinkRole? = null
    @Volatile private var choiceAsked = false
    @Volatile private var recvHint = false
    private var choiceJob: Job? = null
    private var recvJob: Job? = null

    /** 用户在选择框里做了选择 */
    fun choose(r: LinkRole) {
        needChoice.value = false
        role = r
        choiceAsked = false
        recvHint = false
        failures = 0
        retryJob?.cancel()
        retryJob = null
        scanReqAt = 0L
        recvJob?.cancel()
        if (r == LinkRole.RECV) startRecvTimer() else recvJob = null
        kick()
    }

    /**
     * 选了“接收信号”后等 3 秒：对方的热点还没出现，说明对方可能也选了接收。
     * 只由设备 ID 小的那台改为建热点（ID 大的继续等，不会两台同时改）；如果已经能看到对方的热点（正在加入），就不动。
     */
    private fun startRecvTimer() {
        recvJob?.cancel()
        recvJob = Hub.scope.launch {
            delay(3_000)
            val id = target ?: return@launch
            val t = Hub.peers.value[id] ?: return@launch
            val app = appCtx ?: return@launch
            if (role != LinkRole.RECV || t.online || HotspotJoin.linked) return@launch
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (hasSignal(wm, t)) return@launch
            if (Store.deviceId < t.id) {
                role = LinkRole.HOST
                clientJob?.cancel()
                clientJob = null
                HotspotJoin.leave()
                joinedId = null
                recvHint = false
                kick()
            } else {
                recvHint = true
                st(LinkKind.WORK, "对方可能也在接收，将自动由一方建热点，请稍等…")
            }
        }
    }

    private fun st(kind: LinkKind, text: String, action: String = "") {
        status.value = LinkStatus(kind, text, action)
    }

    /** 点状态条：重试 / 去设置里打开定位 */
    fun onStatusClick(ctx: Context) {
        when (status.value?.action) {
            "retry" -> {
                st(LinkKind.WORK, "正在重试…")
                kick()
            }
            "location" -> LocationSwitch.openSettings(ctx)
            "choose" -> needChoice.value = true
        }
    }

    /** 当前打开着对话框的设备 ID；null 表示没有（此时不自动连接） */
    @Volatile private var target: String? = null
    @Volatile private var joinedId: String? = null

    private var keeper: Job? = null
    private var retryJob: Job? = null
    private var scanTimer: Job? = null
    private var recheckJob: Job? = null
    private var watchers: List<Job> = emptyList()
    private var appCtx: Context? = null
    private var clientJob: Job? = null
    private var registered = false
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var paused = false
    @Volatile private var failures = 0
    @Volatile private var scanReqAt = 0L
    @Volatile private var scanDoneAt = 0L
    private var locAsked = false
    private val shown = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                scanDoneAt = now()
                scanTimer?.cancel()
                kick(reset = false)
            } else {
                kick()
            }
        }
    }

    /** 同一类提示只弹一次，条件恢复正常后才会再弹 */
    private fun note(key: String, msg: String) { if (shown.add(key)) Hub.toast(msg) }
    private fun clear(key: String) { shown.remove(key) }

    /**
     * 外部事件触发一次检查。reset=true（默认）表示条件有了新变化，清掉重试计数和等待中的重试；
     * 连接自身状态变化 / 扫描结果（reset=false）不清，避免失败后立刻死循环重试
     */
    fun kick(reset: Boolean = true) {
        if (reset) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
            if (now() - scanReqAt > 20_000) scanReqAt = 0L   // 太久没扫过了，下次重新扫
        }
        kicks.trySend(Unit)
    }

    private val RETRY_MS = longArrayOf(3_000, 5_000, 8_000, 12_000, 20_000, 30_000)

    private fun later(ms: Long) {
        retryJob?.cancel()
        retryJob = Hub.scope.launch {
            delay(ms)
            retryJob = null
            kicks.trySend(Unit)
        }
    }

    /** 失败后重试：3、5、8、12、20 秒，之后每 30 秒一次，最多 15 次，之后等下一次事件 */
    private fun fail(reason: String) {
        failures++
        scanReqAt = 0L
        if (failures > 15) {
            st(LinkKind.WARN, "$reason。已停止自动重试（点这里重试）", "retry")
            return
        }
        st(LinkKind.WARN, "$reason，稍后自动重试（第 $failures/15 次，点这里立即重试）", "retry")
        later(RETRY_MS[minOf(failures - 1, RETRY_MS.size - 1)])
    }

    /** 新开界面时恢复（用户手动“断开”之后，重新点开对话框才会再连） */
    fun resume() { paused = false }

    /** 用户在聊天菜单里手动断开：这次对话框里不再自动重连 */
    fun pause() {
        paused = true
        role = null
        choiceAsked = false
        recvHint = false
        needChoice.value = false
        choiceJob?.cancel()
        recvJob?.cancel()
        st(LinkKind.WARN, "已手动断开。重新点开对话框才会再连")
        retryJob?.cancel()
        retryJob = null
        scanTimer?.cancel()
        clientJob?.cancel()
        clientJob = null
        HotspotJoin.leave()
        DirectGroup.stop()
    }

    private fun registerReceiver(app: Context) {
        if (registered) return
        try {
            val f = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        } catch (_: Exception) {}
    }

    private fun unregisterReceiver() {
        if (!registered) return
        try { appCtx?.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
    }

    /** app 退到后台：不再接收系统广播（省电） */
    fun onBackground() {
        unregisterReceiver()
        scanTimer?.cancel()
    }

    /** app 回到前台：如果对话框还开着，重新监听并检查一次 */
    fun onForeground(ctx: Context) {
        if (target == null) return
        val app = ctx.applicationContext
        appCtx = app
        registerReceiver(app)
        kick()
    }

    /** 进入某台设备的对话框 */
    fun open(ctx: Context, peerId: String) {
        val app = ctx.applicationContext
        appCtx = app
        paused = false
        // 之前连的是另一台设备的热点：先断开（自己建的热点所有已配对设备都能用，不用拆）
        if (joinedId != null && joinedId != peerId) {
            clientJob?.cancel()
            clientJob = null
            if (HotspotJoin.autoMode) HotspotJoin.leave()
            joinedId = null
        }
        if (target != peerId) {
            role = null
            choiceAsked = false
            recvHint = false
            needChoice.value = false
            choiceJob?.cancel()
            recvJob?.cancel()
        }
        target = peerId
        st(LinkKind.WORK, "正在准备连接…")
        registerReceiver(app)
        if (keeper?.isActive != true) {
            try { app.startService(Intent(app, ExitWatcher::class.java)) } catch (_: Exception) {}
            keeper = Hub.scope.launch {
                for (k in kicks) {
                    if (Hub.appVisible && !paused && target != null) {
                        try { step(app) } catch (_: Exception) {}
                    }
                }
            }
            watchers = listOf(
                // 对话框里这台设备上线 / 下线
                Hub.scope.launch {
                    Hub.peers.map { m -> m.values.map { it.id to it.online }.sortedBy { it.first } }
                        .distinctUntilChanged().collect { kick() }
                },
                // 连接自身断开 / 建立
                // 连接一断就作废之前的扫描结果：对方可能已经关了热点又重新开了，必须重新扫，不能拿旧结果去连
                Hub.scope.launch { HotspotJoin.active.collect { on -> if (!on) scanReqAt = 0L; kick(reset = false) } },
                Hub.scope.launch { DirectGroup.up.collect { on -> if (!on) scanReqAt = 0L; kick(reset = false) } }
            )
        }
        kick()
    }

    /** 离开对话框：等传输结束（最多 10 分钟）后断开，期间如果又点开了别的对话框就不断 */
    fun close() {
        target = null
        Hub.scope.launch {
            var waited = 0
            while (waited < 600 && target == null &&
                Hub.msgs.value.any { it.state == MsgState.SENDING || it.state == MsgState.RECEIVING }
            ) {
                delay(1000)
                waited++
            }
            if (target == null) closeLink()
        }
    }

    /** 定位没开时：能自己开就开；Android 12 及以下扫描和建热点都离不开定位，只能引导用户去开 */
    private fun ensureLocation(app: Context): Boolean {
        if (LocationSwitch.isOn(app)) return true
        Store.ownLocation = false
        if (LocationSwitch.set(app, true)) {
            Store.ownLocation = true
            return true
        }
        if (Build.VERSION.SDK_INT < 33) {
            askLocation()
            return false
        }
        return true   // Android 13+：有的系统已不要求定位，先直接试，失败了再提示
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun hasSignal(wm: WifiManager, p: Peer): Boolean {
        // 只认 20 秒内的扫描结果：对方刚关掉的热点还留在系统缓存里，信了就会去连一个已经不存在的热点，白等超时再退避重试
        val nowUs = SystemClock.elapsedRealtime() * 1000
        val ssid = LinkCred.of(p.fp).ssid
        return try {
            wm.scanResults.any { nowUs - it.timestamp < 20_000_000L && it.SSID == ssid }
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun requestScan(wm: WifiManager) {
        scanReqAt = now()
        scanDoneAt = 0L
        try { wm.startScan() } catch (_: Exception) {}
        // 扫描结果广播一般很快就到；万一被系统限流没来，8 秒后也用现有结果继续
        scanTimer?.cancel()
        scanTimer = Hub.scope.launch {
            delay(8000)
            scanDoneAt = now()
            kicks.trySend(Unit)
        }
    }

    private fun step(app: Context) {
        val id = target ?: run { status.value = null; return }
        val t = Hub.peers.value[id]?.takeIf { it.paired } ?: run { status.value = null; return }
        // 用二维码手动连接 / 手动开的热点正在使用：不打扰
        if (HotspotHost.isUp() || HotspotHost.starting || (HotspotJoin.active.value && !HotspotJoin.autoMode)) {
            status.value = null
            return
        }
        // 已经连上对方的热点（或正在连）
        if (HotspotJoin.active.value) {
            when {
                t.online -> st(LinkKind.OK, "已连接：${t.name}")
                HotspotJoin.linked -> st(LinkKind.WORK, "已连上对方的热点，正在联系 ${t.name}…")
                recvHint -> st(LinkKind.WORK, "对方可能也在接收，将自动由一方建热点，请稍等…")
                else -> st(LinkKind.WORK, "正在等待 ${t.name} 的热点…（如有系统弹窗请点\u201c连接\u201d）")
            }
            return
        }
        // 对方已经通过同一个 Wi-Fi 在线（原来的局域网方式能用）：不用选，也不用建热点
        if (t.online && !DirectGroup.up.value) {
            st(LinkKind.OK, "已连接：${t.name}（同一 Wi-Fi）")
            return
        }
        // 还没选角色：本机热点还留着就当作“建热点”，否则等一小会儿（让局域网有时间发现对方）再弹出选择
        if (role == null) {
            if (DirectGroup.up.value) {
                role = LinkRole.HOST
            } else {
                if (choiceAsked) {
                    st(LinkKind.WARN, "请选择：建热点 或 接收信号（点这里选择）", "choose")
                } else {
                    st(LinkKind.WORK, "正在检查是否在同一 Wi-Fi…")
                    if (choiceJob?.isActive != true) {
                        choiceJob = Hub.scope.launch {
                            delay(1_500)
                            val p = Hub.peers.value[id]
                            if (role == null && target == id && p?.online != true &&
                                !DirectGroup.up.value && !HotspotJoin.active.value
                            ) {
                                choiceAsked = true
                                st(LinkKind.WARN, "请选择：建热点 或 接收信号（点这里选择）", "choose")
                                needChoice.value = true
                            }
                        }
                    }
                }
                return
            }
        }
        if (!hotspotCoreGranted(app)) {
            st(LinkKind.WARN, "需要授予" + hotspotPermName() + "权限，授权后自动继续")
            return
        }
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            st(LinkKind.WARN, "请打开 Wi-Fi 开关（不需要连接任何网络），打开后自动连接")
            return
        }
        if (!ensureLocation(app)) {
            st(LinkKind.WARN, "需要打开系统的定位开关才能继续（点这里去设置）", "location")
            return
        }

        // 选的是“接收信号”，但本机热点还开着（比如刚由建热点改成接收）：先关掉
        if (role == LinkRole.RECV && (DirectGroup.up.value || DirectGroup.starting)) {
            DirectGroup.stop()
            return
        }
        // 本机已建热点：核对一次对方是不是也同时建了，ID 小的保留
        if (DirectGroup.up.value) {
            failures = 0
            if (t.online) {
                st(LinkKind.OK, "已连接：${t.name}（本机热点）")
                recheckJob?.cancel()
                recheckJob = null
            } else {
                st(LinkKind.WORK, "热点已建好，等待 ${t.name} 选择\u201c接收信号\u201d…")
                armRecheck()
            }
            if (scanReqAt == 0L) {
                requestScan(wm)
                return
            }
            if (scanDoneAt < scanReqAt) return
            if (t.id < Store.deviceId && hasSignal(wm, t)) {
                st(LinkKind.WORK, "${t.name} 也建了热点，改为连接对方的热点…")
                role = LinkRole.RECV
                recvHint = false
                DirectGroup.stop()
                scanReqAt = 0L
                kick(reset = false)
            } else {
                DirectGroup.verify()
            }
            return
        }
        if (DirectGroup.starting) {
            st(LinkKind.WORK, "正在创建热点…")
            return
        }
        if (retryJob?.isActive == true) return   // 正在等下一次重试（状态已由 fail 设置）

        // 不再先扫描等待：选了建热点就直接建，选了接收就直接去连对方的热点（系统会自己等它出现）
        if (role == LinkRole.HOST) hostStep(app) else clientStep(app, t)
    }

    /**
     * 本机热点已建好、对方还没连上时，每 30 秒重新扫一次、核对一次：
     * 对方可能晚一步才建了热点（两边都在等，谁也不动），或者对方断开又重新打开了
     */
    private fun armRecheck() {
        if (recheckJob?.isActive == true) return
        recheckJob = Hub.scope.launch {
            delay(30_000)
            recheckJob = null
            if (DirectGroup.up.value && target != null) {
                scanReqAt = 0L
                kicks.trySend(Unit)
            }
        }
    }

    private fun hostStep(app: Context) {
        st(LinkKind.WORK, "正在创建本机热点…")
        DirectGroup.start(app, LinkCred.of(Identity.fp)) { msg ->
            if (!LocationSwitch.isOn(app)) askLocation()
            fail(msg)
        }
    }

    private fun clientStep(app: Context, t: Peer) {
        if (HotspotJoin.active.value || clientJob?.isActive == true) return
        val cred = LinkCred.of(t.fp)
        val p = JoinParams(cred.ssid, cred.pwd, t.id, t.name, t.port, "", t.fp)
        joinedId = t.id
        st(LinkKind.WORK, "正在等待 ${t.name} 的热点…（如有系统弹窗请点\u201c连接\u201d）")
        clientJob = Hub.scope.launch {
            val done = CompletableDeferred<Boolean>()
            HotspotJoin.join(app, p, auto = true, done = done)
            if (done.await()) {
                failures = 0
                st(LinkKind.OK, "已连接：${t.name}")
            } else {
                HotspotJoin.leave()
                joinedId = null
                fail("没连上 ${t.name}：请确认对方选了\u201c建热点\u201d，并开着 Wi-Fi 开关")
            }
        }
    }

    /** 只弹一次：用户取消后不再反复打扰；之后在系统里把定位打开了，会收到定位开关变化的事件，自动继续 */
    private fun askLocation() {
        if (!locAsked) {
            locAsked = true
            needLocation.value = true
        }
    }

    /** 断开本 app 自己建 / 连的热点，并把本 app 自己开的定位关掉（别人开的不碰） */
    private fun closeLink() {
        retryJob?.cancel()
        retryJob = null
        scanTimer?.cancel()
        recheckJob?.cancel()
        recheckJob = null
        choiceJob?.cancel()
        recvJob?.cancel()
        role = null
        choiceAsked = false
        recvHint = false
        needChoice.value = false
        clientJob?.cancel()
        clientJob = null
        HotspotJoin.leave()
        DirectGroup.stop()
        joinedId = null
        status.value = null
        scanReqAt = 0L
        scanDoneAt = 0L
        failures = 0
        shown.clear()
        locAsked = false
        needLocation.value = false
        if (Store.ownLocation) {
            LocationSwitch.set(Hub.app, false)
            Store.ownLocation = false
        }
    }

    /** 退出 app：等传输结束（最多 10 分钟），然后只关本 app 自己开的 */
    fun exit() {
        Hub.scope.launch {
            var waited = 0
            while (waited < 600 && !Hub.appVisible &&
                Hub.msgs.value.any { it.state == MsgState.SENDING || it.state == MsgState.RECEIVING }
            ) {
                delay(1000)
                waited++
            }
            if (Hub.appVisible) return@launch   // 中途又打开了 app，不关
            target = null
            watchers.forEach { it.cancel() }
            watchers = emptyList()
            keeper?.cancel()
            keeper = null
            unregisterReceiver()
            HotspotHost.stop()
            closeLink()
        }
    }
}

/** 用户从最近任务里划掉 app 时，系统会回调这里；此时也要关掉本 app 开的热点和定位 */
class ExitWatcher : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        AutoLink.exit()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
