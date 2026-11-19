package com.leapcontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 充电中心的时间/周期逻辑。
 *
 * 这组用例锁三件事：
 *  1. **跨天判断** —— 23:00→07:00 必须显示「次日」，否则卡片上会写成「23:00-07:00」，
 *     用户会以为只充 0 分钟；09:00→18:00 又不能标次日。
 *  2. **重复日文案与掩码** —— 190 的 `cycles` 是 7 位 0/1 掩码（索引 0=周一），
 *     161 的 `days` 是整数数组（0=周日），两套编号规则不同，混用会把周一设成周日。
 *  3. **充电上限对齐** —— 车端只收整数百分比，滑杆是 5% 一档，越界值必须夹到 50..100
 *     （区间与上游 leapauto 的 HealthyChargingBottomSheet 一致）。
 */
class ChargeScheduleTest {

    @Test
    fun `结束早于开始算跨天`() {
        assertTrue(ChargeSchedule.isOvernight("23:00", "07:00"))
        assertTrue(ChargeSchedule.isOvernight("22:30", "06:00"))
        // 相等按一整天处理（车端也是当日 +1）
        assertTrue(ChargeSchedule.isOvernight("23:00", "23:00"))
    }

    @Test
    fun `同日时段不标次日`() {
        assertFalse(ChargeSchedule.isOvernight("09:00", "18:00"))
        assertFalse(ChargeSchedule.isOvernight("00:00", "08:00"))
        // 时间格式不合法时不做任何假设
        assertFalse(ChargeSchedule.isOvernight("", "07:00"))
        assertFalse(ChargeSchedule.isOvernight("25:00", "07:00"))
    }

    @Test
    fun `时段文案带次日标记`() {
        assertEquals("23:00-次日07:00", ChargeSchedule.rangeText("23:00", "07:00"))
        assertEquals("09:00-18:00", ChargeSchedule.rangeText("09:00", "18:00"))
    }

    @Test
    fun `重复日文案`() {
        assertEquals("每天", ChargeSchedule.daysLabel(ChargeSchedule.ALL_DAYS))
        assertEquals("工作日", ChargeSchedule.daysLabel(setOf(1, 2, 3, 4, 5)))
        assertEquals("周末", ChargeSchedule.daysLabel(setOf(6, 7)))
        assertEquals("周一、周四", ChargeSchedule.daysLabel(setOf(1, 4)))
        // 空集合按全周处理：车端 cycles 为空等价于每天
        assertEquals("每天", ChargeSchedule.daysLabel(emptySet()))
    }

    @Test
    fun `卡片副标题`() {
        assertEquals(
            "每天 23:00-次日07:00",
            ChargeSchedule.summaryText("23:00", "07:00", ChargeSchedule.ALL_DAYS),
        )
        assertEquals(
            "工作日 09:00-18:00",
            ChargeSchedule.summaryText("09:00", "18:00", setOf(1, 2, 3, 4, 5)),
        )
    }

    @Test
    fun `充电上限夹取并对齐5的倍数`() {
        // 下界 50（与上游 leapauto 一致）：更小的值抬到 50
        assertEquals(50, ChargeSchedule.sanitizeSoc(20))
        assertEquals(50, ChargeSchedule.sanitizeSoc(50))
        assertEquals(80, ChargeSchedule.sanitizeSoc(80))
        assertEquals(100, ChargeSchedule.sanitizeSoc(120))
        assertEquals(90, ChargeSchedule.sanitizeSoc(90))
        assertEquals(95, ChargeSchedule.sanitizeSoc(93))
        assertEquals(100, ChargeSchedule.sanitizeSoc(98))
        // 50..100 之间共 11 个档位
        assertEquals(
            listOf(50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100),
            ChargeSchedule.LIMIT_STEPS,
        )
        // 刻度点每 10% 一个，不是 11 档全画
        assertEquals(listOf(60, 70, 80, 90), ChargeSchedule.SOC_TICKS)
    }

    @Test
    fun `滑杆位置与上限互转`() {
        assertEquals(0f, ChargeSchedule.socToFraction(50), 0.001f)
        assertEquals(1f, ChargeSchedule.socToFraction(100), 0.001f)
        assertEquals(0.5f, ChargeSchedule.socToFraction(75), 0.001f)
        assertEquals(50, ChargeSchedule.fractionToSoc(0f))
        assertEquals(75, ChargeSchedule.fractionToSoc(0.5f))
        assertEquals(100, ChargeSchedule.fractionToSoc(1f))
    }

    @Test
    fun `打开健康充电时满上限自动降到90`() {
        // 已经拉满 100 的，开启那一刻落到官方推荐值
        assertEquals(90, ChargeSchedule.onHealthyEnabled(100))
        // 本来就在养护区间里的值不动
        assertEquals(95, ChargeSchedule.onHealthyEnabled(95))
        assertEquals(80, ChargeSchedule.onHealthyEnabled(80))
        // 越界值先对齐再判断
        assertEquals(90, ChargeSchedule.onHealthyEnabled(120))
    }

    @Test
    fun `健康充电开着时拖到100吸到95`() {
        assertEquals(95, ChargeSchedule.onDragSoc(100, healthyEnabled = true))
        assertEquals(95, ChargeSchedule.onDragSoc(98, healthyEnabled = true))
        assertEquals(90, ChargeSchedule.onDragSoc(90, healthyEnabled = true))
        // 关掉健康充电就允许拉满
        assertEquals(100, ChargeSchedule.onDragSoc(100, healthyEnabled = false))
        // 下界也按 50 对齐
        assertEquals(50, ChargeSchedule.onDragSoc(10, healthyEnabled = false))
    }

    @Test
    fun `充电上限按滑杆值下发，不随健康充电开关改写`() {
        // 这条是「上限设了不生效」的守门：开关关闭时也必须发用户选的值，
        // 曾经关闭时固定发 100，滑杆显示 80 却永远发出 100。
        assertEquals(80, ChargeSchedule.socForDispatch(80))
        assertEquals(90, ChargeSchedule.socForDispatch(90))
        assertEquals(95, ChargeSchedule.socForDispatch(95))
        assertEquals(100, ChargeSchedule.socForDispatch(100))
        // 越界与 5 的倍数对齐仍在
        assertEquals(50, ChargeSchedule.socForDispatch(30))
        assertEquals(100, ChargeSchedule.socForDispatch(120))
        assertEquals(85, ChargeSchedule.socForDispatch(83))
    }

    @Test
    fun `时间格式统一两位`() {
        assertEquals("07:05", ChargeSchedule.formatTime(7, 5))
        assertEquals("23:00", ChargeSchedule.formatTime(23, 0))
        assertEquals("07:30", ChargeSchedule.timeOfDay("2026-10-28 07:30:00"))
        assertEquals("23:00", ChargeSchedule.timeOfDay("23:00"))
        assertNull(ChargeSchedule.timeOfDay(""))
    }

    @Test
    fun `161 的 days 数组把0映射成周日`() {
        val days = ChargeSchedule.daysFrom161(JSONArray(listOf(0, 1, 2)))
        assertEquals(setOf(7, 1, 2), days)
        // 空数组/缺失按每天
        assertEquals(ChargeSchedule.ALL_DAYS, ChargeSchedule.daysFrom161(null))
        assertEquals(ChargeSchedule.ALL_DAYS, ChargeSchedule.daysFrom161(JSONArray()))
    }

    @Test
    fun `解析161预约项`() {
        val payload = JSONObject()
            .put(
                "controls",
                JSONArray().put(
                    JSONObject()
                        .put("on", "1")
                        .put("start_time", "2026-10-28 07:30:00")
                        .put("days", JSONArray(listOf(1, 2, 3, 4, 5))),
                ),
            )
            .toString()

        val state = ChargeSchedule.parsePreheatState(payload)!!
        assertTrue(state.hasSchedule)
        assertEquals("07:30", state.startTime)
        assertEquals("1,1,1,1,1,0,0", state.days)
    }

    @Test
    fun `161 没有预约项时不当作开启`() {
        val payload = JSONObject().put("controls", JSONArray()).toString()
        val state = ChargeSchedule.parsePreheatState(payload)!!
        assertFalse(state.hasSchedule)
        assertNull(state.startTime)
        assertNull(state.days)
    }

    @Test
    fun `161 返回结构异常时返回空让调用方保留草稿`() {
        assertNull(ChargeSchedule.parsePreheatState(null))
        assertNull(ChargeSchedule.parsePreheatState(""))
        assertNull(ChargeSchedule.parsePreheatState("not-json"))
        assertNull(ChargeSchedule.parsePreheatState(JSONObject().toString()))
    }

    @Test
    fun `草稿默认值与官方默认一致`() {
        val draft = ChargeCenterDraft()
        assertEquals(100, draft.limitSoc)
        assertEquals("23:00", draft.scheduledStart)
        assertEquals("07:00", draft.scheduledEnd)
        assertTrue(draft.continueUntilLimit)
        assertEquals(ChargeSchedule.ALL_DAYS, draft.scheduledDays)
    }
}
