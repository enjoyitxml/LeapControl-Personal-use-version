package com.leapcontrol.app.trip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 行程采样的**闹钟兜底**（见 [TripSampling]）。
 *
 * 前台服务被 OEM 清理、或撞上 Android 15 的 6 小时上限之后，这条路径还在：
 * 闹钟醒了就自己拉一次车况、记一个点，再把下一次排上。不依赖服务活着。
 *
 * ⚠️ `goAsync` 的广播预算约 10 秒，请求超时卡在 8 秒；超时就按失败处理，
 * 下一个间隔再试 —— 不在这里做长重试，否则系统会把进程当 ANR 干掉。
 */
class TripSampleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TripSampling.ACTION_SAMPLE) return
        val app = context.applicationContext
        if (!TripSampling.isRecording(app)) {
            TripSampling.cancelAlarm(app)
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (TripSampleRunner.sample(app, timeoutMs = 8_000L)) {
                    TripSampleRunner.Result.CLOSED,
                    TripSampleRunner.Result.NO_SESSION -> TripSampling.stop(app)
                    // 跳过 = 前台服务刚采过，它还活着。闹钟这时退到两倍间隔，
                    // 少一次「叫醒进程→发现不用采」的空转；服务一死，下一次就轮到
                    // 闹钟真正采样，那时自动回到正常间隔（走 else 分支）。
                    TripSampleRunner.Result.SKIPPED ->
                        TripSampling.scheduleNext(app, TripSampling.intervalMs(app) * 2)
                    else -> TripSampling.scheduleNext(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
