package com.leapcontrol.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 升级后把定时空调的联动闹钟重新排上。
 *
 * 系统在重启和覆盖安装时会清掉所有闹钟，不清的话用户第二天早上的
 * 「到点关窗 + 座椅加热」就静默消失了 —— 而且没有任何提示，很难察觉。
 *
 * 这里只排闹钟，不发任何请求：开机时车还没联网，拉车况只会多一条失败日志。
 */
class ScheduledAcLinkageBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { ScheduledAcLinkageScheduler.reschedule(context.applicationContext) }
    }
}
