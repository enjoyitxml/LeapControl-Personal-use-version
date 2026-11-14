package com.leapcontrol.app.widget

import com.leapcontrol.app.tiles.QuickTiles

/**
 * 小组件点按的两条纯规则（只吃时间戳，便于单测）。
 *
 * 车端 T-Box 唤醒 + 执行要 5~15 秒，而卡片要在 2~3 秒内给答复（见 [WidgetFlash.TTL_MS]），
 * 两者之间的那 8 秒（[QuickTiles.ACTION_GRACE_MS]）是一个「意图已发出、车况还没跟上」的窗口。
 * 2026-11-11 排掉的两条隐患都出在这个窗口里：
 *
 * 1. **窗口内再点同一个开关键**：下发成功那一刻本地开合态已经按意图翻过去了，
 *    再按「取反」判方向就得到**反方向**指令，「开完立刻关」两条互相抵消 ——
 *    用户看到的是「按了没反应」，实际发了两次。这类点击要直接拦掉，见
 *    `VehicleWidgetProvider.execute` 开头那道拦截。
 * 2. **窗口内重画**：车况里还是上一轮的旧值，按键高亮会被打回原样；用户以为没生效又点一下，
 *    正好踩上面那条。所以高亮在窗口内跟**本地意图**走，见 `VehicleWidget.bindLive` 的 `normallyOn`。
 *
 * 判据只有一条：[withinIntentWindow]。两处共用同一个窗口，别另写一份阈值。
 */
internal object WidgetTapRules {

    /**
     * 这次动作是否还落在「上一次成功下发」的意图窗口里。
     *
     * @param lastActionAt 上一次**成功下发**的时刻（`QuickTiles.lastActionAt`）；0 = 从没发过
     * @param now 当前时刻（毫秒）
     * @return true = 还在窗口内：车端大概率没执行完，方向不可判、高亮也该跟意图
     *
     * 边界取左闭右开 `[0, ACTION_GRACE_MS)`：正好 8 秒整算窗口外，与
     * `VehicleWidgetProvider.execute` 里 `elapsed < ACTION_GRACE_MS` 的写法保持一致。
     * 时钟回拨（差值为负）一律 false —— 宁可多拉一次车况，也不凭空放行一条指令。
     */
    fun withinIntentWindow(lastActionAt: Long, now: Long): Boolean {
        if (lastActionAt == 0L) return false
        val elapsed = now - lastActionAt
        return elapsed in 0L until QuickTiles.ACTION_GRACE_MS
    }
}
