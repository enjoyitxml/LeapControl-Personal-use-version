package com.leapcontrol.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** 1° 的弧度值。用常量而不是 `PI / 180`：后者是 Double，会把整条计算链拖进 Double。 */
private const val DEG_TO_RAD = 0.017453292f

/** 2π。同上，避免顺手写 `PI.toFloat() * 2` 时漏掉后缀。 */
private const val TAU = 6.2831855f

/**
 * 车模行驶动效（个性化 → 车模外观 → 行驶动效 / 首页共用同一份绘制）。
 *
 * ## 只有两个元素
 *
 * 1. **车身抖动** —— 车模整体在 `graphicsLayer` 里做十几分之一像素级的位移 +
 *    极小的俯仰，外加每 0.7 秒一次短促下沉（过路面接缝那种「顿一下」）。
 * 2. **风** —— 一丝一丝的**细直线**盖在车身上，从**左往右**掠过（车模朝左，
 *    车往左开，空气相对车往右走）。
 *
 * ## 风的形状（2026-11-08 用户第三次定稿）
 *
 * 前两版是「长条带状气流 + 大擦痕 + 外晕模糊」，用户两次打回，最后明确要
 * 「用细直线展现风的感觉，但要稀疏一点」。所以现在的话是：
 *
 * - 纯 `moveTo → lineTo`，**不弯曲、不加 blur、不做带状包络**；
 * - 密度 100% 时全屏 38 条（远 14 / 中 16 / 近 8），**默认 35% 只有 14 条**，
 *   最长 112dp，线宽 0.65~1.65dp；
 * - 两端线性渐隐（0 → .13 → .5 → .86 → 1），圆头收尾，不会出现硬切口；
 * - 直线不像机械，靠三样随机：每条线自己的倾角微差、速度差、长度差。
 *
 * 这 38 条不是拍脑袋的数：条数少了画面会空，多了就变回「一片面」。粒子池的
 * 重生间隔（[LayerSpec.gapMin] / [LayerSpec.gapMax]）也是紧跟着调出来的 ——
 * 出屏后在屏外等 280px 才回来的话，稀疏就变成「时有时无」。
 *
 * ## 倒车没有风
 *
 * 见 [CarMotionSettings.windVisible]：倒车只抖不吹风。
 *
 * ## 性能（首页是常驻页面，这一条比好看重要）
 *
 * - [CarMotionSettings.animatesAt] 为假（静止 / 没速度信号）时**一次 `withFrameNanos`
 *   都不申请**，也没有任何绘制。
 * - 滚动时由 [paused] 停帧：沿用首页「滑动只更新平移矩阵」的老规矩。
 * - 抖动在 `graphicsLayer` 的 draw lambda 里算，风在 Canvas 的 draw lambda 里算，
 *   两者都只读时钟 State → 只失效绘制，不触发重组。
 * - 粒子池最多 61 条（密度 1.6），不分配位图、不做离屏缓冲。
 */
@Composable
internal fun CarMotionStage(
    /** 当前车速（km/h）；null / 低于阈值时整层静止。 */
    speedKmh: Float?,
    settings: CarMotionSettings,
    /** 首页滚动时停帧；设置页预览传恒 false。 */
    paused: State<Boolean>,
    /** 深色主题：风线要浅色，浅底上要换成偏深的蓝灰，否则看不见。 */
    dark: Boolean,
    /** 倒车中：只抖不吹风（见 [CarMotionSettings.windVisible]）。 */
    reversing: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // 帧时钟：draw 阶段读它 → 只失效绘制，不重组
    val clock = remember { mutableLongStateOf(0L) }
    val engine = remember { WindEngine() }
    val animating = settings.animatesAt(speedKmh)
    val speed = speedKmh ?: 0f
    val wind = settings.windVisible(speedKmh, reversing)
    // LaunchedEffect 的 key 只有 (animating, paused)，拖滑杆时它不会重启 ——
    // 直接捕获 speed / windAngle 会一直用**进入页面那一刻**的值，倾角改了风还是按老角度吹。
    val liveSpeed = rememberUpdatedState(speed)
    val liveAngle = rememberUpdatedState(settings.windAngle)

    LaunchedEffect(animating, paused) {
        if (!animating) return@LaunchedEffect
        var lastNs = 0L
        while (true) {
            if (paused.value) {
                // 滚动 / 不可见：不申请新帧，省电；恢复时重新取时间基准，避免相位跳一大段
                lastNs = 0L
                delay(120)
                continue
            }
            withFrameNanos { now ->
                if (lastNs != 0L) {
                    val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    engine.step(dt, liveSpeed.value, liveAngle.value)
                }
                lastNs = now
                clock.longValue = now
            }
        }
    }

    Box(modifier) {
        // 车模本体：抖动只加在这一层，风盖在它上面（见下面的 Canvas）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 滚动中要显式归零：layer 的属性不会自动复位，直接 return 会把
                    // 上一帧的偏移留在车上，滑回来时车模歪着停在卡片里。
                    if (!animating || paused.value) {
                        translationX = 0f
                        translationY = 0f
                        rotationZ = 0f
                        return@graphicsLayer
                    }
                    val t = clock.longValue / 1_000_000_000f
                    if (t <= 0f) return@graphicsLayer
                    val off = carShake(t, speed, settings.shakeDp)
                    translationX = off.x.dp.toPx()
                    translationY = off.y.dp.toPx()
                    rotationZ = off.rotation
                },
        ) {
            content()
        }
        Canvas(Modifier.fillMaxSize()) {
            // **必须在这块 Canvas 自己的 draw 里读一次帧时钟。**
            // Compose 的 State 只订阅「读到它的那个绘制 lambda」：之前只有上面的
            // graphicsLayer 读了 clock，那是另一个 layer，它每帧失效只会重画车模本身，
            // 不会带着这块 Canvas 一起重绘 —— 结果就是「车在抖、风一动不动」
            // （2026-11-08 用户截图反馈：预览窗口里风是静止的）。
            // frame 的值不用，读它是为了建立订阅；< 0 永不成立，留着只为让变量被使用。
            val frame = clock.longValue
            // 暂停 / 倒车时也要重绘一次（读 paused、wind → 失效）：Canvas 每帧从透明
            // 重画，这一帧什么都不画就等于把已经画上的风线擦掉，不会留一层"静止的风"。
            if (paused.value || !wind || frame < 0L) return@Canvas
            engine.draw(this, speed, settings.windAngle, settings.windDensity, dark)
        }
    }
}

/** 一帧的抖动偏移：位移单位 dp，俯仰单位度。 */
internal data class ShakeOffset(val x: Float, val y: Float, val rotation: Float)

/**
 * 车身抖动（纯函数，方便单测）。
 *
 * 三条正弦叠出「不规则的细碎颠簸」，再叠一次每 0.7 秒的衰减冲击。频率与幅度
 * 都随车速走：20 km/h 只是轻轻晃，120 km/h 才明显颠。
 *
 * 冲击的系数从 1.5 降到 0.85（2026-11-08）：用户要求「抖动幅度小一点」，而那个
 * 0.7 秒一次的冲击幅度是正弦本身的 1.5 倍，是「一顿一顿」的主要来源。降默认值
 * 只会整体等比缩小，把这个系数压下来才是让抖动变细腻。
 *
 * @param t 秒（动画起点之后的时间）
 * @param speedKmh 车速
 * @param shakeDp 用户调好的幅度（dp）
 */
internal fun carShake(t: Float, speedKmh: Float, shakeDp: Float): ShakeOffset {
    if (!t.isFinite() || shakeDp <= 0f || speedKmh < CarMotionSettings.MIN_SPEED_KMH) {
        return ShakeOffset(0f, 0f, 0f)
    }
    val k = motionFactor(speedKmh)
    val freq = 2.4f + k * 3.2f // 2.4 ~ 5.6 Hz
    val amp = shakeDp * (0.55f + k * 0.75f)
    val ph = t * freq * TAU

    var y = sin(ph) * amp + sin(ph * 2.7f + 1.1f) * amp * 0.32f
    val x = sin(ph * 1.3f + 0.7f) * amp * 0.22f
    val rot = sin(ph * 0.9f + 2f) * 0.10f

    // 每 0.7 秒一次单次冲击：短促下沉再回弹（阻尼正弦）。
    // 只有「在跑」的时候出现，匀速直线时它负责给出一点点意外感。
    val period = 0.7f
    val p = (t % period) / period
    y += exp(-p * 9f) * sin(p * 34f) * amp * IMPACT_AMP

    return ShakeOffset(x, y, rot)
}

/** 0.7 秒冲击的幅度系数（相对正弦幅度）。1.5 太跳，0.85 刚好是「顿一下」。 */
private const val IMPACT_AMP = 0.85f

/**
 * 一层风线的规格。长度 / 宽度 / 间隔 / 速度都按 **dp** 写，绘制时乘屏幕密度，
 * 这样 375dp 和 411dp 的手机看到的是同一个疏密，线不会在窄屏上挤成一坨。
 */
private class LayerSpec(
    /** 密度 100% 时的条数（默认 35% 实际画的是它乘 0.35 之后取整）。 */
    val baseCount: Int,
    val lenMin: Float,
    val lenMax: Float,
    val widthMin: Float,
    val widthMax: Float,
    val alphaMin: Float,
    val alphaMax: Float,
    /** 速度系数：近处的风看着更快（透视）。 */
    val speedFactor: Float,
    /** 每条线的倾角微差（±度）：全靠它让一排直线看起来不像栅栏。 */
    val angleJitter: Float,
    /** 出屏后在屏外「等」多远才回来（dp）。条数越少越要短，否则画面会空。 */
    val gapMin: Float,
    val gapMax: Float,
    /** 深色主题下的颜色。 */
    val darkColor: Color,
    /** 浅色主题下的颜色。 */
    val lightColor: Color,
)

/**
 * 三层细直线，密度 100% 时共 38 条（设计稿 `design-wind/index.html` 的 `line` 风格实测值）。
 *
 * | 层 | 条数 | 长度 | 线宽 | 透明度 | 速度 |
 * |---|---|---|---|---|---|
 * | 远 far | 14 | 12~30 | 0.65~0.85 | .22~.34 | ×0.50 |
 * | 中 mid | 16 | 30~66 | 0.85~1.15 | .32~.48 | ×1.00 |
 * | 近 near | 8 | 64~112 | 1.20~1.65 | .44~.62 | ×1.45 |
 *
 * 近层压在车身上（同一张 Canvas，画在 content 之后），所以不需要 `front` 标记。
 */
private val LAYERS = arrayOf(
    LayerSpec(
        baseCount = 14,
        lenMin = 12f, lenMax = 30f,
        widthMin = 0.65f, widthMax = 0.85f,
        alphaMin = 0.22f, alphaMax = 0.34f,
        speedFactor = 0.50f, angleJitter = 2.0f,
        gapMin = 8f, gapMax = 80f,
        darkColor = Color(0xFFAFC7E8), lightColor = Color(0xFF708094),
    ),
    LayerSpec(
        baseCount = 16,
        lenMin = 30f, lenMax = 66f,
        widthMin = 0.85f, widthMax = 1.15f,
        alphaMin = 0.32f, alphaMax = 0.48f,
        speedFactor = 1.00f, angleJitter = 1.5f,
        gapMin = 6f, gapMax = 60f,
        darkColor = Color(0xFFC8DCF7), lightColor = Color(0xFF546780),
    ),
    LayerSpec(
        baseCount = 8,
        lenMin = 64f, lenMax = 112f,
        widthMin = 1.20f, widthMax = 1.65f,
        alphaMin = 0.44f, alphaMax = 0.62f,
        speedFactor = 1.45f, angleJitter = 0.9f,
        gapMin = 5f, gapMax = 50f,
        darkColor = Color(0xFFE6F1FF), lightColor = Color(0xFF2E405A),
    ),
)

/**
 * 风线粒子池：三层各一个池，按用户密度增删。
 *
 * 速度 = `(140 + 车速×13) dp/s × 层系数 × 每条自己的 0.78~1.3 × 阵风呼吸`，
 * 60 km/h 时中层约 920 dp/s —— 一屏 375dp 大概 0.4 秒穿完，与设计稿 GIF 一致。
 */
private class WindEngine {
    private class Streak {
        var x = 0f
        var y = 0f
        var len = 0f
        var v = 1f
        var a = 0.2f
        var w = 1.5f

        /** 这条线自己的倾角微差（度）。 */
        var jit = 0f
    }

    private val pools = Array(LAYERS.size) { ArrayList<Streak>() }
    private var w = 0f
    private var h = 0f

    /** 1dp = 多少像素。 */
    private var pxPerDp = 1f

    /** 用户设的密度（[CarMotionSettings.windDensity]）。 */
    private var density = 1f

    /** 内部时钟（秒）：给阵风呼吸用，暂停时会一起停住。 */
    private var timeSec = 0f

    private val rng = Random(0x5EED)

    private fun rnd(a: Float, b: Float): Float = a + rng.nextFloat() * (b - a)

    fun step(dt: Float, speedKmh: Float, windAngle: Float) {
        if (dt <= 0f) return
        timeSec += dt

        // 阵风呼吸：风速整体 ±23% 缓慢起伏，否则 38 条匀速直线看着像流水线
        val gust = 1f + 0.16f * sin(timeSec * 0.42f) + 0.07f * sin(timeSec * 1.13f)
        val baseSpeed = (140f + speedKmh * 13f) * pxPerDp

        for (i in LAYERS.indices) {
            val spec = LAYERS[i]
            val v = baseSpeed * spec.speedFactor * gust
            val pool = pools[i]
            for (s in pool) {
                val ang = (windAngle + s.jit) * DEG_TO_RAD
                s.x += cos(ang) * v * s.v * dt
                s.y += sin(ang) * v * s.v * dt
                // 回收：跑出右边界，或者因为倾角一路飘出上下边界
                if (s.x > w + s.len * 1.6f + 40f * pxPerDp ||
                    s.y < -160f * pxPerDp ||
                    s.y > h + 160f * pxPerDp
                ) {
                    respawn(s, spec, initial = false)
                }
            }
        }
    }

    fun draw(
        scope: DrawScope,
        speedKmh: Float,
        windAngle: Float,
        /** 用户密度：变了就增删线条。 */
        windDensity: Float,
        dark: Boolean,
    ) {
        if (scope.size.width <= 0f || scope.size.height <= 0f) return
        ensure(scope.size.width, scope.size.height, scope.density, windDensity)
        if (pools.all { it.isEmpty() }) return

        val k = motionFactor(speedKmh)
        // 长度与透明度都随车速长（0.72→1.27 倍 / 0.62→1.17 倍）：
        // 慢速只是淡淡几丝，高速才拉长变亮。
        val lenScale = 0.72f + k * 0.55f
        val alphaScale = 0.62f + k * 0.55f

        for (i in LAYERS.indices) {
            val spec = LAYERS[i]
            val color = if (dark) spec.darkColor else spec.lightColor
            for (s in pools[i]) {
                val a = s.a * alphaScale
                if (a <= 0.004f) continue
                val ang = (windAngle + s.jit) * DEG_TO_RAD
                val len = s.len * lenScale
                val start = Offset(s.x, s.y)
                val end = Offset(s.x + cos(ang) * len, s.y + sin(ang) * len)
                // 五段渐隐：两端各留一段全透明，中间最亮，末段收得比首段更急
                // （风尾散得快，风头是从无到有地起来）。
                scope.drawLine(
                    brush = Brush.linearGradient(
                        0f to color.copy(alpha = 0f),
                        0.13f to color.copy(alpha = a * 0.82f),
                        0.50f to color.copy(alpha = a),
                        0.86f to color.copy(alpha = a * 0.28f),
                        1f to color.copy(alpha = 0f),
                        start = start,
                        end = end,
                    ),
                    start = start,
                    end = end,
                    strokeWidth = s.w,
                    cap = StrokeCap.Round,
                )
            }
        }
    }

    /**
     * 尺寸 / 密度变化时对齐粒子池。
     *
     * 密度**只增删尾部的线**，不做整池重排：用户正拖着滑杆，全部重生会让满屏线
     * 瞬间跳一下位置。尺寸变了（旋屏 / 卡片高度变化）才必须全部重排 —— 旧坐标
     * 已经不在可见区域里了。
     */
    private fun ensure(width: Float, height: Float, scale: Float, windDensity: Float) {
        val resized = width != w || height != h || scale != pxPerDp
        if (resized) {
            w = width
            h = height
            pxPerDp = scale
        }
        density = windDensity

        for (i in LAYERS.indices) {
            val spec = LAYERS[i]
            val pool = pools[i]
            val target = (spec.baseCount * windDensity).roundToInt().coerceAtLeast(0)
            if (resized) {
                pool.clear()
                repeat(target) { pool.add(Streak().also { respawn(it, spec, initial = true) }) }
                continue
            }
            while (pool.size < target) {
                pool.add(Streak().also { respawn(it, spec, initial = true) })
            }
            while (pool.size > target) {
                pool.removeAt(pool.size - 1)
            }
        }
    }

    private fun respawn(s: Streak, spec: LayerSpec, initial: Boolean) {
        val scale = pxPerDp
        s.len = rnd(spec.lenMin, spec.lenMax) * scale
        s.w = rnd(spec.widthMin, spec.widthMax) * scale
        s.a = rnd(spec.alphaMin, spec.alphaMax)
        s.v = rnd(0.78f, 1.3f)
        s.jit = rnd(-spec.angleJitter, spec.angleJitter)

        // Y 用三角分布偏画面中间：镜头焦点在车上，上下边缘本来就该稀一些。
        // 均匀分布会在车顶上方堆出一排等距的线，一眼看出是程序画的。
        val g = (rng.nextFloat() + rng.nextFloat() + rng.nextFloat() - 1.5f) / 1.5f
        val hh = if (h > 0f) h else 1f
        s.y = hh * 0.5f + g * hh * 0.52f + rnd(-8f, 8f) * scale

        s.x = if (initial) {
            rnd(0f, if (w > 0f) w else 1f)
        } else {
            -s.len - rnd(spec.gapMin, spec.gapMax) * scale
        }
    }
}

/** 仅供单测：三层基准条数之和（密度 100% 时），用于断言「基准就是 38 条」。 */
internal fun windBaseCount(): Int = LAYERS.sumOf { it.baseCount }

/** 仅供单测：标准密度下某一层的条数（0 远 / 1 中 / 2 近）。 */
internal fun windLayerBaseCount(layer: Int): Int = LAYERS[layer].baseCount

/** 仅供单测：某一层的最长线（dp），守「不许再回到 270px 的长条」。 */
internal fun windLayerMaxLenDp(layer: Int): Float = LAYERS[layer].lenMax

/** 仅供单测：某一层最粗的线宽（dp）。 */
internal fun windLayerMaxWidthDp(layer: Int): Float = LAYERS[layer].widthMax

/** 仅供单测：抖动的 0.7 秒冲击系数。 */
internal fun shakeImpactAmp(): Float = IMPACT_AMP

/** 仅供单测：密度换算出的实际条数。 */
internal fun windCountAt(density: Float): Int =
    LAYERS.sumOf { (it.baseCount * density).roundToInt().coerceAtLeast(0) }
