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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Blue1 = Color(0xFF5FD3FA)
val Blue2 = Color(0xFF4BA6F5)
val ChatBg = Color(0xFFEBEEF5)
val ListBg = Color(0xFFF5F6FA)
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
    data class Chat(val peerId: String) : Screen
    data class Picker(val peerId: String) : Screen
    data object Connect : Screen
    data object MyQr : Screen
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    var tab by remember { mutableStateOf(0) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun openChat(id: String) { stack = listOf(Screen.Home, Screen.Chat(id)) }
    BackHandler(stack.size > 1) { pop() }

    // 点击状态栏通知：直接打开对应的聊天
    val chatReq by Hub.openChatRequest.collectAsState()
    LaunchedEffect(chatReq) {
        val id = chatReq ?: return@LaunchedEffect
        Hub.openChatRequest.value = null
        if (Hub.peers.value.containsKey(id)) openChat(id)
    }

    // 配对确认：两台手机会显示同一个 6 位验证码，核对一致后被扫的一方点"同意"才算配对成功
    val prompt by Hub.pairPrompt.collectAsState()
    prompt?.let { p ->
        val shown = p.code.take(3) + " " + p.code.drop(3)
        if (p.isHost) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("配对请求") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("\u201c${p.peerName}\u201d请求与本机配对。\n请核对对方手机上显示的验证码，和下面是否一致：", fontSize = 14.sp)
                        Spacer(Modifier.height(14.dp))
                        Text(shown, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = Blue2)
                        Spacer(Modifier.height(14.dp))
                        Text("不一致，或者不认识这台设备，请点\u201c拒绝\u201d。", fontSize = 12.sp, color = Gray)
                    }
                },
                confirmButton = { TextButton(onClick = { p.decision?.complete(true) }) { Text("一致，同意") } },
                dismissButton = { TextButton(onClick = { p.decision?.complete(false) }) { Text("拒绝") } }
            )
        } else {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("等待对方确认") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("请让对方核对这个验证码，一致后在对方手机上点\u201c同意\u201d：", fontSize = 14.sp)
                        Spacer(Modifier.height(14.dp))
                        Text(shown, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = Blue2)
                    }
                },
                confirmButton = { TextButton(onClick = { Net.cancelPair() }) { Text("取消") } }
            )
        }
    }

    // 扫热点二维码：缺权限时先申请；仍然没有就改为手动连接（免权限）
    val needPerm by HotspotJoin.needPerm.collectAsState()
    val joinPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val p = HotspotJoin.needPerm.value
        HotspotJoin.needPerm.value = null
        if (p != null) {
            if (hotspotCoreGranted(ctx)) HotspotJoin.join(ctx, p) else HotspotJoin.manual.value = p
        }
    }
    LaunchedEffect(needPerm) {
        if (needPerm != null) joinPermLauncher.launch(hotspotPermsMissing(ctx).toTypedArray())
    }
    val manual by HotspotJoin.manual.collectAsState()
    manual?.let { p ->
        AlertDialog(
            onDismissRequest = { HotspotJoin.manual.value = null },
            title = { Text("手动连接对方的热点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("自动连接用不了（缺少权限或被系统拒绝），可以手动连：", fontSize = 14.sp)
                    Text("Wi-Fi 名称：" + p.ssid + "\n密码：" + p.pwd, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "在系统的 Wi-Fi 列表里连上它（提示\u201c无法上网\u201d没关系，选保持连接）。连上后回到互传，点\u201c已连接，继续\u201d。",
                        fontSize = 12.sp, color = Gray
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { HotspotJoin.copyPassword(ctx, p.pwd) }) { Text("复制密码") }
                        OutlinedButton(onClick = { HotspotJoin.openWifiSettings(ctx) }) { Text("打开 Wi-Fi 设置") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    HotspotJoin.manual.value = null
                    HotspotJoin.continueManual(ctx, p)
                }) { Text("已连接，继续") }
            },
            dismissButton = { TextButton(onClick = { HotspotJoin.manual.value = null }) { Text("取消") } }
        )
    }

    val share by Hub.pendingShare.collectAsState()
    val joinStatus by HotspotJoin.status.collectAsState()
    BackHandler(share != null) { Hub.pendingShare.value = null }

    // 首次使用：一次性说明并申请权限，之后不再逐项打断
    var showIntro by remember { mutableStateOf(!Store.permsAsked) }
    val permsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Store.permsAsked = true
        showIntro = false
    }
    if (showIntro) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("首次使用需要几项授权") },
            text = { Text("• 通知：让互传在后台保持接收\n• 附近设备 / 定位：创建或连接临时热点（不授权也能用，改为手动开热点）\n• 相机：扫描二维码\n\n授权一次，之后不会再逐项弹窗打断你。") },
            confirmButton = { TextButton(onClick = { permsLauncher.launch(requiredPerms()) }) { Text("继续") } }
        )
    }

    // 对方扫了本机二维码 / 本机扫码连上热点后，自动进入对应聊天
    LaunchedEffect(Unit) {
        Hub.hellos.collect { id ->
            val top = stack.last()
            if (top is Screen.MyQr || top is Screen.Connect) openChat(id)
        }
    }
    LaunchedEffect(Unit) {
        HotspotJoin.connected.collect { id ->
            if (id != null) {
                HotspotJoin.connected.value = null
                openChat(id)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(ChatBg)) {
        val sh = share
        if (sh != null) {
            Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                ShareScreen(sh) { id ->
                    Hub.pendingShare.value = null
                    if (id != null) openChat(id)
                }
            }
        } else {
            when (val cur = stack.last()) {
                Screen.Home -> HomeTabs(
                    tab = tab, onTab = { tab = it },
                    onChat = { push(Screen.Chat(it)) },
                    onOpenChat = { openChat(it) },
                    onConnect = { push(Screen.Connect) },
                    onMyQr = { push(Screen.MyQr) }
                )
                else -> Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                    when (cur) {
                        is Screen.Chat -> ChatScreen(cur.peerId, onBack = { pop() }, onPickApps = { push(Screen.Picker(cur.peerId)) })
                        is Screen.Picker -> PickerScreen(cur.peerId) { pop() }
                        Screen.Connect -> ConnectScreen(onBack = { pop() }, onMyQr = { push(Screen.MyQr) }, onOpenChat = { openChat(it) })
                        Screen.MyQr -> MyQrScreen(onBack = { pop() })
                        Screen.Home -> {}
                    }
                }
            }
        }
        if (joinStatus.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp), color = Color(0xE6333333),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
            ) {
                Text(joinStatus, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
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
fun Avatar(name: String, online: Boolean = true, size: Dp = 42.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (online) Blue2 else Color(0xFFB8BEC8)),
        contentAlignment = Alignment.Center
    ) {
        Text(name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.43f).sp, fontWeight = FontWeight.Bold)
    }
}

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

/** 消息列表右侧的时间：今天 时:分 / 昨天 / 月-日 / 年-月-日 */
fun fmtListTime(t: Long): String {
    if (t <= 0) return ""
    val nowC = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val sameYear = nowC.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    val dayDiff = nowC.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
        sameYear && dayDiff == 1 -> "昨天"
        sameYear -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(t))
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(t))
    }
}

fun previewOf(m: Msg): String {
    val body = when (m.kind) {
        Kind.TEXT -> m.text
        Kind.FILE -> "[文件]${m.name}"
        Kind.APP -> "[应用]${m.name}"
    }
    return if (m.outgoing && m.state == MsgState.FAILED) "[发送失败] $body" else body
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

// ---------------- 聊天 ----------------

@Composable
fun ChatScreen(peerId: String, onBack: () -> Unit, onPickApps: () -> Unit) {
    val ctx = LocalContext.current
    val peers by Hub.peers.collectAsState()
    val all by Hub.msgs.collectAsState()
    val joinActive by HotspotJoin.active.collectAsState()
    val hsPayload by HotspotHost.payload.collectAsState()
    val peer = peers[peerId]
    val list = remember(all, peerId) { all.filter { it.peerId == peerId }.sortedBy { it.time } }
    val ls = rememberLazyListState()
    LaunchedEffect(list.size) { if (list.isNotEmpty()) ls.scrollToItem(list.size - 1) }

    // 打开聊天即视为已读；聊天开着时收到的新消息也直接算已读
    DisposableEffect(peerId) {
        Hub.openPeer = peerId
        Hub.markRead(peerId)
        onDispose { if (Hub.openPeer == peerId) Hub.openPeer = null }
    }
    // 只有界面在前台时才算"已读"；退到桌面后收到的消息要保持未读，才会有通知和未读数
    LaunchedEffect(all.size) { if (Hub.appVisible) Hub.markRead(peerId) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach {
            try { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Hub.sendFile(peerId, it)
        }
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
        peer == null -> "聊天"
        !peer.paired -> peer.name + "（未配对）"
        peer.online -> peer.name
        else -> peer.name + "（离线）"
    }
    val linkActive = joinActive || hsPayload != null
    var more by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(title, onBack) {
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "更多", tint = Color.White) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (linkActive) {
                        DropdownMenuItem(text = { Text("断开热点连接") }, onClick = {
                            more = false
                            HotspotJoin.leave()
                            HotspotHost.stop()
                        })
                    }
                    DropdownMenuItem(text = { Text("清空聊天记录") }, onClick = { more = false; Hub.clearHistory(peerId) })
                    DropdownMenuItem(text = { Text("删除此设备") }, onClick = { more = false; Hub.forgetPeer(peerId); onBack() })
                }
            }
        }
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("还没有传输记录", color = Gray, fontSize = 14.sp)
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
                    DropdownMenuItem(text = { Text("发送文件") }, onClick = { menuOpen = false; picker.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("发送应用") }, onClick = { menuOpen = false; onPickApps() })
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
                if (text.isNotBlank()) { Hub.sendText(peerId, text.trim()); text = "" }
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
                    "发送失败：${friendlyError(m.error)}（点击重试）", color = Color(0xFFE5484D), fontSize = 12.sp,
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
