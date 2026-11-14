package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 行程轨迹页「点击出现矩形 + 圆形遮罩」的守门测试（2026-11-08 用户反馈）。
 *
 * 现象：点开一段行程（卡片展开）时，卡片上会压出一块半透明的**矩形**色块，
 * 同时按压点扩散出一个**圆形**高亮，松手后过好几秒都不散，正好盖在地址和
 * 「耗电 1.8 kWh」上。
 *
 * 根因：Material3 的默认 `indication` 就是 ripple —— 它由两部分组成：
 * 铺满整块 shape 的 press 填充（圆角 13dp，看起来就是个矩形）+ 从按压点扩散的圆。
 * 这一页的卡片是整块自绘的纯色面板，卡片展开、勾选角标、背景变色已经足够说明
 * 「点到了」，涟漪只是叠在内容上的脏东西。
 *
 * 所以本页所有点击一律走 [com.leapcontrol.app.ui.noRippleClickable] /
 * [com.leapcontrol.app.ui.noRippleCombinedClickable]（indication = null）。
 * 谁想换回裸 `clickable`，这里会先红。
 */
class TripNoRippleTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(relative: String): String = File(projectDir, relative).readText()

    private val tripPage: String by lazy {
        read("app/src/main/java/com/leapcontrol/app/ui/TripHistoryScreen.kt")
    }

    private val components: String by lazy {
        read("app/src/main/java/com/leapcontrol/app/ui/UiComponents.kt")
    }

    /**
     * 去掉注释再断言。
     *
     * 注释里会写「不要用 `.clickable`」这类反例文案，不过滤的话测试会被自己的说明文字绊倒
     * （同类问题在 `CarModel3DDragTest` 上踩过）。
     */
    private fun codeOnly(src: String): String {
        val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(src, "")
        return noBlock.lineSequence()
            .map { line -> line.substringBefore("//") }
            .joinToString("\n")
    }

    /** 截取某个顶层函数（从 `private fun X(` 到下一个独占一行的 `}`）的源码。 */
    private fun bodyOf(code: String, marker: String): String {
        val start = code.indexOf(marker)
        assertTrue("找不到 $marker：函数被改名或挪走了", start >= 0)
        val end = code.indexOf("\n}\n", start)
        assertTrue("$marker 函数体没有正常闭合", end > start)
        return code.substring(start, end)
    }

    @Test
    fun `行程页没有带涟漪的裸 clickable`() {
        val code = codeOnly(tripPage)
        val plain = Regex("""\.clickable\s*[({]""").findAll(code).count()
        val combined = Regex("""\.combinedClickable\s*[({]""").findAll(code).count()
        assertEquals(
            "行程页还留着 $plain 处 .clickable：点击会再出现矩形 + 圆形遮罩，" +
                "请换成 Modifier.noRippleClickable",
            0,
            plain,
        )
        assertEquals(
            "行程页还留着 $combined 处 .combinedClickable：长按 / 点击都会出遮罩，" +
                "请换成 Modifier.noRippleCombinedClickable",
            0,
            combined,
        )
    }

    @Test
    fun `行程卡本身走无涟漪的组合点击`() {
        val row = bodyOf(codeOnly(tripPage), "private fun TimelineRow(")
        assertTrue(
            "行程卡（TimelineRow）必须用 noRippleCombinedClickable，否则点开展开时" +
                "整卡会先压出一块矩形色块 + 一个圆形高亮",
            row.contains(".noRippleCombinedClickable("),
        )
        assertTrue("长按进多选的手势不能丢", row.contains("onLongClick = onLongClick"))
        assertTrue(
            "圆角裁切要保留：改回方角卡片边缘会露底色",
            row.contains(".clip(RoundedCornerShape(13.dp))"),
        )
    }

    @Test
    fun `页面里确实有多处改成了无涟漪点击`() {
        val code = codeOnly(tripPage)
        val count = Regex("""\.noRippleClickable\s*[({]""").findAll(code).count()
        assertTrue(
            "行程页只找到 $count 处 noRippleClickable，页顶「怎么记 / 选择 / 排序」、" +
                "「删除这段行程」等入口应一并静音",
            count >= 8,
        )
    }

    @Test
    fun `月份胶囊也静音`() {
        val picker = codeOnly(read("app/src/main/java/com/leapcontrol/app/ui/TripMonthPicker.kt"))
        val chip = bodyOf(picker, "internal fun MonthChip(")
        assertTrue(
            "页顶的月份胶囊是行程页面积最大的可点元素，点一下同样会出矩形 + 圆形遮罩",
            chip.contains(".noRippleClickable("),
        )
    }

    @Test
    fun `无涟漪扩展的实现确实是 indication = null`() {
        val code = codeOnly(components)
        listOf("fun Modifier.noRippleClickable(", "fun Modifier.noRippleCombinedClickable(").forEach { marker ->
            val body = bodyOf(code, marker)
            assertTrue(
                "$marker 里没有 `indication = null`：涟漪没关掉，遮罩还会出现",
                body.contains("indication = null"),
            )
            assertTrue(
                "$marker 必须自带一个稳定的 MutableInteractionSource，" +
                    "否则每次重组都新建一个，按压态会抖动",
                body.contains("remember { MutableInteractionSource() }"),
            )
        }
    }
}
