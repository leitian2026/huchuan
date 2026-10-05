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

/** 创建热点还缺哪些权限（为空表示都有了） */
fun hotspotPermsMissing(ctx: Context): List<String> {
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val l = ArrayList<String>()
    if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.NEARBY_WIFI_DEVICES)) {
        l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
        l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        l.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    return l
}

/** 处理扫到的二维码：局域网二维码直接加设备并打招呼；热点二维码自动连热点 */
object Pairing {
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
        try {
            if (j.has("ssid")) {
                HotspotJoin.join(
                    ctx, j.getString("ssid"), j.getString("pwd"),
                    j.getString("id"), j.optString("name"), j.getInt("port"), k, fp
                )
            } else {
                val id = j.getString("id")
                val name = j.optString("name")
                val ip = j.getString("ip")
                val port = j.getInt("port")
                Hub.scope.launch {
                    if (Hub.pair(id, name, ip, port, k, fp)) withContext(Dispatchers.Main) { onChat(id) }
                }
            }
        } catch (e: Exception) {
            Hub.toast("二维码内容无效")
        }
    }
}
