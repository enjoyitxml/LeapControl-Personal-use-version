package com.leapcontrol.app.widget

import java.util.Locale

/**
 * 小组件右上角那一行的**纯逻辑**：写什么字、算不算「高亮」。
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
 * 所有入参都是基本类型 / 数据类，不碰 `CarStatus` / Android，纯 JVM 单测可以直接跑。
 */
object WidgetStatusText {

    /** 高亮 = 用设置里可自定义的那支颜色（默认版式主色）。WARN 仍走警示色，PLAIN 走副信息灰。 */
    enum class Tone { PLAIN, HIGHLIGHT, WARN }

    data class Line(val text: String, val tone: Tone)

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
     * 开着的功能，按「先看安全、再看舒适」排。
     *
     * 只收明确的 `true`；`null`（这台车读不到）与 `false` 都不写，
     * 否则会把「读不到」画成「已关闭」或反过来误报。
     */
    fun openHints(state: State?): List<String> = buildList {
        if (state == null) return@buildList
        if (state.locked == false) add("车辆已解锁")
        if (state.trunkOpen == true) add("后备箱已开启")
        if (state.windowOpen == true) add("车窗已开启")
        if (state.acOn == true) add("空调已开启")
        if (state.batteryPreheatOn == true) add("电池预热已开启")
        if (state.sunshadeOpen == true) add("遮阳帘已开启")
        if (state.sentryOn == true) add("哨兵已开启")
    }

    /**
     * 多开项折成一句：只写第一条，其余用「等N项」交代，不让桌面把半句话截掉。
     * N 是**总共**开着的项数，不是「另外还有几项」。
     */
    fun hintsText(hints: List<String>): String? {
        if (hints.isEmpty()) return null
        val first = hints.first()
        return if (hints.size > 1) "$first · 等${hints.size}项" else first
    }

    /** 右上角那行的最终文案 + 色调。优先级从上到下，别把车况提示顶掉登录失效。 */
    fun resolve(input: Input): Line {
        val state = input.state
        val charging = isCharging(state)
        val clockSuffix = input.clock?.let { " · $it" }.orEmpty()
        return when {
            // 控车进行中 / 二次确认 / 行驶禁控这类临时提示最高优先
            input.busy != null -> Line(input.busy, Tone.HIGHLIGHT)

            !input.signedIn -> Line(
                if (input.sessionExpired) "登录已失效" else "点此登录",
                if (input.sessionExpired) Tone.WARN else Tone.PLAIN,
            )

            !input.pinReady -> Line("未设控车密码", Tone.WARN)

            // 刚控过车：先把结果说出来，车端报文要 5~15 秒才跟上
            input.flashText != null -> Line(
                if (charging) "${input.flashText} · 充电中" else input.flashText,
                if (input.flashOk) Tone.HIGHLIGHT else Tone.WARN,
            )

            state == null -> Line("正在同步…", Tone.PLAIN)

            charging -> {
                val remain = chargeRemain(state)
                // 有剩余时长就别再挂时间：118dp 放不下「充电中 · 剩1时23分 · 12:30」
                val tail = remain?.let { " · $it" } ?: clockSuffix
                Line("充电中$tail", Tone.HIGHLIGHT)
            }

            state.chargeState == 2 -> Line(
                if (input.fullConfirmed) "已充满" else "充电完成",
                Tone.HIGHLIGHT,
            )

            else -> {
                val hint = hintsText(openHints(state))
                when {
                    hint != null -> Line(hint, Tone.HIGHLIGHT)
                    input.clock != null -> Line("${input.clock} 更新", Tone.PLAIN)
                    else -> Line("尚未同步", Tone.PLAIN)
                }
            }
        }
    }
}
