package com.leapcontrol.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 「怎么才能记到行程」完整说明弹窗。
 *
 * 入口在行程轨迹页右上角「怎么记」：原来是一条常显横幅，占一整行还把月合计挤下去，
 * 用户 2026-11-06 要求挪到右上角，横幅组件随之删掉。
 *
 * 2026-11-12 起只有手动记录：用户点「开始记录」后，零控用前台服务 + 闹钟在后台
 * 按设定间隔（默认 45 秒）自己读车况，锁屏也照采。这份说明要把「能记到什么程度、
 * 会多耗一点车电、怎么让它更稳」讲清楚，免得用户以为跟高德足迹一样全自动。
 */
@Composable
internal fun TripGuideDialog(onDismiss: () -> Unit) {
    val C = LocalAppColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler(enabled = true, onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = DIALOG_SCRIM))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 22.dp)
                    .fillMaxWidth()
                    .widthIn(max = 400.dp)
                    .consumeClicks()
                    .clip(RoundedCornerShape(22.dp))
                    .background(C.dialogBg)
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Text(
                    "怎么才能记到行程",
                    color = C.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "只有手动记录：点一次开始，剩下交给后台",
                    color = C.textSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    GuideStep(
                        number = "1",
                        title = "上车前点一次「开始记录」",
                        body = "点下去那一刻就是出发时间，也是起点坐标。没有「车一开就自动记」这回事 —— " +
                            "自动判据被挡位信号噪声坑过很多次（明明停着却显示正在记录），所以这个方案已经删掉。",
                    )
                    GuideStep(
                        number = "2",
                        title = "之后零控自己按间隔采点，锁屏也照采",
                        body = "页顶第一格是「采样间隔」：30 秒 / 45 秒 / 1 / 2 / 3 / 5 分钟，默认 45 秒。" +
                            "开始记录后零控会常驻一个前台服务（通知栏显示「正在记录行程」），按这个间隔读一次车况；" +
                            "服务被系统清掉时还有闹钟兜底，所以不必把 App 挂在屏幕上。",
                    )
                    GuideStep(
                        number = "3",
                        title = "想更稳：允许零控后台运行",
                        body = "国产 ROM 的省电策略会清后台。记录期间如果页面提示「后台采点可能被系统省电策略掐断」，" +
                            "点一下去把零控加进「不受电池优化限制」；小米 / 华为 / OPPO / vivo 还建议在系统设置里" +
                            "允许自启动、后台运行。做完这一步，锁屏一小时也不会缺点。",
                    )
                    GuideStep(
                        number = "4",
                        title = "会多耗一点车电，但只在记录期间",
                        body = "每次采点都要唤醒一次车机的 T-Box，和你在 App 里手动刷一次车况同价。" +
                            "所以这个行为只在「正在记录」时发生：点结束、暂停、锁车、停稳 5 分钟收尾，服务与闹钟都会立刻停。" +
                            "平时不记录时一次都不多发。嫌费电就把间隔调到 3 / 5 分钟。",
                    )
                    GuideStep(
                        number = "5",
                        title = "结束：手动点，或者停车 / 锁车兜底",
                        body = "点「结束行程」立刻收尾。忘了点也没关系：停稳后连续 5 分钟没有行驶就自动收一段" +
                            "（这 5 分钟不计入时长，红灯、临停不会被拆开）；锁车或整车下电会当场收尾，" +
                            "结束时间同样回拨到刚停车那一刻。",
                    )
                    GuideStep(
                        number = "6",
                        title = "中途下车办事：暂停，或者收尾后合并",
                        body = "点「暂停行程」后一帧都不采，后台采样也停，回来点「恢复行程」接着记；" +
                            "暂停这段时间的里程、时长、耗电都不算。如果直接锁车走了，回来再开是新的一段，" +
                            "可以在历史清单里勾起来点「合并」。",
                    )
                    GuideStep(
                        number = "7",
                        title = "想让起终点显示中文地址",
                        body = "在设置里填写高德 Web Key，行程收尾时各反查一次；没填就显示「起点 / 终点」。",
                    )
                    GuideStep(
                        number = "8",
                        title = "结算：里程看里程表，能耗看云端累计",
                        body = "里程用「首尾表显总里程相减」，不是把 GPS 点连线累加 —— 后者在采样稀疏时会少算。" +
                            "能耗优先用云端累计能耗在行程前后的差值（最准），拿不到时退回首末电量百分比 × 电池容量；" +
                            "两条都拿不到才显示「未读到」。想看到度数（kWh）和百公里电耗，在页顶「电池容量」里填一次。" +
                            "中途充过电的那段不显示耗电。",
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "这些点只存本机、按车架号分开、保留最近 90 天；换手机前记得在「备份」里导出。",
                        color = C.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(C.accent.copy(alpha = 0.14f))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "知道了",
                        color = C.accent,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideStep(number: String, title: String, body: String) {
    val C = LocalAppColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(C.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number,
                color = C.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = C.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = body,
                color = C.textSecondary,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            )
        }
    }
}
