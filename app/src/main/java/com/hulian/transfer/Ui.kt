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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Blue1 = Color(0xFF5FD3FA)
val Blue2 = Color(0xFF4BA6F5)
val ChatBg = Color(0xFFEBEEF5)
val OutBubble = Color(0xFF4FB5F5)
val Gray = Color(0xFF8A8F99)

@Composable
fun HulianTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Blue2, secondary = Blue1, background = ChatBg, surface = Color.White),
        content = content
    )
}

sealed interface Screen {
    data object Home : Screen
    data object Devices : Screen
    data class Picker(val peerId: String) : Screen
    data object Connect : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot() {
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(stack.size > 1) { pop() }
    val cur by Hub.current.collectAsState()
    Box(Modifier.fillMaxSize().background(ChatBg).navigationBarsPadding()) {
        when (val s = stack.last()) {
            Screen.Home -> ChatScreen(cur, null, onPickApps = { push(Screen.Picker(it)) }) {
                HomeMenu(
                    onDevices = { push(Screen.Devices) },
                    onConnect = { push(Screen.Connect) },
                    onSettings = { push(Screen.Settings) }
                )
            }
            Screen.Devices -> DevicesScreen(onBack = { pop() }, onConnect = { push(Screen.Connect) })
            is Screen.Picker -> PickerScreen(s.peerId, onBack = { pop() })
            Screen.Connect -> ConnectScreen(onBack = { pop() }, onChat = { id -> Hub.setCurrent(id); stack = listOf(Screen.Home) })
            Screen.Settings -> SettingsScreen(onBack = { pop() })
        }
    }
}

// ---------------- 通用组件 ----------------

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    // 渐变背景先画，再加状态栏内边距：渐变会一直铺到屏幕最顶端，和状态栏融为一体
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Blue1, Blue2)))
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(title, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
fun Avatar(name: String, online: Boolean = true) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(if (online) Blue2 else Color(0xFFB8BEC8)),
        contentAlignment = Alignment.Center
    ) {
        Text(name.take(1).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

fun qrBitmap(text: String, size: Int = 640): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        px[y * size + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, size, 0, 0, size, size)
    return bmp.asImageBitmap()
}

// ---------------- 设备列表（切换聊天对象） ----------------

@Composable
fun HomeMenu(onDevices: () -> Unit, onConnect: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Menu, "菜单", tint = Color.White) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("切换设备") }, onClick = { open = false; onDevices() })
            DropdownMenuItem(text = { Text("连接设备（扫码 / 热点）") }, onClick = { open = false; onConnect() })
            DropdownMenuItem(text = { Text("重新搜索设备") }, onClick = { open = false; Hub.discovery?.restart() })
            DropdownMenuItem(text = { Text("设置") }, onClick = { open = false; onSettings() })
        }
    }
}

@Composable
fun DevicesScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val peers by Hub.peers.collectAsState()
    val msgs by Hub.msgs.collectAsState()
    val cur by Hub.current.collectAsState()
    val list = remember(peers) {
        peers.values.sortedWith(compareByDescending<Peer> { it.online }.thenByDescending { it.lastSeen })
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("选择设备", onBack) {
            IconButton(onClick = { Hub.discovery?.restart() }) { Icon(Icons.Default.Refresh, "刷新", tint = Color.White) }
        }
        Text(
            "本机：${Store.deviceName}", color = Gray, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("连接设备（扫码 / 热点）")
        }
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有设备\n两台手机连同一个 Wi-Fi 并打开本应用即可", color = Gray, fontSize = 15.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { p ->
                    val last = msgs.lastOrNull { it.peerId == p.id }
                    val preview = if (last == null) p.host else when (last.kind) {
                        Kind.TEXT -> last.text
                        Kind.FILE -> "[文件] ${last.name}"
                        Kind.APP -> "[应用] ${last.name}"
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (p.id == cur) Color(0xFFE3F3FE) else Color.White)
                            .clickable { Hub.setCurrent(p.id); onBack() }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(p.name, p.online)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(preview, fontSize = 13.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (p.online) "在线" else "离线", fontSize = 12.sp, color = if (p.online) Color(0xFF2EB872) else Gray)
                    }
                    HorizontalDivider(color = ChatBg)
                }
            }
        }
    }
}

// ---------------- 聊天 ----------------

@Composable
fun ChatScreen(
    peerId: String?,
    onBack: (() -> Unit)?,
    onPickApps: (String) -> Unit,
    menu: @Composable RowScope.() -> Unit = {}
) {
    val ctx = LocalContext.current
    val peers by Hub.peers.collectAsState()
    val all by Hub.msgs.collectAsState()
    val peer = if (peerId == null) null else peers[peerId]
    val list = remember(all, peerId) {
        if (peerId == null) emptyList() else all.filter { it.peerId == peerId }.sortedBy { it.time }
    }
    val ls = rememberLazyListState()
    LaunchedEffect(list.size) { if (list.isNotEmpty()) ls.scrollToItem(list.size - 1) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (peerId != null) uris.forEach {
            try { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Hub.sendFile(peerId, it)
        }
    }

    fun needPeer(): Boolean {
        if (peerId == null) { Hub.toast("请先连接设备（右上角菜单 → 连接设备）"); return false }
        return true
    }

    fun onMsgClick(m: Msg) {
        if (m.outgoing && m.state == MsgState.FAILED) {
            Hub.retry(m)
        } else if (!m.outgoing && m.state == MsgState.DONE && m.kind != Kind.TEXT && m.uri.isNotEmpty()) {
            val n = m.file.ifEmpty { m.name }
            if (n.endsWith(".apk", true) || n.endsWith(".apks", true)) Installer.install(ctx, Uri.parse(m.uri), n)
            else Saver.open(ctx, Uri.parse(m.uri), n)
        }
    }

    val title = when {
        peer == null -> "互传"
        peer.online -> peer.name
        else -> peer.name + "（离线）"
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(title, onBack, menu)
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (peerId == null) "还没有连接设备\n点右上角菜单 → 连接设备" else "还没有传输记录",
                    color = Gray, fontSize = 14.sp, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = ls, contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(list, key = { _, m -> m.id }) { i, m ->
                    Column {
                        if (i == 0 || m.time - list[i - 1].time > 300_000) {
                            Text(
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(m.time)),
                                color = Gray, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        MsgRow(m, peer?.name ?: "?") { onMsgClick(m) }
                    }
                }
            }
        }
        var text by remember { mutableStateOf("") }
        var menuOpen by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().background(Color(0xFFF7F8FA)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Add, "更多", tint = Gray) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("发送文件") }, onClick = { menuOpen = false; if (needPeer()) picker.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("发送应用") }, onClick = { menuOpen = false; if (needPeer()) peerId?.let(onPickApps) })
                }
            }
            TextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(22.dp), maxLines = 4,
                placeholder = { Text("输入消息") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            IconButton(onClick = {
                if (text.isNotBlank() && needPeer()) { peerId?.let { Hub.sendText(it, text.trim()) }; text = "" }
            }) { Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = Blue2) }
        }
    }
}

@Composable
fun MsgRow(m: Msg, peerName: String, onClick: () -> Unit) {
    val out = m.outgoing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = if (out) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!out) { Avatar(peerName); Spacer(Modifier.width(8.dp)) }
        Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 300.dp)) {
            if (m.kind == Kind.TEXT) {
                Surface(shape = RoundedCornerShape(18.dp), color = if (out) OutBubble else Color.White) {
                    SelectionContainer {
                        Text(
                            m.text, color = if (out) Color.White else Color(0xFF111111), fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            } else {
                FileCard(m, onClick)
            }
            if (m.state == MsgState.FAILED && out) {
                Text(
                    "发送失败：${m.error}（点击重试）", color = Color(0xFFE5484D), fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onClick).padding(top = 2.dp)
                )
            }
        }
        if (out) { Spacer(Modifier.width(8.dp)); Avatar("我") }
    }
}

@Composable
fun FileCard(m: Msg, onClick: () -> Unit) {
    val pct = if (m.size > 0) (m.done.toFloat() / m.size).coerceIn(0f, 1f) else 0f
    val status = when (m.state) {
        MsgState.SENDING -> "发送中 ${(pct * 100).toInt()}%"
        MsgState.RECEIVING -> "接收中 ${(pct * 100).toInt()}%"
        MsgState.DONE -> if (m.outgoing) "已发送" else if (m.kind == Kind.APP) "已接收，点击安装" else "已接收，点击打开"
        MsgState.FAILED -> "失败"
    }
    val isApk = (m.file.ifEmpty { m.name }).let { it.endsWith(".apk", true) || it.endsWith(".apks", true) }
    val badge = if (m.kind == Kind.APP || isApk) "APK" else m.name.substringAfterLast('.', "FILE").take(4).uppercase()
    val title = if (m.kind == Kind.APP) "${m.name}  ${m.ver}" else m.name
    Surface(
        shape = RoundedCornerShape(18.dp), color = if (m.outgoing) Color(0xFFD9F0FD) else Color.White,
        modifier = Modifier.width(280.dp).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text("${fmtSize(m.size)} / $status", fontSize = 12.sp, color = Gray)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFF1F3F6)),
                    contentAlignment = Alignment.Center
                ) { Text(badge, fontSize = 12.sp, color = Gray, fontWeight = FontWeight.Bold) }
            }
            if (m.state == MsgState.SENDING || m.state == MsgState.RECEIVING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------- 选择已安装应用 ----------------

@Composable
fun PickerScreen(peerId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<AppEntry>?>(null) }
    var q by remember { mutableStateOf("") }
    var showSys by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { Apps.list(ctx) } }
    val shown = remember(all, q, showSys) {
        (all ?: emptyList()).filter { (showSys || !it.system) && (q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true)) }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("选择要发送的应用", onBack)
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = q, onValueChange = { q = it }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("搜索应用名或包名") }, shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(8.dp))
            Text("系统应用", fontSize = 12.sp, color = Gray)
            Switch(checked = showSys, onCheckedChange = { showSys = it })
        }
        if (all == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(shown, key = { it.pkg }) { e ->
                    val icon by produceState<ImageBitmap?>(null, e.pkg) {
                        value = withContext(Dispatchers.IO) { Apps.icon(ctx, e.pkg) }
                    }
                    val checked = e.pkg in sel
                    Row(
                        Modifier.fillMaxWidth().background(Color.White)
                            .clickable { if (checked) sel.remove(e.pkg) else sel.add(e.pkg) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(42.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${e.version}　${fmtSize(e.size)}${if (e.paths.size > 1) "　拆分包×${e.paths.size}" else ""}",
                                fontSize = 12.sp, color = Gray, maxLines = 1
                            )
                        }
                        Checkbox(checked = checked, onCheckedChange = { if (it) sel.add(e.pkg) else sel.remove(e.pkg) })
                    }
                    HorizontalDivider(color = ChatBg)
                }
            }
        }
        Button(
            onClick = {
                val picked = all?.filter { it.pkg in sel } ?: emptyList()
                picked.forEach { Hub.sendApp(peerId, it) }
                onBack()
            },
            enabled = sel.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) { Text("发送（${sel.size}）") }
    }
}

// ---------------- 连接：二维码 / 热点 / 扫码 ----------------

@Composable
fun ConnectScreen(onBack: () -> Unit, onChat: (String) -> Unit) {
    val ctx = LocalContext.current
    val hsPayload by HotspotHost.payload.collectAsState()
    val hsErr by HotspotHost.error.collectAsState()
    val joinStatus by HotspotJoin.status.collectAsState()
    val joined by HotspotJoin.connected.collectAsState()
    var lanQr by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) { Hub.hellos.collect { onChat(it) } }
    LaunchedEffect(joined) { joined?.let { HotspotJoin.connected.value = null; onChat(it) } }

    val wifiPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) HotspotHost.start(ctx) else HotspotHost.error.value = "需要授予\u201c附近设备/定位\u201d权限才能创建热点"
    }

    fun handleQr(text: String) {
        val j = try { JSONObject(text) } catch (e: Exception) { null }
        if (j == null || j.optString("t") != "hl") { Hub.toast("这不是互传的二维码"); return }
        try {
            if (j.has("ssid")) {
                HotspotJoin.join(ctx, j.getString("ssid"), j.getString("pwd"), j.getString("id"), j.optString("name"), j.getInt("port"))
            } else {
                val id = j.getString("id")
                Hub.upsertPeer(id, j.optString("name"), j.getString("ip"), j.getInt("port"))
                Hub.scope.launch { Hub.hello(id) }
                onChat(id)
            }
        } catch (e: Exception) {
            Hub.toast("二维码内容无效")
        }
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { handleQr(it) } }

    Column(Modifier.fillMaxSize()) {
        TopBar("连接设备", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("同一个 Wi-Fi", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("两台手机连同一个 Wi-Fi 并打开本应用，会自动出现在首页，不用扫码。如果一直找不到（路由器开了设备隔离），可以让对方扫你的二维码。", fontSize = 13.sp, color = Gray)
                    OutlinedButton(onClick = {
                        val ip = Net.localIps().firstOrNull()
                        if (ip == null) Hub.toast("没有找到本机局域网地址，请先连接 Wi-Fi")
                        else lanQr = qrBitmap(JSONObject().put("t", "hl").put("ip", ip).put("id", Store.deviceId)
                            .put("name", Store.deviceName).put("port", Hub.port).toString())
                    }) { Text("显示我的二维码") }
                    lanQr?.let { Image(it, null, Modifier.size(220.dp).align(Alignment.CenterHorizontally)) }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("没有 Wi-Fi", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("一方创建仅用于互传的热点并显示二维码，另一方扫码连接（不消耗流量，对方会暂时断开原来的 Wi-Fi）。", fontSize = 13.sp, color = Gray)
                    if (hsPayload == null) {
                        OutlinedButton(onClick = {
                            if (ContextCompat.checkSelfPermission(ctx, wifiPerm) == PackageManager.PERMISSION_GRANTED) HotspotHost.start(ctx)
                            else permLauncher.launch(wifiPerm)
                        }) { Text("创建热点并显示二维码") }
                    } else {
                        val img = remember(hsPayload) { qrBitmap(hsPayload!!) }
                        Image(img, null, Modifier.size(220.dp).align(Alignment.CenterHorizontally))
                        OutlinedButton(onClick = { HotspotHost.stop() }) { Text("停止热点") }
                    }
                    hsErr?.let { Text(it, color = Color(0xFFE5484D), fontSize = 12.sp) }
                }
            }
            Button(
                onClick = { scan.launch(ScanOptions().setPrompt("扫描对方的互传二维码").setBeepEnabled(false).setOrientationLocked(false)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("扫一扫") }
            if (joinStatus.isNotEmpty()) Text(joinStatus, fontSize = 13.sp, color = Gray)
        }
    }
}

// ---------------- 设置 ----------------

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Store.deviceName) }
    var dir by remember { mutableStateOf(Store.saveDir) }
    val dirLabel = remember(dir) {
        dir?.let {
            val d = DocumentFile.fromTreeUri(ctx, Uri.parse(it))
            if (d != null && d.canWrite()) d.name ?: "已选择" else "（所选文件夹不可用，请重新选择）"
        } ?: "默认：下载/互传"
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                Store.saveDir = uri.toString()
                dir = uri.toString()
            } catch (e: Exception) {
                Hub.toast("无法获得该文件夹的写入权限，请换一个（可新建子文件夹）")
            }
        }
    }
    DisposableEffect(Unit) { onDispose { Hub.discovery?.restart() } }
    val ver = remember {
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName}（${pi.longVersionCode}）"
        } catch (e: Exception) { "" }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("设置", onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("当前版本：$ver", fontSize = 13.sp, color = Gray)
            OutlinedTextField(
                value = name, onValueChange = { name = it; if (it.isNotBlank()) Store.deviceName = it.trim() },
                label = { Text("本机名称（对方看到的名字）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("接收文件保存位置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(dirLabel, fontSize = 14.sp)
                    Text("选一次即可，之后不会再询问。系统不允许直接选存储根目录或\u201c下载\u201d文件夹本身，请在里面新建一个子文件夹再选它。", fontSize = 12.sp, color = Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { treePicker.launch(null) }) { Text("选择文件夹") }
                        if (dir != null) OutlinedButton(onClick = { Store.saveDir = null; dir = null }) { Text("恢复默认") }
                    }
                }
            }
        }
    }
}
