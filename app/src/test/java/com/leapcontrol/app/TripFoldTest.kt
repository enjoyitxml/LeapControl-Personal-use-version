package com.leapcontrol.app

import com.leapcontrol.app.map.RouteFold
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹「假折返」的守门测试（2026-11-13）。
 *
 * 用户反馈：1.0 km 的行程图上绕出一个大圈又绕回来，可车根本没掉头。
 * 两类来源都在 [RouteFold] 里收口：
 * 1. 采样点尖刺（GPS 甩出去一两百米又回来）→ [RouteFold.cleanSamples]；
 * 2. 导航规划自己绕的街区 → [RouteFold.pruneLoops]（只在超出表显里程预算时动手）。
 *
 * 用户的口径是「判断不出真的折返，就按没有折返画」，所以这里同时守两个方向：
 * 假的要删掉，**真的（共线的掉头、正常拐弯）一个点都不许动**。
 */
class TripFoldTest {

    // ---------------- 造点工具：按「米」摆点，阈值才是可读的 ----------------

    private val lat30 = 30.0
    private val mPerLat = 110_540.0
    private val mPerLng = 96_400.0 // 北纬 30° 附近 1 经度 ≈ 96.4 km

    /** x = 向东多少米，y = 向北多少米。 */
    private fun pt(x: Double, y: Double) = RouteFold.Pt(lat30 + y / mPerLat, 120.0 + x / mPerLng)

    private fun sample(x: Double, y: Double, atSec: Long) = RouteFold.Sample(
        lat = lat30 + y / mPerLat,
        lng = 120.0 + x / mPerLng,
        at = atSec * 1_000L,
    )

    private fun line(vararg xy: Pair<Double, Double>) = xy.map { pt(it.first, it.second) }

    private fun xy(vararg pairs: Double): List<Pair<Double, Double>> =
        pairs.toList().chunked(2).map { it[0] to it[1] }

    // ---------------- 采样点：删尖刺 ----------------

    @Test
    fun `横甩出去的尖刺点会被删掉`() {
        // 沿路走了 50 米，中间那帧 GPS 甩到路侧 250 米外
        val cleaned = RouteFold.cleanSamples(
            listOf(
                sample(0.0, 0.0, 0),
                sample(0.0, 250.0, 45),
                sample(50.0, 0.0, 90),
            ),
        )
        assertEquals("尖刺没删掉，地图上又是一个折返圈", 2, cleaned.size)
        assertEquals(0.0, cleaned.first().lat - lat30, 1e-9)
        assertTrue("末点必须保留", cleaned.last().lng > 120.0)
    }

    @Test
    fun `正常拐弯不删`() {
        // 北 300 米再东 300 米：A→C 比两条腿都长，第一眼就不是折返
        val cleaned = RouteFold.cleanSamples(
            listOf(
                sample(0.0, 0.0, 0),
                sample(0.0, 300.0, 45),
                sample(300.0, 300.0, 90),
            ),
        )
        assertEquals(3, cleaned.size)
    }

    @Test
    fun `真的掉头是共线的，一个点都不许删`() {
        // 往北开 300 米、掉头回到 150 米处：三点共线，垂距≈0
        val cleaned = RouteFold.cleanSamples(
            listOf(
                sample(0.0, 0.0, 0),
                sample(0.0, 300.0, 45),
                sample(0.0, 150.0, 90),
            ),
        )
        assertEquals("真掉头被当成尖刺删了，用户会看到自己少走了一段", 3, cleaned.size)
    }

    @Test
    fun `几十米的抖动不动它`() {
        // 腿太短：路口进出辅路本来就是这个样子
        val cleaned = RouteFold.cleanSamples(
            listOf(
                sample(0.0, 0.0, 0),
                sample(0.0, 30.0, 45),
                sample(30.0, 0.0, 90),
            ),
        )
        assertEquals(3, cleaned.size)
    }

    @Test
    fun `原地停车的重复点只留一个，首末保留`() {
        val cleaned = RouteFold.cleanSamples(
            listOf(
                sample(0.0, 0.0, 0),
                sample(1.0, 0.0, 45),
                sample(2.0, 0.0, 90),
            ),
        )
        assertEquals(2, cleaned.size)
        assertTrue("末点还在", cleaned.last().lng > 120.0 + 1.0 / mPerLng)
    }

    // ---------------- 规划线：剪自绕的环 ----------------

    /** 1 公里直线 + 中间绕一个 100×150 米的方块（600 米），这是规划器最典型的假绕行。 */
    private fun lineWithBlockLoop(): List<RouteFold.Pt> = line(
        *xy(
            0.0, 0.0,
            250.0, 0.0,
            500.0, 0.0,
            500.0, 150.0,
            600.0, 150.0,
            600.0, 0.0,
            500.0, 0.0,
            750.0, 0.0,
            1000.0, 0.0,
        ).toTypedArray(),
    )

    @Test
    fun `超出表显里程预算时把绕出去的方块剪掉`() {
        val raw = lineWithBlockLoop()
        val pruned = RouteFold.pruneLoops(raw, budgetM = 1_150.0)
        assertTrue("方块没被剪掉：画出来还是一个绕街区的大圈", pruned.size < raw.size)
        assertTrue("剪完还超预算", RouteFold.lengthM(pruned) <= 1_150.0)
        // 首末不能动，整条线还得通着
        assertEquals(raw.first(), pruned.first())
        assertEquals(raw.last(), pruned.last())
    }

    @Test
    fun `预算够的时候环要留着——车真的绕过这个街区`() {
        val raw = lineWithBlockLoop()
        val pruned = RouteFold.pruneLoops(raw, budgetM = 2_000.0)
        assertEquals("预算够还剪，等于把用户真的走过的路抹掉了", raw, pruned)
    }

    @Test
    fun `读不到表显里程时只剪一眼假的大环`() {
        val small = lineWithBlockLoop() // 600 米的环，不到 HARD_LOOP_M 的两倍但已超过
        assertTrue(RouteFold.lengthM(small) > 1_400.0)
        // 没有预算：600 米的环仍然超过 HARD_LOOP_M(400)，会剪
        val pruned = RouteFold.pruneLoops(small, budgetM = null)
        assertTrue(RouteFold.lengthM(pruned) < RouteFold.lengthM(small))

        // 一条只有 300 米绕行的线（不到 400 米门槛）不动
        val mild = line(
            *xy(
                0.0, 0.0,
                300.0, 0.0,
                300.0, 75.0,
                375.0, 75.0,
                375.0, 0.0,
                300.0, 0.0,
                600.0, 0.0,
            ).toTypedArray(),
        )
        assertEquals(RouteFold.pruneLoops(mild, budgetM = null), mild)
    }

    @Test
    fun `正常直路不会被误剪`() {
        val raw = line(*xy(0.0, 0.0, 300.0, 0.0, 600.0, 0.0, 900.0, 0.0).toTypedArray())
        assertEquals(raw, RouteFold.pruneLoops(raw, budgetM = 200.0))
    }

    // ---------------- 长度预算 ----------------

    @Test
    fun `预算按表显里程加两成再加一百米`() {
        // 表显总里程单位是 km：12 000.0 → 12 001.0 就是跑了 1 km
        assertEquals(1_300.0, RouteFold.budgetM(12_000.0, 12_001.0)!!, 0.5)
    }

    @Test
    fun `表显里程读不到或不可信时不给预算`() {
        assertNull(RouteFold.budgetM(null, 12_001.0))
        assertNull(RouteFold.budgetM(12_001.0, null))
        assertNull("里程回绕 / 换表", RouteFold.budgetM(12_001.0, 12_000.0))
        assertNull("几乎没动", RouteFold.budgetM(12_000.0, 12_000.02))
        assertNull("字段串了", RouteFold.budgetM(0.0, 5_000_000.0))
    }

    // ---------------- 接线守门 ----------------

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun source(relative: String): String =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/$relative").readText()

    @Test
    fun `规划结果必须过一遍剪环`() {
        val src = source("map/TripRoute.kt")
        assertTrue("TripRoute.build 没有剪环：规划器绕的街区会原样画给用户", src.contains("pruneLoops("))
        assertTrue("build 不再接收长度预算", src.contains("budgetM: Double? = null"))
    }

    @Test
    fun `地图两层都要先清采样点尖刺`() {
        val src = source("ui/TripMapPreview.kt")
        // 展开图 + 清单缩略图，两处都要清：漏一处就会出现「缩略图里有折返、点开没有」
        assertTrue("展开图没清尖刺", src.contains("RouteFold.cleanSamples("))
        assertTrue("没有把表显里程预算传给规划", src.contains("budgetM = budgetM"))
    }
}
