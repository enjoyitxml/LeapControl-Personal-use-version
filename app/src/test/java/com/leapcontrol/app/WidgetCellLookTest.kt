package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 小组件按键外观的两个开关：**圆角底** 与 **竖向分隔线**。
 *
 * ## 为什么单独守
 *
 * 这两样在用户眼里是「按键后面那块白底」和「中间那条竖线」，在代码里却是两个完全不同的东西：
 * 底色是按键容器的 `background`（`widget_cell_*`），分隔线是另外四个 1dp 的 ImageView。
 * 它们唯一的共同点是**都只能由 RemoteViews 按 id 改**，而 RemoteViews 有个特点：
 * 找不到 id 不会崩，只是这一次更新**悄悄失效**，卡片继续显示旧样子。
 *
 * 所以这里钉三件事：
 * 1. 六份布局都有 `wg_div1~4` —— 缺一份，那一套版式就关不掉线；
 * 2. 两个开关的默认值都是「开」—— 老用户升级后不能突然变成全裸按键；
 * 3. 两个开关各接各的 setter —— 用户明确要求「两个功能可以单独选择」，不能串成一个。
 */
class WidgetCellLookTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(path: String) = File(projectDir, path).readText()

    private fun codeOnly(src: String): String {
        val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(src, "")
        return noBlock.lineSequence()
            .map { line -> line.substringBefore("//") }
            .joinToString("\n")
    }

    private val layouts = listOf(
        "widget_a.xml", "widget_a_wide.xml",
        "widget_d.xml", "widget_d_wide.xml",
        "widget_t.xml", "widget_t_wide.xml",
    )

    /**
     * 六份布局都要有四个分隔线 id。
     *
     * A / D 那四份原先没写 id（只有全透明版为了跟着控件色换线色才加了），
     * 于是「关掉分隔线」当时只能对一套版式生效 —— 用户要的是三套都能关。
     */
    @Test
    fun `六份布局都有四个分隔线 id`() {
        layouts.forEach { name ->
            val xml = read("app/src/main/res/layout/$name")
            (1..4).forEach { n ->
                val hits = Regex("""@\+id/wg_div$n\b""").findAll(xml).count()
                assertEquals("$name 里 wg_div$n 出现 $hits 次，应该正好 1 次", 1, hits)
            }
        }
    }

    /**
     * 两个开关的默认值：**按键底默认关、分隔线默认开**（2026-11-16 用户定稿）。
     *
     * 默认版式改成全透明之后，按键再压一层玻璃底就跟壁纸糊在一起了；
     * 分隔线留着 —— 那是全透明版式里唯一还看得出「这是五个按键」的边界。
     *
     * 老设备 prefs 里存着旧默认的 `cell_bg=true`，光改常量它读不到新默认，
     * 所以必须有 [WidgetStyle.applyNewDefaults] 那次一次性迁移；迁移之后完全听用户的。
     */
    @Test
    fun `按键底默认关、分隔线默认开`() {
        val src = codeOnly(read("app/src/main/java/com/leapcontrol/app/widget/WidgetStyle.kt"))
        assertTrue(
            "按键底色开关的默认值不是 false",
            src.contains("""getBoolean(KEY_CELL_BG, false)"""),
        )
        assertTrue(
            "分隔线开关的默认值不是 true",
            src.contains("""getBoolean(KEY_DIVIDER, true)"""),
        )
        assertTrue(
            "没有一次性迁移：老设备会一直停在被写死的旧默认上",
            src.contains("applyNewDefaults") && src.contains("KEY_DEFAULTS_V2"),
        )
    }

    /** 关掉底色时退回「只有水波」的格子，而不是直接不设背景（那样按下去毫无反馈）。 */
    @Test
    fun `关掉按键底色后仍然保留点按水波`() {
        val src = codeOnly(read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt"))
        assertTrue(
            "cellBackgroundRes 没读按键底色开关",
            src.contains("if (!WidgetStyle.cellBgVisible(context)) return plainCellRes(context)"),
        )
        // plainCellRes 借的是素纸那两份「无底色 + 只有 ripple」的格子，
        // 换成透明色会把水波一起弄没
        assertTrue(
            "无底色格子没有退回带水波的 drawable",
            src.contains("R.drawable.widget_cell_a_dark else R.drawable.widget_cell_a"),
        )
        assertTrue("找不到 plainCellRes 的实现", src.contains("private fun plainCellRes(context: Context): Int"))
    }

    /**
     * 分隔线关掉时必须是 GONE。
     *
     * INVISIBLE 仍然占 1dp 宽 —— 五个按键各有 weight，行里留下四道空缝，
     * 看着像「线没擦干净」，正是用户截图里会挑的毛病。
     */
    @Test
    fun `关掉分隔线用 GONE 不用 INVISIBLE`() {
        val src = codeOnly(read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt"))
        assertTrue(
            "分隔线没有按开关设可见性",
            src.contains("views.setViewVisibility(id, if (dividers) View.VISIBLE else View.GONE)"),
        )
        assertTrue("不该出现 INVISIBLE：它仍占 1dp，会留下四道空缝", !src.contains("View.INVISIBLE"))
        assertTrue(
            "分隔线 id 列表不全",
            src.contains("R.id.wg_div1, R.id.wg_div2, R.id.wg_div3, R.id.wg_div4"),
        )
    }

    /** 每个开关各接各的 setter —— 用户要的是「单独选择」，共用一个就等于只有一个开关。 */
    @Test
    fun `设置页各开关分别绑定各自的偏好`() {
        val src = codeOnly(read("app/src/main/java/com/leapcontrol/app/ui/WidgetSettings.kt"))
        assertTrue("没有「按键背景」这一行", src.contains("""label = "按键背景""""))
        assertTrue("没有「分隔线」这一行", src.contains("""label = "分隔线""""))
        assertTrue("没有「显示车标」这一行", src.contains("""label = "显示车标""""))
        assertTrue(
            "按键背景开关没接 setCellBgVisible",
            src.contains("WidgetStyle.setCellBgVisible(context, on)"),
        )
        assertTrue(
            "分隔线开关没接 setDividersVisible",
            src.contains("WidgetStyle.setDividersVisible(context, on)"),
        )
        assertTrue(
            "车标开关没接 setLogoVisible",
            src.contains("WidgetStyle.setLogoVisible(context, on)"),
        )
        // 改完必须全量重画：这三样都在 RemoteViews 的「静态部分」里，增量更新碰不到
        val toggles = Regex("""onToggle\w+ = \{ on ->""").findAll(src).count()
        assertEquals("三处开关回调应该各写一处", 3, toggles)
    }
}
