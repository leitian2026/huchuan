package com.hulian.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一的 root 入口：Wi-Fi 开关、定位开关都借它执行命令（su -c ...）。
 * - 所有 su 调用排队执行：第一次会弹出 root 授权窗口，不能同时弹出两个
 * - available 给界面显示用：null=还没检测完，true=有 root 且已授权，false=没有 root（或授权被拒绝）
 */
object Root {
    val available = MutableStateFlow<Boolean?>(null)

    private val lock = Mutex()
    private val checking = AtomicBoolean(false)

    private class Out(val code: Int, val text: String)

    /** 执行一条 su 命令。没有 su、启动失败、超时返回 null；否则返回退出码和输出 */
    private suspend fun exec(cmd: String, timeoutSec: Long): Out? = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            // 第一次会弹出 root 授权窗口，等久一点
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else {
                Out(p.exitValue(), p.inputStream.bufferedReader().readText())
            }
        } catch (e: Exception) {
            // 没有 su 时这里是 IOException，属于正常情况，不打扰用户
            Hub.log("Root", e)
            null
        }
    }

    /** 以 root 执行命令，退出码为 0 返回 true。成功说明本机确实有 root，顺便更新状态 */
    suspend fun run(cmd: String, timeoutSec: Long = 20): Boolean = lock.withLock {
        val r = exec(cmd, timeoutSec)
        val ok = r != null && r.code == 0
        if (ok) available.value = true
        ok
    }

    /**
     * 检测本机有没有 root：执行 `su -c id`，看到 uid=0 才算。
     * 平时只在启动时检测一次；force=true 用于用户点“没有root”标签重新检测
     */
    fun refresh(force: Boolean = false) {
        if (!force && available.value != null) return
        if (!checking.compareAndSet(false, true)) return
        Hub.scope.launch {
            try {
                val r = lock.withLock { exec("id", 30) }
                available.value = r != null && r.code == 0 && r.text.contains("uid=0")
            } finally {
                checking.set(false)
            }
        }
    }
}
