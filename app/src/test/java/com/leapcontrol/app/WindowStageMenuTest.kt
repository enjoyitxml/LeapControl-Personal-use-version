package com.leapcontrol.app

import com.leapcontrol.app.ui.WindowControl
import com.leapcontrol.app.ui.WindowStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页车窗「档位菜单」的守门测试（2026-11-08 建立，2026-11-12 改为恒列四档）。
 *
 * ## 用户的原话（2026-11-08）
 * 「这是官方首页的车窗控制按键，我想做成跟官方一样的操作逻辑，上方弹出菜单，
 *   我们的就不画图标了直接用文字。目前车窗有四种状态：关闭、微开、半开、全开，
 *   当处于任何一种状态之中，点击按钮弹出剩余三种状态。」
 *
 * 以及随后补充的边界：
 * 「只改零控首页的逻辑就行了，桌面小组件和快捷中心的逻辑还跟之前保持一致。」
 *
 * ## 2026-11-12 用户改了口径
 * 「如果用户开了车窗，但是没有拿到车辆的回执，车窗就会一直显示是关闭的，
 *   但是实际车窗已经打开了，但是本地认为车窗还是关闭的，所以没办法关闭，
 *   我的解决方案是点击车窗的时候，车窗四个状态都显示在上面。」
 *
 * 「只列剩余三种」的前提是档位判断一定准，而档位依赖车端回执。回执丢了，
 * 本地就把窗当成关着，「关闭」被从菜单里滤掉 —— 功能直接不可达。
 * 所以现在 **四档恒列**，任何时候都能点到「关闭」。
 *
 * 所以这里钉三件事：
 * 1. 菜单恒列四档，与当前档位无关；
 * 2. 点哪一档就发哪一档（`commandOf`，不是往前一档的 `nextCommand`）；
 * 3. 磁贴 / 小组件**仍然走四档循环**，别被顺手改掉。
 */
class WindowStageMenuTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun file(path: String): String = File(projectDir, path).readText()

    private val home: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt")
    }
    private val menu: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/ui/WindowStageMenu.kt")
    }
    private val window: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/ui/WindowControl.kt")
    }
    private val tile: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/tiles/VehicleTileService.kt")
    }
    private val widget: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt")
    }

    // ---------------- 菜单恒列四档 ----------------

    /**
     * 2026-11-12：菜单不再按当前档位过滤。
     *
     * 只要这条断言在，就没人能把「当前档位之外的几档」那套过滤加回来 ——
     * 一旦加回来，回执丢失时「关闭」又会从菜单里消失。
     */
    @Test
    fun `菜单永远四档全列 与当前档位无关`() {
        val expected = listOf(
            WindowStage.CLOSED,
            WindowStage.VENT,
            WindowStage.HALF,
            WindowStage.FULL,
        )
        assertEquals("菜单不是四档全列", expected, WindowControl.menuStages())
        // 菜单是给「不知道当前状态」准备的，含「关闭」是硬要求：
        // 少了它，本地误判成「已关闭」时用户就再也关不掉窗。
        assertTrue("菜单里没有「关闭」，回执丢失时用户将无法关窗", WindowControl.menuStages().contains(WindowStage.CLOSED))
    }

    /** 顺序固定，别因为 HashSet / Map 迭代顺序把「关闭」排到最下面。 */
    @Test
    fun `菜单顺序固定为 关 微开 半开 全开`() {
        val stages = WindowControl.menuStages()
        assertEquals("菜单项不该重复", stages.distinct(), stages)
        assertEquals(
            "菜单顺序被打乱了",
            listOf(WindowStage.CLOSED, WindowStage.VENT, WindowStage.HALF, WindowStage.FULL),
            stages,
        )
        // 「开度未知」是内部状态，不是菜单项（用户点不到「开启」这种模糊档位）
        assertFalse("OPEN_UNKNOWN 不该出现在菜单里", stages.contains(WindowStage.OPEN_UNKNOWN))
    }

    /** 防回归：过滤逻辑一旦回到源码里，上面两条会在真机上失效。 */
    @Test
    fun `源码里不许再按当前档位过滤菜单`() {
        assertFalse(
            "menuStages 又按当前档位过滤了",
            window.contains("all.filter { it != current }") ||
                window.contains("filter { it != current }"),
        )
    }

    // ---------------- 点某一档就发那一档 ----------------

    @Test
    fun `菜单项直接对应目标档位的指令`() {
        assertEquals(WindowControl.CMD_CLOSE, WindowControl.commandOf(WindowStage.CLOSED))
        assertEquals(WindowControl.CMD_VENT, WindowControl.commandOf(WindowStage.VENT))
        assertEquals(WindowControl.CMD_HALF, WindowControl.commandOf(WindowStage.HALF))
        assertEquals(WindowControl.CMD_FULL, WindowControl.commandOf(WindowStage.FULL))
    }

    /** 不能又退回「往前走一档」：那个语义和菜单对不上。 */
    @Test
    fun `点名档位与四档循环是两套映射`() {
        assertEquals(WindowControl.CMD_VENT, WindowControl.nextCommand(WindowStage.CLOSED))
        assertEquals(WindowControl.CMD_CLOSE, WindowControl.commandOf(WindowStage.CLOSED))
        assertEquals(WindowControl.CMD_FULL, WindowControl.nextCommand(WindowStage.HALF))
        assertEquals(WindowControl.CMD_HALF, WindowControl.commandOf(WindowStage.HALF))
    }

    @Test
    fun `菜单文案是纯文字的四个档位名`() {
        assertEquals("关闭", WindowControl.stageLabel(WindowStage.CLOSED))
        assertEquals("微开", WindowControl.stageLabel(WindowStage.VENT))
        assertEquals("半开", WindowControl.stageLabel(WindowStage.HALF))
        assertEquals("全开", WindowControl.stageLabel(WindowStage.FULL))
    }

    // ---------------- 首页接线 ----------------

    @Test
    fun `首页车窗按钮改成弹菜单`() {
        assertTrue("首页没有弹档位菜单", home.contains("WindowStageMenu("))
        assertTrue("点击后没有展开菜单", home.contains("windowMenuOpen = true"))
        assertTrue("菜单选中后没有按目标档位发指令", home.contains("WindowControl.commandOf("))
    }

    @Test
    fun `双击逻辑已经撤掉`() {
        assertFalse("首页还留着双击分支", home.contains("doubleTapCommand"))
        assertFalse("双击判定窗口还留着", window.contains("DOUBLE_TAP_MS"))
        assertFalse("双击槽位还留着", home.contains("WindowTapSlot"))
    }

    /** 锚点槽位必须挂在 composition 上：放进 LazyRow 的 item 会被回收，菜单定位就丢了。 */
    @Test
    fun `菜单状态挂在快捷控车列表外面`() {
        val slot = home.indexOf("remember { WindowAnchorSlot() }")
        val items = home.indexOf("items(cells)")
        assertTrue("找不到锚点槽位", slot >= 0)
        assertTrue("找不到快捷控车列表", items >= 0)
        assertTrue("锚点槽位被放进了 LazyRow 的 item 里", slot < items)
    }

    /** 滚动时每帧都会回调，写成 State 会把整行按钮重组几十次。 */
    @Test
    fun `锚点不用 State 存`() {
        assertTrue("锚点槽位没有用普通可变字段", home.contains("class WindowAnchorSlot"))
        assertTrue("锚点字段应该是普通 var", home.contains("var rect: Rect? = null"))
    }

    @Test
    fun `菜单不画图标 只用文字`() {
        assertFalse("菜单里不该出现图片", menu.contains("painterResource"))
        assertFalse("菜单里不该出现图标资源", menu.contains("R.drawable"))
        assertTrue("菜单项没写 lineHeight（多行会撑高）", menu.contains("lineHeight = 17.sp"))
    }

    /**
     * 2026-11-08 追加：「弹出矩形缩小一点，太大了，比字体稍微再大一点就可以了」+
     * 「每个按钮之间要分隔一下，现在全部连在一起了」。
     */
    @Test
    fun `菜单卡片收窄到只比档位字宽一点`() {
        assertTrue("卡片还占着 124dp", !menu.contains(".width(124.dp)"))
        assertTrue("没找到新的窄宽度", menu.contains(".width(84.dp)"))
    }

    @Test
    fun `档位之间要有分隔线`() {
        assertTrue("没有分隔线，三个档位会连成一整块", menu.contains(".height(1.dp)"))
        // 故意留一道"这是分隔线"的注释，免得以后有人当废代码删掉
        assertTrue(menu.contains("档位之间必须有分隔"))
    }

    /**
     * 2026-11-12：改成**下发就记意图**，不再等车端求证。
     *
     * 2026-11-08 那条「成功才记」的起因是菜单会过滤当前档位（没开成却记成开，那一档就从
     * 菜单里消失、没法重试）。现在菜单恒列四档，前提不存在了；反过来，只等求证会漏掉
     * 「指令发出去了但回执丢了」这一大类 —— 本地一直当成关着，按钮不亮、右上角也不提示
     * 「车窗已开启」，用户以为没开（2026-11-12 用户反馈）。
     *
     * 乐观值由 `resolve()` 收口：车况明确报「全关」时会把本地记录作废。
     */
    @Test
    fun `车窗档位下发就记 不等求证`() {
        assertTrue(
            "下发后要立刻记档位，否则回执一丢就永远显示关闭",
            home.contains("WindowControl.remember(appContext, vinKey, command)"),
        )
        assertFalse(
            "又把「求证成功才记」的闸加回来了（回执一丢就显示不出开）",
            home.contains("cellId == \"window\" && ControlOutcomeText.isSuccess(message)"),
        )
    }

    // ---------------- 用户明确要求：其它入口保持原样 ----------------

    @Test
    fun `快捷中心仍然是四档循环`() {
        assertTrue(
            "磁贴的车窗按钮被改成菜单了？它应该继续走四档循环",
            tile.contains("WindowControl.nextCommand("),
        )
    }

    @Test
    fun `桌面小组件仍然是四档循环`() {
        assertTrue(
            "小组件的车窗按钮被改成菜单了？它应该继续走四档循环",
            widget.contains("WindowControl.nextCommand("),
        )
    }
}
