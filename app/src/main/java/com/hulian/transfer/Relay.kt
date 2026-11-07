package com.hulian.transfer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.spec.SecretKeySpec

/**
 * 远程中转：两台手机不在同一个网络、也连不上热点时用。
 *
 *  - Cloudflare Worker：只负责“谁在线”和“有新消息”的小通知（WebSocket 长连接，对方上线 / 来消息都是推送，不用轮询）
 *  - 网盘 WebDAV（坚果云 / InfiniCLOUD 等）：存放加密后的内容。发送方上传到“对方的文件夹”，对方收到通知后下载、解密、删除
 *
 * 内容在手机上用配对时交换的密钥加密（见 RelayCrypto），网盘和 Cloudflare 都看不到。
 *
 * 大文件（超过一个分段）会切成若干分段，几段同时上传（突破单条连接的速度上限），
 * 每传完一段就通知对方，对方一边收一边写，不用等整个文件传完。文字和小文件仍是一个文件，和旧版本完全兼容。
 */
object Relay {
    /** 给设置页显示的连接状态（出错时写明原因） */
    val state = MutableStateFlow("未启用")
    val connected = MutableStateFlow(false)

    /** 这次连接上是否已经收到过 Cloudflare 发来的“谁在线”名单（没收到之前，“对方在线 / 不在线”还不能下结论） */
    val presence = MutableStateFlow(false)

    /** 通过 Cloudflare 看到“在线”的设备（不含本机） */
    val online = MutableStateFlow<Set<String>>(emptySet())

    @Volatile
    private var sock: WebSocket? = null
    private var job: Job? = null
    private var backoff = 2000L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** 最近一次从 Cloudflare 收到任何数据的时间（心跳判断连接是否还活着） */
    @Volatile private var lastRx = 0L
    private val acks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val processing = ConcurrentHashMap.newKeySet<String>()
    /** 等本机点“接收”的远程文件：消息编号 → 信息。点接收前不下载内容，只读了第一块（消息头）来显示文件名和大小 */
    private class Offer(val from: String?, val peerId: String, val ext: String, val parts: Int)
    private val offers = ConcurrentHashMap<String, Offer>()
    /** 用户点了“接收”的消息编号：receive 再跑到消息头时放行 */
    private val accepted = ConcurrentHashMap.newKeySet<String>()
    /** 点了“接收”的消息是从哪台设备来的（接收没能开始时，用它在对应聊天里写失败原因） */
    private val acceptPeer = ConcurrentHashMap<String, String>()
    /** 接收失败、但网盘上的文件还留着、可以点“重试”的消息编号（本次运行内；重启后会当成新到的文件重新出现待接收卡片） */
    private val keepMids = ConcurrentHashMap.newKeySet<String>()
    /** 远程发出的文件：消息编号 → 聊天里那条消息的 id（对方回“收完了 / 拒绝了 / 过期了”时用，重启后还在，最多记 300 条） */
    private val sentMap: MutableMap<String, String> by lazy { java.util.Collections.synchronizedMap(LinkedHashMap(Store.relaySent)) }
    private fun rememberSent(mid: String, localId: String) {
        synchronized(sentMap) {
            sentMap[mid] = localId
            while (sentMap.size > 300) sentMap.remove(sentMap.keys.first())
            Store.relaySent = LinkedHashMap(sentMap)
        }
    }
    /** 一个文件传完上传、通知也发出去了：聊天里显示“等待对方接收”（对方收完会回通知改成“已发送”） */
    /** 某一段太慢、换连接重试时，在传输卡片上写一句提示；这一段传完就清掉。只在有变化时才更新界面 */
    private fun slowHint(msgId: String?, text: String) {
        if (msgId == null) return
        val cur = Hub.msgs.value.firstOrNull { it.id == msgId } ?: return
        if (cur.error == text || !(cur.state == MsgState.SENDING || cur.state == MsgState.RECEIVING)) return
        if (text.isEmpty() && !cur.error.startsWith("第")) return   // 不是这个提示（别的错误信息）不清
        Hub.patch(msgId) { it.copy(error = text) }
    }

    private fun markWaiting(localId: String?, header: JSONObject) {
        if (localId == null) return
        Hub.patch(localId) {
            if (it.state == MsgState.SENDING || it.state == MsgState.PAUSED) it.copy(state = MsgState.WAITING, done = it.size, error = "") else it
        }
    }
    /** 告诉发送方：这个文件本机已经收完了 */
    private fun sendGot(peerId: String, mid: String) {
        Hub.scope.launch {
            try {
                sendNotify(peerId, JSONObject().put("mid", mid).put("got", true))
            } catch (x: Exception) {
                Hub.log("Relay", x)
            }
        }
    }

    fun canRetryReceive(id: String) = keepMids.contains(id)

    /** 发控制消息（已读、撤回…）：连着 Cloudflare 就发，对方不在线 Cloudflare 会暂存；发出去了返回 true */
    suspend fun sendCtl(to: String, ctl: JSONObject): Boolean {
        if (!connected.value) return false
        return try {
            sendNotify(to, JSONObject().put("ctl", ctl))
            true
        } catch (e: Exception) {
            Hub.log("Relay", e)
            false
        }
    }

    /** 对方撤回了还没接收的文件：撤掉待接收卡片、清掉网盘上的文件（不用通知对方，是它自己撤的） */
    fun dropOffer(mid: String) {
        val o = offers.remove(mid) ?: return
        markDone(mid)
        Hub.scope.launch(Dispatchers.IO) {
            Store.dav?.let { d ->
                if (o.parts > 1) deleteParts(d, mid, o.parts)
                quietDelete(d, "${Store.deviceId}/$mid${if (o.ext.startsWith(".")) o.ext else "." + o.ext}")
            }
        }
    }

    /** 接收失败的文件点“重试”：网盘上的文件还在，直接重新收（不用对方重发，也不用再点“接收”） */
    fun retryReceive(mid: String) {
        val m = Hub.msgs.value.firstOrNull { it.id == mid && !it.outgoing && it.state == MsgState.FAILED } ?: return
        keepMids.remove(mid)
        synchronized(doneMids) {
            doneMids.remove(mid)
            Store.relayDoneMids = doneMids.toList()
        }
        accepted.add(mid)
        acceptPeer[mid] = m.peerId
        Hub.patch(mid) { it.copy(state = MsgState.RECEIVING, done = 0L, error = "") }
        Hub.scope.launch(Dispatchers.IO) {
            // 后缀可能不是默认的：先看一眼网盘里这条消息实际叫什么
            val ext = try {
                Store.dav?.let { WebDav.listEntries(it, Store.deviceId) }
                    ?.firstNotNullOfOrNull { e -> headOf(e.name)?.takeIf { it.first == mid }?.second }
            } catch (e: Exception) {
                null
            }
            receive(null, mid, ext?.let { ".$it" })
        }
    }

    /**
     * 用户点了“接收”之后，接收流程没开始就退出（网盘上找不到文件、已处理过等）：把聊天里那条消息原位改成失败并写明原因，
     * 同时记入错误记录，不能让卡片无声无息地消失。没点过“接收”的（自动扫描）不提示
     */
    private fun failVisible(mid: String, reason: String) {
        if (!accepted.remove(mid)) return
        val pid = acceptPeer.remove(mid)
        Hub.report("远程接收失败", IOException(reason), "消息：$mid")
        if (Hub.msgs.value.any { it.id == mid }) {
            Hub.patch(mid) { if (it.state == MsgState.FAILED) it else it.copy(state = MsgState.FAILED, error = reason) }
        } else if (pid != null) {
            Hub.addMsg(Msg(mid, pid, false, Kind.FILE, now(), name = "远程文件", state = MsgState.FAILED, error = reason))
        }
    }
    /** 消息头读到文件但还没被接受：中断这次下载，改成放一张待接收的卡片 */
    private class OfferException(val peer: Peer, val meta: JSONObject) : IOException("等待用户接收")
    /** 已处理过的消息 ID：存在本机，重启后还在（最多记 1500 条，再老的靠消息里的时间戳拦） */
    private val doneMids: MutableSet<String> by lazy { java.util.Collections.synchronizedSet(LinkedHashSet(Store.relayDoneMids)) }
    private const val DONE_MAX = 1500
    /** 对方发来的文件超过这么久一直没接收：自动清掉 */
    private const val UNACCEPTED_MAX_AGE_MS = 7L * 24 * 3600_000

    /** 700 KB 以内的文字和文件直接放在 Cloudflare 的通知里发，不走网盘（Cloudflare 的 WebSocket 单条消息上限是 1 MB，加密后再转文本会大三分之一） */
    private const val INLINE_MAX = 700_000L
    /** 消息里（已加密校验）的发送时间比这还早，就当作被人重放的旧文件 */
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    private val midPattern = Regex("[A-Za-z0-9-]{1,64}")

    private const val MB = 1024L * 1024
    /** 超过这个大小的文件才分段；每段的大小（文件很大时每段大一点，减少对网盘的请求次数） */
    private fun partSize(size: Long): Long = if (size <= 256 * MB) 4 * MB else 8 * MB
    /** 接收时接受的每段大小上限（防止对方在消息头里声明一个离谱的值） */
    private const val MAX_PART = 64L * 1024 * 1024
    /** 等一段上传出来的最长时间（期间没有任何新的分段通知） */
    private const val PART_WAIT_MS = 120_000L
    /** 小文件（不分段）同时最多传几个：一次发好几张图片时，同时发起的上传太多，坚果云偶尔会回 503 */
    private val singleGate = Semaphore(2)

    /**
     * 消息头文件（第 0 段）在网盘上叫“消息编号.后缀”，后缀由发送方在设置里选（默认 dat）；分段文件固定是“消息编号-序号.prt”。
     * 收件时不看本机的设置，认任何合法后缀。消息编号必须是 UUID，免得把文件夹里别的文件当成消息
     */
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val headExtPattern = Regex("[a-z0-9]{1,8}")
    /** 后缀合法：1～8 位小写字母或数字，且不能是分段文件用的 prt */
    fun headExtOk(x: String) = headExtPattern.matches(x) && x != "prt"
    /** 文件名是消息头文件就返回（消息编号, 后缀），否则 null */
    private fun headOf(name: String): Pair<String, String>? {
        val i = name.indexOf('.')
        if (i <= 0 || name.indexOf('.', i + 1) >= 0) return null
        val mid = name.substring(0, i)
        val ext = name.substring(i + 1)
        return if (uuidPattern.matches(mid) && headExtOk(ext)) mid to ext else null
    }
    /** 没有后缀信息时（旧版本的发件人、没带后缀的通知）依次试这两个：新版默认的 dat，旧版本用的 bin */
    private val defaultHeadExts = listOf(".dat", ".bin")

    /** 等一段上传出来的过程中，隔多久去网盘看一眼（通知可能丢了；不是放弃的时间） */
    private const val PART_POLL_MS = 30_000L
    private val sweeping = AtomicBoolean(false)

    /** 孤儿分段（对应的消息头文件不在了）至少放了这么久才删，免得误删正在上传的 */
    private const val ORPHAN_MIN_AGE_MS = 30L * 60 * 1000
    /** 对方暂停发送时，最多等它多久（防止对方的手机没了，这边一直等） */
    private const val SENDER_PAUSE_MAX_MS = 30L * 60 * 1000

    /** 正在接收的消息：对方每传完一段会来一条通知，这里记下来并唤醒等待的下载（事件驱动，不轮询） */
    private class Live(val from: String?) {
        val tick = MutableStateFlow(0)
        val announced = ConcurrentHashMap.newKeySet<Int>()
        @Volatile var aborted = false
        /** 消息头已经读完整、解开了（之后就有副作用：聊天里加了消息 / 开始写文件，下载断了不能简单地整个重来） */
        @Volatile var gotHeader = false
        /** 对方的通知可能丢了（本机刚上线 / 重连过）：不等通知，直接去网盘看有没有 */
        @Volatile var optimistic = false
        /** 发送方点了暂停：它没有新分段是正常的，不算超时 */
        @Volatile var senderPaused = false
        @Volatile var pausedSince = 0L
        /** 这条消息是谁发来的（收完后把本机的用量告诉它） */
        @Volatile var peerId: String? = null
        /** 这条消息分几段（收到消息头后才知道；取消时要按它清理网盘上的分段） */
        @Volatile var parts = 1
        fun poke() = tick.update { it + 1 }
    }

    /** 本机正在发送的分段文件：消息编号 → 发给谁 + 控制柄（对方点了取消时用） */
    private class Outgoing(val peerId: String, val ctl: TransferCtl)

    private val sending = ConcurrentHashMap<String, Outgoing>()

    private val live = ConcurrentHashMap<String, Live>()

    /** 分段文件接收到一半的状态：第 0 段已经写进文件，剩下的几段还要继续收 */
    private class Multi(
        val peer: Peer, val key: SecretKeySpec, val mid: String,
        val job: Inbox.FileJob, val n: Int, val psz: Long, val ctl: TransferCtl
    ) {
        @Volatile var got = 0L
        var last = 0L
        /** 已经下载进内存、还没写盘的字节数（边下边显示进度用；写盘时按原始大小扣掉） */
        val inflight = java.util.concurrent.atomic.AtomicLong(0L)
        /** 后面各段的下载任务（下标 i-1 对应第 i 段）。提前到第 0 段还在收的时候就开始，所以放在能比单次调用活得更久的作用域里 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs = java.util.concurrent.CopyOnWriteArrayList<Deferred<okio.Buffer>>()
        var launched = 1
    }

    /** 显示进度：已写盘的 + 正在下载的 */
    private fun showProgress(m: Multi) {
        val t = now()
        if (t - m.last > 120) {
            m.last = t
            Inbox.progress(m.job, minOf(m.got + m.inflight.get(), m.job.size))
        }
    }

    /** 再开始下载下一段（最多同时 Store.relayParallel 段，窗口随着写盘往后滑） */
    private fun launchNext(c: DavConfig, m: Multi, lv: Live) {
        synchronized(m) {
            if (m.launched >= m.n) return
            val i = m.launched++
            m.jobs.add(m.scope.async {
                m.ctl.gate()   // 暂停中就不开始下载新的分段
                DavQuota.ensureRoom(m.ctl, 0L, m.psz, 2)   // 坚果云额度快满就自动暂停；2 = 下载 1 次 + 收完后删除 1 次
                fetchPart(c, m, i, lv)
            })
        }
    }

    /** 对方中止了发送，或者很久没有继续上传：消息在聊天里已标成失败，不再弹框 */
    private class PeerGoneException(msg: String, val aborted: Boolean, val n: Int) : IOException(msg)

    // ---------- 直接放在通知里的内容（不走网盘） ----------

    private fun inlineDir() = java.io.File(Hub.app.filesDir, "inline")
    private fun inlineFile(mid: String) = java.io.File(inlineDir(), "$mid.bin")
    private val inlineRecovered = AtomicBoolean(false)

    /**
     * 收到直发的内容：先存到本机文件（存好才回执，进程这时被杀也不会丢，Cloudflare 会补发），再走和网盘文件完全一样的接收流程。
     * 文件要等用户点“接收”，所以文件留在本机，接收 / 拒绝 / 7 天过期时才删
     */
    private fun handleInline(from: String, mid: String, b64: String, n: Long) {
        Hub.scope.launch(Dispatchers.IO) {
            var ok = true
            try {
                if (midPattern.matches(mid) && !doneMids.contains(mid) && !offers.containsKey(mid) && !inlineFile(mid).exists()) {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    val f = inlineFile(mid)
                    f.parentFile?.mkdirs()
                    val tmp = java.io.File(f.path + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(f)) ok = false
                }
            } catch (e: Exception) {
                ok = false
                Hub.log("Relay", e)
            }
            if (n > 0 && ok) sock?.send(JSONObject().put("op", "ack").put("n", n).toString())
            if (ok && midPattern.matches(mid) && !doneMids.contains(mid)) receive(from, mid, ".bin")
        }
    }

    /** 重启后：之前存下来、还没处理完的直发内容（待接收的文件）重新生成卡片；已经处理过的残留删掉 */
    private fun recoverInline() {
        if (!inlineRecovered.compareAndSet(false, true)) return
        Hub.scope.launch(Dispatchers.IO) {
            inlineDir().listFiles()?.forEach { f ->
                val mid = f.name.removeSuffix(".bin")
                if (!midPattern.matches(mid)) { f.delete(); return@forEach }
                if (doneMids.contains(mid)) f.delete()
                else if (!offers.containsKey(mid)) receive(null, mid, ".bin")
            }
        }
    }

    private suspend fun sendInline(peer: Peer, header: JSONObject, size: Long, open: (() -> InputStream)?, onProgress: (Long) -> Unit, ctl: TransferCtl?, localId: String?, mid: String) {
        ctl?.check()
        val s = sock
        if (s == null || !connected.value) throw IOException("没有连上 Cloudflare 中转服务器：" + state.value)
        val meta = JSONObject(header.toString()).put("mid", mid).put("from", Store.deviceId).put("ts", now())
            .toString().toByteArray(Charsets.UTF_8)
        val key = RelayCrypto.key(peer.rk)
        val blob = withContext(Dispatchers.IO) {
            val bo = java.io.ByteArrayOutputStream(maxOf(1024, size.toInt() + 512))
            val ins = open?.invoke()
            try {
                RelayCrypto.encrypt(key, meta, ins, bo, onProgress)
            } finally {
                ins?.close()
            }
            bo.toByteArray()
        }
        ctl?.check()
        val ack = CompletableDeferred<Boolean>()
        acks[mid] = ack
        try {
            val msg = notifyMsg(peer.id, JSONObject().put("mid", mid).put("inl", android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)))
            if (!s.send(msg)) throw IOException("消息没发出去：和 Cloudflare 的连接刚刚断开")
            // 上行慢的时候要多等一会：按至少 20 KB/s 估算
            withTimeout(15_000L + blob.size / 20L) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Cloudflare 没有确认消息已发出（网络太慢或刚好断开）", e)
        } finally {
            acks.remove(mid)
        }
        onProgress(size)
        markWaiting(localId, header)
    }

    private fun markDone(mid: String) {
        synchronized(doneMids) {
            doneMids.add(mid)
            while (doneMids.size > DONE_MAX) doneMids.remove(doneMids.first())
            Store.relayDoneMids = doneMids.toList()
        }
    }

    fun configured() = Store.relayOn && Store.relayUrl.isNotBlank() && Store.relaySecret.isNotBlank()

    /** 不能用远程中转的原因；null 表示可以用 */
    fun notReadyReason(p: Peer?): String? = when {
        !Store.relayOn -> "远程中转没有启用（设置 → 远程中转）"
        Store.relayUrl.isBlank() || Store.relaySecret.isBlank() -> "远程中转还没填 Cloudflare 地址或口令"
        Store.dav == null -> "远程中转还没填${Store.davName}账号"
        p != null && p.rk.isEmpty() -> "这台设备还没有和本机交换过加密密钥（两台手机需要同时在线、直连一次，或者重新扫码配对）"
        else -> null
    }

    fun ready(p: Peer) = notReadyReason(p) == null

    /**
     * 远程中转还没出结果：还没开始连、正在连，或者已连上但还没收到“谁在线”名单。自动连接热点前先等它一小会儿（远程优先）。
     * 只有已经连上并拿到名单，或者明确失败（“未连接：…”）了，才算有结果。
     * 以前只认“正在连接”，App 刚启动、连接还没开始时会误判成“没有远程”，抢先去开 Wi-Fi 和定位
     */
    fun settling() = configured() && !(connected.value && presence.value) && !state.value.startsWith("未连接")

    // ---------- 连接 ----------

    fun start() {
        if (job?.isActive == true) return
        restart()
    }

    fun restart() {
        if (!configured()) goodbye()   // 关掉远程中转 / 清空配置：也算下线，先告诉对方
        stop()
        if (!configured()) {
            state.value = if (Store.relayOn) "还没填 Cloudflare 地址或口令" else "未启用"
            return
        }
        backoff = 2000L
        job = Hub.scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { sock?.close(1000, "bye") } catch (e: Exception) { Hub.log("Relay", e) }
        sock = null
        connected.value = false
        presence.value = false
        online.value = emptySet()
    }

    /**
     * 本机主动下线（退出 app / 关掉远程中转）：先给还在线的对方各发一条“我下线了”，
     * 对方收到后立刻把本机标为离线，不用等 Cloudflare 发现连接断开。必须在 stop() 之前调用；
     * 消息排在关闭帧前面，会先发出去。被系统强杀时来不及发，这种情况只能靠 Cloudflare 自己发现断线
     */
    fun goodbye() {
        val s = sock ?: return
        if (!connected.value) return
        try {
            for (id in online.value) {
                val p = Hub.peers.value[id]?.takeIf { it.paired } ?: continue
                s.send(JSONObject().put("op", "notify").put("to", p.id).put("body", JSONObject().put("bye", true)).toString())
            }
        } catch (e: Exception) { Hub.log("Relay", e) }
    }

    /** 网络变了：旧 socket 多半已经不通了，直接丢掉并马上重连（不等退避和心跳超时） */
    fun kick() {
        backoff = 2000L
        if (connected.value) try { sock?.cancel() } catch (e: Exception) { Hub.log("Relay", e) }
        wake.trySend(Unit)
    }

    private fun wsUrl(): String {
        val u = Store.relayUrl.trim().trimEnd('/')
        UrlRules.relayError(u)?.let { throw IllegalArgumentException(it) }   // 不允许 http:// / ws:// 明文
        val base = when {
            u.startsWith("https://") -> "wss://" + u.removePrefix("https://")
            u.startsWith("wss://") -> u
            else -> "wss://$u"
        }
        return "$base/ws"
    }

    /** 当前有没有可用的上网网络（查不出来就当有，别因为判断失误一直不连） */
    private fun hasNetwork(): Boolean = try {
        val cm = Hub.app.getSystemService(android.net.ConnectivityManager::class.java)
        fun net(n: android.net.Network?) = n != null && cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        // 后台受限时 activeNetwork 可能拿不到：再看一眼所有网络，别误判成“没有网络”
        @Suppress("DEPRECATION")
        net(cm.activeNetwork) || cm.allNetworks.any { net(it) }
    } catch (e: Exception) {
        true
    }

    /** 这一轮连接有没有成功建立过（区分“连上后正常断开”和“一直连不上”） */
    @Volatile private var openedThisRound = false
    private var connectFails = 0

    /** 界面回到前台：没连上就马上重连，不等退避（事件触发，不是定时） */
    fun onForeground() {
        // 后台期间网盘那边的空闲连接多半已被系统 / 服务器悄悄断了：回到前台时清掉（只清空闲的，正在传的不受影响），免得第一次请求踩到断连接而失败
        Hub.scope.launch(Dispatchers.IO) {
            try { Http.dav.connectionPool.evictAll() } catch (e: Exception) { Hub.log("Relay", e) }
        }
        if (configured() && !connected.value) kick()
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            if (!hasNetwork()) {
                // 没有网络：别白白试连接。网络恢复 / 回到前台时会 kick() 唤醒这里；每分钟只在本机查一次网络状态兜底（不联网，几乎不耗电），
                // 因为后台受限时系统可能误报“没有网络”，不能一直傻等
                connected.value = false
                presence.value = false
                online.value = emptySet()
                state.value = "未连接：当前没有网络，恢复后自动重连"
                withTimeoutOrNull(60_000L) { wake.receive() }
                continue
            }
            val why = connectOnce()
            connected.value = false
            presence.value = false
            online.value = emptySet()
            // 连上过又断开（休眠、切网络、服务器心跳超时）是正常现象，会自动重连，不记录也不弹窗；
            // 一次都没连上才算连不上，连续 3 次以上才记入错误记录并提示
            val opened = openedThisRound
            openedThisRound = false
            if (opened) connectFails = 0 else connectFails++
            state.value = "未连接：$why（${backoff / 1000} 秒后重试）"
            if (!opened && connectFails >= 3) {
                if (connectFails == 3 || connectFails % 10 == 0) Hub.log("远程中转", IOException(why))
                Hub.reportOnce("relay-conn", "远程中转连不上 Cloudflare", why, "地址：" + Store.relayUrl)
            }
            withTimeoutOrNull(backoff) { wake.receive() }
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    /** 连一次，一直等到断开；返回断开的原因 */
    private suspend fun connectOnce(): String = coroutineScope {
        state.value = "正在连接 Cloudflare…"
        val closed = CompletableDeferred<String>()
        val req = try {
            Request.Builder().url(wsUrl())
                .header("X-Relay-Secret", Store.relaySecret)
                .header("X-Device", Store.deviceId)
                .header("X-Ack", "1")   // 告诉 Cloudflare：本机会回执“新消息”通知，没回执的请在重连时补发
                .build()
        } catch (e: IllegalArgumentException) {
            return@coroutineScope UrlRules.relayError(Store.relayUrl) ?: ("Cloudflare 地址格式不对：" + Store.relayUrl)
        }
        val s = Http.ws.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.value = true
                Hub.resendFailedText()
                recoverInline()
                openedThisRound = true
                backoff = 2000L
                state.value = if (Store.dav == null) "已连接（但${Store.davName}账号没填，收不到内容）" else "已连接"
                // 断线重连：断开期间可能漏了分段通知，正在接收的文件直接去网盘看一眼
                for (lv in live.values) {
                    lv.optimistic = true
                    lv.poke()
                }
                Hub.scope.launch { sweepInbox() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastRx = now()
                onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete("服务器关闭了连接（$code $reason）")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(explain(t, response))
            }
        })
        sock = s
        val hb = launch {
            // 只负责发现“半死连接”（切网络 / 休眠后 socket 还在但已经不通，没有任何事件会通知）：
            // 发 ping 后一定要有任何数据回来，没有就立刻判定断线，重连成功后 onOpen 会去网盘补收
            while (isActive) {
                delay(25_000)
                val seen = lastRx
                s.send("ping")
                val t0 = now()
                delay(10_000)
                // 实际等了远不止 10 秒：说明进程被系统冻结过（回应可能就压在队列里还没处理），这一轮不判，下一轮再看，免得白白重连
                if (lastRx == seen && now() - t0 < 20_000L) {
                    closed.complete("连接已失效（发出心跳后 10 秒没有任何回应）")
                    break
                }
            }
        }
        try {
            closed.await()
        } finally {
            hb.cancel()
            try { s.cancel() } catch (e: Exception) { Hub.log("Relay", e) }
            if (sock === s) sock = null
        }
    }

    private fun explain(t: Throwable, r: Response?): String {
        if (r != null) {
            return when (r.code) {
                401 -> "口令不对（Cloudflare 返回 401）：手机里的“中转口令”要和部署时设置的 RELAY_SECRET 完全一样"
                404 -> "Cloudflare 返回 404：地址可能填错了，或者中转程序还没有部署"
                else -> "Cloudflare 返回了 HTTP ${r.code}"
            }
        }
        netHint(t)?.let { return it + "（" + t.javaClass.simpleName + "）" }   // DNS 被改 / 握手被掐：别再说成“配对身份不一致”
        return friendlyError(t) + "（" + t.javaClass.simpleName + "）"
    }

    private fun onText(text: String) {
        if (text == "pong") return
        try {
            val j = JSONObject(text)
            when (j.optString("op")) {
                "presence" -> {
                    val a = j.getJSONArray("online")
                    online.value = (0 until a.length()).map { a.getString(it) }.filter { it != Store.deviceId }.toSet()
                    presence.value = true
                }
                "notify" -> {
                    val n = j.optLong("n", 0L)
                    val from = j.getString("from")
                    val body = j.getJSONObject("body")
                    // 先回执再处理；重复收到的靠消息编号去重。直发的内容例外：存到本机文件后才回执（handleInline）
                    if (n > 0 && !body.has("inl")) sock?.send(JSONObject().put("op", "ack").put("n", n).toString())
                    if (body.optBoolean("bye")) {   // 对方主动下线：马上标为离线
                        online.value = online.value - from
                        return
                    }
                    body.optJSONObject("ctl")?.let { Hub.onCtl(from, it); return }   // 回执 / 撤回等控制消息
                    val mid = body.getString("mid")
                    body.optJSONObject("q")?.let { DavQuota.peerReport(from, it) }   // 对方带来的坚果云用量
                    if (body.optBoolean("qonly")) return   // 只是报用量
                    if (body.has("inl")) { handleInline(from, mid, body.optString("inl"), n); return }
                    if (body.optBoolean("got")) {   // 接收方收完了：发出去的文件从“等待对方接收”变成“已发送”
                        sentMap[mid]?.let { id ->
                            Hub.patch(id) {
                                if (it.outgoing && (it.state == MsgState.WAITING || it.state == MsgState.SENDING || (it.state == MsgState.FAILED && it.error == "已中断"))) it.copy(state = MsgState.DONE, done = it.size, error = "") else it
                            }
                        }
                        return
                    }
                    if (body.optBoolean("cancel")) {   // 接收方点了取消 / 拒绝 / 一直没接收被清掉：发送方停止上传，或者更新“等待对方接收”
                        val out = sending[mid]?.takeIf { it.peerId == from }
                        if (out != null) {
                            out.ctl?.cancelByPeer()
                        } else {
                            sentMap[mid]?.let { id ->
                                Hub.patch(id) {
                                    if (it.outgoing && it.state == MsgState.WAITING) {
                                        it.copy(state = MsgState.CANCELED, error = if (body.optBoolean("expired")) "对方 7 天内没有接收，文件已自动清除" else "对方拒绝了")
                                    } else it
                                }
                            }
                        }
                        return
                    }
                    if (body.has("pause")) {   // 发送方暂停 / 继续：暂停期间没有新分段不算超时
                        live[mid]?.takeIf { it.from == null || it.from == from }?.let {
                            it.senderPaused = body.optBoolean("pause")
                            it.pausedSince = now()
                            it.poke()
                        }
                        if (!body.optBoolean("poke")) return
                    }
                    if (body.optBoolean("abort")) {   // 发送方失败 / 取消了：正在等分段的接收马上结束
                        live[mid]?.takeIf { it.from == null || it.from == from }?.let {
                            it.aborted = true
                            it.poke()
                        }
                        return
                    }
                    if (body.optBoolean("poke")) {   // 发送方说：本机刚上线，去网盘看一下有没有已经传好的分段
                        live[mid]?.takeIf { it.from == null || it.from == from }?.let {
                            it.optimistic = true
                            it.poke()
                        }
                        return
                    }
                    val p = body.optInt("p", 0)
                    if (p > 0) {   // 第 p 段传完了
                        live[mid]?.takeIf { it.from == null || it.from == from }?.let {
                            it.announced.add(p)
                            it.poke()
                        }
                        return
                    }
                    if (!midPattern.matches(mid) || doneMids.contains(mid)) return
                    // 先登记再启动接收：紧跟在后面的分段通知不会因为接收还没开始而丢掉
                    live.computeIfAbsent(mid) { Live(from) }
                    val x = body.optString("ext")
                    Hub.scope.launch { receive(from, mid, if (headExtOk(x)) ".$x" else null) }
                }
                "sent" -> acks.remove(j.optString("mid"))?.complete(j.optBoolean("online"))
                "error" -> acks.remove(j.optString("mid"))
                    ?.completeExceptionally(IOException("Cloudflare 没有转发这条通知：" + j.optString("error")))
            }
        } catch (e: Exception) {
            Hub.log("远程中转消息", e)
        }
    }

    // ---------- 设置页测试 ----------

    /** 测试 Cloudflare：地址通不通、口令对不对。失败抛出带原因的异常 */
    fun testWorker() {
        val raw = Store.relayUrl.trim().trimEnd('/')
        if (raw.isEmpty()) throw IOException("还没填 Cloudflare 地址")
        if (Store.relaySecret.isEmpty()) throw IOException("还没填中转口令")
        UrlRules.relayError(raw)?.let { throw IOException(it) }
        val base = if (raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
        val req = try {
            Request.Builder().url("$base/test").header("X-Relay-Secret", Store.relaySecret).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Cloudflare 地址格式不对：$raw", e)
        }
        val resp = try {
            Http.client.newCall(req).execute()
        } catch (e: IOException) {
            throw IOException(netHint(e) ?: throw e, e)
        }
        resp.use { r ->
            when (r.code) {
                200 -> Unit
                401 -> throw IOException("口令不对（401）：手机里的“中转口令”要和部署时设置的 RELAY_SECRET 完全一样")
                404 -> throw IOException("Cloudflare 返回 404：地址可能填错了，或者中转程序还没有部署")
                else -> throw IOException("Cloudflare 返回了 HTTP ${r.code}：" + (r.body?.string()?.take(200) ?: ""))
            }
        }
    }

    // ---------- 发送 ----------

    /** 通过坚果云 + Cloudflare 发给 peer。header 和直连时一样（type=text / file …） */
    suspend fun send(peer: Peer, header: JSONObject, size: Long, open: (() -> InputStream)?, onProgress: (Long) -> Unit, ctl: TransferCtl? = null, localId: String? = null) {
        notReadyReason(peer)?.let { throw IOException(it) }
        val s = sock
        if (s == null || !connected.value) throw IOException("没有连上 Cloudflare 中转服务器：" + state.value)
        val c = Store.dav ?: throw IOException("还没填${Store.davName}账号")
        val mid = newId()
        if (localId != null) rememberSent(mid, localId)
        val headExt = Store.relayHeadExt
        val psz = partSize(size)
        if (size <= INLINE_MAX) {   // 小内容直接放通知里发，不碰网盘
            sendInline(peer, header, size, open, onProgress, ctl, localId, mid)
            return
        }
        if (open != null && size > psz) {
            sendParts(peer, header, size, open, onProgress, c, mid, psz, ctl, localId)
            markWaiting(localId, header)
            return
        }
        ctl?.check()
        val meta = JSONObject(header.toString()).put("mid", mid).put("from", Store.deviceId).put("ts", now())
            .toString().toByteArray(Charsets.UTF_8)
        val key = RelayCrypto.key(peer.rk)
        val total = RelayCrypto.encryptedSize(meta.size, size)
        singleGate.withPermit {
            withContext(Dispatchers.IO) {
                var attempt = 0
                var busy = 0
                while (true) {
                    try {
                        ctl?.check()
                        WebDav.ensureDir(c, peer.id)
                        WebDav.put(c, "${peer.id}/$mid.$headExt", total, ctl) { out ->
                            val ins = open?.invoke()
                            try {
                                RelayCrypto.encrypt(key, meta, ins, out, onProgress)
                            } finally {
                                ins?.close()
                            }
                        }
                        break
                    } catch (e: WebDav.DavException) {
                        ctl?.check()
                        // 正在 429 退避中，请求没发出去：等到退避结束再来，不算一次失败
                        if (e.queued) {
                            delay(e.waitMs.coerceAtLeast(1000L))
                            continue
                        }
                        // 网盘上的文件夹被删了：缓存作废，重建后重试一次
                        if (attempt++ == 0 && (e.code == 404 || e.code == 409)) {
                            WebDav.forgetDirs()
                            continue
                        }
                        // 服务器一时忙（429 / 5xx）：等几秒再试，最多 2 次；被限流封了（BlockedTemporarily）重试只会封得更久，不试
                        if (!e.blocked && (e.code == 429 || e.code in 500..599) && busy++ < 2) {
                            delay(maxOf(2000L * busy, e.waitMs))   // 429 时按服务器要求 / 退避时间等
                            continue
                        }
                        throw e
                    }
                }
            }
        }
        DavQuota.addUp(total)
        try {
            ctl?.check()
        } catch (e: UserCancelException) {
            quietDelete(c, "${peer.id}/$mid.$headExt")   // 上传完的那一刻点了取消：别把文件留在网盘里
            throw e
        }
        val ack = CompletableDeferred<Boolean>()
        acks[mid] = ack
        try {
            if (!s.send(notifyMsg(peer.id, JSONObject().put("mid", mid).put("ext", headExt)))) throw IOException("内容已上传到${Store.davName}，但通知没发出去：和 Cloudflare 的连接刚刚断开")
            withTimeout(15_000) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("内容已上传到${Store.davName}，但 Cloudflare 15 秒内没有确认通知已发出", e)
        } finally {
            acks.remove(mid)
        }
        reportUsage(peer.id, mid)
        markWaiting(localId, header)
    }

    // ---------- 大文件：分段并行上传 ----------

    /**
     * 文件 = 第 0 段（$mid.dat，带消息头）+ 第 1.. 段（$mid-N.prt），每段是一个独立的加密文件，各自带一个被校验的小消息头
     * （消息编号 / 发送者 / 第几段 / 这段多长），所以分段不能被调换、混进别的消息、截断或少收。
     * 几段同时上传；第 0 段传完先通知对方开始收，之后每传完一段再通知一次
     */
    private suspend fun sendParts(
        peer: Peer, header: JSONObject, size: Long, open: () -> InputStream, onProgress: (Long) -> Unit,
        c: DavConfig, mid: String, psz: Long, ctl: TransferCtl?, localId: String? = null
    ) {
        val n = ((size + psz - 1) / psz).toInt()
        val headExt = Store.relayHeadExt
        val headMeta = JSONObject(header.toString()).put("mid", mid).put("from", Store.deviceId).put("ts", now())
            .put("parts", n).put("psz", psz).toString().toByteArray(Charsets.UTF_8)
        val key = RelayCrypto.key(peer.rk)
        val sent = AtomicLong(0)
        val lock = Any()
        val uploaded = ConcurrentHashMap.newKeySet<Int>()
        val headSent = CompletableDeferred<Unit>()
        val headNotified = AtomicBoolean(false)
        val par = Store.relayParallel
        if (ctl != null) {
            ctl.canPause = true
            sending[mid] = Outgoing(peer.id, ctl)
        }
        try {
            withContext(Dispatchers.IO) {
                ctl?.check()
                WebDav.ensureDir(c, peer.id)
                coroutineScope {
                    // 对方在传输中途上线：它错过了之前的分段通知，发一条“去网盘看一下”（等在线名单变化，不轮询）
                    val watcher = launch {
                        var was = peer.id in online.value
                        online.collect { set ->
                            val now = peer.id in set
                            if (now && !was && headNotified.get()) {
                                try {
                                    sendNotify(peer.id, JSONObject().put("mid", mid).put("poke", true).put("pause", ctl?.paused == true))
                                } catch (e: Exception) {
                                    Hub.log("Relay", e)
                                }
                            }
                            was = now
                        }
                    }
                    // 本机点了暂停 / 继续：告诉对方，它就不会把“没有新分段”当成超时
                    val pauseWatcher = launch {
                        if (ctl == null) return@launch
                        var last = TransferCtl.RUN
                        ctl.state.collect { st ->
                            if (st != last && st != TransferCtl.CANCELED) {
                                last = st
                                if (headNotified.get() && peer.id in online.value) {
                                    try {
                                        sendNotify(peer.id, JSONObject().put("mid", mid).put("pause", st == TransferCtl.PAUSED))
                                    } catch (e: Exception) {
                                        Hub.log("Relay", e)
                                    }
                                }
                            }
                        }
                    }
                    coroutineScope {
                        val next = AtomicInteger(0)
                        repeat(minOf(par, n)) {
                            launch {
                                while (true) {
                                    ctl?.gate()   // 暂停中就停在这里，不开始新的分段；已经在传的会传完
                                    val i = next.getAndIncrement()
                                    if (i >= n) break
                                    // 坚果云额度快满就在这里自动暂停（只拦新的一段，已经在传的传完）
                                    DavQuota.ensureRoom(ctl, minOf(psz, size - i * psz), 0L, 1)
                                    uploadPart(c, peer, mid, key, headMeta, headExt, i, psz, size, open, sent, lock, onProgress, ctl) { slowHint(localId, "第 ${i + 1} / $n 段较慢，已换连接重试") }
                                    slowHint(localId, "")
                                    uploaded.add(i)
                                    // 通知放到单独的协程里发，这条分段的“工人”马上去传下一段：
                                    // 以前它要等第 0 段的通知被 Cloudflare 确认（最多十几秒、断线时更久），所有分段都跟着停在原地
                                    if (i == 0) {
                                        headNotified.set(true)
                                        launch {
                                            notifyHead(peer, mid, headExt)
                                            headSent.complete(Unit)
                                        }
                                    } else {
                                        launch {
                                            headSent.await()   // 对方先收到第 0 段的通知，再收到后面各段的
                                            // 对方不在线就不发：Cloudflare 会把每条通知都存起来（每台设备最多 200 条），
                                            // 几十条分段通知会挤掉别的消息；对方上线后自己扫描网盘就能收到已传好的分段
                                            if (peer.id in online.value) sendNotify(peer.id, JSONObject().put("mid", mid).put("p", i))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    watcher.cancel()
                    pauseWatcher.cancel()
                }
            }
        } catch (e: Throwable) {
            // 失败或被取消：通知对方别再等了，并把已经传上去的分段删掉
            withContext(NonCancellable + Dispatchers.IO) {
                if (headNotified.get() && peer.id in online.value && ctl?.byPeer != true) {
                    try {
                        sock?.send(notifyMsg(peer.id, JSONObject().put("mid", mid).put("abort", true)))
                    } catch (x: Exception) {
                        Hub.log("Relay", x)
                    }
                }
                for (i in uploaded) quietDelete(c, "${peer.id}/" + if (i == 0) "$mid.$headExt" else "$mid-$i.prt")
            }
            throw e
        } finally {
            sending.remove(mid)
        }
    }

    private suspend fun uploadPart(
        c: DavConfig, peer: Peer, mid: String, key: SecretKeySpec, headMeta: ByteArray, headExt: String,
        i: Int, psz: Long, size: Long, open: () -> InputStream,
        sent: AtomicLong, lock: Any, onProgress: (Long) -> Unit, ctl: TransferCtl?, onSlow: () -> Unit = {}
    ) {
        val plain = minOf(psz, size - i * psz)
        val meta = if (i == 0) headMeta else JSONObject().put("mid", mid).put("from", Store.deviceId)
            .put("idx", i).put("len", plain).toString().toByteArray(Charsets.UTF_8)
        val total = RelayCrypto.encryptedSize(meta.size, plain)
        val name = if (i == 0) "${peer.id}/$mid.$headExt" else "${peer.id}/$mid-$i.prt"
        var attempt = 0
        var slowTries = 0   // 这一段因为太慢换过几次连接：换 3 次还是慢就不再判慢了（网络本来就慢）
        while (true) {
            var last = 0L
            val t0 = System.nanoTime()
            try {
                WebDav.put(c, name, total, ctl, fresh = slowTries > 0 || attempt > 0) { out ->
                    val ins = open()
                    try {
                        skipFully(ins, i * psz)
                        RelayCrypto.encrypt(key, meta, ins, out, onProgress = { v ->
                            synchronized(lock) {
                                sent.addAndGet(v - last)
                                last = v
                                onProgress(sent.get())
                            }
                            // 边传边看速度：这一段太慢就中断，换新连接重传（没有定时器，每写一块顺便算一下）
                            if (slowTries < 3 && WebDav.isSlow((System.nanoTime() - t0) / 1_000_000L, v)) {
                                throw WebDav.SlowException("第 ${i + 1} 段上传太慢")
                            }
                        }, limit = plain)
                    } finally {
                        ins.close()
                    }
                }
                DavQuota.addUp(total)
                return
            } catch (e: WebDav.DavException) {
                ctl?.check()   // 取消造成的失败不要重试
                synchronized(lock) { sent.addAndGet(-last); onProgress(sent.get()) }   // 这一段要重传，进度退回去
                // 正在 429 退避中，请求没发出去：等到退避结束再来，不算一次失败
                if (e.queued) {
                    delay(e.waitMs.coerceAtLeast(1000L))
                    continue
                }
                val folderGone = e.code == 404 || e.code == 409
                val busy = !e.blocked && (e.code == 429 || e.code in 500..599)
                if (attempt++ < 2 && (folderGone || busy)) {
                    if (folderGone) {
                        WebDav.forgetDirs()
                        WebDav.ensureDir(c, peer.id)
                    } else {
                        delay(maxOf(2000L * attempt, e.waitMs))   // 429 时按服务器要求 / 退避时间等
                    }
                    continue
                }
                throw e
            } catch (e: IOException) {
                ctl?.check()   // 取消中断了正在传的请求：不要重试
                synchronized(lock) { sent.addAndGet(-last); onProgress(sent.get()) }
                if (e is WebDav.SlowException || e.cause is WebDav.SlowException) {
                    slowTries++   // 太慢：马上换新连接重传，不等
                    onSlow()
                    continue
                }
                if (attempt++ < 2) {
                    delay(1500L * attempt)
                    ctl?.check()
                    continue
                }
                throw e
            }
        }
    }

    /** 跳过流的前 n 个字节（有的流 skip 可能一次跳不完，甚至返回 0） */
    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = ins.skip(left)
            if (k > 0) {
                left -= k
            } else {
                if (ins.read() < 0) throw IOException("文件读取不完整")
                left--
            }
        }
    }

    /** 第 0 段传完后的通知：和单文件一样，等 Cloudflare 确认 */
    private suspend fun notifyHead(peer: Peer, mid: String, headExt: String) {
        val ack = CompletableDeferred<Boolean>()
        acks[mid] = ack
        try {
            sendNotify(peer.id, JSONObject().put("mid", mid).put("ext", headExt))
            withTimeout(15_000) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("内容已上传到${Store.davName}，但 Cloudflare 15 秒内没有确认通知已发出", e)
        } finally {
            acks.remove(mid)
        }
    }

    /** 发一条通知。刚好断线的话，等它重连上（等连接状态变化，不是轮询）最多 20 秒再发一次 */
    /** 通知的内容：带上本机的坚果云用量，对方才能算出账号的总用量 */
    private fun notifyMsg(to: String, body: JSONObject): String {
        DavQuota.report()?.let { body.put("q", it) }
        return JSONObject().put("op", "notify").put("to", to).put("body", body).toString()
    }

    /** 传完 / 收完后，把本机最新的用量告诉对方（对方在线才发） */
    private suspend fun reportUsage(peerId: String, mid: String) {
        if (Store.davProvider != "jgy" || peerId !in online.value) return
        try {
            sendNotify(peerId, JSONObject().put("mid", mid).put("qonly", true))
        } catch (e: Exception) {
            Hub.log("Relay", e)
        }
    }

    private suspend fun sendNotify(to: String, body: JSONObject) {
        val msg = notifyMsg(to, body)
        var tries = 0
        while (true) {
            val s = sock
            if (s != null && connected.value && s.send(msg)) return
            if (tries++ >= 1) throw IOException("内容已上传到${Store.davName}，但通知没发出去：和 Cloudflare 的连接断开了")
            withTimeoutOrNull(20_000) { connected.first { it } }
        }
    }

    private fun quietDelete(c: DavConfig, name: String) {
        // 直发的内容存在本机文件里，不在网盘上：删本机文件，不发网盘请求（省请求次数）
        val base = name.substringAfterLast('/')
        if (base.length >= 36 && inlineFile(base.substring(0, 36)).delete()) return
        try {
            WebDav.delete(c, name)
        } catch (e: WebDav.DavException) {
            if (e.code != 404) Hub.log("删除中转文件", e)
        } catch (e: Exception) {
            Hub.log("删除中转文件", e)
        }
    }

    // ---------- 接收 ----------

    /** 连上 Cloudflare 后，把坚果云里留给本机的内容都收一遍（对方发的时候本机可能不在线） */
    private suspend fun sweepInbox() = withContext(Dispatchers.IO) {
        if (!sweeping.compareAndSet(false, true)) return@withContext   // 已经有一次在扫了
        try {
            val c = Store.dav ?: return@withContext
            val entries = WebDav.listEntries(c, Store.deviceId)
            for (e in entries) headOf(e.name)?.let { (m, x) -> receive(null, m, ".$x") }
            cleanupRemnants(c, entries, ORPHAN_MIN_AGE_MS)   // 收完了顺手把残留清掉（用的是同一份列表，不多发请求）
        } catch (e: Exception) {
            Hub.log("检查${Store.davName}里的未收消息失败", e)   // 后台动作，偶发网络问题很常见：只记录，不弹窗
        } finally {
            sweeping.set(false)
        }
    }

    /** 收一条：fromHint 是通知里的发送者（扫描网盘时未知，null）。从坚果云下载、解密、记入聊天、删除 */
    private suspend fun receive(fromHint: String?, mid: String, ext: String? = null): Unit = withContext(Dispatchers.IO) {
        if (!midPattern.matches(mid)) return@withContext
        if (keepMids.contains(mid) && !accepted.contains(mid)) {   // 失败了在等用户点“重试”：扫描、重复通知都别再弹新的待接收卡片
            live.remove(mid)
            return@withContext
        }
        if (doneMids.contains(mid)) {
            failVisible(mid, "这条消息已经处理过（收完、拒绝或失败过），本机不会再收一次，请让对方重新发送")
            live.remove(mid)
            return@withContext
        }
        if (offers.containsKey(mid)) return@withContext   // 已经有张待接收的卡片了，等用户决定
        // 同一条已经有人在处理了（回前台时 Cloudflare 补发的通知和扫描网盘会撞上点“接收”）：重复的这次悄悄退出。
        // 以前这里会把卡片标成失败，用户就看到“提示失败”
        if (!processing.add(mid)) return@withContext
        var again = false
        val lv = live.computeIfAbsent(mid) { Live(fromHint).also { l -> l.optimistic = fromHint == null } }
        var multi: Multi? = null
        // 知道后缀（扫描网盘时、或通知里带了）先试它，找不到（404）再依次试 .dat、.bin（旧版本的发件人没有后缀信息）
        val exts = (listOfNotNull(ext) + defaultHeadExts).distinct()
        var extIdx = 0
        var path = "${Store.deviceId}/$mid${exts[0]}"
        try {
            val c = Store.dav ?: throw IOException("还没填${Store.davName}账号，收不到远程发来的内容")
            val peers = Hub.peers.value.values.filter {
                it.paired && it.rk.isNotEmpty() && (fromHint == null || it.id == fromHint)
            }
            if (peers.isEmpty()) {
                Hub.log("远程中转", IOException("收到来自 ${fromHint ?: "未知设备"} 的内容，但没有对应的已配对设备或加密密钥，已忽略"))
                failVisible(mid, "没有对应的已配对设备或加密密钥，无法接收")
                markDone(mid)
                return@withContext
            }
            // 下载中途被断开（常见：对方刚传完，网盘还没写好）：消息头还没读完整时没有任何副作用，等几秒从头再下一次，最多 2 次；
            // 退避中（刚被 429 限速、请求没发出去）也是等一等再来
            var cuts = 0
            var waits = 0
            while (true) {
                try {
                    try {
                        val localHead = inlineFile(mid).takeIf { it.exists() }   // 直发的内容在本机文件里，不用下载
                        val onHead: (InputStream) -> Unit = { ins ->
                            val cin = CountingInputStream(ins)
                            multi = handleStream(mid, peers, cin, lv)
                            if (localHead == null) DavQuota.addDown(cin.count)
                        }
                        if (localHead != null) localHead.inputStream().use(onHead) else WebDav.get(c, path, block = onHead)
                    } catch (e: WebDav.DavException) {
                        if (e.code == 404) {
                            if (extIdx + 1 < exts.size) {   // 换另一个扩展名再找一次
                                extIdx++
                                path = "${Store.deviceId}/$mid${exts[extIdx]}"
                                continue
                            }
                            failVisible(mid, "${Store.davName}上找不到这个文件（可能对方已取消、已被删除，或还没传完）")
                            markDone(mid) // 已经收过并删除了
                            return@withContext
                        }
                        if (e.queued && waits++ < 10) {
                            delay(e.waitMs.coerceAtLeast(1000L))
                            continue
                        }
                        throw e
                    }
                    break
                } catch (e: WebDav.StreamCutException) {
                    if (lv.gotHeader || cuts >= 2) {
                        throw if (cuts > 0) WebDav.StreamCutException("${e.message}（已自动重试 $cuts 次，仍然失败）", e) else e
                    }
                    cuts++
                    Hub.log("远程中转：下载被中途断开，第 $cuts 次重试（$mid）", e)
                    delay(3000L * cuts)
                }
            }
            // 分段文件：第 0 段（上面）收完后，继续收后面的分段
            multi?.let { finishMulti(c, it, lv) }
            markDone(mid)
            multi?.let { deleteParts(c, mid, it.n) }
            quietDelete(c, path)
            lv.peerId?.let { reportUsage(it, mid) }
        } catch (e: OfferException) {
            // 这次读消息头的过程中用户已经点了“接收”（这是重复的一次扫描 / 补发通知先读到的）：不能再弹新的待接收卡片，
            // 释放处理权后按“已接收”重新来一遍（见函数末尾）
            if (accepted.contains(mid)) again = true else try {
                if (Store.isRecalled(e.meta.optString("cid"))) {
                    // 对方在消息还没来得及显示时就撤回了：不显示，直接清掉网盘上的文件
                    markDone(mid)
                    Store.dav?.let { d ->
                        val parts = e.meta.optInt("parts", 1).coerceAtLeast(1)
                        if (parts > 1) deleteParts(d, mid, parts)
                        quietDelete(d, path)
                    }
                    return@withContext
                }
                offers[mid] = Offer(fromHint, e.peer.id, exts[extIdx], e.meta.optInt("parts", 1).coerceAtLeast(1))
                Inbox.offerFile(e.peer.id, mid, e.meta)
            } catch (x: Exception) {
                offers.remove(mid)
                markDone(mid)
                Hub.report("远程接收失败", x, "消息：$mid")
            }
        } catch (e: NoKeyException) {
            markDone(mid)
            if (fromHint == null) {
                // 扫描网盘时已经试过本机所有已配对设备的密钥，第一块完整数据都解不开：多半是重新配对前留下的旧内容，
                // 旧密钥已经没了，以后也不可能解开。删掉它，免得每次重连都再扫到、再弹一次框；只记入错误记录，不打扰
                Hub.log("远程中转：网盘里有一条解不开的旧内容，已删除（$mid）", e)
                try {
                    Store.dav?.let { quietDelete(it, path) }
                } catch (d: Exception) {
                    Hub.log("删除解不开的中转文件", d)
                }
            } else {
                // 收到的是对方刚发来的通知，对方等着这条消息：仍然提示，但不删文件（下次扫描网盘时再判断）
                if (accepted.contains(mid)) failVisible(mid, friendlyError(e)) else Hub.report("远程接收失败", e, "消息：$mid")
            }
        } catch (e: UserCancelException) {
            // 本机点了取消：把网盘上这条消息的残留清掉，并告诉发送方别再传了
            markDone(mid)
            val m = multi
            if (m != null) {
                Store.dav?.let { d ->
                    deleteParts(d, mid, m.n)
                    quietDelete(d, path)
                }
                if (m.peer.id in online.value) {
                    try {
                        sendNotify(m.peer.id, JSONObject().put("mid", mid).put("cancel", true))
                    } catch (x: Exception) {
                        Hub.log("Relay", x)
                    }
                }
            } else {
                // 第 0 段还没收完就点了取消：用消息头里记下的分段数和发送者来清理、通知
                Store.dav?.let { d ->
                    if (lv.parts > 1) deleteParts(d, mid, lv.parts)
                    quietDelete(d, path)
                }
                val pid = lv.peerId
                if (pid != null && pid in online.value) {
                    try {
                        sendNotify(pid, JSONObject().put("mid", mid).put("cancel", true))
                    } catch (x: Exception) {
                        Hub.log("Relay", x)
                    }
                }
            }
        } catch (e: PeerGoneException) {
            markDone(mid)
            Hub.log("远程接收", e)   // 聊天里那条消息已经标成失败并写明了原因，不再弹框
            if (!e.aborted) {
                // 对方很久没有继续上传：把已经传上来的残留清掉，免得每次重连都再扫到
                Store.dav?.let { d ->
                    deleteParts(d, mid, e.n)
                    quietDelete(d, path)
                }
            }
        } catch (e: StaleException) {
            markDone(mid)
            Hub.report("远程接收：已丢弃过旧的消息", e, "消息：$mid")
            Store.dav?.let { quietDelete(it, path) }
        } catch (e: Exception) {
            if (accepted.contains(mid)) {
                // 用户点了接收但失败了：网盘上的文件留着，聊天里给“重试”（不记成已处理，也不会被清理）
                keepMids.add(mid)
                failVisible(mid, friendlyError(e))
            } else {
                markDone(mid) // 自动扫描失败的不要在本次运行里反复重试
                Hub.report("远程接收失败", e, "消息：$mid")
            }
        } finally {
            multi?.scope?.cancel()   // 提前开始下载的分段：不管成功失败都停掉
            multi?.let { Transfers.end(it.job.id, it.ctl) }
            // 点“接收”后消息还停在“接收中”、却没有传输在跑（没走到开始传就退出了）：不能让它一直转
            Hub.msgs.value.firstOrNull { it.id == mid }?.let { c ->
                if (c.state == MsgState.RECEIVING && Transfers.get(mid) == null && !offers.containsKey(mid)) {
                    Hub.patch(mid) { it.copy(state = MsgState.FAILED, error = "接收没有开始，请重试或让对方重发") }
                }
            }
            processing.remove(mid)
            live.remove(mid)
        }
        if (again) Hub.scope.launch { receive(fromHint, mid, ext) }
    }

    /** 用户点了“接收”：开始下载。分段文件的后续分段通知在等待期间被忽略了，所以直接去网盘取，不等通知 */
    fun acceptOffer(mid: String) {
        val o = offers.remove(mid) ?: run {
            Hub.toast("这个文件已经失效，请让对方重新发送")
            Hub.removeMsg(mid)
            return
        }
        // 卡片先别删：马上变成“接收中 0%”，读到消息头、正式的接收消息建好以后才换掉（receiveFile 里删），中间不会空着
        Hub.patch(mid) { it.copy(state = MsgState.RECEIVING, done = 0L, error = "") }
        accepted.add(mid)
        acceptPeer[mid] = o.peerId
        live.computeIfAbsent(mid) { Live(o.from) }.optimistic = true
        Hub.scope.launch { receive(o.from, mid, if (o.ext.startsWith(".")) o.ext else ".${o.ext}") }
    }

    /** 用户点了“拒绝”：不下载，清掉网盘上的文件，告诉发送方 */
    fun rejectOffer(mid: String) {
        val o = offers.remove(mid) ?: run { Hub.removeMsg(mid); return }
        markDone(mid)
        Hub.patch(mid) { it.copy(state = MsgState.CANCELED, error = "已拒绝") }
        Hub.persistNow()
        Hub.scope.launch(Dispatchers.IO) {
            Store.dav?.let { d ->
                if (o.parts > 1) deleteParts(d, mid, o.parts)
                quietDelete(d, "${Store.deviceId}/$mid${if (o.ext.startsWith(".")) o.ext else "." + o.ext}")
            }
            try {
                sendNotify(o.peerId, JSONObject().put("mid", mid).put("cancel", true))   // 对方不在线也发：Cloudflare 会暂存，对方上线就能看到“被拒绝”
            } catch (x: Exception) {
                Hub.log("Relay", x)
            }
        }
    }

    /** 所有候选密钥都解不开消息头（和下载中断、文件损坏这类错误区分开：那些不会走到这里） */
    private class NoKeyException(msg: String) : IOException(msg)

    /** 消息太旧：不重试，也不留在网盘里 */
    private class StaleException(msg: String) : IOException(msg)

    private fun handleStream(mid: String, peers: List<Peer>, ins: InputStream, lv: Live): Multi? {
        val reader = RelayCrypto.Reader(ins)
        val keys = peers.map { RelayCrypto.key(it.rk) }
        val (idx, metaBytes) = reader.open(keys)
            ?: throw NoKeyException("无法解密：密钥对不上（对方用的不是和本机交换过的密钥，或者这不是发给本机的内容）")
        lv.gotHeader = true   // 到这里才有副作用：之前断了可以整个重来
        val peer = peers[idx]
        lv.peerId = peer.id
        val meta = JSONObject(String(metaBytes, Charsets.UTF_8))
        if (meta.optString("from") != peer.id) throw IOException("来源校验失败：消息里的发送者和密钥对应的设备不一致")
        // 旧版本发的没有 ts，照常收；有 ts 但太旧的，当作被重放的旧文件
        val ts = meta.optLong("ts", 0L)
        if (ts > 0 && now() - ts > MAX_AGE_MS) throw StaleException("这条远程消息是 30 天前发的，本机已经不收这么旧的内容（可能是被人重新放回网盘的旧文件）")
        return when (val type = meta.getString("type")) {
            "text" -> {
                val text = meta.getString("text")
                if (text.length > Server.MAX_TEXT_LEN) throw IOException("文字太长")
                Inbox.addText(peer.id, text, meta.optString("cid"), meta.optString("rq"))
                sendGot(peer.id, mid)   // 告诉发送方：文字本机收到了（“已发出”变“已送达”）
                null
            }
            "file" -> {
                if (!accepted.contains(mid)) throw OfferException(peer, meta)   // 文件不自动下载：等用户点“接收”
                receiveFile(mid, peer, keys[idx], meta, reader, lv)
            }
            else -> throw IOException("不支持的消息类型：$type")
        }
    }

    /** 单文件收完返回 null；分段文件只收完第 0 段，返回还要继续收的状态 */
    private fun receiveFile(mid: String, peer: Peer, key: SecretKeySpec, meta: JSONObject, reader: RelayCrypto.Reader, lv: Live): Multi? {
        // 点“接收”时留着的卡片原位改成“接收中”（同一个 id，列表不会跳）；没有卡片才新建
        val job = Inbox.beginFile(peer.id, meta, mid)
        // 聊天里一出现这条消息就有控制柄：取消按钮马上能点（分段文件的暂停也在读到消息头后马上开放，见下）
        val ctl = Transfers.begin(job.id)
        var handedOver = false
        var early: Multi? = null
        try {
            val parts = meta.optInt("parts", 1)
            val psz = meta.optLong("psz", 0L)
            lv.parts = parts
            if (parts > 1) {
                if (meta.optString("mid") != mid) throw IOException("消息编号校验失败：文件名和消息里的编号对不上")
                if (psz < RelayCrypto.CHUNK || psz > MAX_PART || parts.toLong() != (job.size + psz - 1) / psz) {
                    throw IOException("分段信息无效")
                }
                // 消息头校验通过：后面的分段马上并行开始下载，不等第 0 段收完
                val em0 = Multi(peer, key, mid, job, parts, psz, ctl)
                early = em0
                ctl.canPause = true   // 分段文件读到消息头就能暂停：暂停后不再开始新的分段，第 0 段（最多 4～8 MB）会继续收完
                Store.dav?.let { dc -> repeat(Store.relayParallel) { launchNext(dc, em0, lv) } }
            }
            val want = if (parts > 1) psz else job.size   // 这个文件里应该有多少数据
            var got = 0L
            var last = 0L
            val os = job.saved.stream
            while (got < want) {
                ctl.check()   // 点了取消就停
                val f = reader.next()
                    ?: throw IOException("远程文件不完整：中途就结束了（已收到 ${fmtSize(got)} / ${fmtSize(job.size)}）")
                if (got + f.size > want) throw IOException("数据与声明的大小不符")
                os.write(f)
                got += f.size
                val em = early
                if (em != null) {
                    em.got = got
                    showProgress(em)
                } else {
                    val t = now()
                    if (t - last > 120) {
                        last = t
                        Inbox.progress(job, got)
                    }
                }
            }
            if (early != null) {
                ctl.canPause = true
                handedOver = true   // 控制柄交给后面收各段的流程，由它结束
                return early.also { it.got = got }
            }
            os.close()
            Inbox.finishFile(peer.id, job)
            sendGot(peer.id, mid)
            return null
        } catch (e: Exception) {
            early?.scope?.cancel()   // 第 0 段失败了：提前开始的分段下载也停掉
            try { job.saved.stream.close() } catch (x: Exception) { Hub.log("Relay", x) }
            if (ctl.cancelled) {
                Inbox.cancelFile(job)
                throw UserCancelException()
            }
            Inbox.failFile(job, e)
            throw e
        } finally {
            if (!handedOver) Transfers.end(job.id, ctl)
        }
    }

    /**
     * 继续收第 1.. 段：同时下载几段（设置里的并行数），按顺序解密写进文件。
     * 某一段还没传上来就等对方的分段通知（或重连 / 取消 / 超时），不轮询
     */
    private suspend fun finishMulti(c: DavConfig, m: Multi, lv: Live) {
        val ctl = m.ctl
        ctl.onCancel = { lv.poke() }   // 取消时唤醒正在等分段通知的下载
        try {
            ctl.check()
            // 前面几段在第 0 段还没收完时就已经开始下了（receiveFile 里）；这里按顺序取出来写盘，写一段就再开始下一段
            val par = Store.relayParallel
            repeat((par - (m.launched - 1)).coerceAtLeast(0)) { launchNext(c, m, lv) }   // 窗口里还差几段就补几段（提前开始的已经算在内）
            for (i in 1 until m.n) {
                val raw = m.jobs[i - 1].await()
                launchNext(c, m, lv)
                m.inflight.addAndGet(-raw.size)
                consumePart(raw, i, m)
            }
            m.job.saved.stream.close()
            if (m.got != m.job.size) throw IOException("远程文件大小对不上（收到 ${fmtSize(m.got)} / ${fmtSize(m.job.size)}）")
            Inbox.finishFile(m.peer.id, m.job)
            sendGot(m.peer.id, m.mid)
        } catch (e: Exception) {
            try { m.job.saved.stream.close() } catch (x: Exception) { Hub.log("Relay", x) }
            if (ctl.cancelled) {
                Inbox.cancelFile(m.job)
                throw UserCancelException()
            }
            Inbox.failFile(m.job, e)
            throw e
        }
    }

    private suspend fun fetchPart(c: DavConfig, m: Multi, i: Int, lv: Live): okio.Buffer {
        val name = "${Store.deviceId}/${m.mid}-$i.prt"
        val max = m.psz + (m.psz / RelayCrypto.CHUNK + 2) * 20 + 4096
        var slowTries = 0   // 这一段因为太慢换过几次连接：换 3 次还是慢就不再判慢了（网络本来就慢，让它慢慢下）
        fun tryGet(): okio.Buffer? {
            while (true) {
                var added = 0L
                try {
                    return WebDav.getBuffer(c, name, max, m.ctl, fresh = slowTries > 0, slowCheck = slowTries < 3) { d ->
                        added += d
                        m.inflight.addAndGet(d)
                        showProgress(m)
                    }.also {
                        DavQuota.addDown(it.size)
                        if (slowTries > 0) slowHint(m.mid, "")
                    }   // 成功：这些字节留在 inflight 里，写盘时（finishMulti）按原始大小扣掉
                } catch (e: Exception) {
                    m.inflight.addAndGet(-added)   // 这次没下成：已经算进去的字节撤回
                    if (e is WebDav.SlowException) {
                        m.ctl.check()
                        slowTries++
                        slowHint(m.mid, "第 ${i + 1} / ${m.n} 段较慢，已换连接重试")
                        continue
                    }
                    if (e is WebDav.DavException && e.code == 404) return null else throw e
                }
            }
        }
        var waitStart = now()   // 从什么时候开始一直没有新动静（放弃的时间从这里算）
        while (true) {
            m.ctl.gate()
            if (lv.aborted) throw PeerGoneException("对方中止了发送（发送方出错或取消了）", true, m.n)
            val seen = lv.tick.value   // 先记下，再去看：看的时候来的通知不会漏掉
            if (lv.optimistic || i in lv.announced) tryGet()?.let { return it }
            // 每隔 PART_POLL_MS 就去网盘看一眼（通知可能丢了），而不是干等到放弃的时间
            if (withTimeoutOrNull(PART_POLL_MS) { lv.tick.first { it != seen } } != null) {
                waitStart = now()
                continue
            }
            if (m.ctl.paused) { waitStart = now(); continue }   // 本机暂停中：不算对方超时，继续后再等
            if (lv.senderPaused && now() - lv.pausedSince < SENDER_PAUSE_MAX_MS) { waitStart = now(); continue }   // 对方暂停了
            tryGet()?.let { return it }
            if (now() - waitStart >= PART_WAIT_MS) {
                val why = if (lv.senderPaused) "对方暂停发送后超过 ${SENDER_PAUSE_MAX_MS / 60000} 分钟没有继续" else "对方 ${PART_WAIT_MS / 1000} 秒没有继续上传"
                throw PeerGoneException("$why（第 ${i + 1} / ${m.n} 段）", false, m.n)
            }
        }
    }

    private fun consumePart(raw: okio.Buffer, i: Int, m: Multi) {
        val reader = RelayCrypto.Reader(raw.inputStream())
        val (_, metaBytes) = reader.open(listOf(m.key)) ?: throw IOException("第 ${i + 1} 段解不开：密钥对不上")
        val pm = JSONObject(String(metaBytes, Charsets.UTF_8))
        val want = minOf(m.psz, m.job.size - i * m.psz)
        if (pm.optString("mid") != m.mid || pm.optString("from") != m.peer.id ||
            pm.optInt("idx", -1) != i || pm.optLong("len", -1L) != want
        ) throw IOException("第 ${i + 1} 段校验失败：不是这条消息的分段，或者被调换过")
        val os = m.job.saved.stream
        var got = 0L
        while (got < want) {
            val f = reader.next() ?: throw IOException("第 ${i + 1} 段不完整：中途就结束了")
            if (got + f.size > want) throw IOException("第 ${i + 1} 段的数据与声明的大小不符")
            os.write(f)
            got += f.size
            m.got += f.size
            showProgress(m)
        }
        if (reader.next() != null) throw IOException("第 ${i + 1} 段的数据比声明的多")
    }

    /**
     * 清理网盘上本机文件夹里的残留（发给本机的内容都在这个文件夹里）：
     *  - 已经处理过的消息（收完了，或者已经失败结案）还留着的文件：消息头文件没删掉、分段没删完
     *  - 孤儿分段：对应的消息头文件不在了（发送方中途挂了；或者接收方放弃后发送方还在传），放够 minAgeMs 才删
     * 正在接收的消息不碰。返回 (删了几个文件, 约释放多少字节)
     */
    private suspend fun cleanupRemnants(c: DavConfig, entries: List<WebDav.Entry>, minAgeMs: Long): Pair<Int, Long> {
        val now = System.currentTimeMillis()
        fun midOf(name: String): String? = headOf(name)?.first
            ?: if (name.endsWith(".prt")) name.removeSuffix(".prt").substringBeforeLast('-', "").takeIf { uuidPattern.matches(it) } else null   // 消息编号本身带 '-'，最后一个 '-' 后面才是第几段
        val heads = entries.mapNotNull { headOf(it.name)?.first }.toSet()
        // 对方发来超过 7 天一直没有接收：清掉网盘上的文件、待接收卡片，并告诉发送方
        val expired = entries.filter { e -> headOf(e.name) != null && e.modified > 0L && now - e.modified > UNACCEPTED_MAX_AGE_MS }
            .mapNotNull { headOf(it.name)?.first }
            .filter { it !in processing && !live.containsKey(it) && !accepted.contains(it) }.toSet()
        val inlExpired = inlineDir().listFiles()
            ?.filter { it.lastModified() > 0L && now - it.lastModified() > UNACCEPTED_MAX_AGE_MS }
            ?.map { it.name.removeSuffix(".bin") }
            ?.filter { it !in processing && !live.containsKey(it) && !accepted.contains(it) }.orEmpty()
        for (mid in expired + inlExpired) {
            inlineFile(mid).delete()
            val card = Hub.msgs.value.firstOrNull { it.id == mid && !it.outgoing }
            offers.remove(mid)
            if (card != null && (card.state == MsgState.OFFERED || card.state == MsgState.FAILED)) {
                Hub.patch(mid) { it.copy(state = MsgState.CANCELED, error = "对方发来超过 7 天没有接收，已自动清除") }
            }
            keepMids.remove(mid)
            markDone(mid)
            card?.peerId?.let { pid ->
                try {
                    sendNotify(pid, JSONObject().put("mid", mid).put("cancel", true).put("expired", true))
                } catch (x: Exception) {
                    Hub.log("Relay", x)
                }
            }
        }
        var n = 0
        var bytes = 0L
        for (e in entries) {
            val mid = midOf(e.name) ?: continue
            if (processing.contains(mid) || live.containsKey(mid)) continue
            val done = doneMids.contains(mid)
            val orphan = e.name.endsWith(".prt") && mid !in heads && e.modified > 0L && now - e.modified >= minAgeMs
            if (!done && !orphan) continue
            DavQuota.awaitRequests(1)   // 请求次数快满就等一等，别因为清理把额度用光
            quietDelete(c, "${Store.deviceId}/${e.name}")
            n++
            bytes += e.size
        }
        return Pair(n, bytes)
    }

    /** 设置里的“清理网盘残留”：马上清一遍，返回给用户看的一句话 */
    suspend fun cleanupNow(): String = withContext(Dispatchers.IO) {
        val c = Store.dav ?: throw IOException("${Store.davName}的地址、账号、密码没有填全")
        val entries = WebDav.listEntries(c, Store.deviceId)
        val (n, bytes) = cleanupRemnants(c, entries, 10L * 60 * 1000)
        if (n == 0) {
            "没有需要清理的残留（本机文件夹里现在有 ${entries.size} 个文件，共 ${fmtSize(entries.sumOf { it.size })}）"
        } else {
            "已清理 $n 个残留文件，释放约 ${fmtSize(bytes)}"
        }
    }

    private suspend fun deleteParts(c: DavConfig, mid: String, n: Int) {
        for (i in 1 until n) {
            DavQuota.awaitRequests(1)   // 请求次数快满就等一等再删，别因为清理把额度用光
            quietDelete(c, "${Store.deviceId}/$mid-$i.prt")
        }
    }
}
