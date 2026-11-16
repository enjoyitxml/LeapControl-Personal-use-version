package com.leapcontrol.app

import com.leapcontrol.app.widget.WidgetStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件样式里那几条**纯逻辑**：亮度判据、版式名、默认色。
 *
 * 存取颜色要走 SharedPreferences，得在设备上跑，这里不覆盖；
 * 但「哪种颜色算浅」决定了透明主题用深玻璃底还是浅玻璃底、
 * 进度条用亮版还是暗版 —— 这条算错只会在真机上表现为「字看不见」，所以钉住。
 */
class WidgetStyleTest {

    @Test
    fun `白色与黑色分在亮暗两边`() {
        assertTrue(WidgetStyle.isLight(0xFFFFFFFF.toInt()))
        assertFalse(WidgetStyle.isLight(0xFF000000.toInt()))
    }

    @Test
    fun `预设色板里每一档的亮暗都符合直觉`() {
        // 与 ui/WidgetColorPicker.kt 的 PRESET_COLORS 一一对应
        val lightExpected = mapOf(
            0xFFFFFFFF.toInt() to true,   // 白
            0xFFC9CED6.toInt() to true,   // 浅灰
            0xFF5A626E.toInt() to false,  // 灰
            0xFF12141A.toInt() to false,  // 黑
            0xFF3DD68C.toInt() to true,   // 绿
            0xFF6FBEFF.toInt() to true,   // 蓝
            0xFFFFA94D.toInt() to true,   // 橙
            0xFFFF6B6B.toInt() to false,  // 红
        )
        lightExpected.forEach { (color, expected) ->
            assertEquals(
                "颜色 #%06X 的亮暗判断变了，透明主题会挑错素材".format(color and 0xFFFFFF),
                expected,
                WidgetStyle.isLight(color),
            )
        }
    }

    @Test
    fun `透明版式默认字色与控件色都是白`() {
        // 2026-11-16 用户定稿：全透明默认整张卡只有一种颜色（白），
        // 按键生效时不再变绿，「哪个键开着」交给右上角那句提示说明。
        assertEquals(
            "默认字色不是白",
            0xFFFFFFFF.toInt(),
            WidgetStyle.DEFAULT_INK,
        )
        assertEquals(
            "默认控件色不是白",
            0xFFFFFFFF.toInt(),
            WidgetStyle.DEFAULT_CTRL,
        )
        assertTrue("默认字色应该是浅色（深壁纸上最好读）", WidgetStyle.isLight(WidgetStyle.DEFAULT_INK))
    }

    @Test
    fun `三种版式的名字与顺序`() {
        assertEquals(listOf("素纸", "沉浸", "全透明"), WidgetStyle.ALL.map { WidgetStyle.displayName(it) })
        // 默认是全透明（2026-11-16 用户定稿）；三张缩略图的排列顺序不动，
        // 默认那张在最后，用户扫一眼就能看出「现在用的是哪套」
        assertEquals(WidgetStyle.STYLE_T, WidgetStyle.DEFAULT_STYLE)
    }

    @Test
    fun `未知版式折回素纸`() {
        // 老 prefs 里残留的 "f"（已删除的色块版）由 style() 兜底，这里只验兜底目标没变
        assertEquals("素纸", WidgetStyle.displayName("f"))
    }
}
