package com.hulian.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户点了“取消”（或对方取消了）。继承 IOException 而不是协程的 CancellationException：
 * 并行上传 / 下载时，普通异常会让整组任务一起失败；CancellationException 只会悄悄结束其中一个，其余的还会接着跑
 */
class UserCancelException : IOException("已取消")

/** 一条正在传输的消息的控制柄：界面上的暂停 / 继续 / 取消按钮通过它通知正在传的代码 */
class TransferCtl {
    companion object {
        const val RUN = 0
        const val PAUSED = 1
        const val CANCELED = 2
    }

    val state = MutableStateFlow(RUN)

    /** 取消时除了中断网盘请求以外还要做的事（直连关连接、唤醒等待中的下载…） */
    @Volatile var onCancel: (() -> Unit)? = null

    /** 能不能暂停：只有远程中转的大文件（分段上传 / 下载）可以；直连的连接超过 60 秒没数据就会断，不能暂停 */
    @Volatile var canPause = false

    /** 取消是对方发起的（界面上显示“对方取消了”） */
    @Volatile var byPeer = false

    val cancelled: Boolean get() = state.value == CANCELED
    val paused: Boolean get() = state.value == PAUSED

    fun cancel() {
        if (state.value == CANCELED) return
        state.value = CANCELED   // 同时唤醒所有在“暂停”里等着的代码，它们醒来会发现已取消
        WebDav.cancelCalls(this)   // 这条传输的网盘请求都带着本对象做标记：正在进行的马上中断
        try { onCancel?.invoke() } catch (e: Exception) { Hub.log("Transfers", e) }
    }

    fun cancelByPeer() {
        byPeer = true
        cancel()
    }

    fun setPaused(p: Boolean) {
        if (!canPause) return
        if (p) state.compareAndSet(RUN, PAUSED) else state.compareAndSet(PAUSED, RUN)
    }

    /** 已取消就抛 UserCancelException */
    fun check() {
        if (cancelled) throw UserCancelException()
    }

    /** 暂停中就一直等到继续（等状态变化，不轮询）；已取消就抛 UserCancelException */
    suspend fun gate() {
        state.first { it != PAUSED }
        check()
    }
}

object Transfers {
    private val map = ConcurrentHashMap<String, TransferCtl>()

    /** 开始（或重新开始）一条消息的传输：换一个全新的控制柄 */
    fun begin(id: String): TransferCtl = TransferCtl().also { map[id] = it }

    fun get(id: String): TransferCtl? = map[id]

    fun end(id: String, ctl: TransferCtl) {
        map.remove(id, ctl)
    }
}
