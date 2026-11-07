package com.hulian.transfer

import org.json.JSONObject
import java.io.IOException

/** 收到内容后的处理（记入聊天、存文件、通知）。直连（Net）和远程中转（Relay）共用，行为完全一致 */
object Inbox {
    class FileJob(val id: String, val size: Long, val title: String, val isApp: Boolean, val saved: Saver.Out)

    /** cid：发送方那边这条消息的 id；重复收到（重发、补发）或对方已经撤回了的不再加一条 */
    fun addText(pid: String, text: String, cid: String = "", quote: String = "") {
        if (cid.isNotEmpty()) {
            if (Store.isRecalled(cid)) return
            if (Hub.msgs.value.any { it.rid == cid && it.peerId == pid }) return
        }
        Hub.addMsg(Msg(newId(), pid, false, Kind.TEXT, now(), text = text, read = Hub.isViewing(pid), rid = cid, quote = quote))
        Notifier.message(pid, text)
    }

    /**
     * 开始接收文件：检查大小和存储空间，建好聊天里的消息和目标文件。
     * 不能接收时抛 IOException，消息就是原因（直连时会原样回给发送方）
     */
    /** reuse：聊天里已经有这条消息（点“接收”前的待接收卡片）就原位改成“接收中”，不删了重建，列表不会跳 */
    fun beginFile(pid: String, h: JSONObject, reuse: String? = null): FileJob {
        val size = h.getLong("size")
        if (size < 0) throw IOException("文件大小无效")
        val free = Space.available()
        if (free >= 0 && size + Space.RESERVE > free) {
            Hub.toast("存储空间不足，已拒收文件（" + fmtSize(size) + "）")
            throw IOException("对方存储空间不足，需要 " + fmtSize(size))
        }
        val fname = Saver.sanitize(Space.safeName(h.getString("fname")))
        val isApp = h.optString("kind") == "app"
        val inPlace = reuse != null && Hub.msgs.value.any { it.id == reuse }
        val id = if (inPlace) reuse!! else newId()
        val title = if (isApp) h.optString("app").ifEmpty { fname } else fname
        if (inPlace) {
            Hub.patch(id) {
                it.copy(
                    name = title, file = fname, size = size, state = MsgState.RECEIVING, done = 0L, error = "",
                    pkg = h.optString("pkg"), ver = h.optString("ver"), rid = h.optString("cid"), quote = h.optString("rq")
                )
            }
        } else {
            Hub.addMsg(
                Msg(
                    id, pid, false, if (isApp) Kind.APP else Kind.FILE, now(),
                    name = title, file = fname, size = size, state = MsgState.RECEIVING,
                    pkg = h.optString("pkg"), ver = h.optString("ver"), read = Hub.isViewing(pid),
                    rid = h.optString("cid"), quote = h.optString("rq")
                )
            )
        }
        val saved: Saver.Out = try {
            Saver.create(Hub.app, fname)
        } catch (e: Exception) {
            Hub.log("创建接收文件", e)
            Hub.patch(id) { it.copy(state = MsgState.FAILED, error = "无法保存文件：" + friendlyError(e)) }
            throw IOException("对方无法保存文件", e)
        }
        return FileJob(id, size, title, isApp, saved)
    }

    /** 远程发来文件：先在聊天里放一张“待接收”的卡片，用户点“接收”才开始下载。卡片的 id 就是消息编号 */
    fun offerFile(pid: String, mid: String, h: JSONObject) {
        val size = h.getLong("size")
        if (size < 0) throw IOException("文件大小无效")
        val fname = Saver.sanitize(Space.safeName(h.getString("fname")))
        val isApp = h.optString("kind") == "app"
        val title = if (isApp) h.optString("app").ifEmpty { fname } else fname
        Hub.removeMsg(mid)   // 同一条重复扫到：不留两张卡
        Hub.addMsg(
            Msg(
                mid, pid, false, if (isApp) Kind.APP else Kind.FILE, now(),
                name = title, file = fname, size = size, state = MsgState.OFFERED,
                pkg = h.optString("pkg"), ver = h.optString("ver"), read = Hub.isViewing(pid),
                rid = h.optString("cid"), quote = h.optString("rq")
            )
        )
        Notifier.message(pid, (if (isApp) "发来应用：" else "发来文件：") + title + "（点开选择是否接收）")
    }

    fun progress(j: FileJob, got: Long) {
        Hub.patch(j.id) { it.copy(done = got) }
    }

    fun finishFile(pid: String, j: FileJob) {
        val u = j.saved.uri.toString()
        Hub.patch(j.id) { it.copy(state = MsgState.DONE, done = j.size, uri = u) }
        Notifier.message(pid, (if (j.isApp) "收到应用：" else "收到文件：") + j.title)
    }

    /** 用户取消了接收（或对方取消了）：删掉写了一半的文件，消息标成“已取消” */
    fun cancelFile(j: FileJob) {
        Saver.delete(Hub.app, j.saved.uri)
        Hub.patch(j.id) { it.copy(state = MsgState.CANCELED, error = "") }
    }

    fun failFile(j: FileJob, e: Exception) {
        Saver.delete(Hub.app, j.saved.uri)
        Hub.log("接收文件", e)
        Hub.patch(j.id) { it.copy(state = MsgState.FAILED, error = "接收中断：" + friendlyError(e)) }
    }
}
