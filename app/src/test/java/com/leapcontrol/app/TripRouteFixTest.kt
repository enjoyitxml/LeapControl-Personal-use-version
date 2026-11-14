package com.leapcontrol.app

import com.leapcontrol.app.trip.TripFixPoint
import com.leapcontrol.app.trip.TripRecord
import com.leapcontrol.app.trip.TripRouteFix
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手工修正路线的落盘：JSON 往返、坏数据容错、老数据兼容。 */
class TripRouteFixTest {

    private fun p(lat: Double, lng: Double) = TripFixPoint(lat, lng)

    @Test
    fun `JSON 往返不丢点`() {
        val points = listOf(p(28.680123, 115.850456), p(28.681, 115.851), p(28.682, 115.852))
        val back = TripRouteFix.fromJson(TripRouteFix.toJson(points))
        assertEquals(points, back)
    }

    @Test
    fun `老数据没有 fix 字段读成 null 继续用自动路线`() {
        assertNull(TripRouteFix.fromJson(null))
        val record = TripRecord(
            id = 1L,
            startAt = 1_000L,
            endAt = 2_000L,
            points = emptyList(),
            distanceKm = 3.2,
            distanceEstimated = false,
        )
        assertFalse("老数据不该凭空多出 fix", record.toJson().has("fix"))
        assertNull(TripRecord.fromJson(record.toJson())!!.routeFix)
    }

    @Test
    fun `修正线随 TripRecord 一起存下来 采样点一个都不动`() {
        val fix = listOf(p(28.68, 115.85), p(28.69, 115.86))
        val record = TripRecord(
            id = 1L,
            startAt = 1_000L,
            endAt = 2_000L,
            points = emptyList(),
            distanceKm = 3.2,
            distanceEstimated = false,
            routeFix = fix,
        )
        val json = record.toJson()
        assertTrue(json.has("fix"))
        val back = TripRecord.fromJson(json)!!
        assertEquals(fix, back.routeFix)
        assertEquals(record.points, back.points)
        assertEquals(record.distanceKm, back.distanceKm, 0.0)
        assertEquals(record.endAt, back.endAt)
    }

    @Test
    fun `坏点被跳过 少于两个有效点返回 null`() {
        val raw = JSONArray().apply {
            put(JSONArray().apply { put(28.68); put(115.85) })
            put(JSONArray().apply { put("oops"); put("bad") })
            put(JSONArray().apply { put(999.0); put(115.86) }) // 纬度越界
            put("not a pair")
        }
        assertNull(TripRouteFix.fromJson(raw))
    }

    @Test
    fun `坐标压到 6 位小数 别把 trips json 撑爆`() {
        val clean = TripRouteFix.normalize(listOf(p(28.680123456789, 115.850123456789), p(28.69, 115.86)))!!
        assertEquals(28.680123, clean[0].lat, 0.0)
        assertEquals(115.850123, clean[0].lng, 0.0)
    }

    @Test
    fun `超长修正线等距抽稀 首尾仍在`() {
        val long = List(3000) { i -> p(28.68 + i * 0.00001, 115.85) }
        val clean = TripRouteFix.normalize(long)!!
        assertEquals(TripRouteFix.MAX_POINTS, clean.size)
        assertEquals(long.first().lat, clean.first().lat, 1e-9)
        assertEquals(long.last().lat, clean.last().lat, 1e-9)
    }

    @Test
    fun `非法输入不落盘`() {
        assertNull(TripRouteFix.normalize(null))
        assertNull(TripRouteFix.normalize(emptyList()))
        assertNull(TripRouteFix.normalize(listOf(p(28.68, 115.85))))
        assertNull(TripRouteFix.normalize(listOf(p(Double.NaN, 115.85), p(28.69, 115.86))))
    }
}
