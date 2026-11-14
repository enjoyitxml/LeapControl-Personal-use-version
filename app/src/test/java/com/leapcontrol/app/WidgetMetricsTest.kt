package com.leapcontrol.app

import com.leapcontrol.app.widget.WidgetMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件「信息区大小」的换算（2026-11-09 需求）。
 *
 * 用户原话：「这个进度条不要单独设置，这整个续航区域一块整体缩放，但是进度条的粗细
 * 默认增加到 9dp」——所以这里钉三件事：
 *
 * 1. **只有一个倍率**：字号和条粗细都从「信息区大小」推出来，不再有第二根滑杆；
 * 2. **默认 9dp**，随倍率一起乘，且永远夹在 12dp 槽位内；
 * 3. **数字要量宽度**：里程槽是定宽的，字体一宽就会切掉单位（2026-11-09 的 m 被裁），
 *    所以先用系统字体量一遍，超了才缩。
 *
 * 这些都是纯算术，能在单测里算得很死，不必每次都装到手机上看。
 */
class WidgetMetricsTest {

    // ---- 字号 ----

    @Test
    fun `默认组合就是原样`() {
        assertEquals(1.0f, WidgetMetrics.textScale(cardScale = 1.0f, infoScale = 1.0f), 0.001f)
    }

    @Test
    fun `用户调大时卡片尺寸系数继续叠乘`() {
        assertEquals(1.2f, WidgetMetrics.textScale(cardScale = 1.0f, infoScale = 1.2f), 0.001f)
        assertEquals(1.1f, WidgetMetrics.textScale(cardScale = 0.9167f, infoScale = 1.2f), 0.005f)
    }

    @Test
    fun `两头上限都夹住`() {
        // 大屏卡片（1.28）× 用户最大（1.3）会到 1.664，不夹就会把标题顶出卡片
        assertEquals(
            WidgetMetrics.TEXT_MAX_SCALE,
            WidgetMetrics.textScale(cardScale = 1.28f, infoScale = 1.3f),
            0.001f,
        )
        // 小屏卡片（0.82）× 用户最小（0.8）= 0.656，再小就只剩一坨灰点
        assertEquals(
            WidgetMetrics.MIN_SCALE,
            WidgetMetrics.textScale(cardScale = 0.82f, infoScale = 0.8f),
            0.001f,
        )
    }

    @Test
    fun `数字的上限比标题更紧`() {
        // 数字装在定宽槽里，标题是 weight + ellipsize，宽容度不一样
        assertTrue(WidgetMetrics.NUM_MAX_SCALE < WidgetMetrics.TEXT_MAX_SCALE)
    }

    // ---- 数字的实测宽度 ----

    @Test
    fun `默认倍率放得下就一点都不缩`() {
        // 13dp × 1.1 的「888km」在常见字体里约 45dp（46dp 列内）
        val scale = WidgetMetrics.numberSizeScale(
            merged = 1.1f,
            baseDp = 13f,
            limitDp = WidgetMetrics.VALUE_COLUMN_WIDTH_DP,
            measureDp = { size -> size * 3.15f }, // 3.15em ≈ 45dp @14.3dp
        )
        assertEquals(1.1f, scale, 0.001f)
    }

    @Test
    fun `字体偏宽时缩到刚好占满列宽`() {
        // 每 dp 字号占 4em 的宽字体：13dp × 1.3 = 16.9dp → 67.6dp，远超 46dp
        val scale = WidgetMetrics.numberSizeScale(
            merged = 1.3f,
            baseDp = 13f,
            limitDp = 46f,
            measureDp = { size -> size * 4f },
        )
        assertTrue("应当缩小：$scale", scale < 1.3f)
        // 缩完之后的宽度必须正好等于列宽（不浪费、也不溢出）
        assertEquals(46f, 13f * scale * 4f, 0.01f)
    }

    @Test
    fun `量不出宽度时不缩`() {
        // 字体没就绪 / ROM 返回 0：按上限走，别把字缩成 0
        assertEquals(
            1.2f,
            WidgetMetrics.numberSizeScale(1.2f, 13f, 46f) { 0f },
            0.001f,
        )
    }

    @Test
    fun `再怎么放大也不越过数字上限`() {
        val scale = WidgetMetrics.numberSizeScale(
            merged = 2f,
            baseDp = 13f,
            limitDp = 60f,
            measureDp = { 10f },
        )
        assertEquals(WidgetMetrics.NUM_MAX_SCALE, scale, 0.001f)
    }

    @Test
    fun `宽版的列更宽，同一条文案能放得更大`() {
        // 固定 60dp 宽的字（比如某个 ROM 的中文字体）
        val measure: (Float) -> Float = { 60f }
        val narrow = WidgetMetrics.numberSizeScale(1.3f, 13f, WidgetMetrics.VALUE_COLUMN_WIDTH_DP, measure)
        val wide = WidgetMetrics.numberSizeScale(1.3f, 13f, WidgetMetrics.VALUE_COLUMN_WIDTH_WIDE_DP, measure)
        assertTrue("宽版应该能放得更大：$narrow vs $wide", wide > narrow)
    }

    @Test
    fun `里程列的最小宽度与首页里程槽同一量级`() {
        // 首页 MileageRow 的里程槽是 48dp（13sp「445km」实测约 45dp）；小组件这一列
        // 46dp / 宽版 52dp，同一句话放得下，也不会把进度条挤没。
        assertTrue(WidgetMetrics.VALUE_COLUMN_WIDTH_DP in 44f..50f)
        assertTrue(WidgetMetrics.VALUE_COLUMN_WIDTH_WIDE_DP > WidgetMetrics.VALUE_COLUMN_WIDTH_DP)
    }

    // ---- 进度条粗细 ----

    @Test
    fun `默认粗细是 9dp，且跟着倍率一起变`() {
        assertEquals(9f, WidgetMetrics.barThicknessDp(1f), 0.001f)
        assertEquals(10.8f, WidgetMetrics.barThicknessDp(1.2f), 0.001f)
        // 最小 0.8 → 7.2dp，仍然看得见
        assertEquals(7.2f, WidgetMetrics.barThicknessDp(0.8f), 0.001f)
    }

    @Test
    fun `粗细永远夹在槽位内`() {
        assertTrue("不能超过 12dp 的槽", WidgetMetrics.barThicknessDp(5f) <= WidgetMetrics.BAR_SLOT_DP)
        assertEquals(WidgetMetrics.MIN_BAR_DP, WidgetMetrics.barThicknessDp(0.01f), 0.001f)
        assertTrue(WidgetMetrics.BAR_BASE_DP < WidgetMetrics.BAR_SLOT_DP)
    }

    @Test
    fun `默认 9dp 在 12dp 槽里上下各留 1_5dp`() {
        // 密度 2.0：1.5dp → 3px
        assertEquals(3, WidgetMetrics.barPaddingPx(thicknessDp = 9f, density = 2f))
    }

    @Test
    fun `调细时内边距变大、调粗时变小`() {
        val thin = WidgetMetrics.barPaddingPx(thicknessDp = 3f, density = 2f)
        val mid = WidgetMetrics.barPaddingPx(thicknessDp = 9f, density = 2f)
        val thick = WidgetMetrics.barPaddingPx(thicknessDp = 12f, density = 2f)
        assertTrue("越细 padding 应该越大：$thin / $mid / $thick", thin > mid)
        assertTrue("最粗时不该再有上下留白", thick == 0)
    }

    @Test
    fun `越界值一律夹回可用范围`() {
        // 比槽位还粗：夹到 12dp，没有负内边距
        assertEquals(0, WidgetMetrics.barPaddingPx(thicknessDp = 40f, density = 3f))
        // 细到看不见：夹到 3dp
        assertEquals(
            WidgetMetrics.barPaddingPx(thicknessDp = WidgetMetrics.MIN_BAR_DP, density = 3f),
            WidgetMetrics.barPaddingPx(thicknessDp = 0.5f, density = 3f),
        )
    }

    @Test
    fun `px 换算按密度走且不会为负`() {
        assertEquals(9, WidgetMetrics.barPaddingPx(thicknessDp = 3f, density = 2f))
        listOf(1f, 1.5f, 2.75f, 3f, 4f).forEach { density ->
            assertTrue(
                "密度 $density 下算出了负内边距",
                WidgetMetrics.barPaddingPx(thicknessDp = 9f, density = density) >= 0,
            )
        }
    }
}
