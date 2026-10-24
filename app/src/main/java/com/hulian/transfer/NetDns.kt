package com.hulian.transfer

import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 公网请求（Cloudflare WebSocket、网盘 WebDAV）的域名解析：
 *  1. Host 映射（设置里填：域名 → IP；有开关，默认开）。命中就直接用，不发任何 DNS 查询；一个域名可以填多个 IP，连不上会依次换下一个
 *  2. 系统 DNS：没有映射的域名用它（有开关，默认开；关了就解析失败）
 *
 * 局域网直连用的是 IP，不经过这里。
 */
object NetDns : Dns {
    class Result(val addrs: List<InetAddress>, val source: String)
    class Parsed(val map: Map<String, List<InetAddress>>, val error: String?)

    @Volatile private var hostsCache: Pair<String, Parsed>? = null

    private val v4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

    /** 只认 IP 字面量（IPv4 / IPv6），绝不会触发 DNS 查询 */
    private fun literal(s: String): InetAddress? {
        val t = s.trim().removePrefix("[").removeSuffix("]")
        val ok = v4.matches(t) ||
            (t.contains(':') && t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' })
        if (!ok) return null
        return try { InetAddress.getByName(t) } catch (e: Exception) { null }
    }

    /**
     * 解析 Host 映射文本。每行：域名 IP [IP…]；分隔符可以是空格、逗号或 =，# 后面是注释。
     * 也认 Clash 的写法 “域名: IP”。出错的行会被跳过，error 是第一处错误（给界面提示用）
     */
    fun parseHosts(text: String): Parsed {
        hostsCache?.let { if (it.first == text) return it.second }
        val map = LinkedHashMap<String, MutableList<InetAddress>>()
        var err: String? = null
        text.lines().forEachIndexed { i, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(Regex("[\\s,=]+")).filter { it.isNotEmpty() }
            val host = parts[0].trimEnd(':').lowercase()
            if (host.isEmpty() || host.contains('/') || host.contains("://")) {
                if (err == null) err = "Host 映射第 ${i + 1} 行：域名不对"
                return@forEachIndexed
            }
            // 一行只有 IP / 写成了“IP 域名”（hosts 文件的顺序）：给明确提示，别笼统说格式不对
            if (literal(parts[0].trimEnd(':')) != null) {
                if (err == null) err = if (parts.size == 1) "Host 映射第 ${i + 1} 行：这一行只有 IP，前面要写域名（格式：域名 IP）"
                else "Host 映射第 ${i + 1} 行：开头应该是域名，不是 IP（顺序是：域名 IP，和 hosts 文件相反）"
                return@forEachIndexed
            }
            val ips = parts.drop(1).map { literal(it) }
            if (ips.isEmpty()) {
                if (err == null) err = "Host 映射第 ${i + 1} 行：$host 后面没有 IP"
                return@forEachIndexed
            }
            if (ips.any { it == null }) {
                if (err == null) err = "Host 映射第 ${i + 1} 行：IP 格式不对（只能填 IP 地址，不能填域名）"
                return@forEachIndexed
            }
            map.getOrPut(host) { ArrayList() }.addAll(ips.filterNotNull())
        }
        val p = Parsed(map, err)
        hostsCache = text to p
        return p
    }

    /** 当前生效的 Host 映射：开关关着就是空 */
    private fun activeHosts(): Map<String, List<InetAddress>> =
        if (Store.hostsOn) parseHosts(Store.hostsText).map else emptyMap()

    /** 设置页保存后调用：清缓存，并断开旧连接，下次连接按新设置解析 */
    fun reset() {
        hostsCache = null
        try { Http.client.connectionPool.evictAll() } catch (e: Exception) { Hub.log("NetDns", e) }
    }

    override fun lookup(hostname: String): List<InetAddress> = resolve(hostname).addrs

    /** 设置页“测试解析”用：返回结果和来源，失败抛 UnknownHostException */
    fun explain(hostname: String): String {
        val r = resolve(hostname)
        return hostname + " → " + r.addrs.joinToString("、") { it.hostAddress ?: "?" } + "（来源：" + r.source + "）"
    }

    fun resolve(hostname: String): Result {
        val host = hostname.lowercase()

        // 1. Host 映射（开关关着就整个跳过）
        activeHosts()[host]?.let { if (it.isNotEmpty()) return Result(it, "Host 映射") }

        // 2. 系统 DNS（开关关着就不查，避免被劫持的答案混进来）
        if (!Store.sysDnsOn) throw UnknownHostException("$hostname 没有 Host 映射，且已关闭“没映射的域名用系统 DNS”")
        return Result(ipv4First(Dns.SYSTEM.lookup(hostname)), "系统 DNS")
    }

    /** IPv4 排前面：有些网络 IPv6 不通，先试 IPv6 会白等一轮超时 */
    private fun ipv4First(l: List<InetAddress>): List<InetAddress> = l.sortedBy { it is Inet6Address }
}

/**
 * 公网连接失败时的中文提示：DNS 被改 / 握手被掐，和“设备配对身份”无关。
 * 不是这类错误返回 null，调用方继续用原来的提示。
 */
fun netHint(t: Throwable): String? {
    var c: Throwable? = t
    var n = 0
    var dns: String? = null
    var cert = false
    var ssl = false
    while (c != null && n < 6) {
        val m = c.message.orEmpty()
        if (c is UnknownHostException && dns == null) dns = m
        if (c is javax.net.ssl.SSLPeerUnverifiedException || c is java.security.cert.CertificateException ||
            m.contains("CERTIFICATE_VERIFY_FAILED") || m.contains("Trust anchor")
        ) cert = true
        else if (c is javax.net.ssl.SSLException) ssl = true
        c = c.cause
        n++
    }
    val fix = "请在设置里填写 Host 映射，或换一个域名 / 换网络 / 开代理"
    if (dns != null) {
        val own = dns.any { it in '\u4e00'..'\u9fff' }
        return if (own) "$dns。请检查设置里的 Host 映射" else "域名解析失败，可能没有网络或 DNS 被干扰。$fix"
    }
    if (cert) return "证书校验失败：连到的不是你的 Cloudflare 服务器，域名很可能被 DNS 劫持到了别处。$fix"
    if (ssl) return "TLS 握手被中途掐断：域名可能被 DNS 劫持，或被网络屏蔽。这和设备配对无关，不用删除设备。$fix"
    return null
}
