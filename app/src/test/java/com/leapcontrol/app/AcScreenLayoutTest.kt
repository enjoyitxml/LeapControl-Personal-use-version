package com.leapcontrol.app

import com.leapcontrol.app.ui.acSuggestion
import com.leapcontrol.app.ui.presetEditOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 空调二级页按上游 v4.3.6 重排后的守门测试（2026-11-07）。
 *
 * 上游 `ClimateControlBottomSheet` 用 `showExtendedControls`（默认 false）把
 * 「出风方向 + 座椅与座舱舒适」折起来，本项目要求**一屏展开**；
 * 风量是滑杆且松手才下发、座椅是 0/2 两态开关、预设点击要同步整组编辑态 ——
 * 这些在编译和常规点按里都不容易发现回归，所以直接钉住源码与纯函数口径。
 */
class AcScreenLayoutTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val source: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/AcScreen.kt").readText()
    }

    @Test
    fun `出风方向与座椅舒适必须直接展开，不许加折叠菜单`() {
        listOf("AnimatedVisibility", "showExtendedControls", "展开出风与座椅舒适", "收起更多设置").forEach { banned ->
            assertFalse("空调页又出现折叠开关：$banned", source.contains(banned))
        }
        assertTrue("缺少出风方向区", source.contains("AcSection(title = \"出风方向\")"))
        assertTrue("缺少座椅与座舱舒适区", source.contains("AcSection(title = \"座椅与座舱舒适\")"))
        assertTrue("整页必须可滚动", source.contains(".verticalScroll(rememberScrollState())"))
    }

    @Test
    fun `风量是滑杆且松手才下发，不是七个格子`() {
        assertTrue("风量没换成滑杆", source.contains("Slider("))
        assertTrue("滑杆必须 onValueChangeFinished 才下发", source.contains("onValueChangeFinished"))
        assertFalse(
            "又变回 1..7 的平铺档位格了",
            source.contains("for (level in Commands.MIN_AC_WIND_LEVEL..Commands.MAX_AC_WIND_LEVEL)"),
        )
    }

    @Test
    fun `座椅只发 0 或 2 两态，开一方本地关另一方`() {
        assertFalse("又变回 0→1→2→3 档位循环了", source.contains("% 4"))
        assertTrue(source.contains("driverSeatHeating_\${if (on) 2 else 0}"))
        assertTrue(source.contains("passengerSeatVentilation_\${if (on) 2 else 0}"))
        // 上游：开加热只把通风本地归零，不额外下发一条关闭指令
        assertTrue(source.contains("if (on) seatVentDriver = 0"))
        assertTrue(source.contains("if (on) seatHeatPassenger = 0"))
    }

    @Test
    fun `预设点击要同步整组编辑态并回填首页温度草稿`() {
        assertTrue(source.contains("edit = next"))
        assertTrue(source.contains("onPresetTemperature(next.temperature)"))
        assertTrue(source.contains("AC_OPTIMISTIC_WINDOW_MS"))
        assertEquals(4, listOf("quickCool", "quickHeat", "defrost", "deodorize").count {
            presetEditOf(it) != null
        })
    }

    @Test
    fun `预设编辑态与本项目实测过的 170 指令一致`() {
        val cool = presetEditOf("quickCool")!!
        assertEquals(18, cool.temperature)
        assertEquals(7, cool.wind)
        assertEquals("cold", cool.mode)
        assertEquals("in", cool.circle)
        assertFalse(cool.defogging)
        assertEquals("all", cool.position)

        val heat = presetEditOf("quickHeat")!!
        assertEquals(32, heat.temperature)
        assertEquals("hot", heat.mode)

        val deodorize = presetEditOf("deodorize")!!
        assertEquals(24, deodorize.temperature)
        assertEquals("nohotcold", deodorize.mode)
        assertEquals("out", deodorize.circle)

        // 这条不能按上游文档改成 24℃/5 挡/cold：真车实测那组只改风量、前挡不出风
        val defrost = presetEditOf("defrost")!!
        assertEquals(32, defrost.temperature)
        assertEquals(7, defrost.wind)
        assertEquals("hot", defrost.mode)
        assertEquals("out", defrost.circle)
        assertTrue(defrost.defogging)
        assertEquals("wshld", defrost.position)

        assertNull(presetEditOf("acOn"))
    }

    @Test
    fun `不接实时天气时建议条只在车内极热极冷出现，常温整块不显示`() {
        // 用户 2026-11-07 明确：不接天气接口，去掉常驻的那条除雾提示
        assertNull("常温不该再出现常驻建议", acSuggestion(24.0, null, false))
        assertNull("读不到车内温度时也不显示建议条", acSuggestion(null, null, false))
        assertNull("空调开着且常温时同样不显示", acSuggestion(24.0, null, true))

        assertEquals("quickCool", acSuggestion(35.0, null, false)?.command)
        assertEquals("quickHeat", acSuggestion(8.0, null, false)?.command)

        // 上游：空调已开且当前预设就是这条建议时，不再重复提示
        assertNull(acSuggestion(35.0, "quickCool", true))
        assertNotNull(acSuggestion(35.0, "quickHeat", true))
        assertTrue(source.contains("AcSuggestionCard"))
        listOf("前风挡易起雾", "一键除雾").forEach { banned ->
            assertFalse("常驻除雾提示又被加回来了：$banned", source.contains(banned))
        }
    }
}
