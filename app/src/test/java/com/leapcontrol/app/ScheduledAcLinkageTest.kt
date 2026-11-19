package com.leapcontrol.app

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时空调「到点联动」的纯逻辑。
 *
 * 这里守三件事：
 *  1. **指令顺序** —— 关窗必须排在座椅前面，越靠前越急；
 *  2. **开关到指令的映射** —— 勾了哪几项就只发哪几条，不多发；
 *  3. **触发时刻** —— 今天还没到就今天，过了就顺延到下一个匹配的重复日。
 */
class ScheduledAcLinkageTest {

    @Test
    fun `没勾任何项时没有任何指令`() {
        val empty = ScheduledAcLinkage.EMPTY
        assertFalse(empty.anyEnabled)
        assertTrue(empty.commands().isEmpty())
        assertEquals("", empty.summary())
    }

    @Test
    fun `关窗排在所有舒适项之前`() {
        val linkage = ScheduledAcLinkage(
            closeWindows = true,
            closeSunshade = true,
            steeringWheelHeating = true,
            driverHeating = true,
        )
        assertEquals(
            listOf("windowClose", "sunshadeClose", "driverSeatHeating_2", "steeringWheelHeating_on"),
            linkage.commands(),
        )
    }

    @Test
    fun `勾哪几项就只发哪几条`() {
        val linkage = ScheduledAcLinkage(
            closeSunshade = true,
            passengerVent = true,
            leftRearHeating = true,
            rightRearVent = true,
        )
        assertEquals(
            listOf(
                "sunshadeClose",
                "passengerSeatVentilation_2",
                "leftRearSeatHeating_2",
                "rightRearSeatVentilation_2",
            ),
            linkage.commands(),
        )
        assertTrue(linkage.anyEnabled)
    }

    @Test
    fun `全选十一项的指令条数与顺序`() {
        val all = ScheduledAcLinkage(
            closeWindows = true,
            closeSunshade = true,
            driverHeating = true,
            driverVent = true,
            passengerHeating = true,
            passengerVent = true,
            leftRearHeating = true,
            leftRearVent = true,
            rightRearHeating = true,
            rightRearVent = true,
            steeringWheelHeating = true,
        )
        val commands = all.commands()
        assertEquals(11, commands.size)
        assertEquals("windowClose", commands.first())
        assertEquals("steeringWheelHeating_on", commands.last())
        // 座椅固定 2 档，不能是 3 档（过热）
        assertTrue(commands.filter { it.contains("Seat") }.all { it.endsWith("_2") })
    }

    @Test
    fun `摘要按选择拼中文`() {
        val linkage = ScheduledAcLinkage(closeWindows = true, driverHeating = true, steeringWheelHeating = true)
        assertEquals("关窗、主驾加热、方向盘加热", linkage.summary())
    }

    // ---------------- 触发时刻 ----------------

    private fun at(month: Int, day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(2026, month - 1, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `今天还没到点就排今天`() {
        val now = at(11, 19, 13, 0)
        val next = ScheduledAcLinkageSchedule.nextTriggerAt(now, "20:00", "1,1,1,1,1,1,1")
        assertNotNull(next)
        assertEquals(now + 7 * 60 * 60 * 1000L, next)
    }

    @Test
    fun `今天的点已经过了就顺延到明天`() {
        val now = at(11, 19, 13, 0)
        val next = ScheduledAcLinkageSchedule.nextTriggerAt(now, "07:30", "1,1,1,1,1,1,1")
        assertNotNull(next)
        // 24 小时后
        assertEquals(now + 24 * 60 * 60 * 1000L - (5 * 60 + 30) * 60 * 1000L, next)
    }

    @Test
    fun `只勾周一则跨到下一个周一`() {
        val now = at(11, 19, 13, 0) // 周四
        val next = ScheduledAcLinkageSchedule.nextTriggerAt(now, "09:00", "1,0,0,0,0,0,0")
        assertNotNull(next)
        val cal = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `重复日为空按每天处理`() {
        val now = at(11, 19, 13, 0)
        assertNotNull(ScheduledAcLinkageSchedule.nextTriggerAt(now, "20:00", ""))
        assertNotNull(ScheduledAcLinkageSchedule.nextTriggerAt(now, "20:00", null))
    }

    @Test
    fun `时间格式不对返回空`() {
        val now = at(11, 19, 13, 0)
        assertNull(ScheduledAcLinkageSchedule.nextTriggerAt(now, "", "1,1,1,1,1,1,1"))
        assertNull(ScheduledAcLinkageSchedule.nextTriggerAt(now, "25:00", "1,1,1,1,1,1,1"))
        assertNull(ScheduledAcLinkageSchedule.nextTriggerAt(now, "07:99", "1,1,1,1,1,1,1"))
    }

    @Test
    fun `星期映射按周一为一`() {
        assertEquals(1, ScheduledAcLinkageSchedule.isoDayOfWeek(Calendar.MONDAY))
        assertEquals(4, ScheduledAcLinkageSchedule.isoDayOfWeek(Calendar.THURSDAY))
        assertEquals(7, ScheduledAcLinkageSchedule.isoDayOfWeek(Calendar.SUNDAY))
        assertEquals(6, ScheduledAcLinkageSchedule.isoDayOfWeek(Calendar.SATURDAY))
    }
}
