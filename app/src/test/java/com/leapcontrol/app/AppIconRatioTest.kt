package com.leapcontrol.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面图标里「图形占画布多大」的守门（2026-11-20）。
 *
 * 用户反馈方向盘比官方 App 图标大一圈。原因不在启动器、也不在背景，就是这张前景 PNG
 * 里的图形占比：它原本占画布的 48%（可见区内 72%），而官方 logo 那套 vector 的图形
 * 只占 35.6% 宽 / 41.3% 高（可见区内 53% × 62%）。现在取画布 40%（可见区内 60%），
 * 与官方的高度齐平，视觉体量才对得上。
 *
 * 这里把量出来的比例钉住：以后重新导出 PNG 没裁边、或换了别的底图，测试会直接红。
 *
 * 口径：108dp 画布、1dp = 4px → 432×432；启动器实际只显示中间 72dp（66.7%）。
 * 注意不能用 ImageIO —— 单元测试跑在 Android 的单测 JVM 上，`java.desktop` 不在类路径里，
 * 所以下面自带一个只认 8-bit RGBA / 非隔行的最小 PNG 解码器（我们的图标正是这个格式）。
 */
class AppIconRatioTest {

    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val fgFile = File(root, "app/src/main/res/drawable-nodpi/ic_launcher_fg_steering.png")

    // ---------- 最小 PNG 解码 ----------

    private class Rgba(val width: Int, val height: Int, private val data: ByteArray) {
        fun alpha(x: Int, y: Int): Int = data[(y * width + x) * 4 + 3].toInt() and 0xFF

        fun rgb(x: Int, y: Int): Int {
            val o = (y * width + x) * 4
            return ((data[o].toInt() and 0xFF) shl 16) or
                ((data[o + 1].toInt() and 0xFF) shl 8) or
                (data[o + 2].toInt() and 0xFF)
        }
    }

    private fun u32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun decodePng(file: File): Rgba {
        val raw = file.readBytes()
        require(raw.size > 8 && raw[1].toInt().toChar() == 'P' && raw[2].toInt().toChar() == 'N') {
            "${file.name} 不是 PNG"
        }
        var pos = 8
        var w = 0
        var h = 0
        var bitDepth = -1
        var colorType = -1
        val idat = ByteArrayOutputStream()
        while (pos + 8 <= raw.size) {
            val len = u32(raw, pos)
            val type = String(raw, pos + 4, 4, Charsets.US_ASCII)
            val body = pos + 8
            when (type) {
                "IHDR" -> {
                    w = u32(raw, body)
                    h = u32(raw, body + 4)
                    bitDepth = raw[body + 8].toInt() and 0xFF
                    colorType = raw[body + 9].toInt() and 0xFF
                    require((raw[body + 12].toInt() and 0xFF) == 0) { "不支持隔行 PNG" }
                }
                "IDAT" -> idat.write(raw, body, len)
                "IEND" -> break
            }
            pos += 12 + len
        }
        require(bitDepth == 8 && colorType == 6) { "只支持 8-bit RGBA，实际 bitDepth=$bitDepth colorType=$colorType" }
        require(w > 0 && h > 0) { "PNG 尺寸读不出来" }

        val inflated = ByteArray(w * h * 4 + h)
        val inf = Inflater()
        inf.setInput(idat.toByteArray())
        var got = 0
        while (got < inflated.size) {
            val n = inf.inflate(inflated, got, inflated.size - got)
            if (n == 0) break
            got += n
        }
        inf.end()
        require(got == inflated.size) { "IDAT 解压不完整：$got / ${inflated.size}" }

        val stride = w * 4
        val out = ByteArray(w * h * 4)
        var prev = IntArray(stride)
        var cur = IntArray(stride)
        for (y in 0 until h) {
            val base = y * (stride + 1)
            val filter = inflated[base].toInt() and 0xFF
            for (i in 0 until stride) cur[i] = inflated[base + 1 + i].toInt() and 0xFF
            for (i in 0 until stride) {
                val a = if (i >= 4) cur[i - 4] else 0
                val b = prev[i]
                val c = if (i >= 4) prev[i - 4] else 0
                val add = when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> paeth(a, b, c)
                    else -> error("未知 PNG filter $filter")
                }
                cur[i] = (cur[i] + add) and 0xFF
            }
            for (i in 0 until stride) out[y * stride + i] = cur[i].toByte()
            val swap = prev
            prev = cur
            cur = swap
        }
        return Rgba(w, h, out)
    }

    // ---------- 度量 ----------

    private data class Box(
        val canvas: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val opaque: Int,
        val nonWhite: Int,
    )

    private fun measure(img: Rgba): Box {
        var left = img.width
        var right = -1
        var top = img.height
        var bottom = -1
        var opaque = 0
        var nonWhite = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val a = img.alpha(x, y)
                if (a <= 8) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
                if (a > 250) {
                    opaque++
                    // 抗锯齿边缘会有 253/254 的过渡像素，只把明显发灰的算脏
                    val rgb = img.rgb(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val bc = rgb and 0xFF
                    if (r < 240 || g < 240 || bc < 240) nonWhite++
                }
            }
        }
        return Box(img.width, left, top, right, bottom, opaque, nonWhite)
    }

    // ---------- 断言 ----------

    @Test
    fun `方向盘图形占画布 40`() {
        val m = measure(decodePng(fgFile))
        assertEquals("前景画布必须是 108dp 的正方形（1dp = 4px）", 432, m.canvas)
        val ratio = (m.right - m.left + 1).toDouble() / m.canvas
        val actual = "%.3f".format(ratio)
        assertTrue(
            "图形宽占画布 $actual，对标官方 logo 的取值是 0.400 —— " +
                "改比例要同时改 mipmap-anydpi-v26/ic_launcher.xml 的注释和这条断言",
            ratio > 0.390 && ratio < 0.412,
        )
    }

    @Test
    fun `图形居中且是纯白`() {
        val m = measure(decodePng(fgFile))
        assertEquals("左右要居中，否则圆环会被掩膜切偏", 215.5, (m.left + m.right) / 2.0, 1.5)
        assertEquals("上下要居中", 215.5, (m.top + m.bottom) / 2.0, 1.5)
        assertEquals("前景必须是白色（单色主题图标靠它，允许抗锯齿的 253/254）", 0, m.nonWhite)
        assertTrue("前景不该是空图", m.opaque > 6000)
    }

    @Test
    fun `个性化页预览两套图标用同一个放大系数`() {
        // 两套前景占比已经接近（40% vs 官方 41.3% 高），预览再按版本分支缩放，界面上就会一大一小
        val src = File(root, "app/src/main/java/com/leapcontrol/app/ui/PersonalizationScreen.kt").readText()
        assertTrue(
            "预览里的放大不能再按 official 分支，要两套共用",
            !src.contains("if (official) {\n                        Modifier.graphicsLayer"),
        )
        assertTrue("预览放大系数丢了", src.contains("graphicsLayer(scaleX = 1.64f, scaleY = 1.64f)"))
    }

    @Test
    fun `图标 xml 的注释与实测比例对得上`() {
        val xml = File(root, "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue("注释里要写明 40% 这个取值", xml.contains("画布的 40%"))
        assertTrue("注释里要提醒跑 AppIconRatioTest", xml.contains("AppIconRatioTest"))
    }
}
