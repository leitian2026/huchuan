package com.hulian.transfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 收到文字/文件/应用时的状态栏通知。应用正在前台显示时不打扰（界面里有未读数）。 */
object Notifier {
    private const val CH = "msg"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "新消息", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "收到文字、文件或应用时提醒"
            nm.createNotificationChannel(ch)
        }
    }

    /** 每个聊天对象一条通知（新消息会更新同一条）；避开前台服务通知用的 id 1 */
    private fun nid(peerId: String): Int = 1000 + (peerId.hashCode() and 0x0fffffff) % 100000

    fun message(peerId: String, text: String) {
        if (Hub.appVisible) return
        val ctx = Hub.app
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            ensureChannel(ctx)
            val name = Hub.peers.value[peerId]?.name?.ifEmpty { null } ?: "互传"
            val unread = Hub.msgs.value.count { it.peerId == peerId && !it.outgoing && !it.read }
            val title = if (unread > 1) name + "（" + unread + " 条新消息）" else name
            val open = Intent(ctx, MainActivity::class.java)
                .putExtra("peer", peerId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                ctx, nid(peerId), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(500)))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(ctx).notify(nid(peerId), n)
        } catch (_: Exception) {
        }
    }

    /** 打开了这个聊天，就把它的通知清掉 */
    fun cancel(peerId: String) {
        try {
            NotificationManagerCompat.from(Hub.app).cancel(nid(peerId))
        } catch (_: Exception) {
        }
    }
}
