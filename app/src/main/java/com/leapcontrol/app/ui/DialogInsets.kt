package com.leapcontrol.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 全屏 Dialog 里「离屏幕底边要留多少」。
 *
 * ## 为什么不直接用 `navigationBarsPadding()`
 * 这个仓库里两种全屏写法都踩过同一个坑（见 `DestinationSearch` / `TripMapViewer` 的注释）：
 * 窗口避让开着的时候 `navigationBarsPadding()` 拿到的 inset 会是 **0**（内容区已经被系统收进安全区，
 * Compose 再减一次就没了），于是「栏内 + 固定 8dp」看起来只离底边 8dp；真机上再遇到三键导航栏
 * 那种 40~48dp 的高度，最下面那一行字就有一半压在导航栏里 —— 用户报的
 * 「提示文字下半部分被遮挡」「提示被手机挡住」都是这么来的。
 *
 * 所以这里取两路：
 *  1. Compose 自己能读到导航栏 inset → 就用它（这才是正常的机型）；
 *  2. 读不到（恒 0）→ 直接向窗口根 View 要 `navigationBars` 的真实高度，并给一个 [minimum] 兜底；
 *     根 insets 是**未经消费**的，不受上面那层「内容已在安全区内」的影响。
 *
 * 组合那一刻 Dialog 的窗口常常还没 attach，第一次读一定是 0，所以等一帧再读、最多试三帧。
 *
 * @param extra 在安全高度之外还想多留的空白（文字与栏之间总得透一口气）。
 * @param minimum 读不到任何 inset 时的兜底高度：手势导航约 24dp、三键导航约 40~48dp，
 *                取 32dp 能盖住手势条，三键机型由根 insets 那条路补足。
 */
@Composable
internal fun dialogBottomGap(extra: Dp = 0.dp, minimum: Dp = 32.dp): Dp {
    val density = LocalDensity.current
    val view = LocalView.current
    // Compose 视角的导航栏 inset：非 0 说明这一棵树的 inset 没被窗口吃掉，直接可用
    val composeInsetPx = WindowInsets.navigationBars.getBottom(density)
    var rootInsetPx by remember(view) { mutableIntStateOf(0) }
    LaunchedEffect(view, composeInsetPx) {
        if (composeInsetPx > 0 || rootInsetPx > 0) return@LaunchedEffect
        var frames = 0
        while (frames < 3 && rootInsetPx == 0) {
            withFrameNanos { }
            rootInsetPx = runCatching {
                ViewCompat.getRootWindowInsets(view)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            }.getOrDefault(0)
            frames++
        }
    }
    val base = if (composeInsetPx > 0) {
        with(density) { composeInsetPx.toDp() }
    } else {
        with(density) { rootInsetPx.toDp() }.coerceAtLeast(minimum)
    }
    return base + extra
}
