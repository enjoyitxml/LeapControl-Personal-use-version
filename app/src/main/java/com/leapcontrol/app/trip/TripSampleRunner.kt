package com.leapcontrol.app.trip

import android.content.Context
import android.os.PowerManager
import com.leapcontrol.app.ui.AppRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 后台采样的**唯一实现**：前台服务与闹钟兜底共用这一份，避免两条链路各写一遍。
 *
 * 做的事情就是「拉一次车况」——`AppRuntime.refreshStatus()` 内部会把这一帧压成
 * [TripSample] 喂给 [TripTracker]，并顺手更新小组件 / 充电观测。所以这里不自己
 * 解析信号，也不自己算点，口径与用户在前台刷新完全一致。
 *
 * 三件事必须做对：
 *  1. **不为不存在的行程拉车况**：没登录、换了车、这一段已经结束 —— 立刻返回 STOP；
 *  2. **失败也要记时间戳**：否则服务会 5 秒一次地重试，把网关和车机打爆；
 *  3. **拿 PartialWakeLock**：Doze 下 CPU 可能已经睡了，只靠闹钟叫醒进程还不够，
 *     网络请求要跑完才释放（最多 25 秒，超时自释放，不会漏电）。
 */
object TripSampleRunner {

    enum class Result {
        /** 采到一个点。 */
        SAMPLED,
        /** 还没到下一个间隔（另一条链路刚采过），跳过。 */
        SKIPPED,
        /** 没登录 / 会话失效：不该继续，调用方应停止。 */
        NO_SESSION,
        /** 这一趟已经结束 / 暂停 / 换了车：停止。 */
        CLOSED,
        /** 网络或网关失败：下一轮再试。 */
        FAILED,
    }

    /** 车况接口正常 1~3 秒；20 秒还没回来说明网关卡了，不能把服务挂在那里。 */
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
            val vin = TripSampling.recordingVin(app) ?: return@withContext Result.CLOSED
            val now = System.currentTimeMillis()
            if (!force && !TripSampling.isDue(now, TripSampling.lastSampleAt(app), TripSampling.intervalMs(app))) {
                return@withContext Result.SKIPPED
            }
            // 先记时间戳再请求：失败也要等下一个间隔，不能变成忙等重试
            TripSampling.markSampleAttempt(app, now)

            // 每次重新构造：SessionStore 可能刚被重新登录改过，缓存实例会拿着旧 token
            val runtime = runCatching { AppRuntime(app) }.getOrNull()
                ?: return@withContext Result.FAILED
            val session = runtime.session
            if (session.selectedVin.isBlank() ||
                (session.oldAuth == null && session.newAuth == null)
            ) {
                return@withContext Result.NO_SESSION
            }
            // 当前选中的不是正在记录的那辆车：这一趟属于别的车，别再拉了
            if (session.selectedVin != vin) return@withContext Result.CLOSED
            // 已经收尾 / 暂停 / 还没开始记录：后台不该再唤醒车机
            if (TripTracker.flow.value?.open.isNullOrEmpty()) return@withContext Result.CLOSED

            val wake = wakeLock(app, timeoutMs + 5_000L)
            try {
                withTimeoutOrNull(timeoutMs) { runtime.refreshStatus() }
                    ?: return@withContext Result.FAILED
                // refreshStatus 里那一帧可能刚好触发收尾（锁车 / 驻车满 5 分钟），
                // 收尾就告诉调用方停掉服务与闹钟，别再多耗一次电
                if (TripTracker.flow.value?.open.isNullOrEmpty()) Result.CLOSED
                else Result.SAMPLED
            } finally {
                runCatching { wake?.release() }
            }
        }

    /** 唤醒锁：Doze 下 CPU 可能已经睡了，叫醒进程还不够，网络请求得跑完。超时自动释放。 */
    private fun wakeLock(context: Context, holdMs: Long): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leapcontrol:trip-sample").apply {
            setReferenceCounted(false)
            acquire(holdMs)
        }
    }.getOrNull()
}
