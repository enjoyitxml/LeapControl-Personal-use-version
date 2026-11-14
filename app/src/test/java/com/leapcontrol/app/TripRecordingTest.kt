package com.leapcontrol.app

import com.leapcontrol.app.trip.TripCloseResult
import com.leapcontrol.app.trip.TripOverview
import com.leapcontrol.app.trip.TripPauseResult
import com.leapcontrol.app.trip.TripResumeResult
import com.leapcontrol.app.trip.TripRules
import com.leapcontrol.app.trip.TripSample
import com.leapcontrol.app.trip.TripStartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动记录 → 采点 → 收尾/暂停 的守门用例。
 *
 * 用户 2026-11-12 定的规则：
 *  - **只有手动开段**：点「开始记录」那一刻就是出发，自动记录方案整个删除；
 *  - 结束仍有三路兜底：手动点「结束行程」/ 驻车连续 5 分钟 / 锁车或下电；
 *  - 驻车收尾的结束时间取**刚开始驻车那一刻**，5 分钟确认期不计入时长；
 *  - 暂停期间一帧都不采，里程、时长、能耗都不涨；
 *  - 结算口径：里程 = 分段首尾表显里程差之和，能耗 = 云端累计电量差（SOC 兜底）。
 */
class TripRecordingTest {

    private val t0 = 1_700_000_000_000L

    private fun point(
        at: Long,
        driving: Boolean = false,
        odo: Double? = null,
        soc: Double? = null,
        lat: Double? = 39.90,
        lng: Double? = 116.40,
        locked: Boolean? = null,
        shutDown: Boolean = false,
    ) = TripSample(
        at = at,
        latitude = lat,
        longitude = lng,
        speedKmh = if (driving) 40.0 else 0.0,
        odometerKm = odo,
        driving = driving,
        socPercent = soc,
        locked = locked,
        shutDown = shutDown,
    )

    /** 用户点了「开始记录」：t0 那一帧就是起点（也是出发时间）。 */
    private fun started(
        odo: Double = 100.0,
        soc: Double? = null,
        cloudKwh: Double? = null,
    ): TripOverview = TripRules.start(
        TripOverview("VIN"),
        point(t0, driving = true, odo = odo, soc = soc),
        cloudKwh,
    ).first

    // ---------------------------------------------------------------- 开段

    @Test
    fun `点开始才开段 之后每一帧都进来`() {
        var state = TripOverview("VIN")
        assertTrue("没点开始之前不会自己开段", state.open.isEmpty())

        val (opened, result) = TripRules.start(state, point(t0, driving = true, odo = 100.0))
        assertEquals(TripStartResult.STARTED, result)
        assertEquals(t0, opened.open.single().at)

        state = TripRules.ingest(opened, point(t0 + 60_000, driving = true, odo = 103.0))
        state = TripRules.ingest(state, point(t0 + 120_000, driving = true, odo = 106.0))
        assertEquals(listOf(t0, t0 + 60_000, t0 + 120_000), state.open.map { it.at })
    }

    @Test
    fun `挡位或信号缺失不会被当成行驶`() {
        // UNKNOWN / NONE / PARK 都含 R 或 N，用 contains 匹配会一打开 App 就开段
        assertFalse(TripRules.isDriving(0.0, "UNKNOWN"))
        assertFalse(TripRules.isDriving(0.0, "NONE"))
        assertFalse(TripRules.isDriving(0.0, "PARK"))
        assertFalse(TripRules.isDriving(2.9, "P"))
        assertTrue(TripRules.isDriving(TripRules.MIN_DRIVING_KMH, "P"))
        assertTrue(TripRules.isDriving(0.0, "D"))
    }

    // ---------------------------------------------------------------- 自动收尾（兜底）

    @Test
    fun `驻车要连续 5 分钟才收尾 结束时间回拨到刚开始驻车那一刻`() {
        var state = TripRules.ingest(started(), point(t0 + 120_000, driving = true, odo = 105.0))
        val parkedAt = t0 + 180_000
        state = TripRules.ingest(state, point(parkedAt, odo = 105.0))
        assertEquals("第一帧驻车就是宽限期起点", parkedAt, state.pausedSince)
        assertTrue("刚停下不能立刻收尾", state.trips.isEmpty())

        // 差 1 分钟：仍然在宽限期里
        state = TripRules.ingest(
            state,
            point(parkedAt + TripRules.PAUSE_GRACE_MS - 60_000, odo = 105.0),
        )
        assertTrue(state.trips.isEmpty())

        // 满 5 分钟才收尾
        state = TripRules.ingest(state, point(parkedAt + TripRules.PAUSE_GRACE_MS, odo = 105.0))
        assertEquals(1, state.trips.size)
        val trip = state.trips.single()
        assertEquals(t0, trip.startAt)
        assertEquals("结束时间取驻车那一刻，不含 5 分钟确认期", parkedAt, trip.endAt)
        assertEquals(3 * 60_000L, trip.durationMs)
        assertEquals(5.0, trip.distanceKm, 0.001)
        assertFalse(trip.distanceEstimated)
        assertTrue(state.open.isEmpty())
    }

    @Test
    fun `宽限期内又开起来 仍是同一段`() {
        var state = TripRules.ingest(started(), point(t0 + 60_000, driving = true, odo = 102.0))
        state = TripRules.ingest(state, point(t0 + 120_000, odo = 102.0))
        assertEquals(t0 + 120_000, state.pausedSince)

        state = TripRules.ingest(state, point(t0 + 180_000, driving = true, odo = 103.0))
        assertNull(state.pausedSince)
        assertTrue(state.trips.isEmpty())
    }

    @Test
    fun `驻车后锁车当场收尾 结束时间同样回拨`() {
        var state = started()
        val parkedAt = t0 + 120_000
        state = TripRules.ingest(state, point(parkedAt, odo = 108.0))
        // 车主锁门走了：比等 5 分钟更硬的「这趟完了」
        state = TripRules.ingest(state, point(parkedAt + 90_000, odo = 108.0, locked = true))
        assertEquals(1, state.trips.size)
        assertEquals(parkedAt, state.trips.single().endAt)
        assertEquals(8.0, state.trips.single().distanceKm, 0.001)
    }

    @Test
    fun `行驶中自动落锁不能把行程砍断`() {
        var state = started()
        state = TripRules.ingest(state, point(t0 + 60_000, driving = true, odo = 101.0, locked = true))
        state = TripRules.ingest(state, point(t0 + 120_000, driving = true, odo = 102.0, locked = true))
        assertTrue(state.trips.isEmpty())
        assertEquals(3, state.open.size)
    }

    @Test
    fun `整车下电且没在行驶 当场收尾`() {
        var state = started()
        val lastMoveAt = t0 + 90_000
        state = TripRules.ingest(state, point(lastMoveAt, driving = true, odo = 104.0))
        state = TripRules.ingest(state, point(lastMoveAt + 30_000, odo = 104.0, shutDown = true))
        assertEquals(1, state.trips.size)
        // 中间没采到驻车帧，最后一帧还是行驶帧且只差 30 秒：用下电这一刻当结束时间
        assertEquals(lastMoveAt + 30_000, state.trips.single().endAt)
        assertEquals(4.0, state.trips.single().distanceKm, 0.001)
    }

    // ---------------------------------------------------------------- 手动暂停

    @Test
    fun `暂停后一帧都不采`() {
        var state = TripRules.ingest(started(), point(t0 + 60_000, driving = true, odo = 103.0))

        val (paused, result) = TripRules.pause(state, t0 + 120_000)
        assertEquals(TripPauseResult.PAUSED, result)
        assertEquals(1, paused.segments.size)
        assertTrue(paused.open.isEmpty())

        // 暂停期间车跑了 9 km，一帧都不该进来
        val after = TripRules.ingest(paused, point(t0 + 180_000, driving = true, odo = 112.0))
        assertTrue(after.open.isEmpty())
        assertEquals(1, after.segments.size)
        assertEquals(103.0, after.segments.single().last().odometerKm!!, 0.001)
    }

    @Test
    fun `恢复后按段结算 暂停期的里程和时长都不算`() {
        var state = TripRules.ingest(started(), point(t0 + 60_000, driving = true, odo = 103.0))

        state = TripRules.pause(state, t0 + 120_000).first
        // 暂停这两分钟跑了 103 → 112（9 km），不该算进这一趟
        state = TripRules.ingest(state, point(t0 + 180_000, driving = true, odo = 112.0))

        val (resumed, resumeResult) =
            TripRules.resume(state, point(t0 + 240_000, driving = true, odo = 112.0))
        assertEquals(TripResumeResult.RESUMED, resumeResult)
        assertEquals(2 * 60_000L, resumed.pausedTotalMs)

        val running = TripRules.ingest(resumed, point(t0 + 300_000, driving = true, odo = 118.0))
        val (closed, closeResult) = TripRules.closeOpen(running, t0 + 300_000)
        assertEquals(TripCloseResult.SAVED, closeResult)

        val trip = closed.trips.single()
        // 第一段 100→103（3 km）+ 第二段 112→118（6 km），暂停期间的 103→112 不算
        assertEquals(9.0, trip.distanceKm, 0.001)
        // 实际走了 3 分钟（0~2 分钟 + 4~5 分钟），暂停的 2 分钟不涨
        assertEquals(3 * 60_000L, trip.durationMs)
        // 9 km ÷ 3 分钟 = 180 km/h（数据是造的，只为验证「时长扣了暂停」这一个点）
        assertEquals(180.0, trip.averageKmh!!, 0.001)
    }

    @Test
    fun `暂停中再点开始等于恢复 不会重复计暂停`() {
        var state = TripRules.pause(started(), t0 + 60_000).first

        val (resumed, result) =
            TripRules.start(state, point(t0 + 180_000, driving = true, odo = 100.0))
        assertEquals(TripStartResult.STARTED, result)
        assertNull(resumed.manualPausedAt)
        assertEquals(2 * 60_000L, resumed.pausedTotalMs)
        assertEquals(1, resumed.open.size)
    }

    @Test
    fun `没在记时不能暂停 也没暂停时不能恢复`() {
        assertEquals(TripPauseResult.NO_TRIP, TripRules.pause(TripOverview("VIN"), t0).second)
        assertEquals(
            TripResumeResult.NOT_PAUSED,
            TripRules.resume(TripOverview("VIN"), point(t0)).second,
        )
    }

    @Test
    fun `暂停中停手 六分钟后仍然停着 不会自己收尾`() {
        var state = TripRules.pause(started(), t0 + 60_000).first
        state = TripRules.ingest(state, point(t0 + 30 * 60_000, odo = 100.0))
        assertTrue("用户按了暂停就是让他停着，别自己结账", state.trips.isEmpty())
        assertTrue(state.open.isEmpty())
        assertEquals(t0 + 60_000, state.manualPausedAt)
    }

    // ---------------------------------------------------------------- 能耗口径

    @Test
    fun `能耗优先用云端累计电量差`() {
        var state = started(odo = 100.0, soc = 80.0, cloudKwh = 40.0)
            .copy(batteryKwh = 60.0)
        state = TripRules.ingest(state, point(t0 + 120_000, driving = true, odo = 110.0, soc = 75.0))
        val parkedAt = t0 + 180_000
        state = TripRules.ingest(state, point(parkedAt, odo = 110.0, soc = 75.0))
        state = TripRules.ingest(
            state,
            point(parkedAt + TripRules.PAUSE_GRACE_MS, odo = 110.0, soc = 75.0),
            cloudKwh = 43.5,
        )

        val energy = state.trips.single().energy!!
        assertEquals("cloud", energy.source)
        assertEquals(3.5, energy.kwh!!, 0.001)
        assertEquals(35.0, energy.per100Km!!, 0.001)
        // 电量差照记，但度数以云端为准（SOC 量化误差不进来）
        assertEquals(5.0, energy.usedPercent!!, 0.001)
    }

    @Test
    fun `拿不到云端就退回首末 SOC 差`() {
        var state = started(odo = 100.0, soc = 80.0).copy(batteryKwh = 60.0)
        state = TripRules.ingest(state, point(t0 + 120_000, driving = true, odo = 110.0, soc = 75.0))
        val parkedAt = t0 + 180_000
        state = TripRules.ingest(state, point(parkedAt, odo = 110.0, soc = 75.0))
        state = TripRules.ingest(state, point(parkedAt + TripRules.PAUSE_GRACE_MS, odo = 110.0, soc = 75.0))

        val energy = state.trips.single().energy!!
        assertEquals("soc", energy.source)
        assertEquals(3.0, energy.kwh!!, 0.001)
    }

    @Test
    fun `云端差值为负 说明中途充过电 退回首末 SOC 差`() {
        var state = started(odo = 100.0, soc = 80.0, cloudKwh = 40.0)
            .copy(batteryKwh = 60.0)
        state = TripRules.ingest(state, point(t0 + 120_000, driving = true, odo = 110.0, soc = 75.0))
        val parkedAt = t0 + 180_000
        state = TripRules.ingest(state, point(parkedAt, odo = 110.0, soc = 75.0))
        state = TripRules.ingest(
            state,
            point(parkedAt + TripRules.PAUSE_GRACE_MS, odo = 110.0, soc = 75.0),
            cloudKwh = 39.0,
        )

        val energy = state.trips.single().energy!!
        assertEquals("soc", energy.source)
        assertEquals(3.0, energy.kwh!!, 0.001)
    }

    @Test
    fun `改电池容量不会重算云端口径的度数`() {
        var state = started(odo = 100.0, soc = 80.0, cloudKwh = 40.0)
            .copy(batteryKwh = 60.0)
        state = TripRules.ingest(state, point(t0 + 120_000, driving = true, odo = 110.0, soc = 75.0))
        val parkedAt = t0 + 180_000
        state = TripRules.ingest(state, point(parkedAt, odo = 110.0, soc = 75.0))
        state = TripRules.ingest(
            state,
            point(parkedAt + TripRules.PAUSE_GRACE_MS, odo = 110.0, soc = 75.0),
            cloudKwh = 43.5,
        )

        val repriced = TripRules.reprice(state.copy(batteryKwh = 80.0))
        assertEquals(3.5, repriced.trips.single().energy!!.kwh!!, 0.001)
    }

    // ---------------------------------------------------------------- 点数上限

    /**
     * 后台 45 秒采样让长途很容易超过 240 点。旧的 `takeLast` 会把用户点「开始记录」
     * 那一帧扔掉 —— 出发时间、起点坐标、首帧表显里程全丢，里程整段少算。
     * 现在超限是抽稀：首尾必须留着。
     */
    @Test
    fun `点太多时抽稀 但必须保留出发那一帧`() {
        val points = (0 until 600).map { i ->
            point(t0 + i * 45_000L, driving = true, odo = 100.0 + i * 0.1)
        }
        val trimmed = TripRules.trimOpen(points)
        assertEquals(TripRules.MAX_OPEN_POINTS, trimmed.size)
        assertEquals("首帧必须保留：出发时间 / 起点 / 首帧里程都靠它", t0, trimmed.first().at)
        assertEquals(points.last().at, trimmed.last().at)
        assertTrue("抽稀后顺序不能乱", trimmed.zipWithNext().all { (a, b) -> a.at < b.at })
    }

    @Test
    fun `不超过上限时原样返回`() {
        val points = (0 until 10).map { point(t0 + it * 45_000L) }
        assertEquals(points, TripRules.trimOpen(points))
    }

    // ---------------------------------------------------------------- 存档

    @Test
    fun `暂停状态能存档往返 且不再写记录方式`() {
        var state = TripRules.ingest(started(), point(t0 + 60_000, driving = true, odo = 103.0))
        state = TripRules.pause(state, t0 + 60_000).first

        val raw = state.toJson()
        assertFalse("自动/手动已经取消，不该再写这个键", raw.has("mode"))
        val back = TripOverview.fromJson("VIN", raw)
        assertEquals(t0 + 60_000, back.manualPausedAt)
        assertEquals(1, back.segments.size)
        assertNotNull(back.segments.single().first().odometerKm)
    }

    @Test
    fun `暂停时长不写进 JSON 时读成 0 不扣时长`() {
        val json = org.json.JSONObject()
            .put("id", t0)
            .put("start", t0)
            .put("end", t0 + 60_000)
            .put("km", 1.0)
            .put("est", false)
            .put("points", org.json.JSONArray())
        val trip = com.leapcontrol.app.trip.TripRecord.fromJson(json)!!
        assertEquals(60_000L, trip.durationMs)
        assertEquals(0L, trip.pausedMs)
    }
}
