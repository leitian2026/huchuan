package com.hulian.transfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
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
        val ssid: String?, val pwd: String?, val ip: String?
    )

    fun handle(ctx: Context, text: String, onChat: (String) -> Unit) {
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
                if (hot) null else j.getString("ip")
            )
        } catch (e: Exception) {
            null
        }
        if (q == null) {
            Hub.toast("二维码内容无效")
            return
        }
        if (q.ssid != null && q.pwd != null) {
            HotspotJoin.start(ctx, JoinParams(q.ssid, q.pwd, q.id, q.name, q.port, q.k, q.fp))
        } else if (q.ip != null) {
            // 对方在一个没有网络的 Wi-Fi 里（比如手动开的个人热点）而本机默认走流量时，要先绑定到那个 Wi-Fi
            HotspotJoin.bindWifiFor(ctx, q.ip)
            Hub.scope.launch {
                if (Hub.pair(q.id, q.name, q.ip, q.port, q.k, q.fp)) withContext(Dispatchers.Main) { onChat(q.id) }
            }
        }
    }
}
