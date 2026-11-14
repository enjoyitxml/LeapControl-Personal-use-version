package com.leapcontrol.app.widget

import android.content.Context

/**
 * 小组件「哨兵模式」按键的本地开合态。
 *
 * ## 为什么不塞进 QuickTiles
 * 控制中心磁贴固定 8 块（含 `ic_tile_*`），哨兵**不是**一块磁贴：
 * `QuickTiles.ALL` 里没有它，也就没有对应的 `TileSpec` 可传给
 * `QuickTiles.setOn / lastActionAt`。但小组件这条链路仍然需要同一套东西：
 * - 8 秒意图窗口（`QuickTiles.ACTION_GRACE_MS`）里不能再下发一次，
 *   否则「开完立刻关」两条指令互相抵消；
 * - 窗口内按键高亮得跟**本地意图**走，车端报文 5~15 秒才跟上；
 * - 车况读不到 `sentryOn` 时，得有个兜底值决定下一次点按的方向。
 *
 * 所以这里用同一份 prefs（`widget_action_state`）、同样的 `state_at` 语义单独记一个
 * 「sentry」状态，不新增磁贴、不改 `QuickTiles.ALL`、不碰 `ic_tile_*`。
 *
 * **指令本身不在这里判向**：`VehicleWidgetProvider` 一律走
 * `ControlActions.resolveCommand("sentry", status)`，与零控首页的哨兵按钮同源；
 * 本地态只在「车况读不到」和「意图窗口」这两处兜底。
 */
object WidgetSentryState {

    private const val PREFS = "widget_action_state"
    private const val KEY_ON = "state_sentry"
    private const val KEY_AT = "state_at_sentry"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本地记下的开合态；默认 false = 没开过（与 `QuickTiles.isOn` 同口径）。 */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    /** 上一次由用户成功下发哨兵指令的时刻；0 = 从没发过。 */
    fun lastActionAt(context: Context): Long = prefs(context).getLong(KEY_AT, 0L)

    /**
     * 写开合态。
     *
     * [fromUser] 与 `QuickTiles.setOn` 完全同口径：只有用户点击才刷新时间戳，
     * 车况校准（`false`）只改状态，不然一次校准会让之后 8 秒的窗口误判成「刚点过」。
     */
    fun setOn(context: Context, on: Boolean, fromUser: Boolean = true) {
        val editor = prefs(context).edit().putBoolean(KEY_ON, on)
        if (fromUser) editor.putLong(KEY_AT, System.currentTimeMillis())
        editor.apply()
    }

    /**
     * 用车况校准本地态 —— **只校显示 / 判向依据，本身不发指令**。
     *
     * 三条纪律与 `QuickTiles.calibrateFromStatus` 一致：
     * 信号读不到（null）不动、8 秒意图窗口内不动、校准不算用户动作。
     */
    fun calibrateFromStatus(context: Context, sentryOn: Boolean?) {
        if (sentryOn == null) return
        if (WidgetTapRules.withinIntentWindow(lastActionAt(context), System.currentTimeMillis())) return
        setOn(context, sentryOn, fromUser = false)
    }
}
