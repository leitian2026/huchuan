package com.hulian.transfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 首次使用一次性申请的权限 */
fun requiredPerms(): Array<String> {
    val l = ArrayList<String>()
    if (Build.VERSION.SDK_INT >= 33) {
        l.add(Manifest.permission.POST_NOTIFICATIONS)
        l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    // 精确/大致位置必须一起申请；部分系统创建热点时仍要求位置权限，所以所有版本都申请
    l.add(Manifest.permission.ACCESS_FINE_LOCATION)
    l.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    l.add(Manifest.permission.CAMERA)
    return l.toTypedArray()
}

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/**
 * 创建 / 连接临时热点必须的权限：Android 13+ 是"附近设备"，更低版本是"位置信息"。
 * 没有它就只能走免权限的方式（手动开系统热点 / 手动连 Wi-Fi）。
 */
fun hotspotCoreGranted(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 33) granted(ctx, Manifest.permission.NEARBY_WIFI_DEVICES)
    else granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

/** 创建热点还缺哪些权限（要一次申请的清单；为空表示都有了）。部分系统 13+ 也要求位置，所以位置也一并申请 */
fun hotspotPermsMissing(ctx: Context): List<String> {
    val l = ArrayList<String>()
    if (Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.NEARBY_WIFI_DEVICES)) {
        l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) {
        l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        l.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    return l
}

/** 给用户看的权限名：按安卓版本区分，避免让用户去找不存在的选项 */
fun hotspotPermName(): String =
    if (Build.VERSION.SDK_INT >= 33) "\u201c附近设备\u201d（个别手机还需要\u201c位置信息\u201d）" else "\u201c位置信息\u201d"

/** 处理扫到的二维码：局域网二维码直接配对；热点二维码自动连热点（没有权限就改为手动连） */
object Pairing {
    private class Qr(
        val id: String, val name: String, val port: Int, val k: String, val fp: String,
        val ssid: String?, val pwd: String?, val ip: String?, val p2p: Boolean
    )

    fun handle(ctx: Context, text: String, onChat: (String) -> Unit) {
        // 扫码前先清掉自动连接留下的群组 / 连接，等系统拆完再继续（二维码内容无效时也不影响，清理本身无害）
        if (AutoLink.releaseForPairing()) {
            Hub.scope.launch {
                delay(1500)
                withContext(Dispatchers.Main) { handleNow(ctx, text, onChat) }
            }
        } else {
            handleNow(ctx, text, onChat)
        }
    }

    private fun handleNow(ctx: Context, text: String, onChat: (String) -> Unit) {
        val j = try { JSONObject(text) } catch (e: Exception) { null }
        if (j == null || j.optString("t") != "hl") {
            Hub.toast("这不是互传的二维码")
            return
        }
        val k = j.optString("k")
        val fp = j.optString("fp")
        if (k.isEmpty() || fp.isEmpty()) {
            Hub.toast("这个二维码来自旧版本，请让对方更新互传后再扫")
            return
        }
        // 先只解析：解析失败才说"二维码内容无效"，后面连接环节的问题不再被误报成这个
        val q = try {
            val hot = j.has("ssid")
            Qr(
                j.getString("id"), j.optString("name"), j.getInt("port"), k, fp,
                if (hot) j.getString("ssid") else null,
                if (hot) j.getString("pwd") else null,
                if (hot) null else j.optString("ip").ifEmpty { null },
                j.optBoolean("p2p", false)
            )
        } catch (e: Exception) {
            null
        }
        if (q == null) {
            Hub.toast("二维码内容无效")
            return
        }
        if (q.ssid == null && q.ip == null && !q.p2p) {
            Hub.toast("二维码内容无效")
            return
        }
        if (q.ssid != null && q.pwd != null) {
            HotspotJoin.start(ctx, JoinParams(q.ssid, q.pwd, q.id, q.name, q.port, q.k, q.fp))
        } else if (q.ip != null) {
            // 本机没连上对方所在的 Wi-Fi / 热点：对方二维码里带了 p2p 标记就自动加入对方的 Wi-Fi Direct 群组再配对，不用用户手动连
            if (!HotspotJoin.onSameNetwork(ctx, q.ip)) {
                if (q.p2p) {
                    joinViaP2p(ctx, q, onChat)
                } else {
                    Hub.toastLong("你的手机没有连上对方所在的 Wi-Fi / 热点，所以配对不了。\n请先连上同一个 Wi-Fi（或对方的热点）再扫；对方也可以在“我的二维码”页点“改用热点”")
                    HotspotJoin.openWifiSettings(ctx)
                }
                return
            }
            // 对方在一个没有网络的 Wi-Fi 里（比如手动开的个人热点）而本机默认走流量时，要先绑定到那个 Wi-Fi
            HotspotJoin.bindWifiFor(ctx, q.ip)
            Hub.scope.launch {
                if (Hub.pair(q.id, q.name, q.ip, q.port, q.k, q.fp)) withContext(Dispatchers.Main) { onChat(q.id) }
            }
        } else if (q.p2p) {
            // 对方没有 Wi-Fi 也没有热点，只建了 Wi-Fi Direct 群组：直接自动加入
            joinViaP2p(ctx, q, onChat)
        }
    }

    /** 不在同一个网络：按二维码里的证书指纹算出对方固定的 Wi-Fi Direct 群组名和密码，自动加入后配对 */
    private fun joinViaP2p(ctx: Context, q: Qr, onChat: (String) -> Unit) {
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            Hub.toastLong("请打开 Wi-Fi 开关（不需要连接任何网络），然后重新扫码")
            HotspotJoin.openWifiSettings(ctx)
            return
        }
        if (!hotspotCoreGranted(app)) {
            Hub.toastLong("需要授予" + hotspotPermName() + "权限才能自动连接对方，请在系统设置里授权后重新扫码")
            return
        }
        if (Build.VERSION.SDK_INT < 33 && !LocationSwitch.isOn(app)) {
            // Android 12 及以下，Wi-Fi Direct 要求系统定位开关打开；有权限就自动开，没有就引导去开
            if (!LocationSwitch.set(app, true)) {
                Hub.toastLong("请先打开系统的“定位”开关（Android 12 及以下连接对方需要），然后重新扫码")
                LocationSwitch.openSettings(ctx)
                return
            }
            Store.ownLocation = true
        }
        val cred = LinkCred.of(q.fp)
        Hub.toast("正在自动连接对方，请稍等…")
        Hub.scope.launch {
            val done = CompletableDeferred<Boolean>()
            P2pJoin.join(app, JoinParams(cred.ssid, cred.pwd, q.id, q.name, q.port, q.k, q.fp), done, pairing = true)
            if (done.await()) {
                withContext(Dispatchers.Main) { onChat(q.id) }
            } else {
                // 配对本身失败时 Hub.pair 已经弹过提示；这里只处理“连不上对方群组”
                if (P2pJoin.lastError.isNotEmpty()) {
                    Hub.toastLong("自动连接对方失败" + P2pJoin.lastError + "。请让对方保持“我的二维码”页面不要关，并打开 Wi-Fi 开关后重新扫码")
                }
                HotspotJoin.leave()
            }
        }
    }
}
