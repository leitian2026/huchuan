package com.hulian.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置页里的“传输方式”：同一 Wi-Fi、热点各一个开关，和下面的“远程中转”开关互相独立，需要哪个开哪个 */
@Composable
fun TransportSettingsCard() {
    var lan by remember { mutableStateOf(Store.lanOn) }
    var hs by remember { mutableStateOf(Store.hotspotOn) }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("传输方式", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("需要哪个打开哪个。关掉的方式不会在后台工作，也不会影响其他方式；远程中转在下面单独设置。", fontSize = 12.sp, color = Gray)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同一 Wi-Fi", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("两台手机连着同一个 Wi-Fi 时，自动发现并直连。关闭后不再做局域网发现，扫码配对也不用 Wi-Fi 地址。", fontSize = 12.sp, color = Gray)
                }
                Switch(checked = lan, onCheckedChange = {
                    lan = it
                    Store.lanOn = it
                    Hub.discovery?.restart()
                    Hub.probeKnown()
                })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("热点", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("没有共同 Wi-Fi 时，点开对话框自动用 Wi-Fi Direct / 临时热点连接，也用于扫码配对。关闭后不会自动开 Wi-Fi、定位或热点。", fontSize = 12.sp, color = Gray)
                }
                Switch(checked = hs, onCheckedChange = {
                    hs = it
                    Store.hotspotOn = it
                    if (it) AutoLink.kick() else AutoLink.hotspotDisabled()
                })
            }

            if (!lan && !hs) {
                Text("同一 Wi-Fi 和热点都关着，现在只有远程中转能传。", fontSize = 12.sp, color = Color(0xFFE5484D))
            }
        }
    }
}
