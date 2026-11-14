package com.leapcontrol.app

import com.leapcontrol.app.ui.CarStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行驶中禁控的门禁规则。
 *
 * 口径来自官方 `VehicleDrivingSafetyPolicy`（D/R 挡即视为行驶中，不看车速），
 * 但额外排除「熄火后残留的挡位」—— 否则锁车离车后首页会全部变灰、一个键都点不了。
 */
class DrivingSafetyPolicyTest {

    private fun status(
        gear: String? = null,
        speed: String? = null,
        shutDown: Boolean = false,
    ) = CarStatus(
        soc = 50,
        range = "300",
        electricRange = "300",
        fuelRange = null,
        fuelSoc = null,
        isRangeExtender = false,
        totalMileage = "1000",
        interiorTemp = "20",
        locked = true,
        acOn = false,
        gearStatus = gear,
        speed = speed,
        isShutDown = shutDown,
    )

    @Test
    fun `D 挡与 R 挡一律按行驶中处理`() {
        listOf("D", "D挡", "DRIVE", "drive", "前进", "3").forEach {
            assertTrue("$it 应判为行驶中", DrivingSafetyPolicy.isDriving(it, null, false))
        }
        listOf("R", "R挡", "REVERSE", "倒车", "1").forEach {
            assertTrue("$it 应判为行驶中", DrivingSafetyPolicy.isDriving(it, null, false))
        }
    }

    @Test
    fun `等红灯挂 D 挡车速为 0 也要拦`() {
        // 官方口径的关键一条：车随时会起步，不能用「现在刚好 0km/h」放行
        assertTrue(DrivingSafetyPolicy.isDriving("D", "0", false))
        assertTrue(DrivingSafetyPolicy.isDriving("D", "0.0 km/h", false))
    }

    @Test
    fun `P 挡与 N 挡不拦`() {
        assertFalse(DrivingSafetyPolicy.isDriving("P", null, false))
        assertFalse(DrivingSafetyPolicy.isDriving("N", "0", false))
        assertFalse(DrivingSafetyPolicy.isDriving(null, null, false))
        assertFalse(DrivingSafetyPolicy.isDriving("", "", false))
    }

    @Test
    fun `没有挡位时报出车速也算行驶中`() {
        assertTrue(DrivingSafetyPolicy.isDriving(null, "42", false))
        assertTrue(DrivingSafetyPolicy.isDriving(null, "42 km/h", false))
        assertTrue(DrivingSafetyPolicy.isDriving("P", "12.5", false))
    }

    @Test
    fun `车速抖动残值不算行驶中`() {
        // GPS / 报文抖动会给 0.1、0.2 这类值
        assertFalse(DrivingSafetyPolicy.isDriving(null, "0", false))
        assertFalse(DrivingSafetyPolicy.isDriving(null, "0.2", false))
        assertFalse(DrivingSafetyPolicy.isDriving(null, "0.5", false))
    }

    @Test
    fun `已熄火离车时挡位与车速都当残值`() {
        // 车端熄火后不一定把 1010 刷成 P：残值仍是 D，照抄官方口径会把车主锁在门外
        assertFalse(DrivingSafetyPolicy.isDriving("D", "60", true))
        assertFalse(DrivingSafetyPolicy.isDriving("R", "3", true))
    }

    @Test
    fun `车况读不到时放行`() {
        // 宁可少拦一次，也不要因为「读不到车况」把整车控车锁死
        assertFalse(DrivingSafetyPolicy.isDriving(null as CarStatus?))
    }

    @Test
    fun `车况版本按挡位与熄火状态判定`() {
        assertTrue(DrivingSafetyPolicy.isDriving(status(gear = "D", speed = "0")))
        assertFalse(DrivingSafetyPolicy.isDriving(status(gear = "D", shutDown = true)))
        assertFalse(DrivingSafetyPolicy.isDriving(status(gear = "P")))
    }

    @Test
    fun `拦截文案逐字固定`() {
        assertEquals("车辆正在行驶中，无法进行此项操作", DrivingSafetyPolicy.HINT)
    }
}
