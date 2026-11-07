@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hulian.transfer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

private val ScreenStackSaver = listSaver<List<Screen>, String>(
    save = { st ->
        st.map {
            when (it) {
                is Screen.Home -> "H"
                is Screen.Connect -> "C"
                is Screen.MyQr -> "Q"
                is Screen.Chat -> "T:" + it.peerId
                is Screen.Picker -> "P:" + it.peerId
            }
        }
    },
    restore = { l ->
        l.map {
            when {
                it == "C" -> Screen.Connect
                it == "Q" -> Screen.MyQr
                it.startsWith("T:") -> Screen.Chat(it.substring(2))
                it.startsWith("P:") -> Screen.Picker(it.substring(2))
                else -> Screen.Home
            }
        }.ifEmpty { listOf(Screen.Home) }
    }
)

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    // 用 rememberSaveable：界面被系统回收重建后，仍停在原来的对话框，不会被甩回首页
    var stack by rememberSaveable(stateSaver = ScreenStackSaver) { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    var tab by remember { mutableStateOf(0) }
    fun chatOf(s: List<Screen>): String? = s.filterIsInstance<Screen.Chat>().lastOrNull()?.peerId
    // 所有页面切换都走这里：离开某个对话框时（bye=true）通知对方也退出；
    // 因为对方退出 / 断线而退出的（bye=false）不用再通知
    fun setStack(new: List<Screen>, bye: Boolean = true) {
        val old = chatOf(stack)
        if (bye && old != null && old != chatOf(new)) Hub.sendBye(old)
        stack = new
    }
    fun push(s: Screen) { setStack(stack + s) }
    fun pop() { if (stack.size > 1) setStack(stack.dropLast(1)) }
    fun openChat(id: String) { setStack(listOf(Screen.Home, Screen.Chat(id))) }
    BackHandler(stack.size > 1) { pop() }

    // 对方退出了和本机的对话框：本机也退出
    LaunchedEffect(Unit) {
        Hub.peerLeft.collect { id ->
            if (chatOf(stack) == id) setStack(listOf(Screen.Home), bye = false)
        }
    }

    // 对话框里已经连上过对方，之后连接断了：每 2 秒核对一次、共 6 次，都连不上就自动退出对话框
    val chatId = chatOf(stack)
    LaunchedEffect(chatId) {
        val id = chatId ?: return@LaunchedEffect
        var wasOnline = false
        Hub.peers.map { it[id]?.online == true }.distinctUntilChanged().collect { on ->
            if (on) {
                wasOnline = true
                return@collect
            }
            if (!wasOnline) return@collect
            for (i in 0 until 6) {
                delay(2000)
                val p = Hub.peers.value[id] ?: return@collect
                if (p.online) return@collect
                if (id in Relay.online.value && Relay.ready(p)) return@collect   // 直连断了但还能远程联系：留在对话框里
                if (withContext(Dispatchers.IO) { Net.ping(p) }) {
                    Hub.markOnline(id)
                    return@collect
                }
            }
            setStack(listOf(Screen.Home), bye = false)
        }
    }

    // 点击状态栏通知：直接打开对应的聊天
    val chatReq by Hub.openChatRequest.collectAsState()
    LaunchedEffect(chatReq) {
        val id = chatReq ?: return@LaunchedEffect
        Hub.openChatRequest.value = null
        if (Hub.peers.value.containsKey(id)) openChat(id)
    }

    // 任何失败都在这里显示原因（可选中文字，也可以一键复制全部）
    val errInfo by Hub.errorDialog.collectAsState()
    errInfo?.let { e ->
        val full = e.title + "：" + e.reason + (if (e.detail.isNotEmpty()) "\n\n详细信息：\n" + e.detail else "")
        AlertDialog(
            onDismissRequest = { Hub.dismissError() },
            title = { Text(e.title) },
            text = {
                SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(e.reason, fontSize = 15.sp)
                        if (e.detail.isNotEmpty()) Text("详细信息：\n" + e.detail, fontSize = 12.sp, color = Gray)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { Hub.dismissError() }) { Text("知道了") } },
            dismissButton = {
                TextButton(onClick = {
                    try {
                        ctx.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("error", full))
                        Hub.toast("已复制")
                    } catch (e: Exception) { Hub.log("Ui", e) }
                }) { Text("复制") }
            }
        )
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

    // 配对成功后：本机有远程中转设置，问要不要发给对方（默认不发，发给别人对方就能用你的网盘和口令）
    val cfgOffer by Hub.cfgOffer.collectAsState()
    cfgOffer?.let { o ->
        AlertDialog(
            onDismissRequest = { Hub.cfgOffer.value = null },
            title = { Text("同步远程中转设置？") },
            text = {
                Text(
                    "已和\u201c${o.peerName}\u201d配对。要把本机的远程中转设置（Cloudflare 地址和口令、网盘账号密码、域名解析）也发给它，让它不用重新填吗？\n\n" +
                        "只发给你自己的手机。发给别人，对方就能用你的网盘和口令。",
                    fontSize = 14.sp
                )
            },
            confirmButton = { TextButton(onClick = { Hub.cfgOffer.value = null; Hub.sendCfg(o) }) { Text("发送") } },
            dismissButton = { TextButton(onClick = { Hub.cfgOffer.value = null }) { Text("不发送") } }
        )
    }

    // 扫热点二维码：缺权限时先申请；仍然没有就改为手动连接（免权限）
    val needPerm by HotspotJoin.needPerm.collectAsState()
    val joinPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        AutoLink.kick()
        val p = HotspotJoin.needPerm.value
        HotspotJoin.needPerm.value = null
        if (p != null) {
            if (hotspotCoreGranted(ctx)) HotspotJoin.join(ctx, p) else {
                HotspotJoin.manualReason.value = "没有授予" + hotspotPermName() + "权限"
                HotspotJoin.manual.value = p
            }
        }
    }
    LaunchedEffect(needPerm) {
        if (needPerm != null) joinPermLauncher.launch(hotspotPermsMissing(ctx).toTypedArray())
    }
    val manual by HotspotJoin.manual.collectAsState()
    val manualWhy by HotspotJoin.manualReason.collectAsState()
    manual?.let { p ->
        AlertDialog(
            onDismissRequest = { HotspotJoin.manual.value = null },
            title = { Text("手动连接对方的热点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("自动连接用不了，原因：" + manualWhy.ifEmpty { "系统没有给出原因" } + "\n\n可以手动连：", fontSize = 14.sp)
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

    // 自动开热点需要系统定位，而本 app 没有权限自己开：引导去设置里打开，返回后会自动继续
    val needLoc by AutoLink.needLocation.collectAsState()
    if (needLoc) {
        AlertDialog(
            onDismissRequest = { AutoLink.needLocation.value = false },
            title = { Text("需要打开定位") },
            text = {
                Text(
                    "创建热点需要系统的\u201c定位\u201d开关。点\u201c去打开\u201d，打开后回到互传，会自动继续。\n\n" +
                        "想让互传以后自动开关定位：有 root 的手机给互传授权 root 即可；没有 root，就在电脑上执行一次\nadb shell pm grant com.hulian.transfer android.permission.WRITE_SECURE_SETTINGS",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AutoLink.needLocation.value = false
                    LocationSwitch.openSettings(ctx)
                }) { Text("去打开") }
            },
            dismissButton = { TextButton(onClick = { AutoLink.needLocation.value = false }) { Text("取消") } }
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
        AutoLink.kick()
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
fun TopBar(title: String, onBack: (() -> Unit)?, titleContent: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
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
            if (titleContent != null) {
                Box(Modifier.weight(1f)) { titleContent() }
            } else {
                Text(title, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
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
        Kind.FILE -> if (isImageName(m.file.ifEmpty { m.name })) "[图片]" else "[文件]${m.name}"
        Kind.APP -> "[应用]${m.name}"
    }
    if (m.state == MsgState.RECALLED) return if (m.outgoing) "你撤回了一条消息" else "对方撤回了一条消息"
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
    val groupUp by DirectGroup.up.collectAsState()
    val peer = peers[peerId]
    // 点开对话框：自动用热点连接这台设备；离开对话框：传输结束后自动断开
    DisposableEffect(peerId) {
        AutoLink.open(ctx, peerId)
        // 界面被系统销毁重建时不断开：新界面马上会再进同一个对话框
        onDispose { if (!Hub.recreating) AutoLink.close() }
    }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    var quoting by remember { mutableStateOf<Msg?>(null) }
    var forwardList by remember { mutableStateOf<List<Msg>?>(null) }
    val tfState = rememberTextFieldState(Store.draft(peerId))
    // 草稿：输入框里没发出去的字，退出聊天再进来还在（停手 0.4 秒存一次，不是每个字都写）
    LaunchedEffect(peerId) {
        snapshotFlow { tfState.text.toString() }.collectLatest { delay(400); Store.setDraft(peerId, it) }
    }
    DisposableEffect(peerId) { onDispose { Store.setDraft(peerId, tfState.text.toString()) } }
    BackHandler(enabled = selecting) { selected.clear() }
    BackHandler(enabled = searching && !selecting) { searching = false; query = "" }
    val list = remember(all, peerId, searching, query) {
        val l = all.filter { it.peerId == peerId }.sortedBy { it.time }
        if (searching && query.isNotBlank()) l.filter { it.state != MsgState.RECALLED && previewOf(it).contains(query.trim(), true) } else l
    }
    val ls = rememberLazyListState()
    // 新消息来了：本来就停在最底部（或是自己发的、第一次进来）才滚到底；正在往上翻历史时不抢走位置
    var firstScroll by remember { mutableStateOf(true) }
    LaunchedEffect(list.size) {
        if (list.isEmpty() || searching) return@LaunchedEffect
        // 列表是底部对齐的（最新一条是第 0 项）：新消息进来时本来就在底部附近（或是自己发的）才跟到底，往上翻历史时不抢位置
        if (firstScroll || ls.firstVisibleItemIndex <= 1 || list.last().outgoing) ls.scrollToItem(0)
        firstScroll = false
    }

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
            try { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { Hub.log("记住文件读取权限", e) }
            Hub.sendFile(peerId, it)
        }
    }

    // 相册：图片和视频（系统照片选择器，不需要存储权限）
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        uris.forEach { Hub.sendFile(peerId, it) }
    }
    // 表情面板里的“+”：从相册批量导入图片 / 动图到表情库
    val stickerScope = rememberCoroutineScope()
    val stickerImport = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) stickerScope.launch {
            val rs = withContext(Dispatchers.IO) {
                uris.map { Stickers.add(ctx, it, Saver.queryMeta(ctx, it).first) }
            }
            val ok = rs.count { it == Stickers.Result.ADDED }
            val dup = rs.count { it == Stickers.Result.EXISTS }
            val big = rs.count { it == Stickers.Result.TOO_BIG }
            val bad = rs.count { it == Stickers.Result.FAILED }
            Hub.toast(buildString {
                append("已添加 $ok 个表情")
                if (dup > 0) append("，$dup 个已经有了")
                if (big > 0) append("，$big 个超过 15MB 没添加")
                if (bad > 0) append("，$bad 个读取失败")
            })
        }
    }
    // 文件夹：打成 zip 再发
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) Hub.sendFolder(peerId, uri)
    }

    fun onMsgClick(m: Msg) {
        if (m.outgoing && (m.state == MsgState.FAILED || m.state == MsgState.CANCELED)) {
            Hub.retry(m)
        } else if (!m.outgoing && m.state == MsgState.DONE && m.kind != Kind.TEXT && m.uri.isNotEmpty()) {
            val n = m.file.ifEmpty { m.name }
            if (n.endsWith(".apk", true) || n.endsWith(".apks", true)) Installer.install(ctx, Uri.parse(m.uri), n)
            else Saver.open(ctx, Uri.parse(m.uri), n)
        }
    }

    fun onAction(m: Msg, act: String) {
        when (act) {
            "copy" -> {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("消息", if (m.kind == Kind.TEXT) m.text else m.name))
                Hub.toast("已复制")
            }
            "quote" -> quoting = m
            "forward" -> forwardList = listOf(m)
            "recall" -> Hub.recall(m)
            "delete" -> Hub.deleteMsgs(setOf(m.id))
            "select" -> { selected.clear(); selected.add(m.id) }
        }
    }

    val relayOnline by Relay.online.collectAsState()
    val title = when {
        peer == null -> "聊天"
        !peer.paired -> peer.name + "（未配对）"
        peer.online -> peer.name
        peer.id in relayOnline -> peer.name + "（远程）"
        else -> peer.name + "（离线）"
    }
    val linkActive = joinActive || hsPayload != null || groupUp
    var more by remember { mutableStateOf(false) }

    // 顶栏里原来“红米k30”的位置直接显示自动连接状态，字号/颜色/转圈和原来的状态条完全一致；没有状态时才显示设备名
    val link by AutoLink.status.collectAsState()
    val statusTitle: (@Composable () -> Unit)? = link?.let { st ->
        {
            val fg = when (st.kind) {
                LinkKind.OK -> Color(0xFF2E7D32)
                LinkKind.WARN -> Color(0xFFE65100)
                LinkKind.WORK -> Color(0xFF1565C0)
            }
            Row(
                Modifier.clickable(enabled = st.action.isNotEmpty()) { AutoLink.onStatusClick(ctx) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (st.kind == LinkKind.WORK) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = fg)
                    Spacer(Modifier.width(8.dp))
                }
                Text(st.text, color = fg, fontSize = 14.sp)
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().background(Blue2).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selected.clear() }) { Icon(Icons.Default.Close, "取消多选", tint = Color.White) }
                Text("已选 ${selected.size} 条", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    forwardList = list.filter { it.id in selected }.filter { it.kind == Kind.TEXT || it.uri.isNotEmpty() }
                }) { Text("转发", color = Color.White) }
                TextButton(onClick = { Hub.deleteMsgs(selected.toSet()); selected.clear() }) { Text("删除", color = Color.White) }
            }
        } else TopBar(title, onBack, statusTitle) {
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "更多", tint = Color.White) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (linkActive) {
                        DropdownMenuItem(text = { Text("断开热点连接") }, onClick = {
                            more = false
                            AutoLink.pause()
                            HotspotJoin.leave()
                            HotspotHost.stop()
                        })
                    }
                    DropdownMenuItem(text = { Text("搜索聊天记录") }, onClick = { more = false; searching = true })
                    DropdownMenuItem(text = { Text("清空聊天记录") }, onClick = { more = false; Hub.clearHistory(peerId) })
                    DropdownMenuItem(text = { Text("删除此设备") }, onClick = { more = false; Hub.forgetPeer(peerId); onBack() })
                }
            }
        }
        if (searching) {
            Row(Modifier.fillMaxWidth().background(Color(0xFFF7F8FA)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("搜索聊天记录") }, shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    )
                )
                TextButton(onClick = { searching = false; query = "" }) { Text("取消") }
            }
        }
        // 提示条浮在列表上方，不占列表的高度：网络状态变化时它们出现 / 消失，聊天内容不会跟着跳
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (searching && query.isNotBlank()) "没有找到" else "还没有传输记录", color = Gray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = ls, reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(list.asReversed(), key = { _, m -> m.id }) { j, m ->
                    val i = list.size - 1 - j
                    Column {
                        if (i == 0 || m.time - list[i - 1].time > 300_000) {
                            Text(
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(m.time)),
                                color = Gray, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        MsgRow(
                            m, peer?.name ?: "?", selecting, m.id in selected,
                            onClick = { onMsgClick(m) },
                            onToggle = { if (m.id in selected) selected.remove(m.id) else selected.add(m.id) },
                            onAction = { act -> onAction(m, act) }
                        )
                    }
                }
            }
        }
            Column(Modifier.align(Alignment.TopStart)) {
        // 远程中转的状态：和上面的热点状态是两回事。直连没连上时也能走远程中转发送，这里单独说清楚现在能不能用、不能用的原因
        val relayConn by Relay.connected.collectAsState()
        val relayState by Relay.state.collectAsState()
        val relayWhy = if (peer != null) Relay.notReadyReason(peer) else null
        val relayLine: Pair<String, Boolean>? = when {
            peer == null || !peer.paired || !Store.relayOn -> null
            peer.online -> null
            relayWhy != null -> ("远程中转不可用：" + relayWhy) to false
            !relayConn -> ("远程中转还没连上 Cloudflare：" + relayState) to false
            peer.id in relayOnline -> "对方在线（远程中转），可以直接发送，不用等热点" to true
            else -> "对方现在没连上 Cloudflare，发送的内容会先存到网盘，对方上线后自动收取" to true
        }
        relayLine?.let { (txt, ok) ->
            Text(
                txt,
                color = if (ok) Color(0xFF2E7D32) else Color(0xFFE65100),
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xFFF1F6FC)).padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        // 坚果云额度：常显在对话框里，上传 / 下载了多少就少多少
        val quota by DavQuota.snap.collectAsState()
        LaunchedEffect(Unit) { DavQuota.refresh() }
        quota?.let { q ->
            if (peer != null && peer.paired && Store.relayOn) {
                val upLeft = (q.upLimit - q.upUsed).coerceAtLeast(0L)
                val downLeft = (q.downLimit - q.downUsed).coerceAtLeast(0L)
                val low = upLeft < q.upLimit / 5 || downLeft < q.downLimit / 5 || q.req >= q.reqLimit * 8 / 10
                Text(
                    "坚果云额度  上传剩 ${fmtSize(upLeft)} / ${fmtSize(q.upLimit)}  ·  下载剩 ${fmtSize(downLeft)} / ${fmtSize(q.downLimit)}  ·  请求 ${q.req} / ${q.reqLimit}（30 分钟）" +
                        (if (q.resetAt > System.currentTimeMillis()) "  ·  ${(q.resetAt - System.currentTimeMillis() + 86_399_999L) / 86_400_000L} 天后刷新" else "") +
                        (if (q.folderBytes >= 0 && q.folderName.isNotEmpty()) "  ·  ${q.folderName} 占 ${fmtSize(q.folderBytes)}" else ""),
                    color = if (low) Color(0xFFE65100) else Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF1F6FC)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
            }
        }
        var menuOpen by remember { mutableStateOf(false) }
        var panel by remember { mutableStateOf(false) }
        val kb = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        BackHandler(enabled = panel) { panel = false }
        quoting?.let { q ->
            Row(
                Modifier.fillMaxWidth().background(Color(0xFFEDEFF2)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "回复：" + previewOf(q), color = Gray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                )
                IconButton(onClick = { quoting = null }) { Icon(Icons.Default.Close, "取消回复", tint = Gray) }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFFF7F8FA)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Add, "更多", tint = Gray) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("图片和视频") }, onClick = {
                        menuOpen = false; Hub.picking = true
                        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    })
                    DropdownMenuItem(text = { Text("发送文件") }, onClick = { menuOpen = false; Hub.picking = true; picker.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("发送文件夹") }, onClick = { menuOpen = false; Hub.picking = true; folderPicker.launch(null) })
                    DropdownMenuItem(text = { Text("发送应用") }, onClick = { menuOpen = false; onPickApps() })
                }
            }
            IconButton(onClick = {
                if (panel) panel = false
                else { kb?.hide(); focus.clearFocus(); panel = true }
            }) { Text("🙂", fontSize = 22.sp) }
            // 用 TextFieldState 版本的输入框：只有它才能接收输入法发来的图片 / 动图（也包括长按“粘贴”的图片）
            BasicTextField(
                state = tfState,
                modifier = Modifier.weight(1f)
                    .onFocusChanged { if (it.isFocused) panel = false }
                    .contentReceiver { t -> receiveFromKeyboard(ctx, peerId, t) },
                textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF111111)),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 4),
                cursorBrush = SolidColor(Blue2),
                decorator = { inner ->
                    Box(
                        Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (tfState.text.isEmpty()) Text("输入消息", color = Gray, fontSize = 16.sp)
                        inner()
                    }
                }
            )
            IconButton(onClick = {
                val t = tfState.text.toString().trim()
                if (t.isNotEmpty()) {
                    Hub.sendText(peerId, t, quoting?.let { previewOf(it).take(80) } ?: "")
                    quoting = null
                    tfState.clearText()
                }
            }) { Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = Blue2) }
        }
        if (panel) {
            StickerPanel(
                onSend = { f -> Hub.sendLocalFile(peerId, f) },
                onImport = {
                    Hub.picking = true
                    stickerImport.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
        }
    }
    forwardList?.let { fl ->
        AlertDialog(
            onDismissRequest = { forwardList = null },
            title = { Text("转发给") },
            text = {
                val targets = peers.values.filter { it.paired }
                if (targets.isEmpty()) Text("还没有已配对的设备")
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    targets.forEach { t ->
                        Text(
                            t.name, fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                forwardList = null
                                selected.clear()
                                fl.sortedBy { it.time }.forEach { m ->
                                    if (m.kind == Kind.TEXT) Hub.sendText(t.id, m.text)
                                    else if (m.kind == Kind.FILE && m.uri.isNotEmpty()) Hub.sendFile(t.id, Uri.parse(m.uri))
                                }
                                Hub.toast("已转发")
                            }.padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { forwardList = null }) { Text("取消") } }
        )
    }
}

/** 输入法 / 粘贴送来的图片：马上复制到本机缓存（输入法给的地址是临时授权，回调一结束就失效），再当普通图片发出 */
private fun receiveFromKeyboard(ctx: Context, peerId: String, t: TransferableContent): TransferableContent? {
    if (!t.hasMediaType(MediaType.Image)) return t
    return t.consume { item ->
        val uri = item.uri ?: return@consume false
        val f = copyIncomingImage(ctx, uri) ?: return@consume false
        Hub.sendLocalFile(peerId, f)
        true
    }
}

private fun copyIncomingImage(ctx: Context, uri: Uri): File? {
    return try {
        val dir = File(File(ctx.cacheDir, "outbox"), newId()).apply { mkdirs() }
        val tmp = File(dir, "in.tmp")
        val limit = 15L * 1024 * 1024
        var total = 0L
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) { dir.deleteRecursively(); Hub.toast("图片超过 15MB，没有发送"); return null }
                    out.write(buf, 0, n)
                }
            }
        } ?: run { dir.deleteRecursively(); return null }
        // 不信任输入法给的类型，直接看文件头判断格式
        val head = ByteArray(12)
        val got = tmp.inputStream().use { it.read(head) }
        val ext = when {
            got >= 4 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() -> "gif"
            got >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() -> "png"
            got >= 12 && head[0] == 'R'.code.toByte() && head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() -> "webp"
            got >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg"
            else -> null
        }
        if (ext == null) { dir.deleteRecursively(); Hub.toast("不支持这种图片格式"); return null }
        val f = File(dir, "image_${now()}.$ext")
        if (!tmp.renameTo(f)) { dir.deleteRecursively(); return null }
        f
    } catch (e: Throwable) {
        Hub.log("接收输入法图片", e)
        Hub.toast("接收输入法发来的图片失败")
        null
    }
}

/** 表情面板：第一格“+”从相册导入，其余是收藏的表情，点一下就发，长按删除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerPanel(onSend: (File) -> Unit, onImport: () -> Unit) {
    val ctx = LocalContext.current
    val list by Stickers.list.collectAsState()
    var del by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Stickers.reload(ctx) } }
    Box(Modifier.fillMaxWidth().height(260.dp).background(Color(0xFFF7F8FA))) {
        LazyVerticalGrid(
            GridCells.Adaptive(76.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "add") {
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).clickable(onClick = onImport),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Add, "从相册添加表情", tint = Gray) }
            }
            gridItems(list, key = { it.name }) { f ->
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
                        .combinedClickable(onClick = { onSend(f) }, onLongClick = { del = f }),
                    contentAlignment = Alignment.Center
                ) {
                    StickerThumb(f, 68.dp)
                    DropdownMenu(expanded = del == f, onDismissRequest = { del = null }) {
                        DropdownMenuItem(text = { Text("删除这个表情") }, onClick = {
                            del = null
                            Stickers.remove(ctx, f)
                        })
                    }
                }
            }
        }
        if (list.isEmpty()) {
            Text(
                "点 + 从相册添加；也可以长按聊天里的图片，选“添加到表情”",
                color = Gray, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MsgRow(
    m: Msg, peerName: String, selecting: Boolean, selected: Boolean,
    onClick: () -> Unit, onToggle: () -> Unit, onAction: (String) -> Unit
) {
    val out = m.outgoing
    if (m.state == MsgState.RECALLED) {
        Text(
            if (out) "你撤回了一条消息" else "对方撤回了一条消息", color = Gray, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        return
    }
    // 图片读不出来（文件被删、格式系统不支持）时退回文件卡片
    var imgFailed by remember(m.id) { mutableStateOf(false) }
    var menu by remember(m.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().background(if (selected) Color(0x1A1E88E5) else Color.Transparent)) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = if (out) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (selecting) { Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(end = 6.dp, top = 8.dp)); if (out) Spacer(Modifier.weight(1f)) }
        if (!out) { Avatar(peerName); Spacer(Modifier.width(8.dp)) }
        Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 300.dp)) {
            if (m.kind == Kind.TEXT) {
                Box {
                    Surface(shape = RoundedCornerShape(18.dp), color = if (out) OutBubble else Color.White) {
                        Column(
                            Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            if (m.quote.isNotEmpty()) {
                                Text(
                                    m.quote, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = if (out) Color(0xCCFFFFFF) else Gray,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                        .background(if (out) Color(0x22FFFFFF) else Color(0xFFF1F3F6), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Text(m.text, color = if (out) Color.White else Color(0xFF111111), fontSize = 16.sp)
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MsgMenuItems(m, { menu = false }, onAction)
                    }
                }
            } else if (isInlineImage(m) && !imgFailed) {
                ImageBubble(m, onClick, { imgFailed = true }, onAction)
            } else {
                Box {
                    FileCard(m, onClick, onLong = { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MsgMenuItems(m, { menu = false }, onAction)
                    }
                }
            }
            if (m.state == MsgState.FAILED && out) {
                Text(
                    "发送失败：${friendlyError(m.error)}（点击重试）", color = Color(0xFFE5484D), fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onClick).padding(top = 2.dp)
                )
            }
            if (m.state == MsgState.CANCELED && out) {
                Text(
                    "已取消" + (if (m.error.isNotEmpty()) "（${m.error}）" else "") + "（点击重新发送）", color = Gray, fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onClick).padding(top = 2.dp)
                )
            }
            // 发出的消息：已发出 / 已送达 / 已读
            val tick = when {
                !out -> ""
                m.state == MsgState.DONE && m.seen -> "已读"
                m.kind == Kind.TEXT && m.state == MsgState.SENDING -> "发送中"
                m.kind == Kind.TEXT && m.state == MsgState.WAITING -> "已发出"
                m.kind == Kind.TEXT && m.state == MsgState.DONE -> "已送达"
                else -> ""
            }
            if (tick.isNotEmpty()) Text(tick, color = if (tick == "已读") Blue2 else Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (out) { Spacer(Modifier.width(8.dp)); Avatar("我") }
    }
    if (selecting) Box(Modifier.matchParentSize().clickable(onClick = onToggle))
    }
}

/** 长按菜单：复制 / 回复 / 转发 / 撤回 / 删除 / 多选 */
@Composable
private fun ColumnScope.MsgMenuItems(m: Msg, dismiss: () -> Unit, onAction: (String) -> Unit) {
    DropdownMenuItem(text = { Text(if (m.kind == Kind.TEXT) "复制" else "复制文件名") }, onClick = { dismiss(); onAction("copy") })
    DropdownMenuItem(text = { Text("回复") }, onClick = { dismiss(); onAction("quote") })
    if (m.kind == Kind.TEXT || (m.kind == Kind.FILE && m.uri.isNotEmpty())) {
        DropdownMenuItem(text = { Text("转发") }, onClick = { dismiss(); onAction("forward") })
    }
    if (Hub.canRecall(m)) DropdownMenuItem(text = { Text("撤回") }, onClick = { dismiss(); onAction("recall") })
    if (!m.active) DropdownMenuItem(text = { Text("删除") }, onClick = { dismiss(); onAction("delete") })
    DropdownMenuItem(text = { Text("多选") }, onClick = { dismiss(); onAction("select") })
}

/** 聊天里直接显示图片 / 动图；长按可以“添加到表情” */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageBubble(m: Msg, onClick: () -> Unit, onFail: () -> Unit, onAction: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val pct = if (m.size > 0) (m.done.toFloat() / m.size).coerceIn(0f, 1f) else 0f
    Box {
        Column(horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
                ChatImage(m, onFail = onFail)
            }
            if (m.state == MsgState.WAITING) {
                Text("等待对方接收", fontSize = 12.sp, color = Gray, modifier = Modifier.padding(top = 2.dp))
            }
            if (m.state == MsgState.SENDING || m.state == MsgState.PAUSED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (m.state == MsgState.PAUSED) "已暂停 " else "发送中 ") + "${(pct * 100).toInt()}%",
                        fontSize = 12.sp, color = Gray, modifier = Modifier.padding(top = 2.dp)
                    )
                    TransferButtons(m)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("添加到表情") }, onClick = {
                menu = false
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Stickers.add(ctx, Uri.parse(m.uri), m.file.ifEmpty { m.name }) }
                    Hub.toast(when (r) {
                        Stickers.Result.ADDED -> "已添加到表情"
                        Stickers.Result.EXISTS -> "这个表情已经收藏过了"
                        Stickers.Result.TOO_BIG -> "图片超过 15MB，没有添加"
                        Stickers.Result.FAILED -> "添加失败：原图已经读取不到了"
                    })
                }
            })
            if (!m.outgoing) {
                DropdownMenuItem(text = { Text("用其他应用打开") }, onClick = {
                    menu = false
                    Saver.open(ctx, Uri.parse(m.uri), m.file.ifEmpty { m.name })
                })
            }
            MsgMenuItems(m, { menu = false }, onAction)
        }
    }
}

/** 传输中的“暂停 / 继续”和“取消”：没有控制柄（已经传完、或者是很快就传完的小文件）就不显示 */
@Composable
private fun TransferButtons(m: Msg) {
    val ctl = Transfers.get(m.id) ?: return
    Row {
        if (ctl.canPause) {
            TextButton(onClick = { Hub.togglePause(m) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (m.state == MsgState.PAUSED) "继续" else "暂停", fontSize = 13.sp)
            }
        }
        TextButton(onClick = { Hub.cancelTransfer(m.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("取消", fontSize = 13.sp, color = Color(0xFFE5484D))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(m: Msg, onClick: () -> Unit, onLong: () -> Unit = {}) {
    val pct = if (m.size > 0) (m.done.toFloat() / m.size).coerceIn(0f, 1f) else 0f
    val status = when (m.state) {
        MsgState.SENDING -> "发送中 ${(pct * 100).toInt()}%"
        MsgState.RECEIVING -> "接收中 ${(pct * 100).toInt()}%"
        MsgState.PAUSED -> if (m.error.isNotEmpty()) "已暂停：${m.error}" else "已暂停 ${(pct * 100).toInt()}%"
        MsgState.CANCELED -> "已取消"
        MsgState.DONE -> if (m.outgoing) "已发送" else if (m.kind == Kind.APP) "已接收，点击安装" else "已接收，点击打开"
        MsgState.FAILED -> if (m.error.isNotEmpty()) "失败：${m.error}" else "失败"
        MsgState.OFFERED -> "待接收"
        MsgState.WAITING -> "等待对方接收"
        MsgState.RECALLED -> "已撤回"
    }
    val isApk = (m.file.ifEmpty { m.name }).let { it.endsWith(".apk", true) || it.endsWith(".apks", true) }
    val badge = if (m.kind == Kind.APP || isApk) "APK" else m.name.substringAfterLast('.', "FILE").take(4).uppercase()
    val title = if (m.kind == Kind.APP) "${m.name}  ${m.ver}" else m.name
    Surface(
        shape = RoundedCornerShape(18.dp), color = if (m.outgoing) Color(0xFFD9F0FD) else Color.White,
        modifier = Modifier.width(280.dp).combinedClickable(onClick = onClick, onLongClick = onLong)
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
            if (m.state == MsgState.SENDING || m.state == MsgState.RECEIVING || m.state == MsgState.PAUSED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TransferButtons(m) }
            }
            // 接收失败、网盘上的文件还在：直接重试，不用对方重发
            if (!m.outgoing && m.state == MsgState.FAILED && Relay.canRetryReceive(m.id)) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { Relay.retryReceive(m.id) }, modifier = Modifier.fillMaxWidth()) { Text("重试接收") }
            }
            // 远程发来的文件不自动下载：点“接收”才开始，“拒绝”会清掉网盘上的文件并通知对方
            if (m.state == MsgState.OFFERED) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Relay.rejectOffer(m.id) }, modifier = Modifier.weight(1f)) {
                        Text("拒绝", color = Color(0xFFE5484D))
                    }
                    Button(onClick = { Relay.acceptOffer(m.id) }, modifier = Modifier.weight(1f)) { Text("接收") }
                }
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
