package com.leapcontrol.app

import com.leapcontrol.app.ui.ControlActions
import com.leapcontrol.app.ui.ControlFeedback
import com.leapcontrol.app.ui.vehicleStatusRows
import com.leapcontrol.app.ui.withControlFlash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分置版首页右上角那几行（[vehicleStatusRows]）的规则。
 *
 * 当前守的是哨兵这一条：用户 2026-11-08 要求「哨兵模式开启时，右上角要显示
 * 一个状态：哨兵已开启」。要点三个 ——
 * 1. 只有**真开着**才画（关着是默认态，不该常年占一行）；
 * 2. 位置紧跟闭锁（同属安防状态），但不能顶掉闭锁的基线位置；
 * 3. 带 [ControlFeedback.TOPIC_SENTRY]，这样「哨兵开启中…」能原地顶替，
 *    不会出现「哨兵已开启 · 哨兵开启中…」两句并排。
 */
class VehicleStatusRowTest {

    private fun rows(
        locked: Boolean? = true,
        acOn: Boolean? = null,
        windowOpen: Boolean? = null,
        trunkOpen: Boolean? = null,
        sunshadeOpen: Boolean? = null,
        sentryOn: Boolean? = null,
    ) = vehicleStatusRows(locked, acOn, windowOpen, trunkOpen, sunshadeOpen, sentryOn)

    private fun sentryFlash(text: String = "哨兵开启中…") =
        ControlFeedback.Flash(text, ControlFeedback.Phase.RUNNING, "sentryOn")

    @Test
    fun `哨兵开着时显示哨兵已开启，且紧跟闭锁`() {
        val r = rows(sentryOn = true)
        assertEquals(listOf("车辆已上锁", "哨兵已开启"), r.map { it.label })
    }

    @Test
    fun `哨兵关着或信号缺失都不占行`() {
        assertTrue(rows(sentryOn = false).none { it.label.contains("哨兵") })
        assertTrue(rows(sentryOn = null).none { it.label.contains("哨兵") })
    }

    @Test
    fun `哨兵行不是告警色，但带自己的 topic 和图标`() {
        val row = rows(sentryOn = true).first { it.label == "哨兵已开启" }
        assertFalse("哨兵开着是常态，不该红", row.alert)
        assertEquals(ControlFeedback.TOPIC_SENTRY, row.topic)
        // 用户 2026-11-08：右上角这一行的图标 = 零控页快捷控车按钮上那颗哨兵。
        // 断言「与按钮同源」而不是写死资源名 —— 以后换按钮图标，这里会立刻提醒同步。
        assertEquals(ControlActions.find("sentry")!!.iconRes, row.iconRes)
    }

    @Test
    fun `每一行的图标都与对应控车按钮同源`() {
        val r = rows(
            locked = true,
            sentryOn = true,
            windowOpen = true,
            trunkOpen = true,
            sunshadeOpen = true,
        )
        val byLabel = r.associateBy { it.label }
        assertEquals(ControlActions.iconOf("lock"), byLabel.getValue("车辆已上锁").iconRes)
        assertEquals(ControlActions.iconOf("sentry"), byLabel.getValue("哨兵已开启").iconRes)
        assertEquals(ControlActions.iconOf("window"), byLabel.getValue("车窗已开启").iconRes)
        assertEquals(ControlActions.iconOf("trunk"), byLabel.getValue("后备箱已开启").iconRes)
        assertEquals(ControlActions.iconOf("sunshade"), byLabel.getValue("遮阳帘已开启").iconRes)
        // 解锁那一行要跟「解锁」按钮走，不能错用上锁的图标
        assertEquals(
            ControlActions.iconOf("unlock"),
            rows(locked = false).single().iconRes,
        )
    }

    @Test
    fun `哨兵排在车窗后备箱遮阳帘空调之前`() {
        val r = rows(
            sentryOn = true,
            windowOpen = true,
            trunkOpen = true,
            sunshadeOpen = true,
            acOn = true,
        )
        assertEquals(
            listOf("车辆已上锁", "哨兵已开启", "车窗已开启", "后备箱已开启", "遮阳帘已开启", "空调已开启"),
            r.map { it.label },
        )
    }

    @Test
    fun `闭锁信号缺失时哨兵仍然能显示`() {
        val r = rows(locked = null, sentryOn = true)
        assertEquals(listOf("哨兵已开启"), r.map { it.label })
    }

    @Test
    fun `控车提示顶掉哨兵那一行而不是再多一行`() {
        val before = rows(sentryOn = true)
        val after = withControlFlash(before, sentryFlash())
        assertEquals(before.size, after.size)
        assertEquals("哨兵开启中…", after[1].label)
        assertTrue("正在执行的那行要提亮", after[1].accent)
    }

    @Test
    fun `车况里没有哨兵行时，控车提示追加到末尾`() {
        val after = withControlFlash(rows(sentryOn = false), sentryFlash())
        assertEquals(listOf("车辆已上锁", "哨兵开启中…"), after.map { it.label })
    }

    @Test
    fun `哨兵失败提示走告警色`() {
        val after = withControlFlash(
            rows(sentryOn = true),
            ControlFeedback.Flash("开哨兵失败", ControlFeedback.Phase.FAILED, "sentryOn"),
        )
        assertTrue(after[1].alert)
        assertFalse(after[1].accent)
    }
}
