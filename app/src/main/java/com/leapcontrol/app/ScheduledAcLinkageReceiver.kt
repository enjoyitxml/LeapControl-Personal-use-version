package com.leapcontrol.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * 定时空调到点联动的执行端。
 *
 * ## 到点只做「附带动作」，空调本身由车端开
 *
 * `cmdid=171` 已经把「到点开空调」交给车端了，车不需要 App 在线也会执行。
 * 这里补的是车端做不到的部分：关窗、关遮阳帘、座椅加热通风、方向盘加热。
 * 所以即使这个接收器没跑起来，空调照样开，只是少了这几项。
 *
 * ## 判据只用本地记录
 *
 * 到点时可能没网、可能 App 刚被系统杀掉重建。计划（开关 / 时间 / 重复日）
 * 在用户点「保存」那一刻就写进本地了，这里直接读本地，不先请求云端 ——
 * 否则一次网络抖动就等于整条联动失效。
 */
class ScheduledAcLinkageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduledAcLinkage"
        private const val CHANNEL_ID = "scheduled_ac_linkage"
        private const val NOTIFICATION_ID = 1711

        /**
         * 两条指令之间的间隔。
         *
         * 这些指令打同一个控车网关，连发很容易回「系统繁忙」（与 161 预热的
         * 1.5 秒间隔同一个原因）。1.2 秒是实测能稳定受理的最小值。
         */
        private const val COMMAND_GAP_MS = 1200L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledAcLinkageScheduler.ACTION) return
        val targetVin = intent.getStringExtra(ScheduledAcLinkageScheduler.EXTRA_VIN).orEmpty()
        val pendingResult = goAsync()
        Thread {
            try {
                run(context.applicationContext, targetVin)
            } catch (t: Throwable) {
                Log.w(TAG, "定时空调联动失败：${t.message}")
            } finally {
                runCatching { pendingResult.finish() }
            }
        }.start()
    }

    private fun run(app: Context, targetVin: String) {
        val store = SessionStore(app)
        val session = store.load() ?: run {
            Log.i(TAG, "未登录，跳过联动")
            return
        }
        val vin = targetVin.ifBlank { session.selectedVin.orEmpty() }
        if (vin.isBlank()) return

        // 用户可能在到点前把定时空调关了：本地记录是唯一可信来源
        if (!store.loadScheduledAcEnabled(vin)) {
            Log.i(TAG, "定时空调已关闭，跳过联动")
            ScheduledAcLinkageScheduler.cancel(app)
            return
        }
        val linkage = store.loadScheduledAcLinkage(vin)
        if (!linkage.anyEnabled) {
            ScheduledAcLinkageScheduler.cancel(app)
            return
        }

        // 今天不在重复周期里（车端会自己跳过，这边也不该发）
        val today = todayIsoDay()
        val days = ChargePlanCyclesHelper.parseToDaySet(store.loadScheduledAcDays(vin))
        if (!days.contains(today)) {
            Log.i(TAG, "今天不在重复周期内，只排下一次")
            ScheduledAcLinkageScheduler.reschedule(app, vin)
            return
        }

        val pin = store.loadOpPassword().orEmpty()
        if (pin.isBlank()) {
            // 没设控车密码连一条都发不出去：直接告诉他原因，别静默失败
            notify(app, listOf("未设置控车密码，联动已跳过"))
            ScheduledAcLinkageScheduler.reschedule(app, vin)
            return
        }

        val api = LeapmotorApi(session)
        val done = mutableListOf<String>()
        linkage.commands().forEachIndexed { index, name ->
            if (index > 0) Thread.sleep(COMMAND_GAP_MS)
            val command = runCatching { Commands.build(name) }.getOrNull() ?: return@forEachIndexed
            runCatching { api.sendControl(command, pin) }
                .onSuccess { done += command.label }
                .onFailure { Log.w(TAG, "${command.label} 下发失败：${it.message}") }
        }

        if (done.isNotEmpty()) notify(app, done)
        // 执行完立刻排下一天，不依赖用户再进来一次
        ScheduledAcLinkageScheduler.reschedule(app, vin)
    }

    /** Calendar 的 `DAY_OF_WEEK`（1=周日）→ 本项目口径（1=周一 … 7=周日）。 */
    private fun todayIsoDay(): Int {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (day == Calendar.SUNDAY) 7 else day - 1
    }

    /**
     * 执行完给一条通知。
     *
     * 不做静默执行：用户不在车边，只有这条通知能告诉他「刚才关窗成功了 / 座椅加热失败了」。
     * 通知失败不影响任何逻辑（Android 13 起没授权就发不出去）。
     */
    private fun notify(app: Context, actions: List<String>) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "定时空调联动",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "定时空调到点后自动关窗、开座椅加热等" }
                nm.createNotificationChannel(channel)
            }
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            val pi = launch?.let {
                PendingIntent.getActivity(
                    app,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_control)
                .setContentTitle("定时空调已联动")
                .setContentText(actions.joinToString("、"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(actions.joinToString("、")))
                .setAutoCancel(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }
    }
}
