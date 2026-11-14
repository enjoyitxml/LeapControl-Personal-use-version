package com.leapcontrol.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.journal.ChargeAnchor
import com.leapcontrol.app.journal.ChargeCorrection
import java.util.Locale

/**
 * 「手动修正上次充电」对话框。
 *
 * 整段充电期间一次都没打开过 App（插枪前没看、充完直接开走），这次充电就没被看见，
 * 「自上次充电行驶里程」会一直算到更早那次。车端 / 云端都没有充电记录接口可查，
 * 只有用户手里有这三个数：什么时候充满的、充到百分之多少、当时的表显总里程。
 *
 * 三个数一起校验、一起落盘；写出来的锚点带 `edited`，观测不会把它改回去
 * （见 `ChargeCycle.observe`）。预填现有值，用户往往只需要改其中一两格。
 *
 * 版式沿用 [BatteryKwhDialog]：居中 AlertDialog + 同一套输入框颜色，多行文字显式写
 * lineHeight（不写会继承 24sp，把小屏上的说明撑出对话框）。
 */
@Composable
fun ChargeCycleCorrectionDialog(
    anchor: ChargeAnchor?,
    currentKm: Double?,
    onSave: (ChargeCorrection.Values) -> Unit,
    onDismiss: () -> Unit,
) {
    val C = LocalAppColors.current

    // 已有起点就按它的时间预填；从没记过就落在「现在」。
    // 里程优先用已存起点（那是同一段的），其次才是当前读数。
    val initial = remember(anchor, currentKm) {
        val at = anchor?.at?.takeIf { it > 0L } ?: System.currentTimeMillis()
        Triple(
            ChargeCorrection.formatDate(at),
            ChargeCorrection.formatTime(at),
            (anchor?.km ?: currentKm)?.let { String.format(Locale.CHINA, "%.1f", it) }.orEmpty(),
        )
    }
    var dateText by remember { mutableStateOf(initial.first) }
    var timeText by remember { mutableStateOf(initial.second) }
    var percentText by remember { mutableStateOf(anchor?.socPeak?.toString().orEmpty()) }
    var kmText by remember { mutableStateOf(initial.third) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.panelBg,
        title = {
            Text(
                "修正上次充电",
                color = C.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "结束充电时没打开 App，这次充电就可能没记上。照着车机把三个数填进来，" +
                        "首页的行驶里程会立刻按它们重算。",
                    color = C.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CorrectionField(
                        value = dateText,
                        onValueChange = { dateText = it; error = null },
                        label = "充电日期",
                        placeholder = "2026-11-10",
                        keyboard = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    CorrectionField(
                        value = timeText,
                        onValueChange = { timeText = it; error = null },
                        label = "充满时间",
                        placeholder = "22:30",
                        keyboard = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val now = System.currentTimeMillis()
                        dateText = ChargeCorrection.formatDate(now)
                        timeText = ChargeCorrection.formatTime(now)
                        error = null
                    }) {
                        Text("用当前时间", color = C.accent, fontSize = 11.sp)
                    }
                }

                CorrectionField(
                    value = percentText,
                    onValueChange = { percentText = it; error = null },
                    label = "充到了百分之多少（%）",
                    placeholder = "不知道就留空，如 90",
                    keyboard = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                CorrectionField(
                    value = kmText,
                    onValueChange = { kmText = it; error = null },
                    label = "充电时车辆总里程（km）",
                    placeholder = "仪表盘上的总里程，如 12345.6",
                    keyboard = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = error
                        ?: "保存后不会被自动改写；下次真正充一次电会恢复自动记录。",
                    color = if (error != null) AlertRed else C.textSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val parsed = ChargeCorrection.build(dateText, timeText, percentText, kmText)) {
                    is ChargeCorrection.Parse.Err ->
                        error = ChargeCorrection.errorText(parsed.field)
                    is ChargeCorrection.Parse.Ok -> onSave(parsed.values)
                }
            }) {
                Text("保存", color = C.accent, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = C.textSecondary, fontSize = 14.sp)
            }
        },
    )
}

/** 四个输入框共用一套外观，避免每处重复配色。 */
@Composable
private fun CorrectionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
) {
    val C = LocalAppColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label, color = C.textSecondary, fontSize = 11.sp, maxLines = 1) },
        placeholder = { Text(placeholder, color = C.textSecondary, fontSize = 10.5.sp, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.textPrimary,
            unfocusedTextColor = C.textPrimary,
            cursorColor = C.accent,
            focusedBorderColor = C.accent,
            unfocusedBorderColor = C.border,
        ),
    )
}
