package com.leapcontrol.app

import androidx.compose.ui.graphics.Color
import com.leapcontrol.app.ui.appColors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 首页控车按钮的标签颜色（2026-11-16）。
 *
 * ## 用户的原话
 * 「主要是控车按钮下面灰色字体看不清，处理好这部分吧」
 *
 * ## 为什么单独一档色
 * 标签是直接压在壁纸 / 场景上的，而 [com.leapcontrol.app.ui.AppColors.textSecondary]
 * 是给**实心卡片**里的次要文字用的。同一档中灰（#7A7E87）铺到湖面高光、白墙、
 * 逆光草地上只剩一层影子 —— 这不是「壁纸反色没生效」，反色只管明暗方向，
 * 不管「这层底色本身也在发光」。
 *
 * ## 钉住的规则
 * 1. 这档色不靠 alpha 稀释：半透明会让背景透上来，底越亮字越淡；
 * 2. 在反色已经判定过的那一侧里，最坏底色上仍要 ≥ 4.5:1（AA），实测要明显更高；
 * 3. 必须比卡片里的次级灰强一个量级，否则等于没改；
 * 4. 接线：QuickButton / 编辑按钮都用它，并带一层反向柔光。
 */
class HomeBackdropLabelTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun ui(name: String): String =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/$name").readText()

    // ---------------- 对比度 ----------------

    @Test
    fun `浅色档在亮壁纸上远超 AA 门槛`() {
        val c = appColors(dark = false)
        // 纯白（雪景 / 白墙）、薄纱后的亮水面、薄纱后的浅草地、中灰
        val backgrounds = listOf(
            "纯白" to Color.White,
            "白纱后的亮水面" to Color(0xFFE6EAE7),
            "白纱后的浅草地" to Color(0xFFD5DCD2),
            "中灰" to Color(0xFFBDBDBD),
        )
        backgrounds.forEach { (name, bg) ->
            val ratio = contrast(c.textOnBackdrop, bg)
            assertTrue("浅色档压在「$name」上只有 %.2f:1".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun `深色档在暗壁纸上远超 AA 门槛`() {
        val c = appColors(dark = true)
        val backgrounds = listOf(
            "纯黑" to Color.Black,
            "黑纱后的夜景" to Color(0xFF14161A),
            "黑纱后的暗草地" to Color(0xFF33383A),
        )
        backgrounds.forEach { (name, bg) ->
            val ratio = contrast(c.textOnBackdrop, bg)
            assertTrue("深色档压在「$name」上只有 %.2f:1".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun `这档色必须明显强于卡片里的次级灰`() {
        val light = appColors(dark = false)
        val dark = appColors(dark = true)
        val lightBg = Color(0xFFD5DCD2) // 薄纱后的浅草地，用户截图里就是这种底
        val darkBg = Color(0xFF33383A)

        val before = contrast(light.textSecondary, lightBg)
        val after = contrast(light.textOnBackdrop, lightBg)
        assertTrue("浅色档提升不够：%.2f → %.2f".format(before, after), after >= before * 2)

        val beforeDark = contrast(dark.textSecondary, darkBg)
        val afterDark = contrast(dark.textOnBackdrop, darkBg)
        assertTrue("深色档提升不够：%.2f → %.2f".format(beforeDark, afterDark), afterDark >= beforeDark * 1.5)
    }

    @Test
    fun `这档色是实色，不许靠 alpha 稀释`() {
        listOf(false, true).forEach { dark ->
            val c = appColors(dark)
            assertTrue("dark=$dark 的 textOnBackdrop 带了透明度：底会透上来，越亮的地方字越淡", c.textOnBackdrop.alpha == 1f)
        }
    }

    // ---------------- 接线守门 ----------------

    @Test
    fun `控车按钮标签用场景文字色，不用卡片次级灰`() {
        val button = section(ui("HomeScreen.kt"), "private fun QuickButton(", "/** 末尾的「编辑」按钮")
        assertTrue("找不到 QuickButton 定义", button.isNotBlank())
        assertTrue(
            "标签色应改为 textOnBackdrop",
            button.contains("else -> c.textOnBackdrop"),
        )
        assertFalse(
            "标签又退回卡片里的次级灰了 —— 压到壁纸上就是一层影子",
            button.contains("else -> c.textSecondary"),
        )
        assertTrue(
            "标签要带反向柔光，壁纸有花纹时单靠颜色压不住",
            button.contains("style = LocalTextStyle.current.copy(shadow = labelShadow)"),
        )
        assertTrue(
            "柔光要按内容明暗换方向",
            button.contains("backdropLabelShadow(c.isDark)"),
        )
    }

    @Test
    fun `编辑按钮的小字同理`() {
        val src = ui("HomeScreen.kt")
        val edit = section(src, "private fun EditButton(", "private fun backdropLabelShadow")
        assertTrue("找不到 EditButton 定义", edit.isNotBlank())
        assertTrue("「编辑」也是压在壁纸上的 12sp 小字，要跟控车标签同色", edit.contains("c.textOnBackdrop"))
        assertFalse("「编辑」还留着次级灰", edit.contains("color = c.textSecondary"))
    }

    @Test
    fun `柔光只给场景文字，别扩散到卡片正文`() {
        val src = ui("HomeScreen.kt")
        // 只允许一个定义 + 两处使用（控车标签、编辑按钮）
        val uses = Regex("backdropLabelShadow\\(").findAll(src).count()
        assertTrue("backdropLabelShadow 使用点变多了（$uses）：柔光扩散到实心卡片上会显脏", uses <= 3)
        assertTrue(src.contains("private fun backdropLabelShadow(dark: Boolean)"))
    }

    // ---------------- 工具 ----------------

    private fun section(src: String, start: String, end: String): String {
        val i = src.indexOf(start)
        if (i < 0) return ""
        val j = src.indexOf(end, i)
        return if (j > i) src.substring(i, j) else src.substring(i)
    }

    /** WCAG 相对亮度。 */
    private fun relativeLuminance(c: Color): Double {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
