package com.hulian.transfer

import org.json.JSONObject

/**
 * 新手机和旧手机扫码配对时，把旧手机的“远程中转 + 域名解析”设置发给新手机，省得重新填一遍。
 *
 * 安全约束：
 *  - 发送方每次都要用户在弹窗里点“发送”，默认不发（配对的可能是朋友的手机，发出去对方就能用你的网盘和口令）
 *  - 只走已配对的 TLS 直连通道，不经过 Cloudflare 或网盘
 *  - 接收方只在“本机还没有远程中转设置”并且“刚配对完 10 分钟内”才接受，已有设置绝不覆盖
 */
object RelayCfg {
    const val WINDOW_MS = 10 * 60_000L

    fun hasConfig(): Boolean = Store.relayUrl.isNotBlank() && Store.relaySecret.isNotBlank()

    fun export(): JSONObject = JSONObject()
        .put("u", Store.relayUrl).put("s", Store.relaySecret)
        .put("dv", Store.davProvider).put("du", Store.davUrl).put("dn", Store.davUser)
        .put("dp", Store.davPass).put("dd", Store.davDir)
        .put("hs", Store.hostsText).put("ho", Store.hostsOn)
        .put("dh", Store.dohUrl).put("do", Store.dohOn)
        .put("cs", Store.cfSysDns).put("ds", Store.davSysDns)

    /** 本机没有设置时才应用。成功返回 null；不接受就返回原因（会回给对方） */
    fun applyIfEmpty(j: JSONObject): String? {
        if (hasConfig()) return "对方已经有远程中转设置，没有改动"
        val u = j.optString("u").trim()
        val s = j.optString("s").trim()
        if (u.isEmpty() || s.isEmpty()) return "收到的设置不完整"
        UrlRules.relayError(u)?.let { return "收到的设置有问题：$it" }
        val prov = j.optString("dv", "jgy").takeIf { p -> DavProviders.all.any { it.id == p } } ?: "jgy"
        val du = j.optString("du").trim()
        if (du.isNotEmpty()) UrlRules.davError(du, DavProviders.get(prov).name)?.let { return "收到的设置有问题：$it" }

        Store.davProvider = prov
        if (du.isNotEmpty()) {
            Store.davUrl = du
            Store.davUser = j.optString("dn").trim()
            Store.davPass = j.optString("dp").trim()
            Store.davDir = j.optString("dd").trim().trim('/').ifEmpty { "hulian-relay" }
        }
        // 域名解析：格式不对的整项跳过，不影响其他设置
        val hs = j.optString("hs").trim()
        if (NetDns.parseHosts(hs).error == null) {
            Store.hostsText = hs
            if (j.has("ho")) Store.hostsOn = j.optBoolean("ho", true)
        }
        val dh = j.optString("dh").trim()
        if (NetDns.dohError(dh) == null) {
            Store.dohUrl = dh
            Store.dohOn = j.optBoolean("do", false) && dh.isNotEmpty()
        }
        // 两路各自的“使用系统 DNS”：旧版本发来的没有这两项，保持本机默认（开）
        if (j.has("cs")) Store.cfSysDns = j.optBoolean("cs", false)
        if (j.has("ds")) Store.davSysDns = j.optBoolean("ds", false)
        Store.relayUrl = u
        Store.relaySecret = s
        Store.relayOn = true
        NetDns.reset()
        WebDav.forgetDirs()
        return null
    }
}
