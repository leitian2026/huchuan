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

/** 设置页里的“远程中转”：Cloudflare 地址 / 口令 + 坚果云账号，可测试、可在两台手机间复制配置 */
@Composable
fun RelaySettingsCard() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Store.relayOn) }
    var url by remember { mutableStateOf(Store.relayUrl) }
    var secret by remember { mutableStateOf(Store.relaySecret) }
    var davUrl by remember { mutableStateOf(Store.davUrl) }
    var davUser by remember { mutableStateOf(Store.davUser) }
    var davPass by remember { mutableStateOf(Store.davPass) }
    var davDir by remember { mutableStateOf(Store.davDir) }
    var busy by remember { mutableStateOf(false) }
    val state by Relay.state.collectAsState()

    fun save() {
        Store.relayOn = on
        Store.relayUrl = url.trim()
        Store.relaySecret = secret.trim()
        Store.davUrl = davUrl.trim()
        Store.davUser = davUser.trim()
        Store.davPass = davPass.trim()
        Store.davDir = davDir.trim().trim('/').ifEmpty { "hulian-relay" }
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
            .put("dn", davUser.trim()).put("dp", davPass.trim()).put("dd", davDir.trim())
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
            davUrl = j.getString("du")
            davUser = j.getString("dn")
            davPass = j.getString("dp")
            davDir = j.getString("dd")
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
                "两台手机不在同一个网络时，用 Cloudflare（通知谁在线、有新消息）+ 坚果云（存放加密内容）收发。" +
                    "内容在手机上加密，网盘和 Cloudflare 都看不到。两台手机要填同一份配置，能直连时仍然优先直连。",
                fontSize = 12.sp, color = Gray
            )
            Text("状态：$state", fontSize = 13.sp, color = if (state.startsWith("已连接")) Color(0xFF2E9E5B) else Gray)
            Field("Cloudflare 地址（https://xxx.workers.dev）", url) { url = it }
            Field("中转口令（部署时设置的 RELAY_SECRET）", secret, secretField = true) { secret = it }
            Field("坚果云 WebDAV 地址", davUrl) { davUrl = it }
            Field("坚果云账号（注册邮箱）", davUser) { davUser = it }
            Field("坚果云应用密码（不是登录密码）", davPass, secretField = true) { davPass = it }
            Field("网盘里用的文件夹名", davDir) { davDir = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(); Hub.toast("已保存") }) { Text("保存") }
                OutlinedButton(enabled = !busy, onClick = { test("测试 Cloudflare 失败", "Cloudflare 可用，口令正确") { Relay.testWorker() } }) { Text("测试 Cloudflare") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    test("测试坚果云失败", "坚果云可用（能建文件夹、上传、下载、删除）") {
                        WebDav.test(Store.dav ?: throw java.io.IOException("坚果云地址、账号、应用密码没有填全"))
                    }
                }) { Text("测试坚果云") }
                OutlinedButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("互传配置", exportCfg()))
                    Hub.toast("已复制。里面有口令和网盘密码，只粘贴到自己的设备上")
                }) { Text("复制配置") }
                OutlinedButton(onClick = { importCfg() }) { Text("粘贴配置") }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, secretField: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secretField) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
}
