package com.hulian.transfer

import android.annotation.SuppressLint
import android.content.Context
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address

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
                        .toString()
                }

                override fun onStopped() {
                    payload.value = null
                }

                override fun onFailed(reason: Int) {
                    payload.value = null
                    error.value = "热点启动失败（代码 $reason）。请确认已打开系统的\u201c定位\u201d开关，并关闭本机正在使用的个人热点后重试"
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            error.value = "无法启动热点：" + (e.message ?: "缺少权限")
        }
    }

    fun stop() {
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
        payload.value = null
    }
}

/** 没有 Wi-Fi 时（本机当"客人"）：扫码后连到对方热点，再向网关发消息 */
object HotspotJoin {
    val status = MutableStateFlow("")
    val connected = MutableStateFlow<String?>(null)
    /** 本机当前是否连着（或正在连）对方热点 */
    val active = MutableStateFlow(false)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var cm: ConnectivityManager? = null

    private fun clearStatusLater() {
        Hub.scope.launch {
            delay(3000)
            if (!active.value) status.value = ""
        }
    }

    fun join(ctx: Context, ssid: String, pwd: String, peerId: String, peerName: String, port: Int) {
        leave()
        Hub.touch()
        val m = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        cm = m
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pwd).build()
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
                Hub.upsertPeer(peerId, peerName, gw, port)
                Hub.scope.launch {
                    Hub.hello(peerId)
                    connected.value = peerId
                }
            }

            override fun onUnavailable() {
                active.value = false
                status.value = "连接失败或已取消"
                clearStatusLater()
            }

            override fun onLost(network: Network) {
                active.value = false
                status.value = "热点连接已断开"
                try { m.bindProcessToNetwork(null) } catch (_: Exception) {}
                clearStatusLater()
            }
        }
        callback = cb
        m.requestNetwork(req, cb, 30000)
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
