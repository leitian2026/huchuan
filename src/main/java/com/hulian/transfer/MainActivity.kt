package com.hulian.transfer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 状态栏透明，让顶部渐变条一直延伸到屏幕最上面，颜色完全一致
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent { HulianTheme { AppRoot() } }
        // 前台服务不依赖通知权限，直接启动；权限在首次使用时由界面统一说明并申请
        ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java))
        if (savedInstanceState == null) readShare(intent)?.let { Hub.pendingShare.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShare(intent)?.let { Hub.pendingShare.value = it }
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
