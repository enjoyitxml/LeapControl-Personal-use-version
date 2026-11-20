package com.leapcontrol.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 行程二级页「进行中面板」默认展开的守门（2026-11-20）。
 *
 * 这条被来回翻过两次，所以钉在这里：
 *  - 2026-11-06：完整面板带地图有 400dp 高，展开会把历史清单挤出首屏，改成**默认收起**；
 *  - 2026-11-20：用户要求「详情面板不再默认折叠，而是默认打开，用户可以手动收起」。
 *
 * 现在整页是 LazyColumn，展开后往下滚就能看到清单，不再「被遮挡」。改回 false
 * 只需要动一个词，非常容易被顺手「优化」回去，所以用测试卡住初始值、切换入口和文案。
 */
class TripLivePanelDefaultTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val screen: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/TripHistoryScreen.kt").readText()
    }

    @Test
    fun `进行中面板默认展开`() {
        assertTrue(
            "进页面就该看到当前这趟：liveExpanded 的初始值必须是 true",
            screen.contains("var liveExpanded by rememberSaveable(live?.startAt) { mutableStateOf(true) }"),
        )
    }

    @Test
    fun `收起后返回再进来不被强行展开`() {
        assertTrue(
            "用 remember 的话返回首页再进来会被重置成展开，用户收起的选择白做",
            !screen.contains("var liveExpanded by remember { mutableStateOf("),
        )
    }

    @Test
    fun `面板可以手动收起`() {
        assertTrue(
            "展开 / 收起必须挂在面板头部那行上，否则用户收不起来",
            screen.contains("liveExpanded = !liveExpanded"),
        )
        assertTrue(
            "文案要跟着状态走，收起后不能还写「收起」",
            screen.contains("if (liveExpanded) \"收起\" else \"展开\""),
        )
    }
}
