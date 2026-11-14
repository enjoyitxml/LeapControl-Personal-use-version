package com.leapcontrol.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设置」与「个性化」两页的分组结构（2026-11-13 版式整理）。
 *
 * 背景：两个页面加了十几个功能之后都成了「卡片平铺」，用户要找一个开关得从头扫到尾。
 * 这次按「这页到底管什么」重新分组：
 *
 *  - **设置 = 怎么运行**：车辆 → 控车与安全 → 快捷入口 → 数据与备份 → 地图与目的地 → 高级
 *  - **个性化 = 看起来怎样**：主题与背景 → 首页 → 零记 → 桌面小组件 → 应用图标
 *
 * 单测画不出页面，但能把「分组顺序」和「某项该在哪一页」钉死 —— 这两件事一旦被改回去，
 * 就是用户要重新学一遍入口在哪。所以这里断言的是源码里的**分组标题顺序**与**归属**。
 */
class SettingsLayoutTest {

    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val settingsSrc: String =
        File(root, "app/src/main/java/com/leapcontrol/app/ui/SettingsScreen.kt").readText()

    private val personalizationSrc: String =
        File(root, "app/src/main/java/com/leapcontrol/app/ui/PersonalizationScreen.kt").readText()

    /** 断言若干分组标题按给定顺序出现，且每个都带了一行说明。 */
    private fun assertOrder(src: String, labels: List<String>) {
        val positions = labels.map { label ->
            val needle = "SettingsSection(title = \"$label\""
            val at = src.indexOf(needle)
            assertTrue("分组「$label」不见了，或不再用 SettingsSection(title = ...) 写法", at >= 0)
            // desc 必须紧跟着写在同一个调用里，分组标题下面全靠它解释「这一组管什么」
            val tail = src.substring(at, minOf(src.length, at + 240))
            assertTrue("分组「$label」缺 desc，标题会变成孤零零一个词", tail.contains("desc = "))
            at
        }
        positions.zipWithNext().forEach { (a, b) ->
            assertTrue("分组顺序被打乱了（${labels.joinToString(" → ")}）", a < b)
        }
    }

    @Test
    fun `设置页六组按 车辆 到 高级 的顺序排列`() {
        assertOrder(
            settingsSrc,
            listOf("车辆", "控车与安全", "快捷入口", "数据与备份", "地图与目的地"),
        )
        // 高级设置是折叠卡，永远压在最后一块
        val advanced = settingsSrc.indexOf("CollapsibleSettingsCard(")
        assertTrue("高级设置不见了", advanced > 0)
        assertTrue(
            "高级设置必须排在「地图与目的地」之后（它是最后一组）",
            advanced > settingsSrc.indexOf("SettingsSection(title = \"地图与目的地\""),
        )
    }

    @Test
    fun `个性化页四组加小组件与图标按 由大到小 排列`() {
        assertOrder(
            personalizationSrc,
            listOf("主题与背景", "首页", "零记", "应用图标"),
        )
        // 桌面小组件自带分组标题（在 WidgetSettings.kt 里），位置夹在「零记」与「应用图标」之间
        val widget = personalizationSrc.indexOf("WidgetSettingsGroup()")
        assertTrue("桌面小组件不见了", widget > 0)
        assertTrue(
            "桌面小组件应排在「零记」之后、「应用图标」之前",
            widget > personalizationSrc.indexOf("SettingsSection(title = \"零记\"") &&
                widget < personalizationSrc.indexOf("SettingsSection(title = \"应用图标\""),
        )
    }

    @Test
    fun `外观相关项只在个性化里，设置页不再插手`() {
        // 换壁纸方式 2026-11-13 从「设置 → 首页外观」搬到个性化：外观只留一页，避免两处都能改。
        // 组件本身还留在 SettingsScreen.kt 里（internal，给个性化复用），所以查的是**调用点**：
        // 设置页不该再读 runtime.setQuickSwitchMode。
        assertFalse("设置页又出现了换壁纸开关", settingsSrc.contains("runtime.setQuickSwitchMode("))
        assertTrue("个性化里少了「换壁纸的方式」", personalizationSrc.contains("SettingsCard(\"换壁纸的方式\")"))
        assertTrue("个性化里没接上 QuickSwitchCards", personalizationSrc.contains("QuickSwitchCards("))
        assertTrue(
            "个性化的换壁纸开关要写回 prefs",
            personalizationSrc.contains("runtime.setQuickSwitchMode("),
        )
        // 三项与主题强相关的设置都只能在个性化
        listOf("\"主题壁纸\"", "\"相伴卡背景\"", "\"车模外观\"", "\"换壁纸的方式\"").forEach {
            assertTrue("个性化少了 $it", personalizationSrc.contains(it))
            assertFalse("设置页不该再有 $it", settingsSrc.contains("SettingsCard($it)"))
        }
    }

    @Test
    fun `数据出口收成一张卡，丢数据时只需要找一个地方`() {
        val at = settingsSrc.indexOf("SettingsCard(\"备份与找回\")")
        assertTrue("没有「备份与找回」这张卡", at >= 0)
        val end = settingsSrc.indexOf("Spacer(Modifier.height(18.dp))", at)
        val card = settingsSrc.substring(at, if (end > 0) end else settingsSrc.length)
        assertTrue("零记备份没进这张卡", card.contains("JournalBackupRows("))
        assertTrue("零记找回没进这张卡", card.contains("JournalSafetyRows("))
        assertTrue("行程备份没进这张卡", card.contains("TripBackupRows("))
        // 两套数据各自备份，卡里要用小标题分开，否则用户分不清哪个开关管哪份数据
        assertTrue("卡里少了「零记」小标题", card.contains("\"零记\""))
        assertTrue("卡里少了「行程轨迹」小标题", card.contains("\"行程轨迹\""))
    }

    @Test
    fun `分组标题与卡片标题不重复堆叠`() {
        // 同一组里不再用「车辆」这种和分组同名的卡片标题，否则一行里会出现两次同样的词
        listOf("车辆", "控车与安全", "快捷入口", "数据与备份", "地图与目的地").forEach { label ->
            assertFalse(
                "分组「$label」下面又套了一张同名卡片，标题重复",
                settingsSrc.contains("SettingsSection(title = \"$label\"") &&
                    settingsSrc.contains("SettingsCard(\"$label\")"),
            )
        }
    }

    @Test
    fun `个性化页的组件复用设置页那套，不自建第二套样式`() {
        // 两页都用 SettingsSection + SettingsCard，视觉一致；出现自建 Header 说明又分叉了
        assertTrue(personalizationSrc.contains("SettingsSection(title = "))
        assertTrue(personalizationSrc.contains("SettingsCard("))
        assertFalse(
            "个性化页不该自己画分组标题",
            personalizationSrc.contains("fun PersonalizationSection("),
        )
        assertEquals(
            "分组数量应稳定在 4 组（桌面包自带一组）",
            4,
            Regex("""SettingsSection\(title = """").findAll(personalizationSrc).count(),
        )
    }
}
