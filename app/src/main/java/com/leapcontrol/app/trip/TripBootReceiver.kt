package com.leapcontrol.app.trip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 升级后把「还在记录中」的那一趟接上。
 *
 * 这里**只排闹钟，不起前台服务**：Android 15 起明确禁止从 `BOOT_COMPLETED`
 * 启动 `dataSync` 类型的前台服务，硬起会被系统拒掉（还会留下一条失败记录）。
 * 闹钟路径已经能独立采样；等用户下次打开零控，前台身份合法了再把服务接回来。
 */
class TripBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!TripSampling.isRecording(context)) return
        // 开机后系统还在忙，给 30 秒缓冲再采第一个点
        TripSampling.scheduleNext(context, 30_000L)
    }
}
