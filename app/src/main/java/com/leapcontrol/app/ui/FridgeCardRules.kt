package com.leapcontrol.app.ui

import com.leapcontrol.app.Commands
import com.leapcontrol.app.FridgeControlCommand
import com.leapcontrol.app.FridgeMode
import com.leapcontrol.app.FridgeStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 首页「车载冰箱」卡片的纯逻辑：不依赖 Android / Compose，方便单测钉住参数口径。
 *
 * ## 上游口径（leapauto-ref，cmdid=500）
 * 只有一个指令：`Commands.buildFridgeControl` 把 8 个字段一次性全量下发
 * （`cycles / duration / enable / mode / parkEnable / style / temp / value`），
 * 所以卡片里的开关、模式、温度、风格、离车保持都是**同一条指令的不同字段**，
 * 不存在「调温单独发一条」这回事 —— 全部攒在草稿里，点一次按钮合成一条。
 *
 * - 制冷温度 `-6..15`，步进 1℃；制热固定 50℃（[Commands] 会忽略传入的 temp）。
 * - 风格 `normal` / `turbo`；离车时长 1/2/4/8 小时（`durationSeconds`）。
 * - `cycles`：`"1"` 仅单次生效，`"2"` 每次离车自动开启。
 * - 关闭冰箱时 `parkEnable` 一律为 0（上游 FridgeControlBottomSheet 的关闭按钮就是这么拼的）。
 *
 * **不做车辆能力判断**：卡片常显，车况没有冰箱信号（[FridgeCardState.hasSignal] = false）
 * 时照样能设置、能下发，只是状态行提示「状态待同步」。是否有硬件由用户自己加不加这张卡决定。
 */
object FridgeCardRules {

    const val TEMP_MIN = -6
    const val TEMP_MAX = 15

    /** 上游的四个温度预设胶囊（FridgeControlBottomSheet：冰镇 / 冷饮 / 果蔬 / 保鲜）。 */
    val PRESETS: List<FridgePreset> = listOf(
        FridgePreset(0, "冰镇"),
        FridgePreset(4, "冷饮"),
        FridgePreset(8, "果蔬"),
        FridgePreset(12, "保鲜"),
    )

    /** 上游「保持运行时长」的四档。 */
    val DURATION_HOURS: List<Int> = listOf(1, 2, 4, 8)

    /** 车况没给温度 / 给了越界值时，卡片显示的制冷目标温度。 */
    fun clampTemp(value: Int?): Int =
        (value ?: Commands.FRIDGE_DEFAULT_TEMP).coerceIn(TEMP_MIN, TEMP_MAX)

    /** 加减号：到边界就停住，上游按钮在边界也是 disabled。 */
    fun stepTemp(current: Int, delta: Int): Int =
        (current + delta).coerceIn(TEMP_MIN, TEMP_MAX)

    /**
     * [CarStatus.fridge] → 卡片状态。
     *
     * `null`（车况没上报冰箱信号）只把 [FridgeCardState.hasSignal] 置 false，
     * **不是「这车没有冰箱」的结论**：卡片照常渲染、照常能下发，状态行写「状态待同步」。
     */
    fun of(snapshot: FridgeSnapshot?): FridgeCardState {
        if (snapshot == null) return FridgeCardState()
        return FridgeCardState(
            enabled = snapshot.enabled,
            mode = snapshot.mode ?: FridgeMode.COLD,
            style = snapshot.style ?: FridgeStyle.NORMAL,
            temp = clampTemp(snapshot.temp),
            parkEnable = snapshot.parkEnable == true,
            parkDurationHours = (snapshot.parkDurationHours ?: 1).coerceIn(1, 24),
            parkCycles = snapshot.parkCycles ?: 0,
            parkEndTimeEpochSeconds = snapshot.parkEndTimeEpochSeconds ?: 0L,
            hasSignal = true,
        )
    }

    /** 车况快照 → 卡片草稿的初值（只在首次进入 / 换车时取一次，之后以用户操作为准）。 */
    fun draftOf(state: FridgeCardState): FridgeDraft = FridgeDraft(
        enabled = state.enabled == true,
        mode = state.mode,
        temp = clampTemp(if (state.mode == FridgeMode.HOT) Commands.FRIDGE_DEFAULT_TEMP else state.temp),
        style = state.style,
        parkEnable = state.parkEnable,
        // 车况上报的是 1..24 任意小时；卡片只有四档，取最接近的一档，别让它显示成「没有选中」
        durationHours = nearestDuration(state.parkDurationHours),
        everyDeparture = state.parkCycles == 1,
    )

    /** 把 1..24 的任意时长收敛到四档之一。 */
    fun nearestDuration(hours: Int): Int =
        DURATION_HOURS.minByOrNull { abs(it - hours.coerceIn(1, 24)) } ?: 1

    /**
     * 草稿 → 一条 cmdid=500 指令。
     *
     * [enable] 由按钮决定：「关闭冰箱」传 false，「应用设置 / 开启车载冰箱」传 true。
     * 制热时 temp 仍传草稿值（切回制冷时还能恢复），[Commands.buildFridgeControl] 会强制成 50。
     */
    fun command(draft: FridgeDraft, enable: Boolean = draft.enabled): FridgeControlCommand =
        FridgeControlCommand(
            enable = enable,
            mode = draft.mode,
            temp = draft.temp,
            style = draft.style,
            parkEnable = if (enable) draft.parkEnable else false,
            durationSeconds = draft.durationHours * 3600,
            cycles = if (draft.everyDeparture) "2" else "1",
            value = "false",
        )

    /** 头部那句状态，取自**车况**而不是草稿（草稿是「待下发」，不能冒充已生效）。 */
    fun statusText(state: FridgeCardState): String = when {
        !state.hasSignal -> "状态待同步"
        state.enabled != true -> "已关闭"
        state.parkEnable -> "离车保持中"
        state.mode == FridgeMode.HOT -> "恒温暖饮 · ${Commands.FRIDGE_HOT_TEMP}°C"
        state.style == FridgeStyle.TURBO -> "急速制冷 · ${state.temp}°C"
        else -> "智能制冷 · ${state.temp}°C"
    }

    /**
     * 首页摘要卡上那一行（2026-11-08 起卡片只做摘要，详细功能在二级页）。
     *
     * 比 [statusText] 更省字：「离车保持中」+ 截止时间合并成「离车保持 · 至 21:30」，
     * 免得一行里出现两次「离车保持」。没有截止时间就退回 [statusText]。
     */
    fun summaryText(state: FridgeCardState): String {
        val base = statusText(state)
        if (!state.hasSignal || state.enabled != true || !state.parkEnable) return base
        val end = parkEndLabel(state.parkEndTimeEpochSeconds)?.substringAfter("至 ") ?: return base
        return "离车保持 · 至 $end"
    }

    /**
     * 离车保持截止时间（信号 11260，秒级时间戳）→「离车保持至 HH:mm」。
     *
     * 用 SimpleDateFormat / Date 而不是 `LocalDate.ofInstant`：后者 API 31 才有，
     * minSdk 26 上调用会 NoSuchMethodError 闪退（项目里踩过）。
     */
    fun parkEndLabel(epochSeconds: Long?): String? {
        if (epochSeconds == null || epochSeconds <= 0L) return null
        val text = runCatching {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000L))
        }.getOrNull() ?: return null
        return "离车保持至 $text"
    }
}

/** 一个温度预设胶囊。 */
data class FridgePreset(val temp: Int, val label: String)

/**
 * 车况快照（从 [CarStatus.fridge] 转过来）。
 *
 * 跟 [com.leapcontrol.app.FridgeStatus] 不是同一个类：这个是卡片用的、字段全可空，
 * 且 [hasSignal] 只用来写状态文案，**不当门禁**。
 */
data class FridgeCardState(
    val enabled: Boolean? = null,
    val mode: FridgeMode = FridgeMode.COLD,
    val style: FridgeStyle = FridgeStyle.NORMAL,
    val temp: Int = Commands.FRIDGE_DEFAULT_TEMP,
    val parkEnable: Boolean = false,
    val parkDurationHours: Int = 1,
    val parkCycles: Int = 0,
    val parkEndTimeEpochSeconds: Long = 0L,
    val hasSignal: Boolean = false,
)

/** 卡片里用户还没下发的全部设置。 */
data class FridgeDraft(
    val enabled: Boolean = false,
    val mode: FridgeMode = FridgeMode.COLD,
    val temp: Int = Commands.FRIDGE_DEFAULT_TEMP,
    val style: FridgeStyle = FridgeStyle.NORMAL,
    val parkEnable: Boolean = false,
    val durationHours: Int = 2,
    val everyDeparture: Boolean = false,
)
