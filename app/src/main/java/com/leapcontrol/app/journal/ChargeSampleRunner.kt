package com.leapcontrol.app.journal

import android.content.Context
import android.os.PowerManager
import com.leapcontrol.app.ui.AppRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 充电采样的**唯一实现**：前台服务循环与闹钟兜底共用这一份，避免两条链路各写一遍。
 *
 * 做的事只有一件：拉一次车况。`AppRuntime.refreshStatus()` 内部会把这一帧喂给
 * `ChargeCycle.observeChargeCycle`（记下「充到多少」和最后一次看见充电的时刻），
 * 再调 [ChargeWatch.reschedule] 决定继续还是全停 —— 所以这里不解析任何信号，
 * 口径与用户在前台刷新完全一致。
 *
 * 三件必须做对的事（与 `TripSampleRunner` 同一套）：
 *  1. **不为不存在的窗口拉车况**：没在监测 / 超时 / 连续失败太多次 / 换了车 / 没登录，
 *     立刻返回 STOPPED；
 *  2. **失败也写时间戳**：否则服务会 5 秒一次地重试，把网关和车机打爆；
 *  3. **拿 PartialWakeLock**：Doze 下 CPU 可能已经睡了，网络请求要跑完才释放。
 *
 * 连续失败要单独封顶：请求失败时 `refreshStatus` 根本走不到 `reschedule`，
 * 「读不到 chargeState」那条收手逻辑管不着它，不封顶就会断网时整夜每分钟空转。
 */
object ChargeSampleRunner {

    enum class Result {
        /** 采到一帧车况，且仍在监测中。 */
        SAMPLED,
        /** 还没到下一个间隔（另一条链路刚采过），跳过。 */
        SKIPPED,
        /** 没登录 / 会话失效：不该继续。 */
        NO_SESSION,
        /** 充电已停 / 窗口已收尾 / 换了车：不该再拉。 */
        STOPPED,
        /** 网络或网关失败：下一轮再试。 */
        FAILED,
    }

    /** 车况接口正常 1~3 秒；20 秒没回来说明网关卡了，不能把服务挂在那里。 */
    private const val TIMEOUT_MS = 20_000L

    /**
     * @param timeoutMs 整轮请求预算。广播（`goAsync`）只有 10 秒，传 8 秒；
     *   前台服务可以给足 20 秒。
     */
    suspend fun sample(
        context: Context,
        force: Boolean = false,
        timeoutMs: Long = TIMEOUT_MS,
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val result = doSample(app, force, timeoutMs)
        when (result) {
            Result.FAILED -> ChargeWatch.noteFailure(app)
            Result.SAMPLED -> ChargeWatch.clearFailures(app)
            else -> Unit
        }
        result
    }

    private suspend fun doSample(app: Context, force: Boolean, timeoutMs: Long): Result {
        if (!ChargeWatch.isWatching(app)) return Result.STOPPED
        if (ChargeWatch.expired(app) ||
            ChargeWatch.tooManyFailures(ChargeWatch.failureStreak(app))
        ) {
            ChargeWatch.stop(app)
            return Result.STOPPED
        }
        val now = System.currentTimeMillis()
        if (!force && !ChargeWatch.isDue(now, ChargeWatch.lastSampleAt(app))) {
            return Result.SKIPPED
        }
        // 先记时间戳再请求：失败也要等下一个间隔，不能变成忙等重试
        ChargeWatch.markSampleAttempt(app, now)

        // 每次重新构造：SessionStore 可能刚被重新登录改过，缓存实例会拿着旧 token
        val runtime = runCatching { AppRuntime(app) }.getOrNull() ?: return Result.FAILED
        val session = runtime.session
        if (session.selectedVin.isBlank() ||
            (session.oldAuth == null && session.newAuth == null)
        ) {
            return Result.NO_SESSION
        }
        // 采样期间换了车：这一窗口属于旧车，别再拿新账号读旧车
        val vin = ChargeWatch.watchingVin(app)
        if (!vin.isNullOrBlank() && session.selectedVin != vin) {
            ChargeWatch.stop(app)
            return Result.STOPPED
        }

        val wake = wakeLock(app, timeoutMs + 5_000L)
        val result = try {
            if (withTimeoutOrNull(timeoutMs) { runtime.refreshStatus() } == null) {
                null
            } else if (ChargeWatch.isWatching(app)) {
                // 这一帧可能刚好捕捉到「停止充电」：refreshStatus → reschedule → stop。
                // 收尾后不再排下一次，也别再多耗一次电。
                Result.SAMPLED
            } else {
                Result.STOPPED
            }
        } finally {
            runCatching { wake?.release() }
        }
        return result ?: Result.FAILED
    }

    /** 唤醒锁：Doze 下叫醒进程还不够，网络请求得跑完。超时自动释放，不会漏电。 */
    private fun wakeLock(context: Context, holdMs: Long): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leapcontrol:charge-sample").apply {
            setReferenceCounted(false)
            acquire(holdMs)
        }
    }.getOrNull()
}
