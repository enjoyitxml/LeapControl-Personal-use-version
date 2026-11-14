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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import com.leapcontrol.app.ui.theme.statusGood
import com.leapcontrol.app.ui.theme.statusGoodDark

/**
 * 「蓝牙钥匙开启提示」——把总开关从关拨到开之前的那道闸。
 *
 * ## 为什么要有这一层
 * 这套蓝牙钥匙是靠车端 BLE 信道自己实现的，跟官方 App 抢同一块信道：
 * 两个 App 同时开着，就会互相把对方踢下线，用户看到的是「时好时坏、走到车边不解锁」。
 * 而且不同车型的车端行为差别不小，本功能只能算补充手段。
 * 所以**开启之前必须把话说清楚**，而不是等用户用坏了再去页尾找兼容性说明
 * （那段说明在详情页最底下，从设置页直接把开关拨开的人根本不会看到）。
 *
 * 版式对齐官方 App 那次提示：标题 + 一段现状 + 三条建议（第一条标绿推荐）+
 * 两个按钮「仍要开启 / 保持关闭（推荐）」。
 * 文案由我们自己写：官方那段讲的是「关掉本开关去用官方钥匙」，
 * 我们这里讲的是「本功能的适配性有限，优先用官方」。
 *
 * @param onConfirm 用户坚持开启（关掉弹层并真正下发配置由调用方负责）
 * @param onDismiss 放弃开启（保持关闭）
 */
@Composable
fun BleEnablePromptDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val C = LocalAppColors.current
    // 推荐项用绿色：截图里那条就是绿的，绿色在这里表示「更稳的那条路」，
    // 和「已就绪」用的同一支色，用户不需要重新理解。
    val good = if (C.isDark) statusGoodDark else statusGood

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 弹层开着时，侧滑 / 返回键只收弹层，别把设置页或详情页一起退了
        BackHandler(enabled = true, onBack = onDismiss)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = DIALOG_SCRIM))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 22.dp, vertical = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 外层只管裁圆角与底色，内层滚动：小屏（或系统大字号）时正文能滑动，
            // 按钮不会被挤出屏幕 —— 这一页正好是「必须读完才能点」的那种。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    // 自绘卡片内部必须吃东西，否则点正文空白处会穿透到遮罩上直接关掉
                    .consumeClicks()
                    .clip(RoundedCornerShape(22.dp))
                    .background(C.dialogBg),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                ) {
                    Text(
                        text = "蓝牙钥匙开启提示",
                        color = C.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(14.dp))

                    // 第一段就是用户最该知道的那句话：能开，但别指望它比官方稳
                    Text(
                        text = "本功能目前适配性一般：部分车型可能一直连不上车，或者出现" +
                            "靠近不解锁、离开不落锁的情况。日常用车建议优先使用官方零跑 App " +
                            "的蓝牙钥匙控车，本入口只作为补充。",
                        color = C.textPrimary,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "若本机同时运行官方零跑 App，两端后台会争抢蓝牙信道，" +
                            "易导致频繁断连或感应迟钝。",
                        color = C.textSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "为获得最稳定的日常体验，建议：",
                        color = C.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(10.dp))

                    PromptItem(
                        index = "1.",
                        text = "优先使用官方 App 的蓝牙钥匙与手机 NFC 钥匙，关闭此开关（推荐）",
                        color = good,
                        emphasized = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    PromptItem(
                        index = "2.",
                        text = "若坚持使用本 App 的蓝牙钥匙，需要先删除官方 App 里已保存好的" +
                            "蓝牙数字钥匙，否则无法使用本 App 的蓝牙钥匙",
                        color = C.textPrimary,
                    )
                    Spacer(Modifier.height(10.dp))
                    PromptItem(
                        index = "3.",
                        text = "推荐组合是两个手机号 A 和 B：A 号登录本 App 控车，B 号登录官方 " +
                            "App 创建蓝牙钥匙与 NFC 钥匙",
                        color = C.textPrimary,
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 「仍要开启」只给文字，不铺底色：它是次级动作，
                        // 别和推荐项抢视觉重量
                        Box(
                            modifier = Modifier
                                .weight(0.8f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(onClick = onConfirm),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "仍要开启",
                                color = C.textSecondary,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                            )
                        }
                        // 「保持关闭（推荐）」用中性实底（不是主题色）：
                        // 它表示「维持现状」，不该被染成品牌色显得像个行动号召。
                        // 底色由 textPrimary 的极低透明度叠出来，深浅主题都自洽。
                        //
                        // 宽度比例 0.8 : 1.2 是给「保持关闭（推荐）」留位置：这行字更长，
                        // 系统字号调大后等宽两份会被压到换行 / 截断。按钮高度固定 46dp，
                        // 一旦换行第二行就顶出去，所以宁可让它多占一点宽度、单行显示。
                        Box(
                            modifier = Modifier
                                .weight(1.2f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(C.textPrimary.copy(alpha = 0.09f))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "保持关闭（推荐）",
                                color = C.textPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一条编号建议：序号固定宽，正文自动换行，缩进对齐（第二行不对到序号下面会很难读）。 */
@Composable
private fun PromptItem(
    index: String,
    text: String,
    color: Color,
    emphasized: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = index,
            color = color,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = color,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}
