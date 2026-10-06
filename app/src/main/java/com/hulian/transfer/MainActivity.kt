package com.hulian.transfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var askedNotif = false

    override fun onStart() {
        super.onStart()
        Hub.appVisible = true
        // 回到前台时，正在看的聊天里在后台期间收到的消息算已读，并清掉对应通知
        Hub.openPeer?.let { Hub.markRead(it) }
        // Android 13+：之前拒绝过通知权限的话，每次启动再请求一次（系统若已永久拒绝则不会弹窗）
        if (Build.VERSION.SDK_INT >= 33 && Store.permsAsked && !askedNotif &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 打开 app：按上次的角色自动开热点 / 连上次的热点（没有角色记录就什么都不做）
        AutoLink.start(this)
    }

    override fun onStop() {
        Hub.appVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        // 真正退出（返回键退出 / 被关闭）才关；旋转屏幕等重建不算
        if (isFinishing) AutoLink.exit()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 状态栏透明，让顶部渐变条一直延伸到屏幕最上面，颜色完全一致
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        AutoLink.init()
        setContent { HulianTheme { AppRoot() } }
        // 前台服务不依赖通知权限，直接启动；权限在首次使用时由界面统一说明并申请
        ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java))
        if (savedInstanceState == null) {
            readShare(intent)?.let { Hub.pendingShare.value = it }
            intent.getStringExtra("peer")?.let { Hub.openChatRequest.value = it }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShare(intent)?.let { Hub.pendingShare.value = it }
        intent.getStringExtra("peer")?.let { Hub.openChatRequest.value = it }
    }

    @Suppress("DEPRECATION")
    private fun readShare(i: Intent): Share? {
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) uris.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (l != null) uris.addAll(l)
            }
            else -> return null
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text.isNullOrBlank()) return null
        return Share(uris, if (uris.isEmpty()) text else null)
    }
}
