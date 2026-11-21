package com.leapcontrol.app

import android.content.Context

/**
 * 哨兵模式方案：**原厂** / **副厂**（后加装）。
 *
 * ## 为什么要分两种
 * 后加装的哨兵（第三方记录仪 / 安防盒子）能收到 `sentryOn` 这条指令、也能真的开始工作，
 * 但它**不会把「已开启」写进车端信号**——车况里的 `sentryOn` 永远读不到（或恒为 false）。
 * 于是原厂那套「等车端回执 + 读车况求证」在它身上必然失败：用户点开哨兵，App 只能写
 * 「开哨兵已下发」，右上角也不会出现「哨兵已开启」，看着像没生效。
 *
 * 所以副厂方案把「开没开」这件事**记在本机**：指令发出去就算开启，下一次点击就是关闭，
 * 首页右上角与桌面小组件一律按本地状态显示，不再去向车端求证。
 *
 * 原厂方案（默认）走的就是原来那条路，一行都不改。
 */
enum class SentryScheme {
    /** 原厂：等网关回执 + 读车况求证（默认）。 */
    FACTORY,

    /** 副厂：拿不到车端回执，开合状态存在本地，下发即算成功。 */
    AFTERMARKET,
}

/**
 * 「副厂方案第一次开哨兵」那次说明只弹一次。
 *
 * 规则本身不会变（状态存本地、再点即关），每次开启都弹一遍只会让人多点一下；
 * 换回原厂再切回副厂也不重置 —— 用户已经知道了。
 */
object AftermarketSentryTip {

    private const val PREFS = "sentry_scheme"
    private const val KEY = "aftermarket_tip_shown"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isShown(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    fun markShown(context: Context) {
        prefs(context).edit().putBoolean(KEY, true).apply()
    }
}

/** 方案存哪儿。独立 prefs：不跟车况缓存、样式设置混在一起，清缓存时也不会被顺手带走。 */
object SentrySchemeStore {

    private const val PREFS = "sentry_scheme"
    private const val KEY = "scheme"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): SentryScheme =
        when (prefs(context).getString(KEY, null)) {
            SentryScheme.AFTERMARKET.name -> SentryScheme.AFTERMARKET
            // 没存过 / 存坏了：按原厂处理。宁可等回执，也不能凭空把「已开启」写给原厂车
            else -> SentryScheme.FACTORY
        }

    fun set(context: Context, scheme: SentryScheme) {
        prefs(context).edit().putString(KEY, scheme.name).apply()
    }

    fun isAftermarket(context: Context): Boolean = get(context) == SentryScheme.AFTERMARKET
}

/**
 * 副厂方案的纯逻辑（不碰 Context，单测可直接钉）。
 *
 * 只覆盖「副厂怎么表现」；原厂的判定仍留在各处原有代码里（[com.leapcontrol.app.ui.ControlActions]
 * 与小组件的方向判定），保证选原厂时行为与以前完全一致。
 */
object SentrySchemeRules {

    /** 副厂方案下才把状态存在本地；原厂一律返回 false，调用方据此走老路。 */
    fun usesLocalState(scheme: SentryScheme): Boolean = scheme == SentryScheme.AFTERMARKET

    /**
     * 显示用的「哨兵开着没有」。副厂**只看本地**：车端那个信号本来就拿不到，
     * 掺进来只会让界面时不时闪回「没开」。
     */
    fun onForDisplay(scheme: SentryScheme, statusOn: Boolean?, localOn: Boolean): Boolean? =
        if (scheme == SentryScheme.AFTERMARKET) localOn else statusOn

    /** 副厂方案下的下一次点按：本地开着就关，关着就开。 */
    fun nextAftermarket(localOn: Boolean): String = if (localOn) "sentryOff" else "sentryOn"

    /**
     * 这条指令要不要跳过「等回执 + 读车况求证」。
     *
     * 只有副厂方案的开 / 关哨兵跳过；其它指令一律保留原来的求证链。
     */
    fun skipsReceipt(scheme: SentryScheme, commandName: String): Boolean =
        scheme == SentryScheme.AFTERMARKET &&
            (commandName == "sentryOn" || commandName == "sentryOff")

    /** 跳过求证时该说的那句：因为状态就是本地记下的，可以直接说完成式。 */
    fun doneText(commandName: String): String =
        if (commandName == "sentryOff") "哨兵已关闭" else "哨兵已开启"
}

/**
 * 「哨兵开没开」的本机记录（副厂方案用）。
 *
 * ## 为什么放在主包、而不是小组件包里
 * 首页、控制中心那条链路（[com.leapcontrol.app.ui.AppRuntime.control]）与桌面小组件
 * （[com.leapcontrol.app.widget.VehicleWidgetProvider]）必须读**同一份**状态：
 * 在 App 里开了哨兵，桌面卡片右上角就得跟着写「哨兵已开启」；在桌面关掉，回到 App
 * 也不能还亮着。所以用同一份 prefs、同一个 key，两边都从这里读写。
 *
 * prefs 名与 key 沿用旧版 `WidgetSentryState` 的值（`widget_action_state` / `state_sentry`），
 * 老用户已记下的状态不会因为这次搬位置而丢。
 *
 * 8 秒意图窗口（[com.leapcontrol.app.widget.WidgetTapRules.ACTION_GRACE_MS]）的语义与磁贴一致：
 * - 窗口内不再下发第二条，否则「开完立刻关」两条指令互相抵消；
 * - 窗口内按键高亮跟**本地意图**走，车端报文 5~15 秒才跟上；
 * - 校准不算用户动作，不刷新时间戳。
 */
object SentryLocalState {

    private const val PREFS = "widget_action_state"
    private const val KEY_ON = "state_sentry"
    private const val KEY_AT = "state_at_sentry"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本地记下的开合态；默认 false = 没开过。 */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    /** 上一次由用户成功下发哨兵指令的时刻；0 = 从没发过。 */
    fun lastActionAt(context: Context): Long = prefs(context).getLong(KEY_AT, 0L)

    /**
     * 写开合态。
     *
     * [fromUser] 与 `QuickTiles.setOn` 同口径：只有用户点击才刷新时间戳，
     * 车况校准（[fromUser] = false）只改状态，不然一次校准会让之后 8 秒的窗口误判成「刚点过」。
     */
    fun setOn(context: Context, on: Boolean, fromUser: Boolean = true) {
        val editor = prefs(context).edit().putBoolean(KEY_ON, on)
        if (fromUser) editor.putLong(KEY_AT, System.currentTimeMillis())
        editor.apply()
    }

    /**
     * 用车况校准本地态 —— **只校显示 / 判向依据，本身不发指令**。
     *
     * 只有原厂方案才该调：副厂车的 `sentryOn` 要么读不到（null，函数自然不动），
     * 要么恒为 false，拿它校准会把用户刚开的哨兵又打回「关」。
     *
     * 三条纪律与 `QuickTiles.calibrateFromStatus` 一致：
     * 信号读不到（null）不动、8 秒意图窗口内不动、校准不算用户动作。
     */
    fun calibrateFromStatus(context: Context, sentryOn: Boolean?) {
        if (sentryOn == null) return
        if (com.leapcontrol.app.widget.WidgetTapRules.withinIntentWindow(
                lastActionAt(context),
                System.currentTimeMillis(),
            )
        ) {
            return
        }
        setOn(context, sentryOn, fromUser = false)
    }
}
