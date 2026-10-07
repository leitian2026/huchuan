package com.hulian.transfer

import android.content.Context
import android.content.Intent
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

    /** 本机有 su（已 root），但本 app 没拿到授权（拒绝了 / 没点允许）。界面显示“已root 未授权”，点一下去 root 管理器里授权 */
    val denied = MutableStateFlow(false)

    /** 最近一次执行 su 时系统里根本没有 su 这个程序（没 root） */
    @Volatile private var suMissing = false

    /** 用户点了“去授权”：回到本 app 时重新检测一次 */
    @Volatile private var recheckOnReturn = false

    private val lock = Mutex()
    private val checking = AtomicBoolean(false)

    private class Out(val code: Int, val text: String)

    /** 执行一条 su 命令。没有 su、启动失败、超时返回 null；否则返回退出码和输出 */
    private suspend fun exec(cmd: String, timeoutSec: Long): Out? = withContext(Dispatchers.IO) {
        suMissing = false
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
            if (e is java.io.IOException) suMissing = true
            Hub.log("Root", e)
            null
        }
    }

    /** 以 root 执行命令，退出码为 0 返回 true。成功说明本机确实有 root，顺便更新状态 */
    suspend fun run(cmd: String, timeoutSec: Long = 20): Boolean = lock.withLock {
        val r = exec(cmd, timeoutSec)
        val ok = r != null && r.code == 0
        if (ok) {
            available.value = true
            denied.value = false
        }
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
                lock.withLock {
                    val r = exec("id", 30)
                    val ok = r != null && r.code == 0 && r.text.contains("uid=0")
                    denied.value = !ok && !suMissing   // 有 su 但没拿到 root：已 root 未授权
                    available.value = ok
                }
            } finally {
                checking.set(false)
            }
        }
    }

    /** 回到本 app 时是否需要重新检测（只在用户点了“去授权”之后才检测一次，避免反复弹授权窗） */
    fun consumeRecheck(): Boolean {
        val r = recheckOnReturn
        recheckOnReturn = false
        return r
    }

    /** 打开 root 管理器（Magisk / KernelSU / APatch / SuperSU 等），让用户给互传授权。找不到返回 false */
    fun openManager(ctx: Context): Boolean {
        val pkgs = listOf(
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk",
            "me.weishu.kernelsu", "me.bmax.apatch", "eu.chainfire.supersu", "com.thirdparty.superuser"
        )
        for (pkg in pkgs) {
            try {
                val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                recheckOnReturn = true
                return true
            } catch (e: Exception) {
                Hub.log("Root", e)
            }
        }
        return false
    }
}
