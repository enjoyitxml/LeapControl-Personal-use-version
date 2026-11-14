package com.leapcontrol.app

import com.leapcontrol.app.map.RouteSnap
import com.leapcontrol.app.map.TripTileMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「点几个途经点按导航补齐」的纯逻辑与接线守门。
 *
 * 用户原话：「如果手画不好用可以用户自己选择采样点位置，然后根据导航路线补齐」
 * + 「删掉涂抹的方案，只保留选点的方案」。
 *
 * 这里刻意**不真发网络请求**：高德接口有没有开通、有没有额度，取决于用户自己填的 Key，
 * JVM 单测里既没有也不该有。能守的是分段不越上限、无 Key / 非法输入时降级得说人话，
 * 以及最要紧的一条口径 —— 只走驾车路径规划，不再有轨迹纠偏（涂抹）分支。
 */
class RouteSnapTest {

    private fun p(lat: Double, lng: Double) = TripTileMap.Point(lat, lng)

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(relative: String): String = File(projectDir, relative).readText()

    // ---------------- 分段（高德途经点上限 16） ----------------

    @Test
    fun `分段共享端点 每段途经点都不超上限`() {
        val max = 16
        val segs = RouteSnap.segments(19, max)
        assertEquals(2, segs.size)
        assertEquals(0..17, segs[0])
        assertEquals(17..18, segs[1])
        for (n in listOf(2, 3, 17, 18, 19, 40, 100)) {
            val parts = RouteSnap.segments(n, max)
            assertEquals("必须从第一个点开始", 0, parts.first().first)
            assertEquals("必须盖到最后一个点", n - 1, parts.last().last)
            for (r in parts) {
                assertTrue("单段途经点超限", r.last - r.first - 1 <= max)
            }
            for (i in 1 until parts.size) {
                assertEquals("相邻段必须共享端点，否则拼起来会断线", parts[i - 1].last, parts[i].first)
            }
        }
    }

    @Test
    fun `点数不足或上限非法时不分段`() {
        assertTrue(RouteSnap.segments(0).isEmpty())
        assertTrue(RouteSnap.segments(1).isEmpty())
        assertTrue(RouteSnap.segments(5, -1).isEmpty())
    }

    // ---------------- 降级 ----------------

    @Test
    fun `没 Key 时给一句原因 不抛异常也不发请求`() {
        val points = listOf(p(28.6810, 115.8500), p(28.6820, 115.8500))
        val res = RouteSnap.build("", points)
        assertFalse(res.ok)
        assertNull(res.points)
        assertTrue("要给降级说明", res.reason!!.contains("高德"))
        val blank = RouteSnap.build("   ", points)
        assertFalse(blank.ok)
        assertTrue(blank.reason!!.contains("高德"))
    }

    @Test
    fun `途经点超上限直接拒绝 不静默丢掉用户亲手点的点`() {
        val points = List(25) { i -> p(28.68 + i * 0.0001, 115.85) }
        val res = RouteSnap.build("fake-key", points)
        assertFalse(res.ok)
        assertNull(res.points)
        assertTrue(res.reason!!.contains("最多"))
    }

    @Test
    fun `非法点与不足两点的输入直接拒绝`() {
        assertFalse(RouteSnap.build("k", emptyList()).ok)
        assertFalse(RouteSnap.build("k", listOf(p(28.68, 115.85))).ok)
        val bad = RouteSnap.build("k", listOf(p(Double.NaN, 115.85), p(28.68, 115.85)))
        assertFalse(bad.ok)
        assertNull(bad.points)
    }

    @Test
    fun `Result 至少两个点才算成功`() {
        assertFalse(RouteSnap.Result(points = listOf(p(28.68, 115.85))).ok)
        assertTrue(RouteSnap.Result(points = listOf(p(28.68, 115.85), p(28.69, 115.85))).ok)
    }

    // ---------------- 接线口径 ----------------

    @Test
    fun `只走驾车路径规划 涂抹纠偏分支已删除`() {
        val source = read("app/src/main/java/com/leapcontrol/app/map/RouteSnap.kt")
        assertTrue("没走驾车路径规划", source.contains("AmapWebApi.drivingRoute("))
        assertFalse("不许再有轨迹纠偏", source.contains("graspRoad"))
        assertFalse("不许再有模式枚举", source.contains("enum class Mode"))
        assertFalse("不许再提改用涂抹", source.contains("改用涂抹"))
        assertTrue("点数上限要跟高德对齐", source.contains("AmapWebApi.MAX_WAYPOINTS + 2"))
    }

    @Test
    fun `卡片层用 Key 建补齐器 全屏层只拿无参回调`() {
        val preview = read("app/src/main/java/com/leapcontrol/app/ui/TripMapPreview.kt")
        val viewer = read("app/src/main/java/com/leapcontrol/app/ui/TripMapViewer.kt")
        assertTrue("没用 Key 建补齐器", preview.contains("RouteSnap.build(amapKey, segment)"))
        assertTrue("没把回调注入全屏层", preview.contains("onSnap = snap"))
        assertFalse("全屏层不该碰 Key", viewer.contains("amapKey"))
        assertFalse("全屏层不该自己规划整条线", viewer.contains("TripRoute"))
    }

    @Test
    fun `补齐结果首尾由 TrackFix 强制换回接缝点`() {
        val fix = read("app/src/main/java/com/leapcontrol/app/map/TrackFix.kt")
        assertTrue("compose 没把首尾钉回原线", fix.contains("withEndpoints(middle, line[plan.startIndex], line[plan.endIndex])"))
        assertTrue("没有只替换区间的拼接", fix.contains("out.addAll(line.subList(0, plan.startIndex))"))
        assertFalse("涂抹的纯逻辑没删干净", fix.contains("fun apply(") || fix.contains("fun plan("))
    }
}
