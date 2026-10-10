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
    var hosts by remember { mutableStateOf(Store.hostsText) }
    var dohFb by remember { mutableStateOf(Store.dohFallback) }
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
        Store.dohFallback = dohFb
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
            .put("dh", doh.trim()).put("hs", hosts.trim()).put("df", dohFb)
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
            if (j.has("df")) dohFb = j.getBoolean("df")
            on = true
            save()
            Hub.toast("已导入并保存")
        } catch (e: Exception) {
            Hub.report("导入配置失败", e)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("远程中转", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { on = it; save() })
            }
            Text(
                "两台手机不在同一个网络时，用 Cloudflare（通知谁在线、有新消息）+ 网盘（存放加密内容）收发。" +
                    "内容在手机上加密，网盘和 Cloudflare 都看不到。两台手机要填同一份配置，能直连时仍然优先直连。",
                fontSize = 12.sp, color = Gray
            )
            Text("状态：$state", fontSize = 13.sp, color = if (state.startsWith("已连接")) Color(0xFF2E9E5B) else Gray)
            Field("Cloudflare 地址（https://xxx.workers.dev）", url) { url = it }
            Field("中转口令（部署时设置的 RELAY_SECRET）", secret, secretField = true) { secret = it }
            Text("域名解析（防 DNS 劫持）", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                "先查 Host 映射，查不到再问自建 DoH，两项都不填就用系统 DNS。对 Cloudflare 和网盘都生效。" +
                    "注意：DoH 只能防 DNS 被改，握手时的域名（SNI）仍然是明文。",
                fontSize = 12.sp, color = Gray
            )
            Field("自建 DoH 地址（https://…，可填多个，一行一个）", doh, lines = 2) { doh = it }
            Field("Host 映射（每行：域名 IP [IP…]，可填多个 IP）", hosts, lines = 3) { hosts = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DoH 失败时回退到系统 DNS", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = dohFb, onCheckedChange = { dohFb = it })
            }
            Text("默认关闭：回退等于把可能被劫持的系统 DNS 放回来；关闭时 DoH 不可用就连不上。", fontSize = 12.sp, color = Gray)
            val cur = DavProviders.get(prov)
            Text("网盘", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val err = NetDns.parseHosts(hosts).error
                    if (err != null) Hub.toast(err) else { save(); Hub.toast("已保存") }
                }) { Text("保存") }
                OutlinedButton(enabled = !busy, onClick = { test("测试 Cloudflare 失败", "Cloudflare 可用，口令正确") { Relay.testWorker() } }) { Text("测试 Cloudflare") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    val err = NetDns.parseHosts(hosts).error
                    val h = hostOf(url)
                    if (err != null) { Hub.toast(err); return@OutlinedButton }
                    if (h.isEmpty()) { Hub.toast("先填 Cloudflare 地址"); return@OutlinedButton }
                    save()
                    busy = true
                    dnsInfo = "正在解析 $h …"
                    Hub.scope.launch {
                        dnsInfo = try { NetDns.explain(h) } catch (e: Exception) { "失败：" + friendlyError(e) }
                        busy = false
                    }
                }) { Text("测试解析") }
            }
            if (dnsInfo.isNotEmpty()) Text(dnsInfo, fontSize = 12.sp, color = Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    val n = Store.davName
                    test("测试${n}失败", "${n}可用（能建文件夹、上传、下载、删除）") {
                        WebDav.test(Store.dav ?: throw java.io.IOException("${Store.davName}的地址、账号、密码没有填全"))
                    }
                }) { Text("测试网盘") }
                OutlinedButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("互传配置", exportCfg()))
                    Hub.toast("已复制。里面有口令、网盘密码和 DoH 地址，只粘贴到自己的设备上")
                }) { Text("复制配置") }
                OutlinedButton(onClick = { importCfg() }) { Text("粘贴配置") }
            }
        }
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
