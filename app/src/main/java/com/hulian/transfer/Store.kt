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

    var port: Int
        get() = prefs.getInt("port", 0)
        set(v) { prefs.edit().putInt("port", v).apply() }

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
                    .put("pkg", m.pkg).put("ver", m.ver).put("err", m.error)
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
                uri = o.optString("uri"), pkg = o.optString("pkg"), ver = o.optString("ver"), error = err
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun savePeers(map: Map<String, Peer>) {
        val arr = JSONArray()
        map.values.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("host", p.host)
                    .put("port", p.port).put("seen", p.lastSeen)
            )
        }
        writeAtomic(peerFile, arr.toString())
    }

    fun loadPeers(): Map<String, Peer> = try {
        val arr = JSONArray(peerFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Peer(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"), false, o.optLong("seen"))
        }.associateBy { it.id }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}
