package com.leapcontrol.app.widget

import com.leapcontrol.app.tiles.QuickTiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 小组件「8 秒意图窗口」两条规则的守门（2026-11-11）。
 *
 * 背景：点一下只发一条指令、8 秒内再点同一颗键不该发反方向。窗口内车况还没反映执行结果，
 * 用它判方向会把「开」翻成「关」，用它上色会把刚点亮的键打回「灭」——
 * 两者叠加的表现就是用户说的「后备箱按钮按了没效果」。
 *
 * 这里钉三件事：窗口边界（纯函数）、重复点击必须被拦（源码）、高亮必须跟意图（源码）。
 * 源码断言沿用 [com.leapcontrol.app.WidgetDispatchTest] 的做法：这类问题三层各自自洽，
 * 只有把接线钉死才能在改动时立刻红。
 */
class WidgetTapRulesTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    /** 去掉纯注释行：注释里也会出现 `|| command == "trunkOpen"` 这种反例说明，不能算命中。 */
    private fun codeOf(path: String): String =
        File(projectDir, path).readText().lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    private val providerCode = codeOf("app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt")
    private val widgetCode = codeOf("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt")

    @Test
    fun `从没下发过不算在窗口内`() {
        assertFalse(WidgetTapRules.withinIntentWindow(0L, 1_000_000L))
    }

    @Test
    fun `刚点过就在窗口内`() {
        val at = 1_000_000L
        assertTrue(WidgetTapRules.withinIntentWindow(at, at))
        assertTrue(WidgetTapRules.withinIntentWindow(at, at + 1))
        assertTrue(WidgetTapRules.withinIntentWindow(at, at + QuickTiles.ACTION_GRACE_MS - 1))
    }

    @Test
    fun `窗口右边界算窗口外`() {
        val at = 1_000_000L
        assertFalse(WidgetTapRules.withinIntentWindow(at, at + QuickTiles.ACTION_GRACE_MS))
        assertFalse(WidgetTapRules.withinIntentWindow(at, at + 60_000L))
    }

    @Test
    fun `时钟回拨不放行`() {
        val at = 1_000_000L
        assertFalse(WidgetTapRules.withinIntentWindow(at, at - 1))
        assertFalse(WidgetTapRules.withinIntentWindow(at, 0L))
    }

    @Test
    fun `开后备箱不再被强制点两下`() {
        assertFalse(
            "确认门禁只许看设置开关：「|| command == \"trunkOpen\"」这类兜底会越过用户 2026-11-04 定的口径",
            Regex("""\|\|\s*command\s*==\s*"trunkOpen"""").containsMatchIn(providerCode),
        )
    }

    @Test
    fun `窗口内重复点同一个开关键会被拦下`() {
        assertTrue(
            "execute 开头必须调 WidgetTapRules.withinIntentWindow，否则 8 秒内连点会发出反方向指令",
            providerCode.contains("WidgetTapRules.withinIntentWindow"),
        )
    }

    @Test
    fun `窗口内按键高亮跟本地意图`() {
        assertTrue(
            "normallyOn 必须调 WidgetTapRules.withinIntentWindow，否则控车后 2~3 秒重画会把键打回灭",
            widgetCode.contains("WidgetTapRules.withinIntentWindow"),
        )
    }
}
