package com.leapcontrol.app

import com.leapcontrol.app.ui.CarMotionSettings
import com.leapcontrol.app.ui.carShake
import com.leapcontrol.app.ui.motionFactor
import com.leapcontrol.app.ui.parseSpeedKmh
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 行驶动效（抖动 + 风）的纯逻辑测试。
 *
 * 这一版把上一版的「三层特效 + 十几个参数」整套删掉了，能调的只剩两个值，
 * 所以测试也只守三件事：默认值合不合理、坏数据读进来别把界面搞崩、
 * 抖动在停车时必须是死的（否则首页会一直抖）。
 */
class CarMotionTest {

    @Test
    fun `默认参数在合法范围内`() {
        val d = CarMotionSettings.DEFAULT
        assertEquals(CarMotionSettings.DEFAULT_SHAKE_DP, d.shakeDp, 0.001f)
        // 默认 0.2dp（2026-11-08 用户第二次要求「抖动幅度调到 0.2dp」）。
        // 直接钉数值：这个常量被谁顺手改回 1.2 都能立刻发现。
        assertEquals(0.2f, d.shakeDp, 0.001f)
        // 默认 −7°：风略往下压，比纯水平更像顶着一股气流前进
        assertEquals(-7f, d.windAngle, 0.001f)
        // 默认密度 35%（2026-11-08 用户：「风的密度默认 35%」），14 条线
        assertEquals(0.35f, d.windDensity, 0.001f)
        // 总开关默认开：这是老行为，别让升级后动效莫名消失
        assertTrue(d.enabled)
        // 归一化之后不该有任何变化：默认值本身就是合法的
        assertEquals(d, d.normalized())
    }

    @Test
    fun `总开关关掉后一帧都不动`() {
        val off = CarMotionSettings(enabled = false)
        // 车速再高也不动、也不画风
        assertFalse(off.animatesAt(120f))
        assertFalse(off.windVisible(120f, reversing = false))
        // 开着才按车速判断
        val on = CarMotionSettings(enabled = true)
        assertTrue(on.animatesAt(120f))
        assertTrue(on.windVisible(120f, reversing = false))
    }

    @Test
    fun `总开关状态能存住 老数据缺字段按开处理`() {
        // 关掉 → 存 → 读回来还是关
        val off = CarMotionSettings(enabled = false, shakeDp = 2f)
        assertEquals(false, CarMotionSettings.fromJson(JSONObject(off.toJson().toString())).enabled)
        // 上一版存的 JSON 里没有 en 字段：不能因为缺字段就把用户的动效关掉
        assertTrue(CarMotionSettings.fromJson(JSONObject("""{"sh":2.0,"wa":-7}""")).enabled)
        // 归一化不能把开关弄丢（normalized 走 copy，只重写三个数值）
        assertFalse(CarMotionSettings(enabled = false).normalized().enabled)
    }

    @Test
    fun `抖动幅度夹紧到 0 到 5 dp`() {
        assertEquals(0f, CarMotionSettings(shakeDp = -3f).normalized().shakeDp, 0.001f)
        assertEquals(
            CarMotionSettings.MAX_SHAKE_DP,
            CarMotionSettings(shakeDp = 99f).normalized().shakeDp,
            0.001f,
        )
        // 归一化按 0.1dp 取整：滑块给的是这个精度，存进去也别留 2.0000001
        assertEquals(2.3f, CarMotionSettings(shakeDp = 2.26f).normalized().shakeDp, 0.001f)
    }

    @Test
    fun `风向夹紧到正负 45 度并取整`() {
        assertEquals(-45f, CarMotionSettings(windAngle = -80f).normalized().windAngle, 0.001f)
        assertEquals(45f, CarMotionSettings(windAngle = 80f).normalized().windAngle, 0.001f)
        assertEquals(12f, CarMotionSettings(windAngle = 11.6f).normalized().windAngle, 0.001f)
    }

    @Test
    fun `密度夹紧到 0_1 到 1_6 并按 0_05 取整`() {
        // 下限 10%（2026-11-08 用户要求）：再低就是 0 条线，等于偷偷把风关了
        assertEquals(0.1f, CarMotionSettings.MIN_WIND_DENSITY, 0.0001f)
        assertEquals(
            CarMotionSettings.MIN_WIND_DENSITY,
            CarMotionSettings(windDensity = 0.02f).normalized().windDensity,
            0.001f,
        )
        assertEquals(
            CarMotionSettings.MAX_WIND_DENSITY,
            CarMotionSettings(windDensity = 9f).normalized().windDensity,
            0.001f,
        )
        // 0.83 → 0.85；不取整的话滑杆拖动会留下 0.83000004 这种数写进 JSON
        assertEquals(0.85f, CarMotionSettings(windDensity = 0.83f).normalized().windDensity, 0.001f)
    }

    @Test
    fun `停在阈值以下一帧都不动`() {
        val s = CarMotionSettings.DEFAULT
        assertFalse(s.animatesAt(null))
        assertFalse(s.animatesAt(0f))
        assertFalse(s.animatesAt(CarMotionSettings.MIN_SPEED_KMH - 0.1f))
        assertFalse(s.animatesAt(Float.NaN))
        assertTrue(s.animatesAt(CarMotionSettings.MIN_SPEED_KMH))
        assertTrue(s.animatesAt(60f))
    }

    @Test
    fun `JSON 往返`() {
        val s = CarMotionSettings(shakeDp = 3.2f, windAngle = -18f, windDensity = 1.35f).normalized()
        val back = CarMotionSettings.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s.shakeDp, back.shakeDp, 0.001f)
        assertEquals(s.windAngle, back.windAngle, 0.001f)
        assertEquals(s.windDensity, back.windDensity, 0.001f)
    }

    @Test
    fun `坏数据退回默认值`() {
        assertEquals(CarMotionSettings.DEFAULT, CarMotionSettings.fromJson(null))
        assertEquals(CarMotionSettings.DEFAULT, CarMotionSettings.fromJson(JSONObject("{}")))
        // 上一版三层特效写下的老 JSON：字段名完全不同，应该整份被忽略
        val legacy = JSONObject("""{"on":true,"sandS":85,"gAng":30,"wAng":20}""")
        assertEquals(CarMotionSettings.DEFAULT, CarMotionSettings.fromJson(legacy))
        // 越界的值读进来要夹紧，而不是原样信
        val wild = CarMotionSettings.fromJson(JSONObject("""{"sh":99,"wa":-120,"wd":8}"""))
        assertEquals(CarMotionSettings.MAX_SHAKE_DP, wild.shakeDp, 0.001f)
        assertEquals(-CarMotionSettings.MAX_WIND_ANGLE, wild.windAngle, 0.001f)
        assertEquals(CarMotionSettings.MAX_WIND_DENSITY, wild.windDensity, 0.001f)
    }

    @Test
    fun `上一版存的 JSON 没有密度字段时用默认密度`() {
        // 老用户 prefs 里只有 sh / wa，读进来不能因为缺 wd 就退成 0 条风
        val old = CarMotionSettings.fromJson(JSONObject("""{"sh":1.5,"wa":-12}"""))
        assertEquals(1.5f, old.shakeDp, 0.001f)
        assertEquals(-12f, old.windAngle, 0.001f)
        assertEquals(CarMotionSettings.DEFAULT_WIND_DENSITY, old.windDensity, 0.001f)
    }

    @Test
    fun `车速解析`() {
        assertEquals(60f, parseSpeedKmh("60")!!, 0.001f)
        assertEquals(60.5f, parseSpeedKmh("60.5 km/h")!!, 0.001f)
        assertEquals(0f, parseSpeedKmh("0 km/h")!!, 0.001f)
        assertEquals(null, parseSpeedKmh(null))
        assertEquals(null, parseSpeedKmh(""))
        assertEquals(null, parseSpeedKmh("--"))
    }

    @Test
    fun `速度系数在 120 封顶`() {
        assertEquals(0f, motionFactor(0f), 0.001f)
        assertEquals(0.5f, motionFactor(60f), 0.001f)
        assertEquals(1f, motionFactor(120f), 0.001f)
        assertEquals(1f, motionFactor(200f), 0.001f)
    }

    @Test
    fun `停车时抖动严格为零`() {
        val off = carShake(t = 3.7f, speedKmh = 0f, shakeDp = 2f)
        assertEquals(0f, off.x, 0.0001f)
        assertEquals(0f, off.y, 0.0001f)
        assertEquals(0f, off.rotation, 0.0001f)
    }

    @Test
    fun `抖动幅度为零时不动`() {
        val off = carShake(t = 1.3f, speedKmh = 80f, shakeDp = 0f)
        assertEquals(0f, off.x, 0.0001f)
        assertEquals(0f, off.y, 0.0001f)
    }

    @Test
    fun `抖动幅度大体上随设置增长`() {
        // 同一时刻、同一车速，只改幅度：位移峰值必须跟着变大
        fun peak(dp: Float) = (0..120).maxOf { i ->
            val o = carShake(t = i * 0.017f, speedKmh = 60f, shakeDp = dp)
            abs(o.x) + abs(o.y)
        }
        val small = peak(1f)
        val big = peak(3f)
        assertTrue("3dp 应该比 1dp 抖得多：$small vs $big", big > small * 2f)
    }

    @Test
    fun `抖动峰值不会夸张到离谱`() {
        // 5dp 是滑块上限：纵向峰值不该超过 12dp（否则车模会在卡片里乱跳）
        val peak = (0..200).maxOf { i ->
            abs(carShake(t = i * 0.013f, speedKmh = 120f, shakeDp = 5f).y)
        }
        assertTrue("5dp 时纵向峰值 $peak 太大", peak < 12f)
        assertTrue("5dp 时纵向峰值 $peak 太小，等于没抖", peak > 2f)
    }

    @Test
    fun `抖动不会突然闪跳`() {
        // 5Hz 的抖动一帧本来就有 1dp 左右的位移（这是要的），这里守的是「不该出现
        // 十几 dp 的瞬移」—— 那会让车模在卡片里闪一下。
        var prev = carShake(0f, 60f, 2f)
        var worst = 0f
        for (i in 1..120) {
            val cur = carShake(i * 0.016f, 60f, 2f)
            worst = maxOf(worst, abs(cur.y - prev.y), abs(cur.x - prev.x))
            prev = cur
        }
        assertTrue("相邻帧位移差 $worst 太大", worst < 3f)
    }
}
