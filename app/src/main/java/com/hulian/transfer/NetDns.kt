package com.hulian.transfer

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 公网请求的域名解析，防 DNS 劫持。Cloudflare（WebSocket）和网盘（WebDAV）各用一套，互不影响：
 *  1. Host 映射（设置里填：域名 → IP；有总开关，默认开；两路共用同一份）。命中就直接用，不发任何 DNS 查询；一个域名可以填多个 IP，连不上会依次换下一个
 *  2. 自建 DoH（只有网盘用；设置里的开关，默认关；可填多个，按顺序试）。只对没有 Host 映射的域名生效。加密 DNS，运营商看不到也改不了
 *  3. 系统 DNS：由各自的“使用系统 DNS”开关决定（Cloudflare、网盘各一个，默认开）。开着：前面都没解析出来就用系统 DNS；
 *     关着：绝不碰系统 DNS，前面都没解析出来就是连不上
 *
 * 局域网直连用的是 IP，不经过这里。
 * 注意：这只解决 DNS 被改；TLS 握手里的域名（SNI）仍是明文。
 */
object NetDns {
    /** 哪一路在解析：Cloudflare 还是网盘 */
    enum class Chan { CF, DAV }

    private class ChanDns(val chan: Chan) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = NetDns.resolve(hostname, chan).addrs
    }

    val cf: Dns = ChanDns(Chan.CF)
    val dav: Dns = ChanDns(Chan.DAV)

    class Result(val addrs: List<InetAddress>, val source: String)
    class Parsed(val map: Map<String, List<InetAddress>>, val error: String?)

    private const val TTL_MS = 5 * 60_000L

    private class Cached(val at: Long, val r: Result)

    private val cache = ConcurrentHashMap<String, Cached>()
    @Volatile private var hostsCache: Pair<String, Parsed>? = null
    @Volatile private var dohCache: Pair<String, List<DnsOverHttps>>? = null

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

    /** DoH 地址的格式错误（每行都要是 https:// 地址）；没问题返回 null。设置页保存前检查 */
    fun dohError(text: String): String? {
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, line ->
            val u = line.toHttpUrlOrNull()
            if (u == null || u.scheme != "https") return "DoH 第 ${i + 1} 行不对：要以 https:// 开头，像 https://域名/路径"
        }
        return null
    }

    /**
     * 提醒（不阻止保存）：DoH 服务器自己的域名如果没有 Host 映射，解析它就得先用系统 DNS，
     * 而被劫持的正是系统 DNS，DoH 一开始就连不上。DoH 地址里直接写 IP 的不需要映射。没问题返回 null
     */
    fun dohWarning(dohText: String, hostsText: String): String? {
        val map = parseHosts(hostsText).map
        for (line in dohText.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val u = line.toHttpUrlOrNull() ?: continue
            val h = u.host.lowercase()
            if (literal(h) != null) continue
            if (map[h].isNullOrEmpty()) return "DoH 服务器 $h 没有 Host 映射，解析它要先走系统 DNS，可能被劫持（网盘关了“使用系统 DNS”时，这条 DoH 会直接不可用）。请在 Host 映射里加一行：$h IP"
        }
        return null
    }

    /** 当前生效的 Host 映射：开关关着就是空 */
    private fun activeHosts(): Map<String, List<InetAddress>> =
        if (Store.hostsOn) parseHosts(Store.hostsText).map else emptyMap()

    /** 设置页保存后调用：清缓存，并断开两路的旧连接，下次连接按新设置解析 */
    fun reset() {
        cache.clear()
        dohCache = null
        hostsCache = null
        try { Http.client.connectionPool.evictAll() } catch (e: Exception) { Hub.log("NetDns", e) }
        try { Http.dav.connectionPool.evictAll() } catch (e: Exception) { Hub.log("NetDns", e) }
    }

    /** 设置页“测试解析”用：返回结果和来源，失败抛 UnknownHostException */
    fun explain(hostname: String, chan: Chan): String {
        val r = resolve(hostname, chan)
        return hostname + " → " + r.addrs.joinToString("、") { it.hostAddress ?: "?" } + "（来源：" + r.source + "）"
    }

    fun resolve(hostname: String, chan: Chan): Result {
        val host = hostname.lowercase()
        val sysOk = if (chan == Chan.CF) Store.cfSysDns else Store.davSysDns

        // 1. Host 映射（开关关着就整个跳过）
        activeHosts()[host]?.let { if (it.isNotEmpty()) return Result(it, "Host 映射") }

        // 2. 自建 DoH 只给网盘用。局域网名字（nas.local、没有点的主机名等）公网 DoH 查不到，不走 DoH
        var last: Exception? = null
        var dohTried = false
        val dohText = Store.dohUrl.trim()
        if (chan == Chan.DAV && Store.dohOn && dohText.isNotEmpty() && !isLocalName(host)) {
            cache[host]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.r }
            for (d in dohList(dohText, sysOk)) {
                dohTried = true
                try {
                    val a = ipv4First(d.lookup(hostname))
                    if (a.isNotEmpty()) {
                        val r = Result(a, "自建 DoH")
                        cache[host] = Cached(System.currentTimeMillis(), r)
                        return r
                    }
                } catch (e: Exception) {
                    last = e
                }
            }
        }

        // 3. 系统 DNS：开关开着才用；关着就是连不上
        if (sysOk) return Result(ipv4First(Dns.SYSTEM.lookup(hostname)), if (dohTried) "系统 DNS（DoH 没解析出来，已回退）" else "系统 DNS")
        val why = last?.let { it.message ?: it.javaClass.simpleName }
        val msg = if (dohTried) "自建 DoH 解析 $hostname 失败：${why ?: "没有返回地址"}（已关闭“使用系统 DNS”，不回退）"
        else "$hostname 没有 Host 映射" + (if (chan == Chan.DAV) "、没有可用的自建 DoH" else "") + "，且已关闭“使用系统 DNS”，没法解析"
        throw UnknownHostException(msg).also { if (last != null) it.initCause(last) }
    }

    private fun isLocalName(h: String): Boolean =
        !h.contains('.') || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") ||
            h.endsWith(".internal") || h.endsWith(".localdomain")

    /** IPv4 排前面：有些网络 IPv6 不通，先试 IPv6 会白等一轮超时 */
    private fun ipv4First(l: List<InetAddress>): List<InetAddress> = l.sortedBy { it is Inet6Address }

    /** 每行一个 DoH 地址。DoH 服务器自己的域名也先查 Host 映射（相当于 bootstrap），避免“解析 DoH 要先解析 DoH”的死循环 */
    private fun dohList(text: String, sysOk: Boolean): List<DnsOverHttps> {
        val key = text + "\n" + (if (Store.hostsOn) Store.hostsText else "") + "\n" + sysOk
        dohCache?.let { if (it.first == key) return it.second }
        val hosts = activeHosts()
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val url = line.toHttpUrlOrNull() ?: return@mapNotNull null
            if (url.scheme != "https") return@mapNotNull null
            val b = DnsOverHttps.Builder().client(client).url(url).includeIPv6(true).resolvePrivateAddresses(false)
            val boot = hosts[url.host.lowercase()]
            // 关了“使用系统 DNS”时，DoH 服务器自己的域名没有映射就没法引导（引导会用系统 DNS），这一条跳过；直接写 IP 的不受影响
            if (boot.isNullOrEmpty() && !sysOk && literal(url.host) == null) return@mapNotNull null
            if (!boot.isNullOrEmpty()) b.bootstrapDnsHosts(boot)
            b.build()
        }
        dohCache = key to list
        return list
    }
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
        return if (own) "$dns。请检查设置里的 Host 映射和“使用系统 DNS”开关" else "域名解析失败，可能没有网络或 DNS 被干扰。$fix"
    }
    if (cert) return "证书校验失败：连到的不是你的 Cloudflare 服务器，域名很可能被 DNS 劫持到了别处。$fix"
    if (ssl) return "TLS 握手被中途掐断：域名可能被 DNS 劫持，或被网络屏蔽。这和设备配对无关，不用删除设备。$fix"
    return null
}
