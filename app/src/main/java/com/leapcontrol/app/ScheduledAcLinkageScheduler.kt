package com.leapcontrol.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 定时空调「到点联动」的闹钟调度。
 *
 * ## 为什么不跟车端预约一起下发
 *
 * 车端 `cmdid=171` 只负责到点开空调，没有「顺手关窗 / 开座椅加热」的字段。
 * 这些动作只能在手机上定一个同点的闹钟，到点自己发控车指令。
 *
 * ## 闹钟怎么活下来
 *
 * - 有精确闹钟权限 → `setExactAndAllowWhileIdle`，Doze 下也准时；
 * - 没有 → 退 `setAndAllowWhileIdle`（可能晚几分钟），再退普通 `set`。
 *   宁可晚几分钟，也不要因为没权限就整条功能失效。
 * - 开机 / 升级后由 [ScheduledAcLinkageReceiver] 之外的 `ScheduledAcLinkageBootReceiver` 重排。
 *
 * 只排「下一次」，不排一周七条：车端计划随时会被改，排多了反而清不干净。
 */
object ScheduledAcLinkageScheduler {

    const val ACTION = "com.leapcontrol.app.action.SCHEDULED_AC_LINKAGE"
    const val EXTRA_VIN = "extra_vin"

    /** 与上游同号，便于对照日志。 */
    private const val REQUEST_CODE = 1711

    /**
     * 按当前本地记录重排闹钟。
     *
     * 三个前提同时成立才排：定时空调开着、至少勾了一个联动项、能算出下一次时间。
     * 任何一条不满足就把已有闹钟撤掉 —— 残留一条旧闹钟会在用户关掉功能后
     * 突然发一堆控车指令，比不排更糟。
     */
    fun reschedule(context: Context, vin: String? = null, plan: ScheduledAcPlan? = null) {
        val app = context.applicationContext
        cancel(app)
        val store = SessionStore(app)
        val targetVin = vin?.takeIf { it.isNotBlank() }
            ?: store.load()?.selectedVin.orEmpty()
        if (targetVin.isBlank()) return

        val enabled = plan?.enabled ?: store.loadScheduledAcEnabled(targetVin)
        if (!enabled) return
        val linkage = store.loadScheduledAcLinkage(targetVin)
        if (!linkage.anyEnabled) return

        val start = plan?.startTime ?: store.loadScheduledAcStartTime(targetVin)
        val days = plan?.days ?: store.loadScheduledAcDays(targetVin)
        val at = ScheduledAcLinkageSchedule.nextTriggerAt(
            nowMs = System.currentTimeMillis(),
            startTime = start,
            days = days,
        ) ?: return
        scheduleAt(app, targetVin, at)
    }

    /** 直接定到某个时刻（[reschedule] 与接收器内部重排都用它）。 */
    fun scheduleAt(context: Context, vin: String, atMs: Long) {
        val app = context.applicationContext
        val am = alarm(app) ?: return
        val pi = pending(app, vin)
        if (canScheduleExact(app)) {
            val ok = runCatching {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }.isSuccess
            if (ok) return
        }
        val ok = runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }.isSuccess
        if (!ok) runCatching { am.set(AlarmManager.RTC_WAKEUP, atMs, pi) }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        // VIN 用空串也能取消：PendingIntent 的等价性只看 requestCode + action，
        // 这里 requestCode 固定，所以不管哪辆车都指向同一条闹钟
        runCatching { alarm(app)?.cancel(pending(app, "")) }
    }

    /** Android 12+ 精确闹钟要用户授权；13 起默认给，14 起新装默认又不给。 */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = alarm(context) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    private fun alarm(context: Context): AlarmManager? =
        runCatching { context.getSystemService(AlarmManager::class.java) }.getOrNull()

    private fun pending(context: Context, vin: String): PendingIntent {
        val intent = Intent(context, ScheduledAcLinkageReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_VIN, vin)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
