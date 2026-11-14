package com.leapcontrol.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「行驶动效」设置页（个性化 → 车模外观 → 行驶动效）。
 *
 * ## 一个总开关 + 三个滑杆 + 一个预览车速
 *
 * 总开关关掉之后整条动画循环都不跑（见 [CarMotionSettings.animatesAt]），
 * 下面三个滑杆跟着置灰 —— 没生效的参数还拖得动，用户会以为是"调了不起作用"。
 *
 * 车身抖动幅度、风向倾角、风的密度 —— 都是「看着调」的东西。预览车速只在**这一页**
 * 生效，不落盘：它是量具，不是参数（用户明确要求「预览的时候可以调车速」）。
 *
 * ## 上半屏预览、下半屏参数
 *
 * 参数和结果不在同一屏，改一个值要滑上去看一眼再滑下来，十几次之后就放弃了。
 * 所以预览固定在上面、车速条压在同一块里，参数区自己滚。
 */
@Composable
fun CarMotionScreen(
    runtime: AppRuntime,
    onBack: () -> Unit,
) {
    val C = LocalAppColors.current
    var bitmap by remember { mutableStateOf(runtime.cachedDisplayCarBitmap()) }
    LaunchedEffect(Unit) {
        runCatching { runtime.displayCarBitmap() }.getOrNull()?.let { bitmap = it }
    }

    // 草稿：滑杆只改它，预览立刻跟着变；停手 280ms 再落盘。
    // 直接每帧写 prefs 的话，拖一次要写几十次 SharedPreferences。
    var draft by remember { mutableStateOf(runtime.carMotionSettings()) }
    LaunchedEffect(draft) {
        delay(280)
        runtime.setCarMotion(draft)
    }
    // 防抖有 280ms 窗口：改完立刻按返回，上面的协程会被取消，参数就丢了。
    // 页面销毁时再存一次兜底（拖到最后一下松手就走也存得住）。
    val latestDraft by rememberUpdatedState(draft)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { runtime.setCarMotion(latestDraft) }
    }

    // 预览车速：纯本页状态，不落盘
    var previewSpeed by remember { mutableFloatStateOf(DEFAULT_PREVIEW_SPEED) }

    // 二级页必须自己注册返回，否则侧滑会直接 finish Activity。
    BackHandler(enabled = true) {
        runtime.setCarMotion(draft)
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.bgTop)
            .statusBarsPadding(),
    ) {
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsTopBar(title = "行驶动效", onBack = onBack)
        }

        // ---- 上半屏：预览 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.46f)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            MotionPreview(
                bitmap = bitmap,
                settings = draft,
                dark = C.isDark,
                speedKmh = previewSpeed,
                onSpeedChange = { previewSpeed = it },
            )
        }

        // ---- 下半屏：总开关 + 三个参数 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.54f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(6.dp))

            SettingsCard {
                SwitchRow(
                    label = "启用行驶动效",
                    desc = if (draft.enabled) {
                        "车开起来时的车身抖动和风。关掉后车模完全静止。"
                    } else {
                        "已关闭：车模保持静止，不抖动、不画风。"
                    },
                    checked = draft.enabled,
                    onCheckedChange = { draft = draft.copy(enabled = it) },
                )
            }

            Spacer(Modifier.height(12.dp))

            // 关掉总开关时下面三块整体变淡，明确「现在调了也不生效」
            Column(
                modifier = Modifier.alpha(if (draft.enabled) 1f else 0.45f),
            ) {
                SettingsCard {
                    Text(
                        text = "车身抖动",
                        color = C.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "车速越快抖得越密。调 0 就完全不抖，只留风。"
                            + "倒车时只有抖动、没有风。",
                        color = C.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    MotionSlider(
                        label = "幅度",
                        value = draft.shakeDp,
                        range = CarMotionSettings.MIN_SHAKE_DP..CarMotionSettings.MAX_SHAKE_DP,
                        display = String.format(java.util.Locale.US, "%.1f dp", draft.shakeDp),
                        enabled = draft.enabled,
                        onChange = { draft = draft.copy(shakeDp = it).normalized() },
                    )
                }

                Spacer(Modifier.height(12.dp))

                SettingsCard {
                    Text(
                        text = "风",
                        color = C.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "车模朝左，风一律从左往右掠过车身。倾角决定它往上掀还是往下压，"
                            + "密度决定全屏有多少根细线（最低 10%，只剩零星几丝）。",
                        color = C.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    MotionSlider(
                        label = "倾角",
                        value = draft.windAngle,
                        range = -CarMotionSettings.MAX_WIND_ANGLE..CarMotionSettings.MAX_WIND_ANGLE,
                        steps = (CarMotionSettings.MAX_WIND_ANGLE * 2 - 1).toInt(),
                        display = windText(draft.windAngle),
                        enabled = draft.enabled,
                        onChange = { draft = draft.copy(windAngle = it).normalized() },
                    )
                    MotionSlider(
                        label = "密度",
                        value = draft.windDensity,
                        range = CarMotionSettings.MIN_WIND_DENSITY..CarMotionSettings.MAX_WIND_DENSITY,
                        steps = densitySteps(),
                        display = densityText(draft.windDensity),
                        enabled = draft.enabled,
                        onChange = { draft = draft.copy(windDensity = it).normalized() },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "恢复默认（开启 · 抖动 ${defaultShakeText()} · 倾角 −7° · 密度 ${defaultDensityText()}）",
                color = C.accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .noRippleClickable { draft = CarMotionSettings.DEFAULT }
                    .padding(vertical = 10.dp),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 恢复默认那行里的抖动值：从常量取，别再手写 1.2 这种数字（改默认值时漏改过一次）。 */
private fun defaultShakeText(): String =
    String.format(java.util.Locale.US, "%.1f dp", CarMotionSettings.DEFAULT_SHAKE_DP)

/** 同上，密度默认值也走常量：手写「100%」在默认改成 35% 之后就是说谎。 */
private fun defaultDensityText(): String =
    "${(CarMotionSettings.DEFAULT_WIND_DENSITY * 100).roundToInt()}%"

/** 预览默认车速：中风速，抖动和风都能看清；拉满 120 反而不容易判断「是不是太夸张」。 */
private const val DEFAULT_PREVIEW_SPEED = 60f

/** 预览车速上限（和 [motionFactor] 的封顶一致）。 */
private const val MAX_PREVIEW_SPEED = 120f

/** 密度按 0.05 一档：0.1 ~ 1.6 共 29 个刻度，10% 那一档也能单独选中。 */
private fun densitySteps(): Int {
    val span = CarMotionSettings.MAX_WIND_DENSITY - CarMotionSettings.MIN_WIND_DENSITY
    return (span / 0.05f).roundToInt() - 1
}

/** 风向文案：0 说「水平」，正负各自说清往哪边斜。 */
private fun windText(angle: Float): String = when {
    abs(angle) < 0.5f -> "0° 水平"
    angle > 0f -> "+${angle.roundToInt()}° 下压"
    else -> "${angle.roundToInt()}° 上掀"
}

/** 密度文案：直接给百分比，比 0.85 这种小数好理解。 */
private fun densityText(density: Float): String = "${(density * 100).roundToInt()}%"

/**
 * 预览区：车模 + 动效 + 车速调节。
 *
 * 车速条压在这一块底部（半透明胶囊），而不是塞进下面的参数区 ——
 * 「调车速看效果」和「调参数」是两件事，混在一起会让人以为车速也会被存下来。
 */
@Composable
private fun MotionPreview(
    bitmap: Bitmap?,
    settings: CarMotionSettings,
    dark: Boolean,
    speedKmh: Float,
    onSpeedChange: (Float) -> Unit,
) {
    val C = LocalAppColors.current
    val shown = remember(bitmap) { bitmap?.asImageBitmap() }
    val paused = remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(C.accent.copy(alpha = 0.08f), C.iconBg),
                ),
            ),
    ) {
        if (shown == null) {
            Text(
                "还没取到车模图\n刷新一次车模后就能在这里预览",
                color = C.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        CarMotionStage(
            speedKmh = speedKmh,
            settings = settings,
            paused = paused,
            dark = dark,
            modifier = Modifier.fillMaxSize(),
        ) {
            Image(
                bitmap = shown,
                contentDescription = "车模动效预览",
                contentScale = ContentScale.Fit,
                // 车模整体上移一点，别让底部的车速条压住轮子；
                // 风画在 CarMotionStage 的 Canvas 上，仍然铺满整块预览，不受这个 padding 影响。
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 26.dp),
            )
        }

        PreviewSpeedBar(
            speedKmh = speedKmh,
            onSpeedChange = onSpeedChange,
            enabled = settings.enabled,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * 预览车速条：压在半透明胶囊里，滑到 0 就是「停车没动效」的样子。
 *
 * 总开关关掉时整条置灰并改成「动效已关闭」：否则用户拉着车速条看不到任何变化，
 * 会以为是预览坏了而不是自己关了开关。
 */
@Composable
private fun PreviewSpeedBar(
    speedKmh: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** 总开关：关掉时整条置灰，避免用户拉不动却以为预览坏了。 */
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.26f))
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (enabled) "预览车速" else "动效已关闭",
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 10.5.sp,
            maxLines = 1,
        )
        Slider(
            value = speedKmh,
            onValueChange = onSpeedChange,
            valueRange = 0f..MAX_PREVIEW_SPEED,
            steps = (MAX_PREVIEW_SPEED / 5f).roundToInt() - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.9f),
                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            // 滑到 3 km/h 以下整层就静止了，直接写「静止」比显示 0 km/h 更说明问题
            text = when {
                !enabled -> "静止"
                speedKmh < CarMotionSettings.MIN_SPEED_KMH -> "静止"
                else -> "${speedKmh.roundToInt()} km/h"
            },
            color = Color.White,
            fontSize = 10.5.sp,
            maxLines = 1,
            modifier = Modifier.width(52.dp),
        )
    }
}

/**
 * 一行滑杆：左边标签、右边当前值。
 *
 * [steps] 传 0 表示连续（抖动幅度按 0.1 走，密度够细）；风向按 1° 吸附。
 * [enabled] 为假时滑杆只读（总开关关掉时用），但仍然显示当前值 ——
 * 用户关掉动效后再打开，看到的是自己上次调的那组数。
 */
@Composable
private fun MotionSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    steps: Int = 0,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    val C = LocalAppColors.current
    // 滑杆内部用 Float，避免「0.1 步进 → round 成 Int 再乘回去」的抖动
    var local by remember(value) { mutableFloatStateOf(value) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = C.textSecondary,
            fontSize = 11.5.sp,
            modifier = Modifier.width(40.dp),
        )
        Slider(
            value = local,
            onValueChange = {
                local = it
                onChange(it)
            },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = C.accent,
                activeTrackColor = C.accent,
                inactiveTrackColor = C.barTrack,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = display,
            color = C.textPrimary,
            fontSize = 11.5.sp,
            maxLines = 1,
            modifier = Modifier.width(78.dp),
        )
    }
}
