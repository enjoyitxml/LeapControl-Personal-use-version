package com.leapcontrol.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 位置校准面板在大字体（系统 fontScale）下不裁字的守门测试。
 *
 * Compose 的 `sp` 会跟着系统字体缩放，而 `dp` 不会。原先坐标系四张卡片写死
 * `height(52.dp)`、步长按钮写死 `height(34.dp)`，外层又有圆角 `clip` —— 字体一放大，
 * 「标题 + 一句说明」比容器高，下半截直接被切掉（2026-11-07 用户截图反馈）。
 *
 * 这类问题**编译得过、常规字体下也看不出来**，真机只有开大字体才复现，
 * 所以这里直接钉住「容器不是固定高、文字不限行数、同一行卡片会拉齐」。
 */
class LocationCalibrateLayoutTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val source: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/LocationCalibratePanel.kt").readText()
    }

    private fun datumChipBlock(): String {
        val start = source.indexOf("private fun DatumChip(")
        val end = source.indexOf("/** 每个选项一句", startIndex = start)
        assertTrue("找不到 DatumChip 的源码段落", start >= 0 && end > start)
        return source.substring(start, end)
    }

    @Test
    fun `坐标系卡片与步长按钮都不能写死高度`() {
        assertFalse(
            "坐标系卡片又变成固定 52dp 高了：大字体下小字会被圆角裁掉",
            source.contains(".height(52.dp)"),
        )
        assertFalse(
            "步长按钮又变成固定 34dp 高了：大字体下「50 米」会被裁掉",
            source.contains(".height(34.dp)"),
        )
        assertTrue("坐标系卡片要保留最小高度", source.contains(".heightIn(min = 52.dp)"))
        assertTrue("步长按钮要保留最小高度", source.contains(".heightIn(min = 34.dp)"))
    }

    @Test
    fun `坐标系卡片的文字不限行数，选中态给对勾留位置`() {
        val chip = datumChipBlock()
        assertFalse(
            "DatumChip 里又加了行数上限：大字体下标题或说明会被省略号截断",
            chip.contains("maxLines ="),
        )
        assertTrue(
            "选中态右上角有对勾，标题要留出安全距离，否则大字会压到图标上",
            chip.contains("padding(end = if (selected) 16.dp else 0.dp)"),
        )
    }

    @Test
    fun `常规字体同一行两张卡片等高，大字体改成每张独占一行`() {
        assertTrue(
            "2×2 的每一行要用 IntrinsicSize.Min 取行内最高卡片",
            source.contains("Modifier.height(IntrinsicSize.Min)"),
        )
        assertTrue(
            "每张卡片要 fillMaxHeight() 跟齐同一行的高度",
            source.contains(".fillMaxHeight()"),
        )
        assertTrue(
            "系统大字体下要切成单列：宽度翻倍后那句说明才不会被挤成两三行",
            source.contains("LocalDensity.current.fontScale > 1.3f") && source.contains("chunked(1)"),
        )
    }
}
