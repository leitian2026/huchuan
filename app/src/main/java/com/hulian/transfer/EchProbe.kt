package com.hulian.transfer

import android.os.Build

/**
 * ECH 能力检测（只读，不改任何连接行为）：看这台手机上有没有能用的 ECH 接口。
 * 1. 系统自带：Android 17（API 37）起才有 android.net.ssl.SSLSockets.setEchConfigList
 * 2. 自带 Conscrypt（org.conscrypt:conscrypt-android:2.7.0）：发布说明里写了支持 ECH，但 Java 层接口的名字和参数
 *    我没法在没有联网下载的环境里确认，所以用反射把名字里带 Ech / ECH 的方法列出来，再按真实接口写正式代码。
 * 全程用反射，所以即使接口不存在也不会崩溃。
 */
object EchProbe {
    class Report(val verdict: String, val detail: String)

    private fun exists(name: String): Boolean = try { Class.forName(name); true } catch (e: Throwable) { false }

    private val echName = Regex("Ech|ECH")   // 区分大小写：不会匹配 check / fetch 这类词

    fun run(): Report {
        val d = StringBuilder()
        d.append("Android API ").append(Build.VERSION.SDK_INT).append('\n')

        val platform = Build.VERSION.SDK_INT >= 37 && exists("android.net.ssl.SSLSockets")
        d.append("系统自带 ECH 接口（Android 17 起）：").append(if (platform) "有" else "没有").append('\n')

        var hasConscrypt = false
        val found = ArrayList<String>()
        try {
            val c = Class.forName("org.conscrypt.Conscrypt")
            hasConscrypt = true
            d.append("Conscrypt：已打进安装包\n")
            try {
                val avail = c.getMethod("isAvailable").invoke(null)
                d.append("Conscrypt.isAvailable() = ").append(avail).append('\n')
            } catch (e: Throwable) {
                d.append("Conscrypt.isAvailable() 调用失败：").append(e.javaClass.simpleName).append('\n')
            }
            c.methods.filter { echName.containsMatchIn(it.name) }.forEach { found.add("Conscrypt." + it.toString()) }
            c.classes.filter { echName.containsMatchIn(it.simpleName) }.forEach { k ->
                found.add("类 " + k.name)
                k.methods.filter { echName.containsMatchIn(it.name) }.forEach { found.add("  " + it.toString()) }
            }
        } catch (e: Throwable) {
            d.append("Conscrypt：没有（").append(e.javaClass.simpleName).append("）\n")
        }

        d.append('\n').append("名字里带 Ech / ECH 的接口：\n")
        if (found.isEmpty()) d.append("（没找到）\n") else found.sorted().forEach { d.append(it).append('\n') }

        val verdict = when {
            platform -> "这台手机的系统自带 ECH（Android 17+）"
            hasConscrypt && found.isNotEmpty() -> "自带的 Conscrypt 里找到了 ECH 相关接口，详情里是完整列表，把它发给开发者"
            hasConscrypt -> "Conscrypt 在，但没找到 ECH 相关接口（可能是 Java 层没公开，或被压缩工具去掉了）"
            else -> "没有 ECH 可用：系统不支持，也没有 Conscrypt"
        }
        return Report(verdict, d.toString().trimEnd())
    }
}
