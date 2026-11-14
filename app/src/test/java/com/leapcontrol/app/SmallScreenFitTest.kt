package com.leapcontrol.app

import com.leapcontrol.app.ui.fittedFontSp
import com.leapcontrol.app.ui.theme.fontScaleCap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窄屏 / 大字体适配的守门测试（2026-11-08）。
 *
 * 用户反馈的原始现象：小屏或开了大字体的手机上，首页充电时的续航文字被遮挡
 * —— `286 km 充电中 剩 3 小时 5 分钟` 这一行把「剩…分钟」的尾巴裁掉，里程行的
 * `70%` 只剩一个「7」。
 *
 * 根因不是字号，而是「定宽 + `softWrap = false`」：Compose 在宽度不够时既不折行也不省略，
 * 直接画出去再裁掉。这里的测试分两类：
 * 1. 纯函数：字号分档与「按宽度缩字」的数学；
 * 2. 源码断言：那几处必须继续用弹性布局（`weight` / `FlowRow` / `AutoFitText`）。
 */
class SmallScreenFitTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun source(relative: String): String =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/$relative").readText()

    // ---------------- 字号分档 ----------------

    @Test
    fun `窄屏的字号封顶比宽屏更紧`() {
        val narrow = fontScaleCap(320)
        val compact = fontScaleCap(360)
        val normal = fontScaleCap(412)
        val wide = fontScaleCap(480)

        assertTrue("320dp 屏仍按 1.3 倍封顶，续航行必然挤爆", narrow <= 1.15f)
        assertTrue("360dp 屏的封顶应低于 412dp 屏", compact < normal)
        assertEquals(1.3f, normal, 0.001f)
        assertEquals(1.3f, wide, 0.001f)
        assertTrue("任何宽度都不该允许超过 1.3 倍", listOf(narrow, compact, normal, wide).all { it <= 1.3f })
    }

    @Test
    fun `宽度未知时退回最宽松的封顶`() {
        assertEquals(1.3f, fontScaleCap(0), 0.001f)
    }

    // ---------------- 按宽度缩字 ----------------

    @Test
    fun `放得下就不动字号`() {
        assertEquals(40f, fittedFontSp(measuredWidthPx = 90, availableWidthPx = 120, baseSp = 40f, minSp = 26f), 0.001f)
    }

    @Test
    fun `放不下时按宽度等比缩`() {
        // 需要 200px 只给 150px → 缩到 0.75 倍
        assertEquals(30f, fittedFontSp(measuredWidthPx = 200, availableWidthPx = 150, baseSp = 40f, minSp = 20f), 0.001f)
    }

    @Test
    fun `再挤也不低于下限`() {
        assertEquals(26f, fittedFontSp(measuredWidthPx = 400, availableWidthPx = 60, baseSp = 40f, minSp = 26f), 0.001f)
    }

    @Test
    fun `测量失败时保持基准字号`() {
        assertEquals(40f, fittedFontSp(measuredWidthPx = 0, availableWidthPx = 120, baseSp = 40f, minSp = 26f), 0.001f)
        assertEquals(40f, fittedFontSp(measuredWidthPx = 200, availableWidthPx = -1, baseSp = 40f, minSp = 26f), 0.001f)
    }

    // ---------------- 源码守门 ----------------

    @Test
    fun `首页里程条必须整行靠左、只在摆不下时缩短`() {
        val src = source("ui/HomeScreen.kt")
        val body = src.substringAfter("internal fun MileageRow(", "")
        assertTrue("找不到 MileageRow", body.isNotEmpty())
        val row = body.substringBefore("\n}\n")

        assertTrue(
            "进度条宽度没有按『可用宽度 - 固定部件』封顶，窄屏上整行会溢出、百分比被裁",
            row.contains("minOf(barWidth, (maxWidth - fixedWidth).coerceAtLeast(0.dp))"),
        )
        assertTrue(
            "固定部件（图标 / 里程槽 / 间隙 / 百分比槽）没有算成一份 fixedWidth",
            row.contains("fixedWidth = 16.dp + 6.dp + labelWidth"),
        )
        assertTrue(
            "进度条又用 weight 吃剩余宽度了：大屏上百分比会被顶到行尾，条和数字之间空一大截",
            !row.contains("Modifier.weight(1f)"),
        )
        assertTrue(
            "百分比不再是「有下限的槽位」：两行进度条会不一样长，或者 100% 又被裁",
            // 2026-11-08 用户截图「100%」的 % 被切：定宽 30dp 装不下，改成 widthIn(min)
            // —— 槽位保证起点对齐，字体更宽时文字自己撑开，永远不会被裁。
            row.contains("widthIn(min = percentWidth)"),
        )
        assertTrue(
            "百分比槽位没跟字号缩放",
            row.contains("percentWidth = 36.dp * fontScale"),
        )
    }

    @Test
    fun `续航大字必须按可用宽度自适应`() {
        val src = source("ui/HomeScreen.kt")
        assertTrue(
            "首页续航大字没走 AutoFitText：窄屏上 40sp 的「286」会把 km 和充电图标顶出屏幕",
            src.contains("AutoFitText(") && src.contains("minFontSize = 26.sp"),
        )
    }

    @Test
    fun `首页车名必须吃满图标以左的剩余宽度`() {
        val src = source("ui/HomeScreen.kt")
        assertTrue(
            "车名又写死了宽度上限：2026-11-13 实机截图里右侧空着近 130dp，名字却提前变成「西米露的玛莎拉…」",
            // 带前导点，避免把注释里那句解释也算进来
            !src.contains(".widthIn(max = 180.dp)"),
        )
        val nameBlock = src
            .substringAfter("车名：点一下直接改名", "")
            .substringBefore("车名与图标之间留一点固定间距", "")
        assertTrue("找不到首页车名那一块", nameBlock.isNotEmpty())
        assertTrue(
            "车名没有按权重吃剩余宽度：换到宽屏 / 平板也不会多显示一个字",
            nameBlock.contains(".weight(1f, fill = false)"),
        )
    }

    @Test
    fun `右上角状态与分置版胶囊都必须能折行而不是裁字`() {
        val status = source("ui/HomeStatusText.kt")
        assertTrue(
            "HomeStatusText 的主行退回 Row 了：宽度不够时「剩 3 小时 5 分钟」会被裁",
            status.contains("FlowRow("),
        )
        assertTrue(
            "HomeStatusText 的列宽没按可用宽度收缩，窄屏上会吃掉续航区的宽度",
            status.contains("minOf(MAX_WIDTH, (maxWidth - 150.dp).coerceAtLeast(96.dp))"),
        )
        assertTrue(
            "状态列又按固定比例（maxWidth * 0.46f）收窄了：常规屏明明放得下却提前折行",
            !status.contains("maxWidth * 0.46f"),
        )

        val hero = source("ui/HeroLayoutViews.kt")
        val badge = hero.substringAfter("internal fun DrivingStatusBadge", "")
        assertTrue("找不到 DrivingStatusBadge", badge.isNotEmpty())
        assertTrue(
            "分置版行驶胶囊退回 Row 了：窄屏上 detail 会被裁",
            badge.substringBefore("\n}\n").contains("FlowRow("),
        )
    }

    @Test
    fun `全局字号封顶必须继续按屏宽分档`() {
        val theme = source("ui/theme/Theme.kt")
        assertTrue(
            "主题不再调用 fontScaleCap，窄屏又会被放大到 1.3 倍",
            theme.contains("fontScaleCap(LocalConfiguration.current.screenWidthDp)"),
        )
        assertTrue(
            "封顶值被抬到 1.3 以上了，成排的定宽元素会被撑爆",
            !theme.contains("MAX_FONT_SCALE = 1.4") && !theme.contains("MAX_FONT_SCALE = 1.5"),
        )
    }
}
