package com.leapcontrol.app

import com.leapcontrol.app.ui.AcTempDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设定温度「连续点击」的守门测试（2026-11-07 用户要求忘掉旧反馈约束、完全按上游改）。
 *
 * 上游 `ClimateControlBottomSheet`：每一下 ± 先改 `editedTemperature`，
 * `pendingTemperatureJob?.cancel()` 后重新 `delay(400L)`，停手才 `submitSettings` 一条；
 * `actionsEnabled = status != null` 不拿回执锁按钮。MainActivity 侧
 * `submitClimateControl` 用 `showBusy = false`、`climateStatusRevision` 防旧请求覆盖新点击，
 * 网关受理即成功，不轮询 /query 判成败、最终状态交给乐观值 + 静默遥测刷新。
 *
 * 本项目旧口径是「每点一下发一条 + 等回执才解锁」，用户明确撤销。
 * Compose 的调度跑不进 JVM 单测，所以按项目惯例直接钉源码 + 测纯算术。
 */
class AcTemperatureSyncTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun ui(name: String): String =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/$name").readText()

    private val home: String by lazy { ui("HomeScreen.kt") }
    private val acScreen: String by lazy { ui("AcScreen.kt") }
    private val quickCard: String by lazy { ui("AcQuickCard.kt") }

    @Test
    fun `连点按上游 400ms 防抖，只发停手后的最后一个值`() {
        assertTrue("防抖窗口不是上游的 400ms", home.contains("AC_TEMP_DEBOUNCE_MS = 400L"))
        assertTrue("没有等停手就下发", home.contains("delay(AC_TEMP_DEBOUNCE_MS)"))
        assertTrue("连点时没有取消上一份待发任务", home.contains("acTempJob?.cancel()"))
        assertTrue("没有重新挂一份待发任务", home.contains("acTempJob = scope.launch"))
        assertTrue("没有给每次下发编号", home.contains("acTempRevision += 1L"))
        assertTrue("旧请求的结果会盖掉新点的值", home.contains("revision == acTempRevision"))
        assertTrue(
            "被新点击取消的旧任务不能当成失败（否则会把刚点的新温度回滚掉）",
            home.contains("catch (e: CancellationException)"),
        )
        // 用户撤销了「每次调节都要有反馈 / 等回执」：不能留串行循环、在途补发或 busy 锁
        assertFalse("又变回串行等回执了", home.contains("acTempSyncing"))
        assertFalse("又在等别的 170 指令收尾", home.contains("waitedMs < 20_000L"))
        assertFalse("又在指令在途后补发一条", home.contains("lastSent = wanted"))
        assertFalse("温度路径又把按钮锁上了", home.contains("acTempBusy"))
    }

    @Test
    fun `网关受理即返回，不轮询判成败也不显示等待确认`() {
        assertTrue("空调开着调温没有走 resultWaitMs = 0L", home.contains("resultWaitMs = 0L"))
        assertEquals(
            "两条温度指令（调温 / 关机态开机设温）都要不等回执",
            2,
            home.split("resultWaitMs = 0L").size - 1,
        )
        assertFalse("温度路径又设了 controlling（会点亮 busy 条并锁屏）", home.contains("controlling = \"acTemp\""))
        assertTrue("网关受理后没有安排静默遥测刷新", home.contains("if (sendAcTemperature(target))"))
    }

    @Test
    fun `加减号不再等回执，也不接任何 busy 锁`() {
        // 空调页与首页概览条都传绝对目标温度，不再各自 ±1 算一遍
        assertFalse("又变回按一下发一条的 delta 回调了", acScreen.contains("onTempStep"))
        assertFalse("首页还留着旧的 delta 接线", home.contains("onTempStep"))
        assertTrue("空调页没有把绝对温度交给上层", acScreen.contains("onTemperature(next)"))
        assertTrue("首页没有接空调页的绝对温度", home.contains("onTemperature = { setAcTemperature(it) }"))

        listOf(-1, 1).forEach { delta ->
            assertTrue(
                "空调页 ±($delta) 又被 busy 锁住了",
                acScreen.contains("enabled = AcTempDraft.canStep(edit.temperature, $delta)"),
            )
            assertTrue(
                "首页空调条 ±($delta) 又被 busy 锁住了",
                quickCard.contains("enabled = AcTempDraft.canStep(target, $delta)"),
            )
            assertFalse(
                "首页空调条 ±($delta) 还留着 busy 锁",
                quickCard.contains("enabled = !busy && AcTempDraft.canStep(target, $delta)"),
            )
        }
        assertFalse("首页空调条还留着专用锁参数", quickCard.contains("tempBusy"))
        assertFalse("空调页还留着专用锁参数", acScreen.contains("tempBusy"))
    }

    @Test
    fun `下发失败要把两层显示温度一起退回车端真值`() {
        assertTrue("失败时没有清掉首页草稿", home.contains("pendingAcTempDraft = null"))
        assertTrue("失败时没有通知空调页", home.contains("acTempRollbackTick++"))
        assertTrue("空调页没收到回滚信号", home.contains("temperatureRollbackTick = acTempRollbackTick"))
        assertTrue("空调页没有强制回灌（8 秒保护窗口会把回滚挡住）", acScreen.contains("LaunchedEffect(temperatureRollbackTick)"))
        assertTrue("空调页回滚时没有用上层真值", acScreen.contains("edit = edit.copy(temperature = targetTemp)"))
    }

    @Test
    fun `连点算术：五下只落到最终值，边界不越界`() {
        var shown = 24
        repeat(5) { shown = AcTempDraft.step(shown, 1) }
        assertEquals("连点五下应该只显示 29℃", 29, shown)
        assertEquals("到上限后再点也不能越界", 32, AcTempDraft.step(32, 1))
        assertEquals("到下限后再点也不能越界", 16, AcTempDraft.step(16, -1))
        assertEquals("草稿优先：界面立刻跟手", 29, AcTempDraft.shown(true, 24, 29))
    }
}
