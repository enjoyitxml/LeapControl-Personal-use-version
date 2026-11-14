package com.leapcontrol.app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行程备份的编解码与合并规则（用户 2026-11-09：退出登录丢数据，要能备份 / 迁移）。
 *
 * 守三件事：
 * 1. 导出的文件能原样读回来（车辆、行程、里程都不丢）；
 * 2. 不是零控行程的 JSON 要被挡住，别让人把别的文件导进来；
 * 3. 导入是**合并**：同一段行程不会变成两条，本机补过的地址与设置不被覆盖。
 */
class TripBackupTest {

    private val t0 = 1_700_000_000_000L

    private fun sample(at: Long, odo: Double?) =
        TripSample(at = at, odometerKm = odo, driving = true)

    private fun trip(id: Long, km: Double, startLabel: String? = null) = TripRecord(
        id = id,
        startAt = id,
        endAt = id + 30 * 60_000L,
        points = listOf(sample(id, 1000.0), sample(id + 30 * 60_000L, 1000.0 + km)),
        distanceKm = km,
        distanceEstimated = false,
        startLabel = startLabel,
    )

    private fun overview(vin: String, trips: List<TripRecord>, open: List<TripSample> = emptyList()) =
        TripOverview(vin = vin, trips = trips, open = open)

    private fun payload(vararg vehicles: TripBackup.Vehicle) =
        TripBackup.Payload(exportedAt = t0, appVersion = "2.0.4", vehicles = vehicles.toList())

    // ---------------- 编解码 ----------------

    @Test
    fun roundTripKeepsEverything() {
        val text = TripBackup.encode(
            payload(
                TripBackup.Vehicle("LAAA", overview("LAAA", listOf(trip(t0, 12.5)))),
                TripBackup.Vehicle("LBBB", overview("LBBB", listOf(trip(t0 + 1, 3.0), trip(t0 + 2, 7.25)))),
            ),
        )
        val decoded = TripBackup.decode(text).getOrThrow()
        assertEquals(2, decoded.vehicleCount)
        assertEquals(3, decoded.tripCount)
        assertEquals(22.75, decoded.km, 0.001)
        val aaa = decoded.vehicles.first { it.vin == "LAAA" }
        assertEquals("LAAA", aaa.data.vin)
        assertEquals(12.5, aaa.data.trips.single().distanceKm, 0.001)
        // 采样点也要在：轨迹靠它画
        assertEquals(2, aaa.data.trips.single().points.size)
    }

    @Test
    fun wrongKindIsRejected() {
        // 零记的备份文件不能被当成行程导入
        val journal = """{"kind":"leapcontrol-journal","format":1,"vehicles":[{"vin":"L","data":{}}]}"""
        val error = TripBackup.decode(journal).exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("不是零控的行程备份"))
    }

    @Test
    fun futureFormatIsRejected() {
        val text = """{"kind":"leapcontrol-trip","format":99,"vehicles":[{"vin":"L","data":{}}]}"""
        val error = TripBackup.decode(text).exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("版本"))
    }

    @Test
    fun emptyVehiclesRejected() {
        val text = """{"kind":"leapcontrol-trip","format":1,"vehicles":[]}"""
        assertNotNull(TripBackup.decode(text).exceptionOrNull())
    }

    @Test
    fun emptyTextRejected() {
        assertNotNull(TripBackup.decode("   ").exceptionOrNull())
    }

    // ---------------- 合并 ----------------

    @Test
    fun sameTripDoesNotDuplicate() {
        val local = overview("LAAA", listOf(trip(t0, 10.0)))
        val incoming = overview("LAAA", listOf(trip(t0, 10.0)))
        val merged = TripBackup.merge(local, incoming)
        assertEquals(1, merged.trips.size)
    }

    @Test
    fun newTripsAreAppendedInOrder() {
        val local = overview("LAAA", listOf(trip(t0 + 100, 5.0)))
        val incoming = overview("LAAA", listOf(trip(t0, 8.0), trip(t0 + 100, 5.0)))
        val merged = TripBackup.merge(local, incoming)
        assertEquals(2, merged.trips.size)
        assertEquals(t0, merged.trips.first().id)
        assertEquals(t0 + 100, merged.trips.last().id)
    }

    @Test
    fun localTripWinsWhenSameId() {
        // 本机可能后来补过起终点地址，不能被备份里那份空的覆盖
        val local = overview("LAAA", listOf(trip(t0, 10.0, startLabel = "杭州市余杭区文一西路")))
        val incoming = overview("LAAA", listOf(trip(t0, 10.0, startLabel = "起点")))
        val merged = TripBackup.merge(local, incoming)
        assertEquals("杭州市余杭区文一西路", merged.trips.single().startLabel)
    }

    @Test
    fun localSettingsWin() {
        // 电池容量是本机设置：本机填过就以本机为准，备份里那个数是另一台设备填的
        val local = overview("LAAA", listOf(trip(t0, 1.0))).copy(batteryKwh = 78.5)
        val incoming = overview("LAAA", listOf(trip(t0 + 1, 2.0))).copy(batteryKwh = 60.0)
        val merged = TripBackup.merge(local, incoming)
        assertEquals(78.5, merged.batteryKwh!!, 0.001)
    }

    @Test
    fun ongoingSegmentComesFromNewerSide() {
        // 正在进行的那一段不能两边拼：哪边最后一帧更晚就用哪边
        val localOpen = listOf(sample(t0 + 10, 900.0))
        val incomingOpen = listOf(sample(t0 + 20, 900.0), sample(t0 + 30, 905.0))
        val local = overview("LAAA", emptyList(), localOpen).copy(lastSample = localOpen.last())
        val incoming = overview("LAAA", emptyList(), incomingOpen).copy(lastSample = incomingOpen.last())
        val merged = TripBackup.merge(local, incoming)
        assertEquals(2, merged.open.size)
        assertEquals(t0 + 30, merged.lastSample!!.at)
    }

    @Test
    fun olderBackupDoesNotReplaceOngoingSegment() {
        val localOpen = listOf(sample(t0 + 30, 905.0))
        val incomingOpen = listOf(sample(t0 + 10, 900.0))
        val local = overview("LAAA", emptyList(), localOpen).copy(lastSample = localOpen.last())
        val incoming = overview("LAAA", emptyList(), incomingOpen).copy(lastSample = incomingOpen.last())
        val merged = TripBackup.merge(local, incoming)
        assertEquals(1, merged.open.size)
        assertEquals(t0 + 30, merged.open.single().at)
    }

    // ---------------- 文案 ----------------

    @Test
    fun fileNameIsRecognisable() {
        val name = TripBackup.fileName(t0)
        assertTrue(name.startsWith("零控行程-"))
        assertTrue(name.endsWith(".json"))
    }

    @Test
    fun describeCountsOnlyRealTrips() {
        val p = payload(TripBackup.Vehicle("LAAA", overview("LAAA", listOf(trip(t0, 10.0)))))
        val text = TripBackup.describe(p)
        assertTrue(text.contains("行程 1 段"))
        assertTrue(text.contains("车辆：1 辆"))
    }
}
