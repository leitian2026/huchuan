package com.hulian.transfer

import android.app.Application
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 程序崩溃时先把原因写到文件，下次打开时弹出来（崩溃当时没有机会显示窗口）
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                File(filesDir, "crash.txt").writeText("$time 线程 ${t.name}\n" + android.util.Log.getStackTraceString(e))
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
        Hub.init(this)
        val crash = File(filesDir, "crash.txt")
        if (crash.exists()) {
            val txt = try { crash.readText() } catch (e: Exception) { "读取崩溃记录失败：" + friendlyError(e) }
            try { crash.delete() } catch (_: Exception) {}
            Hub.fail("上次程序异常退出", "互传上次因为下面的错误退出了。点“复制”把详细信息发给开发者，可以帮助修复这个问题", txt.take(3500))
        }
    }
}
