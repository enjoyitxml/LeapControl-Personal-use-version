package com.leapcontrol.app.widget

import android.content.Context

/**
 * 小组件右上角的「刚刚控车的结果」短时提示。
 *
 * ## 为什么需要这一层
 * 车端 T-Box 唤醒 + 执行要 5~15 秒，报文回得更慢。控车成功后如果立刻回到
 * 「12:30 更新」，用户看到的就是「点了上锁，卡片什么也没变」——
 * 这正是 2026-11-05 那条反馈。所以把结果先记在这里，卡片右上角直接说
 * 「车辆已上锁 / 空调已开启」，等真实车况跟上再自然交回去。
 *
 * ## 为什么用 prefs 而不是内存字段
 * 小组件的渲染跑在桌面进程发起的广播里，进程随时可能被回收；写进 prefs 之后
 * 即使这一帧之后进程没了，下次重画（桌面定时更新、改尺寸）仍能带上这条结果。
 *
 * 只放**一条**：控车是串行的人机动作，新的结果覆盖旧的比排队更符合直觉。
 */
object WidgetFlash {

    private const val PREFS = "widget_flash"
    private const val KEY_TEXT = "text"
    private const val KEY_AT = "at"
    private const val KEY_OK = "ok"
    private const val KEY_HINT = "hint"
    private const val KEY_TTL = "ttl"

    /**
     * 结果在右上角停留多久。
     *
     * 2 秒：够用户看清「车辆已上锁」这一句，随即交回「HH:mm 更新」/
     * 充电中 / 未锁车这些真正该常显的车况。用户明确要求「过一会儿就切回来，
     * 不要一直挂着上一轮的结果」—— 之前 20 秒太长，看起来像卡片卡住了。
     *
     * 到期后必须**主动重画一帧**（见 `VehicleWidgetProvider.scheduleFlashExpiry`）：
     * 这里只是让 [read] 返回 null，桌面不重画的话，那行字会一直停在旧结果上，
     * 直到下次有事件（刷新、控车、改尺寸）才更新。
     */
    const val TTL_MS = 2_000L

    /** 「车端确实没动」这类结论要让人看清，多留一会儿（仍是短提示，不是常显）。 */
    const val TTL_WARN_MS = 5_000L

    /**
     * @param text 要显示的完成式短句，如「车辆已解锁」
     * @param ok true = 主色；false = 警示色（车端明确没动 / 没发出去）
     * @param hint 这次动作让**哪个按键**应该亮起来，取值见 [HINT_UNLOCK] 等。
     *   有了它，右上角写「空调已开启」的同一帧里，空调键也已经是高亮的 ——
     *   否则要等 2~3 秒后的车况回读，中间那一下文字和按键互相矛盾。
     * @param ttlMs 这条提示停留多久（默认 [TTL_MS]；明确没成的那种用 [TTL_WARN_MS]）
     */
    data class Flash(
        val text: String,
        val ok: Boolean,
        val hint: String?,
        val ttlMs: Long = TTL_MS,
    )

    /** 结果意图：车已解锁 → 解锁键亮。 */
    const val HINT_UNLOCK = "unlock"

    /** 结果意图：车已上锁 → 上锁键**不亮**（见 VehicleWidget.bindLive 的高亮规则）。 */
    const val HINT_LOCK = "lock"
    const val HINT_AC_ON = "acOn"
    const val HINT_AC_OFF = "acOff"
    const val HINT_TRUNK_OPEN = "trunkOpen"
    const val HINT_TRUNK_CLOSE = "trunkClose"

    // 车窗四档循环：微开 / 半开 / 全开 → 车窗键亮；关 → 灭。
    // 取值与 Commands.build 的指令名一致：write() 里 hint 直接传 command。
    const val HINT_WINDOW_VENT = "windowVent"
    const val HINT_WINDOW_HALF = "windowOpen"
    const val HINT_WINDOW_FULL = "windowFull"
    const val HINT_WINDOW_CLOSE = "windowClose"

    const val HINT_BATTERY_PREHEAT_ON = "batteryPreheat"
    const val HINT_BATTERY_PREHEAT_OFF = "batteryPreheatOff"
    const val HINT_SUNSHADE_OPEN = "sunshadeOpen"
    const val HINT_SUNSHADE_CLOSE = "sunshadeClose"

    // 哨兵与首页同一组指令名：resolveCommand("sentry", ...) 只会给出这两个。
    const val HINT_SENTRY_ON = "sentryOn"
    const val HINT_SENTRY_OFF = "sentryOff"

    fun write(
        context: Context,
        text: String,
        ok: Boolean,
        hint: String? = null,
        ttlMs: Long = TTL_MS,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TEXT, text)
            .putLong(KEY_AT, System.currentTimeMillis())
            .putBoolean(KEY_OK, ok)
            .putString(KEY_HINT, hint)
            .putLong(KEY_TTL, ttlMs)
            .apply()
    }

    /** 读当前有效的那条；过期就当没有（顺便不清理，下次写入会覆盖）。 */
    fun read(context: Context): Flash? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        val at = prefs.getLong(KEY_AT, 0L)
        val ttl = prefs.getLong(KEY_TTL, TTL_MS).takeIf { it > 0 } ?: TTL_MS
        if (at == 0L || System.currentTimeMillis() - at > ttl) return null
        return Flash(text, prefs.getBoolean(KEY_OK, true), prefs.getString(KEY_HINT, null), ttl)
    }

    /**
     * 这条提示还是不是「我写的那一条」。
     *
     * 控车结果要等车况回读才敢下结论，中间用户完全可能又点了别的键 ——
     * 后台那条核对协程醒过来时，卡片上早就换成新一轮的结果了。
     * 没有这道判断，前一次的结论会把后一次的动作覆盖掉（连点两个键时提示乱跳）。
     *
     * 刻意**不看 TTL**：提示到期后只是不再显示，写入记录还在，那句结论依然属于这次动作；
     * 用 [read] 判断的话，2 秒一过就永远 false，事后求证根本没法把结论写回来。
     */
    fun stillCurrent(context: Context, text: String, hint: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TEXT, null) ?: return false
        return stored == text && prefs.getString(KEY_HINT, null) == hint
    }

    /** 用户主动点了刷新，说明想看真实车况 —— 结果提示立刻让位。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
