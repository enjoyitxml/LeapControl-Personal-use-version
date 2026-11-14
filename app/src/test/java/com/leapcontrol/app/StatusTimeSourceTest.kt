package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「更新时间显示的是车况数据时间，不是拉取时刻」的守门测试（2026-11-09）。
 *
 * ## 用户反馈
 * 首页左上角与小组件的时间，应该写「最近一次与车机成功通信」的时间，
 * 不是用户点刷新的时间，否则会误判车辆状态。
 *
 * ## 为什么不能直接用 `lastUpdatedFlow`
 * 它记的是**我们刚发过请求**的时刻。车机休眠时网关会把旧报文原样回给 App，
 * 请求照样成功 → 时间跳成「刚刚」，界面说着「车况是新的」而实际是几小时前的。
 * 展示必须走 `statusDataAtFlow`（判定见 [StatusFreshness]）；
 * `lastUpdatedFlow` 只留给内部用途（磁贴判新鲜度、行程采样时间戳）。
 */
class StatusTimeSourceTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun file(path: String): String = File(projectDir, path).readText()

    private val runtime by lazy { file("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt") }
    private val home by lazy { file("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt") }
    private val widget by lazy { file("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt") }

    /** 从签名到同缩进的右花括号，即整个函数体。 */
    private fun bodyOf(text: String, signature: String): String {
        val at = text.indexOf(signature)
        assertTrue("找不到 $signature —— 函数被改名了，这条守门要跟着更新", at >= 0)
        val lineStart = text.lastIndexOf('\n', at) + 1
        val indent = text.substring(lineStart, at).takeWhile { it == ' ' }
        val end = text.indexOf("\n$indent}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    // ---------------- 首页 ----------------

    @Test
    fun homeShowsStatusDataTime() {
        assertTrue(
            "首页的「更新于 …」必须订阅 statusDataAtFlow",
            home.contains("val statusDataAt by runtime.statusDataAtFlow.collectAsState()"),
        )
        assertFalse(
            "首页不能再用 lastUpdatedFlow 显示时间 —— 那是「我们刚拉过」的时刻",
            home.contains("lastUpdatedFlow.collectAsState()"),
        )
        assertTrue(home.contains("\"更新于 \${formatUpdateTime(statusDataAt)}\""))
        assertTrue(home.contains("statusDataAt == 0L -> \"尚未更新\""))
    }

    @Test
    fun homeLocationCardsUseStatusDataTime() {
        // 位置卡的「X 分钟前」同样得是数据时间：熄火后位置冻在熄火点，
        // 用拉取时刻会永远写「刚刚」
        assertFalse("HomeScreen 里还有 reportedAt = lastUpdated", home.contains("reportedAt = lastUpdated"))
        assertTrue(home.contains("reportedAt = statusDataAt"))
    }

    // ---------------- 桌面小组件 ----------------

    @Test
    fun widgetShowsStatusDataTime() {
        assertTrue(widget.contains("val updatedAt = r.statusDataAtFlow.value"))
        assertFalse(
            "小组件右上角不能再用 lastUpdatedFlow —— 重画一次就会写成「刚刚」",
            widget.contains("val updatedAt = r.lastUpdatedFlow.value"),
        )
    }

    // ---------------- 运行时 ----------------

    @Test
    fun refreshAdvancesDataTime() {
        val body = bodyOf(runtime, "suspend fun refreshStatus()")
        assertTrue("刷新成功后必须推进数据时间", body.contains("applyStatusDataTime(status)"))
        // 拉取时刻仍然要记：磁贴新鲜度、行程采样都靠它，别把语义搅在一起
        assertTrue(body.contains("_lastUpdated.value = System.currentTimeMillis()"))
    }

    @Test
    fun chargeReadbackAdvancesDataTime() {
        val body = bodyOf(runtime, "private fun readChargeState(")
        assertTrue("起停充电期间的回读也要推进数据时间", body.contains("applyStatusDataTime(status)"))
    }

    @Test
    fun cachedStatusRestoresAnchor() {
        val body = bodyOf(runtime, "fun loadCachedStatus(")
        assertTrue("冷启动要恢复锚点，不能把几小时前的时间抹成「尚未更新」", body.contains("loadStatusAnchor(vin)"))
        assertTrue(body.contains("_statusDataAt.value"))
    }

    @Test
    fun everyRefreshPathGoesThroughTheRule() {
        // 三条推进路径：正常刷新、充电回读、磁盘恢复。任何一条绕过 StatusFreshness
        // 就会有一个入口把时间写成「现在」，用户照样会被骗。
        assertEquals(
            "applyStatusDataTime 只该由 refreshStatus / readChargeState 调用",
            runtime.split("applyStatusDataTime(status)").size - 1,
            2,
        )
        assertTrue(runtime.contains("StatusFreshness.next("))
    }

    @Test
    fun logoutClearsDataTime() {
        // 登出 / 会话失效后不能把上一辆车的「数据时间」留在界面和小组件上
        assertTrue(runtime.split("_statusDataAt.value = 0L").size - 1 >= 3)
        assertTrue(runtime.contains("statusAnchor = null"))
    }
}
