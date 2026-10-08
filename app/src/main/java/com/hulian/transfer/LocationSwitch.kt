package com.hulian.transfer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.delay

/**
 * 系统“定位”开关。自己开关有两种办法，都没有就只能引导用户去设置里开：
 * 1. 授予过 WRITE_SECURE_SETTINGS（电脑上 adb 授权一次）：直接写系统设置
 * 2. 有 root：借 su 执行命令（和 Wi-Fi 开关一样）
 */
object LocationSwitch {
    fun isOn(ctx: Context): Boolean = try {
        LocationManagerCompat.isLocationEnabled(ctx.getSystemService(LocationManager::class.java))
    } catch (e: Exception) {
        Hub.log("AutoLink", e)
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
        } catch (e: Exception) {
            Hub.log("AutoLink", e)
            false
        }
    }

    /** 开 / 关定位：优先用已授予的 WRITE_SECURE_SETTINGS，没有（或没生效）再借 root。返回 true 表示现在已经是想要的状态 */
    suspend fun setAny(ctx: Context, on: Boolean): Boolean {
        if (canWrite(ctx) && set(ctx, on)) return true
        return setByRoot(ctx, on)
    }

    private suspend fun setByRoot(ctx: Context, on: Boolean): Boolean {
        // 先用老办法 location_mode（多数系统有效）；没生效再用 Android 11+ 的 cmd location
        val cmds = listOf(
            "settings put secure location_mode " + if (on) "3" else "0",
            "cmd location set-location-enabled $on"
        )
        for (c in cmds) {
            val ran = Root.run(c)
            // su 本身不可用（没有 root / 拒绝授权）：不必再试下一条，也避免再弹一次授权
            if (!ran && Root.available.value != true) break
            if (ran && waitState(ctx, on)) return true
        }
        return isOn(ctx) == on
    }

    /** 命令返回后系统状态可能还没更新，最多等 1.5 秒 */
    private suspend fun waitState(ctx: Context, on: Boolean): Boolean {
        repeat(10) {
            if (isOn(ctx) == on) return true
            delay(150)
        }
        return isOn(ctx) == on
    }

    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Hub.log("AutoLink", e) }
    }
}
