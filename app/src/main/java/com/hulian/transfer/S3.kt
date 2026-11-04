package com.hulian.transfer

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cloudflare R2（S3 兼容接口）的最小客户端：只做签名和拼地址，真正发请求仍在 WebDav 里（和 WebDAV 共用同一套重试、取消、暂停逻辑）。
 *
 * 地址填 `https://<账号ID>.r2.cloudflarestorage.com/<桶名>`（路径风格），"文件夹名"当对象名前缀。
 * 签名用 SigV4，区域固定 auto、服务 s3；正文不参与签名（UNSIGNED-PAYLOAD，走 https，R2 支持），所以上传可以边读边发，不用先全部读进内存。
 */
object S3 {
    const val REGION = "auto"
    const val UNSIGNED = "UNSIGNED-PAYLOAD"

    private val hexChars = "0123456789abcdef".toCharArray()

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { sb.append(hexChars[(x.toInt() shr 4) and 15]).append(hexChars[x.toInt() and 15]) }
        return sb.toString()
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    /** RFC3986 编码：只放过 A-Z a-z 0-9 - _ . ~；encodeSlash=false 时保留 / */
    fun enc(s: String, encodeSlash: Boolean = true): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ok = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code || (c == '/'.code && !encodeSlash)
            if (ok) sb.append(c.toChar()) else sb.append('%').append(hexChars[c shr 4].uppercaseChar()).append(hexChars[c and 15].uppercaseChar())
        }
        return sb.toString()
    }

    /**
     * Secret Access Key：R2 自己生成的是 64 位十六进制，原样用；
     * 否则当作 Cloudflare API Token 的值，按官方规则取 SHA-256（此时“Access Key ID”要填 Token 的 ID）。
     */
    fun secretOf(pass: String): String {
        val p = pass.trim()
        return if (Regex("^[0-9a-fA-F]{64}$").matches(p)) p else hex(sha256(p.toByteArray(Charsets.UTF_8)))
    }

    /** 纯函数，方便对照官方示例单测：返回要加到请求上的 Authorization 头 */
    fun authorization(
        method: String, host: String, path: String, query: String, payloadHash: String,
        accessKey: String, secret: String, region: String, amzDate: String
    ): String {
        val day = amzDate.substring(0, 8)
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val canonicalQuery = if (query.isEmpty()) "" else query.split('&').sorted().joinToString("&")
        val canonical = "$method\n$path\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$day/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${hex(sha256(canonical.toByteArray(Charsets.UTF_8)))}"
        val k = hmac(hmac(hmac(hmac(("AWS4$secret").toByteArray(Charsets.UTF_8), day), region), "s3"), "aws4_request")
        val sig = hex(hmac(k, toSign))
        return "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$sig"
    }

    private fun now(): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /** 某个对象的地址：桶地址 / 前缀文件夹 / sub（每一段单独编码） */
    fun objUrl(c: DavConfig, sub: String): String {
        val base = c.url.trim().trimEnd('/')
        val parts = (c.dir.trim().trim('/') + "/" + sub.trim('/')).split('/').filter { it.isNotEmpty() }
        return base + parts.joinToString("") { "/" + enc(it) }
    }

    /** 列对象的地址：prefix 是完整前缀（以 / 结尾），token 是上一页给的续传标记 */
    fun listUrl(c: DavConfig, prefix: String, token: String?): String {
        val q = StringBuilder("delimiter=").append(enc("/")).append("&list-type=2&prefix=").append(enc(prefix))
        if (!token.isNullOrEmpty()) q.append("&continuation-token=").append(enc(token))
        return c.url.trim().trimEnd('/') + "?" + q
    }

    fun prefixOf(c: DavConfig, sub: String): String {
        val p = (c.dir.trim().trim('/') + "/" + sub.trim('/')).trim('/')
        return if (p.isEmpty()) "" else "$p/"
    }

    fun request(c: DavConfig, method: String, url: String, body: RequestBody?, tag: Any?): Request {
        UrlRules.davError(url, Store.davName)?.let { throw IOException(it) }   // 密钥只走 https
        val hu = try { url.toHttpUrl() } catch (e: IllegalArgumentException) {
            throw IOException("${Store.davName}地址格式不对：$url（应该像 ${DavProviders.get("r2").urlExample}）", e)
        }
        val host = if (hu.port == 443) hu.host else "${hu.host}:${hu.port}"
        val date = now()
        val auth = authorization(
            method, host, hu.encodedPath, hu.encodedQuery.orEmpty(), UNSIGNED,
            c.user.trim(), secretOf(c.pass), REGION, date
        )
        return Request.Builder().url(hu)
            .header("x-amz-content-sha256", UNSIGNED)
            .header("x-amz-date", date)
            .header("Authorization", auth)
            .method(method, body)
            .tag(tag)
            .build()
    }

    /** 解析 ListObjectsV2 的一页：对象列表 + 下一页标记（没有就是 null） */
    class Page(val entries: List<WebDav.Entry>, val next: String?)

    private fun unxml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun parseList(xml: String): Page {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = ArrayList<WebDav.Entry>()
        Regex("<Contents>([\\s\\S]*?)</Contents>").findAll(xml).forEach { m ->
            val b = m.groupValues[1]
            val key = Regex("<Key>([^<]*)</Key>").find(b)?.groupValues?.get(1)?.let(::unxml) ?: return@forEach
            if (key.endsWith("/")) return@forEach
            val size = Regex("<Size>(\\d+)</Size>").find(b)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val t = Regex("<LastModified>([^<]+)</LastModified>").find(b)?.groupValues?.get(1)?.let {
                try { fmt.parse(it.take(19))?.time } catch (e: Exception) { null }
            } ?: 0L
            out.add(WebDav.Entry(key.substringAfterLast('/'), size, t))
        }
        val truncated = Regex("<IsTruncated>true</IsTruncated>").containsMatchIn(xml)
        val next = if (truncated) Regex("<NextContinuationToken>([^<]*)</NextContinuationToken>").find(xml)?.groupValues?.get(1)?.let(::unxml) else null
        return Page(out, next)
    }
}
