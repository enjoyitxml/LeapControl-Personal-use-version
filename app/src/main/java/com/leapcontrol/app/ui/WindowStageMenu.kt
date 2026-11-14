package com.leapcontrol.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * 首页「车窗」按钮的档位菜单（对齐官方首页的交互）。
 *
 * 点按钮后在**按钮正上方**弹出一张小卡片，**四档恒列**（关闭 / 微开 / 半开 / 全开）；
 * 用户点哪一档就发哪一档，不再是「点一下往后走一档」。
 *
 * 为什么恒列四档而不是只列「剩下的几档」：见 [WindowControl.menuStages] 的注释 ——
 * 档位判断依赖回执，回执丢了就会把「关闭」从菜单里滤掉，用户反而关不掉窗。
 *
 * 为什么不再画图标：四个档位（关 / 微开 / 半开 / 全开）画成图标，
 * 一律是「车窗轮廓 + 不同高度」，22dp 下根本分不出来；官方这张菜单也是纯文字。
 *
 * @param anchor 按钮在窗口里的矩形（点击时记录的，不是每帧跟随）。
 *               不用「跟随按钮」是因为按钮在横向列表里，滚动时菜单跟着飘会晃眼。
 */
@Composable
internal fun WindowStageMenu(
    anchor: Rect,
    onPick: (WindowStage) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = AboveAnchorPositionProvider(anchor, gapPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
    ) {
        Column(
            modifier = Modifier
                // 只比档位文字宽一点：档位名都是两个字（关闭 / 微开 / 半开 / 全开），
                // 卡片再宽就成了一块糊在车模上的大方块（2026-11-08 用户反馈「太大了」）
                .width(84.dp)
                // 弹窗压在首页车模 / 四季渐变上：必须用不透明的 dialogBg，
                // 半透明的 cardBg 会把底下的深色画面透上来（见 AppColors 注释）
                .shadow(12.dp, RoundedCornerShape(12.dp), clip = false)
                .clip(RoundedCornerShape(12.dp))
                .background(c.dialogBg)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .padding(vertical = 3.dp),
        ) {
            WindowControl.menuStages().forEachIndexed { index, stage ->
                // 档位之间必须有分隔：几行字连在一起像一段话，看不出是几个可点的按钮
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .height(1.dp)
                            .background(c.textSecondary.copy(alpha = 0.25f)),
                    )
                }
                Text(
                    text = WindowControl.stageLabel(stage),
                    color = c.textPrimary,
                    fontSize = 13.sp,
                    // 多行 Text 必须显式写 lineHeight，否则继承 bodyLarge 会撑高
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(stage) }
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/**
 * 把菜单摆在按钮上方居中；上方放不下（按钮贴着屏幕顶端、或菜单太高）就翻到下方。
 *
 * 横向一定会夹在窗口内：车窗按钮排在四个快捷按钮里，靠右时菜单居中会顶出屏幕。
 */
private class AboveAnchorPositionProvider(
    private val anchor: Rect,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val x = (anchor.center.x.roundToInt() - popupContentSize.width / 2).coerceIn(0, maxX)
        val above = anchor.top.roundToInt() - popupContentSize.height - gapPx
        val y = if (above >= 0) above else anchor.bottom.roundToInt() + gapPx
        return IntOffset(x, y.coerceIn(0, maxY))
    }
}
