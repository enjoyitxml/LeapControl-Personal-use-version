package com.leapcontrol.app

import com.leapcontrol.app.trip.TripEnergy
import com.leapcontrol.app.trip.TripFormat
import com.leapcontrol.app.trip.TripRules
import com.leapcontrol.app.trip.TripSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续航达成率 = 实际里程 ÷ 表显续航消耗（2026-11-20 用户要求，记录中与已完成都要显示）。
 *
 * 这个数看着简单，但它最容易被两类输入毁掉，所以每条门槛都要钉住：
 *  - **表显续航按整公里跳**：只跑 0.2 km 却掉了 8 km，算出来 2.5% —— 那是分辨率不够，
 *    不是「续航虚」，宁可显示 `--`；
 *  - **中途充过电 / 续航信号串了**：续航只掉 1 km 却跑了 40 km，会算出 4000%，
 *    比没有数更糟。
 */
class TripAchievementTest {

    private fun energy(startRange: Double?, endRange: Double?): TripEnergy =
        TripEnergy(startRangeKm = startRange, endRangeKm = endRange, rangeUsedKm = 
            if (startRange != null && endRange != null) {
                (startRange - endRange).takeIf { it > 0.0 }
            } else null)

    @Test
    fun `里程除表显续航消耗`() {
        val e = energy(420.0, 320.0)
        assertEquals(0.98, e.achievement(98.0)!!, 0.0001)
        // 跑得比表显还多（下坡 / 表显保守）也算，只要在可信区间内
        assertEquals(1.2, e.achievement(120.0)!!, 0.0001)
    }

    @Test
    fun `缺续航或缺里程时没有达成率`() {
        assertNull(energy(null, 320.0).achievement(98.0))
        assertNull(energy(420.0, null).achievement(98.0))
        assertNull(energy(320.0, 320.0).achievement(0.0))
        assertNull(energy(420.0, 320.0).achievement(null))
        assertNull(energy(420.0, 320.0).achievement(0.0))
    }

    @Test
    fun `分辨率不够与脏数据一律不给数`() {
        val e = energy(420.0, 412.0) // 只掉了 8 km
        // 0.2 km ÷ 8 km = 2.5%：表显按整公里跳，这个数没有意义
        assertNull(e.achievement(0.2))
        // 40 km ÷ 1 km = 4000%：多半是中途充过电
        assertNull(energy(421.0, 420.0).achievement(40.0))
    }

    @Test
    fun `文案只取整百分比`() {
        assertEquals("98%", TripFormat.achievement(0.98))
        assertEquals("120%", TripFormat.achievement(1.2))
        assertEquals("--", TripFormat.achievement(null))
        assertEquals("--", TripFormat.achievement(0.0))
        assertEquals("--", TripFormat.achievement(-1.0))
    }

    @Test
    fun `采样点算出来的能耗能直接给达成率`() {
        val points = listOf(
            TripSample(at = 1_000L, rangeKm = 420.0, socPercent = 80.0, odometerKm = 12_000.0),
            TripSample(at = 2_000L, rangeKm = 320.0, socPercent = 60.0, odometerKm = 12_100.0),
        )
        val e = TripRules.energy(points, distanceKm = 98.0, capacityKwh = 60.0)!!
        assertEquals(100.0, e.rangeUsedKm!!, 0.001)
        assertEquals(0.98, e.achievement(98.0)!!, 0.0001)
    }

    @Test
    fun `首页卡与实时面板都挂着这一格`() {
        val card = read("app/src/main/java/com/leapcontrol/app/ui/TripTrackCard.kt")
        assertTrue(
            "首页行程卡的指标行必须有「续航达成」这一格",
            card.contains("TripFormat.achievement(trip.rangeAchievement)") &&
                card.contains("\"续航达成\""),
        )
        val live = read("app/src/main/java/com/leapcontrol/app/ui/TripLivePanel.kt")
        assertTrue(
            "记录中的实时面板也要显示续航达成率",
            live.contains("rangeAchievementDetail(live.energy, live.distanceKm)") &&
                live.contains("续航达成"),
        )
        val history = read("app/src/main/java/com/leapcontrol/app/ui/TripHistoryScreen.kt")
        assertTrue(
            "已完成的行程在清单里要看得到：收起那行与展开明细都要有",
            history.contains("trip.rangeAchievement") &&
                history.contains("rangeAchievementDetail(trip.energy, trip.distanceKm)"),
        )
    }

    private val projectDir: java.io.File = generateSequence(
        java.io.File(requireNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { java.io.File(it, "app").isDirectory }

    private fun read(path: String): String =
        java.io.File(projectDir, path).readText(charset = java.nio.charset.StandardCharsets.UTF_8)
}
