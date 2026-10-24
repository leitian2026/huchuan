package com.hulian.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    var doh by remember { mutableStateOf(Store.dohUrl) }
    var hostsOn by remember { mutableStateOf(Store.hostsOn) }
    var hosts by remember { mutableStateOf(Store.hostsText) }
    var dohOn by remember { mutableStateOf(Store.dohOn) }
    var dohFb by remember { mutableStateOf(Store.dohFallback) }
    var cfSys by remember { mutableStateOf(Store.cfSysDns) }
    var davSys by remember { mutableStateOf(Store.davSysDns) }
    var dnsInfo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val state by Relay.state.collectAsState()

    fun save() {
        Store.davProvider = prov
        Store.relayOn = on
        Store.relayUrl = url.trim()
        Store.relaySecret = secret.trim()
        Store.davUrl = davUrl.trim()
        Store.davUser = davUser.trim()
        Store.davPass = davPass.trim()
        Store.davDir = davDir.trim().trim('/').ifEmpty { "hulian-relay" }
        Store.dohUrl = doh.trim()
        Store.hostsText = hosts.trim()
        Store.hostsOn = hostsOn
        Store.dohFallback = dohFb
        Store.dohOn = dohOn
        Store.cfSysDns = cfSys
        Store.davSysDns = davSys
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
            .put("dh", doh.trim()).put("hs", hosts.trim()).put("df", dohFb).put("do", dohOn).put("ho", hostsOn)
            .put("cs", cfSys).put("ds", davSys)
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
            if (j.has("df")) dohFb = j.getBoolean("df")
            // 旧版本导出的配置没有开关：填了 DoH 地址就当开着，和升级时的规则一致
            if (j.has("do")) dohOn = j.getBoolean("do") else if (j.has("dh") && j.getString("dh").isNotBlank()) dohOn = true
            // 旧版本导出的配置没有这两个开关：保持本机原样
            if (j.has("cs")) cfSys = j.getBoolean("cs")
            if (j.has("ds")) davSys = j.getBoolean("ds")
            on = true
            save()
            Hub.toast("已导入并保存")
        } catch (e: Exception) {
            Hub.report("导入配置失败", e)
        }
    }

    val cur = DavProviders.get(prov)
    // DoH 只给网盘用；网盘选了“使用系统 DNS”时 DoH 不生效，也就不检查它（免得界面上看不见的东西挡住保存）
    val dohEff = dohOn && !davSys
    val dohCount = if (dohEff) doh.lines().count { it.isNotBlank() } else 0
    val hostCount = if (hostsOn) hosts.lines().count { it.substringBefore('#').isNotBlank() } else 0
    val dnsSummary = if (!hostsOn) "Host 映射关" else if (hostCount == 0) "未配置" else "Host $hostCount 条"
    val davDnsSummary = if (davSys) "系统 DNS" else if (dohEff) "DoH $dohCount 个" else ""
    // 两路都用系统 DNS 时，Host 映射谁也不用，格式问题不挡保存
    val hostsErr = if (hostsOn && !(cfSys && davSys)) NetDns.parseHosts(hosts).error else null
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
        Text("状态：$state", fontSize = 13.sp, color = if (state.startsWith("已连接")) Color(0xFF2E9E5B) else Gray)
        Field("Cloudflare 地址（域名或 xxx.workers.dev）", url) { url = it }
        Field("中转口令（RELAY_SECRET）", secret, secretField = true) { secret = it }

        SwitchRow("Cloudflare 使用系统 DNS", cfSys, "开着时连 Cloudflare 不用 Host 映射，直接用系统 DNS") { cfSys = it }

        FoldSection("域名解析", dnsSummary) {
            Text(
                "Host 映射：给域名指定 IP，填了的域名直接连这个 IP，不查 DNS，连不上自动换下一个。Cloudflare 和网盘共用这一份；" +
                    "哪一路开了“使用系统 DNS”，那一路就不用它。没有映射的域名用系统 DNS（网盘可另开自建 DoH，在“网盘”里设置）。",
                fontSize = 12.sp, color = Gray
            )
            SwitchRow("使用 Host 映射", hostsOn, "关闭时下面填的内容保留但不使用，所有域名都不再用写死的 IP") { hostsOn = it }
            if (hostsOn) Field("Host 映射（每行：域名 IP [IP…]）", hosts, lines = 3) { hosts = it }
            hostsErr?.let { Text(it, fontSize = 12.sp, color = Color(0xFFE5484D)) }
        }

        FoldSection("网盘", cur.name + " · " + davDir + (if (davDnsSummary.isEmpty()) "" else " · $davDnsSummary"), startOpen = davUser.isBlank() || davPass.isBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DavProviders.all.forEach { p ->
                    FilterChip(selected = prov == p.id, onClick = { switchProv(p.id) }, label = { Text(p.name) })
                }
            }
            Text(cur.note + "两台手机要选同一家、填同一个账号。", fontSize = 12.sp, color = Gray)
            Field(cur.urlLabel, davUrl) { davUrl = it }
            Field(cur.userLabel, davUser) { davUser = it }
            Field(cur.passLabel, davPass, secretField = true) { davPass = it }
            Field("网盘里用的文件夹名", davDir) { davDir = it }

            SwitchRow("网盘使用系统 DNS", davSys, "开着时连网盘不用 Host 映射，也不用自建 DoH，直接用系统 DNS") { davSys = it }
            if (!davSys) {
                Text(
                    "自建 DoH（可选，默认关）：只用来解析没有映射的网盘域名。只防 DNS 被改，握手时的域名（SNI）仍是明文。",
                    fontSize = 12.sp, color = Gray
                )
                SwitchRow("使用自建 DoH", dohOn, "关闭时没有映射的网盘域名用系统 DNS；填的 DoH 地址会保留") { dohOn = it }
                if (dohOn) {
                    Field("自建 DoH（https://…，一行一个）", doh, lines = 2) { doh = it }
                    SwitchRow("DoH 失败时回退系统 DNS", dohFb, "默认关：关闭时 DoH 不可用就连不上；打开等于放回可能被劫持的系统 DNS") { dohFb = it }
                }
                dohErr?.let { Text(it, fontSize = 12.sp, color = Color(0xFFE5484D)) }
                dohWarn?.let { Text("提醒：$it", fontSize = 12.sp, color = Color(0xFFE08A00)) }
            }
        }

        HorizontalDivider(color = Color(0xFFEDEFF3))
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
        Row(horizontalArrangement = BtnRowGap) {
            ActionBtn("测试中转", enabled = !busy) { test("测试 Cloudflare 失败", "Cloudflare 可用，口令正确") { Relay.testWorker() } }
            ActionBtn("测试网盘", enabled = !busy) {
                val n = Store.davName
                test("测试${n}失败", "${n}可用（能建文件夹、上传、下载、删除）") {
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
        if (dnsInfo.isNotEmpty()) Text(dnsInfo, fontSize = 12.sp, color = Gray)
    }
}

@Composable
private fun Field(label: String, value: String, secretField: Boolean = false, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        visualTransformation = if (secretField) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 从 Cloudflare 地址里取出域名（可带或不带 https://，可带路径） */
private fun hostOf(u: String): String = u.trim().substringAfter("://").substringBefore('/').substringBefore(':').trim()
