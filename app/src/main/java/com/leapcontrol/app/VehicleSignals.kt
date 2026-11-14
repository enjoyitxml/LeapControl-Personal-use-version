package com.leapcontrol.app

import org.json.JSONObject

/**
 * 从车况原始 JSON 里解析 App 自己关心的几个派生信号：锁车、行驶、车窗。
 *
 * 这里放的都是**纯函数**（只吃 JSON、不碰网络与缓存），所以首页状态卡、
 * 锁车后安全提醒都直接用它，也方便单测。
 */
object VehicleSignals {
    fun locked(status: JSONObject): Boolean? = locked(status.opt("driverDoorLockStatus"))

    fun locked(values: Map<String, Any?>): Boolean? = locked(values["driverDoorLockStatus"])

    /**
     * 三态布尔解析：`true/false`、`1/0`、`"1"/"0"` 都认，缺失或无法解析 → null。
     *
     * 车端的开关类信号（除霜、加热…）在不同车型上既有数字也有字符串，
     * 而且**缺信号不等于关着**，所以这里宁可返回 null 让 UI 不显示，
     * 也不要猜成 false。
     */
    fun bool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "on", "open", "opened" -> true
            "false", "0", "off", "close", "closed" -> false
            else -> null
        }
        else -> null
    }

    /** 返回开着的那几扇车窗的名称，用于锁车后的安全提醒。 */
    fun openWindowLabels(status: JSONObject, carType: String? = null): List<String> {
        if (noWindowTelemetry(carType)) return emptyList()
        return windowStates(status)
            .filterValues { it == WindowSignalState.OPEN }
            .keys
            .toList()
    }

    /** 只有车况里真的带车窗信号时才为 true（缺信号 ≠ 车窗关着）。 */
    fun hasWindowTelemetry(status: JSONObject, carType: String? = null): Boolean {
        if (noWindowTelemetry(carType)) return false
        return WINDOW_SIGNAL_KEYS.any { key -> status.has(key) && status.opt(key) != JSONObject.NULL }
    }

    /**
     * 是否有车窗未关：`true` = 确认有窗开着，`false` = 确认全关，`null` = 读不到或值不可信。
     *
     * **为什么不写成「有值且非 0 就是开」**：车端的开关位在车门被开关过之后会短暂（有车型是持久）
     * 上报 0/1 之外的值（中间态、位置未学习、无效值 255 之类）。旧实现用 `!= 0` 一律当「开」，
     * 于是「充电时开了下车门、再关上」就会在首页顶出一条假的「车窗已开启」，而且刷新也去不掉
     * （用户 2026-11-08 反馈，车实际从没开过窗）。
     *
     * 现在只有**明确的 1** 才算开；其它取不到准话的值一律当「未知」——
     * 未知不参与「开着」的结论，也不冒充「关着」，UI 直接不显示这一行。
     */
    fun windowOpen(status: JSONObject, carType: String? = null): Boolean? {
        if (noWindowTelemetry(carType)) return null
        val states = windowStates(status)
        if (states.isEmpty()) return null
        if (states.values.any { it == WindowSignalState.OPEN }) return true
        return if (states.values.all { it == WindowSignalState.CLOSED }) false else null
    }

    /**
     * 逐窗状态。
     *
     * 车端根本没有这一路信号时，对应的窗**不出现在结果里**（＝没有证据）；
     * 出现了就说明车端给了值，只是可能不可信（[WindowSignalState.UNKNOWN]）。
     * 这两种情况必须分开：前者不影响其它窗的结论，后者会。
     */
    fun windowStates(status: JSONObject): Map<String, WindowSignalState> {
        val out = LinkedHashMap<String, WindowSignalState>()
        WINDOW_SLOTS.forEach { (label, percentKey, statusKey) ->
            val state = windowStateOf(status.opt(percentKey), status.opt(statusKey))
            if (state != null) out[label] = state
        }
        return out
    }

    private fun noWindowTelemetry(carType: String?): Boolean =
        carType?.contains("T03", ignoreCase = true) == true

    /**
     * 是否在行驶：以车速为准（停车后可能长时间保持同一个锁车状态，
     * 车门锁不能当判据）；老车况没有 speed 字段时退回挡位判断。
     */
    fun isDriving(status: JSONObject): Boolean = when {
        status.has("speed") -> parseSpeed(status.opt("speed"))?.let { it > 0.0 } == true
        else -> isDrivingGear(status.opt("gearStatus"))
    }

    fun isDriving(values: Map<String, Any?>): Boolean = when {
        values.containsKey("speed") -> parseSpeed(values["speed"])?.let { it > 0.0 } == true
        else -> isDrivingGear(values["gearStatus"])
    }

    private fun isDrivingGear(value: Any?): Boolean = when (value?.toString()?.trim()?.uppercase()) {
        "D", "DRIVE", "前进", "D挡", "3", "R", "REVERSE", "倒车", "R挡", "1" -> true
        else -> false
    }

    private fun parseSpeed(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        else -> value?.toString()?.trim()?.removeSuffix("km/h")?.trim()?.toDoubleOrNull()
    }

    /**
     * 一扇窗的状态：**开度百分比优先**，没有百分比才退回开关位。
     *
     * 百分比是硬指标（0 就是关、>0 才是开），开关位是两位的状态字，两者冲突时信百分比。
     */
    private fun windowStateOf(percent: Any?, status: Any?): WindowSignalState? =
        percentState(percent) ?: statusState(status)

    private fun percentState(value: Any?): WindowSignalState? {
        val text = value?.toString()?.trim()?.removeSuffix("%")?.trim().orEmpty()
        val number = text.toDoubleOrNull() ?: return null
        // 0~100 之外不是开度，是车端的无效值（-1 / 255 这类）。
        // 旧实现直接比 0：`255` 会被当成「开着」、`-1` 会被当成「关着」，两句都是假话。
        // 返回 null 让它退回开关位判断，而不是武断地下结论。
        if (number < 0.0 || number > 100.0) return null
        return if (number > 0.0) WindowSignalState.OPEN else WindowSignalState.CLOSED
    }

    private fun statusState(value: Any?): WindowSignalState? = when (value) {
        null, JSONObject.NULL -> null
        is Boolean -> if (value) WindowSignalState.OPEN else WindowSignalState.CLOSED
        is Number -> exactWindow(value.toDouble())
        is String -> {
            val text = value.trim().lowercase()
            when (text) {
                "true", "on", "open", "opened", "opening" -> WindowSignalState.OPEN
                "false", "off", "close", "closed" -> WindowSignalState.CLOSED
                // "1" / "1.0" / "0" / "0.0" 这些字符串形态也按数值走
                else -> text.toDoubleOrNull()?.let { exactWindow(it) } ?: WindowSignalState.UNKNOWN
            }
        }
        else -> WindowSignalState.UNKNOWN
    }

    /** 只认 0 和 1。中间态、255 / -1 这类无效值一律「未知」，绝不当成「开着」。 */
    private fun exactWindow(value: Double): WindowSignalState = when (value) {
        0.0 -> WindowSignalState.CLOSED
        1.0 -> WindowSignalState.OPEN
        else -> WindowSignalState.UNKNOWN
    }

    /** 四扇窗对应的「开度百分比 / 开关位」字段名。 */
    private val WINDOW_SLOTS = listOf(
        Triple("左前", "leftFrontWindowPercent", "driverWindowStatus"),
        Triple("右前", "rightFrontWindowPercent", "rightFrontWindowStatus"),
        Triple("左后", "leftRearWindowPercent", "leftRearWindowStatus"),
        Triple("右后", "rightRearWindowPercent", "rightRearWindowStatus"),
    )

    private val WINDOW_SIGNAL_KEYS = listOf(
        "leftFrontWindowPercent",
        "rightFrontWindowPercent",
        "leftRearWindowPercent",
        "rightRearWindowPercent",
        "driverWindowStatus",
        "rightFrontWindowStatus",
        "leftRearWindowStatus",
        "rightRearWindowStatus"
    )

    private fun locked(value: Any?): Boolean? = when (value) {        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "locked", "lock" -> true
            "false", "0", "unlocked", "unlock" -> false
            else -> null
        }
        else -> null
    }
}

/**
 * 一扇车窗的状态。
 *
 * [UNKNOWN] 和「信号缺失」不是一回事：缺失是车端压根没发这一路，未知是发了但值不可信
 * （中间态、无效值）。前者不参与判断，后者会否掉「全部关着」的结论。
 */
enum class WindowSignalState { OPEN, CLOSED, UNKNOWN }
