package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 行程轨迹页顶部两枚设置胶囊（自动开始 / 电池容量）的文字居中的守门测试（2026-11-08）。
 *
 * 踩过的坑：`Text(fontSize = 9.5.sp)` 只给字号不给 `lineHeight` 时，
 * 行高会沿用 `LocalTextStyle`（Material3 bodyLarge = **24sp**），
 * 一行 24dp 的行框里只画 8~10dp 的字形。两行叠起来 51dp 塞进 48dp 的胶囊，
 * 内容溢出后被圆角裁掉、整体往下坠 —— 表现为「文字太靠下」，
 * 而且 `verticalArrangement = Center` 完全救不回来。
 *
 * 所以这两行必须各自显式写 lineHeight，别被「简化」掉。
 */
class TripPillLineHeightTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val source: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/TripHistoryScreen.kt").readText()
    }

    /** 取出 `private fun SettingPill(` 到下一个顶层 `}` 的整段函数源码。 */
    private fun settingPillBody(): String {
        val start = source.indexOf("private fun SettingPill(")
        assertTrue("找不到 SettingPill：胶囊被改名或挪走了", start >= 0)
        val end = source.indexOf("\n}\n", start)
        assertTrue("SettingPill 函数体没有正常闭合", end > start)
        return source.substring(start, end)
    }

    private fun body(): String = settingPillBody()

    @Test
    fun `胶囊里的两行文字各自显式写了 lineHeight`() {
        val f = body()
        val texts = Regex("""\bText\(""").findAll(f).count()
        val lineHeights = Regex("""lineHeight\s*=""").findAll(f).count()
        assertEquals("SettingPill 应该正好两行文字（标签 + 当前值）", 2, texts)
        assertEquals(
            "两行文字必须各写一个 lineHeight；少写就继承 bodyLarge 的 24sp 行高，" +
                "两行撑爆 48dp 胶囊、文字整体下坠（2026-11-08 用户反馈）",
            2,
            lineHeights,
        )
    }

    @Test
    fun `行高贴合字号，不再用 24sp 那种大行框`() {
        val f = body()
        assertTrue("标签行（9.5sp）的行高应压到 12sp 左右", f.contains("lineHeight = 12.sp"))
        assertTrue("数值行（12sp）的行高应压到 16sp 左右", f.contains("lineHeight = 16.sp"))
    }

    @Test
    fun `胶囊仍是 48dp 固定高，两行内容能整体居中`() {
        val f = body()
        assertTrue("胶囊高度被改了，和右边「开始记录」不再等高", f.contains(".height(48.dp)"))
        assertTrue("没做垂直居中", f.contains("verticalArrangement = Arrangement.Center"))
    }
}
