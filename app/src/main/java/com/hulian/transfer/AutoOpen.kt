package com.hulian.transfer

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 需要时自动打开 Wi-Fi / 定位开关（借 root，定位还可以用 adb 授予的 WRITE_SECURE_SETTINGS）。
 * 本 app 打开的会记到 Store.ownWifi / Store.ownLocation，离开对话框 / 退出 / 扫码配对失败时只还原这些
 * （见 AutoLink.restoreSwitches）；本来就开着的、用户自己开的不动。
 *
 * 扫码配对用这里的挂起函数：打开后会等到系统状态真的变化再返回。
 * 对话框里的自动连接（AutoLink）由事件驱动，Wi-Fi 仍然在它自己的流程里打开，定位用这里的 location()
 */
object AutoOpen {
    private suspend fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        var t = 0L
        while (t < ms) {
            if (cond()) return true
            delay(200)
            t += 200
        }
        return cond()
    }

    /** 确保 Wi-Fi 开着（不需要连接任何网络）。返回 true 表示现在是开着的；false 表示没打开（没有 root / 拒绝授权） */
    suspend fun wifi(ctx: Context): Boolean {
        if (WifiSwitch.isOn(ctx)) return true
        // 下命令和记录“本 app 打开”要一气呵成：界面中途离开导致协程取消时，也不能出现“开了却没记录、永远不还原”
        val opened = withContext(NonCancellable) {
            val ok = WifiSwitch.set(true)
            if (ok) Store.ownWifi = true   // 本来是关的、这次由本 app 打开：之后要还原
            ok
        }
        if (!opened) return false
        val ok = waitUntil(8000) { WifiSwitch.isOn(ctx) }
        if (ok) delay(800)     // 刚打开时 Wi-Fi Direct 还没就绪，立刻用会返回“正忙”
        return ok
    }

    /** 确保系统定位开着。返回 true 表示现在是开着的；false 表示没打开（没有 root / 没授权 / 拒绝授权） */
    suspend fun location(ctx: Context): Boolean {
        if (LocationSwitch.isOn(ctx)) return true
        Store.ownLocation = false   // 现在是关的：以前留下的“本 app 打开”记录已经过期
        return withContext(NonCancellable) {
            val ok = LocationSwitch.setAny(ctx, true)
            if (ok) Store.ownLocation = true    // 这次由本 app 打开：之后要还原
            ok
        }
    }
}
