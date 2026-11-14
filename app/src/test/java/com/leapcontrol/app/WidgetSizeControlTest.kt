package com.leapcontrol.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件的「信息区大小」是不是真的能调（2026-11-09 需求）。
 *
 * 为什么用源码断言：这项的效果全在**桌面进程里 RemoteViews 的落地方式**上，
 * 单测里既画不出卡片也调不到桌面。能钉死的是「实现路径对不对」：
 *
 * 1. 粗细只能靠 `setViewPadding` —— `setViewLayoutHeight` 是 API 31 的，
 *    minSdk 26 的机器上静默无效，一旦有人换成它，Android 8~11 的用户就再也调不动；
 * 2. 布局里条的槽位必须是 12dp。若退回 7dp，`setViewPadding` 只能把条**变细**，
 *    用户往粗拖会毫无反应（内边距算出来是 0，条已经顶满）。
 * 3. 字号与粗细**共用同一个倍率**，不许再给粗细单开设置（2026-11-09 用户要求）。
 * 4. 条的圆角要跟着默认粗细走（9dp → 4.5dp），否则 9dp 的条会顶着 7dp 时代的小圆角，
 *    看起来像被切了一刀的方块。
 */
class WidgetSizeControlTest {

    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val layouts: List<File> =
        File(root, "app/src/main/res/layout").listFiles()
            .orEmpty()
            .filter { it.name.startsWith("widget_") && it.name.endsWith(".xml") }
            .sortedBy { it.name }

    private val widgetSrc: String =
        File(root, "app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt").readText()

    private val settingsSrc: String =
        File(root, "app/src/main/java/com/leapcontrol/app/ui/WidgetSettings.kt").readText()

    private val styleSrc: String =
        File(root, "app/src/main/java/com/leapcontrol/app/widget/WidgetStyle.kt").readText()

    @Test
    fun `六份布局的进度条槽位都留到 12dp`() {
        assertEquals(6, layouts.size)
        layouts.forEach { f ->
            val src = f.readText()
            assertEquals(
                "${f.name}：进度条槽位不是 12dp，默认 9dp 就没法再调粗",
                2,
                Regex("android:layout_height=\"12dp\"").findAll(src).count(),
            )
            assertTrue(
                "${f.name}：还留着 7dp 的条，粗细会被写死",
                !src.contains("android:layout_height=\"7dp\""),
            )
        }
    }

    @Test
    fun `条的门禁 id 六份布局都齐`() {
        val want = listOf("wg_ev_bar", "wg_fuel_bar")
        layouts.forEach { f ->
            val src = f.readText()
            want.forEach { id ->
                assertTrue("${f.name} 少了 $id", src.contains("@+id/$id"))
            }
        }
    }

    @Test
    fun `粗细走 setViewPadding、由信息区倍率推导`() {
        val body = widgetSrc.substring(
            widgetSrc.indexOf("private fun applyBarThickness"),
            widgetSrc.indexOf("private fun applyImage"),
        )
        assertTrue("粗细必须用 setViewPadding 实现", body.contains("setViewPadding"))
        assertTrue("不许用 setViewLayoutHeight（API 31 才有）", !body.contains("setViewLayoutHeight"))
        // 2026-11-09：粗细不再单独设，改成 9dp × 「信息区大小」
        assertTrue("粗细要跟信息区倍率走", body.contains("WidgetStyle.infoScale"))
        assertTrue("粗细的换算统一走 WidgetMetrics", body.contains("WidgetMetrics.barThicknessDp"))
        assertTrue(
            "不许再读单独的粗细设置",
            !body.contains("WidgetStyle.barThicknessDp") && !body.contains("setBarThicknessDp"),
        )
        // 2026-11-09 打回：内边距加在 ProgressBar 自己身上，条会整体上移、跟文字不齐。
        // 只能加在定高的槽（BAR_BOX_IDS）上，条作为子视图跟着变矮。
        assertTrue("内边距必须加在槽上", body.contains("BAR_BOX_IDS"))
        val padOnBox = body.indexOf("views.setViewPadding(id, 0, pad, 0, pad)")
        val padOnBoxLoop = body.indexOf("for (id in BAR_BOX_IDS)")
        assertTrue("加内边距的循环要遍历 BAR_BOX_IDS", padOnBoxLoop >= 0 && padOnBox > padOnBoxLoop)
    }

    @Test
    fun `六份布局都有进度条的槽，条在槽内撑满`() {
        layouts.forEach { f ->
            val src = f.readText()
            listOf("wg_ev_bar_box", "wg_fuel_bar_box").forEach { box ->
                assertTrue("${f.name} 少了 $box", src.contains("@+id/$box"))
            }
            // 槽是定高 12dp 的 FrameLayout，里面那条必须 match_parent：
            // 条自己再定高，槽的内边距就只能把条压扁、压不回中心（粗细又会写死）。
            listOf("wg_ev_bar", "wg_fuel_bar").forEach { bar ->
                val at = src.indexOf("@+id/$bar\"")
                val tag = src.substring(at, minOf(at + 400, src.length))
                assertTrue(
                    "${f.name}：$bar 必须 match_parent，跟着槽的内容区变矮",
                    tag.contains("""android:layout_height="match_parent"""") &&
                        tag.contains("""android:layout_width="match_parent""""),
                )
            }
        }
    }

    @Test
    fun `条的圆角跟着 9dp 的默认粗细走`() {
        val bars = File(root, "app/src/main/res/drawable").listFiles()
            .orEmpty()
            .filter { it.name.startsWith("widget_bar_") && it.name.endsWith(".xml") }
        assertEquals("八份条（电 / 油 × 素纸 / 沉浸 / 透明亮 / 透明暗）都该在", 8, bars.size)
        bars.forEach { f ->
            val src = f.readText()
            assertTrue("${f.name}：圆角还是旧的 3.5dp，9dp 的条会像被切过", !src.contains("3.5dp"))
            assertTrue("${f.name}：圆角应为 4.5dp（9dp 的一半）", src.contains("4.5dp"))
        }
    }

    @Test
    fun `字号乘上用户调的整体大小`() {
        val body = widgetSrc.substring(
            widgetSrc.indexOf("private fun applyTextScale"),
            widgetSrc.indexOf("private fun applyBarThickness"),
        )
        assertTrue("要把用户系数乘进去", body.contains("WidgetStyle.infoScale"))
        assertTrue("组合与夹取统一走 WidgetMetrics", body.contains("WidgetMetrics.textScale"))
        // 里程 · 百分比（电 / 油各一组）共四处，且共用同一个 numDp（任何一边单独调都会一大一小）
        assertEquals(
            4,
            Regex("size\\(R\\.id\\.wg_(?:ev|fuel)_(?:value|pct), numDp\\)").findAll(body).count(),
        )
        // 倍率还要过一道「系统字体实测宽度」，否则默认倍率下就会切掉单位
        assertTrue("数字字号必须按实测宽度收", body.contains("WidgetMetrics.numberSizeScale"))
    }

    @Test
    fun `信息区仍只有一个倍率，细节滑杆单独接各自的偏好`() {
        assertTrue("少了「信息区大小」滑杆", settingsSrc.contains("\"信息区大小\""))
        assertTrue(
            "「进度条粗细」已经并进信息区大小，不该再有独立滑杆",
            !settingsSrc.contains("\"进度条粗细\""),
        )
        assertTrue("拖动要落盘", settingsSrc.contains("WidgetStyle.setInfoScale"))
        // 2026-11-13 多了「控车图标大小」「更新时间与刷新键」两根细节滑杆：
        // 它们各写各的 prefs，不许偷偷改信息区的倍率，否则拖图标会把续航一起放大。
        assertEquals(
            "信息区倍率只该由「信息区大小」写",
            1,
            Regex("""WidgetStyle\.setInfoScale""").findAll(settingsSrc).count(),
        )
        assertTrue(
            "不许再写单独的粗细设置",
            !settingsSrc.contains("setBarThicknessDp") && !styleSrc.contains("fun setBarThicknessDp"),
        )
        // 字号与粗细都是结构属性，增量更新不会碰，必须整卡重画
        assertTrue("松手后必须 render", settingsSrc.contains("onCommitSize"))
        assertTrue("要能恢复默认", settingsSrc.contains("WidgetStyle.resetSize"))
    }
}
