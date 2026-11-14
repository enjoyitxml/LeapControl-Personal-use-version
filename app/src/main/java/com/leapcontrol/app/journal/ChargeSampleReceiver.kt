package com.leapcontrol.app.journal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 充电采样的**闹钟兜底**（见 [ChargeWatch]）。
 *
 * 前台服务被 OEM 清理、或撞上 Android 15 的 6 小时上限之后，这条路径还在：
 * 闹钟醒了就自己拉一次车况、记一次充电情况，再把下一次排上。不依赖服务活着。
 *
 * 最要紧的一条：这一帧如果读到「已经停止充电」，`refreshStatus` → `reschedule`
 * 已经把窗口、闹钟、服务全停了，这里**绝不能再排下一次**，否则就成了用户说的
 * 「一直后台耗电」。所以只有仍在监测中才续闹钟。
 *
 * ⚠️ `goAsync` 的广播预算约 10 秒，请求超时卡在 8 秒；超时按失败处理，
 * 下一个间隔再试 —— 不在这里做长重试，否则系统会把进程当 ANR 干掉。
 */
class ChargeSampleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ChargeWatch.ACTION) return
        val app = context.applicationContext
        if (!ChargeWatch.isWatching(app)) {
            ChargeWatch.cancelAlarm(app)
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (ChargeSampleRunner.sample(app, timeoutMs = 8_000L)) {
                    ChargeSampleRunner.Result.STOPPED,
                    ChargeSampleRunner.Result.NO_SESSION -> ChargeWatch.stop(app)
                    // 跳过 = 前台服务刚采过，它还活着。闹钟退到两倍间隔，
                    // 少一次「叫醒进程→发现不用采」的空转；服务一死就轮到闹钟真正采样。
                    ChargeSampleRunner.Result.SKIPPED ->
                        ChargeWatch.scheduleNext(app, ChargeWatch.INTERVAL_MS * 2)
                    else -> if (ChargeWatch.isWatching(app)) ChargeWatch.scheduleNext(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
