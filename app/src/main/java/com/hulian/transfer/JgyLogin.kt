package com.hulian.transfer

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val JGY_HOME = "https://www.jianguoyun.com/d/home"
private const val JGY_SITE = "https://www.jianguoyun.com/"

/** 读内置网页里坚果云的登录 Cookie（系统的 CookieManager 连网页脚本读不到的那种也能读到）；没有返回空串 */
private fun readJgyCookie(): String = CookieManager.getInstance().getCookie(JGY_SITE) ?: ""

/** 取完就把内置网页里的登录状态清掉：登录凭证只留一份（加密存在本机），不在 WebView 里再留一份明文 */
private fun clearWebLogin() {
    val cm = CookieManager.getInstance()
    cm.removeAllCookies(null)
    cm.flush()
    WebStorage.getInstance().deleteAllData()
}

/**
 * 在 App 里打开坚果云官方登录页，用户自己登录（验证码、短信验证都在官方页面里完成，密码不经过 App 的任何输入框）。
 * 登录成功（页面里请求账户信息能拿到流量数据）就自动取回 Cookie 并关闭；也可以点“我已登录”手动取。
 * onClose：取到的 Cookie；用户取消返回 null。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JgyLoginDialog(onClose: (String?) -> Unit) {
    val holder = remember { arrayOfNulls<WebView>(1) }
    val finished = remember { booleanArrayOf(false) }

    fun finish(cookie: String?) {
        if (finished[0]) return
        finished[0] = true
        clearWebLogin()   // cookie 已经读出来了，再清
        onClose(cookie)
    }

    DisposableEffect(Unit) {
        onDispose {
            holder[0]?.destroy()
            holder[0] = null
        }
    }

    Dialog(
        onDismissRequest = { finish(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { finish(null) }) { Text("取消") }
                    Text("登录坚果云", fontSize = 16.sp)
                    TextButton(onClick = {
                        val ck = readJgyCookie()
                        if (ck.isEmpty()) Hub.toast("还没有登录") else finish(ck)
                    }) { Text("我已登录") }
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            holder[0] = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = settings.userAgentString.replace("; wv", "")
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    // 每个坚果云页面加载完，在页面里同源请求一次账户信息：拿到流量数据就说明已经登录成功
                                    if (view == null || url == null || !url.startsWith(JGY_SITE)) return
                                    view.evaluateJavascript(
                                        "(function(){try{var x=new XMLHttpRequest();x.open('GET','/d/ajax/userop/getUserInfo',false);" +
                                            "x.send();return x.responseText.indexOf('usedUpRate')>=0;}catch(e){return false;}})()"
                                    ) { r ->
                                        if (r == "true") {
                                            val ck = readJgyCookie()
                                            if (ck.isNotEmpty()) finish(ck)
                                        }
                                    }
                                }
                            }
                            loadUrl(JGY_HOME)
                        }
                    }
                )
            }
        }
    }
}
