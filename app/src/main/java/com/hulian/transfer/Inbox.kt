package com.hulian.transfer

import org.json.JSONObject
import java.io.IOException

/** 收到内容后的处理（记入聊天、存文件、通知）。直连（Net）和远程中转（Relay）共用，行为完全一致 */
object Inbox {
    class FileJob(val id: String, val size: Long, val title: String, val isApp: Boolean, val saved: Saver.Out)

    fun addText(pid: String, text: String) {
        Hub.addMsg(Msg(newId(), pid, false, Kind.TEXT, now(), text = text, read = Hub.isViewing(pid)))
        Notifier.message(pid, text)
    }

    /**
     * 开始接收文件：检查大小和存储空间，建好聊天里的消息和目标文件。
     * 不能接收时抛 IOException，消息就是原因（直连时会原样回给发送方）
     */
    fun beginFile(pid: String, h: JSONObject): FileJob {
        val size = h.getLong("size")
        if (size < 0) throw IOException("文件大小无效")
        val free = Space.available()
        if (free >= 0 && size + Space.RESERVE > free) {
            Hub.toast("存储空间不足，已拒收文件（" + fmtSize(size) + "）")
            throw IOException("对方存储空间不足，需要 " + fmtSize(size))
        }
        val fname = Saver.sanitize(Space.safeName(h.getString("fname")))
        val isApp = h.optString("kind") == "app"
        val id = newId()
        val title = if (isApp) h.optString("app").ifEmpty { fname } else fname
        Hub.addMsg(
            Msg(
                id, pid, false, if (isApp) Kind.APP else Kind.FILE, now(),
                name = title, file = fname, size = size, state = MsgState.RECEIVING,
                pkg = h.optString("pkg"), ver = h.optString("ver"), read = Hub.isViewing(pid)
            )
        )
        val saved: Saver.Out = try {
            Saver.create(Hub.app, fname)
        } catch (e: Exception) {
            Hub.log("创建接收文件", e)
            Hub.patch(id) { it.copy(state = MsgState.FAILED, error = "无法保存文件：" + friendlyError(e)) }
            throw IOException("对方无法保存文件", e)
        }
        return FileJob(id, size, title, isApp, saved)
    }

    fun progress(j: FileJob, got: Long) {
        Hub.patch(j.id) { it.copy(done = got) }
    }

    fun finishFile(pid: String, j: FileJob) {
        val u = j.saved.uri.toString()
        Hub.patch(j.id) { it.copy(state = MsgState.DONE, done = j.size, uri = u) }
        Notifier.message(pid, (if (j.isApp) "收到应用：" else "收到文件：") + j.title)
    }

    fun failFile(j: FileJob, e: Exception) {
        Saver.delete(Hub.app, j.saved.uri)
        Hub.log("接收文件", e)
        Hub.patch(j.id) { it.copy(state = MsgState.FAILED, error = "接收中断：" + friendlyError(e)) }
    }
}
