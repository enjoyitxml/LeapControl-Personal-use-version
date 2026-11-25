package com.leapcontrol.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 节日主题场景（10 套：日间 5 + 夜间 5）。
 *
 * 这一组是从 `节日主题预览.html`（同一套 canvas 原型）逐元素搬过来的，配色 / 位置 / 比例
 * 都按原型的比例常数写，**改动请先看原型**，别在 Kotlin 这边凭手感调。
 *
 * 三条继承自原型的约定：
 *  · 所有尺寸按 [DrawScope.size] 换算（`w` / `h` 的比例），不写死 px；
 *  · 主体压在上 0.52h 以上 —— [ThemeBackdrop] 的淡出从 0.30 起、0.52 就只剩 60% 不透明度，
 *    再往下的东西会被洗成粉白（爆竹第一版就是踩了这个坑）；
 *  · 圣诞不用圣诞树（「雪原松林」已经有一片了），春节不用灯笼（「孔明灯升空」已占这个意象）。
 */

private const val TAU = 2f * PI.toFloat()

// ============================================================================
// 通用小工具（只服务于这 10 套，故保持 private，别提升成全局 API）
// ============================================================================

/** 顶部天空层：从顶部 [alpha] 渐隐到 [to] 处完全透明。 */
private fun DrawScope.fSky(color: Color, alpha: Float, to: Float) {
    val w = size.width
    val h = size.height
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            startY = 0f,
            endY = h * to,
        ),
        size = Size(w, h * to),
    )
}

/** 顶部一层暖色霞光（日间不加这一层，冷白会显得「没过年」）。 */
private fun DrawScope.fDusk(color: Color, alpha: Float, to: Float) {
    val w = size.width
    val h = size.height
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            startY = 0f,
            endY = h * to,
        ),
        size = Size(w, h * to),
    )
}

/** 一片虚化光斑。[ur] 是半径相对画布高度的比例。 */
private class BokehDot(
    val ux: Float,
    val uy: Float,
    val ur: Float,
    val color: Color,
    val alpha: Float,
    val phase: Float,
)

private fun DrawScope.bokeh(dots: List<BokehDot>, t: Float, drift: Boolean = true) {
    val w = size.width
    val h = size.height
    for (d in dots) {
        val x = w * d.ux + if (drift) sin(TAU * t + d.phase) * w * 0.008f else 0f
        val y = h * d.uy + if (drift) cos(TAU * 0.8f * t + d.phase) * h * 0.006f else 0f
        val r = h * d.ur
        drawCircle(
            brush = Brush.radialGradient(
                0f to d.color.copy(alpha = d.alpha),
                0.62f to d.color.copy(alpha = d.alpha * 0.55f),
                1f to d.color.copy(alpha = 0f),
                center = Offset(x, y),
                radius = r,
            ),
            radius = r,
            center = Offset(x, y),
        )
    }
}

/** 六角小雪花：比圆点更像雪。 */
private fun DrawScope.fFlake(x: Float, y: Float, r: Float, alpha: Float) {
    if (alpha <= 0.01f || r <= 0.2f) return
    val stroke = maxOf(0.6f, r * 0.34f)
    for (i in 0 until 3) {
        val ang = (i / 3f) * PI.toFloat()
        drawLine(
            Color.White.copy(alpha = alpha),
            start = Offset(x - cos(ang) * r, y - sin(ang) * r),
            end = Offset(x + cos(ang) * r, y + sin(ang) * r),
            strokeWidth = stroke,
        )
    }
}

/**
 * 飘落的纸屑 / 金屑。
 *
 * @param rise true 从下往上升（金屑），false 从上往下飘（爆竹红纸屑）
 */
private fun DrawScope.confetti(
    t: Float,
    n: Int,
    base: Int,
    speed: Float,
    y0: Float,
    y1: Float,
    rise: Boolean,
    colors: List<Color>,
    scale: Float,
) {
    val w = size.width
    val h = size.height
    for (i in 0 until n) {
        val s = base + i
        val prog = (t * speed * (0.7f + rnd(s + 12, i) * 0.6f) + rnd(s, i)) % 1f
        val y = if (rise) h * (y1 - prog * (y1 - y0)) else h * (y0 + prog * (y1 - y0))
        val x = rnd(s + 3, i) * w + sin(TAU * t + i) * w * 0.014f
        val color = colors[i % colors.size]
        val sz = h * (0.004f + rnd(s + 6, i) * scale)
        val alpha = 0.60f + rnd(s + 9, i) * 0.38f
        rotate(degrees = t * 360f * (0.5f + rnd(s + 15, i)) + i * 57.3f, pivot = Offset(x, y)) {
            drawRect(
                color = color,
                topLeft = Offset(x - sz * 0.5f, y - sz * 1.1f),
                size = Size(sz, sz * 2.2f),
                alpha = alpha,
            )
        }
    }
}

/** 塔形松树：三层，越往上越窄。 */
private fun DrawScope.pineTree(x: Float, baseY: Float, s: Float, color: Color, alpha: Float) {
    for (i in 0 until 3) {
        val halfW = s * (0.50f - i * 0.12f)
        val top = baseY - s * (0.40f + i * 0.27f)
        val bot = baseY - s * (0.14f + i * 0.27f)
        val path = Path().apply {
            moveTo(x, top)
            quadraticBezierTo(x + halfW * 0.62f, top + (bot - top) * 0.52f, x + halfW, bot)
            quadraticBezierTo(x + halfW * 0.30f, bot + s * 0.045f, x, bot + s * 0.02f)
            quadraticBezierTo(x - halfW * 0.30f, bot + s * 0.045f, x - halfW, bot)
            quadraticBezierTo(x - halfW * 0.62f, top + (bot - top) * 0.52f, x, top)
            close()
        }
        drawPath(path, color, alpha = alpha)
    }
    drawRect(
        color = color,
        topLeft = Offset(x - s * 0.040f, baseY - s * 0.16f),
        size = Size(s * 0.080f, s * 0.17f),
        alpha = alpha,
    )
}

/** 树上的挂球：先光晕后实心，才像「亮着的」。 */
private fun DrawScope.ornamentBall(x: Float, y: Float, r: Float, color: Color, alpha: Float) {
    softGlow(x, y, r * 3f, color, alpha * 0.42f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = alpha * 0.9f),
            0.45f to color.copy(alpha = alpha),
            1f to color.copy(alpha = alpha * 0.72f),
            center = Offset(x - r * 0.3f, y - r * 0.35f),
            radius = r * 1.45f,
        ),
        radius = r * 1.02f,
        center = Offset(x, y),
    )
}

/** 雪夜小屋：屋顶 + 墙 + 雪 + 两扇暖窗。 */
private fun DrawScope.cabinHouse(
    x: Float,
    baseY: Float,
    s: Float,
    ink: Color,
    alpha: Float,
    winColor: Color,
    winAlpha: Float,
    snow: Color? = null,
) {
    val roof = Path().apply {
        moveTo(x - s * 0.68f, baseY - s * 0.40f)
        lineTo(x, baseY - s * 0.96f)
        lineTo(x + s * 0.68f, baseY - s * 0.40f)
        close()
    }
    drawPath(roof, ink, alpha = alpha)
    drawRect(
        color = ink,
        topLeft = Offset(x - s * 0.48f, baseY - s * 0.42f),
        size = Size(s * 0.96f, s * 0.42f),
        alpha = alpha,
    )
    if (snow != null) {
        val cap = Path().apply {
            moveTo(x - s * 0.70f, baseY - s * 0.38f)
            lineTo(x, baseY - s * 0.98f)
            lineTo(x + s * 0.70f, baseY - s * 0.38f)
            lineTo(x + s * 0.52f, baseY - s * 0.44f)
            lineTo(x, baseY - s * 0.90f)
            lineTo(x - s * 0.52f, baseY - s * 0.44f)
            close()
        }
        drawPath(cap, snow, alpha = 0.72f)
    }
    softGlow(x - s * 0.22f, baseY - s * 0.21f, s * 0.42f, winColor, winAlpha * 0.75f)
    softGlow(x + s * 0.24f, baseY - s * 0.21f, s * 0.38f, winColor, winAlpha * 0.68f)
    drawRect(
        color = winColor,
        topLeft = Offset(x - s * 0.30f, baseY - s * 0.29f),
        size = Size(s * 0.16f, s * 0.17f),
        alpha = min(1f, winAlpha * 1.30f),
    )
    drawRect(
        color = winColor,
        topLeft = Offset(x + s * 0.14f, baseY - s * 0.29f),
        size = Size(s * 0.16f, s * 0.17f),
        alpha = min(1f, winAlpha * 1.30f),
    )
}

/**
 * 雪人：两个雪球（头大才可爱），**不戴帽子、不围围巾**。
 *
 * 帽子和围巾试过三版（横平带 / 同心圆弧环 / 椭圆前弧带）都被否 —— 环形布件只要形不准整张图就垮。
 * 现在头顶点一只红鸟（圣诞的红有了，造型还便宜），脸靠眼睛 / 胡萝卜鼻 / 微笑弧 / 腮红撑。
 */
private fun DrawScope.snowmanArt(x: Float, baseY: Float, ht: Float, alpha: Float, t: Float) {
    val rB = ht * 0.345f
    val rH = ht * 0.300f
    val cyB = baseY - rB * 0.86f
    val cyH = cyB - rB * 0.72f - rH * 0.66f

    fun snowBall(cx: Float, cy: Float, r: Float) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = alpha),
                0.34f to Color(0xFFFBFCFF).copy(alpha = alpha),
                0.72f to Color(0xFFEEF5FD).copy(alpha = alpha),
                1f to Color(0xFFD2E2F4).copy(alpha = alpha * 0.96f),
                center = Offset(cx - r * 0.32f, cy - r * 0.38f),
                radius = r * 1.52f,
            ),
            radius = r * 1.02f,
            center = Offset(cx, cy),
        )
        // 右下角补一道冷色反光，球才不是一张平白饼
        clipPath(Path().apply { addOval(Rect(center = Offset(cx, cy), radius = r * 0.985f)) }) {
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color(0xFFC6DCF2).copy(alpha = 0f),
                    1f to Color(0xFFBCD3EC).copy(alpha = alpha * 0.34f),
                    center = Offset(cx + r * 0.34f, cy + r * 0.30f),
                    radius = r * 1.02f,
                ),
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
            )
        }
    }

    // 地面投影：雪人才是「坐」在雪上
    drawOval(
        color = Color(0xFF9FB8D6),
        topLeft = Offset(x + rB * 0.12f - rB * 1.02f, baseY + rB * 0.03f - rB * 0.17f),
        size = Size(rB * 2.04f, rB * 0.34f),
        alpha = 0.22f,
    )

    // 手臂先画（藏在身体后面），从两侧斜上伸出，末端两个小杈
    val armStroke = maxOf(1.6f, ht * 0.020f)
    for (dir in intArrayOf(-1, 1)) {
        val ox = x + dir * rB * 0.72f
        val oy = cyB - rB * 0.02f
        val ex = ox + dir * rB * 0.70f
        val ey = oy - rB * if (dir > 0) 0.52f else 0.40f
        val arm = Path().apply {
            moveTo(ox, oy)
            quadraticBezierTo(ox + dir * rB * 0.48f, oy - rB * 0.10f, ex, ey)
        }
        drawPath(arm, Color(0xFF7A5238), alpha = alpha * 0.95f, style = Stroke(width = armStroke))
        val twigStroke = maxOf(1.3f, ht * 0.015f)
        for (k in 0 until 2) {
            val dx = if (k == 0) -0.20f else 0.16f
            val dy = if (k == 0) -0.46f else -0.34f
            val twig = Path().apply {
                moveTo(ex - dir * rB * 0.06f, ey + rB * 0.06f)
                lineTo(ex + dir * rB * dx, ey + rB * dy)
            }
            drawPath(twig, Color(0xFF7A5238), alpha = alpha * 0.95f, style = Stroke(width = twigStroke))
        }
    }

    // 身体
    snowBall(x, cyB, rB)

    // 头与身体的接缝：一道很淡的暗，别用椭圆（会压成盘子）
    clipPath(Path().apply { addOval(Rect(center = Offset(x, cyB), radius = rB * 0.985f)) }) {
        drawRect(
            brush = Brush.radialGradient(
                0f to Color(0xFF9FBBD9).copy(alpha = 0.30f),
                1f to Color(0xFF9FBBD9).copy(alpha = 0f),
                center = Offset(x, cyH + rH * 0.30f),
                radius = rH * 1.15f,
            ),
            topLeft = Offset(x - rB, cyB - rB),
            size = Size(rB * 2f, rB * 2f),
        )
    }

    // 纽扣
    for (i in 0 until 3) {
        drawCircle(
            color = Color(0xFF2E2B33),
            radius = rB * 0.042f,
            center = Offset(x, cyB - rB * 0.26f + i * rB * 0.26f),
            alpha = alpha * 0.90f,
        )
    }

    // 头
    snowBall(x, cyH, rH)

    // ---- 头顶的小红鸟 ----
    val bob = sin(TAU * 0.9f * t) * rH * 0.012f
    val bx = x + rH * 0.14f
    val by = cyH - rH * 0.94f + bob
    val bs = rH * 0.465f

    // 站立接触影
    drawOval(
        color = Color(0xFF9FBBD9),
        topLeft = Offset(bx + bs * 0.06f - bs * 0.52f, by + bs * 0.60f - bs * 0.16f),
        size = Size(bs * 1.04f, bs * 0.32f),
        alpha = 0.20f,
    )

    // 尾羽：三片细长的羽毛朝后下方
    for (k in 0 until 3) {
        val dy0 = 0.10f + k * 0.08f
        val dy1 = 0.14f + k * 0.16f
        drawLine(
            Color(0xFFB23522).copy(alpha = alpha * 0.96f),
            start = Offset(bx + bs * 0.30f, by + bs * dy0),
            end = Offset(bx + bs * 0.80f, by + bs * dy1),
            strokeWidth = maxOf(1.1f, bs * 0.115f),
        )
    }
    // 身体
    drawOval(
        brush = Brush.radialGradient(
            0f to Color(0xFFF4603F).copy(alpha = alpha),
            0.62f to Color(0xFFDE402A).copy(alpha = alpha),
            1f to Color(0xFFB93220).copy(alpha = alpha),
            center = Offset(bx - bs * 0.20f, by - bs * 0.24f),
            radius = bs * 0.86f,
        ),
        topLeft = Offset(bx - bs * 0.66f, by - bs * 0.56f),
        size = Size(bs * 1.32f, bs * 1.12f),
    )
    // 头
    drawCircle(Color(0xFFDE402A).copy(alpha = alpha), bs * 0.40f, Offset(bx - bs * 0.42f, by - bs * 0.34f))
    // 冠羽
    val crest = Path().apply {
        moveTo(bx - bs * 0.52f, by - bs * 0.70f)
        lineTo(bx - bs * 0.34f, by - bs * 1.02f)
        lineTo(bx - bs * 0.20f, by - bs * 0.66f)
        close()
    }
    drawPath(crest, Color(0xFFDE402A), alpha = alpha)
    // 黑面罩
    val mask = Path().apply {
        moveTo(bx - bs * 0.80f, by - bs * 0.44f)
        quadraticBezierTo(bx - bs * 0.60f, by - bs * 0.14f, bx - bs * 0.38f, by - bs * 0.24f)
        quadraticBezierTo(bx - bs * 0.44f, by - bs * 0.52f, bx - bs * 0.80f, by - bs * 0.44f)
        close()
    }
    drawPath(mask, Color(0xFF2A2028), alpha = alpha * 0.92f)
    // 喙
    val beak = Path().apply {
        moveTo(bx - bs * 0.78f, by - bs * 0.44f)
        lineTo(bx - bs * 1.12f, by - bs * 0.34f)
        lineTo(bx - bs * 0.76f, by - bs * 0.24f)
        close()
    }
    drawPath(beak, Color(0xFFF2A03A), alpha = alpha)
    // 眼
    drawCircle(Color(0xFF20191F), bs * 0.075f, Offset(bx - bs * 0.54f, by - bs * 0.46f), alpha = alpha)
    drawCircle(Color.White, bs * 0.030f, Offset(bx - bs * 0.56f, by - bs * 0.48f), alpha = alpha * 0.9f)
    // 腿
    for (dx in floatArrayOf(-0.16f, 0.10f)) {
        drawLine(
            Color(0xFF8A5A2B).copy(alpha = alpha * 0.9f),
            start = Offset(bx + bs * dx, by + bs * 0.48f),
            end = Offset(bx + bs * dx, by + bs * 0.66f),
            strokeWidth = maxOf(1.0f, bs * 0.055f),
        )
    }

    // ---- 脸 ----
    for (dx in floatArrayOf(-0.30f, 0.30f)) {
        drawOval(
            color = Color(0xFF2E2B33),
            topLeft = Offset(x + rH * dx - rH * 0.072f, cyH - rH * 0.10f - rH * 0.092f),
            size = Size(rH * 0.144f, rH * 0.184f),
            alpha = alpha * 0.94f,
        )
    }
    for (dx in floatArrayOf(-0.30f, 0.30f)) {
        drawCircle(
            Color.White,
            rH * 0.026f,
            Offset(x + rH * (dx - 0.020f), cyH + rH * (-0.10f - 0.032f)),
            alpha = alpha * 0.92f,
        )
    }
    softGlow(x - rH * 0.52f, cyH + rH * 0.14f, rH * 0.26f, Color(0xFFFF8E77), alpha * 0.42f)
    softGlow(x + rH * 0.46f, cyH + rH * 0.12f, rH * 0.26f, Color(0xFFFF8E77), alpha * 0.42f)

    // 胡萝卜鼻
    val nose = Path().apply {
        moveTo(x + rH * 0.02f, cyH + rH * 0.03f)
        lineTo(x + rH * 0.62f, cyH + rH * 0.25f)
        lineTo(x + rH * 0.02f, cyH + rH * 0.26f)
        close()
    }
    drawPath(
        nose,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFFFA53C).copy(alpha = alpha), Color(0xFFE1621B).copy(alpha = alpha)),
            start = Offset(x, cyH + rH * 0.10f),
            end = Offset(x + rH * 0.74f, cyH + rH * 0.26f),
        ),
    )

    // 微笑弧
    val smile = Path().apply {
        moveTo(x - rH * 0.15f, cyH + rH * 0.38f)
        quadraticBezierTo(x, cyH + rH * 0.50f, x + rH * 0.15f, cyH + rH * 0.38f)
    }
    drawPath(
        smile,
        Color(0xFF2E2B33),
        alpha = alpha * 0.86f,
        style = Stroke(width = maxOf(1.3f, rH * 0.040f)),
    )

    // 身体下半的几粒雪，轻轻点一点就够
    for (i in 0 until 4) {
        val ang = 0.35f + i * 0.30f
        drawCircle(
            Color.White,
            rB * 0.026f,
            Offset(x + cos(ang) * rB * 0.66f, cyB + sin(ang) * rB * 0.62f),
            alpha = alpha * 0.75f,
        )
    }
}

/** 雪地里的小礼物盒（雪人脚下用）。 */
private fun DrawScope.giftBox(x: Float, baseY: Float, gw: Float, color: Color, alpha: Float) {
    val gh = gw * 0.78f
    drawOval(
        color = Color(0xFF9FB8D4),
        topLeft = Offset(x + gw * 0.10f - gw * 0.66f, baseY - gw * 0.14f),
        size = Size(gw * 1.32f, gw * 0.28f),
        alpha = 0.22f,
    )
    drawRect(color, topLeft = Offset(x - gw / 2f, baseY - gh), size = Size(gw, gh), alpha = alpha)
    val ribbon = Color(0xFFFFF3D8)
    drawRect(
        ribbon,
        topLeft = Offset(x - gw * 0.075f, baseY - gh),
        size = Size(gw * 0.15f, gh),
        alpha = alpha * 0.92f,
    )
    drawRect(
        ribbon,
        topLeft = Offset(x - gw / 2f, baseY - gh * 0.60f),
        size = Size(gw, gh * 0.12f),
        alpha = alpha * 0.92f,
    )
    drawRect(
        Color.White,
        topLeft = Offset(x - gw / 2f, baseY - gh),
        size = Size(gw, gh * 0.14f),
        alpha = alpha * 0.86f,
    )
}

/** 年桔的叶：两段弧合成柳叶形，深绿、薄边。 */
private fun DrawScope.kqLeaf(x: Float, y: Float, ang: Float, len: Float, alpha: Float) {
    val wd = len * 0.40f
    rotate(degrees = ang * 180f / PI.toFloat(), pivot = Offset(x, y)) {
        val leaf = Path().apply {
            moveTo(x, y)
            quadraticBezierTo(x + wd, y - len * 0.46f, x, y - len)
            quadraticBezierTo(x - wd, y - len * 0.46f, x, y)
            close()
        }
        drawPath(leaf, Color(0xFF3F7A50), alpha = alpha * 0.96f)
        drawLine(
            Color(0xFF2C5A3A).copy(alpha = alpha * 0.45f),
            start = Offset(x, y - len * 0.10f),
            end = Offset(x, y - len * 0.84f),
            strokeWidth = maxOf(0.6f, len * 0.045f),
        )
    }
}

/** 金桔果：橙红球 + 高光 + 果蒂。 */
private fun DrawScope.kqFruit(x: Float, y: Float, r: Float, alpha: Float) {
    softGlow(x, y, r * 1.9f, Color(0xFFFFB25C), alpha * 0.34f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFFFFC06A).copy(alpha = alpha),
            0.42f to Color(0xFFF0822C).copy(alpha = alpha),
            1f to Color(0xFFD2541B).copy(alpha = alpha),
            center = Offset(x - r * 0.34f, y - r * 0.38f),
            radius = r * 1.45f,
        ),
        radius = r,
        center = Offset(x, y),
    )
    drawCircle(Color(0xFFFFF0D2), r * 0.24f, Offset(x - r * 0.30f, y - r * 0.34f), alpha = alpha * 0.72f)
    drawCircle(Color(0xFF2F5C38), r * 0.16f, Offset(x, y - r * 0.86f), alpha = alpha * 0.80f)
}

/**
 * 盆里的金桔树：三根主枝从盆口长出，枝上布叶、枝端垂果。
 *
 * 枝梢与分叉末端都要补叶补果 —— 第一版只沿主枝撒，枝尖光秃秃地戳出去，看着像枯枝。
 */
private fun DrawScope.kumquatTree(cx: Float, baseY: Float, w0: Float, alpha: Float) {
    val hw = w0 * 1.06f
    // 每条主枝：x0,y0, cx,cy, x1,y1
    val stems = arrayOf(
        floatArrayOf(cx - w0 * 0.06f, baseY - w0 * 0.04f, cx - hw * 0.52f, baseY - w0 * 0.78f, cx - hw * 0.98f, baseY - w0 * 1.52f),
        floatArrayOf(cx + w0 * 0.02f, baseY - w0 * 0.04f, cx + hw * 0.18f, baseY - w0 * 0.96f, cx + hw * 0.06f, baseY - w0 * 1.78f),
        floatArrayOf(cx + w0 * 0.08f, baseY - w0 * 0.04f, cx + hw * 0.58f, baseY - w0 * 0.70f, cx + hw * 0.94f, baseY - w0 * 1.42f),
    )

    fun bez(s: FloatArray, u: Float): Pair<Float, Float> {
        val v = 1f - u
        val px = v * v * s[0] + 2f * v * u * s[2] + u * u * s[4]
        val py = v * v * s[1] + 2f * v * u * s[3] + u * u * s[5]
        return px to py
    }

    // 枝干 + 分叉（末端补叶补果）
    stems.forEachIndexed { i, s ->
        val trunk = Path().apply {
            moveTo(s[0], s[1])
            quadraticBezierTo(s[2], s[3], s[4], s[5])
        }
        drawPath(
            trunk,
            Color(0xFF6E4A2E),
            alpha = alpha * 0.95f,
            style = Stroke(width = maxOf(1.4f, w0 * (0.058f - i * 0.008f))),
        )
        for (k in 1..3) {
            val (bx0, by0) = bez(s, 0.30f + k * 0.19f)
            val dir = if (k % 2 == 1) 1f else -1f
            val tx = bx0 + dir * hw * 0.46f
            val ty = by0 - w0 * 0.42f
            val twig = Path().apply {
                moveTo(bx0, by0)
                quadraticBezierTo(bx0 + dir * hw * 0.30f, by0 - w0 * 0.16f, tx, ty)
            }
            drawPath(
                twig,
                Color(0xFF6E4A2E),
                alpha = alpha * 0.95f,
                style = Stroke(width = maxOf(1.0f, w0 * 0.022f)),
            )
            kqLeaf(tx, ty + w0 * 0.05f, dir * 0.30f, w0 * 0.40f, alpha * 0.95f)
            kqLeaf(tx + dir * w0 * 0.05f, ty + w0 * 0.10f, dir * 0.95f, w0 * 0.34f, alpha * 0.92f)
            kqFruit(tx + dir * w0 * 0.06f, ty + w0 * 0.16f, w0 * 0.068f, alpha)
        }
    }

    // 沿枝布叶
    stems.forEachIndexed { i, s ->
        for (k in 0 until 13) {
            val (bx0, by0) = bez(s, 0.10f + k * 0.072f)
            val dir = if (k % 2 == 1) 1f else -1f
            val ang = dir * (0.62f + rnd(311 + i, k) * 0.62f) + (i - 1) * 0.14f
            kqLeaf(bx0 + dir * w0 * 0.05f, by0, ang, w0 * (0.34f + rnd(313 + i, k) * 0.16f), alpha * 0.95f)
        }
    }

    // 枝梢再补一簇叶
    stems.forEachIndexed { i, s ->
        val (tx, ty) = bez(s, 0.985f)
        kqLeaf(tx, ty, (i - 1) * 0.5f, w0 * 0.38f, alpha * 0.95f)
        kqLeaf(tx + w0 * 0.06f, ty + w0 * 0.06f, 1.05f + i * 0.2f, w0 * 0.32f, alpha * 0.92f)
        kqLeaf(tx - w0 * 0.06f, ty + w0 * 0.08f, -1.05f - i * 0.2f, w0 * 0.32f, alpha * 0.92f)
    }

    // 果：成串垂在枝上
    stems.forEachIndexed { i, s ->
        for (k in 0 until 10) {
            val u = 0.26f + rnd(321 + i, k) * 0.68f
            val (bx0, by0) = bez(s, u)
            val dx = (rnd(323 + i, k) - 0.5f) * hw * 0.72f
            val dy = (rnd(325 + i, k) - 0.25f) * w0 * 0.34f
            kqFruit(bx0 + dx, by0 + dy, w0 * (0.060f + rnd(327 + i, k) * 0.032f), alpha)
        }
    }
}

/**
 * 立在地上的爆竹：细长的红筒 + 两道细金箍 + 腰间金菱 + 引线。
 *
 * 筒径与高度比控制在 1:5 左右 —— 第一版 1:4 出头，读出来是「炸弹」。
 */
private fun DrawScope.firecrackerStick(x: Float, baseY: Float, s: Float, alpha: Float, tilt: Float) {
    translate(left = x, top = baseY) {
        rotate(degrees = tilt * 180f / PI.toFloat(), pivot = Offset.Zero) {
            val wd = s * 0.200f
            val r = wd * 0.10f
            val body = Path().apply {
                moveTo(-wd * 0.5f + r, -s)
                lineTo(wd * 0.5f - r, -s)
                quadraticBezierTo(wd * 0.5f, -s, wd * 0.5f, -s + r)
                lineTo(wd * 0.5f, -r)
                quadraticBezierTo(wd * 0.5f, 0f, wd * 0.5f - r, 0f)
                lineTo(-wd * 0.5f + r, 0f)
                quadraticBezierTo(-wd * 0.5f, 0f, -wd * 0.5f, -r)
                lineTo(-wd * 0.5f, -s + r)
                quadraticBezierTo(-wd * 0.5f, -s, -wd * 0.5f + r, -s)
                close()
            }
            drawPath(
                body,
                brush = Brush.linearGradient(
                    0f to Color(0xFFB01A0B).copy(alpha = alpha),
                    0.34f to Color(0xFFF2462A).copy(alpha = alpha),
                    0.72f to Color(0xFFDF3318).copy(alpha = alpha),
                    1f to Color(0xFFA51206).copy(alpha = alpha),
                    start = Offset(-wd * 0.5f, 0f),
                    end = Offset(wd * 0.5f, 0f),
                ),
            )
            // 两道细金箍
            val gold = Color(0xFFF7C955)
            drawRect(gold, topLeft = Offset(-wd * 0.5f, -s * 0.82f), size = Size(wd, s * 0.052f), alpha = alpha * 0.96f)
            drawRect(gold, topLeft = Offset(-wd * 0.5f, -s * 0.22f), size = Size(wd, s * 0.052f), alpha = alpha * 0.96f)
            // 腰间的金色菱形
            val dr = wd * 0.30f
            rotate(degrees = 45f, pivot = Offset(0f, -s * 0.520f)) {
                drawRect(
                    gold,
                    topLeft = Offset(-dr * 0.5f, -s * 0.520f - dr * 0.5f),
                    size = Size(dr, dr),
                    alpha = alpha * 0.90f,
                )
            }
            // 引线：短、细，斜上一小段
            val fuse = Path().apply {
                moveTo(0f, -s)
                quadraticBezierTo(s * 0.075f, -s * 1.08f, s * 0.020f, -s * 1.17f)
            }
            drawPath(
                fuse,
                Color(0xFF8A5A2B),
                alpha = alpha * 0.78f,
                style = Stroke(width = maxOf(0.7f, s * 0.022f)),
            )
        }
    }
}

/** 爆竹引线处的火星：中心白闪 + 短星芒 + 斜向上的小火星（带抛体下坠）。 */
private fun DrawScope.sparkBurst(x: Float, y: Float, s: Float, seed: Int, alpha: Float) {
    if (alpha <= 0.02f) return
    softGlow(x, y, s * 1.6f, Color(0xFFFFC85C), alpha * 0.50f)
    softGlow(x, y, s * 0.60f, Color.White, alpha * 0.95f)

    val rayAlpha = alpha * 0.92f
    for (i in 0 until 8) {
        val ang = (i / 8f) * TAU + rnd(seed, 0) * 0.6f
        val l = s * (0.95f + rnd(seed + 3, i) * 1.05f)
        drawLine(
            Color(0xFFFFF6DC).copy(alpha = rayAlpha),
            start = Offset(x + cos(ang) * s * 0.30f, y + sin(ang) * s * 0.30f),
            end = Offset(x + cos(ang) * l, y + sin(ang) * l),
            strokeWidth = maxOf(1.2f, s * 0.085f),
        )
    }
    for (i in 0 until 16) {
        val ang = -PI.toFloat() / 2f + (rnd(seed + 5, i) - 0.5f) * 2.3f
        val d = s * (0.35f + rnd(seed + 7, i) * 1.25f)
        val px = x + cos(ang) * d
        val py = y + sin(ang) * d + d * d * 0.0020f
        softGlow(px, py, s * 0.42f, Color(0xFFFFC24D), alpha * 0.66f)
        drawCircle(Color(0xFFFFF6DC), maxOf(0.7f, s * 0.085f), Offset(px, py), alpha = alpha * 0.98f)
    }
}

/** 五瓣梅花：五个圆瓣 + 金蕊。 */
private fun DrawScope.plumBlossom(x: Float, y: Float, r: Float, alpha: Float, rot: Float = 0f) {
    softGlow(x, y, r * 2.6f, Color(0xFFE0453A), alpha * 0.24f)
    for (i in 0 until 5) {
        val ang = rot + (i / 5f) * TAU
        val px = x + cos(ang) * r * 0.74f
        val py = y + sin(ang) * r * 0.74f
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFFFF9C86).copy(alpha = alpha),
                0.55f to Color(0xFFE0453A).copy(alpha = alpha * 0.96f),
                1f to Color(0xFFB92C28).copy(alpha = alpha * 0.60f),
                center = Offset(px, py),
                radius = r * 0.60f,
            ),
            radius = r * 0.38f,
            center = Offset(px, py),
        )
    }
    for (i in 0 until 5) {
        val ang = rot + (i / 5f) * TAU + 0.6f
        drawCircle(
            Color(0xFFFFD06A),
            maxOf(0.5f, r * 0.095f),
            Offset(x + cos(ang) * r * 0.24f, y + sin(ang) * r * 0.24f),
            alpha = alpha * 0.92f,
        )
    }
    drawCircle(Color(0xFFFFF0C0), maxOf(0.5f, r * 0.12f), Offset(x, y), alpha = alpha * 0.95f)
}

/** 梅枝：递归分叉，末端成簇开花。 */
private fun DrawScope.plumBranch(
    x: Float,
    y: Float,
    ang: Float,
    len: Float,
    wd: Float,
    depth: Int,
    seed: Int,
    ink: Color,
    alpha: Float,
    bloomAlpha: Float,
) {
    val x2 = x + cos(ang) * len
    val y2 = y + sin(ang) * len
    val bend = (rnd(seed, 1) - 0.5f) * len * 0.5f
    val mx = (x + x2) / 2f + cos(ang + PI.toFloat() / 2f) * bend
    val my = (y + y2) / 2f + sin(ang + PI.toFloat() / 2f) * bend
    val path = Path().apply {
        moveTo(x, y)
        quadraticBezierTo(mx, my, x2, y2)
    }
    drawPath(path, ink, alpha = alpha, style = Stroke(width = maxOf(0.9f, wd)))

    if (depth <= 0) {
        // 末端：两三朵挤在一起，大小错开才像真花
        val n = if (rnd(seed, 3) > 0.55f) 3 else 2
        for (i in 0 until n) {
            val rr = len * (0.26f + rnd(seed + 5, i) * 0.18f)
            val dx = (rnd(seed + 9, i) - 0.5f) * len * 0.86f
            val dy = (rnd(seed + 13, i) - 0.5f) * len * 0.80f
            plumBlossom(x2 + dx, y2 + dy, rr, bloomAlpha * (0.78f + rnd(seed + 17, i) * 0.22f), rnd(seed + 19, i) * 1.2f)
        }
        return
    }
    // 枝条中段零星开花，避免末端一堆、中间空荡
    if (depth == 1) {
        plumBlossom(mx, my, len * 0.22f, bloomAlpha * 0.82f, rnd(seed + 23, 1) * 1.2f)
    }
    plumBranch(x2, y2, ang - 0.38f - rnd(seed + 2, 0) * 0.34f, len * 0.72f, wd * 0.60f, depth - 1, seed + 31, ink, alpha * 0.96f, bloomAlpha)
    plumBranch(x2, y2, ang + 0.34f + rnd(seed + 2, 1) * 0.34f, len * 0.66f, wd * 0.56f, depth - 1, seed + 37, ink, alpha * 0.96f, bloomAlpha)
}

/** 孔明灯：一盏鼓腹的小灯笼 + 底部暖光。 */
private fun DrawScope.kongmingLantern(x: Float, y: Float, s: Float, color: Color, alpha: Float) {
    softGlow(x, y, s * 3f, color, alpha * 0.26f)
    val body = Path().apply {
        moveTo(x - s * 0.26f, y + s * 0.56f)
        quadraticBezierTo(x - s * 0.40f, y + s * 0.06f, x - s * 0.34f, y - s * 0.30f)
        quadraticBezierTo(x, y - s * 0.50f, x + s * 0.34f, y - s * 0.30f)
        quadraticBezierTo(x + s * 0.40f, y + s * 0.06f, x + s * 0.26f, y + s * 0.56f)
        close()
    }
    drawPath(
        body,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFFFE0A8).copy(alpha = alpha * 0.95f),
                color.copy(alpha = alpha * 0.88f),
            ),
            start = Offset(x, y - s * 0.30f),
            end = Offset(x, y + s * 0.44f),
        ),
    )
    softGlow(x, y + s * 0.34f, s * 0.52f, Color(0xFFFFF0C8), alpha * 0.85f)
}

/**
 * 烟花：三层星（长拖尾 / 中束 / 内蕊）+ 爆点白闪 + 重力下坠 + 余烬 + 烟。
 *
 * 关键在「头的亮度分布」：星越往外越亮、尾巴向中心渐隐 —— 均匀等长的细线只会像蒲公英。
 */
private fun DrawScope.firework(x: Float, y: Float, R: Float, color: Color, p: Float, alpha: Float, core: Color = Color.White) {
    if (p <= 0f || p >= 1f) return
    val e = 1f - (1f - p).pow(2.8f)
    val r = R * e
    val rise = min(1f, p / 0.045f)
    val fall = (1f - maxOf(0f, (p - 0.05f) / 0.95f)).pow(1.55f)
    val fade = rise * fall * alpha
    val drop = p * p * R * 0.26f
    if (fade <= 0.01f) return

    // 爆点白闪 + 十字眩光（只在前 22%）
    val flash = maxOf(0f, 1f - p / 0.22f)
    if (flash > 0.02f) {
        softGlow(x, y, R * (0.30f + flash * 0.55f), color, 0.55f * flash)
        softGlow(x, y, R * 0.16f, core, 0.95f * flash * alpha)
        val sl = R * 0.85f * flash
        drawLine(Color.White.copy(alpha = 0.50f * flash), Offset(x - sl, y), Offset(x + sl, y), strokeWidth = maxOf(1f, R * 0.012f))
        drawLine(Color.White.copy(alpha = 0.50f * flash), Offset(x, y - sl), Offset(x, y + sl), strokeWidth = maxOf(1f, R * 0.012f))
    }

    // 外层：长拖尾，细而疏，头亮点在末端
    val inner = 0.14f + 0.34f * e
    for (i in 0 until 22) {
        val ang = (i / 22f) * TAU + (rnd(801, i) - 0.5f) * 0.16f
        val len = r * (0.82f + rnd(803, i) * 0.26f)
        val c = cos(ang)
        val s = sin(ang)
        val x1 = x + c * len
        val y1 = y + s * len + drop
        val xm = x + c * len * (inner + (1f - inner) * 0.55f)
        val ym = y + s * len * (inner + (1f - inner) * 0.55f) + drop * 0.75f
        val x0 = x + c * len * inner
        val y0 = y + s * len * inner + drop * 0.45f
        drawLine(
            color.copy(alpha = fade * 0.34f),
            Offset(x0, y0), Offset(xm, ym),
            strokeWidth = maxOf(0.9f, R * 0.0140f),
        )
        drawLine(
            color.copy(alpha = fade * 0.70f),
            Offset(xm, ym), Offset(x1, y1),
            strokeWidth = maxOf(1.0f, R * 0.0165f),
        )
        drawCircle(core, maxOf(0.8f, R * 0.0105f), Offset(x1, y1), alpha = fade * 0.86f)
    }

    // 中层：短一些、粗一些，撑住中段的密度
    for (i in 0 until 20) {
        val ang = ((i + 0.5f) / 20f) * TAU + (rnd(811, i) - 0.5f) * 0.28f
        val len = r * (0.34f + rnd(813, i) * 0.24f)
        val c = cos(ang)
        val s = sin(ang)
        val x1 = x + c * len
        val y1 = y + s * len + drop * 0.70f
        drawLine(
            color.copy(alpha = fade * 0.90f),
            Offset(x + c * len * (0.30f + 0.20f * e), y + s * len * (0.30f + 0.20f * e)),
            Offset(x1, y1),
            strokeWidth = maxOf(1.2f, R * 0.028f),
        )
        drawCircle(core, maxOf(1.0f, R * 0.018f), Offset(x1, y1), alpha = fade * 0.86f)
    }

    // 内层：短而亮的蕊
    for (i in 0 until 10) {
        val ang = (i / 10f) * TAU + 0.3f
        val len = r * 0.28f
        drawLine(
            core.copy(alpha = fade * 0.80f),
            Offset(x, y),
            Offset(x + cos(ang) * len, y + sin(ang) * len + drop * 0.40f),
            strokeWidth = maxOf(1.4f, R * 0.034f),
        )
    }

    // 余烬：脱离主体、散在外圈，带闪烁
    for (i in 0 until 10) {
        val ang = rnd(821, i) * TAU
        val d = r * (1.02f + rnd(823, i) * 0.30f)
        val tw = 0.45f + 0.55f * abs(sin(p * 34f + i * 1.7f))
        drawCircle(
            color,
            maxOf(0.6f, R * 0.0068f),
            Offset(x + cos(ang) * d, y + sin(ang) * d * 0.94f + drop * 1.1f),
            alpha = fade * 0.55f * tw,
        )
    }

    // 照亮的夜空 + 中心余晖 + 后期烟雾
    val ballR = R * 0.52f * e
    if (ballR > 1f) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = fade * 0.42f),
                0.55f to color.copy(alpha = fade * 0.20f),
                1f to color.copy(alpha = 0f),
                center = Offset(x, y + drop * 0.30f),
                radius = ballR,
            ),
            radius = ballR,
            center = Offset(x, y + drop * 0.30f),
        )
    }
    softGlow(x, y + drop * 0.40f, R * 0.72f, color, fade * 0.40f)
    softGlow(x, y, R * 0.22f, core, fade * 0.30f)
    if (p > 0.25f) {
        softGlow(x, y + drop * 0.60f, R * 0.82f, Color(0xFF9A8FA8), min(1f, (p - 0.25f) / 0.55f) * 0.16f)
    }
}

// ============================================================================
// 日间 · 静态
// ============================================================================

/** 雪原松林：冷白天空 + 低日暖光 + 两簇松林（中间留白给车名和车模）+ 树上挂球。 */
internal fun DrawScope.drawXmasSnowfield(p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.52f, 0.58f)
    fDusk(Color(0xFFFFE2B8), 0.30f, 0.40f)
    softGlow(w * 0.76f, h * 0.13f, w * 0.52f, p.c, 0.55f)
    ridge(h * 0.415f, h * 0.046f, 1.5f, p.a, 0.38f, waves = 1.2f)
    mistBand(h * 0.450f, h * 0.095f, Color.White, 0.42f)
    // 近景雪坡先立起来，树压在上面才有纵深
    ridge(h * 0.592f, h * 0.040f, 0.8f, p.a, 0.32f, waves = 1.1f)
    ridge(h * 0.566f, h * 0.042f, 0.8f, Color.White, 1.00f, waves = 1.1f)

    val trees = arrayOf(
        floatArrayOf(0.066f, 0.645f, 0.245f), floatArrayOf(0.170f, 0.695f, 0.190f),
        floatArrayOf(0.264f, 0.615f, 0.155f), floatArrayOf(0.340f, 0.678f, 0.120f),
        floatArrayOf(0.686f, 0.618f, 0.222f), floatArrayOf(0.792f, 0.688f, 0.178f),
        floatArrayOf(0.880f, 0.632f, 0.142f),
    )
    for (tr in trees) pineTree(w * tr[0], h * tr[1], h * tr[2], p.ink, 0.54f)

    // 树上挂球：红 / 金交替，成串往下挂
    trees.forEachIndexed { i, tr ->
        for (k in 0 until 4) {
            val yy = h * tr[1] - h * tr[2] * (0.20f + k * 0.19f)
            val xx = w * tr[0] + (if (k % 2 == 1) -1f else 1f) * h * tr[2] * (0.16f - k * 0.02f)
            ornamentBall(xx, yy, h * tr[2] * 0.034f, if (k % 2 == 1) Color(0xFFFFD05C) else Color(0xFFE8503A), 0.95f)
        }
    }

    // 空中几片雪（静态也得有雪，否则不像圣诞）
    for (i in 0 until 26) {
        fFlake(rnd(87, i) * w, rnd(89, i) * h * 0.54f, h * (0.008f + rnd(91, i) * 0.011f), 0.42f + rnd(93, i) * 0.33f)
    }
    // 雪面碎光
    for (i in 0 until 30) {
        drawCircle(
            Color.White,
            h * 0.0024f,
            Offset(rnd(71, i) * w, h * (0.575f + rnd(73, i) * 0.16f)),
            alpha = 0.35f + rnd(77, i) * 0.45f,
        )
    }
}

/** 金桔贺岁：白墙不见，改成一盆年桔摆台面上 —— 橙红果 + 深绿叶 + 盆上菱福。 */
internal fun DrawScope.drawSpringKumquat(p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.50f, 0.56f)
    softGlow(w * 0.22f, h * 0.09f, w * 0.56f, p.c, 0.46f)
    softGlow(w * 0.80f, h * 0.14f, w * 0.34f, Color(0xFFFFE7B8), 0.26f)
    ridge(h * 0.318f, h * 0.024f, 1.6f, p.a, 0.16f, waves = 1.0f)
    mistBand(h * 0.348f, h * 0.070f, Color.White, 0.38f)

    // 台面：一条浅色桌面 + 高光，盆栽才站得住
    val tY = h * 0.648f
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color(0xFFFFFCF4).copy(alpha = 0.99f),
            0.45f to Color(0xFFF8EFDF).copy(alpha = 0.97f),
            1f to Color(0xFFE7D9C3).copy(alpha = 0.95f),
            startY = tY,
            endY = h * 0.90f,
        ),
        topLeft = Offset(0f, tY),
        size = Size(w, h * 0.90f - tY),
    )
    drawRect(Color(0xFFD6C3A9), topLeft = Offset(0f, tY), size = Size(w, h * 0.0032f), alpha = 0.50f)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFFF6E2).copy(alpha = 0.55f), Color(0xFFFFF6E2).copy(alpha = 0f)),
            startY = tY,
            endY = tY + h * 0.030f,
        ),
        topLeft = Offset(0f, tY),
        size = Size(w, h * 0.030f),
    )

    // 花盆：白瓷（略带青），上宽下窄，盆口一道金线
    val cx = w * 0.455f
    val potTop = h * 0.492f
    val potBot = tY + h * 0.002f
    val wTop = w * 0.196f
    val wBot = w * 0.148f

    drawOval(
        color = Color(0xFFB99F86),
        topLeft = Offset(cx + wBot * 0.10f - wBot * 0.92f, potBot + h * 0.006f - h * 0.012f),
        size = Size(wBot * 1.84f, h * 0.024f),
        alpha = 0.28f,
    )
    val pot = Path().apply {
        moveTo(cx - wTop * 0.5f, potTop)
        lineTo(cx + wTop * 0.5f, potTop)
        quadraticBezierTo(cx + wBot * 0.52f, potBot - h * 0.010f, cx + wBot * 0.46f, potBot)
        lineTo(cx - wBot * 0.46f, potBot)
        quadraticBezierTo(cx - wBot * 0.52f, potBot - h * 0.010f, cx - wTop * 0.5f, potTop)
        close()
    }
    drawPath(
        pot,
        brush = Brush.linearGradient(
            0f to Color(0xFFDCE7E2).copy(alpha = 0.99f),
            0.32f to Color(0xFFFBFDFB).copy(alpha = 1f),
            0.72f to Color(0xFFEAF1ED).copy(alpha = 0.99f),
            1f to Color(0xFFC7D6CF).copy(alpha = 0.98f),
            start = Offset(cx - wTop * 0.5f, 0f),
            end = Offset(cx + wTop * 0.5f, 0f),
        ),
    )
    // 盆口 + 金线
    val gold = Color(0xFFF7C955)
    drawRect(gold, topLeft = Offset(cx - wTop * 0.50f, potTop + h * 0.006f), size = Size(wTop, h * 0.0042f), alpha = 0.85f)
    drawRect(
        gold,
        topLeft = Offset(cx - wTop * 0.44f, potBot - h * 0.030f),
        size = Size(wTop * 0.88f, h * 0.0032f),
        alpha = 0.55f,
    )
    // 盆口里的土
    drawOval(
        color = Color(0xFF4A3524),
        topLeft = Offset(cx - wTop * 0.46f, potTop + h * 0.004f - h * 0.010f),
        size = Size(wTop * 0.92f, h * 0.020f),
        alpha = 0.90f,
    )

    kumquatTree(cx, potTop + h * 0.004f, wTop * 0.98f, 0.97f)

    // 盆上贴一枚菱形福字：年味落在盆上，比枝上挂一堆小红牌干净
    val fx = cx
    val fy = potBot - h * 0.040f
    val fs = w * 0.046f
    rotate(degrees = 45f, pivot = Offset(fx, fy)) {
        drawRect(Color(0xFFD63A24), topLeft = Offset(fx - fs * 0.5f, fy - fs * 0.5f), size = Size(fs, fs), alpha = 0.96f)
        drawRect(
            color = Color(0xFFF7C955),
            topLeft = Offset(fx - fs * 0.40f, fy - fs * 0.40f),
            size = Size(fs * 0.80f, fs * 0.80f),
            alpha = 0.90f,
            style = Stroke(width = maxOf(0.9f, fs * 0.075f)),
        )
        // 「福」的笔画化成一块块矩形，远看是个字，近看不会糊成一团
        drawRect(gold, topLeft = Offset(fx - fs * 0.085f, fy - fs * 0.24f), size = Size(fs * 0.17f, fs * 0.48f), alpha = 0.92f)
        drawRect(gold, topLeft = Offset(fx - fs * 0.24f, fy - fs * 0.085f), size = Size(fs * 0.48f, fs * 0.17f), alpha = 0.92f)
        drawRect(gold, topLeft = Offset(fx - fs * 0.16f, fy + fs * 0.16f), size = Size(fs * 0.32f, fs * 0.055f), alpha = 0.92f)
    }

    // 台面上散落的两粒小金桔 + 一片叶
    kqFruit(w * 0.196f, tY + h * 0.028f, w * 0.020f, 0.90f)
    kqFruit(w * 0.700f, tY + h * 0.020f, w * 0.016f, 0.86f)
    kqLeaf(w * 0.760f, tY + h * 0.030f, 2.3f, w * 0.040f, 0.72f)

    bokeh(
        listOf(
            BokehDot(0.140f, 0.150f, 0.030f, Color(0xFFFFD98A), 0.34f, 0.0f),
            BokehDot(0.640f, 0.100f, 0.024f, Color(0xFFFFE4A8), 0.26f, 1.4f),
            BokehDot(0.910f, 0.235f, 0.018f, Color(0xFFFFD98A), 0.22f, 2.6f),
        ),
        t = 0f,
        drift = false,
    )
    for (i in 0 until 14) {
        fFlake(rnd(241, i) * w, rnd(243, i) * h * 0.58f, h * (0.004f + rnd(245, i) * 0.004f), 0.40f + rnd(247, i) * 0.30f)
    }
}

// ============================================================================
// 日间 · 动态
// ============================================================================

/** 雪地雪人：远山 + 雪地 + 两球雪人（头顶红鸟）+ 三只礼物盒 + 三层落雪。 */
internal fun DrawScope.drawXmasSnowman(t: Float, p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.52f, 0.58f)
    fDusk(Color(0xFFFFE2B8), 0.34f, 0.42f)
    // 低日照：一团暖光压在雪人右后方，雪面才有反光
    softGlow(w * 0.80f, h * 0.16f, w * 0.50f, p.c, 0.42f)
    ridge(h * 0.470f, h * 0.040f, 1.15f, p.a, 0.26f, waves = 1.2f)
    ridge(h * 0.505f, h * 0.030f, 0.85f, Color.White, 0.52f, waves = 1.2f)
    mistBand(h * 0.540f, h * 0.070f, Color.White, 0.38f)
    ridge(h * 0.648f, h * 0.026f, 0.9f, Color.White, 0.98f, waves = 1.1f)
    softGlow(w * 0.50f, h * 0.680f, w * 0.60f, Color(0xFFFFDFA8), 0.16f)

    // 主体雪人：脚底 0.700h，总高约 0.52h
    val baseY = h * 0.700f
    val ht = h * 0.520f
    softGlow(w * 0.435f, baseY - ht * 0.46f, ht * 1.15f, Color(0xFFFFE7BE), 0.18f)
    snowmanArt(w * 0.435f, baseY, ht, 0.96f, t)

    // 脚下一小堆雪 + 三只礼物盒，交代「刚堆好」
    drawOval(
        color = Color.White,
        topLeft = Offset(w * 0.300f - w * 0.075f, baseY + h * 0.012f - h * 0.018f),
        size = Size(w * 0.150f, h * 0.036f),
        alpha = 0.96f,
    )
    giftBox(w * 0.150f, baseY + h * 0.004f, w * 0.086f, Color(0xFFF5C452), 0.92f)
    giftBox(w * 0.700f, baseY + h * 0.006f, w * 0.098f, Color(0xFFD8452E), 0.95f)
    giftBox(w * 0.845f, baseY + h * 0.004f, w * 0.070f, Color(0xFFF5C452), 0.90f)

    // 落雪三层：近处大而慢，远处小而密
    val layers = arrayOf(
        floatArrayOf(0.0140f, 1.00f, 0.95f),
        floatArrayOf(0.0095f, 0.66f, 0.78f),
        floatArrayOf(0.0060f, 0.42f, 0.58f),
    )
    layers.forEachIndexed { li, ly ->
        for (i in 0 until 26) {
            val seed = 20 + li * 40 + i
            val x0 = rnd(seed, i) * w
            val drift = (rnd(seed + 5, i) - 0.5f) * w * 0.10f
            val y = ((t * ly[1] + rnd(seed + 9, i)) % 1f) * h * 0.76f
            val x = x0 + drift * sin(TAU * t + i)
            fFlake(x, y, h * ly[0] * 0.6f, ly[2] * (0.55f + rnd(seed + 3, i) * 0.45f))
        }
    }
}

/** 金屑升起：中央暖金光柱 + 三层金屑从下往上飘。 */
internal fun DrawScope.drawNewyearConfetti(t: Float, p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.52f, 0.60f)
    // 中央暖金光柱，节日的「亮」主要来自这里
    softGlow(w * 0.50f, h * 0.46f, w * 0.92f, p.c, 0.26f)
    softGlow(w * 0.50f, h * 0.30f, w * 0.46f, Color(0xFFFFDF9A), 0.22f)
    softGlow(w * 0.84f, h * 0.12f, w * 0.38f, p.c, 0.40f)
    ridge(h * 0.520f, h * 0.026f, 1.3f, p.a, 0.20f, waves = 1.1f)
    mistBand(h * 0.560f, h * 0.13f, Color(0xFFFFF6E2), 0.48f)
    // 底部升起的一团暖光：用柔光叠，不用硬边梯形（梯形在浅底上像贴了一块色卡）
    softGlow(w * 0.50f, h * 0.560f, w * 0.72f, Color(0xFFFFC24D), 0.22f)
    softGlow(w * 0.50f, h * 0.430f, w * 0.52f, Color(0xFFFFD98A), 0.16f)
    softGlow(w * 0.50f, h * 0.300f, w * 0.34f, Color(0xFFFFE8B8), 0.12f)

    bokeh(
        listOf(
            BokehDot(0.20f, 0.30f, 0.030f, Color(0xFFFFE0A0), 0.34f, 0.6f),
            BokehDot(0.72f, 0.24f, 0.026f, Color(0xFFFFE8B8), 0.30f, 2.1f),
            BokehDot(0.48f, 0.12f, 0.020f, Color(0xFFFFD98A), 0.28f, 3.4f),
            BokehDot(0.88f, 0.42f, 0.018f, Color(0xFFFFE0A0), 0.24f, 4.6f),
        ),
        t = t,
    )

    // 金屑：三层，从下往上，颜色在金 / 香槟 / 暖红之间跳
    confetti(
        t = t, n = 40, base = 300, speed = 0.15f, y0 = 0.12f, y1 = 0.62f, rise = true,
        colors = listOf(Color(0xFFFFC24D), Color(0xFFFFDF9A), Color(0xFFE8683E), Color(0xFFFFF0C8)),
        scale = 0.013f,
    )
    confetti(
        t = t, n = 34, base = 360, speed = 0.24f, y0 = 0.16f, y1 = 0.60f, rise = true,
        colors = listOf(Color(0xFFFFD05C), Color(0xFFFFF0C8), Color(0xFFF08A4A)),
        scale = 0.010f,
    )
    confetti(
        t = t, n = 28, base = 420, speed = 0.34f, y0 = 0.20f, y1 = 0.58f, rise = true,
        colors = listOf(Color(0xFFFFC24D), Color(0xFFFFE8B8), Color(0xFFE8683E)),
        scale = 0.0070f,
    )
}

/** 爆竹迎春：两簇细红筒立在雪地上，两支正在响（引线喷火星）+ 红纸屑。 */
internal fun DrawScope.drawSpringFirecracker(t: Float, p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.54f, 0.58f)
    fDusk(Color(0xFFFFD9A0), 0.48f, 0.46f)
    softGlow(w * 0.24f, h * 0.14f, w * 0.52f, p.c, 0.34f)
    softGlow(w * 0.80f, h * 0.20f, w * 0.38f, Color(0xFFFFE0A8), 0.26f)
    ridge(h * 0.440f, h * 0.026f, 1.5f, p.a, 0.20f, waves = 1.1f)
    mistBand(h * 0.474f, h * 0.080f, Color.White, 0.42f)
    ridge(h * 0.548f, h * 0.012f, 0.7f, Color.White, 0.98f, waves = 1.1f)

    bokeh(
        listOf(
            BokehDot(0.30f, 0.26f, 0.024f, Color(0xFFFFD98A), 0.30f, 0.8f),
            BokehDot(0.70f, 0.20f, 0.020f, Color(0xFFFFE4A8), 0.26f, 2.2f),
            BokehDot(0.90f, 0.36f, 0.016f, Color(0xFFFFD98A), 0.22f, 3.9f),
        ),
        t = t,
        drift = false,
    )

    // 两簇爆竹：中间留空，别在车名文字后面堆一排
    val sticks = arrayOf(
        floatArrayOf(0.072f, 0.542f, 0.135f, 0.07f),
        floatArrayOf(0.156f, 0.554f, 0.190f, -0.05f),
        floatArrayOf(0.236f, 0.540f, 0.104f, 0.05f),
        floatArrayOf(0.312f, 0.550f, 0.152f, 0.03f),
        floatArrayOf(0.690f, 0.546f, 0.182f, -0.06f),
        floatArrayOf(0.784f, 0.538f, 0.118f, 0.04f),
        floatArrayOf(0.872f, 0.550f, 0.146f, -0.03f),
    )
    for (st in sticks) {
        val s0 = h * st[2]
        val x = w * st[0]
        val y = h * st[1]
        // 雪地上的投影 + 被压出的雪堆
        drawOval(
            color = Color(0xFF8FA9C6),
            topLeft = Offset(x + s0 * 0.10f - s0 * 0.34f, y + s0 * 0.01f - s0 * 0.070f),
            size = Size(s0 * 0.68f, s0 * 0.140f),
            alpha = 0.20f,
        )
        drawOval(
            color = Color.White,
            topLeft = Offset(x - s0 * 0.03f - s0 * 0.26f, y - s0 * 0.01f - s0 * 0.056f),
            size = Size(s0 * 0.52f, s0 * 0.112f),
            alpha = 0.90f,
        )
        firecrackerStick(x, y, s0, 0.96f, st[3])
    }

    // 两颗正在响：火光在引线末端，火星向上斜喷
    val b1x = w * 0.156f
    val b1y = h * 0.554f - h * 0.190f * 1.17f
    val b2x = w * 0.690f
    val b2y = h * 0.546f - h * 0.182f * 1.17f
    softGlow(b1x, b1y - h * 0.020f, h * 0.056f, Color(0xFFFFE6C0), 0.26f)
    softGlow(b2x, b2y - h * 0.018f, h * 0.050f, Color(0xFFFFE6C0), 0.22f)
    sparkBurst(b1x, b1y, h * 0.044f, 701, 0.62f + 0.38f * (0.5f + 0.5f * sin(TAU * 1.6f * t)).pow(2.0f))
    sparkBurst(b2x, b2y, h * 0.040f, 733, 0.52f + 0.48f * (0.5f + 0.5f * sin(TAU * 1.6f * t + 1.9f)).pow(2.0f))

    // 红纸屑：爆开后飘落
    confetti(
        t = t, n = 22, base = 500, speed = 0.13f, y0 = 0.12f, y1 = 0.60f, rise = false,
        colors = listOf(Color(0xFFD4593F), Color(0xFFE8764A), Color(0xFFC93B24), Color(0xFFF0A24E)),
        scale = 0.0068f,
    )

    // 近景一层极薄的雾，别在雪地上糊出一团灰斑
    mistBand(h * 0.572f, h * 0.026f, Color.White, 0.14f)
}

// ============================================================================
// 夜间 · 静态
// ============================================================================

/** 雪夜小屋：星空 + 月 + 松 + 木屋暖窗 + 雪地上的一片暖光。 */
internal fun DrawScope.drawXmasVillageNight(p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.72f, 0.62f)
    starField(seed = 11, count = 46, alphaScale = 0.46f, t = 0f, twinkle = false)
    // 月亮 + 月晕
    softGlow(w * 0.80f, h * 0.100f, w * 0.10f, Color(0xFFFFF6DC), 0.70f)
    softGlow(w * 0.80f, h * 0.100f, w * 0.26f, Color(0xFFCBDCF5), 0.20f)
    ridge(h * 0.420f, h * 0.042f, 1.4f, p.a, 0.42f, waves = 1.2f)
    // 雪地：亮，夜间也要能看出是雪
    ridge(h * 0.560f, h * 0.038f, 0.9f, p.a, 0.34f, waves = 1.1f)
    ridge(h * 0.540f, h * 0.038f, 0.9f, Color(0xFFEFF7FF), 0.40f, waves = 1.1f)
    pineTree(w * 0.105f, h * 0.600f, h * 0.185f, p.ink, 0.84f)
    pineTree(w * 0.912f, h * 0.624f, h * 0.150f, p.ink, 0.78f)
    // 树上小灯
    for (k in 0 until 3) {
        ornamentBall(
            w * 0.115f + (if (k % 2 == 1) 1f else -1f) * h * 0.030f,
            h * 0.600f - h * 0.145f * (0.30f + k * 0.20f),
            h * 0.008f,
            if (k % 2 == 1) Color(0xFFFFD05C) else Color(0xFFE8503A),
            0.85f,
        )
    }
    cabinHouse(w * 0.445f, h * 0.600f, h * 0.165f, p.ink, 0.80f, p.c, 0.72f, Color(0xFFEAF2FF))
    softGlow(w * 0.445f, h * 0.588f, h * 0.30f, p.c, 0.20f)

    // 暖光洒在雪地
    val spill = Path().apply {
        moveTo(w * 0.360f, h * 0.600f)
        lineTo(w * 0.530f, h * 0.600f)
        lineTo(w * 0.600f, h * 0.680f)
        lineTo(w * 0.290f, h * 0.680f)
        close()
    }
    drawPath(
        spill,
        brush = Brush.verticalGradient(
            colors = listOf(p.c.copy(alpha = 0.22f), p.c.copy(alpha = 0f)),
            startY = h * 0.600f,
            endY = h * 0.680f,
        ),
    )

    bokeh(
        listOf(
            BokehDot(0.62f, 0.50f, 0.020f, Color(0xFFFFD08A), 0.20f, 1.0f),
            BokehDot(0.28f, 0.46f, 0.016f, Color(0xFFFFD08A), 0.16f, 3.0f),
        ),
        t = 0f,
        drift = false,
    )
}

/** 红梅映雪：夜雪 + 一枝红梅 + 远处屋檐剪影（不用灯笼）。 */
internal fun DrawScope.drawSpringPlumSnow(p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.76f, 0.66f)
    starField(seed = 201, count = 30, alphaScale = 0.30f, t = 0f, twinkle = false)
    // 天边一点暖晖，别让夜空变成一块纯黑
    softGlow(w * 0.72f, h * 0.286f, w * 0.56f, p.c, 0.15f)

    // 远处屋檐剪影：屋脊 + 两片翘角，给画面一个「去处」
    val roof = Path().apply {
        moveTo(0f, h * 0.472f)
        lineTo(w * 0.235f, h * 0.472f)
        lineTo(w * 0.300f, h * 0.438f)
        lineTo(w * 0.665f, h * 0.438f)
        lineTo(w * 0.728f, h * 0.472f)
        lineTo(w, h * 0.472f)
        lineTo(w, h * 0.570f)
        lineTo(0f, h * 0.570f)
        close()
    }
    drawPath(roof, p.ink, alpha = 0.70f)
    // 屋脊上的一线雪光
    val ridgeLine = Path().apply {
        moveTo(0f, h * 0.472f)
        lineTo(w * 0.235f, h * 0.472f)
        lineTo(w * 0.300f, h * 0.438f)
        lineTo(w * 0.665f, h * 0.438f)
        lineTo(w * 0.728f, h * 0.472f)
        lineTo(w, h * 0.472f)
    }
    drawPath(
        ridgeLine,
        Color(0xFFF3D6C0),
        alpha = 0.26f,
        style = Stroke(width = maxOf(1f, h * 0.0032f)),
    )

    // 雪地
    ridge(h * 0.620f, h * 0.022f, 0.9f, Color(0xFFF6E9E6), 0.30f, waves = 1.1f)
    ridge(h * 0.604f, h * 0.018f, 1.4f, Color.White, 0.17f, waves = 1.1f)
    softGlow(w * 0.44f, h * 0.706f, w * 0.48f, Color(0xFFE0453A), 0.10f)

    // 主梅枝：左下伸向右上。枝干别用近黑 —— 深色底上一整条会彻底看不见
    plumBranch(-w * 0.05f, h * 0.652f, -0.58f, w * 0.400f, h * 0.0215f, 3, 311, Color(0xFF7E463A), 0.95f, 0.97f)
    // 副枝：从右上斜插进来，与主枝成对角，重心才不塌在左下
    plumBranch(w * 1.04f, h * 0.210f, PI.toFloat() - 0.32f, w * 0.300f, h * 0.0150f, 3, 457, Color(0xFF70402F), 0.90f, 0.92f)

    // 零星落梅
    for (i in 0 until 14) {
        val x = rnd(601, i) * w
        val y = rnd(603, i) * h * 0.58f
        rotate(degrees = rnd(605, i) * 360f, pivot = Offset(x, y)) {
            drawOval(
                color = Color(0xFFD8453A),
                topLeft = Offset(x - h * 0.0042f, y - h * 0.0028f),
                size = Size(h * 0.0084f, h * 0.0056f),
                alpha = 0.30f + rnd(607, i) * 0.34f,
            )
        }
    }

    bokeh(
        listOf(
            BokehDot(0.62f, 0.20f, 0.020f, Color(0xFFFFC98A), 0.20f, 0.4f),
            BokehDot(0.34f, 0.34f, 0.015f, Color(0xFFFFD9A8), 0.16f, 2.0f),
        ),
        t = 0f,
        drift = false,
    )
}

// ============================================================================
// 夜间 · 动态
// ============================================================================

/** 灯串微闪：两道灯串交错呼吸 + 细雪。 */
internal fun DrawScope.drawXmasTwinkle(t: Float, p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.70f, 0.62f)
    starField(seed = 23, count = 36, alphaScale = 0.36f, t = t, twinkle = true)
    ridge(h * 0.440f, h * 0.038f, 1.6f, p.a, 0.36f, waves = 1.2f)
    ridge(h * 0.570f, h * 0.040f, 0.9f, p.a, 0.34f, waves = 1.1f)
    ridge(h * 0.552f, h * 0.040f, 0.9f, Color(0xFFDCE8F8), 0.26f, waves = 1.1f)

    // 两串灯：上面一条长弧、下面一条短的，交错呼吸
    val strands = arrayOf(
        floatArrayOf(0.250f, 0.070f, 14f, 0.02f, 0.98f, 1.00f, 0.0f),
        floatArrayOf(0.330f, 0.038f, 9f, 0.14f, 0.70f, 0.82f, 1.7f),
    )
    for (sd in strands) {
        val n = sd[2].toInt()
        for (i in 0 until n) {
            val u = i / (n - 1f)
            val x = w * (sd[3] + u * (sd[4] - sd[3]))
            val y = h * (sd[0] + sin(u * PI.toFloat()) * sd[1])
            val tw = 0.30f + 0.70f * (0.5f + 0.5f * sin(TAU * 0.75f * t + i * 0.85f + sd[6])).pow(2.0f)
            softGlow(x, y, h * 0.070f * sd[5], p.c, 0.34f * tw)
            softGlow(x, y, h * 0.026f * sd[5], Color(0xFFFFF3D6), 0.60f * tw)
            drawCircle(Color(0xFFFFF7E8), h * 0.0090f * sd[5], Offset(x, y), alpha = 0.30f + tw * 0.62f)
        }
    }

    // 细雪 + 光斑
    for (i in 0 until 24) {
        val seed = 240 + i
        val y = ((t * 0.32f + rnd(seed, i)) % 1f) * h * 0.72f
        val x = rnd(seed + 2, i) * w + sin(TAU * t + i) * w * 0.009f
        fFlake(x, y, h * (0.0030f + rnd(seed + 7, i) * 0.0035f), 0.30f + rnd(seed + 5, i) * 0.32f)
    }
    bokeh(
        listOf(
            BokehDot(0.20f, 0.40f, 0.022f, Color(0xFFFFD9A0), 0.18f, 0.9f),
            BokehDot(0.74f, 0.38f, 0.018f, Color(0xFFFFD9A0), 0.16f, 2.8f),
        ),
        t = t,
        drift = false,
    )
}

/** 跨年烟花：五束错峰烟花 + 城市剪影 + 一整条暖光。 */
internal fun DrawScope.drawNewyearFireworks(t: Float, p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.76f, 0.64f)
    starField(seed = 31, count = 44, alphaScale = 0.32f, t = t, twinkle = true)
    // 烟花映在夜空上的余晖（整片暖起来，才不阴森）
    softGlow(w * 0.50f, h * 0.260f, w * 0.85f, Color(0xFF5A4A7A), 0.14f)
    // 城市剪影 + 地面暖光
    ridge(h * 0.650f, h * 0.018f, 5.0f, p.ink, 0.58f, waves = 1.6f)
    softGlow(w * 0.50f, h * 0.660f, w * 0.95f, p.c, 0.26f)
    softGlow(w * 0.50f, h * 0.700f, w * 0.55f, Color(0xFFFFB46A), 0.16f)

    // 五束烟花，错峰；高度与半径都不同，才像「一次放了好几发」
    val shots = arrayOf(
        floatArrayOf(0.180f, 0.312f, 0.230f, 0.00f),
        floatArrayOf(0.500f, 0.216f, 0.200f, 0.19f),
        floatArrayOf(0.812f, 0.318f, 0.218f, 0.39f),
        floatArrayOf(0.316f, 0.436f, 0.152f, 0.57f),
        floatArrayOf(0.676f, 0.422f, 0.166f, 0.77f),
    )
    val colors = listOf(
        Color(0xFFFFC45C) to Color(0xFFFFF3D0),
        Color(0xFFFF9E6E) to Color(0xFFFFE6C8),
        Color(0xFFFFD98F) to Color(0xFFFFF6E0),
        Color(0xFF8FD9E0) to Color(0xFFE8FBFF),
        Color(0xFFF58AB5) to Color(0xFFFFEAF4),
    )
    shots.forEachIndexed { idx, sh ->
        val p01 = (t + sh[3]) % 1f
        // 升空段：一发带拖尾的光点，不是一根直线
        if (p01 < 0.07f) {
            val k = p01 / 0.07f
            val ty = h * sh[1] + h * 0.20f * (1f - k)
            val by = ty + h * 0.055f
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFF0C8).copy(alpha = 0f), Color(0xFFFFF0C8).copy(alpha = 0.80f * (1f - k * 0.25f))),
                    startY = by,
                    endY = ty,
                ),
                start = Offset(w * sh[0] + sin(k * 9f) * 1.6f, by),
                end = Offset(w * sh[0], ty),
                strokeWidth = maxOf(1f, h * 0.0038f),
            )
            softGlow(w * sh[0], ty, h * 0.022f, Color(0xFFFFE0A8), 0.60f * k)
        }
        firework(
            x = w * sh[0],
            y = h * sh[1],
            R = w * sh[2],
            color = colors[idx].first,
            p = min(1f, maxOf(0f, (p01 - 0.03f) / 0.97f)),
            alpha = 0.94f,
            core = colors[idx].second,
        )
    }

    // 城市上沿的一整条暖光（跨年的灯火），不做成等距点阵
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFFC25C).copy(alpha = 0.26f), Color(0xFFFFC25C).copy(alpha = 0f)),
            startY = h * 0.600f,
            endY = h * 0.680f,
        ),
        topLeft = Offset(0f, h * 0.600f),
        size = Size(w, h * 0.080f),
    )
    for (i in 0 until 16) {
        val x = w * (0.03f + (i / 15f) * 0.94f)
        val tw = 0.5f + 0.5f * sin(TAU * 1.2f * t + i * 0.9f)
        softGlow(x, h * (0.630f + rnd(301, i) * 0.014f), h * 0.024f, p.c, 0.20f + tw * 0.26f)
    }
}

/** 孔明灯升空：三层景深的灯往上升，越往上越淡。 */
internal fun DrawScope.drawSpringSkyLanterns(t: Float, p: ScenePalette) {
    val w = size.width
    val h = size.height

    fSky(p.b, 0.76f, 0.64f)
    starField(seed = 47, count = 26, alphaScale = 0.22f, t = t, twinkle = true)
    // 天边余晖 + 地面暖光
    softGlow(w * 0.50f, h * 0.560f, w * 0.95f, Color(0xFFFF9A5A), 0.16f)
    softGlow(w * 0.50f, h * 0.560f, w * 0.45f, Color(0xFFFFC98A), 0.10f)
    ridge(h * 0.655f, h * 0.016f, 3.0f, p.ink, 0.62f, waves = 1.4f)

    // 孔明灯：三层景深，密一些才像「满天」
    val layers = arrayOf(
        floatArrayOf(0.052f, 0.11f, 0.98f, 90f, 5f),
        floatArrayOf(0.036f, 0.18f, 0.90f, 130f, 6f),
        floatArrayOf(0.024f, 0.26f, 0.78f, 170f, 6f),
    )
    for (ly in layers) {
        val n = ly[4].toInt()
        val base = ly[3].toInt()
        for (i in 0 until n) {
            val seed = base + i
            val up = 1f - ((t * ly[1] + rnd(seed, i)) % 1f)
            val y = h * (0.02f + up * 0.60f)
            val x = w * (0.06f + rnd(seed + 4, i) * 0.88f) + sin(TAU * t + i) * w * 0.010f
            val fade = min(1f, up * 3.6f)
            kongmingLantern(x, y, h * ly[0], p.c, ly[2] * fade)
        }
    }

    // 底部金斑
    bokeh(
        listOf(
            BokehDot(0.24f, 0.52f, 0.026f, Color(0xFFFFC98A), 0.20f, 0.5f),
            BokehDot(0.72f, 0.48f, 0.020f, Color(0xFFFFC98A), 0.18f, 2.4f),
        ),
        t = t,
        drift = false,
    )
}
