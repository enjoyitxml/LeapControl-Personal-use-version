package com.leapcontrol.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车窗开关位的解析守门。
 *
 * 起因（用户 2026-11-08 真机反馈）：充电时开了下车门、再关上，首页顶出一条
 * 「车窗已开启」，之后刷新也去不掉，而车实际从没开过窗。
 * 旧实现是「有值且 != 0 就是开」，车门开关过之后车端上报的中间态 / 无效值
 * （2、3、255、-1）全被算成了「开着」。
 */
class WindowSignalTest {

    private fun status(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }

    private fun oneWindow(value: Any?): WindowSignalState? =
        VehicleSignals.windowStates(status("driverWindowStatus" to value))["左前"]

    // ---------------- 单扇窗 ----------------

    @Test
    fun `0 是关 1 是开`() {
        assertEquals(WindowSignalState.CLOSED, oneWindow(0))
        assertEquals(WindowSignalState.OPEN, oneWindow(1))
    }

    /** 车门开关过之后车端会给中间态；这些值绝不能当成「开着」。 */
    @Test
    fun `中间态与无效值一律未知`() {
        listOf(2, 3, 4, 255, -1).forEach { raw ->
            assertEquals("取值 $raw 不该被判成开或关", WindowSignalState.UNKNOWN, oneWindow(raw))
        }
    }

    @Test
    fun `字符串形态按数值走`() {
        assertEquals(WindowSignalState.OPEN, oneWindow("1"))
        assertEquals(WindowSignalState.OPEN, oneWindow("1.0"))
        assertEquals(WindowSignalState.OPEN, oneWindow("open"))
        assertEquals(WindowSignalState.OPEN, oneWindow(" Opening "))
        assertEquals(WindowSignalState.CLOSED, oneWindow("0"))
        assertEquals(WindowSignalState.CLOSED, oneWindow("0.0"))
        assertEquals(WindowSignalState.CLOSED, oneWindow("closed"))
        assertEquals(WindowSignalState.UNKNOWN, oneWindow("2"))
        assertEquals(WindowSignalState.UNKNOWN, oneWindow("--"))
    }

    @Test
    fun `布尔信号直接映射`() {
        assertEquals(WindowSignalState.OPEN, oneWindow(true))
        assertEquals(WindowSignalState.CLOSED, oneWindow(false))
    }

    /** 没有这一路信号 ≠ 关着：不产出状态，交给调用方判断「读不到」。 */
    @Test
    fun `字段缺失时不产出状态`() {
        assertNull(oneWindow(null))
        assertEquals(emptyMap<String, WindowSignalState>(), VehicleSignals.windowStates(JSONObject()))
    }

    // ---------------- 汇总成「是否有窗开着」 ----------------

    /** 用户踩到的那条：只有左前窗报了 2，其余缺失 → 既不能说开着、也不能说全关。 */
    @Test
    fun `车门开关后的无效值不再顶出车窗已开启`() {
        val st = status("driverWindowStatus" to 2)
        assertNull("值不可信时应该显示不出来，而不是显示开着", VehicleSignals.windowOpen(st))
        assertTrue(VehicleSignals.openWindowLabels(st).isEmpty())
    }

    @Test
    fun `任一窗明确开就是开`() {
        val st = status(
            "driverWindowStatus" to 0,
            "rightFrontWindowStatus" to 0,
            "leftRearWindowStatus" to 1,
            "rightRearWindowStatus" to 2,
        )
        assertEquals(true, VehicleSignals.windowOpen(st))
        assertEquals(listOf("左后"), VehicleSignals.openWindowLabels(st))
    }

    @Test
    fun `四窗都明确关着才是关`() {
        val st = status(
            "driverWindowStatus" to 0,
            "rightFrontWindowStatus" to 0,
            "leftRearWindowStatus" to 0,
            "rightRearWindowStatus" to 0,
        )
        assertEquals(false, VehicleSignals.windowOpen(st))
    }

    /** 三扇关着、一扇给了无效值：不能替车端拍胸脯说「全关」。 */
    @Test
    fun `有未知值时不下全关的结论`() {
        val st = status(
            "driverWindowStatus" to 0,
            "rightFrontWindowStatus" to 0,
            "leftRearWindowStatus" to 0,
            "rightRearWindowStatus" to 3,
        )
        assertNull(VehicleSignals.windowOpen(st))
    }

    @Test
    fun `没有任何车窗信号返回读不到`() {
        assertNull(VehicleSignals.windowOpen(status("soc" to 80)))
    }

    /** T03 不上报车窗，整块按「读不到」处理（老逻辑就是排除这个车型）。 */
    @Test
    fun `T03 不看车窗信号`() {
        val st = status("driverWindowStatus" to 1)
        assertNull(VehicleSignals.windowOpen(st, "零跑T03"))
        assertTrue(VehicleSignals.openWindowLabels(st, "零跑T03").isEmpty())
    }

    // ---------------- 开度百分比优先 ----------------

    /**
     * 开关位卡在 1、开度百分比明确是 0：以开度为准。
     *
     * 这是「无框车门开门时玻璃短降」最容易留下的假象 —— 玻璃已经升回去了，
     * 状态位还停在「开」。
     */
    @Test
    fun `百分比说 0 时不信开关位`() {
        val st = status(
            "leftFrontWindowPercent" to 0,
            "driverWindowStatus" to 1,
        )
        assertEquals(WindowSignalState.CLOSED, VehicleSignals.windowStates(st)["左前"])
        assertEquals(false, VehicleSignals.windowOpen(st))
    }

    @Test
    fun `百分比大于 0 就是开`() {
        val st = status("leftFrontWindowPercent" to 35)
        assertEquals(true, VehicleSignals.windowOpen(st))
    }

    @Test
    fun `百分比带百分号也认`() {
        val st = status("rightRearWindowPercent" to "35%")
        assertEquals(listOf("右后"), VehicleSignals.openWindowLabels(st))
    }

    /**
     * 2026-11-12：开度百分比超出 0~100 是车端的无效值（-1 / 255），不能拿来下结论。
     *
     * 旧实现直接和 0 比：`255 > 0` 会被判成「开着」、`-1` 会被判成「关着」——
     * 前者是 2026-11-08 那种假「车窗已开启」的另一个入口，后者会把真开着的窗说成关。
     * 现在无效百分比退回开关位判断，开关位也没有就老实说读不到。
     */
    @Test
    fun `百分比超出 0 到 100 时退回开关位`() {
        // 255 不是开度，交给开关位（1 = 开）
        assertEquals(
            WindowSignalState.OPEN,
            VehicleSignals.windowStates(
                status("leftFrontWindowPercent" to 255, "driverWindowStatus" to 1),
            )["左前"],
        )
        // -1 也不是开度，交给开关位（0 = 关）
        assertEquals(
            WindowSignalState.CLOSED,
            VehicleSignals.windowStates(
                status("leftFrontWindowPercent" to -1, "driverWindowStatus" to 0),
            )["左前"],
        )
        // 只有无效百分比、没有开关位 → 读不到，不猜
        assertNull(VehicleSignals.windowStates(status("leftFrontWindowPercent" to -1))["左前"])
        // 边界值本身合法
        assertEquals(
            WindowSignalState.CLOSED,
            VehicleSignals.windowStates(status("leftFrontWindowPercent" to 0))["左前"],
        )
        assertEquals(
            WindowSignalState.OPEN,
            VehicleSignals.windowStates(status("leftFrontWindowPercent" to 100))["左前"],
        )
    }
}
