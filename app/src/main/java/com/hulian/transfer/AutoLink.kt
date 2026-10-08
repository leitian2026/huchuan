package com.hulian.transfer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

enum class LinkKind { WORK, OK, WARN }

/** 不在同一 Wi-Fi 时本机的角色，首次配对时就定下：开二维码的一方建热点（HOST），扫码的一方去连对方的热点（RECV） */
enum class LinkRole { HOST, RECV }

/** 对话框顶部显示的连接状态。action：""=无，"retry"=点一下重试，"location"=点一下去系统设置打开定位 */
data class LinkStatus(val kind: LinkKind, val text: String, val action: String = "")

/**
 * 点开某台已配对设备的对话框时自动用热点连接，离开对话框（或退出 app）自动断开：
 * - 平时没有任何热点。你点开对话框、对方也点开你的对话框（两边各自点开，不需要通知对方）
 * - 点开后如果已经在同一个 Wi-Fi（对方局域网在线）就什么都不用做；否则按首次配对时定下的角色直接连，不再弹窗选择：
 *   谁当初开了二维码，谁就建热点；扫码的一方去连对方的热点
 * - 旧版本配对的设备没有记录角色：两边都按“设备 ID 小的建热点”，结果一致
 * - 万一两台都建了热点：设备 ID 小的保留，另一台撤掉自己的改为连接
 * - 热点名称和密码由主机自己的证书算出，所有已配对设备都能算出来，不用扫码、不弹窗
 * - 完全由事件触发，没有定时轮询（省电）：点开对话框、Wi-Fi / 定位开关变化、扫描结果、授权完成、设备上线下线、连接断开……才检查。
 *   监听只在 app 在前台时注册。只有“没连上 / 创建失败”时才每 2 秒重试一次，最多 12 次
 * - 只关本 app 自己建的群组、自己改开的 Wi-Fi / 定位；手动开的 / 别的软件开的一律不碰
 */
object AutoLink {
    /** 需要用户去系统里手动打开定位（本 app 没有权限自己开） */
    val needLocation = MutableStateFlow(false)

    /** 当前连接状态（对话框顶部显示）；null 表示不显示 */
    val status = MutableStateFlow<LinkStatus?>(null)

    /** 给界面列表显示用：(设备 ID, 本机面对它的角色)。role 每次改变（定下、互换、清空）都会同步到这里 */
    val roleState = MutableStateFlow<Pair<String, LinkRole>?>(null)

    /** 本次对话框里本机的角色；null 表示还没确定（step 里按配对时的记录定下） */
    @Volatile private var role: LinkRole? = null
        set(v) {
            field = v
            roleState.value = v?.let { r -> target?.let { id -> id to r } }
        }

    /** 本机对这台设备的角色：首次配对时谁开的二维码谁建热点；旧数据没记录就按设备 ID（小的建热点），两边算出来一致 */
    fun roleFor(t: Peer): LinkRole = when (t.iHost) {
        true -> LinkRole.HOST
        false -> LinkRole.RECV
        null -> if (Store.deviceId < t.id) LinkRole.HOST else LinkRole.RECV
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
        }
    }

    /** 当前打开着对话框的设备 ID；null 表示没有（此时不自动连接） */
    @Volatile private var target: String? = null
    @Volatile private var joinedId: String? = null

    private var keeper: Job? = null
    private var retryJob: Job? = null
    private var scanTimer: Job? = null
    private var recheckJob: Job? = null
    @Volatile private var recheckN = 0
    private var watchers: List<Job> = emptyList()
    private var appCtx: Context? = null
    private var clientJob: Job? = null
    private var registered = false
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var paused = false
    @Volatile private var failures = 0
    @Volatile private var wifiTried = false      // 这次对话框里已经试过自动打开 Wi-Fi，失败了不反复弹 root 授权
    @Volatile private var wifiOpening = false
    @Volatile private var wifiWarnAt = 0L        // Wi-Fi 一直关着时开始计时：关满 1 秒才显示红字，避免一闪而过
    @Volatile private var locTried = false       // 同上：定位这次对话框里只自动打开一次
    @Volatile private var locOpening = false
    @Volatile private var scanReqAt = 0L
    @Volatile private var scanDoneAt = 0L
    @Volatile private var openAt = 0L           // 点开对话框的时刻：远程中转还没出结果时，最多等到这之后 RELAY_WAIT_MS 毫秒
    private var locAsked = false

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

    /** 失败后重试：每 2 秒一次，共 12 次（单次连接最长 12 秒，对方晚建热点时靠重新发起连接来重新搜索，总共能等约 3 分钟） */
    private const val RETRY_MS = 2_000L
    private const val RETRY_MAX = 12

    /** 远程优先：点开对话框后，远程中转还在连接时最多等这么久，再决定要不要用热点 */
    private const val RELAY_WAIT_MS = 8_000L

    /** 热点已建好、等对方时的核对：每 2 秒一次，共 6 次 */
    private const val RECHECK_MS = 2_000L
    private const val RECHECK_TIMES = 6

    private fun later(ms: Long) {
        retryJob?.cancel()
        retryJob = Hub.scope.launch {
            delay(ms)
            retryJob = null
            kicks.trySend(Unit)
        }
    }

    /** 失败后重试：每 2 秒一次，最多 12 次，之后等下一次事件 */
    private fun fail(reason: String) {
        failures++
        scanReqAt = 0L
        if (failures > RETRY_MAX) {
            st(LinkKind.WARN, "$reason。已停止自动重试（点这里重试）", "retry")
            return
        }
        st(LinkKind.WARN, "$reason，稍后自动重试（第 $failures/$RETRY_MAX 次，点这里立即重试）", "retry")
        later(RETRY_MS)
    }

    /** 设置里把“热点”关掉：撤掉本 app 建的 / 连的热点，还原自动打开的 Wi-Fi / 定位，并停掉自动重试 */
    fun hotspotDisabled() {
        retryJob?.cancel()
        retryJob = null
        scanTimer?.cancel()
        recheckJob?.cancel()
        recheckJob = null
        clientJob?.cancel()
        clientJob = null
        role = null
        joinedId = null
        HotspotJoin.leave()
        HotspotHost.stop()
        DirectGroup.stop()
        restoreSwitches()
        kicks.trySend(Unit)
    }

    /** 新开界面时恢复（用户手动“断开”之后，重新点开对话框才会再连） */
    fun resume() { paused = false }

    /** 用户在聊天菜单里手动断开：这次对话框里不再自动重连 */
    fun pause() {
        paused = true
        role = null
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
        } catch (e: Exception) { Hub.log("AutoLink", e) }
    }

    private fun unregisterReceiver() {
        if (!registered) return
        try { appCtx?.unregisterReceiver(receiver) } catch (e: Exception) { Hub.log("AutoLink", e) }
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
        openAt = now()   // 刚回到前台，远程中转可能正在重连：重新给它一点时间，别马上去开 Wi-Fi / 定位
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
        if (target != peerId) role = null
        target = peerId
        openAt = now()
        if (Store.hotspotOn) st(LinkKind.WORK, "正在准备连接…") else status.value = null
        registerReceiver(app)
        if (keeper?.isActive != true) {
            try { app.startService(Intent(app, ExitWatcher::class.java)) } catch (e: Exception) { Hub.log("AutoLink", e) }
            keeper = Hub.scope.launch {
                for (k in kicks) {
                    if ((Hub.appVisible || Hub.picking) && !paused && target != null) {
                        try { step(app) } catch (e: Exception) { Hub.log("AutoLink", e) }
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
                // 远程中转连上 / 断开 / 对方上线下线 / 名单到达：远程优先，热点只在远程用不了时才作为备用，所以这些变化都要重新判断
                Hub.scope.launch { Relay.connected.collect { kick() } },
                Hub.scope.launch { Relay.presence.collect { kick() } },
                Hub.scope.launch { Relay.online.collect { kick() } },
                Hub.scope.launch { HotspotJoin.active.collect { on -> if (!on) scanReqAt = 0L; kick(reset = false) } },
                Hub.scope.launch { DirectGroup.up.collect { on -> if (!on) scanReqAt = 0L; kick(reset = false) } },
                // 二维码临时热点创建完成 / 关闭
                Hub.scope.launch { HotspotHost.payload.collect { kick(reset = false) } }
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
            Hub.byeJob?.join()   // 先让“我退出了”的通知发出去，再断热点
            if (target == null) closeLink()
        }
    }

    private enum class LocState { OK, OPENING, MANUAL }

    /**
     * 定位没开时：能自己开就开（root 或 adb 授予的 WRITE_SECURE_SETTINGS），和 Wi-Fi 一样每次对话框只试一次，失败了不反复弹 root 授权。
     * Android 12 及以下扫描和建热点都离不开定位，自动打开失败只能引导用户去开
     */
    private fun ensureLocation(app: Context): LocState {
        if (LocationSwitch.isOn(app)) return LocState.OK
        if (locOpening) return LocState.OPENING   // 正在打开：等系统的定位开关广播再继续，不轮询
        if (!locTried) {
            locTried = true
            locOpening = true
            Hub.scope.launch {
                try {
                    AutoOpen.location(app)   // 本 app 打开的会记到 Store.ownLocation，退出对话框时还原
                } catch (e: Exception) {
                    Hub.log("AutoLink", e)
                } finally {
                    locOpening = false
                }
                kick(reset = false)
            }
            return LocState.OPENING
        }
        if (Build.VERSION.SDK_INT < 33) {
            askLocation()
            return LocState.MANUAL
        }
        return LocState.OK   // Android 13+：有的系统已不要求定位，先直接试，失败了再提示
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun hasSignal(wm: WifiManager, p: Peer): Boolean {
        // 只认 20 秒内的扫描结果：对方刚关掉的热点还留在系统缓存里，信了就会去连一个已经不存在的热点，白等超时再退避重试
        val nowUs = SystemClock.elapsedRealtime() * 1000
        val ssid = LinkCred.of(p.fp).ssid
        return try {
            wm.scanResults.any { nowUs - it.timestamp < 20_000_000L && it.SSID == ssid }
        } catch (e: Exception) {
            Hub.log("AutoLink", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun requestScan(wm: WifiManager) {
        scanReqAt = now()
        scanDoneAt = 0L
        try { wm.startScan() } catch (e: Exception) { Hub.log("AutoLink", e) }
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
        // 设置里关了“热点”：不自动开 Wi-Fi / 定位 / 热点，也不去连对方的热点；顶栏不显示任何自动连接状态
        if (!Store.hotspotOn) {
            status.value = null
            return
        }
        // 二维码配对用的临时热点 / 手动连接还留着：以前这里直接什么都不做、状态条也不显示，
        // 配对后自动连接就永远不会开始（没连 Wi-Fi 时扫码配对最容易出现）。
        // 对方已经在线说明它还在用，保留；对方不在线说明已经没用了，关掉后再继续自动连接
        val manualLink = HotspotJoin.active.value && !HotspotJoin.autoMode
        if (HotspotHost.isUp() || HotspotHost.starting || manualLink) {
            if (t.online) {
                st(LinkKind.OK, "已连接：${t.name}")
            } else if (HotspotHost.starting) {
                st(LinkKind.WORK, "正在关闭二维码用的临时热点…")   // 创建结果一出来 payload 变化会触发再检查
            } else {
                st(LinkKind.WORK, "正在关闭二维码用的临时热点，然后自动连接…")
                HotspotHost.stop()
                if (manualLink) HotspotJoin.leave()
            }
            return
        }
        // 远程优先：双方密钥齐了、对方也连着 Cloudflare，就走远程中转，不开 Wi-Fi / 定位 / 热点（省电）。
        // 热点只在远程用不了时才作为备用。对方已经直连在线的（热点 / 同一 Wi-Fi）保持不动
        if (!t.online && Relay.ready(t)) {
            if (Relay.connected.value && t.id in Relay.online.value) {
                retryJob?.cancel()
                retryJob = null
                scanTimer?.cancel()
                recheckJob?.cancel()
                recheckJob = null
                clientJob?.cancel()
                clientJob = null
                joinedId = null
                failures = 0
                scanReqAt = 0L
                // 正在等对方热点 / 本机热点还在等人：不用了，撤掉（本 app 自己建 / 连的才会动）
                if (HotspotJoin.active.value) HotspotJoin.leave()
                if (DirectGroup.up.value || DirectGroup.starting) DirectGroup.stop()
                st(LinkKind.OK, "已连接：${t.name}（远程中转，不用开热点）")
                return
            }
            // 远程中转还在连接：先等一小会儿再决定（事件一到就会重新检查；超时再用热点备用）
            val waited = now() - openAt
            if (Relay.settling() && waited < RELAY_WAIT_MS) {
                st(LinkKind.WORK, "正在联系远程中转…")
                later(RELAY_WAIT_MS - waited + 50)
                return
            }
        }
        // 已经连上对方的热点（或正在连）
        if (HotspotJoin.active.value) {
            when {
                t.online -> st(LinkKind.OK, "已连接：${t.name}")
                HotspotJoin.linked -> st(LinkKind.WORK, "已连上对方的热点，正在联系 ${t.name}…")
                else -> st(LinkKind.WORK, "正在等待 ${t.name} 的热点…（对方打开和本机的对话后自动连接）")
            }
            return
        }
        // 对方已经通过同一个 Wi-Fi 在线（原来的局域网方式能用）：不用选，也不用建热点
        // 光看“在线”标记不够：上一次热点连接留下的标记要过十几秒才会过期，这时对方地址已经不在本机的局域网里了
        if (t.online && !DirectGroup.up.value && Net.onLan(t.host)) {
            st(LinkKind.OK, "已连接：${t.name}（同一 Wi-Fi）")
            return
        }
        // 角色在首次配对时就定了：开二维码的一方建热点，扫码的一方去连。不弹窗
        val myRole = role ?: roleFor(t).also { role = it }
        if (!hotspotCoreGranted(app)) {
            st(LinkKind.WARN, "需要授予" + hotspotPermName() + "权限，授权后自动继续")
            return
        }
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            // 正在打开：等系统的 Wi-Fi 状态广播再继续，不轮询
            if (wm.wifiState == WifiManager.WIFI_STATE_ENABLING || wifiOpening) {
                st(LinkKind.WORK, "正在打开 Wi-Fi…")
                return
            }
            if (!wifiTried) {
                wifiTried = true
                wifiOpening = true
                st(LinkKind.WORK, "正在打开 Wi-Fi…")
                Hub.scope.launch {
                    val ok = WifiSwitch.set(true)
                    wifiOpening = false
                    if (ok) Store.ownWifi = true   // 本来是关的、这次由本 app 打开：退出对话框时要还原
                    kick(reset = false)
                }
                return
            }
            // 刚点开对话框时 Wi-Fi 状态可能正在变化（比如上次退出时还原的开关还没稳定）：先显示“正在打开”，关满 1 秒还没开才显示红字
            if (wifiWarnAt == 0L) {
                wifiWarnAt = now()
                Hub.scope.launch { delay(1100); kicks.trySend(Unit) }
            }
            if (now() - wifiWarnAt < 1000) {
                st(LinkKind.WORK, "正在打开 Wi-Fi…")
                return
            }
            st(LinkKind.WARN, "请打开 Wi-Fi 开关（不需要连接任何网络），打开后自动连接。自动打开失败：需要 root 授权")
            return
        }
        wifiWarnAt = 0L
        when (ensureLocation(app)) {
            LocState.OPENING -> {
                st(LinkKind.WORK, "正在打开定位…")
                return
            }
            LocState.MANUAL -> {
                st(LinkKind.WARN, "需要打开系统的定位开关才能继续（点这里去设置）。自动打开失败：需要 root 授权", "location")
                return
            }
            LocState.OK -> {}
        }

        // 选的是“接收信号”，但本机热点还开着（比如刚由建热点改成接收）：先关掉
        if (myRole == LinkRole.RECV && (DirectGroup.up.value || DirectGroup.starting)) {
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
                recheckN = 0
            } else {
                st(LinkKind.WORK, "热点已建好，等待 ${t.name} 连接…（对方也要打开和本机的对话）")
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

        // 不再先扫描等待：本机是建热点的一方就直接建，否则直接去连对方的热点（系统会自己等它出现）
        if (myRole == LinkRole.HOST) hostStep(app) else clientStep(app, t)
    }

    /**
     * 本机热点已建好、对方还没连上时，每 2 秒核对一次、共 6 次（约 12 秒），之后每 30 秒一次（都是一次性延时，不是持续轮询）：
     * 对方可能晚一步才建了热点（两边都在等，谁也不动），或者对方断开又重新打开了
     */
    private fun armRecheck() {
        if (recheckJob?.isActive == true) return
        recheckJob = Hub.scope.launch {
            // 前 6 次每 2 秒核对一次，之后才放到 30 秒：对方的热点往往就是比我们晚几秒建好，
            // 一次性延时，不是轮询；系统对前台扫描有次数限制（约 2 分钟 4 次），所以后面放长
            delay(if (recheckN++ < RECHECK_TIMES) RECHECK_MS else 30_000L)
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
        st(LinkKind.WORK, "正在等待 ${t.name} 的热点…（对方打开和本机的对话后自动连接）")
        clientJob = Hub.scope.launch {
            val done = CompletableDeferred<Boolean>()
            P2pJoin.join(app, p, done)
            if (done.await()) {
                failures = 0
                st(LinkKind.OK, "已连接：${t.name}")
            } else {
                HotspotJoin.leave()
                joinedId = null
                fail("没连上 ${t.name}${P2pJoin.lastError}：请确认对方也打开了和本机的对话，并开着 Wi-Fi 开关")
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
        recheckN = 0
        role = null
        clientJob?.cancel()
        clientJob = null
        HotspotJoin.leave()
        DirectGroup.stop()
        joinedId = null
        status.value = null
        scanReqAt = 0L
        scanDoneAt = 0L
        failures = 0
        locAsked = false
        needLocation.value = false
        // 上面的群组已经先拆了；再还原本 app 打开的 Wi-Fi / 定位（本来就开着的、用户自己开的不动）
        wifiTried = false
        wifiWarnAt = 0L
        locTried = false
        restoreSwitches()
    }

    /**
     * 还原本 app 自己打开的 Wi-Fi 和定位开关：只关记录里“本 app 打开”的；本来就开着的、用户自己开的不动。
     * 离开对话框 / 退出 app / 扫码配对没成功时调用。还原是异步的（要借 root），执行时如果又点开了对话框就不关
     */
    fun restoreSwitches() {
        if (Store.ownLocation) {
            Store.ownLocation = false
            Hub.scope.launch {
                try {
                    if (target == null && LocationSwitch.isOn(Hub.app)) LocationSwitch.setAny(Hub.app, false)
                } catch (e: Exception) { Hub.log("AutoLink", e) }
            }
        }
        if (Store.ownWifi) {
            Store.ownWifi = false
            Hub.scope.launch {
                try {
                    if (target == null && WifiSwitch.isOn(Hub.app)) WifiSwitch.set(false)
                } catch (e: Exception) { Hub.log("AutoLink", e) }
            }
        }
    }

    /**
     * 出示二维码 / 扫码配对之前调用：把自动连接留下的状态全部清掉（本机建的群组、加入的群组、重试和等待、状态条）。
     * Wi-Fi 芯片没法同时处理上一个方向留下的群组和配对用的临时热点，所以要先关干净再开始。
     * 有文件正在传输时不动（不能把传输掐断）。返回 true 表示确实关掉了东西，调用方要等一小会儿让系统拆完
     */
    fun releaseForPairing(): Boolean {
        if (Hub.msgs.value.any { it.state == MsgState.SENDING || it.state == MsgState.RECEIVING }) return false
        val had = DirectGroup.up.value || DirectGroup.starting || HotspotJoin.active.value || P2pJoin.running
        target = null
        retryJob?.cancel()
        retryJob = null
        scanTimer?.cancel()
        recheckJob?.cancel()
        recheckJob = null
        recheckN = 0
        role = null
        clientJob?.cancel()
        clientJob = null
        joinedId = null
        failures = 0
        scanReqAt = 0L
        scanDoneAt = 0L
        status.value = null
        HotspotJoin.leave()
        DirectGroup.stop()
        return had
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
