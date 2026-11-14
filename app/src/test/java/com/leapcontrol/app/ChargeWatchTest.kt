package com.leapcontrol.app

import com.leapcontrol.app.journal.ChargeEvidence
import com.leapcontrol.app.journal.ChargeWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 充电期间后台采样的规则与接线守门（2026-11-13 重做）。
 *
 * 用户定的口径：和行程轨迹同一套方案，**捕捉到充电后每 1 分钟同步一次车况**；
 * 捕捉到停止充电就记下这一次的充电信息，然后**不再拉取车辆信息**。
 * 这里钉死三件事：
 *  1. 固定 1 分钟、只有 `1/5` 算充电、两条链路去重、14 小时封顶；
 *  2. 收尾后的 latch 必须挡住车端 `chargeState` 残影（否则会一整天后台耗电）；
 *  3. 前台服务 / 精确闹钟 / 唤醒锁 / 开机恢复 / 换车登出 / 停止后不续闹钟，接线一处不漏。
 */
class ChargeWatchTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    /** 读源码但丢掉纯注释行：文案守门不该被注释里的旧口径干扰。 */
    private fun codeOf(path: String): String =
        File(projectDir, path).readText().lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    // ---------------- 固定 1 分钟 ----------------

    @Test
    fun `充电期间固定每一分钟同步一次车况`() {
        assertEquals(60_000L, ChargeWatch.INTERVAL_MS)
    }

    @Test
    fun `只有交流充电中与直流快充中算充电`() {
        assertTrue(ChargeWatch.isCharging(1))
        assertTrue(ChargeWatch.isCharging(5))
        listOf(0, 2, 3, 4, 6).forEach { assertFalse("$it 不该算充电", ChargeWatch.isCharging(it)) }
        assertFalse(ChargeWatch.isCharging(null))
    }

    @Test
    fun `前台服务与闹钟按 80 容差去重`() {
        val now = 1_000_000L
        assertTrue(ChargeWatch.isDue(now, 0L))
        assertTrue(ChargeWatch.isDue(now, now - 48_000L))
        assertTrue(ChargeWatch.isDue(now, now - 60_000L))
        assertFalse("刚采过 30 秒不该再拉一次", ChargeWatch.isDue(now, now - 30_000L))
        assertTrue("系统时间被回拨要按该采了算", ChargeWatch.isDue(now, now + 10_000L))
        assertEquals(0L, ChargeWatch.msUntilNext(now, now - 60_000L))
        assertEquals(30_000L, ChargeWatch.msUntilNext(now, now - 30_000L))
        assertEquals(0L, ChargeWatch.msUntilNext(now, now + 1_000L))
    }

    @Test
    fun `观察窗口 14 小时封顶`() {
        val now = 100_000_000L
        assertTrue(ChargeWatch.withinWindow(now, now - 13 * 60 * 60 * 1000L))
        assertFalse(ChargeWatch.withinWindow(now, now - ChargeWatch.MAX_WATCH_MS))
        assertFalse(ChargeWatch.withinWindow(now, now + 1_000L))
        assertFalse(ChargeWatch.withinWindow(now, 0L))
    }

    @Test
    fun `连续三帧读不到充电状态就收手`() {
        assertFalse(ChargeWatch.shouldStopAfterUnknown(1))
        assertFalse(ChargeWatch.shouldStopAfterUnknown(2))
        assertTrue(ChargeWatch.shouldStopAfterUnknown(3))
        assertEquals(3, ChargeWatch.MAX_UNKNOWN_STREAK)
    }

    @Test
    fun `断网连续失败也要封顶 不能整夜空转`() {
        assertFalse(ChargeWatch.tooManyFailures(ChargeWatch.MAX_FAILURES - 1))
        assertTrue(ChargeWatch.tooManyFailures(ChargeWatch.MAX_FAILURES))
        assertEquals(10, ChargeWatch.MAX_FAILURES)
        val runner = codeOf("app/src/main/java/com/leapcontrol/app/journal/ChargeSampleRunner.kt")
        assertTrue(runner.contains("ChargeWatch.tooManyFailures(ChargeWatch.failureStreak(app))"))
        assertTrue(runner.contains("Result.FAILED -> ChargeWatch.noteFailure(app)"))
        assertTrue("成功一次要把失败数清零", runner.contains("Result.SAMPLED -> ChargeWatch.clearFailures(app)"))
    }

    // ---------------- 停止充电之后不许再挂起来 ----------------

    @Test
    fun `刚收尾时残影不能重新开启后台采样`() {
        val now = 1_000_000_000L
        val vin = "VIN-1"
        assertTrue("没有收尾记录时正常开窗口", ChargeWatch.mayRearm(now, 0L, null, vin, ChargeEvidence.UNKNOWN))
        assertFalse("刚收尾、又报充电中、没旁证 = 残影", ChargeWatch.mayRearm(now, now, vin, vin, ChargeEvidence.UNKNOWN))
        assertFalse("明确没插枪却报充电中，一律挡住", ChargeWatch.mayRearm(now, now, vin, vin, ChargeEvidence.REFUTED))
        assertTrue("真插枪有硬件旁证，立刻放行", ChargeWatch.mayRearm(now, now, vin, vin, ChargeEvidence.CONFIRMED))
        assertTrue(
            "过了冷却，不上报旁证的车型也要能开窗口",
            ChargeWatch.mayRearm(now + ChargeWatch.REARM_MS, now, vin, vin, ChargeEvidence.UNKNOWN),
        )
        assertFalse(
            "冷却没过就还是不放行",
            ChargeWatch.mayRearm(now + ChargeWatch.REARM_MS - 1, now, vin, vin, ChargeEvidence.UNKNOWN),
        )
        assertTrue("换了车不受上一辆的 latch 影响", ChargeWatch.mayRearm(now, now, "VIN-2", vin, ChargeEvidence.UNKNOWN))
        assertTrue("系统时间回拨按没有 latch 处理", ChargeWatch.mayRearm(now, now + 1_000L, vin, vin, ChargeEvidence.UNKNOWN))
    }

    // ---------------- 接线守门 ----------------

    @Test
    fun `清单里注册了前台服务 采样闹钟与开机恢复`() {
        val manifest = codeOf("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\".journal.ChargeWatchService\""))
        assertTrue(manifest.contains("android:name=\".journal.ChargeSampleReceiver\""))
        assertTrue(manifest.contains("android:name=\".journal.ChargeBootReceiver\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("android.permission.SCHEDULE_EXACT_ALARM"))
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"))
    }

    @Test
    fun `三层保活与彻底全停都在`() {
        val watch = codeOf("app/src/main/java/com/leapcontrol/app/journal/ChargeWatch.kt")
        assertTrue(watch.contains("startForegroundService"))
        assertTrue(watch.contains("setExactAndAllowWhileIdle"))
        assertTrue(watch.contains("setAndAllowWhileIdle"))
        assertTrue("停止充电必须走 stop", watch.contains("if (watching) stop(app, now)"))
        assertTrue("stop 必须撤闹钟", watch.contains("cancelAlarm(app)"))
        assertTrue("stop 必须停服务", watch.contains("stopService(app)"))
        assertTrue("登出 / 换车要清 latch", watch.contains("prefs(app).edit().clear().apply()"))

        val service = codeOf("app/src/main/java/com/leapcontrol/app/journal/ChargeWatchService.kt")
        assertTrue(service.contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue(service.contains("ChargeSampleRunner.sample"))
        assertTrue(service.contains("onTimeout"))

        val runner = codeOf("app/src/main/java/com/leapcontrol/app/journal/ChargeSampleRunner.kt")
        assertTrue(runner.contains("PARTIAL_WAKE_LOCK"))
        assertTrue(runner.contains("runtime.refreshStatus()"))
        assertTrue("失败也要写时间戳，不能忙等", runner.contains("ChargeWatch.markSampleAttempt(app, now)"))

        val receiver = codeOf("app/src/main/java/com/leapcontrol/app/journal/ChargeSampleReceiver.kt")
        assertTrue("停止后闹钟绝不能再排下一次", receiver.contains("if (ChargeWatch.isWatching(app)) ChargeWatch.scheduleNext(app)"))
        assertTrue(receiver.contains("ChargeWatch.cancelAlarm(app)"))
    }

    @Test
    fun `每次车况都是先记录再决定启停 两个入口都接了`() {
        val runtime = codeOf("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt")
        val call = "ChargeWatch.reschedule(appContext, session.selectedVin, chargeSample)"
        assertEquals("refreshStatus 与 readChargeState 都要接", 2, runtime.split(call).size - 1)
        val first = runtime.indexOf(call)
        val observe = runtime.indexOf("journal.observeChargeCycle(chargeSample)", 0)
        assertTrue("先把充电信息定格落盘", observe >= 0 && observe < first)
        assertTrue("换车 / 登出要连 latch 一起清", runtime.contains("ChargeWatch.clear(appContext)"))
        assertFalse("别再留着旧的 cancel 入口", runtime.contains("ChargeWatch.cancel(appContext)"))
    }

    @Test
    fun `回到前台与开机都能接回采样`() {
        val main = codeOf("app/src/main/java/com/leapcontrol/app/MainActivity.kt")
        assertTrue(main.contains("ChargeWatch.resumeIfWatching(this)"))
        val boot = codeOf("app/src/main/java/com/leapcontrol/app/journal/ChargeBootReceiver.kt")
        assertTrue(boot.contains("ChargeWatch.resumeAlarmOnly(context)"))
        assertFalse("开机不许起前台服务", boot.contains("startService"))
    }

    @Test
    fun `用户可见文案统一成每分钟 不再说自适应间隔`() {
        val card = codeOf("app/src/main/java/com/leapcontrol/app/ui/HomeChargeCycleCard.kt")
        assertTrue(card.contains("每 1 分钟"))
        assertFalse(card.contains("每 10 分钟"))
        assertFalse(card.contains("快充满时缩到"))
        assertTrue(card.contains("彻底停掉后台读取"))
    }
}
