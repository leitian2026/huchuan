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
import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
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

/** 由两台手机的证书指纹算出的固定 Wi-Fi 名称和密码：两边算出来一样，不用扫码 */
object LinkCred {
    class Cred(val ssid: String, val pwd: String)

    fun of(peerFp: String): Cred {
        val a = Identity.fp
        val b = peerFp
        val lo = if (a < b) a else b
        val hi = if (a < b) b else a
        val h = java.security.MessageDigest.getInstance("SHA-256").digest("hulian-link-v1|$lo|$hi".toByteArray(Charsets.UTF_8))
        fun hex(from: Int, to: Int) = (from until to).joinToString("") { "%02x".format(h[it]) }
        // Wi-Fi Direct 群组名称必须以 DIRECT-xx 开头；密码 32 位十六进制
        return Cred("DIRECT-hl-" + hex(0, 3), hex(3, 19))
    }
}

/** 本机当“主机”时建的 Wi-Fi Direct 群组（固定名称和密码）。只关本 app 建的 */
object DirectGroup {
    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null

    @Volatile var owned = false
        private set
    @Volatile var starting = false
        private set

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "本机不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "Wi-Fi Direct 正忙（可能本机正连着 Wi-Fi 且芯片不能同时用，或有别的 Wi-Fi Direct 连接）"
        else -> "创建热点失败（代码 $r）"
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, cred: LinkCred.Cred, onFail: (String) -> Unit) {
        val app = ctx.applicationContext
        val m = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) { onFail("本机不支持 Wi-Fi Direct"); return }
        val c = m.initialize(app, Looper.getMainLooper(), null)
        mgr = m
        ch = c
        starting = true
        try {
            m.requestGroupInfo(c) { g ->
                if (g != null && g.networkName == cred.ssid) {
                    // 已经是我们的群组（上次没来得及关）：直接沿用
                    owned = true
                    starting = false
                } else if (g != null) {
                    // 别的软件 / 用户自己建的 Wi-Fi Direct：不动它
                    starting = false
                    onFail("本机已有别的 Wi-Fi Direct 连接，没有改动它")
                } else {
                    create(m, c, cred, onFail)
                }
            }
        } catch (e: SecurityException) {
            starting = false
            onFail("缺少权限：请授予" + hotspotPermName())
        }
    }

    @SuppressLint("MissingPermission")
    private fun create(m: WifiP2pManager, c: WifiP2pManager.Channel, cred: LinkCred.Cred, onFail: (String) -> Unit) {
        try {
            val cfg = WifiP2pConfig.Builder().setNetworkName(cred.ssid).setPassphrase(cred.pwd)
                .enablePersistentMode(false).build()
            m.createGroup(c, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    owned = true
                    starting = false
                }

                override fun onFailure(reason: Int) {
                    starting = false
                    onFail(reasonText(reason))
                }
            })
        } catch (e: Exception) {
            starting = false
            onFail(if (e is SecurityException) "缺少权限：请授予" + hotspotPermName() else "创建热点失败：" + (e.message ?: ""))
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val m = mgr
        val c = ch
        if (owned && m != null && c != null) {
            try { m.removeGroup(c, null) } catch (_: Exception) {}
        }
        owned = false
        starting = false
    }
}

/**
 * 打开 app 自动连、退出 app 自动断，不分谁扫谁：
 * - 对已配对的设备（取最近用的一台），用两边证书算出同一个固定的 Wi-Fi 名称和密码
 * - 设备 ID 较小的一台当主机（建 Wi-Fi Direct 群组），另一台自动连过去；找不到对方就提示
 * - 退出 app 只关本 app 自己建的群组、自己改开的定位；手动开的 / 别的软件开的一律不碰
 */
object AutoLink {
    /** 自动连接已经启动（此时空闲不自动断热点） */
    @Volatile var running = false
        private set

    /** 需要用户去系统里手动打开定位（本 app 没有权限自己开） */
    val needLocation = MutableStateFlow(false)

    private var clientJob: Job? = null

    private fun linkPeer(): Peer? {
        val paired = Hub.peers.value.values.filter { it.paired }
        if (paired.isEmpty()) return null
        return paired.firstOrNull { it.id == Store.currentPeer } ?: paired.maxByOrNull { it.lastSeen }
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (DirectGroup.owned || DirectGroup.starting || HotspotJoin.active.value || clientJob?.isActive == true) {
            running = true
            return
        }
        val peer = linkPeer() ?: return
        if (!hotspotCoreGranted(app)) return
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            Hub.toast("请先打开 Wi-Fi 开关（不需要连接任何网络）")
            return
        }
        val cred = LinkCred.of(peer.fp)
        if (Store.deviceId < peer.id) startHost(app, cred) else startClient(app, peer, cred)
    }

    private fun markRunning(app: Context) {
        if (running) return
        running = true
        try { app.startService(Intent(app, ExitWatcher::class.java)) } catch (_: Exception) {}
    }

    private fun startHost(app: Context, cred: LinkCred.Cred) {
        if (!LocationSwitch.isOn(app)) {
            Store.ownLocation = false
            if (LocationSwitch.set(app, true)) {
                Store.ownLocation = true
            } else if (Build.VERSION.SDK_INT < 33) {
                needLocation.value = true
                return
            }
            // Android 13+：有的系统已不要求定位，先直接试，失败了再提示（见 hostFailed）
        }
        markRunning(app)
        DirectGroup.start(app, cred) { msg ->
            Hub.toast(msg)
            hostFailed(app)
        }
    }

    private fun startClient(app: Context, peer: Peer, cred: LinkCred.Cred) {
        markRunning(app)
        val p = JoinParams(cred.ssid, cred.pwd, peer.id, peer.name, peer.port, "", peer.fp)
        clientJob = Hub.scope.launch {
            for (i in 0 until 4) {
                val done = CompletableDeferred<Boolean>()
                HotspotJoin.join(app, p, auto = true, done = done)
                if (done.await()) return@launch
                HotspotJoin.leave()
                delay(8000)
            }
            Hub.toast("没找到对方：请确认对方也打开了互传，并开着 Wi-Fi 开关")
        }
    }

    /** 创建失败时调用：如果是因为定位没开，提示用户去开 */
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
        clientJob?.cancel()
        clientJob = null
        HotspotJoin.leave()
        DirectGroup.stop()
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
