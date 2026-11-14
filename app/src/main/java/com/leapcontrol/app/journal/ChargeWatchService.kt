package com.leapcontrol.app.journal

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

/**
 * 充电监测期间的常驻前台服务：**保活 + 每 1 分钟采一次车况**。
 *
 * 与 `TripRecordService` 同一套骨架，但生命周期完全独立：用户看不见任何「开始」按钮，
 * 是 [ChargeWatch] 在某一帧车况里发现「正在充电」时自动拉起来的；捕捉到停止充电
 * 后 [ChargeWatch.stop] 会立刻把它停掉，通知也随之消失。
 *
 * 三层保活（见 [ChargeWatch] 注释）：本服务 → 精确闹钟兜底 → 电池优化白名单。
 * 服务被 OEM 清理时闹钟还在；撞上 Android 15 的 6 小时前台服务上限时，
 * [onTimeout] 主动收尾，之后靠闹钟继续采（闹钟不依赖服务活着）。
 */
class ChargeWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ID,
                    buildNotification(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(ID, buildNotification(this))
            }
        }.isSuccess
        if (!started) {
            // 没拿到前台身份就别继续跑：后台进程随时会被清，闹钟兜底更可靠
            stopSelf()
            return START_NOT_STICKY
        }
        // 进程被系统重建时 intent 可能是 null，标记在 prefs 里，照样接得上
        if (!ChargeWatch.isWatching(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureLoop()
        return START_STICKY
    }

    /** 用户在最近任务里划掉零控：不要停，充电还在继续。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    /** Android 15 对 `dataSync` 前台服务有 6 小时上限，到点主动停，交给闹钟继续采。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                if (!ChargeWatch.isWatching(this@ChargeWatchService)) {
                    stopSelf()
                    return@launch
                }
                if (ChargeWatch.expired(this@ChargeWatchService)) {
                    ChargeWatch.stop(this@ChargeWatchService)
                    stopSelf()
                    return@launch
                }
                val wait = ChargeWatch.msUntilNext(
                    now = System.currentTimeMillis(),
                    lastAt = ChargeWatch.lastSampleAt(this@ChargeWatchService),
                )
                if (wait > 0L) {
                    // 5 秒粒度：前台刷新刚采过、或闹钟刚采过，一轮内就能收敛
                    delay(wait.coerceAtMost(5_000L))
                    continue
                }
                when (ChargeSampleRunner.sample(this@ChargeWatchService)) {
                    ChargeSampleRunner.Result.STOPPED,
                    ChargeSampleRunner.Result.NO_SESSION -> {
                        ChargeWatch.stop(this@ChargeWatchService)
                        stopSelf()
                        return@launch
                    }
                    // 失败不退出：时间戳已写，下一个间隔再试，不会忙等
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
        return Notification.Builder(context, CHANNEL)
            .setContentTitle("正在记录充电")
            .setContentText("每分钟记一次电量与里程 · 充电结束后自动停止")
            .setSmallIcon(R.drawable.ic_charge_station)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private companion object {
        const val CHANNEL = "leapauto.charge.watching"
        private const val ID = 1041

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = runCatching {
                context.getSystemService(NotificationManager::class.java)
            }.getOrNull() ?: return
            val channel = NotificationChannel(
                CHANNEL,
                "充电记录",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "充电期间按固定间隔读取车况，充电结束后自动停止，不再后台读取。"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            runCatching { nm.createNotificationChannel(channel) }
        }
    }
}
