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
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

fun JoinParams.toJson(): String = JSONObject().put("ssid", ssid).put("pwd", pwd).put("id", peerId)
    .put("name", peerName).put("port", port).put("fp", hostFp).toString()

fun joinParamsFromJson(s: String): JoinParams? = try {
    val j = JSONObject(s)
    JoinParams(j.getString("ssid"), j.getString("pwd"), j.getString("id"), j.optString("name"), j.getInt("port"), "", j.optString("fp"))
} catch (_: Exception) {
    null
}

/**
 * 打开 app 自动连、退出 app 自动断：
 * - 上次是“开热点的一方”(host)：打开 app 自动开热点（必要时先开定位）
 * - 上次是“扫码连热点的一方”(guest)：打开 app 自动连上次的热点，不用再扫码
 * - 退出 app 时只关“本 app 自己开的”东西：本 app 创建的热点、本 app 改开的定位。
 *   别的软件 / 用户手动开的热点和定位，一律不碰
 */
object AutoLink {
    /** 自动连接已经启动（此时空闲不自动断热点） */
    @Volatile var running = false
        private set

    /** 需要用户去系统里手动打开定位（本 app 没有权限自己开） */
    val needLocation = MutableStateFlow(false)

    private var inited = false

    fun init() {
        if (inited) return
        inited = true
        // 有人扫了本机的热点二维码并配对成功：以后本机固定当“开热点的一方”
        Hub.scope.launch {
            Hub.hellos.collect {
                if (HotspotHost.isUp() && Store.linkRole != "host") Store.linkRole = "host"
            }
        }
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        when (Store.linkRole) {
            "host" -> startHost(app)
            "guest" -> startGuest(app)
            else -> return
        }
    }

    private fun markRunning(app: Context) {
        if (running) return
        running = true
        try { app.startService(Intent(app, ExitWatcher::class.java)) } catch (_: Exception) {}
    }

    private fun startHost(app: Context) {
        if (HotspotHost.isUp() || HotspotHost.starting) { markRunning(app); return }
        // 系统里已经有热点开着（手动开的，或别的软件开的）：不动它，也不抢
        if (Net.apActive()) return
        if (!hotspotCoreGranted(app)) return
        // Store.ownLocation 为真但定位其实已被关掉：说明上次没来得及记录，清掉
        if (!LocationSwitch.isOn(app)) {
            Store.ownLocation = false
            if (LocationSwitch.set(app, true)) {
                Store.ownLocation = true
            } else if (Build.VERSION.SDK_INT < 33) {
                needLocation.value = true
                return
            }
            // Android 13+：有的系统创建本地热点已不要求定位，先直接试，失败了再提示（见 hostFailed）
        }
        markRunning(app)
        HotspotHost.start(app)
    }

    private fun startGuest(app: Context) {
        if (HotspotJoin.active.value) { markRunning(app); return }
        val p = Store.lastJoin?.let { joinParamsFromJson(it) } ?: return
        if (!hotspotCoreGranted(app)) return
        // 已经连着某个 Wi-Fi：不打扰，走原来的局域网发现
        if (Net.wifiIp() != null) return
        markRunning(app)
        HotspotJoin.join(app, p, auto = true)
    }

    /** 热点创建失败时由 HotspotHost 调用：如果是因为定位没开，提示用户去开 */
    fun hostFailed(ctx: Context) {
        if (running && !LocationSwitch.isOn(ctx)) needLocation.value = true
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
            release()
        }
    }

    private fun release() {
        HotspotJoin.leave()
        HotspotHost.stop()
        if (Store.ownLocation) {
            LocationSwitch.set(Hub.app, false)
            Store.ownLocation = false
        }
        needLocation.value = false
        running = false
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
