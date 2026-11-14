package com.leapcontrol.app

import com.leapcontrol.app.widget.VehicleWidget
import com.leapcontrol.app.widget.WidgetMetrics
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面小组件「续航行」的四件（图标 · 里程 · 条 · 百分比）必须**对齐**，且任何一个都不许被裁。
 *
 * 这一行被用户反复打回过五次，每次的病灶记在这里，别再走回头路：
 * - 2026-11-08：续航和百分比一个 `13sp` 却一大一小 —— 续航在定宽槽里被压缩，百分比没人管。
 * - 2026-11-09 上午：定宽槽 46dp → 56dp，救回了「145km」的 m。
 * - 2026-11-09 傍晚：全改 `wrap_content` 后 km 长短不同 → 条的起点一前一后、条也不一样长。
 * - 2026-11-09 晚：「km 要对齐、两条要对齐、百分号也要对齐」→ `minWidth + gravity=end`。
 * - 2026-11-10 凌晨：「数字部分两端对齐」→ 拆成「数字格 + km 格」+ 两位数拉字距。
 * - 2026-11-10 早上：「两端对齐的方案效果太差了，按照零控首页的效果来，续航靠左对齐」。
 *
 * 现在的口径 = **零控首页 `MileageRow`**：里程是**一个** TextView（「195km」数字与单位连写、
 * 贴左起排），列宽 `wrap_content + minWidth`（窄 46 / 宽 52dp），条 `weight=1` 吃剩余，
 * 百分比 `wrap + minWidth + gravity=end`。于是：
 * 数字左缘成一条竖线、条的两端成两条竖线、百分号成一条竖线，字还是自然间距。
 *
 * 守这几件事：
 * 1. 续航与百分比的 `textSize` 必须相同；
 * 2. 续航 / 百分比显式 `autoSizeTextType="none"`，不许桌面/ROM 自己缩字；
 * 3. 里程列 `wrap_content + minWidth`，且数值与 [WidgetMetrics.VALUE_COLUMN_WIDTH_DP] 一致；
 * 4. 里程**不许**再拆成「数字 / km」两格，也不许再靠 `setLetterSpacing` 拉字距（用户已否）；
 * 5. 百分比 `wrap + minWidth + gravity=end`（百分号对齐靠它）；
 * 6. 两条必须 `0dp + weight=1` 且权重、左右外边距完全一致，条才会一样长；
 * 7. 里程与条之间留 5dp，别贴在一起。
 */
class WidgetNumberSizeTest {

    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val layouts: List<File> =
        File(root, "app/src/main/res/layout").listFiles()
            .orEmpty()
            .filter { it.name.startsWith("widget_") && it.name.endsWith(".xml") }
            .sortedBy { it.name }

    private val widgetSrc: String by lazy {
        File(root, "app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt").readText()
    }

    private fun blockOf(src: String, id: String): String {
        val at = src.indexOf("@+id/$id")
        assertTrue("布局里找不到 $id", at >= 0)
        val start = src.lastIndexOf("<TextView", at)
        val end = src.indexOf("/>", at) + 2
        return src.substring(start, end)
    }

    private fun textSize(block: String): String =
        Regex("android:textSize=\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: "NONE"

    /** 取标签里某个 dp 属性的数值；没有就返回 -1。 */
    private fun dpOf(tag: String, name: String): Float =
        Regex("android:$name=\"([\\d.]+)dp\"").find(tag)?.groupValues?.get(1)?.toFloat() ?: -1f

    /** 取标签的 `layout_weight`；没有就返回 -1。 */
    private fun weightOf(tag: String): Float =
        Regex("android:layout_weight=\"([\\d.]+)\"").find(tag)?.groupValues?.get(1)?.toFloat() ?: -1f

    /** 取某个 id 所在标签的开标签文本（TextView / LinearLayout / FrameLayout 都能用）。 */
    private fun tagOf(src: String, id: String): String {
        val at = src.indexOf("@+id/$id")
        assertTrue("布局里找不到 $id", at >= 0)
        val start = src.lastIndexOf('<', at)
        val end = src.indexOf('>', at)
        return src.substring(start, end)
    }

    @Test
    fun `六个版式都在`() {
        assertEquals(6, layouts.size)
    }

    @Test
    fun `续航与百分比的字号必须一模一样`() {
        layouts.forEach { f ->
            val src = f.readText()
            assertEquals(
                "${f.name}：纯电里程与百分比字号不一致，又会一大一小",
                textSize(blockOf(src, "wg_ev_pct")),
                textSize(blockOf(src, "wg_ev_value")),
            )
            assertEquals(
                "${f.name}：燃油里程与燃油百分比字号不一致",
                textSize(blockOf(src, "wg_fuel_pct")),
                textSize(blockOf(src, "wg_fuel_value")),
            )
        }
    }

    @Test
    fun `续航与百分比都显式关掉自动缩字`() {
        val ids = listOf("wg_ev_value", "wg_ev_pct", "wg_fuel_value", "wg_fuel_pct")
        layouts.forEach { f ->
            val src = f.readText()
            ids.forEach { id ->
                assertTrue(
                    "${f.name}/$id 少了 autoSizeTextType=\"none\"，桌面可能自己把字缩小",
                    blockOf(src, id).contains("android:autoSizeTextType=\"none\""),
                )
            }
        }
    }

    @Test
    fun `里程是一个文字块，贴左起排，列宽是 minWidth 而不是定宽`() {
        layouts.forEach { f ->
            val src = f.readText()
            val want = if (f.name.contains("_wide")) {
                WidgetMetrics.VALUE_COLUMN_WIDTH_WIDE_DP
            } else {
                WidgetMetrics.VALUE_COLUMN_WIDTH_DP
            }
            listOf("wg_ev_value", "wg_fuel_value").forEach { id ->
                val tag = tagOf(src, id)
                assertTrue(
                    "${f.name}/$id 必须 wrap_content —— 定宽在字号放大时会切字",
                    tag.contains("""android:layout_width="wrap_content""""),
                )
                assertTrue(
                    "${f.name}/$id 不许写死 layout_width=…dp",
                    !Regex("""android:layout_width="[\d.]+dp"""").containsMatchIn(tag),
                )
                assertEquals(
                    "${f.name}/$id 的 minWidth 必须与 WidgetMetrics 常量一致，两行起点才对齐、条才等长",
                    want,
                    dpOf(tag, "minWidth"),
                    0.01f,
                )
                assertTrue(
                    "${f.name}/$id 必须 gravity=\"start\" —— 续航整行靠左（与首页一致）",
                    tag.contains("""android:gravity="start""""),
                )
                assertTrue(
                    "${f.name}/$id 必须 maxLines=\"1\"，否则「195km」会折成两行把续航区撑高",
                    tag.contains("""android:maxLines="1""""),
                )
            }
        }
    }

    @Test
    fun `里程不许再拆成数字与 km 两格，也不许拉字距`() {
        // 2026-11-10 凌晨那版：数字占三格 + 两位数 setLetterSpacing 拉开 —— 用户判「效果太差」。
        // 现在回到首页口径：一个 TextView 写「195km」，数字与单位自然连写。
        layouts.forEach { f ->
            val src = f.readText()
            listOf("wg_ev_num", "wg_ev_unit", "wg_fuel_num", "wg_fuel_unit").forEach { gone ->
                assertTrue(
                    "${f.name} 里还有 $gone：里程又被拆成两格了（用户明确否掉的两端对齐方案）",
                    !src.contains("@+id/$gone"),
                )
            }
        }
        assertTrue(
            "里程要拼成「195km」一个字符串",
            widgetSrc.contains("private fun kmText(") && widgetSrc.contains("\"${'$'}{it}km\""),
        )
        assertTrue(
            "不许再用 setMinWidth / setMaxWidth 钉数字格 —— 列宽已由布局的 minWidth 决定",
            !widgetSrc.contains("\"setMinWidth\"") && !widgetSrc.contains("\"setMaxWidth\""),
        )
        assertTrue(
            "不许再靠 letterSpacing 拉开数字（只剩一处复位调用，把上一版留下的字距清掉）",
            Regex("\"setLetterSpacing\", 0f").findAll(widgetSrc).count() == 1 &&
                !widgetSrc.contains("spreadLetterSpacingEm"),
        )
    }

    @Test
    fun `百分比仍是右对齐加 minWidth，百分号才落在同一条竖线`() {
        layouts.forEach { f ->
            val src = f.readText()
            listOf("wg_ev_pct", "wg_fuel_pct").forEach { id ->
                val tag = tagOf(src, id)
                assertTrue(
                    "${f.name}/$id 必须是 wrap_content（写死宽度会把「100%」压扁）",
                    tag.contains("""android:layout_width="wrap_content""""),
                )
                assertTrue(
                    "${f.name}/$id 必须 gravity=\"end\"，否则短的那个百分比会左移",
                    tag.contains("""android:gravity="end""""),
                )
                assertEquals(
                    "${f.name}/$id 的百分比列要按最宽的「100%」留宽",
                    WidgetMetrics.PCT_MIN_WIDTH_DP,
                    dpOf(tag, "minWidth"),
                    0.01f,
                )
            }
        }
    }

    @Test
    fun `两行进度条必须一样长，且与里程之间留出间隙`() {
        // 条一样长 = 权重相同 + 左右外边距相同（前面的图标 / minWidth 里程列已钉住）
        layouts.forEach { f ->
            val src = f.readText()
            val ev = tagOf(src, "wg_ev_bar_box")
            val fuel = tagOf(src, "wg_fuel_bar_box")
            assertEquals(
                "${f.name}：两行的条权重不同，长度就不一样",
                weightOf(ev),
                weightOf(fuel),
                0.01f,
            )
            assertEquals(
                "${f.name}：两行的条左右外边距不同，起点或终点会错开",
                listOf(dpOf(ev, "layout_marginStart"), dpOf(ev, "layout_marginEnd")),
                listOf(dpOf(fuel, "layout_marginStart"), dpOf(fuel, "layout_marginEnd")),
            )
            // 里程列本身也带 2dp 起始外边距，两行必须一致，否则数字左缘就错了
            assertEquals(
                "${f.name}：两行里程列的起始外边距不同，数字左缘会错开",
                dpOf(tagOf(src, "wg_ev_value"), "layout_marginStart"),
                dpOf(tagOf(src, "wg_fuel_value"), "layout_marginStart"),
                0.01f,
            )
            listOf("电" to ev, "油" to fuel).forEach { (who, tag) ->
                assertTrue(
                    "${f.name}：$who 那一行的条紧贴里程（用户 2026-11-10：靠得太近了，留一点间隙）",
                    dpOf(tag, "layout_marginStart") >= 4f,
                )
            }
        }
    }

    @Test
    fun `进度条用权重吃满剩余，字号变大才能自动让位`() {
        layouts.forEach { f ->
            val src = f.readText()
            listOf("wg_ev_bar_box", "wg_fuel_bar_box").forEach { id ->
                val tag = tagOf(src, id)
                assertTrue(
                    "${f.name}/$id 必须是 0dp + weight=1，否则字号一变就把百分比顶出去",
                    tag.contains("""android:layout_width="0dp"""") &&
                        tag.contains("""android:layout_weight="1""""),
                )
            }
        }
    }

    @Test
    fun `字号一律走 dp 且续航的四处字共用同一个 numDp`() {
        val body = widgetSrc.substring(
            widgetSrc.indexOf("private fun applyTextScale"),
            widgetSrc.indexOf("/** setImageViewUri"),
        )
        // sp 会跟随系统字体再放大一次，把列宽顶爆 —— 正是这个 bug 的成因
        assertTrue("applyTextScale 里不许再用 SP", !body.contains("COMPLEX_UNIT_SP"))
        assertTrue("字号必须用 dp 落", body.contains("COMPLEX_UNIT_DIP"))
        assertTrue(
            "applyTextScale 必须接 numDp —— 字号只能在 numberSizeDp 里算一次",
            body.contains("numDp: Float"),
        )
        assertEquals(
            "续航 · 百分比（电/油各一组）必须各设一次、且共用同一个 numDp",
            4,
            Regex("size\\(R\\.id\\.wg_(?:ev|fuel)_(?:value|pct), numDp\\)").findAll(body).count(),
        )
        assertEquals(13f, VehicleWidget.NUM_SIZE_SP)
        // 实测缩字：46dp 在 MiSans 下连默认倍率都放不下「145km」（2026-11-09 用户截图）
        assertTrue("数字字号必须过实测宽度", widgetSrc.contains("WidgetMetrics.numberSizeScale"))
        assertTrue("量宽度要按 density 换算", widgetSrc.contains("measureTextDp"))
        assertEquals(1.32f, WidgetMetrics.NUM_MAX_SCALE)
    }
}
