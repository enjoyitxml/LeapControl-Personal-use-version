package com.leapcontrol.app

import com.leapcontrol.app.ui.FridgeCardRules
import com.leapcontrol.app.ui.FridgeCardState
import com.leapcontrol.app.ui.FridgeDraft
import com.leapcontrol.app.ui.FridgeSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页冰箱卡：上游 cmdid=500 的参数口径 + 「默认收起 / 不做能力判断」两条纪律的守门。
 *
 * 上游实现位置（leapauto-ref）：Models.kt `buildFridgeControl`、
 * FridgeControlBottomSheet.kt、API/API.md 的指令矩阵。
 */
class FridgeCardRulesTest {

    @Test
    fun `制冷温度 -6 到 15 步进，到边界停住`() {
        assertEquals(-6, FridgeCardRules.TEMP_MIN)
        assertEquals(15, FridgeCardRules.TEMP_MAX)
        assertEquals(5, FridgeCardRules.stepTemp(4, 1))
        assertEquals(3, FridgeCardRules.stepTemp(4, -1))
        assertEquals(-6, FridgeCardRules.stepTemp(-6, -1))
        assertEquals(15, FridgeCardRules.stepTemp(15, 1))
        // 车况给的越界值 / 空值也要收敛，不能把 50°C 的制热温度显示成制冷目标
        assertEquals(15, FridgeCardRules.clampTemp(50))
        assertEquals(-6, FridgeCardRules.clampTemp(-40))
        assertEquals(4, FridgeCardRules.clampTemp(null))
    }

    @Test
    fun `温度预设与离车时长档位对齐上游`() {
        assertEquals(listOf(0, 4, 8, 12), FridgeCardRules.PRESETS.map { it.temp })
        assertEquals(listOf("冰镇", "冷饮", "果蔬", "保鲜"), FridgeCardRules.PRESETS.map { it.label })
        assertEquals(listOf(1, 2, 4, 8), FridgeCardRules.DURATION_HOURS)
        // 车况上报的是 1..24 任意小时，收敛到卡片上最接近的一档
        assertEquals(1, FridgeCardRules.nearestDuration(0))
        assertEquals(2, FridgeCardRules.nearestDuration(2))
        assertEquals(4, FridgeCardRules.nearestDuration(6))
        assertEquals(8, FridgeCardRules.nearestDuration(24))
    }

    @Test
    fun `一次点击合成一条 cmdid500 且八个字段全量下发`() {
        val draft = FridgeDraft(
            enabled = true,
            mode = FridgeMode.HOT,
            temp = 8,
            style = FridgeStyle.TURBO,
            parkEnable = true,
            durationHours = 4,
            everyDeparture = true,
        )
        val built = Commands.buildFridgeControl(FridgeCardRules.command(draft))
        assertEquals("500", built.cmdid)
        val st = JSONObject(built.stateJson)
        assertEquals(8, st.length())
        assertEquals(1, st.getInt("enable"))
        // 制热强制 50°C，传入的 temp 被忽略
        assertEquals(50, st.getInt("temp"))
        assertEquals("hot", st.getString("mode"))
        assertEquals("turbo", st.getString("style"))
        assertEquals(1, st.getInt("parkEnable"))
        assertEquals(4 * 3600, st.getInt("duration"))
        assertEquals("2", st.getString("cycles"))
        assertEquals("false", st.getString("value"))
    }

    @Test
    fun `制冷指令与仅单次生效的 cycles`() {
        val state = JSONObject(
            Commands.buildFridgeControl(
                FridgeCardRules.command(
                    FridgeDraft(temp = -2, style = FridgeStyle.NORMAL, durationHours = 1),
                    enable = true,
                ),
            ).stateJson,
        )
        assertEquals("cold", state.getString("mode"))
        assertEquals("normal", state.getString("style"))
        assertEquals(-2, state.getInt("temp"))
        assertEquals(3600, state.getInt("duration"))
        assertEquals("1", state.getString("cycles"))
        assertEquals(0, state.getInt("parkEnable"))
    }

    @Test
    fun `关闭冰箱时离车保持一律归零`() {
        val st = JSONObject(
            Commands.buildFridgeControl(
                FridgeCardRules.command(
                    FridgeDraft(parkEnable = true, durationHours = 8, everyDeparture = true),
                    enable = false,
                ),
            ).stateJson,
        )
        assertEquals(0, st.getInt("enable"))
        assertEquals(0, st.getInt("parkEnable"))
    }

    @Test
    fun `状态文案取自车况而不是草稿`() {
        assertEquals("状态待同步", FridgeCardRules.statusText(FridgeCardState()))
        assertEquals("已关闭", FridgeCardRules.statusText(FridgeCardState(enabled = false, hasSignal = true)))
        assertEquals(
            "智能制冷 · 4°C",
            FridgeCardRules.statusText(FridgeCardState(enabled = true, temp = 4, hasSignal = true)),
        )
        assertEquals(
            "急速制冷 · -2°C",
            FridgeCardRules.statusText(
                FridgeCardState(enabled = true, temp = -2, style = FridgeStyle.TURBO, hasSignal = true),
            ),
        )
        assertEquals(
            "恒温暖饮 · 50°C",
            FridgeCardRules.statusText(
                FridgeCardState(enabled = true, mode = FridgeMode.HOT, temp = 50, hasSignal = true),
            ),
        )
        assertEquals(
            "离车保持中",
            FridgeCardRules.statusText(FridgeCardState(enabled = true, parkEnable = true, hasSignal = true)),
        )
    }

    @Test
    fun `离车保持截止时间用小时与分钟回显`() {
        assertNull(FridgeCardRules.parkEndLabel(null))
        assertNull(FridgeCardRules.parkEndLabel(0L))
        assertNull(FridgeCardRules.parkEndLabel(-1L))
        val label = FridgeCardRules.parkEndLabel(1_700_000_000L)
        assertNotNull(label)
        assertTrue(label!!.startsWith("离车保持至 "))
        assertTrue("时间应是 HH:mm：$label", Regex("离车保持至 \\d{2}:\\d{2}").matches(label))
    }

    @Test
    fun `车况的离车信号能进卡片状态与草稿`() {
        val state = FridgeCardRules.of(
            FridgeSnapshot(
                enabled = true,
                mode = FridgeMode.COLD,
                style = FridgeStyle.TURBO,
                temp = 8,
                parkEnable = true,
                parkDurationHours = 6,
                parkCycles = 1,
                parkEndTimeEpochSeconds = 1_700_000_000L,
            ),
        )
        assertTrue(state.hasSignal)
        assertTrue(state.parkEnable)
        assertEquals(1, state.parkCycles)
        val draft = FridgeCardRules.draftOf(state)
        assertEquals(8, draft.temp)
        assertEquals(FridgeStyle.TURBO, draft.style)
        assertEquals(4, draft.durationHours)
        assertTrue(draft.everyDeparture)
    }

    @Test
    fun `没有冰箱信号时卡片照样能设置和下发，不做能力判断`() {
        val state = FridgeCardRules.of(null)
        assertFalse(state.hasSignal)
        assertEquals("状态待同步", FridgeCardRules.statusText(state))
        val cmd = FridgeCardRules.command(FridgeCardRules.draftOf(state), enable = true)
        assertTrue(cmd.enable)
        assertEquals("500", Commands.buildFridgeControl(cmd).cmdid)
    }

    @Test
    fun `冰箱卡默认收起且不被版本升级补入`() {
        assertFalse(HomeCardId.FRIDGE in HomeCardLayout.DEFAULT)
        // 故意不升版本：升了就会把这张卡硬塞给所有老用户
        assertEquals(10, HomeCardLayout.VERSION)
        assertFalse(HomeCardId.FRIDGE in HomeCardLayout.parse(null))
        assertFalse(
            HomeCardId.FRIDGE in HomeCardLayout.parse(
                "AC,LOCATION", savedVersion = HomeCardLayout.VERSION,
            ),
        )
        assertFalse(HomeCardId.FRIDGE in HomeCardLayout.parse("AC,LOCATION", savedVersion = 9))
        val v9Default = "AC,LOCATION,TIRE_TRIP,HEALTH,TRIP,CHARGE_CYCLE,COMPOSITION,RANK,OWNERSHIP"
        assertFalse(HomeCardId.FRIDGE in HomeCardLayout.parse(v9Default, savedVersion = 9))

        // 但它必须能从编辑态「添加卡片」里加进来，加了之后能落盘 / 读回
        assertTrue(HomeCardId.FRIDGE in HomeCardLayout.hidden(HomeCardLayout.DEFAULT))
        val withFridge = HomeCardLayout.DEFAULT + HomeCardId.FRIDGE
        assertEquals(withFridge, HomeCardLayout.parse(HomeCardLayout.serialize(withFridge)))
        // 零记关掉也不影响这张卡（它不依赖零记数据），被收起的只有那三张零记卡
        val withoutJournal = HomeCardLayout.visible(withFridge, journalEnabled = false)
        assertTrue(HomeCardId.FRIDGE in withoutJournal)
        assertTrue(HomeCardId.INSURANCE !in withoutJournal)
        assertTrue(HomeCardId.CHARGE_CYCLE !in withoutJournal)
        assertTrue(HomeCardId.FRIDGE in HomeCardLayout.hidden(HomeCardLayout.DEFAULT, journalEnabled = false))
        assertFalse(HomeCardLayout.usesJournal(HomeCardId.FRIDGE))
    }

    @Test
    fun `源码守门：冰箱卡不进默认顺序与补入表，卡片里不做车辆能力判断`() {
        val projectDir = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.first { File(it, "app").isDirectory }

        val layout = File(projectDir, "app/src/main/java/com/leapcontrol/app/HomeCardLayout.kt").readText()
        val defaultBlock = layout
            .substringAfter("val DEFAULT: List<HomeCardId> = listOf(")
            .substringBefore("\n    )")
        assertFalse("冰箱卡不许进默认顺序", defaultBlock.contains("FRIDGE"))
        val addedBlock = layout
            .substringAfter("private val ADDED_IN")
            .substringBefore("\n    )")
        assertFalse("冰箱卡不许登记补入", addedBlock.contains("FRIDGE"))

        val card = File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/FridgeCard.kt").readText()
        val code = card.lines().filterNot {
            val t = it.trimStart()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString("\n")
        assertFalse("冰箱卡不许读 VehicleCapabilities", code.contains("VehicleCapabilities"))
        assertFalse("冰箱卡不许按 capabilities 隐藏", code.contains("capabilities"))
        assertFalse("冰箱卡不许按 hasSignal 提前 return", code.contains("if (!state.hasSignal) return"))
    }

    @Test
    fun `首页摘要卡一行写完状态，离车保持不重复四个字`() {
        assertEquals("状态待同步", FridgeCardRules.summaryText(FridgeCardState()))
        assertEquals(
            "已关闭",
            FridgeCardRules.summaryText(FridgeCardState(enabled = false, hasSignal = true)),
        )
        assertEquals(
            "智能制冷 · 4°C",
            FridgeCardRules.summaryText(FridgeCardState(enabled = true, temp = 4, hasSignal = true)),
        )
        // 离车保持且带截止时间：合并成一条，不能出现两次「离车保持」
        val parking = FridgeCardState(
            enabled = true,
            parkEnable = true,
            parkEndTimeEpochSeconds = 1_700_000_000L,
            hasSignal = true,
        )
        val summary = FridgeCardRules.summaryText(parking)
        assertTrue("应带上截止时间：$summary", Regex("离车保持 · 至 \\d{2}:\\d{2}").matches(summary))
        // 没有截止时间就退回普通状态文案
        assertEquals(
            "离车保持中",
            FridgeCardRules.summaryText(FridgeCardState(enabled = true, parkEnable = true, hasSignal = true)),
        )
    }

    @Test
    fun `源码守门：冰箱首页卡只做摘要，功能全在二级页且二级页有返回门禁`() {
        val projectDir = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.first { File(it, "app").isDirectory }

        // 首页卡：只有「点开二级页」，不许出现下发回调（避免出现第二个开冰箱的入口）
        val card = File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/FridgeCard.kt").readText()
        assertTrue("摘要卡要用 onOpen 进二级页", card.contains("onOpen: () -> Unit"))
        assertFalse("摘要卡不许自己下发指令", card.contains("onControl"))
        assertFalse("摘要卡不许直接拼指令", card.contains("FridgeControlCommand"))

        // 二级页：全部功能 + 自己的 BackHandler（项目纪律：每个二级页都要有）
        val detail =
            File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/FridgeDetailScreen.kt").readText()
        assertTrue("二级页必须有 BackHandler", detail.contains("BackHandler"))
        assertTrue("二级页负责合成指令", detail.contains("FridgeCardRules.command"))
        assertTrue("二级页要有四个温度预设", detail.contains("FridgeCardRules.PRESETS"))
        assertTrue("二级页要有离车保持时长", detail.contains("FridgeCardRules.DURATION_HOURS"))
        assertFalse("二级页同样不许按能力隐藏", detail.contains("VehicleCapabilities"))

        // 首页接线：卡片 onOpen -> showFridge，showFridge -> 二级页
        val home = File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt").readText()
        assertTrue("卡片要能打开二级页", home.contains("onOpen = { showFridge = true }"))
        assertTrue("二级页要接在 showFridge 分支上", home.contains("FridgeDetailScreen("))
    }
}
