package com.leapcontrol.app

import com.leapcontrol.app.ui.WallpaperContrast
import com.leapcontrol.app.ui.WallpaperPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自定义壁纸的「文字随壁纸反色」（2026-11-16）。
 *
 * ## 用户的原话
 * 「用户使用自定义壁纸时，经常出现字体看不清的情况，能否加一个在自定义壁纸下
 *   字体根据背景颜色反色的方案，加强字体可读性」
 *
 * ## 钉住的规则
 * 1. 亮度按**伽马编码值**加权（0.2126/0.7152/0.0722），中灰要落在 0.5 附近 ——
 *    换成线性化会把中灰压到 0.216，阈值就没法按直觉定；
 * 2. 只有明显偏亮（≥0.56）才切深色内容、明显偏暗（≤0.44）才切浅色内容；
 *    中间灰保持用户原本的外观模式，别把「跟随系统」硬翻掉；
 * 3. 关掉开关 / 亮度未知时，必须原样返回外观模式的结果；
 * 4. 薄纱颜色跟**内容色**走，不跟「取哪张图」走 —— 跟错方向等于把字再糊一次。
 */
class WallpaperContrastTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun ui(name: String): String =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/$name").readText()

    // ---------------- 亮度计算 ----------------

    @Test
    fun `纯白亮度是 1，纯黑是 0`() {
        assertEquals(1f, WallpaperPalette.luminanceOf(fill(0xFFFFFFFF.toInt()), 8, 8)!!, 0.001f)
        assertEquals(0f, WallpaperPalette.luminanceOf(fill(0xFF000000.toInt()), 8, 8)!!, 0.001f)
    }

    @Test
    fun `中灰落在 0_5 附近，不被人眼感知的线性化拉低`() {
        val mid = WallpaperPalette.luminanceOf(fill(0xFF808080.toInt()), 8, 8)!!
        assertEquals(0.502f, mid, 0.01f)
        // 线性化后 128 只有 0.216，那样阈值就得跟着挪，公式一旦被改这里会先失败
        assertTrue("中灰亮度应该接近 0.5，实际 $mid", mid > 0.45f)
    }

    @Test
    fun `单色亮度等于它的加权系数`() {
        assertEquals(0.2126f, WallpaperPalette.luminanceOf(fill(0xFFFF0000.toInt()), 8, 8)!!, 0.001f)
        assertEquals(0.7152f, WallpaperPalette.luminanceOf(fill(0xFF00FF00.toInt()), 8, 8)!!, 0.001f)
        assertEquals(0.0722f, WallpaperPalette.luminanceOf(fill(0xFF0000FF.toInt()), 8, 8)!!, 0.001f)
    }

    @Test
    fun `全透明与空数组返回 null`() {
        assertNull(WallpaperPalette.luminanceOf(fill(0x00FFFFFF.toInt()), 8, 8))
        assertNull(WallpaperPalette.luminanceOf(IntArray(0), 0, 0))
    }

    // ---------------- 明暗判定 ----------------

    @Test
    fun `亮壁纸配深色内容，暗壁纸配浅色内容`() {
        assertFalse(WallpaperContrast.contentDark(0.90f, fallbackDark = false, enabled = true))
        assertFalse(WallpaperContrast.contentDark(0.90f, fallbackDark = true, enabled = true))
        assertTrue(WallpaperContrast.contentDark(0.10f, fallbackDark = true, enabled = true))
        assertTrue(WallpaperContrast.contentDark(0.10f, fallbackDark = false, enabled = true))
    }

    @Test
    fun `阈值边界：0_56 起算亮底，0_44 及以下算暗底`() {
        assertFalse(WallpaperContrast.contentDark(0.56f, fallbackDark = true, enabled = true))
        assertTrue(WallpaperContrast.contentDark(0.44f, fallbackDark = false, enabled = true))
    }

    @Test
    fun `中间灰原样返回外观模式，不硬翻`() {
        assertTrue(WallpaperContrast.contentDark(0.50f, fallbackDark = true, enabled = true))
        assertFalse(WallpaperContrast.contentDark(0.50f, fallbackDark = false, enabled = true))
    }

    @Test
    fun `关掉开关或亮度未知时完全跟随外观模式`() {
        assertTrue(WallpaperContrast.contentDark(0.95f, fallbackDark = true, enabled = false))
        assertFalse(WallpaperContrast.contentDark(0.05f, fallbackDark = false, enabled = false))
        assertTrue(WallpaperContrast.contentDark(null, fallbackDark = true, enabled = true))
        assertFalse(WallpaperContrast.contentDark(null, fallbackDark = false, enabled = true))
    }

    // ---------------- 接线守门 ----------------

    @Test
    fun `薄纱必须按内容色选白黑，而不是按取哪张图`() {
        val backdrop = ui("ThemeBackdrop.kt")
        assertTrue(
            "壁纸薄纱要跟 contentDark 走：深字配白纱、浅字配黑纱",
            backdrop.contains("if (contentDark) Color.Black else Color.White"),
        )
        assertFalse(
            "薄纱不许再按 dark（取哪张图）选色，方向会反",
            backdrop.contains("if (dark) Color.Black else Color.White"),
        )
    }

    @Test
    fun `首页配色必须吃反色后的 contentDark`() {
        val main = ui("MainScreen.kt")
        assertTrue(
            "ProvideAppColors 要传 contentDark，否则文字颜色不会跟着壁纸变",
            main.contains("ProvideAppColors(dark = contentDark"),
        )
        assertTrue(
            "状态栏图标颜色也要跟着内容色走",
            main.contains("isAppearanceLightStatusBars = !contentDark"),
        )
        assertTrue(
            "壁纸层要把 contentDark 传下去",
            main.contains("contentDark = contentDark"),
        )
    }

    @Test
    fun `个性化页必须有这个开关`() {
        val page = ui("PersonalizationScreen.kt")
        assertTrue(page.contains("文字随壁纸反色"))
        assertTrue(page.contains("runtime.setWallpaperAutoContrast"))
    }

    private fun fill(argb: Int): IntArray = IntArray(64) { argb }
}
