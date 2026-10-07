package com.hulian.transfer

import android.content.Context
import android.net.wifi.WifiManager

/**
 * 系统 Wi-Fi 开关。Android 10 起普通 app 不能自己开关 Wi-Fi，这里借 root（su -c svc wifi）。
 * 没有 root / 用户拒绝授权时返回 false，调用方退回“请手动打开 Wi-Fi”的提示。
 * 只负责下命令；开关真正变化由系统广播通知（AutoLink 已经在监听），这里不轮询。
 */
object WifiSwitch {
    fun isOn(ctx: Context): Boolean = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (e: Exception) {
        Hub.log("WifiSwitch", e)
        false
    }

    /** 命令执行成功（su 退出码 0）返回 true。统一走 Root，授权窗口不会重复弹出 */
    suspend fun set(on: Boolean): Boolean = Root.run("svc wifi " + if (on) "enable" else "disable")
}
