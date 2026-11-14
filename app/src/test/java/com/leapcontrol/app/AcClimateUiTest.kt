package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 空调页「07 冷暖分区 + 网络图标 + 后视镜加热常显」的守门测试（2026-11-07）。
 *
 * 这一轮只重排版式与图标来源，**功能链路一行都没动**，所以这里钉住三件容易在
 * 后续迭代里被悄悄改回去的事：渐变只在顶部那张卡、图标不许退回 Canvas 手绘、
 * 后视镜加热不许再被能力门禁藏起来。
 */
class AcClimateUiTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun ui(name: String): String =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/$name.kt").readText()

    private val ac: String by lazy { ui("AcScreen") }
    private val glyphs: String by lazy { ui("AcGlyphs") }

    @Test
    fun `采用 07 冷暖分区：只有顶部状态卡带渐变，下面是中性卡`() {
        assertTrue("顶部状态卡没用冷→暖渐变", ac.contains("Brush.linearGradient(listOf(cool, middle, warm))"))
        assertTrue("渐变没按深浅色分档", ac.contains("if (C.isDark) 0.13f"))
        assertTrue("没把车内温度做成渐变卡的主角", ac.contains("$" + "indoorTemp℃"))
        assertTrue("设定温度没压成横向温度带", ac.contains("AcSection(padding = 12.dp)"))
        assertTrue("舒适区没换成 2×2 四宫格", ac.contains("AcComfortTile("))
        // 07 不接天气、也没有常驻除雾提示
        assertFalse("又挂回常驻除雾提示了", ac.contains("前风挡易起雾"))
        assertEquals("整页只能有一处渐变", 1, ac.split("Brush.linearGradient(").size - 1)
    }

    @Test
    fun `功能图标整套来自网络抓取的矢量资源，不许退回手绘`() {
        assertFalse("AcGlyphs 又出现手绘 Canvas", glyphs.contains("Canvas("))
        assertTrue("没用 drawable 资源画图", glyphs.contains("painterResource(glyph.iconRes)"))
        assertTrue("没标明 MingCute 授权", glyphs.contains("Apache-2.0"))
        assertTrue("没写抓取来源", glyphs.contains("https://api.iconify.design/mingcute/"))
        assertFalse("又给每个调用点传线宽了", glyphs.contains("stroke: Dp"))

        val dir = File(projectDir, "app/src/main/res/drawable")
        val refs = Regex("""R\.drawable\.(ic_ac_\w+)""").findAll(glyphs)
            .map { it.groupValues[1] }.toSet()
        assertEquals("空调页图标数量不对", 15, refs.size)
        refs.forEach { name ->
            // 后视镜加热是**唯一**的位图：用户 2026-11-08 给的 PNG（后视镜 + 三道热浪），
            // 觉得 MingCute 的 mirror-line（镜面里一条竖线）不够直白。位图不算「退回手绘」，
            // 但仍然必须是多密度资源、透明底，才能被 AcIcon 的 tint 正常染色。
            if (name == "ic_ac_mirror_heat") {
                listOf("drawable-xxhdpi", "drawable-xxxhdpi").forEach { density ->
                    val png = File(projectDir, "app/src/main/res/$density/$name.png")
                    assertTrue("$name 的 $density 位图不见了", png.isFile)
                }
                return@forEach
            }
            val file = File(dir, "$name.xml")
            assertTrue("$name 资源不见了", file.isFile)
            val text = file.readText()
            assertTrue("$name 不是 VectorDrawable", text.contains("<vector"))
            assertTrue("$name 缺 pathData", text.contains("android:pathData="))
            assertTrue("$name 没写原图 URL", text.contains("https://api.iconify.design/mingcute/"))
            assertTrue("$name 没写授权", text.contains("Apache-2.0"))
        }
    }

    @Test
    fun `后视镜加热常显，cmdid 440 指令链路不动`() {
        assertFalse("又被 caps.mirrorHeating 藏起来了", ac.contains("caps.mirrorHeating"))
        assertTrue("丢了开指令", ac.contains("rearviewMirrorHeating_on"))
        assertTrue("丢了关指令", ac.contains("rearviewMirrorHeating_off"))
        assertTrue("舒适 2×2 里没有后视镜加热这一格", ac.contains("title = \"后视镜加热\""))
        assertFalse("又把舒适区折起来了", ac.contains("showExtendedControls"))
        assertTrue("运行状态清单仍要跟着显示", ac.contains("if (mirrorLevel > 0) add(\"后视镜加热\")"))
    }
}
