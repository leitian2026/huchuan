package com.hulian.transfer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch

/**
 * 已配对设备自动连接（接收方）：用 Wi-Fi Direct 的 WifiP2pManager.connect，按群组名称 + 密码直接加入对方建好的群组。
 * 不再走 WifiNetworkSpecifier，所以没有系统的“选择设备”窗口，也就没有“该应用已取消选择设备的请求”。
 * 状态仍然记在 HotspotJoin.active / linked / autoMode 上，AutoLink 的状态条和事件监听不用改。
 * 完全由系统回调驱动：连接结果、群组变化的广播；只有一个 30 秒的一次性超时（对方群组还没建好时 connect 不会有失败回调）
 */
object P2pJoin {
    private val h = Handler(Looper.getMainLooper())
    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null
    private var rx: BroadcastReceiver? = null
    private var appCtx: Context? = null
    private var timeout: Runnable? = null
    @Volatile var running = false
        private set

    /** 最近一次失败的原因，给状态条用 */
    @Volatile var lastError = ""
        private set

    private const val CONNECT_TIMEOUT_MS = 30_000L

    @SuppressLint("MissingPermission")
    fun join(ctx: Context, p: JoinParams, done: CompletableDeferred<Boolean>, pairing: Boolean = false) {
        HotspotJoin.leave()
        lastError = ""
        val app = ctx.applicationContext
        appCtx = app
        val m = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) {
            lastError = "（本机不支持 Wi-Fi Direct）"
            done.complete(false)
            return
        }
        val c = m.initialize(app, Looper.getMainLooper(), null)
        mgr = m
        ch = c
        running = true
        HotspotJoin.autoMode = true
        HotspotJoin.linked = false
        HotspotJoin.active.value = true
        Hub.touch()

        var finished = false
        var joined = false

        fun finish(ok: Boolean, err: String = "") {
            if (finished) return
            finished = true
            timeout?.let { h.removeCallbacks(it) }
            timeout = null
            if (!ok) {
                lastError = err
                done.complete(false)
            }
        }

        fun lost() {
            if (!joined) return
            joined = false
            // leave() 会把 active 置为 false，AutoLink 监听到后自动重新连接
            HotspotJoin.leave()
        }

        fun onJoined(gw: String) {
            joined = true
            finished = true
            timeout?.let { h.removeCallbacks(it) }
            timeout = null
            HotspotJoin.linked = true
            Hub.touch()
            Hub.toast("已连接到对方热点")
            Hub.scope.launch {
                if (pairing) done.complete(Hub.pair(p.peerId, p.peerName, gw, p.port, p.token, p.hostFp))   // 扫码配对：连上群组后用一次性口令配对
                else done.complete(HotspotJoin.reconnect(p, gw))
            }
        }

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, i: Intent?) {
                val mm = mgr ?: return
                val cc = ch ?: return
                if (i != null && i.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                    if (i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        if (joined) lost() else finish(false, "（Wi-Fi Direct 被关闭了）")
                    }
                    return
                }
                try {
                    mm.requestConnectionInfo(cc) { info ->
                        if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                            if (!joined && !finished) {
                                val gw = info.groupOwnerAddress.hostAddress ?: ""
                                try {
                                    mm.requestGroupInfo(cc) { g ->
                                        // 必须是对方那个固定名称的群组，不是别的 Wi-Fi Direct 连接
                                        if (g != null && g.networkName == p.ssid && !joined && !finished && gw.isNotEmpty()) onJoined(gw)
                                    }
                                } catch (_: SecurityException) {
                                    finish(false, "（缺少权限）")
                                }
                            }
                        } else if (joined) {
                            lost()
                        }
                    }
                } catch (_: SecurityException) {
                    if (!joined) finish(false, "（缺少权限）")
                }
            }
        }
        rx = r
        try {
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) { Hub.log("Wifi", e) }

        val cfg = try {
            WifiP2pConfig.Builder().setNetworkName(p.ssid).setPassphrase(p.pwd).enablePersistentMode(false).build()
        } catch (e: Exception) {
            finish(false, "（群组参数无效）")
            return
        }
        val t = Runnable { finish(false, "（30 秒内没有加入对方的群组：对方可能已离开二维码页面、没有建成群组，或两台手机离得太远）") }
        timeout = t
        h.postDelayed(t, CONNECT_TIMEOUT_MS)
        try {
            m.connect(c, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}   // 只表示请求已发出，真正连上看广播

                override fun onFailure(reason: Int) {
                    finish(
                        false, when (reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> "（本机不支持 Wi-Fi Direct）"
                            WifiP2pManager.BUSY -> "（Wi-Fi Direct 正忙：可能正在连别的设备，或系统还在拆掉上一个连接，请等几秒再试）"
                            WifiP2pManager.ERROR -> "（Wi-Fi Direct 内部错误，代码 0：请关闭再打开 Wi-Fi 后重试）"
                            WifiP2pManager.NO_SERVICE_REQUESTS -> "（Wi-Fi Direct 没有可用的服务，代码 3）"
                            else -> "（系统返回错误 $reason）"
                        }
                    )
                }
            })
        } catch (e: SecurityException) {
            finish(false, "（缺少权限）")
        } catch (e: Exception) {
            finish(false, "（${e.message ?: "未知错误"}）")
        }
    }

    /** 断开：取消还没完成的连接、退出已加入的群组、释放通道。只处理本对象自己发起的 */
    @SuppressLint("MissingPermission")
    fun leave() {
        timeout?.let { h.removeCallbacks(it) }
        timeout = null
        rx?.let { try { appCtx?.unregisterReceiver(it) } catch (e: Exception) { Hub.log("Wifi", e) } }
        rx = null
        if (!running) return
        val m = mgr
        val c = ch
        mgr = null
        ch = null
        running = false
        if (m != null && c != null) {
            try { m.cancelConnect(c, null) } catch (e: Exception) { Hub.log("Wifi", e) }
            release(m, c, 3)
        }
    }

    /** 退出群组；失败了确认还在群组里就重试（有的系统第一次会返回“正忙”），完成后再释放通道 */
    @SuppressLint("MissingPermission")
    private fun release(m: WifiP2pManager, c: WifiP2pManager.Channel, left: Int) {
        fun closeLater() { h.postDelayed({ try { c.close() } catch (e: Exception) { Hub.log("Wifi", e) } }, 1500) }
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { closeLater() }

                override fun onFailure(reason: Int) {
                    try {
                        m.requestGroupInfo(c) { g ->
                            if (g != null && left > 0) h.postDelayed({ release(m, c, left - 1) }, 1000) else closeLater()
                        }
                    } catch (_: Exception) { closeLater() }
                }
            })
        } catch (_: Exception) { closeLater() }
    }
}
