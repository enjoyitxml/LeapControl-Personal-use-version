package com.leapcontrol.app.trip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.leapcontrol.app.MainActivity
import com.leapcontrol.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * 行程记录期间的常驻前台服务：**保活 + 按间隔采样**。
 *
 * 为什么不是无障碍服务：无障碍要用户去系统设置里手动打开、还会挂一条「可读取屏幕内容」
 * 的安全告警，而它带来的进程优先级（PERCEPTIBLE）**低于**前台服务（FOREGROUND）。
 * 我们的需求只是「进程别被清掉、按时跑一次网络请求」，`dataSync` 前台服务正好覆盖，
 * 且用户点「开始记录」时就顺势起来了，不用额外授权。
 *
 * 三层保活（见 [TripSampling] 注释）：本服务 → 闹钟兜底 → 电池优化白名单。
 * 服务被 OEM 清理时闹钟还在；撞上 Android 15 的 6 小时前台服务上限时，
 * [onTimeout] 主动收尾，之后也靠闹钟继续采。
 */
class TripRecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, notification)
            }
        }.isSuccess
        if (!started) {
            // 没拿到前台身份就绝不能继续跑：后台进程随时会被清，采点也不稳
            stopSelf()
            return START_NOT_STICKY
        }
        // 进程被系统重建时 intent 可能是 null，标记在 prefs 里，照样接得上
        if (!TripSampling.isRecording(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureLoop()
        return START_STICKY
    }

    /**
     * 用户在最近任务里划掉零控：**不要停**。
     *
     * 这恰恰是最常见的场景 —— 上车点完「开始记录」就把手机锁屏揣兜里。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Android 15 对 `dataSync` 前台服务有 6 小时上限，到点系统会回调这里。
     *
     * 主动停掉服务，交给闹钟继续采（闹钟不依赖服务活着）。不这么做的话系统会直接
     * 抛 ForegroundServiceDidNotStopInTimeException，把整个进程干掉。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                if (!TripSampling.isRecording(this@TripRecordService)) {
                    stopSelf()
                    return@launch
                }
                val wait = TripSampling.msUntilNext(
                    now = System.currentTimeMillis(),
                    lastAt = TripSampling.lastSampleAt(this@TripRecordService),
                    intervalMs = TripSampling.intervalMs(this@TripRecordService),
                )
                if (wait > 0L) {
                    // 5 秒粒度：改间隔 / 手动补采都能在一轮内生效，又不会空转
                    delay(wait.coerceAtMost(5_000L))
                    continue
                }
                when (TripSampleRunner.sample(this@TripRecordService)) {
                    TripSampleRunner.Result.CLOSED,
                    TripSampleRunner.Result.NO_SESSION -> {
                        TripSampling.stop(this@TripRecordService)
                        stopSelf()
                        return@launch
                    }
                    // 失败不退出：下一个间隔再试（时间戳已写，不会忙等）
                    else -> Unit
                }
                refresh()
                delay(1_000L)
            }
        }
    }

    private fun refresh() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(ID, buildNotification(this))
        }
    }

    private fun buildNotification(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val seconds = TripSampling.intervalSeconds(context)
        val points = TripTracker.flow.value?.open?.size ?: 0
        val detail = if (points > 0) {
            "每 ${TripSampling.label(seconds)}采一个点 · 已采 $points 个点"
        } else {
            "每 ${TripSampling.label(seconds)}采一个点"
        }
        return Notification.Builder(context, CHANNEL)
            .setContentTitle("正在记录行程")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_nav_map)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL = "leapauto.trip.recording"
        private const val ID = 1037

        /** 只为了让「改间隔」能立刻刷新通知文案，没有别的用途。 */
        @Volatile
        private var instance: WeakReference<TripRecordService>? = null

        fun refreshNotification(context: Context) {
            runCatching { instance?.get()?.refresh() }
        }

        private fun ensureChannel(context: Context) {
            val nm = runCatching {
                context.getSystemService(NotificationManager::class.java)
            }.getOrNull() ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL,
                "行程记录",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "记录行程期间按设定的间隔读取车辆位置。结束记录后自动消失。"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            runCatching { nm.createNotificationChannel(channel) }
        }
    }
}
