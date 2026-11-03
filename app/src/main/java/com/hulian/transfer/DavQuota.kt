package com.hulian.transfer

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
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
    class Snap(val upUsed: Long, val downUsed: Long, val upLimit: Long, val downLimit: Long, val req: Int, val reqLimit: Int)

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
        peers.clear()
        persist()
    }

    private fun persist() {
        lastSave = System.currentTimeMillis()
        val p = JSONObject()
        for ((k, v) in peers) {
            p.put(k, JSONObject().put("m", v.month).put("u", v.up).put("d", v.down).put("r", v.req).put("t", v.at).put("bu", v.baseUp).put("bd", v.baseDown))
        }
        sp.edit().putString("m", month).putInt("rd", cycleDay).putLong("u", up).putLong("d", down).putLong("cu", calUp).putLong("cd", calDown)
            .putString("s", stamps.joinToString(",")).putString("p", p.toString()).apply()
    }

    private fun prune(now: Long) {
        while (stamps.isNotEmpty() && now - stamps.first() > WINDOW_MS) stamps.removeFirst()
    }

    private fun totalUp(): Long = up + calUp + peers.values.filter { it.month == month }.sumOf { it.upNet }

    private fun totalDown(): Long = down + calDown + peers.values.filter { it.month == month }.sumOf { it.downNet }

    private fun totalReq(now: Long): Int {
        prune(now)
        return stamps.size + peers.values.filter { now - it.at < WINDOW_MS }.sumOf { it.req }
    }

    private fun publish(now: Long) {
        snap.value = Snap(totalUp(), totalDown(), Store.quotaUpMb * MB, Store.quotaDownMb * MB, totalReq(now), Store.quotaReq)
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
