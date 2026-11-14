package com.leapcontrol.app

import com.leapcontrol.app.ui.CarStatus
import com.leapcontrol.app.ui.TireInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 车况「数据时间」的判定规则（用户 2026-11-09 反馈）。
 *
 * ## 用户要的是什么
 * 首页左上角 / 桌面小组件上的时间要写「最近一次与车机成功通信」，
 * 不是「用户点了刷新」的那一刻 —— 后者会让人误判车辆状态。
 *
 * ## 为什么这两者会不一样
 * 车机休眠后网关仍会把上一次的报文原样回给 App（HTTP 200，请求是成功的），
 * 数据其实是几小时前的。所以只能用「这帧数据有没有变过」倒推：
 * 变了 → 车机确实上报了；一模一样 → 沿用上一次的时间。
 */
class StatusFreshnessTest {

    private val t0 = 1_700_000_000_000L
    private val later = t0 + 30 * 60 * 1000L

    private fun status(
        soc: Int = 60,
        range: String? = "320km",
        locked: Boolean? = true,
        gear: String? = "P",
        speed: String? = "0",
        windowOpen: Boolean? = false,
        interiorTemp: String? = "22",
        lat: Double? = 30.12345,
        lng: Double? = 120.12345,
        seenAt: Long = 0L,
        tires: List<TireInfo> = emptyList(),
    ) = CarStatus(
        soc = soc,
        range = range,
        electricRange = range,
        fuelRange = null,
        fuelSoc = null,
        isRangeExtender = false,
        totalMileage = "1234",
        interiorTemp = interiorTemp,
        locked = locked,
        acOn = false,
        tires = tires,
        gearStatus = gear,
        speed = speed,
        windowOpen = windowOpen,
        latitude = lat,
        longitude = lng,
        locationSeenAt = seenAt,
    )

    @Test
    fun sameDataKeepsPreviousTime() {
        val fp = StatusFreshness.fingerprint(status())
        val anchor = StatusAnchor(fp, t0)
        // 网关把同一份报文又回了一次：时间不能往前跳
        assertSame(anchor, StatusFreshness.next(anchor, StatusFreshness.fingerprint(status()), later))
        assertEquals(t0, anchor.at)
    }

    @Test
    fun coldStartUsesNow() {
        val anchor = StatusFreshness.next(null, StatusFreshness.fingerprint(status()), later)
        assertEquals(later, anchor.at)
    }

    @Test
    fun brokenAnchorIsRebuilt() {
        // 磁盘里读到 at = 0 的坏数据：不能继续沿用，重记一次
        val broken = StatusAnchor(StatusFreshness.fingerprint(status()), 0L)
        val anchor = StatusFreshness.next(broken, broken.fingerprint, later)
        assertEquals(later, anchor.at)
    }

    @Test
    fun realChangesAdvanceTime() {
        val base = StatusFreshness.fingerprint(status())
        val changed = listOf(
            "电量" to status(soc = 61),
            "续航" to status(range = "319km"),
            "闭锁" to status(locked = false),
            "车窗" to status(windowOpen = true),
            "挡位" to status(gear = "D"),
            "车速" to status(speed = "12"),
            "车内温度" to status(interiorTemp = "25"),
            "坐标" to status(lat = 30.12445),
            "胎压" to status(tires = listOf(TireInfo("左前", "240", "28", false))),
        )
        changed.forEach { (name, next) ->
            assertNotEquals(name, base, StatusFreshness.fingerprint(next))
        }
    }

    @Test
    fun locationSeenAtIsNotPartOfFingerprint() {
        // 这个字段本身是时间，放进指纹会自己跟自己比永远不等
        assertEquals(
            StatusFreshness.fingerprint(status(seenAt = 0L)),
            StatusFreshness.fingerprint(status(seenAt = t0)),
        )
    }

    @Test
    fun numberFormatNoiseIsIgnored() {
        // 车端有时给 "22"，有时给 "22.0"：同一个温度不能判成「变了」
        assertEquals(
            StatusFreshness.fingerprint(status(interiorTemp = "22")),
            StatusFreshness.fingerprint(status(interiorTemp = "22.0")),
        )
        assertEquals(
            StatusFreshness.fingerprint(status(speed = "0")),
            StatusFreshness.fingerprint(status(speed = "0.0")),
        )
    }

    @Test
    fun smallCoordinateDriftIsStillDetected() {
        // 5 位小数 ≈ 1 米，车真的挪了要算变化
        assertNotEquals(
            StatusFreshness.fingerprint(status(lat = 30.12345)),
            StatusFreshness.fingerprint(status(lat = 30.12355)),
        )
    }

    @Test
    fun sameStatusTwiceProducesSameFingerprint() {
        assertEquals(
            StatusFreshness.fingerprint(status()),
            StatusFreshness.fingerprint(status()),
        )
    }
}
