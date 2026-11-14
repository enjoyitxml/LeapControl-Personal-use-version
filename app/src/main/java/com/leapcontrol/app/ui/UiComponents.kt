package com.leapcontrol.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 通用卡片容器（模块、列表分组）。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    cornerRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val C = LocalAppColors.current
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, shape)
            .background(C.cardBg, shape)
            .padding(16.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                color = C.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

/** 顶栏用的小圆钮（半透明底，跟随主题）。 */
@Composable
fun CircleIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: androidx.compose.ui.graphics.Color = LocalAppColors.current.textPrimary,
) {
    val C = LocalAppColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) C.chipBgActive else C.iconBg, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/**
 * 无涟漪点击：只做命中判定，**不画任何按压遮罩**。
 *
 * 为什么需要：Material3 默认 indication 是 ripple，它在**大块自绘卡片**上是两层东西 ——
 * 一层铺满整块形状的半透明矩形（按下即出现、松手才退），一层从按压点扩散的圆。
 * 零控的卡片都是 8~20dp 圆角的纯色面板，两层叠上去就是用户在行程轨迹里看到的
 * 「矩形 + 圆形遮罩」，压在地址和指标数字上很难看。卡片本身已经有展开 / 勾选角标 /
 * 背景变色这些明确的反馈，不需要再补一层涟漪。
 *
 * 用法和 `Modifier.clickable` 完全一致，只是没有 indication；`composed` 是为了拿到一个
 * 稳定的 `MutableInteractionSource`，免得每个调用点各自 remember 一遍。
 */
fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

/** [noRippleClickable] 的长按版本（行程卡靠长按进多选，同样不该出遮罩）。 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.noRippleCombinedClickable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    combinedClickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}

/**
 * 底部导航项。图标二选一：[icon] 用内置 Material 矢量，[iconRes] 用 drawable 资源。
 *
 * [tintIcon] 默认 true：图标跟着「选中 / 未选中」变色（那几张矢量都是纯黑填充）。
 * 用户直接给的图片（底部「回到顶部」那颗圆 + 白箭头 + TOP）必须设 false，
 * 否则整张图会被单色染掉、白箭头也就没了 —— 那一颗要原样显示。
 * 这类图片如果有明暗两版，把深色版放进 [iconResDark]，DockBar 按当前主题挑。
 *
 * [showLabel] = false 用于「回到顶部」那一格：用户要求图标下面不写字、
 * 图标在整条导航栏里**高度居中**（不再跟着有文案的格子对齐上半部分）；
 * [label] 仍保留给读屏当 contentDescription，[iconSize] 用 [BACK_TO_TOP_ICON_SIZE]。
 */
data class NavItem(
    val label: String,
    val icon: ImageVector? = null,
    val iconRes: Int? = null,
    val iconResDark: Int? = null,
    val tintIcon: Boolean = true,
    val showLabel: Boolean = true,
    val iconSize: Dp = 26.dp,
)

/**
 * 「回到顶部」图标尺寸。
 *
 * 去掉文案后在 58dp 底栏里居中，原基准 36dp；用户先要 120%、后又定稿 **125%**
 * → 36 × 1.25 = 45dp。写成表达式而不是硬编码 45.dp，是为了让倍率口径看得见。
 * 45dp 放进 58dp 底栏，上下各留 6.5dp，不会顶到边。
 */
val BACK_TO_TOP_ICON_SIZE: Dp = 36.dp * 1.25f

/**
 * 底部导航（参照主流车企 App 的样式）：
 * 平铺通栏、图标在上文字在下；未选中浅灰，选中深色（深色模式下为近白）。
 *
 * 每格用 [Crossfade] 包住「图标 + 文案」：下拉过一屏时第一格要从「零控」
 * 变成「回到顶部」（还换图标、换尺寸、去掉文案），硬切会闪一下。
 * `NavItem` 是 data class，未变化的格子 targetState 相等 → 不触发过渡；
 * 只有真的换内容的那一格做 260ms 淡入淡出，图标尺寸同步平滑缩放。
 */
@Composable
fun DockBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    items: List<NavItem>,
) {
    val C = LocalAppColors.current
    val activeColor = if (C.isDark) Color(0xFFF2F2F4) else Color(0xFF14161A)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = C.dockBg,
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(58.dp),
            ) {
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    val tint = if (selected) activeColor else C.dockUnselected
                    // 26dp ⇄ 45dp 跟着状态平滑走：淡出的旧图标和淡入的新图标一起缩放，
                    // 不会在切换那一帧硬跳一个尺寸。
                    val iconSize by animateDpAsState(
                        targetValue = item.iconSize,
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                        label = "dockIconSize",
                    )
                    Crossfade(
                        targetState = item,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(index) },
                            ),
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                        label = "dockItem",
                    ) { current ->
                        // 回顶图标浅色 / 深色各一版：浅色圆调浅不抢镜，深色圆与白色图形调柔和。
                        // 其它导航格没配深色版，继续用原来那张（会被 tint 染成选中态单色）。
                        val iconId = (if (C.isDark) current.iconResDark else null) ?: current.iconRes
                        Column(
                            // Crossfade 内部按「子内容最大宽高」给自己定尺寸：这里必须撑满整格，
                            // 否则 weight 分到的一格会缩到图标那么宽，点击区变小、图标还会靠左。
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            when {
                                // 原样显示用户给的图片资源：不套 tint，圆底和白箭头都在图里
                                iconId != null && !current.tintIcon -> Image(
                                    painter = painterResource(iconId),
                                    contentDescription = current.label,
                                    modifier = Modifier.size(iconSize),
                                )

                                iconId != null -> Icon(
                                    painter = painterResource(iconId),
                                    contentDescription = current.label,
                                    tint = tint,
                                    modifier = Modifier.size(iconSize),
                                )

                                current.icon != null -> Icon(
                                    imageVector = current.icon,
                                    contentDescription = current.label,
                                    tint = tint,
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                            // 无文案的一格连文字行一起去掉，Column 会把 43.2dp 的图标
                            // 摆在 58dp 导航栏的正中间（用户要的「往下挪、高度居中」）。
                            if (current.showLabel) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = current.label,
                                    color = tint,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

/** 列表行容器（「我的」页设置项）。 */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val C = LocalAppColors.current
    val shape = RoundedCornerShape(16.dp)
    val base = modifier
        .fillMaxWidth()
        .background(C.cardBg, shape)
    Box(
        modifier = if (onClick != null) {
            base.clip(shape).clickable(onClick = onClick)
        } else {
            base
        },
    ) { content() }
}

/** 选项行（设置页单选）。 */
@Composable
fun OptionSurface(
    selected: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val C = LocalAppColors.current
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) C.chipBgActive else C.chipBg, shape)
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.45f) else Color.Transparent, shape),
    ) { content() }
}
