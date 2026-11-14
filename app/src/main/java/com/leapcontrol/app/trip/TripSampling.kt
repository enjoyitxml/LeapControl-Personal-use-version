package com.leapcontrol.app.trip

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlin.math.abs

/**
 * 行程记录期间的**后台定时采样**：设置、录制标记、闹钟排程。
 *
 * ## 为什么需要它
 *
 * 上一版只在「拿车况」时搭顺风车记点（进首页、下拉刷新、小组件刷新）。用户真跑一趟、
 * 手机在兜里，中间一个点都没有，轨迹就剩下家和公司两个点 —— 这不是「记录轨迹」。
 * 现在用户点了「开始记录」之后，零控自己按固定间隔去读一次车况。
 *
 * ## 三层保险（按强度从高到低）
 *
 * 1. [TripRecordService]：`dataSync` 前台服务常驻，间隔就是用户设的
 *    （30 秒 / 45 秒 / 1 / 2 / 3 / 5 分钟，默认 45 秒）。
 *    前台服务进程优先级是 FOREGROUND，比无障碍服务（PERCEPTIBLE）还高一级，
 *    而且不需要用户去系统设置里开任何敏感权限 —— 这是比无障碍更好的保活手段。
 * 2. [TripSampleAlarmReceiver]：`AlarmManager` 精确闹钟。服务被 OEM 清掉 / 撞上
 *    Android 15 的 6 小时前台服务上限时，闹钟照样把进程叫起来采一次。
 * 3. 电池优化白名单：用户点了「允许后台运行」后，Doze 下网络与唤醒都不再受限。
 *
 * ## 费不费车电
 *
 * 会。每次采样都要唤醒一次 T-Box（和打开 App 刷一次车况同价）。所以只在
 * **用户明确开始记录**期间发生，结束 / 暂停 / 收尾立刻停；默认间隔 45 秒
 * （用户 2026-11-12 指定），嫌费电可以调到 3 / 5 分钟。
 * 这条与「平时别频繁拉车况」的约定不冲突：那说的是平时，这里是用户明确要求记录。
 */
object TripSampling {

    /**
     * 可选间隔（**秒**）。2026-11-12 用户定：30 秒 / 45 秒 / 1 / 2 / 3 / 5 分钟，
     * 默认 45 秒 —— 车端报文本身约 60 秒一帧，45 秒能在下一帧到达前刚好赶上一轮。
     */
    val INTERVALS_SEC = listOf(30, 45, 60, 120, 180, 300)

    /** 默认 45 秒（用户指定）。 */
    const val DEFAULT_INTERVAL_SEC = 45

    /** 采样广播 action（与 [TripSampleAlarmReceiver] 对齐）。 */
    const val ACTION_SAMPLE = "com.leapcontrol.app.action.TRIP_SAMPLE"

    private const val PREFS = "trip_sampling"
    private const val KEY_INTERVAL = "interval_sec"
    private const val KEY_VIN = "recording_vin"
    private const val KEY_LAST = "last_sample_at"
    private const val REQUEST_CODE = 0x7A1D

    // ---------------- 纯逻辑（可单测） ----------------

    /** 把任意秒数归到最近的可选档；超出范围的按最近端点算。 */
    fun normalize(seconds: Int): Int =
        INTERVALS_SEC.minByOrNull { abs(it - seconds) } ?: DEFAULT_INTERVAL_SEC

    /** 界面与通知上的统一说法。别在别处手写「45 秒」。 */
    fun label(seconds: Int): String {
        val s = normalize(seconds)
        return if (s < 60) "$s 秒" else "${s / 60} 分钟"
    }

    fun intervalMsOf(seconds: Int): Long = normalize(seconds) * 1_000L

    /**
     * 距离下一次采样还差多少毫秒（0 = 该采了）。
     *
     * 两条触发路径（服务循环 / 闹钟）共用它做去重：服务活着时闹钟响，
     * 距上次采样还不够一个间隔的 80% 就直接跳过，只重排下一次。
     */
    fun msUntilNext(now: Long, lastAt: Long, intervalMs: Long): Long {
        if (lastAt <= 0L) return 0L
        if (intervalMs <= 0L) return 0L
        // 系统时间被往回调过（lastAt 落在「未来」）：别让采样一直等下去
        if (lastAt > now) return 0L
        return (intervalMs - (now - lastAt)).coerceAtLeast(0L)
    }

    /** 距上次采样是否已经够久（含 80% 容差：定时器早几秒不该白跑一趟）。 */
    fun isDue(now: Long, lastAt: Long, intervalMs: Long): Boolean =
        lastAt <= 0L || lastAt > now || now - lastAt >= intervalMs * 4 / 5

    // ---------------- 用户设置 ----------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun intervalSeconds(context: Context): Int =
        normalize(prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SEC))

    fun intervalMs(context: Context): Long = intervalMsOf(intervalSeconds(context))

    /** 改间隔：正在记录时立刻按新间隔重排闹钟，服务下一轮自己会读到新值。 */
    fun setIntervalSeconds(context: Context, seconds: Int) {
        val app = context.applicationContext
        val value = normalize(seconds)
        prefs(app).edit().putInt(KEY_INTERVAL, value).apply()
        if (isRecording(app)) {
            scheduleNext(app)
            TripRecordService.refreshNotification(app)
        }
    }

    // ---------------- 录制标记 ----------------

    /** 正在记录的那辆车；null = 没在记录。落盘是为了进程被杀 / 重启后还能接上。 */
    fun recordingVin(context: Context): String? =
        prefs(context).getString(KEY_VIN, null)?.takeIf { it.isNotBlank() }

    fun isRecording(context: Context): Boolean = recordingVin(context) != null

    fun isRecording(context: Context, vin: String): Boolean =
        vin.isNotBlank() && recordingVin(context) == vin

    fun lastSampleAt(context: Context): Long = prefs(context).getLong(KEY_LAST, 0L)

    /** 记下「刚刚试过采一次」。失败也写：否则会 5 秒一次地反复打网关。 */
    fun markSampleAttempt(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST, at).apply()
    }

    // ---------------- 启停 ----------------

    /**
     * 开始记录：写标记 → 起前台服务 → 排第一次闹钟。
     *
     * 用户刚点「开始记录」的那一帧已经由 [TripTracker.start] 记下了，所以这里把
     * 「上次采样」也设成现在 —— 第一个后台点在一个间隔之后，不会刚点完就又拉一次车况。
     */
    fun begin(context: Context, vin: String) {
        if (vin.isBlank()) return
        val app = context.applicationContext
        prefs(app).edit()
            .putString(KEY_VIN, vin)
            .putLong(KEY_LAST, System.currentTimeMillis())
            .apply()
        startService(app)
        scheduleNext(app)
    }

    /** 停止记录：清标记 → 撤闹钟 → 停服务。结束 / 暂停 / 自动收尾都走这里。 */
    fun stop(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().remove(KEY_VIN).remove(KEY_LAST).apply()
        cancelAlarm(app)
        stopService(app)
    }

    /** 只在真的在记录时才停：调用方不必自己判断。 */
    fun stopIfRecording(context: Context) {
        if (isRecording(context)) stop(context)
    }

    // ---------------- 前台服务 ----------------

    fun startService(context: Context) {
        val app = context.applicationContext
        runCatching {
            val intent = Intent(app, TripRecordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }
    }

    fun stopService(context: Context) {
        val app = context.applicationContext
        runCatching { app.stopService(Intent(app, TripRecordService::class.java)) }
    }

    // ---------------- 闹钟（兜底） ----------------

    /**
     * 排下一次采样。有精确闹钟权限就用 `setExactAndAllowWhileIdle`（Doze 下也能准时），
     * 没有就退 `setAndAllowWhileIdle`（大约 9 分钟一次），再退普通 `set`。
     * 晚一点总比没有强 —— 前台服务活着时本来也不靠闹钟。
     */
    fun scheduleNext(context: Context, delayMs: Long = intervalMs(context)) {
        val app = context.applicationContext
        val am = alarm(app) ?: return
        val at = System.currentTimeMillis() + delayMs.coerceAtLeast(5_000L)
        val pi = pending(app)
        if (canScheduleExact(app)) {
            val ok = runCatching {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }.isSuccess
            if (ok) return
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
        Intent(context, TripSampleAlarmReceiver::class.java).setAction(ACTION_SAMPLE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ---------------- 电池优化白名单 ----------------

    /** 已经不受电池优化限制（Doze 下也能按时唤醒）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = runCatching {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * 弹系统「允许后台运行」的授权页。
     *
     * 直接请求白名单（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）是最短路径，
     * 少数 ROM 没有这个 Activity 时退回「电池优化设置列表」，再不行就落到应用详情页 ——
     * 让用户至少能找到地方手动加，而不是点了没反应。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val app = context.applicationContext
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { app.startActivity(direct) }.isSuccess) return
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { app.startActivity(list) }.isSuccess) return
        val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(detail) }
    }
}
