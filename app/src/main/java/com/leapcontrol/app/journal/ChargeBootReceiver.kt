package com.leapcontrol.app.journal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 升级后把「还在监测中的那次充电」接上。
 *
 * 与 `TripBootReceiver` 同一条规矩：**只排闹钟，不起前台服务** —— Android 15 起
 * 明确禁止从 `BOOT_COMPLETED` 启动 `dataSync` 类型的前台服务，硬起会被系统拒掉。
 * 闹钟路径能独立采样；等用户下次打开零控，`ChargeWatch.resumeIfWatching` 再把服务接回来。
 */
class ChargeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        ChargeWatch.resumeAlarmOnly(context)
    }
}
