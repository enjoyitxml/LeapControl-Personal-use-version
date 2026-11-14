package com.leapcontrol.app

import com.leapcontrol.app.ui.CarMotionSettings
import com.leapcontrol.app.ui.carShake
import com.leapcontrol.app.ui.isReversing
import com.leapcontrol.app.ui.migrateLegacyDefaultsJson
import com.leapcontrol.app.ui.shakeImpactAmp
import com.leapcontrol.app.ui.windBaseCount
import com.leapcontrol.app.ui.windCountAt
import com.leapcontrol.app.ui.windLayerBaseCount
import com.leapcontrol.app.ui.windLayerMaxLenDp
import com.leapcontrol.app.ui.windLayerMaxWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 行驶风线的守门测试（2026-11-08 定稿：细直线 · 稀疏）。
 *
 * ## 为什么要有这一份
 *
 * 风线前后被打回两次：第一版是「长条带状气流 + 大擦痕 + 外晕模糊」，第二版是
 * 「丝缕」。用户第三次明确说：「还是用细直线来展现风的感觉吧，但是风要稀疏一点。」
 *
 * 也就是说这里守的不是「代码能跑」，而是**形状不许变回去**：
 * 条数一旦被谁调回一百多、长度一旦拉回 270px、或者又加上了 blur / 弯曲包络，
 * 都会立刻变回被否掉的那两版。这些参数写死在 `LAYERS` 里，下面逐条钉住。
 *
 * ## 倒车
 *
 * 用户提的：「倒车的时候应该没有风的特效，只保留车身抖动的特效。」
 * 判据是挡位信号 1010（[isReversing]），**必须精确匹配**，不能 contains ——
 * `UNKNOWN` / `NONE` 里都有 R。
 */
class CarMotionWindTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(relative: String): String = File(projectDir, relative).readText()

    private val fx: String by lazy { read("app/src/main/java/com/leapcontrol/app/ui/CarMotionFx.kt") }

    /** 去掉注释再断言：注释里写着「不加 blur」「不做带状包络」，直接搜会被自己误伤。 */
    private fun codeOnly(src: String): String {
        val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(src, "")
        return noBlock.lineSequence()
            .map { line -> line.substringBefore("//") }
            .joinToString("\n")
    }

    // ---------------------------------------------------------------- 形状

    @Test
    fun `基准密度是 38 条，三层 14 16 8`() {
        // 这是 density = 100% 时的条数，也就是滑杆拉满那个位置；
        // 默认 35% 实际只画 14 条（见下一条），两者别搞混。
        assertEquals("基准密度下的总条数变了：用户要的是「稀疏」", 38, windBaseCount())
        assertEquals(14, windLayerBaseCount(0))
        assertEquals(16, windLayerBaseCount(1))
        assertEquals(8, windLayerBaseCount(2))
    }

    @Test
    fun `线最长 112dp，不许回到 270px 的长条`() {
        // 上一版被否的核心就是「风的线条太长了」：远层 270px 一条盖住半个屏幕
        for (i in 0..2) {
            assertTrue(
                "第 $i 层最长 ${windLayerMaxLenDp(i)}dp 太长：细直线方案上限是 112dp",
                windLayerMaxLenDp(i) <= 112f,
            )
        }
        // 近层必须明显长于远层，否则三层没有层次
        assertTrue(windLayerMaxLenDp(2) > windLayerMaxLenDp(0) * 2f)
    }

    @Test
    fun `线宽最粗 1_65dp，是线不是带`() {
        for (i in 0..2) {
            assertTrue(
                "第 $i 层线宽 ${windLayerMaxWidthDp(i)}dp 太粗：那已经是「带」不是「线」了",
                windLayerMaxWidthDp(i) <= 1.65f,
            )
        }
    }

    @Test
    fun `密度线性换算条数`() {
        // 滑杆两端：10% 最稀（4 条）、100%（38 条）、160% 很密（61 条）；
        // 默认 35% 落在这里（14 条），不用 100% 那个老默认
        assertEquals(4, windCountAt(CarMotionSettings.MIN_WIND_DENSITY))
        assertEquals(14, windCountAt(CarMotionSettings.DEFAULT_WIND_DENSITY))
        assertEquals(23, windCountAt(0.6f))
        assertEquals(38, windCountAt(1.0f))
        assertEquals(61, windCountAt(CarMotionSettings.MAX_WIND_DENSITY))
        // 最低 10% 也得画得出东西：0 条等于把风关了（要关风请关总开关）
        assertTrue(
            "密度下限 ${CarMotionSettings.MIN_WIND_DENSITY} 一条线都没有了",
            windCountAt(CarMotionSettings.MIN_WIND_DENSITY) > 0,
        )
        // 密度变 0 条也不能出现：调用方夹紧过，这里只是兜底断言不抛
        assertTrue(windCountAt(0f) >= 0)
    }

    // ---------------------------------------------------------------- 绘制方式

    @Test
    fun `画的是直线，没有模糊也没有弯曲`() {
        val code = codeOnly(fx)
        assertFalse(
            "CarMotionFx 里又出现了 blur：外晕模糊是被用户否掉的上一版效果",
            code.contains("blur", ignoreCase = true),
        )
        assertFalse("又出现了带状包络 ribbon", code.contains("ribbon", ignoreCase = true))
        assertFalse("又出现了弯曲 bow 参数", Regex("""\bbow\b""").containsMatchIn(code))
        // 直线只能靠 drawLine 画；出现 drawPath 说明又回去画形状了
        assertFalse("风线应该是 drawLine，不该再走 drawPath", code.contains("drawPath"))
        assertTrue("找不到 drawLine：风线绘制被改掉了", code.contains("drawLine"))
    }

    @Test
    fun `两端渐隐靠渐变，不是硬切口`() {
        val code = codeOnly(fx)
        // 五段渐隐：0 与 1 必须都是全透明
        assertTrue(
            "渐变两端没有收到全透明，线会出现硬切口",
            Regex("""0f to color\.copy\(alpha = 0f\)""").containsMatchIn(code) &&
                Regex("""1f to color\.copy\(alpha = 0f\)""").containsMatchIn(code),
        )
    }

    // ---------------------------------------------------------------- 倒车

    @Test
    fun `倒车时只抖不吹风`() {
        val s = CarMotionSettings.DEFAULT
        // 前进：速度够就有风
        assertTrue(s.windVisible(60f, reversing = false))
        // 倒车：速度一样，但没有风
        assertFalse("倒车不应该有风特效", s.windVisible(60f, reversing = true))
        // 倒车且停着：也没有
        assertFalse(s.windVisible(0f, reversing = true))
        // 停着：没有
        assertFalse(s.windVisible(0f, reversing = false))
    }

    @Test
    fun `倒车仍然有车身抖动`() {
        // 风关掉不等于动效关掉：抖动是「车在动」的唯一提示
        val off = carShake(t = 0.42f, speedKmh = 8f, shakeDp = CarMotionSettings.DEFAULT_SHAKE_DP)
        assertTrue("倒车时车模还是得抖", abs(off.y) > 0f)
    }

    @Test
    fun `挡位解析：R 系算倒车`() {
        assertTrue("R".isReversing())
        assertTrue("R挡".isReversing())
        assertTrue("r 挡".isReversing())
        assertTrue("REVERSE".isReversing())
        assertTrue("倒车".isReversing())
        assertTrue("  R  ".isReversing())
    }

    @Test
    fun `挡位解析：别再拿 contains 误判`() {
        // 这几个里都含字母 R / N，contains("R") 会把它们全判成倒车，
        // 结果就是「风特效永远不出现」—— 用户会以为功能坏了。
        assertFalse("UNKNOWN".isReversing())
        assertFalse("NONE".isReversing())
        assertFalse("PARK".isReversing())
        assertFalse("D".isReversing())
        assertFalse("P".isReversing())
        assertFalse("N".isReversing())
        assertFalse("".isReversing())
        assertFalse(null.isReversing())
    }

    // ---------------------------------------------------------------- 抖动

    @Test
    fun `0_7 秒冲击不再盖过正弦本身`() {
        // 上一版冲击系数是 1.5：正弦峰值 1.0 的幅度，冲击打到 1.5，就是「一顿一顿」的来源。
        // 用户要求「抖动幅度小一点」，光降默认值只会整体等比缩小，压这个系数才变细腻。
        assertTrue("冲击系数 ${shakeImpactAmp()} 又调回去了", shakeImpactAmp() <= 1.0f)
        assertTrue("冲击系数太小就等于没有冲击了", shakeImpactAmp() >= 0.4f)
    }

    @Test
    fun `默认抖动比上一版明显更轻`() {
        assertTrue(
            "默认抖动 ${CarMotionSettings.DEFAULT_SHAKE_DP}dp 没有比上一版 2.0dp 小",
            CarMotionSettings.DEFAULT_SHAKE_DP < CarMotionSettings.LEGACY_DEFAULT_SHAKE_DP,
        )
        fun peak(dp: Float) = (0..200).maxOf { i ->
            abs(carShake(t = i * 0.013f, speedKmh = 60f, shakeDp = dp).y)
        }
        assertTrue(
            "新默认在 60km/h 的纵向峰值 ${peak(CarMotionSettings.DEFAULT_SHAKE_DP)} 看着不比旧版轻",
            peak(CarMotionSettings.DEFAULT_SHAKE_DP) < peak(CarMotionSettings.LEGACY_DEFAULT_SHAKE_DP),
        )
    }

    // ---------------------------------------------------------------- 老数据迁移

    @Test
    fun `老设备存的旧默认值会被平移成新默认值`() {
        // 上一版默认写下的就是这两项。装完新版如果原样读出来，用户看不出任何变化
        val moved = migrateLegacyDefaultsJson("""{"sh":1.2,"wa":0}""")
        val back = CarMotionSettings.fromJson(org.json.JSONObject(moved))
        assertEquals(CarMotionSettings.DEFAULT_SHAKE_DP, back.shakeDp, 0.001f)
        assertEquals(-7f, back.windAngle, 0.001f)
        // 老 JSON 里没有 wd 时走默认，也得是新默认 35%
        assertEquals(35f, back.windDensity * 100f, 0.001f)
    }

    @Test
    fun `旧默认密度 100% 也要迁到 35%`() {
        // 老设备存着 wd = 1.0（旧默认）。不迁的话用户升级后还是满屏 38 条线，
        // 「默认改成 35%」等于没做 —— 这就是改 prefs 默认值必须配迁移的那条老坑。
        val back = CarMotionSettings.fromJson(
            org.json.JSONObject(migrateLegacyDefaultsJson("""{"sh":0.2,"wa":-7,"wd":1.0}""")),
        )
        assertEquals(0.35f, back.windDensity, 0.001f)
        // 用户自己调过的密度不许动（1.3 是手动拉上去的）
        val kept = CarMotionSettings.fromJson(
            org.json.JSONObject(migrateLegacyDefaultsJson("""{"sh":0.2,"wa":-7,"wd":1.3}""")),
        )
        assertEquals(1.3f, kept.windDensity, 0.001f)
    }

    @Test
    fun `两层旧默认（2_0 和 1_2）都要迁到 0_2`() {
        // 跳过 v1.2.2 直接装新版的设备存的是 2.0；装过 v1.2.2 的存的是 1.2。
        // 只认一个，就有一半人升级后手感没变 —— 这正是"改了默认值但谁都看不到"的老坑。
        listOf(2.0, 1.2).forEach { old ->
            val back = CarMotionSettings.fromJson(
                org.json.JSONObject(migrateLegacyDefaultsJson("""{"sh":$old,"wa":-7}""")),
            )
            assertEquals("旧默认 $old 没被迁走", CarMotionSettings.DEFAULT_SHAKE_DP, back.shakeDp, 0.001f)
        }
    }

    @Test
    fun `用户自己调过的值不被迁移覆盖`() {
        val moved = migrateLegacyDefaultsJson("""{"sh":3.4,"wa":-22}""")
        val back = CarMotionSettings.fromJson(org.json.JSONObject(moved))
        assertEquals(3.4f, back.shakeDp, 0.001f)
        assertEquals(-22f, back.windAngle, 0.001f)
    }

    @Test
    fun `只迁恰好等于旧默认的那一项`() {
        // 抖过（3.0）但风向没动过（0）：只改风向，抖动留着
        val back = CarMotionSettings.fromJson(
            org.json.JSONObject(migrateLegacyDefaultsJson("""{"sh":3.0,"wa":0}""")),
        )
        assertEquals(3.0f, back.shakeDp, 0.001f)
        assertEquals(-7f, back.windAngle, 0.001f)
    }

    @Test
    fun `迁移碰到脏数据原样返回不崩`() {
        assertEquals("not json", migrateLegacyDefaultsJson("not json"))
        assertEquals("[]", migrateLegacyDefaultsJson("[]"))
        // 只存了 sh（更老的版本）：照样平移，缺失的 wa 由 fromJson 补默认 −7°
        val moved = migrateLegacyDefaultsJson("""{"sh":2.0}""")
        val back = CarMotionSettings.fromJson(org.json.JSONObject(moved))
        assertEquals(CarMotionSettings.DEFAULT_SHAKE_DP, back.shakeDp, 0.001f)
        assertEquals(-7f, back.windAngle, 0.001f)
    }

    // ---------------------------------------------------------------- 动起来

    /**
     * 2026-11-08 用户反馈：「预览的时候上面的预览窗口风不会动」。
     *
     * 根因是 Compose 的 State 只订阅**读到它的那个绘制 lambda**：帧时钟 `clock` 当时只有
     * `graphicsLayer` 在读，于是车模每帧重画、风的 Canvas 一次都不重绘 —— 画面上就是
     * 「车在抖、风钉在那里」。这条测试盯住「Canvas 自己也要读 clock」。
     */
    @Test
    fun `风的 Canvas 自己订帧，不能只靠 graphicsLayer`() {
        val code = codeOnly(fx)
        val canvas = code.substringAfter("Canvas(Modifier.fillMaxSize())", "")
        assertTrue("找不到风的 Canvas：结构被改动了", canvas.isNotEmpty())
        val clockAt = canvas.indexOf("clock.longValue")
        val drawAt = canvas.indexOf("engine.draw")
        assertTrue(
            "Canvas 的 draw 里没有读 clock.longValue：风不会重绘，预览窗口里就是静止的",
            clockAt >= 0,
        )
        assertTrue(
            "clock.longValue 要在 engine.draw 之前读，否则订阅建立得太晚",
            drawAt < 0 || clockAt < drawAt,
        )
    }

    /** 拖滑杆改倾角时，动画循环必须读到最新值，否则要退出页面重进才生效。 */
    @Test
    fun `拖滑杆改倾角时引擎读到的是最新值`() {
        val code = codeOnly(fx)
        assertTrue(
            "LaunchedEffect 只用 (animating, paused) 做 key，直接捕获 speed / windAngle 会一直用进入页面那一刻的值",
            code.contains("rememberUpdatedState"),
        )
        assertTrue(
            "engine.step 要用 liveSpeed/liveAngle 的当前值",
            code.contains("liveSpeed.value") && code.contains("liveAngle.value"),
        )
    }

    // ---------------------------------------------------------------- 总开关

    /**
     * 用户：「车辆行驶特效加一个总开关，可以关掉」。
     *
     * 守两件事：① 设置页真有这个开关，且落在 `CarMotionSettings.enabled` 上；
     * ② 关掉之后**三条滑杆一起置灰**——只关动画不禁用控件，用户会以为调了不起作用。
     */
    @Test
    fun `设置页有总开关 关掉时滑杆置灰`() {
        val screen = codeOnly(read("app/src/main/java/com/leapcontrol/app/ui/CarMotionScreen.kt"))
        assertTrue("设置页没有「启用行驶动效」开关", screen.contains("启用行驶动效"))
        assertTrue("开关没接到 CarMotionSettings.enabled", screen.contains("copy(enabled = it)"))
        assertTrue("关掉后滑杆仍可拖", screen.contains("enabled = draft.enabled"))
        assertTrue("关掉后参数区没有变淡", screen.contains("Modifier.alpha("))
        assertTrue(
            "预览车速条没跟着开关走，用户拉了看不到变化会以为预览坏了",
            screen.contains("enabled = settings.enabled"),
        )
    }

    /** 默认值那一行文字要从常量取：改默认值时手写数字漏改过一次。 */
    @Test
    fun `恢复默认的文案跟着常量走`() {
        val screen = codeOnly(read("app/src/main/java/com/leapcontrol/app/ui/CarMotionScreen.kt"))
        assertTrue(
            "恢复默认那行又手写了抖动值，改默认值时会和实际值对不上",
            screen.contains("defaultShakeText()") &&
                !Regex("""抖动\s*1\.2\s*dp""").containsMatchIn(screen),
        )
        assertTrue(
            "恢复默认那行又手写了密度，默认已经改成 35% 了，写 100% 就是在骗人",
            screen.contains("defaultDensityText()") &&
                !Regex("""密度\s*100%""").containsMatchIn(screen),
        )
    }
}
