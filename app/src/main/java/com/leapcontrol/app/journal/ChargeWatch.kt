package com.leapcontrol.app.journal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 充电期间的后台定时采样：**只在真的在充电时存在，捕捉到停止充电立刻全停**。
 *
 * ## 和行程轨迹用同一套方案（用户 2026-11-13 指定）
 *
 * [com.leapcontrol.app.trip.TripSampling] 那套三层保活原样搬过来：
 *  1. [ChargeWatchService]：`dataSync` 前台服务常驻，按固定间隔跑采样循环（进程优先级
 *     FOREGROUND，Doze 不掐网络，也不需要用户去系统设置开敏感权限）；
 *  2. [ChargeSampleReceiver]：精确闹钟兜底，服务被 OEM 清掉 / 撞上 Android 15 的
 *     6 小时上限时照样把进程叫起来采一次；
 *  3. 电池优化白名单（用户在行程页授权过的话）+ `PartialWakeLock` 保证请求跑完。
 *
 * 两条链路共用 [ChargeSampleRunner] 与 `last_sample_at`，按 80% 容差去重，
 * 不会出现「服务刚采完闹钟又拉一次」。**App 在前台的任何一次车况刷新本身就是采样**，
 * [reschedule] 会把闹钟往后推，所以前台用得越勤，后台越不会重复唤醒车端。
 *
 * ## 为什么固定 1 分钟
 *
 * 车端没有充电历史接口，「充到多少」和「什么时候充完」只能靠零控看见的车况攒。
 * 以前按 `chargeRemainTime` 自适应 3~30 分钟，最后一格离真正拔枪最多差 20 分钟，
 * 结束时间就写不准（用户 2026-11-13 反馈的规则）。现在只要看见过一次充电，
 * 就每分钟读一帧：结束时间精度 1 分钟，最后一格电量也不会漏。
 *
 * 代价是充电期间每分钟唤醒一次 T-Box。充电时车本来就在工作、12V 由 DC-DC 供电，
 * 这笔开销可以接受；**非充电期一次都不多发** —— 这正是「避免一直后台耗电」的兑现方式。
 *
 * ## 停止充电之后
 *
 * `AppRuntime.refreshStatus()` 的顺序是刻意的：先 `journal.observeChargeCycle(...)`
 * 把这一次的结束时刻、最终电量、当时里程定格落盘（[ChargeCycle.closeCurrent]），
 * 再调 [reschedule]；后者看到「没在充电」就 [stop] —— 清标记、撤闹钟、停前台服务，
 * 一条后台车况请求都不再发。
 *
 * 车端 `chargeState` 会在拔枪后残留（记忆里的老问题）：不拦的话早上开一次 App
 * 又会把采样挂起来，一整天每分钟拉一次。所以 [stop] 会写一条「刚收尾」的 latch，
 * [mayRearm] 要求硬件旁证 CONFIRMED、或过了 [REARM_MS] 冷却，才允许重新开窗口。
 *
 * ## 平时不额外拉车况
 *
 * 「捕捉到充电行为」不靠常态轮询（那本身就会一直费车电），而是搭已有车况请求的
 * 顺风车：进首页、手动刷新、小组件、磁贴、行程采样……任何一帧报 `1/5` 就自动接管。
 * 插枪那一刻一次都没打开过零控的极端情况仍然靠「手动修正」兜底，口径不变。
 */
object ChargeWatch {

    /** 采样广播的 action（与 [ChargeSampleReceiver] 对齐）。 */
    const val ACTION = "com.leapcontrol.app.action.CHARGE_SAMPLE"

    /** 固定间隔：1 分钟（用户 2026-11-13 指定，不再做自适应）。 */
    const val INTERVAL_MS = 60_000L

    /**
     * 观察窗口上限：超过 14 小时不再续采样。
     *
     * 一次慢充不会超过它；超了说明车端报文卡在 `state=1`，与其无限期每分钟唤醒，
     * 不如停下来等下一帧明确的「没在充电」。
     */
    const val MAX_WATCH_MS = 14 * 60 * 60 * 1000L

    /** 连续几帧读不到 `chargeState` 就收手（车型根本不上报这一格时别一直拉）。 */
    const val MAX_UNKNOWN_STREAK = 3

    /**
     * 连续多少次车况请求失败就停（断网 / 网关挂了）。
     *
     * 失败时 `refreshStatus` 不会走到 [reschedule]，光靠「读不到状态」那条收手逻辑
     * 收不住 —— 会变成每分钟一次、直到 14 小时窗口超时。10 次（10 分钟）足够盖住
     * 地库没信号这种临时故障，又不会整夜空转耗电。
     */
    const val MAX_FAILURES = 10

    /** 收尾后重新允许开窗口的冷却：挡住拔枪后回放的 `chargeState=1` 残影。 */
    const val REARM_MS = 30 * 60 * 1000L

    private const val REQUEST_CODE = 0x1C0A5
    private const val PREFS = "charge_watch"
    private const val KEY_STARTED = "started_at"
    private const val KEY_VIN = "watching_vin"
    private const val KEY_LAST = "last_sample_at"
    private const val KEY_UNKNOWN = "unknown_streak"
    private const val KEY_FAILS = "fail_streak"
    private const val KEY_STOPPED = "stopped_at"
    private const val KEY_STOPPED_VIN = "stopped_vin"

    // ---------------- 纯逻辑（可单测） ----------------

    /** 正在往电池里灌电：`1` 交流充电中、`5` 直流快充中。其余状态一律不算充电。 */
    fun isCharging(state: Int?): Boolean = state == 1 || state == 5

    /** 两条触发路径（服务循环 / 闹钟）共用它做去重，含 80% 容差。 */
    fun isDue(now: Long, lastAt: Long): Boolean =
        lastAt <= 0L || lastAt > now || now - lastAt >= INTERVAL_MS * 4 / 5

    /** 距离下一次采样还差多少毫秒（0 = 该采了）。时钟回拨不当作「还早」。 */
    fun msUntilNext(now: Long, lastAt: Long): Long {
        if (lastAt <= 0L || lastAt > now) return 0L
        return (INTERVAL_MS - (now - lastAt)).coerceAtLeast(0L)
    }

    /** 观察窗口是否还在有效期内（started 落在未来 = 系统时间被回拨，按无效处理）。 */
    fun withinWindow(now: Long, startedAt: Long): Boolean =
        startedAt > 0L && startedAt <= now && now - startedAt < MAX_WATCH_MS

    /** 连续读不到充电状态是否已到收手的时候。 */
    fun shouldStopAfterUnknown(streak: Int): Boolean = streak >= MAX_UNKNOWN_STREAK

    /** 连续请求失败是否已到收手的时候。 */
    fun tooManyFailures(streak: Int): Boolean = streak >= MAX_FAILURES

    /**
     * 刚刚收尾过，还允许重新开一个观察窗口吗。
     *
     * - 没有收尾记录 / 换了车 / 系统时间回拨：放行；
     * - 明确没插枪（REFUTED）却报「充电中」：那是残影，一律不放行；
     * - 有硬件旁证（CONFIRMED）：真的又插枪了，立刻放行；
     * - 旁证读不到（UNKNOWN，很多车型不上报）：等过冷却再放行，别让残影当天一直挂着。
     */
    fun mayRearm(
        now: Long,
        stoppedAt: Long,
        stoppedVin: String?,
        vin: String,
        evidence: ChargeEvidence,
    ): Boolean {
        if (stoppedAt <= 0L || stoppedAt > now) return true
        if (!stoppedVin.isNullOrBlank() && stoppedVin != vin) return true
        if (evidence == ChargeEvidence.REFUTED) return false
        if (evidence == ChargeEvidence.CONFIRMED) return true
        return now - stoppedAt >= REARM_MS
    }

    // ---------------- 状态读写 ----------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否正在监测（落盘，进程被杀 / 重启后还能接上）。 */
    fun isWatching(context: Context): Boolean = prefs(context).getLong(KEY_STARTED, 0L) > 0L

    /** 正在监测的车；老版本只存了 `started_at`，这里可能读到 null。 */
    fun watchingVin(context: Context): String? = prefs(context).getString(KEY_VIN, null)

    fun startedAt(context: Context): Long = prefs(context).getLong(KEY_STARTED, 0L)

    fun lastSampleAt(context: Context): Long = prefs(context).getLong(KEY_LAST, 0L)

    /** 记下「刚刚看过一帧车况」：前台刷新、服务采样、闹钟采样都算。失败也写，不忙等。 */
    fun markSampleAttempt(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST, at).apply()
    }

    fun failureStreak(context: Context): Int = prefs(context).getInt(KEY_FAILS, 0)

    /** 一次后台请求失败：累加；到 [MAX_FAILURES] 就由调用方收手。 */
    fun noteFailure(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().putInt(KEY_FAILS, failureStreak(app) + 1).apply()
    }

    /** 请求成功过一次（或 App 在前台正常刷新过）：失败计数清零。 */
    fun clearFailures(context: Context) {
        prefs(context).edit().remove(KEY_FAILS).apply()
    }

    /** 窗口是否已经超时（车端一直卡在「充电中」）。 */
    fun expired(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val started = startedAt(context)
        return started > 0L && !withinWindow(now, started)
    }

    // ---------------- 唯一的启停入口 ----------------

    /**
     * 每次拿到车况后都调一次（[com.leapcontrol.app.ui.AppRuntime.refreshStatus] 与
     * `readChargeState` 两个入口）。**调用方必须先 `observeChargeCycle` 落盘定格**，
     * 再由这里决定后台采样是继续还是全停。
     *
     * @param vin 当前选中的车；采样期间换了车就停，绝拿新账号去读旧车
     * @param sample 这一帧的充电状态与硬件旁证（见 [ChargeSample]）
     */
    fun reschedule(
        context: Context,
        vin: String,
        sample: ChargeSample,
        now: Long = System.currentTimeMillis(),
    ) {
        val app = context.applicationContext
        val p = prefs(app)
        // 成功拿到一帧车况（不管是不是还在充电）：后台失败计数清零
        if (p.getInt(KEY_FAILS, 0) > 0) p.edit().remove(KEY_FAILS).apply()
        val started = p.getLong(KEY_STARTED, 0L)
        val watching = started > 0L
        if (watching && !withinWindow(now, started)) {
            stop(app, now)
            return
        }

        val state = sample.chargeState
        if (state == null) {
            // 这一帧没读到 chargeState：不把整次充电丢掉，但也不能无限期重试
            if (!watching) return
            val streak = p.getInt(KEY_UNKNOWN, 0) + 1
            if (shouldStopAfterUnknown(streak)) {
                stop(app, now)
                return
            }
            p.edit().putInt(KEY_UNKNOWN, streak).apply()
            markSampleAttempt(app, now)
            scheduleNext(app)
            return
        }

        // 0 未插枪 / 2 已完成 / 3 故障 / 4 预约等待 / 6 暂停，以及「报 1 但明确没插枪」：
        // 都不再灌电了。调用方已经把充电信息定格落盘，这里只负责把后台彻底关掉。
        val charging = isCharging(state) && sample.evidence != ChargeEvidence.REFUTED
        if (!charging) {
            if (watching) stop(app, now)
            return
        }

        if (!mayRearm(
                now = now,
                stoppedAt = p.getLong(KEY_STOPPED, 0L),
                stoppedVin = p.getString(KEY_STOPPED_VIN, null),
                vin = vin,
                evidence = sample.evidence,
            )
        ) {
            return
        }

        // 继续 / 开一个新窗口。老版本只写了 started_at，顺手把 VIN 补齐。
        p.edit()
            .putString(KEY_VIN, vin)
            .putLong(KEY_STARTED, if (started > 0L) started else now)
            .remove(KEY_STOPPED)
            .remove(KEY_STOPPED_VIN)
            .remove(KEY_UNKNOWN)
            .apply()
        // 这一次车况刷新本身就是有效观测，别让服务循环紧接着再拉一遍
        markSampleAttempt(app, now)
        startService(app)
        scheduleNext(app)
    }

    /**
     * 收尾：写 latch → 清观察窗口 → 撤闹钟 → 停前台服务。
     *
     * 停止充电、没登录、换车、观察超时都走这里。**之后不会再有任何后台车况请求**，
     * 直到下一次明确看见真充电（[mayRearm]）。
     */
    fun stop(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val p = prefs(app)
        val vin = p.getString(KEY_VIN, null).orEmpty()
        p.edit()
            .putLong(KEY_STOPPED, now)
            .putString(KEY_STOPPED_VIN, vin)
            .remove(KEY_STARTED)
            .remove(KEY_VIN)
            .remove(KEY_LAST)
            .remove(KEY_UNKNOWN)
            .remove(KEY_FAILS)
            .apply()
        cancelAlarm(app)
        stopService(app)
    }

    /** 登出 / 换账户：连 latch 一起清，别把上一辆车的收尾状态留给下一辆。 */
    fun clear(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().clear().apply()
        cancelAlarm(app)
        stopService(app)
    }

    /**
     * 用户把 App 切回前台时接回服务（[android.app.Activity.onResume]）。
     * 从后台启动 `dataSync` 前台服务会被系统拒，所以这一步必须在前台做。
     */
    fun resumeIfWatching(context: Context) {
        val app = context.applicationContext
        if (!isWatching(app)) return
        if (expired(app)) {
            stop(app)
            return
        }
        startService(app)
        scheduleNext(app)
    }

    /** 开机 / 升级：只排闹钟，不起前台服务（BOOT_COMPLETED 禁起 dataSync）。 */
    fun resumeAlarmOnly(context: Context) {
        val app = context.applicationContext
        if (!isWatching(app)) return
        if (expired(app)) {
            stop(app)
            return
        }
        // 开机后系统还在忙，给 30 秒缓冲再采第一帧
        scheduleNext(app, 30_000L)
    }

    // ---------------- 前台服务 ----------------

    fun startService(context: Context) {
        val app = context.applicationContext
        runCatching {
            val intent = Intent(app, ChargeWatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }
        // 后台启动被系统拒绝时闹钟链路仍然完整，服务会在下次 onResume 接回来
    }

    fun stopService(context: Context) {
        val app = context.applicationContext
        runCatching { app.stopService(Intent(app, ChargeWatchService::class.java)) }
    }

    // ---------------- 闹钟（兜底） ----------------

    fun scheduleNext(context: Context, delayMs: Long = INTERVAL_MS) {
        val app = context.applicationContext
        val am = alarm(app) ?: return
        val at = System.currentTimeMillis() + delayMs.coerceAtLeast(5_000L)
        val pi = pending(app)
        if (canScheduleExact(app)) {
            if (runCatching {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }.isSuccess
            ) return
        }
        val ok = runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.isSuccess
        if (!ok) runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun cancelAlarm(context: Context) {
        val app = context.applicationContext
        runCatching { alarm(app)?.cancel(pending(app)) }
    }

    /** Android 12+ 精确闹钟要用户授权；13 起默认给，14 起新装默认又不给。 */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = alarm(context) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    private fun alarm(context: Context): AlarmManager? =
        runCatching { context.getSystemService(AlarmManager::class.java) }.getOrNull()

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ChargeSampleReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
