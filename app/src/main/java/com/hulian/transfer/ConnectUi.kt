package com.hulian.transfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 扫码入口：返回一个“点击即启动扫码”的函数，扫到的结果自动处理 */
@Composable
fun rememberScanner(onOpenChat: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { Pairing.handle(ctx, it, onOpenChat) }
    }
    return {
        launcher.launch(
            ScanOptions().setPrompt("扫描对方的互传二维码").setBeepEnabled(false).setOrientationLocked(false)
        )
    }
}

// ---------------- 添加设备：只剩两个按钮 ----------------

@Composable
fun ConnectScreen(onBack: () -> Unit, onMyQr: () -> Unit, onOpenChat: (String) -> Unit) {
    val scan = rememberScanner(onOpenChat)
    Column(Modifier.fillMaxSize()) {
        TopBar("添加设备", onBack)
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "同一个 Wi-Fi 下，两台手机打开互传会自动出现在“设备”里，不用扫码。\n\n" +
                    "没有 Wi-Fi，或一直找不到设备时：一台点“我的二维码”，另一台点“扫一扫”。",
                color = Gray, fontSize = 14.sp
            )
            Button(onClick = scan, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("扫一扫") }
            OutlinedButton(onClick = onMyQr, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("我的二维码") }
        }
    }
}

// ---------------- 我的二维码：自动判断用 Wi-Fi 还是热点 ----------------

@Composable
fun MyQrScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val hsPayload by HotspotHost.payload.collectAsState()
    val hsErr by HotspotHost.error.collectAsState()
    var lan by remember { mutableStateOf<String?>(null) }
    val startHello = remember { Hub.helloCount }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hotspotCoreGranted(ctx)) HotspotHost.start(ctx)
        else HotspotHost.error.value = "没有授予创建热点所需的权限：" + hotspotPermName() +
            "。请点下方\u201c去设置\u201d授权；也可以不授权，自己在系统里打开\u201c个人热点\u201d，本页会自动显示二维码"
    }
    fun lanPayload(ip: String): String = JSONObject().put("t", "hl").put("ip", ip).put("id", Store.deviceId)
        .put("name", Store.deviceName).put("port", Hub.port).put("k", Hub.newPairToken()).put("fp", Identity.fp).toString()
    // 没有 Wi-Fi 时：先补齐权限，再创建临时热点
    fun startHotspot() {
        HotspotHost.error.value = null
        val miss = hotspotPermsMissing(ctx)
        if (miss.isEmpty()) HotspotHost.start(ctx) else permLauncher.launch(miss.toTypedArray())
    }
    LaunchedEffect(Unit) {
        val ip = Net.wifiIp()
        if (HotspotHost.isUp()) {
            // 本 app 的热点已经开着（自动打开的）：不重开，只换新的一次性口令。
            // 注意要放在 Wi-Fi 判断前面：热点开着时热点网卡也会被当成"局域网"
            HotspotHost.refreshPayload()
        } else if (HotspotHost.starting) {
            // 热点正在创建，等它出结果即可
        } else if (ip != null) {
            // 已连 Wi-Fi：直接显示局域网二维码
            lan = lanPayload(ip)
        } else {
            startHotspot()
        }
    }
    // 热点创建失败 / 没有权限时：每 1.5 秒看一下用户是不是已经手动打开了系统热点（或连上了 Wi-Fi），有了就自动显示二维码
    LaunchedEffect(lan, hsPayload, hsErr) {
        while (lan == null && hsPayload == null && hsErr != null) {
            delay(1500)
            val ip = Net.wifiIp()
            if (ip != null) {
                lan = lanPayload(ip)
                HotspotHost.error.value = null
            }
        }
    }
    // 离开本页时，如果还没有人连上，就关掉热点；已经连上的要保留，否则传输会中断
    DisposableEffect(Unit) {
        onDispose { if (Hub.helloCount == startHello && !AutoLink.running) HotspotHost.stop() }
    }

    val payload = lan ?: hsPayload
    Column(Modifier.fillMaxSize()) {
        TopBar("我的二维码", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (payload != null) {
                val img = remember(payload) { qrBitmap(payload) }
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                    Image(img, null, Modifier.padding(16.dp).size(260.dp))
                }
                Text(
                    if (lan != null) "让对方打开互传，点“扫一扫”扫这个码\n（当前在 Wi-Fi 下，局域网内直连）"
                    else "让对方打开互传，点“扫一扫”扫这个码\n（已创建临时热点，不耗流量；对方会暂时离开原来的 Wi-Fi）",
                    color = Gray, fontSize = 14.sp, textAlign = TextAlign.Center
                )
                Text("对方扫码后，两台手机会显示同一个验证码，核对一致再点\u201c同意\u201d。二维码 15 分钟内有效，只能用一次", color = Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
            } else if (hsErr == null) {
                CircularProgressIndicator()
                Text("正在准备…", color = Gray, fontSize = 14.sp)
            }
            hsErr?.let {
                Text(it, color = Color(0xFFE5484D), fontSize = 13.sp, textAlign = TextAlign.Center)
                Button(onClick = { startHotspot() }) { Text("重试") }
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:" + ctx.packageName))
                    )
                }) { Text("去设置") }
                OutlinedButton(onClick = {
                    try { ctx.startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {}
                }) { Text("手动打开系统热点") }
                Text(
                    "不想授权也可以：在系统里打开\u201c个人热点\u201d，让对方连上它。本页检测到后会自动显示二维码，对方扫码即可（不需要任何额外权限）",
                    color = Gray, fontSize = 12.sp, textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---------------- 系统“分享”到互传：选设备 ----------------

@Composable
fun ShareScreen(share: Share, onDone: (String?) -> Unit) {
    val peers by Hub.peers.collectAsState()
    val list = remember(peers) {
        peers.values.sortedWith(compareByDescending<Peer> { it.online }.thenByDescending { it.lastSeen })
    }
    val what = if (share.uris.isNotEmpty()) "${share.uris.size} 个文件" else "文字"
    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar("发送 $what 到…", { onDone(null) })
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有设备\n请先在互传里添加设备", color = Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            share.uris.forEach { Hub.sendFile(p.id, it) }
                            share.text?.let { Hub.sendText(p.id, it) }
                            onDone(p.id)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(p.name, p.online, 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 17.sp)
                            Text(if (!p.paired) "[未配对]" else if (p.online) "[在线]" else "[离线]", fontSize = 13.sp, color = Gray)
                        }
                    }
                    HorizontalDivider(color = Color(0xFFF1F2F5))
                }
            }
        }
    }
}
