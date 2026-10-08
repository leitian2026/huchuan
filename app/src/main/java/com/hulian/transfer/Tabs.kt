package com.hulian.transfer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile

// ---------------- 首页：消息 / 设备 / 我 ----------------

@Composable
fun HomeTabs(
    tab: Int,
    onTab: (Int) -> Unit,
    onChat: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onConnect: () -> Unit,
    onMyQr: () -> Unit
) {
    val scan = rememberScanner(onOpenChat)
    val msgs by Hub.msgs.collectAsState()
    val unreadTotal = msgs.count { !it.outgoing && !it.read }
    Column(Modifier.fillMaxSize()) {
        when (tab) {
            0 -> TabTopBar("消息", onScan = scan, onMyQr = onMyQr)
            1 -> TabTopBar("设备", onScan = scan, onMyQr = onMyQr)
            else -> TopBar("我", null)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> MessagesTab(onChat)
                1 -> DevicesTab(onChat, onConnect)
                else -> MeTab()
            }
        }
        val itemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = Blue2, selectedTextColor = Blue2, indicatorColor = Color(0xFFE3F3FE),
            unselectedIconColor = Gray, unselectedTextColor = Gray
        )
        NavigationBar(containerColor = Color.White) {
            NavigationBarItem(
                selected = tab == 0, onClick = { onTab(0) }, colors = itemColors,
                icon = {
                    BadgedBox(badge = {
                        if (unreadTotal > 0) UnreadBadge(unreadTotal)
                    }) { Icon(Icons.Default.Email, null) }
                },
                label = { Text("消息") }
            )
            NavigationBarItem(
                selected = tab == 1, onClick = { onTab(1) }, colors = itemColors,
                icon = { Icon(Icons.Default.Phone, null) }, label = { Text("设备") }
            )
            NavigationBarItem(
                selected = tab == 2, onClick = { onTab(2) }, colors = itemColors,
                icon = { Icon(Icons.Default.AccountCircle, null) }, label = { Text("我") }
            )
        }
    }
}

/** 仿 QQ 顶栏：左边本机头像+在线点，中间标题，右边“+”菜单 */
@Composable
fun TabTopBar(title: String, onScan: () -> Unit, onMyQr: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val rootOk by Root.available.collectAsState()
    val rootDenied by Root.denied.collectAsState()
    val ctx = LocalContext.current
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Blue1, Blue2)))
            .statusBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center
                ) { Text(Store.deviceName.take(1).uppercase(), color = Blue2, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape)
                        .background(Color(0xFF5BD98C))
                )
            }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 18.sp)
                // 没有 root：提示一下，点一下重新检测。已 root 但没授权：点一下去 root 管理器里授权
                if (rootOk == false) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (rootDenied) "已root 未授权" else "没有root",
                        color = if (rootDenied) Color(0xFFE8590C) else Color(0xFFD93025),
                        fontSize = 12.sp, lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(9.dp))
                            .background(Color.White)
                            .clickable {
                                if (rootDenied) {
                                    if (!Root.openManager(ctx)) {
                                        Hub.toast("没找到 root 管理器，请在里面给互传授权 root")
                                        Root.refresh(force = true)
                                    }
                                } else {
                                    Hub.toast("正在重新检测 root…")
                                    Root.refresh(force = true)
                                }
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.Add, "添加", tint = Color.White) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("扫一扫") }, onClick = { open = false; onScan() })
                    DropdownMenuItem(text = { Text("我的二维码") }, onClick = { open = false; onMyQr() })
                    DropdownMenuItem(text = { Text("重新搜索设备") }, onClick = { open = false; Hub.rescan() })
                }
            }
        }
    }
}

/**
 * 本机面对这台设备时的角色：给二维码的是“创建方”，扫码的是“接收方”。
 * 优先用 AutoLink 里实时的角色（两边同时建热点而互换时会跟着变），没有就按配对记录算（重新配对改了记录也会跟着变）
 */
fun roleOf(p: Peer, live: Pair<String, LinkRole>?): LinkRole? {
    if (!p.paired) return null
    return if (live != null && live.first == p.id) live.second else AutoLink.roleFor(p)
}

/** 列表行右边的角色小标签：创建方蓝色，接收方绿色 */
@Composable
fun RoleTag(role: LinkRole) {
    val host = role == LinkRole.HOST
    Text(
        if (host) "创建方" else "接收方",
        fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        color = if (host) Color(0xFF1565C0) else Color(0xFF2E7D32),
        modifier = Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (host) Color(0xFFE3F2FD) else Color(0xFFE8F5E9))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/** 未读数徽标：一位数是正圆，两位数以上自动拉成胶囊；列表和底部导航共用 */
@Composable
fun UnreadBadge(count: Int) {
    Box(
        Modifier.height(18.dp).defaultMinSize(minWidth = 18.dp)
            .clip(RoundedCornerShape(9.dp)).background(Color(0xFFFF4D4F))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = Color.White, fontSize = 11.sp, lineHeight = 11.sp, maxLines = 1
        )
    }
}

// ---------------- 消息：会话列表 ----------------

@Composable
fun MessagesTab(onChat: (String) -> Unit) {
    val peers by Hub.peers.collectAsState()
    val live by AutoLink.roleState.collectAsState()
    val msgs by Hub.msgs.collectAsState()
    val rows = remember(peers, msgs) {
        msgs.groupBy { it.peerId }.mapNotNull { (id, l) ->
            val last = l.maxByOrNull { it.time } ?: return@mapNotNull null
            val p = peers[id] ?: Peer(id, "未知设备", "", 0)
            Triple(p, last, l.count { !it.outgoing && !it.read })
        }.sortedByDescending { it.second.time }
    }
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            Text("还没有消息\n到“设备”里选一台设备开始传输", color = Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().background(Color.White)) {
            items(rows, key = { it.first.id }) { (p, last, unread) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onChat(p.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(p.name, p.online, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text(previewOf(last), fontSize = 14.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtListTime(last.time), fontSize = 12.sp, color = Gray)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            roleOf(p, live)?.let { RoleTag(it) }
                            if (unread > 0) {
                                Spacer(Modifier.width(6.dp))
                                UnreadBadge(unread)
                            } else {
                                Spacer(Modifier.height(18.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFFF1F2F5), modifier = Modifier.padding(start = 78.dp))
            }
        }
    }
}

// ---------------- 设备：好友列表 ----------------

@Composable
fun DevicesTab(onChat: (String) -> Unit, onConnect: () -> Unit) {
    val peers by Hub.peers.collectAsState()
    val live by AutoLink.roleState.collectAsState()
    var q by remember { mutableStateOf("") }
    val list = remember(peers, q) {
        peers.values.filter { q.isBlank() || it.name.contains(q, true) }
            .sortedWith(compareByDescending<Peer> { it.online }.thenByDescending { it.lastSeen })
    }
    val relayOnline by Relay.online.collectAsState()
    val onlineCount = peers.values.count { it.online || it.id in relayOnline }
    var menuFor by remember { mutableStateOf<String?>(null) }      // 哪一行的“更多”菜单开着
    var confirmDel by remember { mutableStateOf<Peer?>(null) }     // 等用户确认删除的设备
    Column(Modifier.fillMaxSize().background(Color.White)) {
        TextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text("搜索设备", color = Gray) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Gray) },
            shape = RoundedCornerShape(22.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ListBg, unfocusedContainerColor = ListBg,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onConnect).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("添加设备（扫码）", fontSize = 17.sp, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Gray)
        }
        HorizontalDivider(color = Color(0xFFF1F2F5))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("我的设备", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("$onlineCount/${peers.size}", fontSize = 13.sp, color = Gray)
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (peers.isEmpty()) "还没有设备\n点上面的\u201c添加设备（扫码）\u201d，用另一台手机扫码配对一次，之后同一网络下会自动出现" else "没有匹配的设备",
                    color = Gray, fontSize = 14.sp, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onChat(p.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(p.name, p.online || p.id in relayOnline, 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (!p.paired) "[未配对] 请删除后重新扫码配对" else if (p.online) "[在线] ${p.host}" else if (p.id in relayOnline) "[在线·远程]" else "[离线] 上次在线 ${fmtListTime(p.lastSeen)}",
                                fontSize = 13.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        roleOf(p, live)?.let { RoleTag(it); Spacer(Modifier.width(2.dp)) }
                        Box {
                            IconButton(onClick = { menuFor = p.id }) { Icon(Icons.Default.MoreVert, "更多", tint = Gray) }
                            DropdownMenu(expanded = menuFor == p.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text("删除设备") }, onClick = { menuFor = null; confirmDel = p })
                            }
                        }
                    }
                }
            }
        }
    }
    confirmDel?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("删除设备") },
            text = { Text("确定删除\u201c${p.name}\u201d吗？和它的聊天记录会一起删除，以后要再用，需要重新扫码配对。") },
            confirmButton = { TextButton(onClick = { confirmDel = null; Hub.forgetPeer(p.id) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } }
        )
    }
}

// ---------------- 我：设置 ----------------

@Composable
fun MeTab() {
    Column(
        Modifier.fillMaxSize().background(ChatBg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) { SettingsContent() }
}

@Composable
fun SettingsContent() {
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
                Hub.report("无法使用该文件夹", e, "系统没有授予写入权限。请换一个文件夹（可以先在里面新建一个子文件夹），不要直接选存储根目录或“下载”文件夹本身")
            }
        }
    }
    // 离开设置时，让新的设备名称生效
    DisposableEffect(Unit) { onDispose { Hub.discovery?.restart() } }
    val ver = remember {
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName}（${pi.longVersionCode}）"
        } catch (e: Exception) { Hub.log("读取版本号", e); "（读取失败）" }
    }

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
    RelaySettingsCard()
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("错误记录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("程序里出过的错误（包括没有弹窗打扰你的次要错误）都记在这里。反馈问题时，点“查看”再点“复制”发给开发者。", fontSize = 12.sp, color = Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Hub.fail("错误记录", "最近的错误记录（最新的在最下面）", Hub.errorLogText()) }) { Text("查看") }
                OutlinedButton(onClick = { Hub.clearErrorLog(); Hub.toast("已清空") }) { Text("清空") }
            }
        }
    }
}
