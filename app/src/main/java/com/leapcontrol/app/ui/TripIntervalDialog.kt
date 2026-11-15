package com.leapcontrol.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.trip.TripSampling

/**
 * 采样间隔选择（记录期间后台读车况的频率）。
 *
 * 2026-11-12 用户定：30 秒 / 45 秒 / 1 / 2 / 3 / 5 分钟，默认 45 秒；
 * 2026-11-15 追加 10 秒 / 15 秒 / 20 秒三档（默认仍是 45 秒）。
 * 每一档都直接写清代价与收益，不让用户去猜「45 秒和 5 分钟差在哪」。
 *
 * 九档之后列表会超过一屏：选项区自己可滚，标题与底部说明留在原位 ——
 * 否则小屏上「完成」按钮会被挤出屏幕（这个对话框的按钮是唯一出口）。
 */
@Composable
fun SampleIntervalDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val C = LocalAppColors.current
    val picked = TripSampling.normalize(current)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.panelBg,
        title = { Text("采样间隔", color = C.textPrimary) },
        text = {
            Column {
                Text(
                    "记录期间零控会在后台按这个间隔读一次车辆位置，锁屏、切到别的 App 都照采。 " +
                        "间隔越短轨迹越准，车机被唤醒得也越勤（会多耗一点小电瓶的电）。",
                    color = C.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 336.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TripSampling.INTERVALS_SEC.forEach { seconds ->
                        val selected = seconds == picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) C.accent.copy(alpha = 0.14f)
                                    else C.textPrimary.copy(alpha = 0.04f),
                                )
                                .noRippleClickable { onPick(seconds) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = TripSampling.label(seconds),
                                color = if (selected) C.accent else C.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = secondsText(seconds),
                                color = C.textSecondary,
                                fontSize = 10.5.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text("已选", color = C.accent, fontSize = 10.5.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "默认 45 秒：车端报文约 60 秒一帧，45 秒能赶在下一帧到达前采一次。" +
                        "比它更密的档位不会让位置更准，只是新的一帧能更快被采到。",
                    color = C.textSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = C.accent, fontWeight = FontWeight.Medium)
            }
        },
    )
}

/** 每档一句人话，别让用户对着数字猜。 */
private fun secondsText(seconds: Int): String = when (seconds) {
    10 -> "最密，新的一帧最多晚 10 秒采到"
    15 -> "很密，新的一帧最多晚 15 秒采到"
    20 -> "较密，新的一帧最多晚 20 秒采到"
    30 -> "市区走走停停也连得上"
    45 -> "推荐，与车端报文节奏接近"
    60 -> "一分钟一个点"
    120 -> "省电，短途点会稀一些"
    180 -> "很省电，适合长途巡航"
    300 -> "最少唤醒车机"
    // 档位表以外的值走不到这里（normalize 已经归一），留个兜底免得 when 不完整
    else -> "最少唤醒车机"
}
