package com.hulian.transfer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

/** 没有 Wi-Fi 时（本机当"主机"）：开一个仅用于互传的本地热点，二维码里放热点账号密码。热点由本 app 创建、只由本 app 关闭，不影响系统自带的个人热点 */
object HotspotHost {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var curSsid = ""
    private var curPwd = ""
    val payload = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    /** 正在等系统创建热点（还没返回结果） */
    @Volatile var starting = false
        private set

    /** 本 app 创建的热点已经在运行 */
    fun isUp(): Boolean = reservation != null && payload.value != null

    private fun build(): String = JSONObject().put("t", "hl").put("ssid", curSsid).put("pwd", curPwd)
        .put("id", Store.deviceId).put("name", Store.deviceName).put("port", Hub.port)
        .put("k", Hub.newPairToken()).put("fp", Identity.fp)
        .toString()

    /** 热点不重开，只换一个新的一次性配对口令（打开"我的二维码"页时用） */
    fun refreshPayload() {
        if (reservation != null) payload.value = build()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start(ctx: Context) {
        stop()
        error.value = null
        Hub.touch()
        starting = true
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    starting = false
                    reservation = r
                    val ssid: String
                    val pwd: String
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = r.softApConfiguration
                        ssid = c.ssid ?: ""
                        pwd = c.passphrase ?: ""
                    } else {
                        val c = r.wifiConfiguration
                        ssid = (c?.SSID ?: "").removeSurrounding("\"")
                        pwd = (c?.preSharedKey ?: "").removeSurrounding("\"")
                    }
                    Hub.touch()
                    curSsid = ssid
                    curPwd = pwd
                    payload.value = build()
                }

                override fun onStopped() {
                    starting = false
                    payload.value = null
                }

                override fun onFailed(reason: Int) {
                    starting = false
                    payload.value = null
                    val why = when (reason) {
                        ERROR_NO_CHANNEL -> "没有可用的无线信道"
                        ERROR_GENERIC -> "系统内部错误"
                        ERROR_INCOMPATIBLE_MODE -> "与当前 Wi-Fi 连接冲突，本机不能同时连着 Wi-Fi 又建热点"
                        ERROR_TETHERING_DISALLOWED -> "系统禁止本机创建热点"
                        else -> "未知原因"
                    }
                    error.value = "热点启动失败：$why（代码 $reason）。请确认已打开系统的\u201c定位\u201d开关，并关闭本机正在使用的个人热点后重试。也可以不用本功能：自己在系统里打开\u201c个人热点\u201d，本页会自动显示二维码"
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            starting = false
            error.value = if (e is SecurityException) {
                "创建热点缺少权限：请点下方\u201c去设置\u201d授权" + hotspotPermName() + "，并确认系统的定位开关已打开。也可以不授权：自己在系统里打开\u201c个人热点\u201d，本页会自动显示二维码"
            } else {
                "无法启动热点：" + (e.message ?: "未知错误")
            }
        }
    }

    fun stop() {
        try { reservation?.close() } catch (e: Exception) { Hub.log("Wifi", e) }
        reservation = null
        starting = false
        curSsid = ""
        curPwd = ""
        payload.value = null
    }
}

/** 扫到热点二维码后要用到的信息 */
class JoinParams(
    val ssid: String, val pwd: String,
    val peerId: String, val peerName: String, val port: Int,
    val token: String, val hostFp: String
)

/** 没有 Wi-Fi 时（本机当"客人"）：扫码后连到对方热点，再向网关配对。没有权限时改为手动连接 */
object HotspotJoin {
    val status = MutableStateFlow("")
    val connected = MutableStateFlow<String?>(null)
    /** 本机当前是否连着（或正在连）对方热点 */
    val active = MutableStateFlow(false)
    /** 缺少自动连接热点所需的权限，等界面去申请 */
    val needPerm = MutableStateFlow<JoinParams?>(null)
    /** 自动连接用不了（没权限 / 被系统拒绝 / 超时）：让用户手动连，连上后继续配对 */
    val manual = MutableStateFlow<JoinParams?>(null)

    /** 自动连接用不了的具体原因（手动连接窗口里显示） */
    val manualReason = MutableStateFlow("")
    /** 当前这次连接是自动连接（打开 app 自动连固定热点）：空闲不自动断 */
    @Volatile var autoMode = false
    /** 已经真正连上对方的热点（拿到了网关地址）；active 只表示“正在连或已连上” */
    @Volatile var linked = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var cm: ConnectivityManager? = null

    private fun clearStatusLater() {
        Hub.scope.launch {
            delay(3000)
            if (!active.value) status.value = ""
        }
    }

    /** 入口：有权限就自动连；没有就先去申请 */
    fun start(ctx: Context, p: JoinParams) {
        if (hotspotCoreGranted(ctx)) join(ctx, p) else needPerm.value = p
    }

    /** auto=true：打开 app 时自动连对方的固定热点。已配对，所以不再走一次性口令，只验证身份并刷新对方地址；也不跳转聊天。done 用来告诉调用方这次成没成 */
    fun join(ctx: Context, p: JoinParams, auto: Boolean = false, done: kotlinx.coroutines.CompletableDeferred<Boolean>? = null) {
        leave()
        autoMode = auto
        Hub.touch()
        val m = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        cm = m
        val spec = WifiNetworkSpecifier.Builder().setSsid(p.ssid).setWpa2Passphrase(p.pwd).build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        active.value = true
        // 自动连接的进度由对话框顶部的状态条显示，这里只在手动扫码连接时显示
        status.value = if (auto) "" else "正在连接对方热点…（请在系统弹窗中点\u201c连接\u201d）"
        var finished = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                m.bindProcessToNetwork(network)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (finished) return
                var gw = lp.routes.firstOrNull { it.gateway is Inet4Address && it.hasGateway() }?.gateway?.hostAddress
                if (gw == null && Build.VERSION.SDK_INT >= 30) {
                    gw = lp.dhcpServerAddress?.hostAddress
                }
                if (gw == null) return
                finished = true
                linked = true
                Hub.touch()
                status.value = ""
                Hub.toast("已连接到对方热点")
                Hub.scope.launch {
                    if (auto) {
                        done?.complete(reconnect(p, gw))
                    } else if (Hub.pair(p.peerId, p.peerName, gw, p.port, p.token, p.hostFp)) {
                        // 用二维码里的一次性口令配对；失败的原因 Hub.pair 会弹提示
                        connected.value = p.peerId
                    }
                }
            }

            override fun onUnavailable() {
                linked = false
                active.value = false
                status.value = ""
                if (auto) {
                    done?.complete(false)
                } else {
                    // 自动连接没成功（用户取消、系统不弹窗或超时）：提供手动连接的办法
                    manualReason.value = "15 秒内没有连上对方热点：系统弹出的“连接”窗口被取消或没有点，或热点名称 / 密码不对，或对方的热点已经关闭"
                    manual.value = p
                }
            }

            override fun onLost(network: Network) {
                linked = false
                active.value = false
                done?.complete(false)
                if (!auto) status.value = "热点连接已断开"
                try { m.bindProcessToNetwork(null) } catch (e: Exception) { Hub.log("Wifi", e) }
                clearStatusLater()
            }
        }
        callback = cb
        try {
            m.requestNetwork(req, cb, 15000)   // 15 秒内没连上就放弃（含系统弹窗等用户点“连接”的时间）
        } catch (e: Exception) {
            // 比如系统拒绝了权限：不再被当成"二维码无效"，直接改为手动连接
            callback = null
            active.value = false
            status.value = ""
            if (auto) done?.complete(false) else {
                manualReason.value = "系统拒绝了连接请求：" + (e.message ?: e.javaClass.simpleName)
                manual.value = p
            }
        }
    }

    /** 自动重连后：用已配对的证书验证对方，成功就刷新地址并标为在线（对方可能还在启动，每 2 秒核对一次，共 6 次） */
    suspend fun reconnect(p: JoinParams, gw: String): Boolean {
        val known = Hub.peers.value[p.peerId]?.takeIf { it.paired }
        if (known == null) {
            Hub.toast("这台设备已被删除，请重新扫码配对")
            return false
        }
        val target = known.copy(host = gw, port = p.port)
        for (i in 0 until 6) {
            if (Net.ping(target)) {
                Hub.upsertPeer(known.id, known.name, gw, p.port)
                Hub.markOnline(known.id)
                Hub.onNetworkChanged()
                return true
            }
            delay(2000)
        }
        Hub.toast("已连上热点，但对方还没有打开互传")
        return false
    }

    // ---------- 免权限的手动方式 ----------

    @Suppress("DEPRECATION")
    private fun findWifi(m: ConnectivityManager): Pair<Network, String>? {
        for (n in m.allNetworks) {
            val caps = m.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = m.getLinkProperties(n) ?: continue
            var gw = lp.routes.firstOrNull { it.gateway is Inet4Address && it.hasGateway() }?.gateway?.hostAddress
            if (gw == null && Build.VERSION.SDK_INT >= 30) gw = lp.dhcpServerAddress?.hostAddress
            if (gw != null) return Pair(n, gw)
        }
        return null
    }

    /** Wi-Fi 没有网络时系统默认走流量，访问不到里面的对方；绑定到这个 Wi-Fi 才行。Wi-Fi 断开时自动解除 */
    private fun bindTo(m: ConnectivityManager, net: Network) {
        cm = m
        try { m.bindProcessToNetwork(net) } catch (e: Exception) { Hub.log("绑定到对方 Wi-Fi", e); return }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(n: Network) {
                if (n == net) {
                    active.value = false
                    try { m.bindProcessToNetwork(null) } catch (e: Exception) { Hub.log("Wifi", e) }
                }
            }
        }
        callback = cb
        try {
            m.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        } catch (e: Exception) { Hub.log("Wifi", e) }
        active.value = true
    }

    /** 用户在系统里手动连上对方热点后，点"已连接，继续" */
    fun continueManual(ctx: Context, p: JoinParams) {
        val m = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        val found = findWifi(m)
        if (found == null) {
            Hub.toast("还没有连上 Wi-Fi，请先在系统设置里连接对方的热点")
            manualReason.value = "本机还没有在系统里连上对方的热点"
            manual.value = p
            return
        }
        leave()
        bindTo(m, found.first)
        Hub.scope.launch {
            if (Hub.pair(p.peerId, p.peerName, found.second, p.port, p.token, p.hostFp)) connected.value = p.peerId
        }
    }

    private fun sameSubnet(lp: LinkProperties, ip: String): Boolean {
        val target = try { InetAddress.getByName(ip).address } catch (e: Exception) { return false }
        if (target.size != 4) return false
        return lp.linkAddresses.any { la ->
            val a = la.address
            if (a !is Inet4Address) {
                false
            } else {
                val ab = a.address
                var same = true
                for (i in 0 until la.prefixLength) {
                    val mask = 0x80 shr (i % 8)
                    if ((ab[i / 8].toInt() and mask) != (target[i / 8].toInt() and mask)) {
                        same = false
                        break
                    }
                }
                same
            }
        }
    }

    /** 本机当前有没有任何一个网络（Wi-Fi / 热点）和 ip 在同一网段；没有就说明根本连不到对方 */
    @Suppress("DEPRECATION")
    fun onSameNetwork(ctx: Context, ip: String): Boolean {
        val m = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        for (n in m.allNetworks) {
            val caps = m.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = m.getLinkProperties(n) ?: continue
            if (sameSubnet(lp, ip)) return true
        }
        return false
    }

    /**
     * 扫到的是局域网二维码：如果对方在一个不是系统默认网络的 Wi-Fi 里
     * （比如手动开的个人热点，没有网络、本机又开着流量），就绑定到那个 Wi-Fi，否则连不到对方。
     */
    @Suppress("DEPRECATION")
    fun bindWifiFor(ctx: Context, ip: String) {
        val m = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        val def = m.activeNetwork
        for (n in m.allNetworks) {
            if (n == def) continue
            val caps = m.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = m.getLinkProperties(n) ?: continue
            if (sameSubnet(lp, ip)) {
                leave()
                bindTo(m, n)
                return
            }
        }
    }

    fun copyPassword(ctx: Context, pwd: String) {
        try {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("wifi", pwd))
            Hub.toast("密码已复制")
        } catch (e: Exception) { Hub.log("Wifi", e) }
    }

    fun openWifiSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Hub.log("Wifi", e) }
    }

    /** 断开对方热点；系统会自动回到原来的 Wi-Fi */
    fun leave() {
        P2pJoin.leave()
        try { cm?.bindProcessToNetwork(null) } catch (e: Exception) { Hub.log("Wifi", e) }
        callback?.let { try { cm?.unregisterNetworkCallback(it) } catch (e: Exception) { Hub.log("Wifi", e) } }
        callback = null
        linked = false
        active.value = false
        autoMode = false
        status.value = ""
    }
}

/**
 * 已配对设备自动连接（接收方）：用 Wi-Fi Direct 的 WifiP2pManager.connect，按群组名称 + 密码直接加入对方建好的群组。
 * 不再走 WifiNetworkSpecifier，所以没有系统的“选择设备”窗口，也就没有“该应用已取消选择设备的请求”。
 * 状态仍然记在 HotspotJoin.active / linked / autoMode 上，AutoLink 的状态条和事件监听不用改。
 * 完全由系统回调驱动：连接结果、群组变化的广播；只有一个 30 秒的一次性超时（对方群组还没建好时 connect 不会有失败回调）
 */
object P2pJoin {
    private val h = Handler(Looper.getMainLooper())
    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null
    private var rx: BroadcastReceiver? = null
    private var appCtx: Context? = null
    private var timeout: Runnable? = null
    @Volatile var running = false
        private set

    /** 最近一次失败的原因，给状态条用 */
    @Volatile var lastError = ""
        private set

    private const val CONNECT_TIMEOUT_MS = 30_000L

    @SuppressLint("MissingPermission")
    fun join(ctx: Context, p: JoinParams, done: CompletableDeferred<Boolean>, pairing: Boolean = false) {
        HotspotJoin.leave()
        lastError = ""
        val app = ctx.applicationContext
        appCtx = app
        val m = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) {
            lastError = "（本机不支持 Wi-Fi Direct）"
            done.complete(false)
            return
        }
        val c = m.initialize(app, Looper.getMainLooper(), null)
        mgr = m
        ch = c
        running = true
        HotspotJoin.autoMode = true
        HotspotJoin.linked = false
        HotspotJoin.active.value = true
        Hub.touch()

        var finished = false
        var joined = false

        fun finish(ok: Boolean, err: String = "") {
            if (finished) return
            finished = true
            timeout?.let { h.removeCallbacks(it) }
            timeout = null
            if (!ok) {
                lastError = err
                done.complete(false)
            }
        }

        fun lost() {
            if (!joined) return
            joined = false
            // leave() 会把 active 置为 false，AutoLink 监听到后自动重新连接
            HotspotJoin.leave()
        }

        fun onJoined(gw: String) {
            joined = true
            finished = true
            timeout?.let { h.removeCallbacks(it) }
            timeout = null
            HotspotJoin.linked = true
            Hub.touch()
            Hub.toast("已连接到对方热点")
            Hub.scope.launch {
                if (pairing) done.complete(Hub.pair(p.peerId, p.peerName, gw, p.port, p.token, p.hostFp))   // 扫码配对：连上群组后用一次性口令配对
                else done.complete(HotspotJoin.reconnect(p, gw))
            }
        }

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, i: Intent?) {
                val mm = mgr ?: return
                val cc = ch ?: return
                if (i != null && i.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                    if (i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        if (joined) lost() else finish(false, "（Wi-Fi Direct 被关闭了）")
                    }
                    return
                }
                try {
                    mm.requestConnectionInfo(cc) { info ->
                        if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                            if (!joined && !finished) {
                                val gw = info.groupOwnerAddress.hostAddress ?: ""
                                try {
                                    mm.requestGroupInfo(cc) { g ->
                                        // 必须是对方那个固定名称的群组，不是别的 Wi-Fi Direct 连接
                                        if (g != null && g.networkName == p.ssid && !joined && !finished && gw.isNotEmpty()) onJoined(gw)
                                    }
                                } catch (_: SecurityException) {
                                    finish(false, "（缺少权限）")
                                }
                            }
                        } else if (joined) {
                            lost()
                        }
                    }
                } catch (_: SecurityException) {
                    if (!joined) finish(false, "（缺少权限）")
                }
            }
        }
        rx = r
        try {
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) { Hub.log("Wifi", e) }

        val cfg = try {
            WifiP2pConfig.Builder().setNetworkName(p.ssid).setPassphrase(p.pwd).enablePersistentMode(false).build()
        } catch (e: Exception) {
            finish(false, "（群组参数无效）")
            return
        }
        val t = Runnable { finish(false, "（30 秒内没有加入对方的群组：对方可能已离开二维码页面、没有建成群组，或两台手机离得太远）") }
        timeout = t
        h.postDelayed(t, CONNECT_TIMEOUT_MS)
        try {
            m.connect(c, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}   // 只表示请求已发出，真正连上看广播

                override fun onFailure(reason: Int) {
                    finish(
                        false, when (reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> "（本机不支持 Wi-Fi Direct）"
                            WifiP2pManager.BUSY -> "（Wi-Fi Direct 正忙：可能正在连别的设备，或系统还在拆掉上一个连接，请等几秒再试）"
                            WifiP2pManager.ERROR -> "（Wi-Fi Direct 内部错误，代码 0：请关闭再打开 Wi-Fi 后重试）"
                            WifiP2pManager.NO_SERVICE_REQUESTS -> "（Wi-Fi Direct 没有可用的服务，代码 3）"
                            else -> "（系统返回错误 $reason）"
                        }
                    )
                }
            })
        } catch (e: SecurityException) {
            finish(false, "（缺少权限）")
        } catch (e: Exception) {
            finish(false, "（${e.message ?: "未知错误"}）")
        }
    }

    /** 断开：取消还没完成的连接、退出已加入的群组、释放通道。只处理本对象自己发起的 */
    @SuppressLint("MissingPermission")
    fun leave() {
        timeout?.let { h.removeCallbacks(it) }
        timeout = null
        rx?.let { try { appCtx?.unregisterReceiver(it) } catch (e: Exception) { Hub.log("Wifi", e) } }
        rx = null
        if (!running) return
        val m = mgr
        val c = ch
        mgr = null
        ch = null
        running = false
        if (m != null && c != null) {
            try { m.cancelConnect(c, null) } catch (e: Exception) { Hub.log("Wifi", e) }
            release(m, c, 3)
        }
    }

    /** 退出群组；失败了确认还在群组里就重试（有的系统第一次会返回“正忙”），完成后再释放通道 */
    @SuppressLint("MissingPermission")
    private fun release(m: WifiP2pManager, c: WifiP2pManager.Channel, left: Int) {
        fun closeLater() { h.postDelayed({ try { c.close() } catch (e: Exception) { Hub.log("Wifi", e) } }, 1500) }
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { closeLater() }

                override fun onFailure(reason: Int) {
                    try {
                        m.requestGroupInfo(c) { g ->
                            if (g != null && left > 0) h.postDelayed({ release(m, c, left - 1) }, 1000) else closeLater()
                        }
                    } catch (_: Exception) { closeLater() }
                }
            })
        } catch (_: Exception) { closeLater() }
    }
}
