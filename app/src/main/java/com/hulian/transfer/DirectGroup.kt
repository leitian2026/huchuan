package com.hulian.transfer

import android.content.Context
import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** 由“主机”的证书指纹算出的固定 Wi-Fi 名称和密码：主机自己算、所有已配对的设备也能算出同一个，不用扫码，一个主机可以同时被多台已配对设备连 */
object LinkCred {
    class Cred(val ssid: String, val pwd: String)

    fun of(hostFp: String): Cred {
        val h = java.security.MessageDigest.getInstance("SHA-256")
            .digest("hulian-link-v2|${hostFp.lowercase()}".toByteArray(Charsets.UTF_8))
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

    /** 创建还没出结果时就被要求关闭：创建一成功立刻拆掉，不然群组会一直留着没人管 */
    @Volatile private var stopWhenReady = false

    private val h = Handler(Looper.getMainLooper())

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "本机不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "Wi-Fi Direct 正忙（可能本机正连着 Wi-Fi 且芯片不能同时用，或有别的 Wi-Fi Direct 连接）"
        WifiP2pManager.ERROR -> "Wi-Fi Direct 内部错误（代码 0）：请关闭再打开 Wi-Fi 后重试"
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
        stopWhenReady = false
        // 系统偶尔一直不回调：15 秒还没出结果就当失败，别让界面一直卡在“正在创建”
        Handler(Looper.getMainLooper()).postDelayed({
            if (starting) {
                starting = false
                onFail("创建热点超时")
            }
        }, 15_000)
        try {
            m.requestGroupInfo(c) { g ->
                if (g != null && g.networkName == cred.ssid) {
                    // 已经是我们的群组（上次没来得及关）：直接沿用；但如果等结果期间已经被要求关闭，就直接拆掉
                    starting = false
                    if (stopWhenReady) {
                        stopWhenReady = false
                        removeWithRetry(m, c, 3)
                    } else {
                        up.value = true
                    }
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
                    starting = false
                    if (stopWhenReady) {
                        stopWhenReady = false
                        removeWithRetry(m, c, 3)
                        return
                    }
                    up.value = true
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
        } catch (e: Exception) { Hub.log("AutoLink", e) }
    }

    /**
     * 关掉本 app 建的群组。以前只在“记录里群组在运行”时才关：记录和系统不一致（创建中被关、系统回调晚到、检查时误判没了）
     * 就会漏关，群组一直留着、对方一直连着。现在只要有通道就发关闭请求，失败了确认群组还在就重试
     */
    @SuppressLint("MissingPermission")
    fun stop() {
        val m = mgr
        val c = ch
        stopWhenReady = starting
        up.value = false
        starting = false
        if (m != null && c != null) removeWithRetry(m, c, 3)
    }

    @SuppressLint("MissingPermission")
    private fun removeWithRetry(m: WifiP2pManager, c: WifiP2pManager.Channel, left: Int) {
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}

                override fun onFailure(reason: Int) {
                    // 没有群组时也会返回失败，所以先确认群组真的还在，再重试
                    if (left <= 0) return
                    try {
                        m.requestGroupInfo(c) { g ->
                            if (g != null && g.networkName.startsWith("DIRECT-hl-")) {
                                h.postDelayed({ if (!up.value && !starting) removeWithRetry(m, c, left - 1) }, 1000)
                            }
                        }
                    } catch (e: Exception) { Hub.log("AutoLink", e) }
                }
            })
        } catch (e: Exception) { Hub.log("AutoLink", e) }
    }

    /**
     * 本次启动后还没建过群组时调用一次：上次 app 被系统杀掉 / 崩溃，没来得及关的群组会一直留着，
     * 对方还连在上面，这里把它拆掉。只拆名称是本 app 固定前缀的，别的软件 / 用户自己建的不碰
     */
    @SuppressLint("MissingPermission")
    fun cleanupStale(ctx: Context) {
        if (mgr != null || up.value || starting) return
        val app = ctx.applicationContext
        if (!hotspotCoreGranted(app)) return
        val m = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return
        try {
            val c = m.initialize(app, Looper.getMainLooper(), null)
            m.requestGroupInfo(c) { g ->
                if (g != null && g.isGroupOwner && g.networkName.startsWith("DIRECT-hl-") && mgr == null && !up.value && !starting) {
                    removeWithRetry(m, c, 3)
                }
            }
        } catch (e: Exception) { Hub.log("AutoLink", e) }
    }
}
