package com.leapcontrol.app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 后台采样间隔的纯逻辑 + 接线守门（2026-11-12）。
 *
 * 用户定的档位：30 秒 / 45 秒 / 1 / 2 / 3 / 5 分钟，**默认 45 秒**。
 * 这里钉三件事：
 *  1. 间隔归一与文案（改档位时不会出现「90 秒」这种选项外的东西）；
 *  2. 服务 + 闹钟两条链路的去重判据（同一间隔内不重复拉车况）；
 *  3. 接线：Manifest 里确实声明了 dataSync 前台服务与闹钟、开始记录后确实会启停。
 */
class TripSamplingTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun codeOf(path: String): String =
        File(projectDir, path).readText().lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    // ---------------- 档位 ----------------

    @Test
    fun `可选档位含 30 秒与 45 秒 默认 45 秒`() {
        assertTrue(TripSampling.INTERVALS_SEC.contains(30))
        assertTrue(TripSampling.INTERVALS_SEC.contains(45))
        assertEquals(45, TripSampling.DEFAULT_INTERVAL_SEC)
        // 分钟档也都在（用户点名要的四档）
        listOf(60, 120, 180, 300).forEach { assertTrue(TripSampling.INTERVALS_SEC.contains(it)) }
    }

    @Test
    fun `任意数字归到最近档位`() {
        assertEquals(30, TripSampling.normalize(10))
        assertEquals(30, TripSampling.normalize(30))
        assertEquals(45, TripSampling.normalize(45))
        assertEquals(45, TripSampling.normalize(50))
        assertEquals(60, TripSampling.normalize(60))
        assertEquals(120, TripSampling.normalize(100))
        assertEquals(300, TripSampling.normalize(999))
    }

    @Test
    fun `文案按秒或分钟说 不出现 90 秒这种档外值`() {
        assertEquals("30 秒", TripSampling.label(30))
        assertEquals("45 秒", TripSampling.label(45))
        assertEquals("1 分钟", TripSampling.label(60))
        assertEquals("2 分钟", TripSampling.label(120))
        assertEquals("3 分钟", TripSampling.label(180))
        assertEquals("5 分钟", TripSampling.label(300))
        // 档外的 91 秒按最近档（120 秒）说话，绝不出现「91 秒」
        assertEquals("2 分钟", TripSampling.label(91))
    }

    @Test
    fun `间隔换算成毫秒`() {
        assertEquals(30_000L, TripSampling.intervalMsOf(30))
        assertEquals(45_000L, TripSampling.intervalMsOf(45))
        assertEquals(300_000L, TripSampling.intervalMsOf(300))
    }

    // ---------------- 去重判据 ----------------

    @Test
    fun `从没采过就该立刻采`() {
        assertEquals(0L, TripSampling.msUntilNext(now = 1_000_000L, lastAt = 0L, intervalMs = 45_000L))
        assertTrue(TripSampling.isDue(1_000_000L, 0L, 45_000L))
    }

    @Test
    fun `刚采过要等满剩余时间`() {
        val now = 1_000_000L
        assertEquals(45_000L, TripSampling.msUntilNext(now, now, 45_000L))
        assertEquals(20_000L, TripSampling.msUntilNext(now, now - 25_000L, 45_000L))
        assertEquals(0L, TripSampling.msUntilNext(now, now - 60_000L, 45_000L))
    }

    @Test
    fun `提前量到八成就算到点 服务与闹钟不会重复采`() {
        val now = 1_000_000L
        // 45 秒档：36 秒（80%）起算该采了
        assertFalse(TripSampling.isDue(now, now - 35_000L, 45_000L))
        assertTrue(TripSampling.isDue(now, now - 36_000L, 45_000L))
        // 30 秒档：24 秒起算
        assertFalse(TripSampling.isDue(now, now - 23_000L, 30_000L))
        assertTrue(TripSampling.isDue(now, now - 24_000L, 30_000L))
    }

    @Test
    fun `时钟回拨不会卡住采样`() {
        val now = 1_000_000L
        assertTrue("lastAt 在未来（系统时间被调回去）也要能继续采", TripSampling.isDue(now, now + 60_000L, 45_000L))
    }

    // ---------------- 接线守门 ----------------

    @Test
    fun `清单里声明了前台服务与闹钟兜底`() {
        val manifest = codeOf("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifest.contains("SCHEDULE_EXACT_ALARM"))
        assertTrue(manifest.contains("WAKE_LOCK"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(manifest.contains(".trip.TripRecordService"))
        assertTrue(manifest.contains(".trip.TripSampleAlarmReceiver"))
        assertTrue(manifest.contains(".trip.TripBootReceiver"))
    }

    @Test
    fun `开始记录会起服务 结束或暂停会停掉`() {
        val tracker = codeOf("app/src/main/java/com/leapcontrol/app/trip/TripTracker.kt")
        // 开段成功 → 起后台采样
        assertTrue(tracker.contains("TripStartResult.STARTED) TripSampling.begin(context, vin)"))
        // 暂停 / 结束 / 丢弃 / 自动收尾 → 停
        assertTrue(tracker.contains("TripPauseResult.PAUSED) TripSampling.stop(context)"))
        assertTrue(tracker.contains("TripSampling.stop(context)"))
        assertTrue(tracker.contains("TripResumeResult.RESUMED) TripSampling.begin(context, vin)"))
    }

    @Test
    fun `自动记录方案已经从数据层删除`() {
        val models = codeOf("app/src/main/java/com/leapcontrol/app/trip/TripModels.kt")
        assertFalse("不该再有 TripMode", models.contains("TripMode"))
        assertFalse("ingest 不该再接受 autoStart", models.contains("autoStart"))
        val runtime = codeOf("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt")
        assertFalse(runtime.contains("TripMode"))
        assertFalse(runtime.contains("trip_auto_start"))
    }

    @Test
    fun `采样只认正在记录的那辆车 且失败不忙等`() {
        val runner = codeOf("app/src/main/java/com/leapcontrol/app/trip/TripSampleRunner.kt")
        // 换了车 / 已收尾 → 停止
        assertTrue(runner.contains("session.selectedVin != vin"))
        assertTrue(runner.contains("TripTracker.flow.value?.open.isNullOrEmpty()"))
        // 先写时间戳再请求：失败也要等下一个间隔
        val markAt = runner.indexOf("markSampleAttempt")
        val requestAt = runner.indexOf("refreshStatus()")
        assertTrue("时间戳必须写在请求之前", markAt in 1 until requestAt)
    }

    @Test
    fun `通知文案跟着用户设的间隔走`() {
        val service = codeOf("app/src/main/java/com/leapcontrol/app/trip/TripRecordService.kt")
        assertTrue(service.contains("TripSampling.intervalSeconds(context)"))
        assertTrue(service.contains("TripSampling.label(seconds)"))
        // 前台服务类型必须显式声明，否则 Android 14 上 startForeground 直接抛
        assertTrue(service.contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        // Android 15 的 6 小时上限：到点要主动收尾，交给闹钟继续
        assertTrue(service.contains("override fun onTimeout"))
    }
}
