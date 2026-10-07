package com.hulian.transfer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class AppEntry(
    val pkg: String,
    val label: String,
    val version: String,
    val paths: List<String>,
    val system: Boolean
) {
    val size: Long = paths.sumOf { File(it).length() }
}

class Prepared(val name: String, val size: Long, val open: () -> InputStream, val cleanup: () -> Unit)

object Apps {
    fun list(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        return pm.getInstalledPackages(0).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val paths = ArrayList<String>()
            paths.add(ai.sourceDir)
            ai.splitSourceDirs?.let { paths.addAll(it) }
            val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            AppEntry(pi.packageName, ai.loadLabel(pm).toString(), pi.versionName ?: "", paths, isSys)
        }.sortedBy { it.label.lowercase() }
    }

    fun find(ctx: Context, pkg: String): AppEntry? = list(ctx).firstOrNull { it.pkg == pkg }

    fun icon(ctx: Context, pkg: String): ImageBitmap? = try {
        ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
    } catch (e: Exception) {
        Hub.log("读取应用图标 $pkg", e)
        null
    }

    /** 单个 apk 直接发；有拆分包(split APK)时打成 .apks（zip）一起发 */
    fun prepare(ctx: Context, e: AppEntry): Prepared {
        val base = Saver.sanitize("${e.label}_${e.version}")
        if (e.paths.size == 1) {
            val f = File(e.paths[0])
            return Prepared("$base.apk", f.length(), { FileInputStream(f) }, {})
        }
        val tmp = File(ctx.cacheDir, "pack_${System.nanoTime()}.apks")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { z ->
            z.setLevel(Deflater.NO_COMPRESSION)
            for (p in e.paths) {
                val f = File(p)
                z.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { it.copyTo(z, 256 * 1024) }
                z.closeEntry()
            }
        }
        return Prepared("$base.apks", tmp.length(), { FileInputStream(tmp) }, { tmp.delete() })
    }
}

object Installer {
    const val ACTION = "com.hulian.transfer.INSTALL_RESULT"

    fun install(ctx: Context, uri: Uri, fileName: String) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Hub.toast("请先允许本应用\u201c安装未知应用\u201d，返回后再点一次")
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        Hub.scope.launch {
            try {
                val pi = ctx.packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { session ->
                    val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取安装包")
                    if (fileName.endsWith(".apks", true)) {
                        var idx = 0
                        ZipInputStream(BufferedInputStream(input)).use { z ->
                            var e = z.nextEntry
                            while (e != null) {
                                if (!e.isDirectory && e.name.endsWith(".apk", true)) {
                                    session.openWrite("${idx++}_${e.name.substringAfterLast('/')}", 0, -1).use { out ->
                                        z.copyTo(out, 256 * 1024)
                                        session.fsync(out)
                                    }
                                }
                                e = z.nextEntry
                            }
                        }
                    } else {
                        input.use { ins ->
                            session.openWrite("base.apk", 0, -1).use { out ->
                                ins.copyTo(out, 256 * 1024)
                                session.fsync(out)
                            }
                        }
                    }
                    val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION)
                    val pend = PendingIntent.getBroadcast(
                        ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    session.commit(pend.intentSender)
                }
            } catch (e: Exception) {
                Hub.report("安装失败", e, "安装包：$fileName")
            }
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val c = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                c?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Hub.toast("安装完成")
            else -> Hub.fail(
                "安装失败", i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "系统没有给出原因",
                "安装状态码：" + status
            )
        }
    }
}
