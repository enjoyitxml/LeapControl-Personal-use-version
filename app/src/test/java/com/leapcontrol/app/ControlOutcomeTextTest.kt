package com.leapcontrol.app

import com.leapcontrol.app.ui.ControlOutcomeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控车结论那句话（2026-11-08）。
 *
 * ## 用户的原话
 * 「我测试了一下，弹出的这几个按钮下发指令不出去，指令是不通的，发了指令，一直在响应，
 *   我刷新一下就弹出操作成功，但是事实上并没有操作成功。」
 *
 * ## 查到的那一格
 * `control()` 之前的结论是这么写的：网关受理后去车况求证，**求证结果只要不是 `true`
 * 就一律回「执行成功」** —— 连「有车窗信号但一次都没读到」也被算成成功。于是车没动，
 * 界面上却是「车窗已全开」，右上角还跟着写完成式。这次把这一格单独拆出来钉死。
 */
class ControlOutcomeTextTest {

    private fun of(
        accepted: Boolean = true,
        verified: Boolean? = null,
        readable: Boolean = true,
        notAccepted: String = "指令已经发出，车辆还没回话",
    ) = ControlOutcomeText.of(
        label = "车窗全开",
        accepted = accepted,
        verified = verified,
        readable = readable,
        notAccepted = notAccepted,
    )

    @Test
    fun `车况真的变了才算成功`() {
        assertEquals("车窗全开执行成功", of(verified = true))
    }

    @Test
    fun `读得到信号但没变化 不许说成功`() {
        val text = of(verified = false)
        assertFalse("不能出现「执行成功」", text.endsWith("执行成功"))
        assertTrue(text, text.contains("还没变化"))
        assertTrue("要告诉用户下一步怎么办", text.contains("下拉刷新") || text.contains("到车上确认"))
    }

    @Test
    fun `有信号却一次都没读到 不许说成功`() {
        // 这就是用户 11-08 踩到的那一格：网关回 0，车况读不到车窗信号，
        // 旧代码直接说「执行成功」，其实车一动没动
        val text = of(verified = null, readable = true)
        assertFalse("不能出现「执行成功」", text.endsWith("执行成功"))
        assertTrue(text, text.contains("读不到车况"))
    }

    @Test
    fun `本来就没有可回读信号的指令 按网关回执说成功`() {
        // 鸣笛 / 电池预热 / 充电枪：车况里没有对应的开合态，只能信网关
        val horn = ControlOutcomeText.of(
            label = "鸣笛寻车",
            accepted = true,
            verified = null,
            readable = false,
            notAccepted = "指令已经发出，车辆还没回话",
        )
        assertEquals("鸣笛寻车执行成功", horn)
    }

    @Test
    fun `网关都没受理时走调用方的兜底文案`() {
        assertEquals(
            "指令已经发出，车辆还没回话",
            of(accepted = false, verified = null, notAccepted = "指令已经发出，车辆还没回话"),
        )
    }

    @Test
    fun `只有执行成功才算成功 其它一律不算`() {
        assertTrue(ControlOutcomeText.isSuccess("车窗全开执行成功"))
        assertFalse(ControlOutcomeText.isSuccess("车窗全开已发出，但读不到车况，请到车上确认"))
        assertFalse(ControlOutcomeText.isSuccess("车窗全开指令已发出，但车辆状态还没变化，请下拉刷新或到车上确认"))
        assertFalse(ControlOutcomeText.isSuccess("指令已经发出，车辆还没回话"))
    }
}
