package com.hulian.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 设置页里的“远程中转”：Cloudflare 地址 / 口令 + 网盘（坚果云 / InfiniCLOUD / 自定义）账号，可测试、可在两台手机间复制配置 */
@Composable
fun RelaySettingsCard() {
    // 设置被“新旧手机同步”改过时，重新读取一遍，免得页面里的旧值再被保存回去
    val rev by Hub.cfgRev.collectAsState()
    key(rev) { RelaySettingsCardBody() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelaySettingsCardBody() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Store.relayOn) }
    var url by remember { mutableStateOf(Store.relayUrl) }
    var secret by remember { mutableStateOf(Store.relaySecret) }
    var prov by remember { mutableStateOf(Store.davProvider) }
    var davUrl by remember { mutableStateOf(Store.davUrl) }
    var davUser by remember { mutableStateOf(Store.davUser) }
    var davPass by remember { mutableStateOf(Store.davPass) }
    var davDir by remember { mutableStateOf(Store.davDir) }
    var headExt by remember { mutableStateOf(Store.relayHeadExt) }
    var doh by remember { mutableStateOf(Store.dohUrl) }
    var hostsOn by remember { mutableStateOf(Store.hostsOn) }
    var hosts by remember { mutableStateOf(Store.hostsText) }
    var dohOn by remember { mutableStateOf(Store.dohOn) }
    var cfSys by remember { mutableStateOf(Store.cfSysDns) }
    var davSys by remember { mutableStateOf(Store.davSysDns) }
    var dnsInfo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val state by Relay.state.collectAsState()
    var parText by remember { mutableStateOf(Store.relayParallel.toString()) }
    var quotaOn by remember { mutableStateOf(Store.quotaOn) }
    var qUp by remember { mutableStateOf(Store.quotaUpMb.toString()) }
    var qDown by remember { mutableStateOf(Store.quotaDownMb.toString()) }
    var qReq by remember { mutableStateOf(Store.quotaReq.toString()) }
    var qDay by remember { mutableStateOf(Store.quotaResetDay.toString()) }
    var jgyCookie by remember { mutableStateOf(Store.davCookie) }
    var showLogin by remember { mutableStateOf(false) }
    var calUp by remember { mutableStateOf("") }
    var calDown by remember { mutableStateOf("") }
    val quota by DavQuota.snap.collectAsState()

    fun save() {
        Store.davProvider = prov
        Store.relayOn = on
        Store.relayUrl = url.trim()
        Store.relaySecret = secret.trim()
        Store.davUrl = davUrl.trim()
        Store.davUser = davUser.trim()
        Store.davPass = davPass.trim()
        Store.davDir = davDir.trim().trim('/').ifEmpty { "hulian-relay" }
        Store.relayHeadExt = headExt.trim().trimStart('.').lowercase().takeIf { Relay.headExtOk(it) } ?: "dat"
        Store.dohUrl = doh.trim()
        Store.hostsText = hosts.trim()
        Store.hostsOn = hostsOn
        Store.dohOn = dohOn
        Store.cfSysDns = cfSys
        Store.davSysDns = davSys
        Store.relayParallel = parText.toIntOrNull() ?: 3
        Store.quotaOn = quotaOn
        Store.quotaUpMb = qUp.toIntOrNull() ?: 1024
        Store.quotaDownMb = qDown.toIntOrNull() ?: 3072
        Store.quotaReq = qReq.toIntOrNull() ?: 600
        Store.quotaResetDay = qDay.toIntOrNull() ?: 1
        Store.davCookie = jgyCookie.trim()
        DavQuota.refresh()
        NetDns.reset()
        WebDav.forgetDirs()
        Relay.restart()
    }

    /** 换一家网盘：先把当前填的存到当前这家名下，再读出新那家之前填过的（每家各记一套，切换不会丢） */
    fun switchProv(np: String) {
        if (np == prov) return
        save()
        Store.davProvider = np
        prov = np
        davUrl = Store.davUrl
        davUser = Store.davUser
        davPass = Store.davPass
        davDir = Store.davDir
        headExt = Store.relayHeadExt
        WebDav.forgetDirs()
        Relay.restart()
    }

    fun test(title: String, okMsg: String, block: () -> Unit) {
        save()
        busy = true
        Hub.scope.launch {
            try {
                block()
                Hub.toast(okMsg)
            } catch (e: Exception) {
                Hub.report(title, e)
            } finally {
                busy = false
            }
        }
    }

    fun exportCfg(): String {
        val j = JSONObject().put("u", url.trim()).put("s", secret.trim()).put("du", davUrl.trim())
            .put("dn", davUser.trim()).put("dp", davPass.trim()).put("dd", davDir.trim()).put("dv", prov)
            .put("dh", doh.trim()).put("hs", hosts.trim()).put("do", dohOn).put("ho", hostsOn)
            .put("cs", cfSys).put("ds", davSys).put("qd", qDay.toIntOrNull() ?: 1)
        return "HLC1:" + Base64.encodeToString(j.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun importCfg() {
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
            if (!t.startsWith("HLC1:")) throw IllegalArgumentException("剪贴板里没有互传的配置。请先在另一台手机上点“复制配置”")
            val j = JSONObject(String(Base64.decode(t.removePrefix("HLC1:"), Base64.URL_SAFE), Charsets.UTF_8))
            url = j.getString("u")
            secret = j.getString("s")
            // 旧版本导出的配置没有网盘类型，一律当坚果云
            val np = j.optString("dv", "jgy").takeIf { p -> DavProviders.all.any { it.id == p } } ?: "jgy"
            Store.davProvider = np
            prov = np
            davUrl = j.getString("du")
            davUser = j.getString("dn")
            davPass = j.getString("dp")
            davDir = j.getString("dd")
            // 旧版本导出的配置没有域名解析设置：保持本机原有的不动
            if (j.has("dh")) doh = j.getString("dh")
            if (j.has("hs")) hosts = j.getString("hs")
            if (j.has("ho")) hostsOn = j.getBoolean("ho")   // 旧配置没有这个开关：保持本机原样
            // 旧版本导出的配置没有开关：填了 DoH 地址就当开着，和升级时的规则一致
            if (j.has("do")) dohOn = j.getBoolean("do") else if (j.has("dh") && j.getString("dh").isNotBlank()) dohOn = true
            // 旧版本导出的配置没有这两个开关：保持本机原样
            if (j.has("cs")) cfSys = j.getBoolean("cs")
            if (j.has("ds")) davSys = j.getBoolean("ds")
            if (j.has("qd")) qDay = j.getInt("qd").toString()
            on = true
            save()
            Hub.toast("已导入并保存")
        } catch (e: Exception) {
            Hub.report("导入配置失败", e)
        }
    }

    val cur = DavProviders.get(prov)
    // DoH 只给网盘用
    val dohEff = dohOn
    val dohCount = if (dohEff) doh.lines().count { it.isNotBlank() } else 0
    val hostCount = if (hostsOn) hosts.lines().count { it.substringBefore('#').isNotBlank() } else 0
    val dnsSummary = if (!hostsOn) "Host 映射关" else if (hostCount == 0) "未配置" else "Host $hostCount 条"
    val davDnsSummary = (if (dohEff) "DoH $dohCount 个" else "DoH 关") + " · " + (if (davSys) "系统 DNS 开" else "系统 DNS 关")
    val hostsErr = if (hostsOn) NetDns.parseHosts(hosts).error else null
    val dohErr = if (dohEff) NetDns.dohError(doh) else null
    val dohWarn = if (dohEff && dohErr == null && hostsErr == null) NetDns.dohWarning(doh, if (hostsOn) hosts else "") else null

    /** 保存前的检查：中转和网盘只许 https；DoH、Host 映射格式要对。返回第一个错误，没有返回 null */
    fun settingsError(): String? =
        UrlRules.relayError(url) ?: UrlRules.davError(davUrl, DavProviders.get(prov).name) ?: dohErr ?: hostsErr

    SettingsCard {
        CardTitle(
            "远程中转",
            help = "两台手机不在同一个网络时，用 Cloudflare（通知谁在线、有新消息）+ 网盘（存放加密内容）收发。" +
                "内容在手机上加密，网盘和 Cloudflare 都看不到。两台手机要填同一份配置，能直连时仍然优先直连。",
            trailing = { Switch(checked = on, onCheckedChange = { on = it; save() }) }
        )
        StatusLine("状态：$state", state.startsWith("已连接"))

        SubTitle("Cloudflare")
        Field("Cloudflare 地址（域名或 xxx.workers.dev）", url) { url = it }
        Field("中转口令（RELAY_SECRET）", secret, secretField = true) { secret = it }

        SubTitle("高级设置")
        FoldSection("域名解析", dnsSummary) {
            NoteText(
                "Host 映射：给域名指定 IP，填了的域名直接连这个 IP，不查 DNS，连不上自动换下一个。Cloudflare 和网盘共用这一份；" +
                    "没有映射的域名：Cloudflare 看“使用系统 DNS”开关；网盘先试自建 DoH（在“网盘”里设置），再看开关。开关关着就连不上。"
            )
            SubTitle("Host 映射")
            SwitchRow("使用 Host 映射", hostsOn, "关闭时下面填的内容保留但不使用，所有域名都不再用写死的 IP") { hostsOn = it }
            if (hostsOn) Field("Host 映射（每行：域名 IP [IP…]）", hosts, lines = 3) { hosts = it }
            hostsErr?.let { Text(it, fontSize = 12.sp, color = Color(0xFFE5484D)) }
            SubTitle("没有映射的域名")
            SwitchRow("Cloudflare 使用系统 DNS", cfSys, "关闭后只认 Host 映射，没有映射的域名直接连不上，不会用系统 DNS") { cfSys = it }
        }

        FoldSection("网盘", cur.name + " · " + davDir + (if (davDnsSummary.isEmpty()) "" else " · $davDnsSummary"), startOpen = davUser.isBlank() || davPass.isBlank()) {
            // 手机屏幕窄，三个选项一行放不下会把最后一个（自定义）挤出屏幕又划不动：放不下就换行
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DavProviders.all.forEach { p ->
                    FilterChip(selected = prov == p.id, onClick = { switchProv(p.id) }, label = { Text(p.name) })
                }
            }
            NoteText(cur.note + "两台手机要选同一家、填同一个账号。")
            SubTitle("账号")
            Field(cur.urlLabel, davUrl) { davUrl = it }
            Field(cur.userLabel, davUser) { davUser = it }
            Field(cur.passLabel, davPass, secretField = true) { davPass = it }
            Field("网盘里用的文件夹名", davDir) { davDir = it }
            SubTitle("消息文件后缀")
            NoteText(
                "消息在网盘里的文件名后缀（不含点）。个别网盘对某些后缀有问题（比如 Koofr 读不出 .bin），遇到收不到时换一个，再点“测试网盘”确认。" +
                    "只影响本机发送；收件时不看这个设置，自动认任何后缀，两台手机可以不一样。"
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("dat", "tmp", "enc", "msg", "hl", "txt").forEach { x ->
                    FilterChip(selected = headExt == x, onClick = { headExt = x }, label = { Text(".$x") })
                }
            }
            Field("自定义后缀（1～8 位小写字母或数字，默认 dat）", headExt) {
                headExt = it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' }.take(8)
            }
            if (headExt.isNotEmpty() && !Relay.headExtOk(headExt)) {
                Text("这个后缀不能用（prt 是分段文件专用的），保存时会改回 dat", fontSize = 12.sp, color = Color(0xFFE5484D))
            }
            SubTitle("传输速度")
            Field("并行数（大文件同时传 / 收几段，1～8，默认 3）", parText) { parText = it.filter { c -> c.isDigit() }.take(1) }
            NoteText("并行数越大越能叠加速度，但请求次数也烧得更快；坚果云没有公开并发上限，超出请求次数会被临时封号数小时。建议 3～4。")

            SubTitle("域名解析（可选）")
            NoteText("自建 DoH（默认关）：只用来解析没有映射的网盘域名。只防 DNS 被改，握手时的域名（SNI）仍是明文。")
            SwitchRow("使用自建 DoH", dohOn, "关闭时没有映射的网盘域名交给下面的系统 DNS；填的 DoH 地址会保留") { dohOn = it }
            if (dohOn) Field("自建 DoH（https://…，一行一个）", doh, lines = 2) { doh = it }
            SwitchRow("网盘使用系统 DNS", davSys, "开着：没有映射、DoH 没开或没解析出来时用系统 DNS。关闭后只认 Host 映射和 DoH，都不行就连不上，不会用系统 DNS") { davSys = it }
            dohErr?.let { Text(it, fontSize = 12.sp, color = Color(0xFFE5484D)) }
            dohWarn?.let { Text("提醒：$it", fontSize = 12.sp, color = Color(0xFFE08A00)) }
        }

        if (prov == "jgy") {
            val q = quota
            FoldSection(
                "额度保护（坚果云）",
                if (!quotaOn) "已关" else if (q == null) "开" else "上传剩 ${fmtSize((q.upLimit - q.upUsed).coerceAtLeast(0L))} · 下载剩 ${fmtSize((q.downLimit - q.downUsed).coerceAtLeast(0L))}"
            ) {
                NoteText(
                    "额度是整个账号共用的：本机数自己的用量，对方的用量由对方在通知里带过来，加起来才是总用量。" +
                        "网页端上传、别的 WebDAV 软件用掉的，本机数不到，可以在下面“校准”。每月 1 日按本机时间清零。"
                )
                SubTitle("额度设置")
                SwitchRow("快到上限自动暂停", quotaOn, "请求次数用到上限的 90%、或剩下的月流量不够传下一段时，自动暂停并在聊天里写明原因") { quotaOn = it }
                Field("流量刷新日（每月几号，1～31；两台手机要填一样的）", qDay) { qDay = it.filter { c -> c.isDigit() }.take(2) }
                NoteText("当前周期从 ${DavQuota.cycleStart()} 开始，到下一个刷新日自动清零。改刷新日不会清掉已有的用量。")
                Row(horizontalArrangement = BtnRowGap) {
                    ActionBtn("马上重置本月用量") {
                        DavQuota.resetNow()
                        Hub.toast("已重置：本机的用量和校准清零，对方之前报的用量也不再算")
                    }
                }
                Field("每月上传额度（MB，免费版 1024）", qUp) { qUp = it.filter { c -> c.isDigit() }.take(7) }
                Field("每月下载额度（MB，免费版 3072）", qDown) { qDown = it.filter { c -> c.isDigit() }.take(7) }
                Field("每 30 分钟请求上限（免费版 600，付费版 1500）", qReq) { qReq = it.filter { c -> c.isDigit() }.take(5) }
                fun syncNow() {
                    save()
                    busy = true
                    Hub.scope.launch {
                        try {
                            val msg = DavQuota.syncFromWeb()
                            qUp = Store.quotaUpMb.toString()
                            qDown = Store.quotaDownMb.toString()
                            qReq = Store.quotaReq.toString()
                            qDay = Store.quotaResetDay.toString()
                            Hub.toast(msg)
                        } catch (e: Exception) {
                            Hub.toast("同步失败：" + friendlyError(e))
                        }
                        busy = false
                    }
                }
                SubTitleWithHelp(
                    "同步真实用量",
                    "点“登录坚果云”，在弹出的坚果云官方登录页里登录（验证码、短信验证都在官方页面里完成，密码不经过本 App 的输入框），" +
                        "登录成功后登录凭证（Cookie）自动取回、加密保存在本机，并马上同步一次；取完会清掉内置网页里的登录状态。" +
                        "凭证过期了再点一次。也可以手动粘贴：电脑浏览器登录坚果云，按 F12 → 网络 → 点开一条 jianguoyun.com 的请求 → 复制“请求标头”里 Cookie 那一整串。" +
                        "凭证不会被“复制配置”带走，只在点按钮时请求一次。同步后额度、本周期已用、刷新日都按坚果云的真实数据改好。两台手机各自点一次。"
                )
                Field("坚果云网页 Cookie（登录后自动填入，也可手动粘贴）", jgyCookie, secretField = true) { jgyCookie = it }
                Row(horizontalArrangement = BtnRowGap) {
                    ActionBtn("登录坚果云", enabled = !busy) { showLogin = true }
                    ActionBtn("同步真实用量", enabled = !busy) { syncNow() }
                }
                if (showLogin) {
                    JgyLoginDialog { cookie ->
                        showLogin = false
                        if (cookie != null) {
                            jgyCookie = cookie
                            syncNow()
                        }
                    }
                }
                SubTitle("手动校准")
                NoteText("也可以手动填坚果云 App 里看到的本月已用流量（MB），留空的不改")
                Field("本月已用上传（MB）", calUp) { calUp = it.filter { c -> c.isDigit() }.take(7) }
                Field("本月已用下载（MB）", calDown) { calDown = it.filter { c -> c.isDigit() }.take(7) }
                Row(horizontalArrangement = BtnRowGap) {
                    ActionBtn("校准用量") {
                        save()
                        DavQuota.calibrate(calUp.toLongOrNull(), calDown.toLongOrNull())
                        Hub.toast("已校准")
                    }
                }
            }
        }

        HorizontalDivider(color = LineColor, modifier = Modifier.padding(top = 4.dp))
        SubTitle("配置")
        Row(horizontalArrangement = BtnRowGap) {
            ActionBtn("保存", filled = true) {
                val err = settingsError()
                if (err != null) Hub.toast(err) else { save(); Hub.toast(if (dohWarn != null) "已保存。注意：$dohWarn" else "已保存") }
            }
            ActionBtn("复制配置") {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("互传配置", exportCfg()))
                Hub.toast("已复制。里面有口令、网盘密码和 DoH 地址，只粘贴到自己的设备上")
            }
            ActionBtn("粘贴配置") { importCfg() }
        }
        SubTitle("检测与维护")
        Row(horizontalArrangement = BtnRowGap) {
            ActionBtn("测试中转", enabled = !busy) { test("测试 Cloudflare 失败", "Cloudflare 可用，口令正确") { Relay.testWorker() } }
            ActionBtn("测试网盘", enabled = !busy) {
                val n = Store.davName
                test("测试${n}失败", "${n}可用（根文件夹和子文件夹里都能上传、下载、删除）") {
                    WebDav.test(Store.dav ?: throw java.io.IOException("${Store.davName}的地址、账号、密码没有填全"))
                }
            }
            ActionBtn("测试解析", enabled = !busy) {
                val err = settingsError()
                val h = hostOf(url)
                val dh = hostOf(davUrl)
                if (err != null) { Hub.toast(err); return@ActionBtn }
                if (h.isEmpty() && dh.isEmpty()) { Hub.toast("先填 Cloudflare 或网盘地址"); return@ActionBtn }
                save()
                busy = true
                dnsInfo = "正在解析 …"
                Hub.scope.launch {
                    fun one(name: String, host: String, chan: NetDns.Chan): String? =
                        if (host.isEmpty()) null
                        else name + "：" + try { NetDns.explain(host, chan) } catch (e: Exception) { "失败：" + friendlyError(e) }
                    dnsInfo = listOfNotNull(one("Cloudflare", h, NetDns.Chan.CF), one("网盘", dh, NetDns.Chan.DAV)).joinToString("\n")
                    busy = false
                }
            }
        }
        Row(horizontalArrangement = BtnRowGap) {
            ActionBtn("清理网盘残留", enabled = !busy) {
                save()
                busy = true
                Hub.scope.launch {
                    try {
                        Hub.toast(Relay.cleanupNow())
                    } catch (e: Exception) {
                        Hub.toast("清理失败：" + friendlyError(e))
                    }
                    busy = false
                }
            }
            EmptySlot()
            EmptySlot()
        }
        if (dnsInfo.isNotEmpty()) InfoBox(dnsInfo)
    }
}

@Composable
private fun Field(label: String, value: String, secretField: Boolean = false, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        visualTransformation = if (secretField) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 从 Cloudflare 地址里取出域名（可带或不带 https://，可带路径） */
private fun hostOf(u: String): String = u.trim().substringAfter("://").substringBefore('/').substringBefore(':').trim()
