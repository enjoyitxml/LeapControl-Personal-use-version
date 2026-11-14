package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 车型代号归一（Lafa5 的接口代号是 B05）。
 *
 * 出过的事：车主在能耗卡上看到「超越全国 50% 的 B05 车主」，「我的」页车型行
 * 也是 B05。代号只在一张表里映射，其它页面各写一份，漏得七零八落。
 */
class VehicleModelAliasTest {

    // ------------------------------------------------------------ canonical

    @Test
    fun `接口代号 B05 归一成宣传名 Lafa5`() {
        assertEquals("Lafa5", VehicleModels.canonical("B05"))
    }

    @Test
    fun `代号大小写和零跑前缀都不影响归一`() {
        assertEquals("Lafa5", VehicleModels.canonical("b05"))
        assertEquals("Lafa5", VehicleModels.canonical("零跑B05"))
        assertEquals("Lafa5", VehicleModels.canonical(" 零跑 B05 "))
        assertEquals("Lafa5", VehicleModels.canonical("LAFA5"))
        assertEquals("Lafa5", VehicleModels.canonical("lafa5"))
    }

    @Test
    fun `其它车型原样返回`() {
        assertEquals("C11", VehicleModels.canonical("C11"))
        assertEquals("C16", VehicleModels.canonical("零跑C16"))
        assertEquals("T03", VehicleModels.canonical("t03").uppercase())
    }

    @Test
    fun `空值与空白不产生假车型`() {
        assertEquals("", VehicleModels.canonical(null))
        assertEquals("", VehicleModels.canonical(""))
        assertEquals("", VehicleModels.canonical("   "))
        assertEquals("", VehicleModels.canonical("零跑"))
    }

    @Test
    fun `比较键统一大写`() {
        assertEquals("LAFA5", VehicleModels.key("B05"))
        assertEquals("LAFA5", VehicleModels.key("Lafa5"))
        assertEquals("C11", VehicleModels.key("c11"))
        assertEquals("", VehicleModels.key(null))
    }

    // ------------------------------------------------------------ displayName

    @Test
    fun `展示名不出现内部代号`() {
        assertEquals("Lafa5", VehicleModels.displayName("B05"))
        assertEquals("Lafa5", VehicleModels.displayName("零跑B05"))
    }

    @Test
    fun `没有车型时退回零跑`() {
        assertEquals("零跑", VehicleModels.displayName(null))
        assertEquals("零跑", VehicleModels.displayName(""))
    }

    // ------------------------------------------------------------ 配置项

    @Test
    fun `配置下拉里不出现代号 且 Lafa5 只有一条`() {
        assertFalse("下拉里不该再有 B05", SUPPORTED_VEHICLE_MODELS.contains("B05"))
        assertEquals(
            "Lafa5 只能出现一次，否则车主会看到两个同义选项",
            1,
            SUPPORTED_VEHICLE_MODELS.count { it.equals("Lafa5", ignoreCase = true) },
        )
    }

    @Test
    fun `老用户存过 B05 的配置仍然有效`() {
        // 归一后能匹配到 Lafa5 —— 否则会被判成「配置无效」而反复弹确认框
        assertEquals("Lafa5", VehicleConfigConfirmationPolicy.normalizeModel("B05"))
        assertEquals("Lafa5", VehicleConfigConfirmationPolicy.normalizeModel("零跑B05"))
        assertEquals("Lafa5", VehicleConfigConfirmationPolicy.normalizeModel("Lafa5"))
        assertEquals("C11", VehicleConfigConfirmationPolicy.normalizeModel("C11"))
    }

    @Test
    fun `认不出的车型不硬塞成某个车型`() {
        assertEquals(null, VehicleConfigConfirmationPolicy.normalizeModel(""))
        assertEquals(null, VehicleConfigConfirmationPolicy.normalizeModel("X99"))
    }

    // ------------------------------------------------------------ 车漆色板

    @Test
    fun `B05 拿到的色板就是 Lafa5 的`() {
        val byCode = VehicleAppearanceCatalog.availableColors("B05")
        val byName = VehicleAppearanceCatalog.availableColors("Lafa5")
        assertEquals(byName.map { it.id }, byCode.map { it.id })
        assertTrue("Lafa5 的色板不该是空的", byCode.isNotEmpty())
        assertEquals("Lafa5", VehicleAppearanceCatalog.canonicalModel("B05"))
    }

    // ------------------------------------------------------------ 存量数据

    @Test
    fun `已存配置里的旧代号在读出来时也要归一`() {
        // 老版本按原样把接口代号存进了 prefs。只改写入侧治不了这批车主：
        // 他们确认过的车照样显示 B05，而且「我的」页、首页车名全都跟着错。
        val projectDir = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.first { File(it, "app").isDirectory }
        val store = File(
            projectDir,
            "app/src/main/java/com/leapcontrol/app/SessionStore.kt",
        ).readText()
        val loadBody = store.substringAfter("fun loadVehicleConfig(")
            .substringBefore("fun saveVehicleConfig(")
        assertTrue(
            "读取车型配置时必须过 VehicleModels.canonical，否则老数据永远是 B05",
            loadBody.contains("VehicleModels.canonical("),
        )
    }
}
