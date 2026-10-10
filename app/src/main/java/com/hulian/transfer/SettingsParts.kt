package com.hulian.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* 设置页的公共小部件：统一卡片、可折叠的说明、可折叠的分组、等宽按钮、开关行。
   设置页每张卡片都用这几个，间距、字号、按钮宽度才能一致。 */

/** 设置页统一的卡片：白底，内边距和行距统一，比之前紧凑 */
@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** 卡片标题。help 不为空时标题旁边有“说明”，点一下展开 / 收起灰色的解释文字；trailing 放右侧（比如开关） */
@Composable
fun CardTitle(title: String, help: String? = null, trailing: (@Composable () -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (help != null) {
            Text(
                if (open) "收起说明" else "说明",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
    if (help != null && open) Text(help, fontSize = 12.sp, color = Gray, lineHeight = 17.sp)
}

/** 可折叠的分组：一行标题 + 摘要 + ▾，点一下展开里面的内容。startOpen 只在第一次显示时生效 */
@Composable
fun FoldSection(title: String, summary: String, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    HorizontalDivider(color = Color(0xFFEDEFF3))
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            summary, fontSize = 12.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Text(if (open) "▴" else "▾", fontSize = 14.sp, color = Gray)
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** 一行开关：左边名字（可带一行小字），右边开关 */
@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Gray)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 等宽按钮：放在 Row 里平分宽度，同一行的按钮一样宽、一样高；filled 为主按钮 */
@Composable
fun RowScope.ActionBtn(text: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 6.dp)
    if (filled) {
        Button(onClick = onClick, modifier = Modifier.weight(1f), enabled = enabled, contentPadding = pad) {
            Text(text, fontSize = 14.sp, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f), enabled = enabled, contentPadding = pad) {
            Text(text, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** 按钮行里的空位，让只有一两个按钮的行和别的行对齐 */
@Composable
fun RowScope.EmptySlot() {
    Spacer(Modifier.weight(1f))
}

/** 一行按钮的统一间距 */
val BtnRowGap = Arrangement.spacedBy(8.dp)
