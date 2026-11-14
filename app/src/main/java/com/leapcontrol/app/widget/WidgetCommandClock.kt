package com.leapcontrol.app.widget

import android.content.Context

/**
 * 小组件控车的**下发节拍**：记每条指令最后一次真正要发出去的时刻。
 *
 * ## 为什么另起一张表
 * [com.leapcontrol.app.tiles.QuickTiles] 里的 `state_at_<id>` 只覆盖四块开关磁贴
 * （空调 / 后备箱 / 电池预热 / 遮阳帘），车窗走 `WindowControl`、哨兵走 [WidgetSentryState]，
 * 而**解锁、上锁、鸣笛一处都没记**。于是「点完解锁，2 秒后又点开后备箱」这件事，
 * 在源码里完全不可见 ——
 *
 * 1. 8 秒意图窗口（[WidgetTapRules.withinIntentWindow]）只看**同一个**开关键，
 *    跨按键不设防，两条指令会并发冲向刚被唤醒的 T-Box；
 * 2. 第二条抢在第一条还在执行时到达，车端把后一条丢掉，**网关照样回受理码**，
 *    卡片于是写下「后备箱已开启」，车却一动不动（2026-11-13 用户反馈）。
 *
 * 所以这里补两件事：
 * - [waitBeforeSendMs]：任意两条指令之间至少隔 [MIN_GAP_MS]，第二条**等一下再发**；
 * - [withinRepeatWindow]：解锁 / 上锁 / 鸣笛这类瞬时动作，短时间内重复点不再发第二条。
 *
 * ## 为什么记「下发」而不是「点下」
 * 被二次确认拦下的那一下、被 8 秒窗口拦下的那一下，都没有真的发东西出去，
 * 不该占掉一个节拍 —— 否则「再点一次确认」的第二下会被硬等两秒才发。
 * 所以 [noteSend] 的位置在 `ConfirmGate` **之后**、发送之前那一刻。
 *
 * 至于「第一条还在判向、第二条就点下来」的空档，由
 * `VehicleWidgetProvider.execute` 里的 `justTapped`（8 秒内刚发过任意一条就跳过判向）
 * 负责收口：那条点击会直接走到 [noteSend]，不会并发。
 */
internal object WidgetCommandClock {

    private const val PREFS = "widget_command_clock"
    private const val KEY_LAST_SEND = "last_send_at"

    /**
     * 任意两条指令之间的最小间隔。
     *
     * 车端 T-Box 从收到一条指令到能稳稳接下一条，实测要 2 秒上下；再短就会出现
     * 「网关受理、车端丢弃」。取 2.5 秒：多等的这点时间只是把第二条推迟到第一条
     * 执行稳定之后，不会让用户觉得卡住 —— 等待期间卡片已经在写「后备箱调节中…」。
     */
    const val MIN_GAP_MS = 2_500L

    /**
     * 同一个瞬时动作的重复点击窗口。
     *
     * 只给解锁 / 上锁 / 鸣笛用：它们没有「反向」可切，连点两次就是**同一条指令发两遍**，
     * 白白多唤醒一次车。开关型不适用（那 8 秒窗口在 [VehicleWidgetProvider.execute] 里）。
     */
    const val REPEAT_WINDOW_MS = 2_500L

    /** 任意动作上一次下发的时刻（0 = 从没发过）。 */
    fun lastSendAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SEND, 0L)

    /** 某个动作上一次下发的时刻。 */
    fun lastSendAt(context: Context, action: String): Long =
        prefs(context).getLong(keyOf(action), 0L)

    /**
     * 记下「这一条马上要发出去」，返回**还需要等多久**。
     *
     * 读与写的顺序不能反：先算等待量（基于上一条的时间），再覆盖成本次的时间。
     * 反过来的话等待量永远是 0，串行门等于没有。
     */
    fun noteSend(context: Context, action: String, now: Long = System.currentTimeMillis()): Long {
        val wait = waitBeforeSendMs(context, now)
        prefs(context).edit()
            .putLong(KEY_LAST_SEND, now)
            .putLong(keyOf(action), now)
            .apply()
        return wait
    }

    /**
     * 距「可以下发下一条」还要等多久（0 = 立刻可以）。
     *
     * 用 `in 0 until MIN_GAP_MS` 而不是 `<`：正好 2.5 秒算窗口外，与
     * [WidgetTapRules.withinIntentWindow] 的左闭右开写法保持一致。
     */
    fun waitBeforeSendMs(context: Context, now: Long = System.currentTimeMillis()): Long =
        gapRemainingMs(lastSendAt(context), now)

    /** 这个瞬时动作是否刚发过（窗口内不再下发）。 */
    fun withinRepeatWindow(
        context: Context,
        action: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = elapsedInWindow(lastSendAt(context, action), now, REPEAT_WINDOW_MS)

    // ---------------- 纯计算（单测直接钉口径，见 WidgetCommandClockTest） ----------------

    /**
     * 纯计算：距下一次可下发还剩多少毫秒。
     *
     * 时钟回拨（差值为负）一律按「刚发过」处理 —— 宁可多等一会儿，
     * 也不让两条指令挤在一起（挤在一起的那一条会被车端丢掉）。
     */
    fun gapRemainingMs(lastSendAt: Long, now: Long): Long {
        if (lastSendAt == 0L) return 0L
        val elapsed = now - lastSendAt
        if (elapsed < 0L) return MIN_GAP_MS
        if (elapsed >= MIN_GAP_MS) return 0L
        return MIN_GAP_MS - elapsed
    }

    /** 纯计算：距离某次动作是否还在 [windowMs] 窗口内（左闭右开）。 */
    fun elapsedInWindow(at: Long, now: Long, windowMs: Long): Boolean {
        if (at == 0L) return false
        return now - at in 0 until windowMs
    }

    private fun keyOf(action: String): String = "at_${action.substringAfterLast('.')}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
