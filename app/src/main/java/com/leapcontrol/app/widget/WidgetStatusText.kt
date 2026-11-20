package com.leapcontrol.app.widget

import java.util.Locale

/**
 * 小组件右上角那一行的**纯逻辑**：写什么字、算不算「高亮」、前面挂哪个小图标。
 *
 * ## 为什么单独一层
 * `wg_time` 只有 `maxWidth=118dp + ellipsize=end + maxLines=1`，写长了就只剩省略号。
 * 以前这里只认「未锁车 / 充电中 / 已充满」，空调、车窗、后备箱、电池预热、遮阳帘、
 * 哨兵开着时全都被写成一句「12:30 更新」，用户等于看不见。现在把这些状态按优先级
 * 折成一句短语，并且**只有一条栏目**——多项同时开着时写「第一条 · 等N项」，
 * 不靠桌面去截断半句话。
 *
 * 「充电中」按 `chargeState` 判，不能只看 `charging`：那个字段把「充电结束（2）」
 * 也算成 true，充满拔枪前卡片会一直写充电中。剩余时长另给一套更短的写法
 * （[chargeRemain]），`DrivingStateRules.chargeDetail` 那句「剩 1 小时 23 分钟」
 * 在 118dp 里放不下。
 *
 * ## 图标（2026-11-20 加）
 * 每句提示前面挂一颗 10dp 的小图标，让人不读字也知道是什么状态。这里只给**语义**
 * （[HintIcon]），不放 `R.drawable`：这一层要能跑纯 JVM 单测，资源映射在
 * `VehicleWidget.hintIconRes()`。
 *
 * **更新时间那类提示（「12:30 更新」「尚未同步」）不给图标** —— 用户明确要求，
 * 并且它俩本身就是「没事发生」的状态，前面压一颗表反而更吵。
 *
 * 所有入参都是基本类型 / 数据类，不碰 `CarStatus` / Android。
 */
object WidgetStatusText {

    /** 高亮 = 用「字体颜色」（默认白）。WARN 仍走警示色，PLAIN 走副信息灰。 */
    enum class Tone { PLAIN, HIGHLIGHT, WARN }

    /**
     * 状态行前面那格小图标的种类。
     *
     * 只列用得上的：充电、满电、成功、异常、登录这五颗是新画的，其余复用按键那套
     * 官方同款线稿（加粗到小尺寸专用版，见 `ic_hint_*.xml`）。
     */
    enum class HintIcon {
        /** 不挂图标（更新时间、尚未同步）。 */
        NONE,
        SYNC,
        UNLOCK,
        LOCK,
        TRUNK,
        WINDOW,
        AC,
        PREHEAT,
        SUNSHADE,
        SENTRY,
        CHARGE,
        CHARGED,
        OK,
        WARN,
        USER,
    }

    data class Line(val text: String, val tone: Tone, val icon: HintIcon = HintIcon.NONE)

    /** 一条「开着什么」的提示：文案 + 它自己那颗图标。 */
    data class Hint(val text: String, val icon: HintIcon)

    /** 车况里这一行用得上的信号；null 一律当「读不到」，绝不猜成已关。 */
    data class State(
        val locked: Boolean? = null,
        val acOn: Boolean? = null,
        val trunkOpen: Boolean? = null,
        val windowOpen: Boolean? = null,
        val batteryPreheatOn: Boolean? = null,
        val sunshadeOpen: Boolean? = null,
        val sentryOn: Boolean? = null,
        val chargeState: Int? = null,
        val charging: Boolean? = null,
        val chargeRemainMinutes: Int? = null,
        val chargeTimeLeft: String? = null,
    )

    data class Input(
        val busy: String? = null,
        val signedIn: Boolean = true,
        val sessionExpired: Boolean = false,
        val pinReady: Boolean = true,
        val flashText: String? = null,
        val flashOk: Boolean = true,
        val state: State? = null,
        /** 车况数据本身的时刻（HH:mm），不是拉取时刻。 */
        val clock: String? = null,
        /** 充电完成且 SOC ≥ 100：写「已充满」，否则只说「充电完成」。 */
        val fullConfirmed: Boolean = false,
    )

    /** 与旧口径完全一致：1 = 充电中；没给 chargeState 时才退回 charging 兜底。 */
    fun isCharging(state: State?): Boolean =
        state != null && (state.chargeState == 1 || (state.chargeState == null && state.charging == true))

    /**
     * 剩余充电时长的短写法，只有「充电中」才有意义。
     *
     * 分钟原值优先：`剩23分` / `剩1时23分` / `剩2时` / `即将充满`；
     * 只有格式化文本时兜一句 `剩 <原文>`（可能偏长，交给 ellipsize）。
     */
    fun chargeRemain(state: State?): String? {
        if (state?.chargeState != 1) return null
        val minutes = state.chargeRemainMinutes
        if (minutes != null) {
            return when {
                minutes <= 0 -> "即将充满"
                minutes < 60 -> String.format(Locale.CHINA, "剩%d分", minutes)
                minutes % 60 == 0 -> String.format(Locale.CHINA, "剩%d时", minutes / 60)
                else -> String.format(Locale.CHINA, "剩%d时%d分", minutes / 60, minutes % 60)
            }
        }
        val text = state.chargeTimeLeft?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "剩 $text"
    }

    /**
     * 开着的功能，按「先看安全、再看舒适」排；每条自带图标。
     *
     * 只收明确的 `true`；`null`（这台车读不到）与 `false` 都不写，
     * 否则会把「读不到」画成「已关闭」或反过来误报。
     */
    fun openHints(state: State?): List<Hint> = buildList {
        if (state == null) return@buildList
        if (state.locked == false) add(Hint("车辆已解锁", HintIcon.UNLOCK))
        if (state.trunkOpen == true) add(Hint("后备箱已开启", HintIcon.TRUNK))
        if (state.windowOpen == true) add(Hint("车窗已开启", HintIcon.WINDOW))
        if (state.acOn == true) add(Hint("空调已开启", HintIcon.AC))
        if (state.batteryPreheatOn == true) add(Hint("电池预热已开启", HintIcon.PREHEAT))
        if (state.sunshadeOpen == true) add(Hint("遮阳帘已开启", HintIcon.SUNSHADE))
        if (state.sentryOn == true) add(Hint("哨兵已开启", HintIcon.SENTRY))
    }

    /**
     * 多开项折成一句：只写第一条，其余用「等N项」交代，不让桌面把半句话截掉。
     * N 是**总共**开着的项数，不是「另外还有几项」；图标跟着第一条走，不加角标。
     */
    fun hintsLine(hints: List<Hint>): Line? {
        if (hints.isEmpty()) return null
        val first = hints.first()
        val text = if (hints.size > 1) "${first.text} · 等${hints.size}项" else first.text
        return Line(text, Tone.HIGHLIGHT, first.icon)
    }

    /**
     * 控车指令对应的图标：`busy`（正在解锁…）和 `flashText`（已上锁）都是人写的短句，
     * 只能按关键字认。认不出来返回 [HintIcon.NONE]，由调用方兜 OK / WARN。
     *
     * 「解锁」必须排在「上锁」前面无所谓 —— 两句互不包含，顺序不影响结果。
     */
    fun actionIcon(text: String?): HintIcon {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return HintIcon.NONE
        return ACTION_KEYWORDS.firstOrNull { t.contains(it.first) }?.second ?: HintIcon.NONE
    }

    private val ACTION_KEYWORDS = listOf(
        "后备箱" to HintIcon.TRUNK,
        "车窗" to HintIcon.WINDOW,
        "遮阳帘" to HintIcon.SUNSHADE,
        "预热" to HintIcon.PREHEAT,
        "空调" to HintIcon.AC,
        "哨兵" to HintIcon.SENTRY,
        "解锁" to HintIcon.UNLOCK,
        "上锁" to HintIcon.LOCK,
        "锁车" to HintIcon.LOCK,
        "充电" to HintIcon.CHARGE,
    )

    /** 右上角那行的最终文案 + 色调 + 图标。优先级从上到下，别把车况提示顶掉登录失效。 */
    fun resolve(input: Input): Line {
        val state = input.state
        val charging = isCharging(state)
        val clockSuffix = input.clock?.let { " · $it" }.orEmpty()
        return when {
            // 控车进行中 / 二次确认 / 行驶禁控这类临时提示最高优先
            input.busy != null -> Line(
                input.busy,
                Tone.HIGHLIGHT,
                actionIcon(input.busy).takeIf { it != HintIcon.NONE } ?: HintIcon.SYNC,
            )

            !input.signedIn -> Line(
                if (input.sessionExpired) "登录已失效" else "点此登录",
                if (input.sessionExpired) Tone.WARN else Tone.PLAIN,
                if (input.sessionExpired) HintIcon.WARN else HintIcon.USER,
            )

            !input.pinReady -> Line("未设控车密码", Tone.WARN, HintIcon.LOCK)

            // 刚控过车：先把结果说出来，车端报文要 5~15 秒才跟上。
            // 成功时优先用「这件事」的图标（已解锁 → 开锁），认不出再退回对勾。
            input.flashText != null -> Line(
                if (charging) "${input.flashText} · 充电中" else input.flashText,
                if (input.flashOk) Tone.HIGHLIGHT else Tone.WARN,
                if (!input.flashOk) {
                    HintIcon.WARN
                } else {
                    actionIcon(input.flashText).takeIf { it != HintIcon.NONE } ?: HintIcon.OK
                },
            )

            state == null -> Line("正在同步…", Tone.PLAIN, HintIcon.SYNC)

            charging -> {
                val remain = chargeRemain(state)
                // 有剩余时长就别再挂时间：118dp 放不下「充电中 · 剩1时23分 · 12:30」
                val tail = remain?.let { " · $it" } ?: clockSuffix
                Line("充电中$tail", Tone.HIGHLIGHT, HintIcon.CHARGE)
            }

            state.chargeState == 2 -> Line(
                if (input.fullConfirmed) "已充满" else "充电完成",
                Tone.HIGHLIGHT,
                HintIcon.CHARGED,
            )

            else -> hintsLine(openHints(state))
                ?: when {
                    input.clock != null -> Line("${input.clock} 更新", Tone.PLAIN)
                    else -> Line("尚未同步", Tone.PLAIN)
                }
        }
    }
}
