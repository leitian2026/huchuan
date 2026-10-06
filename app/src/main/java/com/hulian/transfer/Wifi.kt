package com.hulian.transfer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
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

/** 没有 Wi-Fi 时（本机当"主机"）：开一个仅用于互传的本地热点，二维码里放热点账号密码 */
object HotspotHost {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    val payload = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start(ctx: Context) {
        stop()
        error.value = null
        Hub.touch()
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
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
                    payload.value = JSONObject().put("t", "hl").put("ssid", ssid).put("pwd", pwd)
                        .put("id", Store.deviceId).put("name", Store.deviceName).put("port", Hub.port)
                        .put("k", Hub.newPairToken()).put("fp", Identity.fp)
                        .toString()
                }

                override fun onStopped() {
                    payload.value = null
                }

                override fun onFailed(reason: Int) {
                    payload.value = null
                    error.value = "热点启动失败（代码 $reason）。请确认已打开系统的\u201c定位\u201d开关，并关闭本机正在使用的个人热点后重试。也可以不用本功能：自己在系统里打开\u201c个人热点\u201d，本页会自动显示二维码"
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            error.value = if (e is SecurityException) {
                "创建热点缺少权限：请点下方\u201c去设置\u201d授权" + hotspotPermName() + "，并确认系统的定位开关已打开。也可以不授权：自己在系统里打开\u201c个人热点\u201d，本页会自动显示二维码"
            } else {
                "无法启动热点：" + (e.message ?: "未知错误")
            }
        }
    }

    fun stop() {
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
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

    fun join(ctx: Context, p: JoinParams) {
        leave()
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
        status.value = "正在连接对方热点…（请在系统弹窗中点\u201c连接\u201d）"
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
                Hub.touch()
                status.value = ""
                Hub.toast("已连接到对方热点")
                Hub.scope.launch {
                    // 用二维码里的一次性口令配对；失败的原因 Hub.pair 会弹提示
                    if (Hub.pair(p.peerId, p.peerName, gw, p.port, p.token, p.hostFp)) connected.value = p.peerId
                }
            }

            override fun onUnavailable() {
                // 自动连接没成功（用户取消、系统不弹窗或超时）：提供手动连接的办法
                active.value = false
                status.value = ""
                manual.value = p
            }

            override fun onLost(network: Network) {
                active.value = false
                status.value = "热点连接已断开"
                try { m.bindProcessToNetwork(null) } catch (_: Exception) {}
                clearStatusLater()
            }
        }
        callback = cb
        try {
            m.requestNetwork(req, cb, 30000)
        } catch (e: Exception) {
            // 比如系统拒绝了权限：不再被当成"二维码无效"，直接改为手动连接
            callback = null
            active.value = false
            status.value = ""
            manual.value = p
        }
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
        try { m.bindProcessToNetwork(net) } catch (_: Exception) { return }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(n: Network) {
                if (n == net) {
                    active.value = false
                    try { m.bindProcessToNetwork(null) } catch (_: Exception) {}
                }
            }
        }
        callback = cb
        try {
            m.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        } catch (_: Exception) {}
        active.value = true
    }

    /** 用户在系统里手动连上对方热点后，点"已连接，继续" */
    fun continueManual(ctx: Context, p: JoinParams) {
        val m = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        val found = findWifi(m)
        if (found == null) {
            Hub.toast("还没有连上 Wi-Fi，请先在系统设置里连接对方的热点")
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
        } catch (_: Exception) {}
    }

    fun openWifiSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** 断开对方热点；系统会自动回到原来的 Wi-Fi */
    fun leave() {
        try { cm?.bindProcessToNetwork(null) } catch (_: Exception) {}
        callback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        active.value = false
        status.value = ""
    }
}
