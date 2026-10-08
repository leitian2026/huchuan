package com.hulian.transfer

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Store {
    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences

    fun init(c: Context) {
        ctx = c.applicationContext
        prefs = ctx.getSharedPreferences("hulian", Context.MODE_PRIVATE)
    }

    val deviceId: String
        get() {
            var v = prefs.getString("id", null)
            if (v == null) {
                v = UUID.randomUUID().toString().replace("-", "").take(12)
                prefs.edit().putString("id", v).apply()
            }
            return v
        }

    var deviceName: String
        get() = prefs.getString("name", null) ?: (Build.MODEL ?: "Android")
        set(v) { prefs.edit().putString("name", v).apply() }

    /** 用户选定的保存目录（SAF tree uri），为空则存到 下载/互传 */
    var saveDir: String?
        get() = prefs.getString("dir", null)
        set(v) { prefs.edit().putString("dir", v).apply() }

    // ---------- 传输方式开关：同一 Wi-Fi / 热点各自独立（远程中转见下面的 relayOn），关掉的方式不会在后台工作 ----------
    var lanOn: Boolean
        get() = prefs.getBoolean("lanOn", true)
        set(v) { prefs.edit().putBoolean("lanOn", v).apply() }
    var hotspotOn: Boolean
        get() = prefs.getBoolean("hsOn", true)
        set(v) { prefs.edit().putBoolean("hsOn", v).apply() }

    // ---------- 远程中转：Cloudflare（通知）+ 网盘 WebDAV（存放加密内容） ----------
    var relayOn: Boolean
        get() = prefs.getBoolean("rOn", false)
        set(v) { prefs.edit().putBoolean("rOn", v).apply() }
    var relayUrl: String
        get() = prefs.getString("rUrl", "") ?: ""
        set(v) { prefs.edit().putString("rUrl", v).apply() }
    var relaySecret: String
        get() = secret("rSec")
        set(v) = putSecret("rSec", v)
    /** 用哪家网盘存放中转内容：jgy=坚果云（默认，沿用旧设置的存储位置）、infini=InfiniCLOUD、custom=自定义 */
    var davProvider: String
        get() = prefs.getString("dProv", "jgy")?.takeIf { p -> DavProviders.all.any { it.id == p } } ?: "jgy"
        set(v) { prefs.edit().putString("dProv", v).apply() }
    val davName: String get() = DavProviders.get(davProvider).name

    /** 每家网盘各用一套设置：坚果云沿用原来的键名（升级不丢），其他加后缀 */
    private fun dk(base: String) = if (davProvider == "jgy") base else base + "_" + davProvider

    var davUrl: String
        get() = prefs.getString(dk("dUrl"), DavProviders.get(davProvider).defaultUrl) ?: ""
        set(v) { prefs.edit().putString(dk("dUrl"), v).apply() }
    var davUser: String
        get() = prefs.getString(dk("dUser"), "") ?: ""
        set(v) { prefs.edit().putString(dk("dUser"), v).apply() }
    var davPass: String
        get() = secret(dk("dPass"))
        set(v) = putSecret(dk("dPass"), v)
    var davDir: String
        get() = prefs.getString(dk("dDir"), "hulian-relay") ?: "hulian-relay"
        set(v) { prefs.edit().putString(dk("dDir"), v).apply() }

    /** 当前网盘的配置；没填全返回 null */
    val dav: DavConfig?
        get() {
            val u = davUrl.trim()
            val n = davUser.trim()
            val p = davPass
            return if (u.isEmpty() || n.isEmpty() || p.isEmpty()) null else DavConfig(u, n, p, davDir.ifBlank { "hulian-relay" })
        }

    /** 口令类设置用系统密钥库加密后再存 */
    private fun secret(k: String): String {
        val s = prefs.getString(k, null)
        return if (s.isNullOrEmpty()) "" else KeyVault.decrypt(s)
    }

    private fun putSecret(k: String, v: String) {
        prefs.edit().putString(k, if (v.isEmpty()) "" else KeyVault.encrypt(v)).apply()
    }

    /** 是否已经做过首次权限说明/申请 */
    var permsAsked: Boolean
        get() = prefs.getBoolean("permsAsked", false)
        set(v) { prefs.edit().putBoolean("permsAsked", v).apply() }

    var port: Int
        get() = prefs.getInt("port", 0)
        set(v) { prefs.edit().putInt("port", v).apply() }

    /** 定位开关是不是本 app 打开的（退出时只关自己开的；用户自己开的不记这里） */
    var ownLocation: Boolean
        get() = prefs.getBoolean("ownLoc", false)
        set(v) { prefs.edit().putBoolean("ownLoc", v).apply() }

    /** Wi-Fi 开关是不是本 app 打开的（退出对话框时只关自己开的；用户自己开的不记这里） */
    var ownWifi: Boolean
        get() = prefs.getBoolean("ownWifi", false)
        set(v) { prefs.edit().putBoolean("ownWifi", v).apply() }

    private val msgFile get() = File(ctx.filesDir, "msgs.json")
    private val peerFile get() = File(ctx.filesDir, "peers.json")

    @Synchronized
    fun saveMsgs(list: List<Msg>) {
        val arr = JSONArray()
        list.takeLast(3000).forEach { m ->
            arr.put(
                JSONObject().put("id", m.id).put("peer", m.peerId).put("out", m.outgoing)
                    .put("kind", m.kind.name).put("time", m.time).put("text", m.text)
                    .put("name", m.name).put("file", m.file).put("size", m.size)
                    .put("done", m.done).put("state", m.state.name).put("uri", m.uri)
                    .put("pkg", m.pkg).put("ver", m.ver).put("err", m.error).put("rd", m.read)
            )
        }
        writeAtomic(msgFile, arr.toString())
    }

    fun loadMsgs(): List<Msg> = try {
        val arr = JSONArray(msgFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            var st = MsgState.valueOf(o.optString("state", "DONE"))
            var err = o.optString("err")
            if (st == MsgState.SENDING || st == MsgState.RECEIVING) {
                st = MsgState.FAILED
                err = "已中断"
            }
            Msg(
                id = o.getString("id"), peerId = o.getString("peer"), outgoing = o.getBoolean("out"),
                kind = Kind.valueOf(o.getString("kind")), time = o.getLong("time"),
                text = o.optString("text"), name = o.optString("name"), file = o.optString("file"),
                size = o.optLong("size"), done = o.optLong("done"), state = st,
                uri = o.optString("uri"), pkg = o.optString("pkg"), ver = o.optString("ver"), error = err,
                read = o.optBoolean("rd", true)
            )
        }
    } catch (e: Exception) {
        badFile(msgFile, e, "聊天记录")
        emptyList()
    }

    @Synchronized
    fun savePeers(map: Map<String, Peer>) {
        val arr = JSONArray()
        map.values.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("host", p.host)
                    .put("port", p.port).put("seen", p.lastSeen).put("fp", p.fp)
                    .also { o -> p.iHost?.let { o.put("ih", it) } }
                    .also { o -> if (p.rk.isNotEmpty()) o.put("rk", KeyVault.encrypt(p.rk)) }
            )
        }
        writeAtomic(peerFile, arr.toString())
    }

    fun loadPeers(): Map<String, Peer> = try {
        val arr = JSONArray(peerFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Peer(
                o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"),
                false, o.optLong("seen"), o.optString("fp"),
                if (o.has("ih")) o.getBoolean("ih") else null,
                if (o.has("rk")) KeyVault.decrypt(o.getString("rk")) else ""
            )
        }.associateBy { it.id }
    } catch (e: Exception) {
        badFile(peerFile, e, "已配对设备列表")
        emptyMap()
    }

    /** 文件存在但读不出来：不能悄悄当成“空”（用户会以为数据没了）。先备份坏文件，再告诉用户原因 */
    private fun badFile(f: File, e: Exception, what: String) {
        if (!f.exists()) return   // 第一次运行，本来就没有
        val bak = File(f.parentFile, f.name + ".bad")
        val saved = try { f.copyTo(bak, overwrite = true); true } catch (_: Exception) { false }
        Hub.reportOnce(
            "load-" + f.name, what + "读取失败",
            "文件已损坏或格式不对，本次启动按“没有数据”处理" + (if (saved) "，原文件已备份为 ${bak.name}" else "") + "。如果是已配对设备列表，需要重新扫码配对",
            techDetail(e)
        )
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) throw java.io.IOException("无法替换文件 ${f.name}（重命名失败，可能存储空间不足或存储不可写）")
    }
}
