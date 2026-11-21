package com.leapcontrol.app

import com.leapcontrol.app.ui.ControlActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页快捷控车的按钮布局：**上限不设，本车支持的都能加**。
 *
 * 以前编辑页那句「最多 7 个（双行共 8 格，最后一格为编辑）」是给**双行格子**布局写的：
 * 8 格里留一格给编辑键，所以只能选 7 个。[ControlActions.sanitize] 也真的会
 * `take(7)`，用户加第 8 个按钮时点击毫无反应（2026-11-21 用户反馈）。
 *
 * 现在的首页控车行是**横向滚动的一行**（一行显示 4 个、左右滑动看其余），
 * 编辑键只是行尾多一个格子，不存在「挤掉别人」的问题，所以上限只由
 * 「[ControlActions.all] 里本车支持的按钮数」决定。
 *
 * 这个文件守三件事：
 * 1. 全部按钮都能存下来（不再被截到 7 个）；
 * 2. 下限仍然是 3 个，少于 3 个退回默认布局（避免用户把自己锁在只有 1 个按钮的界面里）；
 * 3. 已下线的指令 id 与重复项照旧剔除——不设上限不等于什么都往里灌。
 */
class ControlActionsLayoutTest {

    @Test
    fun `全部按钮都能保存 不再截到 7 个`() {
        val all = ControlActions.all.map { it.id }
        // 当年卡的就是这一步：10 个 id 进去只出来 7 个
        assertTrue("按钮总数应大于旧的 7 个上限，否则这个测试没意义", all.size > 7)
        assertEquals(all, ControlActions.sanitize(all))
    }

    @Test
    fun `默认布局就是全部按钮`() {
        assertEquals(
            ControlActions.all.map { it.id },
            ControlActions.defaultLayout,
        )
    }

    @Test
    fun `最少 3 个 少于 3 个退回默认布局`() {
        val two = ControlActions.all.take(2).map { it.id }
        assertEquals(ControlActions.defaultLayout, ControlActions.sanitize(two))
        // 正好 3 个要保留，不能也退回默认
        val three = ControlActions.all.take(3).map { it.id }
        assertEquals(three, ControlActions.sanitize(three))
    }

    @Test
    fun `已下线指令与重复项照旧剔除`() {
        val saved = listOf("lock", "lock", "notARealButton", "horn", "trunk")
        assertEquals(listOf("lock", "horn", "trunk"), ControlActions.sanitize(saved))
    }

    @Test
    fun `顺序按用户保存的顺序 不按内置顺序`() {
        val reversed = ControlActions.all.map { it.id }.reversed()
        assertEquals(reversed, ControlActions.sanitize(reversed))
    }
}
