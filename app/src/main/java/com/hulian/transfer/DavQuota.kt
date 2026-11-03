package com.hulian.transfer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.Locale

/**
 * 坚果云的额度：每月上传 / 下载流量、每 30 分钟的请求次数（免费版 600 次，付费版 1500 次）。
 *
 * 流量按“周期”算：每月几号刷新由用户在设置里填（坚果云按注册时间算周期，不是自然月），到日子自动清零，也可以手动“马上重置”。
 * 额度是整个账号共用的，两台手机都在用：本机自己数自己的用量，对方的用量由对方在每条通知里带过来（见 Relay），
 * 两边加起来才是账号的总用量。本机数不到的部分（网页端上传、别的 WebDAV 软件）可以在设置里“校准”。
 * 只统计坚果云；别的网盘没有这些限制，不统计也不暂停。
 */
object DavQuota {
    private const val WINDOW_MS = 30L * 60 * 1000
    private const val MB = 1024L * 1024

    private val lock = Any()
    private val sp by lazy { Hub.app.getSharedPreferences("hulian_quota", Context.MODE_PRIVATE) }

    private val stamps = ArrayDeque<Long>()   // 本机最近 30 分钟内发出的网盘请求的时间
    private var month = ""   // 当前流量周期的开始日期（yyyy-MM-dd）
    private var cycleDay = 1   // 这个周期是按几号刷新算出来的
    private var up = 0L
    private var down = 0L
    private var calUp = 0L
    private var calDown = 0L
    private var resetAt = 0L   // 坚果云说的下次流量刷新时间（毫秒时间戳，同步真实用量时得到；0 = 不知道）
    private var folderName = ""   // 本软件用的网盘文件夹，以及它在坚果云里占的空间（同步真实用量时得到；-1 = 不知道）
    private var folderBytes = -1L
    private val evStamps = ArrayList<Long>()   // 坚果云操作历史里该文件夹最近 30 分钟的操作时间：本机请求次数的下限，不落盘

    /** up / down 是对方报来的累计值；baseUp / baseDown 是本机“马上重置”时它已经报过的值，算用量时要减掉 */
    private class PeerUse(
        val month: String, val up: Long, val down: Long, val req: Int, val at: Long,
        val baseUp: Long = 0L, val baseDown: Long = 0L
    ) {
        val upNet: Long get() = maxOf(up - baseUp, 0L)
        val downNet: Long get() = maxOf(down - baseDown, 0L)
    }

    private val peers = HashMap<String, PeerUse>()
    private var loaded = false
    private var lastSave = 0L

    /** 界面显示用：已用 / 额度（上传、下载是账号本月的总用量，请求是最近 30 分钟的总次数） */
    class Snap(
        val upUsed: Long, val downUsed: Long, val upLimit: Long, val downLimit: Long,
        val req: Int, val reqLimit: Int, val resetAt: Long, val folderName: String, val folderBytes: Long
    )

    val snap = MutableStateFlow<Snap?>(null)

    private class Block(val text: String, val waitMs: Long, val manual: Boolean)

    private val tracking: Boolean get() = Store.davProvider == "jgy"

    // ---------- 记账（以下函数都在 lock 里调用） ----------

    private fun load() {
        if (loaded) return
        loaded = true
        month = sp.getString("m", "") ?: ""
        cycleDay = sp.getInt("rd", Store.quotaResetDay)   // 旧版本没存过：当作和现在的设置一致
        up = sp.getLong("u", 0L)
        down = sp.getLong("d", 0L)
        calUp = sp.getLong("cu", 0L)
        calDown = sp.getLong("cd", 0L)
        resetAt = sp.getLong("ra", 0L)
        folderName = sp.getString("fn", "") ?: ""
        folderBytes = sp.getLong("fb", -1L)
        (sp.getString("s", "") ?: "").split(',').forEach { s -> s.toLongOrNull()?.let { stamps.addLast(it) } }
        try {
            val a = JSONObject(sp.getString("p", "{}") ?: "{}")
            for (k in a.keys()) {
                val o = a.getJSONObject(k)
                peers[k] = PeerUse(o.getString("m"), o.getLong("u"), o.getLong("d"), o.getInt("r"), o.getLong("t"), o.optLong("bu", 0L), o.optLong("bd", 0L))
            }
        } catch (e: Exception) {
            Hub.log("DavQuota", e)
        }
    }

    /** 现在这个流量周期是哪天开始的：每月 day 号刷新（这个月没有这一天就取月底），今天还没到刷新日就是上个月的 */
    private fun cycleKey(day: Int): String {
        fun startDay(year: Int, month: Int): Int {
            val c = Calendar.getInstance()
            c.clear()
            c.set(year, month, 1)
            return minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH))
        }
        val now = Calendar.getInstance()
        var y = now.get(Calendar.YEAR)
        var m = now.get(Calendar.MONTH)
        if (now.get(Calendar.DAY_OF_MONTH) < startDay(y, m)) {
            m -= 1
            if (m < 0) {
                m = 11
                y -= 1
            }
        }
        return String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, startDay(y, m))
    }

    /** 到了刷新日：流量清零。只是改了刷新日、或者从旧版本（按自然月）升级上来：用量保留，只换周期标记 */
    private fun roll() {
        val day = Store.quotaResetDay
        val key = cycleKey(day)
        if (key == month) return
        if (cycleDay != day || (month.length == 7 && key.startsWith(month))) {
            month = key
            cycleDay = day
            persist()
            return
        }
        month = key
        up = 0L
        down = 0L
        calUp = 0L
        calDown = 0L
        resetAt = 0L
        peers.clear()
        persist()
    }

    private fun persist() {
        lastSave = System.currentTimeMillis()
        val p = JSONObject()
        for ((k, v) in peers) {
            p.put(k, JSONObject().put("m", v.month).put("u", v.up).put("d", v.down).put("r", v.req).put("t", v.at).put("bu", v.baseUp).put("bd", v.baseDown))
        }
        sp.edit().putString("m", month).putInt("rd", cycleDay).putLong("u", up).putLong("d", down).putLong("cu", calUp).putLong("cd", calDown).putLong("ra", resetAt)
            .putString("fn", folderName).putLong("fb", folderBytes)
            .putString("s", stamps.joinToString(",")).putString("p", p.toString()).apply()
    }

    private fun prune(now: Long) {
        while (stamps.isNotEmpty() && now - stamps.first() > WINDOW_MS) stamps.removeFirst()
        evStamps.removeAll { now - it > WINDOW_MS }
    }

    private fun totalUp(): Long = up + calUp + peers.values.filter { it.month == month }.sumOf { it.upNet }

    private fun totalDown(): Long = down + calDown + peers.values.filter { it.month == month }.sumOf { it.downNet }

    private fun totalReq(now: Long): Int {
        prune(now)
        val est = stamps.size + peers.values.filter { now - it.at < WINDOW_MS }.sumOf { it.req }
        return maxOf(est, evStamps.size)   // 坚果云自己记到的文件操作数是真实请求数的下限，估算比它还少就以它为准
    }

    private fun publish(now: Long) {
        snap.value = Snap(totalUp(), totalDown(), Store.quotaUpMb * MB, Store.quotaDownMb * MB, totalReq(now), Store.quotaReq, resetAt, folderName, folderBytes)
    }

    // ---------- 对外 ----------

    /** 每发一个网盘请求就记一次（WebDav.call 里） */
    fun noteRequest() {
        if (!tracking) return
        synchronized(lock) {
            load()
            roll()
            val now = System.currentTimeMillis()
            prune(now)
            stamps.addLast(now)
            if (now - lastSave > 5000) persist()
            publish(now)
        }
    }

    fun addUp(n: Long) = add(n, 0L)

    fun addDown(n: Long) = add(0L, n)

    private fun add(u: Long, d: Long) {
        if (!tracking) return
        synchronized(lock) {
            load()
            roll()
            up += u
            down += d
            persist()
            publish(System.currentTimeMillis())
        }
    }

    /** 设置改了、换了网盘、打开聊天页：重新算一遍显示 */
    fun refresh() {
        synchronized(lock) {
            if (!tracking) {
                snap.value = null
            } else {
                load()
                roll()
                publish(System.currentTimeMillis())
            }
        }
    }

    /** 本机的用量，随每条通知带给对方 */
    fun report(): JSONObject? {
        if (!tracking) return null
        return synchronized(lock) {
            load()
            roll()
            val now = System.currentTimeMillis()
            prune(now)
            JSONObject().put("m", month).put("u", up).put("d", down).put("r", stamps.size)
        }
    }

    /** 收到对方带来的用量 */
    fun peerReport(from: String, q: JSONObject) {
        if (!tracking) return
        try {
            synchronized(lock) {
                load()
                roll()
                val prev = peers[from]
                var bu = prev?.baseUp ?: 0L
                var bd = prev?.baseDown ?: 0L
                val ru = q.getLong("u")
                val rd = q.getLong("d")
                if (ru < bu) bu = 0L   // 对方自己也重置过了：它的计数从头开始
                if (rd < bd) bd = 0L
                val u = PeerUse(q.getString("m"), ru, rd, q.getInt("r"), System.currentTimeMillis(), bu, bd)
                if (peers.size < 16 || peers.containsKey(from)) peers[from] = u
                persist()
                publish(u.at)
            }
        } catch (e: Exception) {
            Hub.log("DavQuota", e)
        }
    }

    /** 用户在设置里填了坚果云 App 上看到的真实用量：把差额记下来，以后在它的基础上累加 */
    fun calibrate(upMb: Long?, downMb: Long?) {
        if (!tracking) return
        synchronized(lock) {
            load()
            roll()
            val pu = peers.values.filter { it.month == month }.sumOf { it.upNet }
            val pd = peers.values.filter { it.month == month }.sumOf { it.downNet }
            if (upMb != null) calUp = upMb * MB - up - pu
            if (downMb != null) calDown = downMb * MB - down - pd
            persist()
            publish(System.currentTimeMillis())
        }
    }

    private const val WEB = "https://www.jianguoyun.com"

    /** 带着用户的登录 Cookie 请求坚果云网页的接口，返回响应正文 */
    private fun webGet(cookie: String, path: String): String {
        val req = try {
            Request.Builder().url(WEB + path)
                .header("Cookie", cookie)
                .header("Referer", "$WEB/")
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                .get().build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Cookie 里有不能用的字符：要整串从浏览器“请求标头”里复制")
        }
        return Http.dav.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("坚果云网页返回 ${r.code}（Cookie 可能失效了）")
            r.body!!.string()
        }
    }

    /**
     * 读文件夹的操作历史（/d/ajax/getEvents，一页 100 条，marker 翻页，marker 为 1 表示最后一页），
     * 返回最近 30 分钟内的操作时间。历史里只有文件的增删改，不含下载和列目录，所以只能当请求次数的下限用
     */
    private fun fetchEvents(cookie: String, sndId: String, magic: String, now: Long): List<Long> {
        val out = ArrayList<Long>()
        var marker = 0L
        var pages = 0
        while (pages < 5) {
            val q = "/d/ajax/getEvents?sndId=" + java.net.URLEncoder.encode(sndId, "UTF-8") +
                "&sndMagic=" + java.net.URLEncoder.encode(magic, "UTF-8") + (if (marker != 0L) "&marker=$marker" else "")
            val j = JSONObject(webGet(cookie, q))
            val evs = j.optJSONArray("events") ?: break
            var inWindow = false
            for (i in 0 until evs.length()) {
                var t = evs.optJSONObject(i)?.optLong("timestamp", 0L) ?: 0L
                if (t in 1L..99_999_999_999L) t *= 1000L   // 秒 → 毫秒
                if (t >= now - WINDOW_MS && t <= now + 5 * 60_000L) {
                    out.add(t)
                    inWindow = true
                }
            }
            val next = j.optLong("marker", 1L)
            pages++
            if (next == 1L || evs.length() == 0 || (pages >= 2 && !inWindow)) break
            marker = next
        }
        return out
    }

    /**
     * 从坚果云网页接口（/d/ajax/userop/getUserInfo）读账号真实的流量用量，并据此校准：
     * 上传 / 下载额度、本周期已用、下次刷新时间（刷新日）、免费 / 付费对应的请求上限；
     * 同时读出本软件所用文件夹（hulian-relay）占了多少空间，以及它最近 30 分钟的操作历史条数。
     * 用的是用户从浏览器里复制来的登录 Cookie（加密保存在本机），只在点“同步”时请求，不轮询。
     * 返回给用户看的一句话，里面带着同步前本机的估算值，方便对照计数准不准
     */
    suspend fun syncFromWeb(): String = withContext(Dispatchers.IO) {
        val cookie = Store.davCookie.trim().removePrefix("Cookie:").removePrefix("cookie:").trim().replace(Regex("[\\r\\n]+"), "")
        if (cookie.isEmpty()) throw IOException("先粘贴坚果云网页的 Cookie")
        val text = webGet(cookie, "/d/ajax/userop/getUserInfo")
        val j = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("返回的不是账户信息：Cookie 失效了或者没复制完整，请重新复制")
        }
        if (!j.has("usedUpRate") || !j.has("freeUpRate") || !j.has("usedDownRate") || !j.has("freeDownRate")) {
            throw IOException("返回里没有流量数据：Cookie 可能失效了，或者坚果云改了接口")
        }
        val upUsed = j.getLong("usedUpRate")
        val downUsed = j.getLong("usedDownRate")
        val upLimit = upUsed + j.getLong("freeUpRate")
        val downLimit = downUsed + j.getLong("freeDownRate")
        if (upLimit <= 0L || downLimit <= 0L) throw IOException("返回的流量额度不对")
        val leftMs = j.optLong("rateResetLeftMills", -1L)
        val now = System.currentTimeMillis()
        val at = if (leftMs > 0L) now + leftMs else 0L

        // 本软件用的文件夹（WebDAV 根下的 hulian-relay）：占了多少空间，以及它的操作历史要用的编号
        val folder = Store.davDir.ifBlank { "hulian-relay" }
        var folderUsed = -1L
        var sndId = ""
        var sndMagic = ""
        val arr = j.optJSONArray("sandboxes")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("name").equals(folder, ignoreCase = true)) {
                    folderUsed = o.optLong("usedSpace", -1L)
                    sndId = o.optString("sandboxId")
                    sndMagic = o.optString("magic")
                }
            }
        }
        // 操作历史是可选的：读不到（接口变了、没找到文件夹）不影响同步
        var events: List<Long> = emptyList()
        var eventsNote = ""
        if (sndId.isNotEmpty() && sndMagic.isNotEmpty()) {
            try {
                events = fetchEvents(cookie, sndId, sndMagic, now)
            } catch (e: Exception) {
                eventsNote = "（操作历史没读到：${friendlyError(e)}）"
            }
        } else {
            eventsNote = "（没在坚果云里找到名叫 $folder 的文件夹，操作历史没读）"
        }

        Store.quotaUpMb = (upLimit / MB).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        Store.quotaDownMb = (downLimit / MB).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        Store.quotaReq = if (j.optBoolean("freeUser", true)) 600 else 1500   // 官方帮助中心：免费版 600、付费版 1500
        var day = 0
        if (at > 0L) {
            val c = Calendar.getInstance()
            c.timeInMillis = at
            day = c.get(Calendar.DAY_OF_MONTH)
            Store.quotaResetDay = day
        }
        var estUp = 0L
        var estDown = 0L
        var estReq = 0
        synchronized(lock) {
            load()
            roll()   // 刷新日改了：只换周期标记，本机的估算还在
            estUp = totalUp()
            estDown = totalDown()
            estReq = totalReq(now)
            // 对方之前报的用量已经包含在真实值里了，只算它之后新增的
            for ((k, v) in peers.toMap()) peers[k] = PeerUse(v.month, v.up, v.down, v.req, v.at, v.up, v.down)
            calUp = upUsed - up
            calDown = downUsed - down
            resetAt = at
            folderName = folder
            folderBytes = folderUsed
            evStamps.clear()
            evStamps.addAll(events)
            persist()
            publish(now)
        }
        val days = if (at > 0L) (leftMs + 86_399_999L) / 86_400_000L else -1L
        "已同步：上传已用 ${fmtSize(upUsed)} / ${fmtSize(upLimit)}，下载已用 ${fmtSize(downUsed)} / ${fmtSize(downLimit)}" +
            (if (days >= 0) "，约 $days 天后刷新（刷新日已设为 $day 号）" else "") +
            (if (folderUsed >= 0) "，$folder 文件夹占 ${fmtSize(folderUsed)}" else "") +
            "。同步前本机估算：上传 ${fmtSize(estUp)}，下载 ${fmtSize(estDown)}" +
            (if (eventsNote.isEmpty()) "。坚果云记录的最近 30 分钟里有 ${events.size} 条文件操作；本机估算最近 30 分钟请求 $estReq 次（操作历史只含增删改、不含下载和列目录，所以条数应该不大于估算）" else eventsNote)
    }

    /** 当前流量周期从哪天开始（设置页显示用） */
    fun cycleStart(): String = synchronized(lock) {
        load()
        roll()
        month
    }

    /**
     * 马上重置：本机的用量和校准清零；对方已经报过的用量也不再算（它之后新增的会照常加上）。
     * 只影响本机的显示和拦截；对方那边要它自己也点一下，或者等到下一个刷新日
     */
    fun resetNow() {
        synchronized(lock) {
            load()
            roll()
            up = 0L
            down = 0L
            calUp = 0L
            calDown = 0L
            val now = System.currentTimeMillis()
            for ((k, v) in peers.toMap()) {
                peers[k] = PeerUse(v.month, v.up, v.down, v.req, v.at, v.up, v.down)
            }
            persist()
            publish(now)
        }
    }

    // ---------- 快到上限就暂停 ----------

    /** 请求次数：用到上限的 90% 就停，等最早的那些请求滑出 30 分钟的窗口后自动继续 */
    private fun requestBlock(reqs: Int, now: Long): Block? {
        val limit = Store.quotaReq
        val soft = limit * 9 / 10
        val used = totalReq(now)
        if (used + reqs <= soft) return null
        val need = used + reqs - soft
        val wait = (if (need <= stamps.size) stamps[need - 1] + WINDOW_MS - now + 1000 else WINDOW_MS).coerceAtLeast(1000L)
        val mins = ((wait + 59_999) / 60_000).coerceAtLeast(1L)
        return Block("坚果云请求次数快到上限（$used/$limit，每 30 分钟），约 $mins 分钟后自动继续", wait, false)
    }

    /** 每月流量：剩下的不够这一段、再留 2% 余量就停；要等下个月刷新，所以不自动继续，由用户点“继续”决定 */
    private fun trafficBlock(upBytes: Long, downBytes: Long): Block? {
        if (upBytes > 0) {
            val limit = Store.quotaUpMb * MB
            val left = limit - totalUp()
            if (left - upBytes < limit / 50) {
                return Block("本月上传额度快满了（剩 ${fmtSize(left.coerceAtLeast(0L))}），已暂停；确认还有额度就点“继续”强行继续", -1L, true)
            }
        }
        if (downBytes > 0) {
            val limit = Store.quotaDownMb * MB
            val left = limit - totalDown()
            if (left - downBytes < limit / 50) {
                return Block("本月下载额度快满了（剩 ${fmtSize(left.coerceAtLeast(0L))}），已暂停；确认还有额度就点“继续”强行继续", -1L, true)
            }
        }
        return null
    }

    /**
     * 传下一段之前调用：额度够就直接返回；快满了就把这条传输自动暂停，聊天里显示原因。
     * 请求次数不够：到时间自动继续（等一个定时，不轮询）；流量不够：等用户点“继续”（点了就不再按流量拦这条传输）
     */
    suspend fun ensureRoom(ctl: TransferCtl?, upBytes: Long, downBytes: Long, reqs: Int) {
        if (ctl == null || !tracking || !Store.quotaOn) return
        while (true) {
            val b = synchronized(lock) {
                load()
                roll()
                requestBlock(reqs, System.currentTimeMillis()) ?: (if (ctl.overrideQuota) null else trafficBlock(upBytes, downBytes))
            } ?: return
            if (!ctl.autoPause(b.text, b.manual)) {
                ctl.gate()   // 已经被用户 / 另一路暂停了：等它继续（取消会在这里抛出）
                continue
            }
            if (b.waitMs > 0) {
                withTimeoutOrNull(b.waitMs) { ctl.state.first { it != TransferCtl.PAUSED } }
            } else {
                ctl.state.first { it != TransferCtl.PAUSED }
            }
            ctl.check()
            ctl.autoResume()
        }
    }

    /** 不带界面的等待：清理网盘上的残留分段时用，请求次数快满就等一等，最多等一个窗口 */
    suspend fun awaitRequests(n: Int) {
        if (!tracking || !Store.quotaOn) return
        var waited = 0L
        while (waited <= WINDOW_MS) {
            val b = synchronized(lock) {
                load()
                roll()
                requestBlock(n, System.currentTimeMillis())
            } ?: return
            delay(b.waitMs)
            waited += b.waitMs
        }
    }
}

/** 数一数从流里读走了多少字节（统计下载流量用） */
class CountingInputStream(ins: java.io.InputStream) : java.io.FilterInputStream(ins) {
    @Volatile var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }
}
