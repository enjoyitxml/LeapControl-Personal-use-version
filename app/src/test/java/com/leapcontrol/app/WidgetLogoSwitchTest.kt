package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面小组件左上角车标的显隐开关（2026-11-16）。
 *
 * 用户要的是：**默认不显示**，要用的人自己去个性化里打开；关掉之后车名往左顶，
 * 行首不能留一段车标让出来的空档。
 *
 * 这件事在代码里横跨三层，任何一层漏了都只会表现为「开关点了没反应」：
 *  1. `WidgetStyle`：新键 + 默认值 + 一次性迁移（老设备 prefs 里没有这个键，
 *     但若不迁移，`applyNewDefaults` 之前读到的仍是旧世界的默认）；
 *  2. `VehicleWidget`：按开关给 `wg_logo` 设可见性，**必须 `GONE`** ——
 *     `INVISIBLE` 仍占 18dp + 1dp，车名左移就落空；
 *  3. `WidgetSettings`：开关接线 + 关掉后把「车标大小」那根滑杆收起来
 *     （没有对象可调的滑杆比不显示更糟）。
 */
class WidgetLogoSwitchTest {

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

    private val styleSrc = codeOnly(read("app/src/main/java/com/leapcontrol/app/widget/WidgetStyle.kt"))
    private val widgetSrc = codeOnly(read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt"))
    private val settingsSrc = codeOnly(read("app/src/main/java/com/leapcontrol/app/ui/WidgetSettings.kt"))

    @Test
    fun `车标默认不显示 且老设备会被一次性迁移成不显示`() {
        assertTrue(
            "车标默认值不是「不显示」",
            styleSrc.contains("""getBoolean(KEY_LOGO_VISIBLE, false)"""),
        )
        assertTrue(
            "迁移里没把车标一起关掉，老设备升级后车标还在",
            styleSrc.contains("putBoolean(KEY_LOGO_VISIBLE, false)"),
        )
        assertTrue("没有 setLogoVisible 落盘", styleSrc.contains("fun setLogoVisible(context: Context, visible: Boolean)"))
    }

    /** 关掉必须用 GONE：INVISIBLE 仍占 18dp + 1dp，车名左移会停在半路。 */
    @Test
    fun `渲染层按开关设可见性 并用 GONE 让位`() {
        assertTrue(
            "渲染层没读车标开关",
            widgetSrc.contains("val logoVisible = WidgetStyle.logoVisible(context)"),
        )
        assertTrue(
            "wg_logo 没有按开关设可见性",
            widgetSrc.contains(
                "views.setViewVisibility(R.id.wg_logo, if (logoVisible) View.VISIBLE else View.GONE)",
            ),
        )
        assertTrue("车标不该出现 INVISIBLE", !widgetSrc.contains("View.INVISIBLE"))
    }

    /**
     * 车名要能真正占回车标的位置：布局里 `wg_title` 必须是 `weight=1` 的弹性宽度，
     * 前面只有 `wg_logo` 一格 —— 两者之间若多一层固定宽度的容器，GONE 也推不动车名。
     */
    @Test
    fun `六份布局里车名紧跟车标且吃掉剩余宽度`() {
        listOf(
            "widget_a.xml", "widget_a_wide.xml",
            "widget_d.xml", "widget_d_wide.xml",
            "widget_t.xml", "widget_t_wide.xml",
        ).forEach { name ->
            val xml = read("app/src/main/res/layout/$name")
            val logoAt = xml.indexOf("""@+id/wg_logo""")
            val titleAt = xml.indexOf("""@+id/wg_title""")
            assertTrue("$name 里找不到 wg_logo", logoAt >= 0)
            assertTrue("$name 里找不到 wg_title", titleAt >= 0)
            assertTrue("$name 里车名不在车标后面", titleAt > logoAt)
            val titleTag = xml.substring(titleAt, minOf(titleAt + 400, xml.length))
            assertTrue("$name 里车名不是弹性宽度，车标 GONE 后也占不回位置", titleTag.contains("""android:layout_weight="1""""))
        }
    }

    @Test
    fun `设置页有显示车标开关 并接上偏好`() {
        assertTrue("设置页没有「显示车标」这一行", settingsSrc.contains("""label = "显示车标""""))
        assertTrue("开关没接 setLogoVisible", settingsSrc.contains("WidgetStyle.setLogoVisible(context, on)"))
        assertTrue("开关状态没回读", settingsSrc.contains("mutableStateOf(WidgetStyle.logoVisible(context))"))
        // 改显隐改的是布局结构（车名要跟着移），必须全量重画
        assertTrue(
            "打开 / 关闭车标后没有整卡重画",
            settingsSrc.contains("onToggleLogo = { on ->") &&
                settingsSrc.contains("WidgetStyle.setLogoVisible(context, on)"),
        )
    }

    /** 车标关掉时那根「车标大小」滑杆要收起：拖了没反应比不显示更糟。 */
    @Test
    fun `车标隐藏时车标大小滑杆收起`() {
        val at = settingsSrc.indexOf("""label = "显示车标"""")
        val sliderAt = settingsSrc.indexOf("""label = "左上角车标大小"""")
        assertTrue("找不到车标大小滑杆", sliderAt > at)
        val between = settingsSrc.substring(at, sliderAt)
        assertTrue("车标大小滑杆没有被 logoVisible 守住", between.contains("if (logoVisible) {"))
    }

    /** 文案口径：用户点的名 —— 提示里把「零跑车标」改成「车标」。 */
    @Test
    fun `提示里不再出现零跑车标`() {
        val uiDir = File(projectDir, "app/src/main/java/com/leapcontrol/app/ui")
        val leftovers = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("零跑车标") }
            .map { it.name }
            .toList()
        assertEquals("界面文案里还写着「零跑车标」：$leftovers", emptyList<String>(), leftovers)
    }

    /** 换版式后第一帧必须走全量：增量更新打不到另一份布局上，会被桌面整帧丢掉。 */
    @Test
    fun `版式变更后强制全量重画`() {
        assertTrue("没有记住上次全量用的版式", widgetSrc.contains("private var lastFullStyle: String? = null"))
        assertTrue(
            "增量判据没把版式算进去",
            widgetSrc.contains("val usePartial = partial && lastFullNight == night && lastFullStyle == style"),
        )
        assertTrue("全量画完没记住版式", widgetSrc.contains("lastFullStyle = style"))
    }

    /** 默认版式也一起钉住：全透明 + 按键不带底 + 分隔线保留。 */
    @Test
    fun `默认外观三件套`() {
        assertTrue("默认版式不是全透明", styleSrc.contains("const val DEFAULT_STYLE = STYLE_T"))
        assertTrue("按键底默认不是关", styleSrc.contains("getBoolean(KEY_CELL_BG, false)"))
        assertTrue("分隔线默认不是开", styleSrc.contains("getBoolean(KEY_DIVIDER, true)"))
        assertFalse("素纸不该还是默认", styleSrc.contains("getString(KEY_STYLE, STYLE_A)"))
    }
}
