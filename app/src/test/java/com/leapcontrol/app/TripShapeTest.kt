package com.leapcontrol.app

import com.leapcontrol.app.trip.TripSample
import com.leapcontrol.app.trip.TripShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行程「形状」：速度曲线与节奏摘要的纯逻辑。
 *
 * 这一组守的是 2026-11-20 用户的原始诉求 —— 旧行程条「不管跑还是不跑都长一样」，
 * 所以每个用例都要求**不同数据必须给出不同结果**，而不是只跑通不报错。
 */
class TripShapeTest {

    /** 一秒一个时间片，`sec` 是相对起点的秒数。 */
    private fun p(sec: Long, kmh: Double?, driving: Boolean = (kmh ?: 0.0) >= 1.0) =
        TripSample(at = 1_700_000_000_000L + sec * 1000L, speedKmh = kmh, driving = driving)

    // ---- 节奏统计 ----

    @Test
    fun `行驶与停留按时长分开统计`() {
        // 3 段行驶（每段 45 秒）→ 停 3 段（每段 45 秒）→ 再 2 段行驶
        val points = buildList {
            for (i in 0 until 4) add(p(i * 45L, 40.0))
            for (i in 4 until 7) add(p(i * 45L, 0.0, driving = false))
            for (i in 7 until 9) add(p(i * 45L, 50.0))
        }
        val r = TripShape.rhythm(points)
        assertNotNull(r)
        // 停住的三段共 3 × 45 秒 = 135 秒，算一次连续停留
        assertEquals(1, r!!.stopCount)
        assertEquals(135_000L, r.stoppedMs)
        assertEquals(135_000L, r.longestStopMs)
        // 其余 5 段 45 秒算行驶
        assertEquals(225_000L, r.movingMs)
        assertEquals(50.0, r.topKmh!!, 1e-9)
    }

    @Test
    fun `短于阈值的停车不算停一次`() {
        // 中间只停了一格（30 秒）：是红绿灯起步前的抖动，不是「停了一次」
        val points = listOf(p(0, 40.0), p(30, 0.0, driving = false), p(60, 40.0))
        val r = TripShape.rhythm(points)!!
        assertEquals(0, r.stopCount)
        assertEquals(0L, r.stoppedMs)
        // 抖动的那 30 秒并回行驶，两个数加起来才对得上观测时长
        assertEquals(60_000L, r.movingMs)
    }

    @Test
    fun `间隔过久的断档不参与统计`() {
        // 前两帧 45 秒，然后停了 20 分钟（暂停 / 后台被杀），再两帧
        val points = listOf(
            p(0, 40.0),
            p(45, 40.0),
            p(45 + 1_200, 0.0, driving = false),
            p(90 + 1_200, 0.0, driving = false),
        )
        val r = TripShape.rhythm(points)!!
        // 断档那 20 分钟既不算行驶也不算停留
        assertEquals(45_000L, r.movingMs)
        assertEquals(45_000L, r.stoppedMs)
        assertEquals(1, r.stopCount)
    }

    @Test
    fun `没有任何速度与行驶标记时不出统计`() {
        val empty = (0 until 5).map { TripSample(at = 1_700_000_000_000L + it * 45_000L) }
        assertNull(TripShape.rhythm(empty))
        assertNull(TripShape.rhythm(emptyList()))
    }

    @Test
    fun `全程最高速为 0 时不给最高速度`() {
        val points = (0 until 10).map { p(it * 45L, 0.0, driving = false) }
        val r = TripShape.rhythm(points)!!
        // 「最高 0」是句废话，宁可不出这一段
        assertNull(r.topKmh)
    }

    // ---- 摘要文案 ----

    @Test
    fun `摘要讲清堵了多久和跑多快`() {
        val text = TripShape.summaryText(
            TripShape.Rhythm(
                movingMs = 21 * 60_000L,
                stoppedMs = 12 * 60_000L,
                stopCount = 6,
                longestStopMs = 3 * 60_000L,
                topKmh = 78.0,
            ),
        )
        assertEquals("行驶 21 分 · 停 6 次共 12 分 · 最高 78", text)
    }

    @Test
    fun `一路没停时写明没停`() {
        val text = TripShape.summaryText(
            TripShape.Rhythm(
                movingMs = 10 * 60_000L,
                stoppedMs = 0L,
                stopCount = 0,
                longestStopMs = 0L,
                topKmh = 45.0,
            ),
        )
        assertEquals("行驶 10 分 · 中途没停 · 最高 45", text)
    }

    @Test
    fun `全程没动时不写行驶 0 分`() {
        val text = TripShape.summaryText(
            TripShape.Rhythm(
                movingMs = 0L,
                stoppedMs = 10 * 60_000L,
                stopCount = 1,
                longestStopMs = 10 * 60_000L,
                topKmh = null,
            ),
        )
        assertEquals("全程没动 · 停留 10 分", text)
    }

    @Test
    fun `超过一小时的停留写成小时`() {
        val text = TripShape.summaryText(
            TripShape.Rhythm(
                movingMs = 5 * 60_000L,
                stoppedMs = 65 * 60_000L,
                stopCount = 1,
                longestStopMs = 65 * 60_000L,
                topKmh = null,
            ),
        )
        assertEquals("行驶 5 分 · 停 1 次共 1 小时 5 分", text)
    }

    @Test
    fun `太短的行程不出摘要`() {
        assertNull(
            TripShape.summaryText(
                TripShape.Rhythm(10_000L, 0L, 0, 0L, 5.0),
            ),
        )
    }

    // ---- 速度曲线 ----

    @Test
    fun `速度点不足三个时不画曲线`() {
        assertNull(TripShape.curve(listOf(p(0, 10.0), p(45, 20.0))))
        assertNull(TripShape.curve(emptyList()))
    }

    @Test
    fun `时间跨度为零时不画曲线`() {
        assertNull(TripShape.curve(listOf(p(0, 10.0), p(0, 20.0), p(0, 30.0))))
    }

    @Test
    fun `全程零速不画曲线`() {
        // 全 0 会得到一条贴底的线，和旧灰条一样没有信息，交给采样条兜底
        assertNull(TripShape.curve((0 until 8).map { p(it * 45L, 0.0, driving = false) }))
    }

    @Test
    fun `曲线横轴铺满首尾且单调递增`() {
        val points = (0 until 20).map { p(it * 45L, if (it < 10) 20.0 else 80.0) }
        val curve = TripShape.curve(points)!!
        assertEquals(0f, curve.points.first().at, 1e-3f)
        assertEquals(1f, curve.points.last().at, 1e-3f)
        for (i in 1 until curve.points.size) {
            assertTrue(
                "第 $i 个点的横轴位置倒退：${curve.points[i - 1].at} → ${curve.points[i].at}",
                curve.points[i].at > curve.points[i - 1].at,
            )
        }
    }

    @Test
    fun `高速段在曲线上明显高于低速段`() {
        val points = (0 until 20).map { p(it * 45L, if (it < 10) 20.0 else 80.0) }
        val curve = TripShape.curve(points)!!
        val early = curve.points.take(curve.points.size / 3).map { curve.ratio(it) }.average()
        val late = curve.points.takeLast(curve.points.size / 3).map { curve.ratio(it) }.average()
        assertTrue("后半程开得快，曲线必须更高（$early vs $late）", late > early + 0.2)
    }

    @Test
    fun `纵轴有 30 的地板，低速行程不会顶格`() {
        val slow = (0 until 10).map { p(it * 45L, 12.0) }
        val curve = TripShape.curve(slow)!!
        assertEquals(12.0, curve.topKmh, 1e-9)
        assertEquals(TripShape.MIN_AXIS_KMH, curve.axisKmh, 1e-9)
        // 12 / 30 = 0.4：画在四成高度，一眼看出这趟跑不快
        assertEquals(0.4f, curve.ratio(curve.points.first()), 1e-3f)
    }

    @Test
    fun `最高速度取平滑之前的真值`() {
        // 单个尖峰（120）夹在 40 之间：平滑会把峰削平，但摘要里的数字不能跟着缩水
        val points = buildList {
            for (i in 0 until 8) add(p(i * 45L, 40.0))
            add(p(8 * 45L, 120.0))
            for (i in 9 until 17) add(p(i * 45L, 40.0))
        }
        val curve = TripShape.curve(points)!!
        assertEquals(120.0, curve.topKmh, 1e-9)
        assertTrue(
            "曲线顶格不该出现：峰被平滑削过，不应等于 120/120",
            curve.points.maxOf { it.kmh } < 120.0,
        )
    }

    @Test
    fun `曲线点数受上限约束`() {
        val dense = (0 until 300).map { p(it * 10L, 30.0 + (it % 40)) }
        val curve = TripShape.curve(dense, maxPoints = 24)!!
        assertTrue("分桶后不该超过上限：${curve.points.size}", curve.points.size <= 24)
    }
}
