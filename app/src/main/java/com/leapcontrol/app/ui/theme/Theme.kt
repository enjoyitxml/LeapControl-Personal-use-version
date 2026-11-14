package com.leapcontrol.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// ============================================================
// LeapControl 主题占位 —— 界面后期由你自由重新设计，
// 只需替换本文件配色与各 Screen 布局，业务逻辑层无需改动。
// ============================================================

val BrandBlue = Color(0xFF1A73E8)
val BrandDeepBlue = Color(0xFF0D47A1)
val BrandAmber = Color(0xFFFFB300)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    secondary = BrandDeepBlue,
    tertiary = BrandAmber,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFFAECBFA),
    tertiary = BrandAmber,
)

/**
 * 字号缩放的封顶倍数。
 *
 * 零控的版式是手工量出来的密排版式（40sp 续航大字、定宽里程槽、胶囊按钮、四个圆钮一排…），
 * 系统把字号拉到 1.5~2.0 倍时，这些定宽元素会被撑爆：里程行折成两行、百分比被挤掉。
 * 与其让整页塌掉，不如把缩放钳在 1.3 倍 —— 仍然明显放大（用户确实需要更大的字），
 * 但不会把版式挤坏。确实要更大字号，可以在局部用 `LocalDensity` 覆盖回来。
 *
 * **窄屏还要再收一档**（2026-11-08 用户反馈「小屏 / 大字体手机上充电时的续航文字被遮挡」）：
 * 同样 1.3 倍，在 393dp 的屏上放得下，在 320~360dp 的屏上就装不进了 ——
 * 首页续航区左边是 40sp 大字、右边是「充电中 剩 3 小时 5 分钟」，两者都要宽度。
 * 按屏宽分三档封顶，比一刀切 1.3 更合身，也不用让用户自己去调。
 */
private const val MAX_FONT_SCALE = 1.3f

/** 窄屏（< 360dp，常见于 5.0~5.5 寸 / 开了「显示大小」放大）上的封顶。 */
private const val NARROW_FONT_SCALE = 1.12f

/** 紧凑屏（360~400dp，绝大多数 6.1~6.5 寸）上的封顶。 */
private const val COMPACT_FONT_SCALE = 1.22f

/**
 * 按屏幕宽度挑字号封顶。
 *
 * 用 `screenWidthDp` 而不是「当前窗口宽度」：分屏时窗口变窄但字号线不该忽大忽小，
 * 而且这里只是封顶值，真正的版式适配仍由各个 `BoxWithConstraints` 自己做。
 */
internal fun fontScaleCap(widthDp: Int): Float = when {
    widthDp <= 0 -> MAX_FONT_SCALE
    widthDp < 360 -> NARROW_FONT_SCALE
    widthDp < 400 -> COMPACT_FONT_SCALE
    else -> MAX_FONT_SCALE
}

@Composable
fun LeapControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cap = fontScaleCap(LocalConfiguration.current.screenWidthDp)
    val clamped = Density(density.density, density.fontScale.coerceAtMost(cap))
    CompositionLocalProvider(LocalDensity provides clamped) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
