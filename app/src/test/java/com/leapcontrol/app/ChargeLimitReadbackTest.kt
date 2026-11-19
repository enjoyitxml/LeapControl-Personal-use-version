package com.leapcontrol.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 充电上限**回读口径**守门（2026-11-19 起）。
 *
 * ## 用户的原话
 * 「不管我开还是关健康充电，调节充电上限到多少，切出页面再切回来，又会显示上限为 90%」
 * 「上限设了不生效」
 *
 * ## 两条根因（都已钉死，别改回去）
 * 1. **下发时被开关改写**：`socForDispatch` 曾经在健康充电关闭时固定发 100，
 *    滑杆写 80、车端收到 100。现在恒等对齐滑杆值。
 * 2. **回填时读错字段**：充电上限取的是 `commonConfig` 充电计划的 `percent`，
 *    那个字段不随设置变化（实测恒 90），用户调多少都被它顶回去。
 *    真值在 `healthyCharging/queryPushState` 的 `targetSoc`，与上游 leapauto 一致。
 *
 * 另外钉住上游那条 15 秒保护窗：车端推送有延迟，刚下发就回读会拿到旧值。
 */
class ChargeLimitReadbackTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val home: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt").readText()
    }

    private val runtime: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt").readText()
    }

    @Test
    fun `充电中心回读必须走 queryPushState 而不是充电计划的 percent`() {
        assertTrue(
            "充电中心没有读 loadHealthyChargePushState()，上限会退回去读 commonConfig 的 percent（恒 90）",
            home.contains("runtime.loadHealthyChargePushState()"),
        )
        assertTrue(
            "AppRuntime 缺少 loadHealthyChargePushState 实现",
            runtime.contains("suspend fun loadHealthyChargePushState()"),
        )
        assertTrue(
            "queryPushState 的 targetSoc 取值丢了（字段名按上游 targetSoc → soc 兜底）",
            runtime.contains("optInt(\"targetSoc\", it.optInt(\"soc\", -1))"),
        )
    }

    @Test
    fun `上限回填时 pushState 必须排在 percent 之前`() {
        val start = home.indexOf("limitSoc = when {")
        assertTrue("loadChargeCenter 里的 limitSoc 分支不见了", start >= 0)
        val block = home.substring(start, (start + 700).coerceAtMost(home.length))
        val push = block.indexOf("healthyPush?.targetSoc")
        val percent = block.indexOf("plan.percent")
        assertTrue("limitSoc 回填里没有用 pushState 的 targetSoc", push >= 0)
        assertTrue("limitSoc 回填里没有 percent 兜底分支", percent >= 0)
        assertTrue(
            "pushState 的 targetSoc 必须排在 percent 前面，否则又会被恒 90 的字段覆盖",
            push < percent,
        )
    }

    @Test
    fun `下发后有 15 秒保护窗，回读不许立刻覆盖刚设的值`() {
        assertTrue("缺少下发保护窗常量 CHARGE_SYNC_GUARD_MS", home.contains("CHARGE_SYNC_GUARD_MS"))
        assertTrue("保护窗不是 15 秒（上游口径）", home.contains("CHARGE_SYNC_GUARD_MS = 15_000L"))
        assertTrue(
            "loadChargeCenter 里没有 justSaved 分支，刚下发就会被旧值冲掉",
            home.contains("val justSaved = System.currentTimeMillis() - chargeSyncedAt"),
        )
        assertTrue(
            "saveCharging 成功后没有记 chargeSyncedAt",
            home.contains("if (sent.isSuccess) chargeSyncedAt = System.currentTimeMillis()"),
        )
    }

    @Test
    fun `充电上限下发按用户选的值，不被健康充电开关改写`() {
        assertTrue(
            "socForDispatch 又被塞回了 healthy 参数分支",
            !runtime.contains("socForDispatch(chargeLimit, healthyCharge)"),
        )
        assertTrue(
            "AppRuntime 下发时没有用 socForDispatch",
            runtime.contains("ChargeSchedule.socForDispatch(chargeLimit)"),
        )
    }
}
