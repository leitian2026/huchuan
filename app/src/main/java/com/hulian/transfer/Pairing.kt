package com.hulian.transfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
    /** 二维码里不变的部分：设备信息 + 一次性配对口令 + 证书指纹。局域网 / 热点 / Wi-Fi Direct 三种二维码都在它上面再加各自的字段 */
    fun qrBase(): JSONObject = JSONObject().put("t", "hl").put("id", Store.deviceId).put("name", Store.deviceName)
        .put("port", Hub.port).put("k", Hub.newPairToken()).put("fp", Identity.fp)

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
            Hub.fail("扫码失败", "这不是互传的二维码。请扫对方手机“我的二维码”页里的码", "二维码内容开头：" + text.take(40))
            return
        }
        val k = j.optString("k")
        val fp = j.optString("fp")
        if (k.isEmpty() || fp.isEmpty()) {
            Hub.fail("扫码失败", "这个二维码来自旧版本，请让对方更新互传后再扫", "缺少字段：" + (if (k.isEmpty()) "k " else "") + (if (fp.isEmpty()) "fp" else ""))
            return
        }
        // 先只解析：解析失败才说"二维码内容无效"，后面连接环节的问题不再被误报成这个
        var parseErr: Exception? = null
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
            parseErr = e
            null
        }
        if (q == null) {
            Hub.fail("扫码失败", "二维码内容无效，缺少必要信息，请让对方重新打开“我的二维码”页再扫", parseErr?.let { techDetail(it) } ?: "")
            return
        }
        if (q.ssid == null && q.ip == null && !q.p2p) {
            Hub.fail("扫码失败", "二维码里没有任何连接方式（既没有 Wi-Fi 地址，也没有热点或 Wi-Fi Direct 信息），请让对方更新互传后重新生成二维码")
            return
        }
        if (q.ssid != null && q.pwd != null) {
            // 连对方热点要先开着 Wi-Fi：关着就自动打开（借 root）；打不开也继续，由后面的流程改为手动连接
            val ssid = q.ssid
            val pwd = q.pwd
            Hub.scope.launch {
                try { AutoOpen.wifi(ctx.applicationContext) } catch (e: Exception) { Hub.log("Pairing", e) }
                withContext(Dispatchers.Main) {
                    HotspotJoin.start(ctx, JoinParams(ssid, pwd, q.id, q.name, q.port, q.k, q.fp))
                }
            }
        } else if (q.ip != null) {
            // 本机没连上对方所在的 Wi-Fi / 热点：对方二维码里带了 p2p 标记就自动加入对方的 Wi-Fi Direct 群组再配对，不用用户手动连
            if (!HotspotJoin.onSameNetwork(ctx, q.ip)) {
                if (q.p2p) {
                    joinViaP2p(ctx, q, onChat)
                } else {
                    Hub.fail(
                        "配对失败",
                        "你的手机没有连上对方所在的 Wi-Fi / 热点，所以连不到对方。请先连上同一个 Wi-Fi（或对方的热点）再扫；对方也可以在“我的二维码”页点“改用热点”，或更新互传到新版",
                        "对方地址：${q.ip}:${q.port}\n对方二维码没有带 Wi-Fi Direct 标记（对方可能是旧版，或没有授予“附近设备”权限）"
                    )
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
        if (!hotspotCoreGranted(app)) {
            Hub.fail("无法自动连接对方", "没有授予" + hotspotPermName() + "权限。请在系统设置里授权后重新扫码")
            return
        }
        Hub.scope.launch {
            try {
                // Wi-Fi 开关、定位开关关着就自动打开（借 root / adb 授权），不用用户手动去开；
                // 本 app 打开的记在 Store 里，配对没成功时马上还原，成功后等离开对话框 / 退出时还原（和自动连接一样）
                if (!AutoOpen.wifi(app)) {
                    Hub.fail(
                        "无法自动连接对方",
                        "本机的 Wi-Fi 开关是关着的，自动打开失败（需要 root 授权）。请打开 Wi-Fi 开关（不需要连接任何网络），然后重新扫码"
                    )
                    return@launch
                }
                // 定位：Android 13+ 多数系统不需要，打不开也先试；Android 12 及以下 Wi-Fi Direct 要求定位开着
                if (!AutoOpen.location(app) && Build.VERSION.SDK_INT < 33) {
                    Hub.fail(
                        "无法自动连接对方",
                        "系统的“定位”开关是关着的（Android 12 及以下连接对方需要它），自动打开失败（需要 root 授权）。请打开定位后重新扫码"
                    )
                    LocationSwitch.openSettings(ctx)
                    AutoLink.restoreSwitches()   // 这次已经打开的 Wi-Fi 还原
                    return@launch
                }
                val cred = LinkCred.of(q.fp)
                Hub.toast("正在自动连接对方，请稍等…")
                val done = CompletableDeferred<Boolean>()
                P2pJoin.join(app, JoinParams(cred.ssid, cred.pwd, q.id, q.name, q.port, q.k, q.fp), done, pairing = true)
                if (done.await()) {
                    withContext(Dispatchers.Main) { onChat(q.id) }
                } else {
                    // 配对本身失败时 Hub.pair 已经弹过窗；没弹过的（连不上对方群组等）在这里补上原因
                    if (Hub.errorDialog.value == null) {
                        val why = P2pJoin.lastError.trim('（', '）').ifEmpty { "系统没有给出原因" }
                        Hub.fail(
                            "自动连接对方失败", why,
                            "对方：${q.name}\n群组名：${cred.ssid}\n请让对方保持“我的二维码”页面不要关，并打开 Wi-Fi 开关后重新扫码"
                        )
                    }
                    HotspotJoin.leave()
                    AutoLink.restoreSwitches()   // 没配上：把本 app 自己打开的 Wi-Fi / 定位还原
                }
            } catch (e: Exception) {
                Hub.fail("自动连接对方失败", friendlyError(e), techDetail(e))
                HotspotJoin.leave()
                AutoLink.restoreSwitches()
            }
        }
    }
}
