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
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
        val a = Identity.fp.lowercase()
        val b = peerFp.lowercase()
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

    /** 本 app 建的群组是否在运行 */
    val up = MutableStateFlow(false)

    @Volatile var starting = false
        private set

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "本机不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "Wi-Fi Direct 正忙（可能本机正连着 Wi-Fi 且芯片不能同时用，或有别的 Wi-Fi Direct 连接），稍后自动重试"
        else -> "创建热点失败（代码 $r），稍后自动重试"
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
                    up.value = true
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
                    up.value = true
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

    /** 群组可能被系统收掉（关了 Wi-Fi、被别的功能抢占等）：发现没了就标记，让自动检查重新建 */
    @SuppressLint("MissingPermission")
    fun verify() {
        val m = mgr
        val c = ch
        if (m == null || c == null) { up.value = false; return }
        try {
            m.requestGroupInfo(c) { g -> if (g == null) up.value = false }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val m = mgr
        val c = ch
        if (up.value && m != null && c != null) {
            try { m.removeGroup(c, null) } catch (_: Exception) {}
        }
        up.value = false
        starting = false
    }
}

/**
 * 打开 app 自动连、退出 app 自动断，不分谁扫谁：
 * - 对已配对的设备（取最近用的一台），用两边证书算出同一个固定的 Wi-Fi 名称和密码
 * - 设备 ID 较小的一台当主机（建 Wi-Fi Direct 群组），另一台自动连过去
 * - 完全由事件触发，没有定时轮询（省电）：打开 app、Wi-Fi 开关变化、定位开关变化、Wi-Fi Direct 状态变化、
 *   授权完成、已配对设备上线/下线、连接断开……才检查一次。只有“没找到对方 / 创建失败”时才按 10 秒起逐步拉长的间隔重试，最多 8 次
 * - 退出 app 只关本 app 自己建的群组、自己改开的定位；手动开的 / 别的软件开的一律不碰
 */
object AutoLink {
    /** 需要用户去系统里手动打开定位（本 app 没有权限自己开） */
    val needLocation = MutableStateFlow(false)

    private var keeper: Job? = null
    private var retryJob: Job? = null
    private var watchers: List<Job> = emptyList()
    private var appCtx: Context? = null
    private var clientJob: Job? = null
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var paused = false
    @Volatile private var failures = 0
    private var locAsked = false
    private val shown = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { kick() }
    }

    /** 同一类提示只弹一次，条件恢复正常后才会再弹 */
    private fun note(key: String, msg: String) { if (shown.add(key)) Hub.toast(msg) }
    private fun clear(key: String) { shown.remove(key) }

    private fun linkPeer(): Peer? {
        val paired = Hub.peers.value.values.filter { it.paired }
        if (paired.isEmpty()) return null
        return paired.firstOrNull { it.id == Store.currentPeer } ?: paired.maxByOrNull { it.lastSeen }
    }

    /**
     * 外部事件触发一次检查。reset=true（默认）表示条件有了新变化，清掉重试计数和等待中的重试；
     * 连接自身状态变化（reset=false）不清，避免失败后立刻死循环重试
     */
    fun kick(reset: Boolean = true) {
        if (reset) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
        }
        kicks.trySend(Unit)
    }

    /** 失败后重试：10 秒、20 秒、40 秒……最长 5 分钟，最多 8 次，之后等下一次事件 */
    private fun fail() {
        failures++
        if (failures > 8) return
        val ms = minOf(10_000L shl (failures - 1), 300_000L)
        retryJob?.cancel()
        retryJob = Hub.scope.launch {
            delay(ms)
            kicks.trySend(Unit)
        }
    }

    /** 新开界面时恢复自动连接（用户手动“断开”之后，到下次打开 app 前不再自动连） */
    fun resume() { paused = false }

    /** 用户在聊天菜单里手动断开：本次不再自动重连 */
    fun pause() {
        paused = true
        retryJob?.cancel()
        retryJob = null
        clientJob?.cancel()
        clientJob = null
        HotspotJoin.leave()
        DirectGroup.stop()
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (keeper?.isActive == true) {
            kick()   // 回到前台：检查一次
            return
        }
        appCtx = app
        try { app.startService(Intent(app, ExitWatcher::class.java)) } catch (_: Exception) {}
        try {
            val f = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}
        keeper = Hub.scope.launch {
            for (k in kicks) {
                if (Hub.appVisible && !paused) {
                    try { step(app) } catch (_: Exception) {}
                }
            }
        }
        watchers = listOf(
            // 已配对设备增删 / 上线下线
            Hub.scope.launch {
                Hub.peers.map { m -> m.values.filter { it.paired }.map { it.id to it.online }.sortedBy { it.first } }
                    .distinctUntilChanged().collect { kick() }
            },
            // 连接自身断开 / 建立
            Hub.scope.launch { HotspotJoin.active.collect { kick(reset = false) } },
            Hub.scope.launch { DirectGroup.up.collect { kick(reset = false) } }
        )
        kick()
    }

    private fun step(app: Context) {
        val peer = linkPeer()
        if (peer == null) {
            if (DirectGroup.up.value) DirectGroup.stop()
            return
        }
        // 用二维码手动连接 / 手动开的热点正在使用：不打扰
        if (HotspotHost.isUp() || HotspotHost.starting || (HotspotJoin.active.value && !HotspotJoin.autoMode)) return
        if (!hotspotCoreGranted(app)) {
            note("perm", "自动连接需要授予" + hotspotPermName() + "权限，授权后会自动继续")
            return
        }
        clear("perm")
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            note("wifi", "请打开 Wi-Fi 开关（不需要连接任何网络），打开后会自动连接")
            return
        }
        clear("wifi")
        val linkUp = DirectGroup.up.value || HotspotJoin.active.value
        // 对方已经通过同一个 Wi-Fi 在线（原来的局域网方式能用）：不用再建热点
        if (peer.online && !linkUp) return
        if (retryJob?.isActive == true) return   // 正在等下一次重试
        val cred = LinkCred.of(peer.fp)
        if (Store.deviceId < peer.id) hostStep(app, cred) else clientStep(app, peer, cred)
    }

    private fun hostStep(app: Context, cred: LinkCred.Cred) {
        if (DirectGroup.starting) return
        if (DirectGroup.up.value) {
            clear("fail")
            failures = 0
            DirectGroup.verify()
            return
        }
        if (!LocationSwitch.isOn(app)) {
            Store.ownLocation = false
            if (LocationSwitch.set(app, true)) {
                Store.ownLocation = true
            } else if (Build.VERSION.SDK_INT < 33) {
                askLocation()
                return
            }
            // Android 13+：有的系统已不要求定位，先直接试，失败了再提示
        }
        DirectGroup.start(app, cred) { msg ->
            note("fail", msg)
            if (!LocationSwitch.isOn(app)) askLocation()
            fail()
        }
    }

    private fun clientStep(app: Context, peer: Peer, cred: LinkCred.Cred) {
        if (HotspotJoin.active.value || clientJob?.isActive == true) return
        val p = JoinParams(cred.ssid, cred.pwd, peer.id, peer.name, peer.port, "", peer.fp)
        clientJob = Hub.scope.launch {
            val done = CompletableDeferred<Boolean>()
            HotspotJoin.join(app, p, auto = true, done = done)
            if (done.await()) {
                failures = 0
                clear("notfound")
            } else {
                HotspotJoin.leave()
                note("notfound", "没找到对方：请确认对方已打开互传并开着 Wi-Fi 开关，会自动重试")
                fail()
            }
        }
    }

    /** 只弹一次：用户取消后不再反复打扰；之后在系统里把定位打开了，会收到定位开关变化的事件，自动继续 */
    private fun askLocation() {
        if (!locAsked) {
            locAsked = true
            needLocation.value = true
        }
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
        watchers.forEach { it.cancel() }
        watchers = emptyList()
        keeper?.cancel()
        keeper = null
        retryJob?.cancel()
        retryJob = null
        clientJob?.cancel()
        clientJob = null
        try { appCtx?.unregisterReceiver(receiver) } catch (_: Exception) {}
        HotspotJoin.leave()
        DirectGroup.stop()
        HotspotHost.stop()
        if (Store.ownLocation) {
            LocationSwitch.set(Hub.app, false)
            Store.ownLocation = false
        }
        needLocation.value = false
        locAsked = false
        failures = 0
        shown.clear()
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
