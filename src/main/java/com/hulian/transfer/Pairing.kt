package com.hulian.transfer

import android.Manifest
import android.content.Context
import android.os.Build
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 首次使用一次性申请的权限 */
fun requiredPerms(): Array<String> {
    val l = ArrayList<String>()
    if (Build.VERSION.SDK_INT >= 33) {
        l.add(Manifest.permission.POST_NOTIFICATIONS)
        l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        l.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    l.add(Manifest.permission.CAMERA)
    return l.toTypedArray()
}

/** 处理扫到的二维码：局域网二维码直接加设备并打招呼；热点二维码自动连热点 */
object Pairing {
    fun handle(ctx: Context, text: String, onChat: (String) -> Unit) {
        val j = try { JSONObject(text) } catch (e: Exception) { null }
        if (j == null || j.optString("t") != "hl") {
            Hub.toast("这不是互传的二维码")
            return
        }
        try {
            if (j.has("ssid")) {
                HotspotJoin.join(
                    ctx, j.getString("ssid"), j.getString("pwd"),
                    j.getString("id"), j.optString("name"), j.getInt("port")
                )
            } else {
                val id = j.getString("id")
                Hub.upsertPeer(id, j.optString("name"), j.getString("ip"), j.getInt("port"))
                Hub.scope.launch { Hub.hello(id) }
                onChat(id)
            }
        } catch (e: Exception) {
            Hub.toast("二维码内容无效")
        }
    }
}
