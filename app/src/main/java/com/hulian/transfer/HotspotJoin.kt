package com.hulian.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.CompletableDeferred
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress

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
        return target.size == 4 && lp.linkAddresses.any { la ->
            val a = la.address
            a is Inet4Address && Net.samePrefix(a.address, target, la.prefixLength)
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
