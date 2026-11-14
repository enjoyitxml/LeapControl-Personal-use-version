package com.leapcontrol.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leapcontrol.app.R
import com.leapcontrol.app.journal.CostCategory

/**
 * 保险 / 保养的角标：**主题色实心圆 + 用户指定的两个图标**。
 *
 * 以前是两个自绘实心徽章（保养六边形扳手、保险盾牌「保」），形状不一样，
 * 在首页一排卡片里显得各说各话。现在两张卡都垫同一个主题色圆，只换圆里的图形：
 * 保险 = 保护伞（`ic_reminder_insurance`），保养 = 机油罐（`ic_reminder_service`）。
 * 两张素材都由用户提供，均为透明底，按同一规格裁紧、染白、居中（`drawable-nodpi`）。
 *
 * 资源是透明底 + 纯白图形（`drawable-nodpi`），用 [ColorFilter.tint] 现场染色：
 * - 圆底恒取 [AppColors.accent]，跟着季节主题走，不用另配一套圆底颜色；
 * - 图形默认白，主题色偏浅（浅黄 / 浅粉）时自动切成深色，避免白图标糊在浅圆上。
 *
 * 两张素材的线条密度不一样，同尺寸并排放时观感不齐：按用户逐次给的口径，
 * 保险与保养最终都定在圆里显示原尺寸的 80%
 * （[INSURANCE_ICON_SCALE] / [SERVICE_ICON_SCALE]）。
 * 圆底本身大小不变，只缩圆里的图形。
 *
 * 原素材已是透明底 PNG，不需要像早期那套自绘徽章一样担心「贴上去留一个白方块」。
 */

/** 保险（保护伞）图标在主题色圆里的缩放比例：原尺寸的 80%（2026-11-07 用户定稿）。 */
private const val INSURANCE_ICON_SCALE = 0.80f

/** 保养（机油罐）图标在主题色圆里的缩放比例：原尺寸的 80%（2026-11-07 用户定稿）。 */
private const val SERVICE_ICON_SCALE = 0.80f
@Composable
fun ReminderBadge(
    category: CostCategory,
    size: Dp = 34.dp,
    modifier: Modifier = Modifier,
) {
    when (category) {
        CostCategory.SERVICE -> ThemedIconBadge(
            R.drawable.ic_reminder_service,
            size,
            modifier,
            iconScale = SERVICE_ICON_SCALE,
        )
        CostCategory.INSURANCE -> ThemedIconBadge(
            R.drawable.ic_reminder_insurance,
            size,
            modifier,
            iconScale = INSURANCE_ICON_SCALE,
        )
        // 其余分类保持原样：这次只换保险与保养两张卡的观感
        else -> CategoryIcon(category = category, modifier = modifier, size = size)
    }
}

@Composable
private fun ThemedIconBadge(
    iconRes: Int,
    size: Dp,
    modifier: Modifier,
    /** 图标相对圆底的占比：1 = 铺满；保险与保养都是 0.80。 */
    iconScale: Float = 1f,
) {
    val accent = LocalAppColors.current.accent
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(iconScale),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(onAccentInk(accent)),
        )
    }
}

/**
 * 主题色圆上的图标色。
 *
 * 主题色可由四季主题注入，亮度跨度很大：品牌绿上白图标正好，换成浅黄再放白图标就看不见了。
 * 这里用 sRGB 加权亮度粗分一档，不需要精确对比度计算。
 */
private fun onAccentInk(accent: Color): Color {
    val luminance = 0.2126f * accent.red + 0.7152f * accent.green + 0.0722f * accent.blue
    return if (luminance > 0.62f) Color(0xFF1B1D22) else Color.White
}
