package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页「保险续费 / 保养提醒」两张卡底座的守门测试。
 *
 * 2026-11-07 用户反馈：首页这两张卡圆角比周围卡片大一圈、还多了一圈描边，
 * 因为数据同源的它们直接套了零记那套 `JournalCard`（20dp + 1dp 发丝描边）。
 * 零记页仍然要用那一套，所以这里钉住「按页面分两套底座」，别再一刀切。
 */
class ReminderCardStyleTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun ui(name: String) =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/$name.kt").readText()

    @Test
    fun `首页其它整行卡确实是 8dp 圆角且没有描边`() {
        // 提醒卡要贴的参照物；哪天首页统一换圆角，这三处和 HOME_CARD_RADIUS 要一起改
        listOf("EnergyCards", "AcQuickCard", "OwnershipCard").forEach { name ->
            val source = ui(name)
            assertTrue("$name 的圆角不再是 8dp，提醒卡也要跟着改", source.contains("RoundedCornerShape(8.dp)"))
            assertFalse("$name 加了描边，首页卡片语言变了", source.contains(".border("))
        }
    }

    @Test
    fun `共用底座支持首页的 8dp 与零宽描边，零记默认不变`() {
        val common = ui("JournalCommon")
        assertTrue("缺少首页圆角常量", common.contains("HOME_CARD_RADIUS = 8.dp"))
        assertTrue("零记默认圆角被改了", common.contains("cornerRadius: Dp = 20.dp"))
        assertTrue("缺少描边宽度开关", common.contains("borderWidth: Dp = 1.dp"))
        assertTrue(
            "零宽描边没有真的跳过绘制",
            common.contains("if (borderWidth > 0.dp) Modifier.border(borderWidth, C.border, shape) else Modifier"),
        )
        assertTrue("卡片底色仍是 panelBg", common.contains(".background(C.panelBg, shape)"))
    }

    @Test
    fun `保险保养两种状态都走同一份按页面切换的底座`() {
        val reminders = ui("JournalReminders")
        assertEquals(
            "空卡与有数据的卡必须共用一份底座（1 处定义 + 2 处调用）",
            3,
            reminders.split("ReminderCard(").size - 1,
        )
        assertTrue("首页没切到共用圆角", reminders.contains("if (homeStyle) HOME_CARD_RADIUS else 20.dp"))
        assertTrue("首页没去掉描边", reminders.contains("if (homeStyle) 0.dp else 1.dp"))
        assertTrue("空卡没有透传 homeStyle", reminders.contains("homeStyle = homeStyle"))
        assertTrue("保险卡缺少首页开关", reminders.contains("fun InsuranceCard("))
        assertTrue("保养卡缺少首页开关", reminders.contains("fun MaintenanceCard("))
    }

    @Test
    fun `只有零控首页开首页样式，零记页保持原样`() {
        val home = ui("HomeScreen")
        assertEquals(
            "首页保险 / 保养两张卡都要开首页样式",
            2,
            home.split("homeStyle = true").size - 1,
        )
        val journal = ui("JournalScreen")
        assertFalse(
            "零记页被误切成首页样式了：那两页的其它卡片仍是 20dp + 描边",
            journal.contains("homeStyle"),
        )
    }
}
