package com.hulian.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

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

    private fun build(): String = Pairing.qrBase().put("ssid", curSsid).put("pwd", curPwd).toString()

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
