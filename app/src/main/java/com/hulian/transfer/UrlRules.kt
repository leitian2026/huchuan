package com.hulian.transfer

/**
 * 地址规则：中转口令、网盘账号密码都放在请求里，只允许走 https / wss，不允许明文 http / ws。
 * 返回 null 表示没问题，否则是给用户看的错误文案。
 * （Android 默认也不让 app 发明文流量，这里是在代码里明确拒绝，不依赖系统兜底。）
 */
object UrlRules {
    /** Cloudflare 地址：可以不写协议（默认 https），写了就只能是 https:// 或 wss:// */
    fun relayError(raw: String): String? {
        val u = raw.trim().lowercase()
        if (u.isEmpty()) return null
        if (u.startsWith("http://") || u.startsWith("ws://"))
            return "Cloudflare 地址不能用 http:// 或 ws://（口令会明文发出去），请改成 https://"
        if (u.contains("://") && !u.startsWith("https://") && !u.startsWith("wss://"))
            return "Cloudflare 地址格式不对，应该像 https://xxx.workers.dev"
        return null
    }

    /** 网盘地址：必须以 https:// 开头 */
    fun davError(raw: String, name: String = "网盘"): String? {
        val u = raw.trim().lowercase()
        if (u.isEmpty()) return null
        if (u.startsWith("http://")) return "${name}地址不能用 http://（账号密码会明文发出去），请改成 https://"
        if (!u.startsWith("https://")) return "${name}地址要以 https:// 开头"
        return null
    }
}
