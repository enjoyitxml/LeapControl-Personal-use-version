package com.leapcontrol.app

import java.util.Calendar

/**
 * 定时空调的「到点联动」配置。
 *
 * ## 为什么单独一份，不塞进 [ScheduledAcPlan]
 *
 * [ScheduledAcPlan] 的每个字段都会写进车端 `cmdid=171` 的 controls 里，
 * 车端不认识「关窗」「座椅加热」这些字段；而这批开关的执行**完全在手机侧**：
 * 车端的预约空调照常在 `startTime` 开空调，手机在同一时刻补发几条控车指令。
 *
 * 所以这份配置只存本地（[SessionStore]），不参与 171 下发，也不参与回读。
 *
 * ## 为什么放在本地闹钟而不是云端
 *
 * 车端预约接口只支持「开空调」这一件事，没有附带动作的字段。想在开空调的同时
 * 关窗、开座椅加热，只能在手机上定一个同点的闹钟，到点自己发指令。
 */
data class ScheduledAcLinkage(
    /** 到点联动关车窗。 */
    val closeWindows: Boolean = false,
    /** 到点联动关闭全景天幕遮阳帘。 */
    val closeSunshade: Boolean = false,
    val driverHeating: Boolean = false,
    val driverVent: Boolean = false,
    val passengerHeating: Boolean = false,
    val passengerVent: Boolean = false,
    val leftRearHeating: Boolean = false,
    val leftRearVent: Boolean = false,
    val rightRearHeating: Boolean = false,
    val rightRearVent: Boolean = false,
    val steeringWheelHeating: Boolean = false,
) {

    val anyEnabled: Boolean
        get() = closeWindows || closeSunshade || driverHeating || driverVent ||
            passengerHeating || passengerVent || leftRearHeating || leftRearVent ||
            rightRearHeating || rightRearVent || steeringWheelHeating

    /**
     * 到点要依次下发的指令，顺序固定：
     * 先关窗（可能下雨）、再关遮阳帘，然后是座椅与方向盘。
     *
     * 顺序有意义：这些指令走同一个控车网关，一条一条发，越靠前越优先。
     * 关窗排在第一位是因为它是「防止车内进东西」的动作，比舒适性配置更急。
     */
    fun commands(): List<String> = buildList {
        if (closeWindows) add("windowClose")
        if (closeSunshade) add("sunshadeClose")
        if (driverHeating) add("driverSeatHeating_$COMFORT_LEVEL")
        if (driverVent) add("driverSeatVentilation_$COMFORT_LEVEL")
        if (passengerHeating) add("passengerSeatHeating_$COMFORT_LEVEL")
        if (passengerVent) add("passengerSeatVentilation_$COMFORT_LEVEL")
        if (leftRearHeating) add("leftRearSeatHeating_$COMFORT_LEVEL")
        if (leftRearVent) add("leftRearSeatVentilation_$COMFORT_LEVEL")
        if (rightRearHeating) add("rightRearSeatHeating_$COMFORT_LEVEL")
        if (rightRearVent) add("rightRearSeatVentilation_$COMFORT_LEVEL")
        if (steeringWheelHeating) add(SteeringWheelHeat.ON)
    }

    /** 一行摘要："关窗、主驾加热、方向盘加热"，给编辑页回显。 */
    fun summary(): String = buildList {
        if (closeWindows) add("关窗")
        if (closeSunshade) add("遮阳帘")
        if (driverHeating) add("主驾加热")
        if (driverVent) add("主驾通风")
        if (passengerHeating) add("副驾加热")
        if (passengerVent) add("副驾通风")
        if (leftRearHeating) add("二排左加热")
        if (leftRearVent) add("二排左通风")
        if (rightRearHeating) add("二排右加热")
        if (rightRearVent) add("二排右通风")
        if (steeringWheelHeating) add("方向盘加热")
    }.joinToString("、")

    companion object {
        /**
         * 座椅加热 / 通风开几档。
         *
         * 固定 2 档而不是 3 档：3 档最烫，作为「上车前的预热」容易过热，
         * 用户上车后自己再调；与空调页快捷开关的默认值保持一致。
         */
        const val COMFORT_LEVEL = 2

        val EMPTY = ScheduledAcLinkage()
    }
}

/**
 * 到点联动的触发时刻计算。纯函数，方便单测。
 */
object ScheduledAcLinkageSchedule {

    /**
     * 下一次该在什么时候响。
     *
     * [days] 是车端 7 位掩码（索引 0 = 周一 … 6 = 周日），与 `cmdid=171` 的 days 同一口径；
     * 空 / 全 0 按「每天」处理（车端也是这个兜底）。
     *
     * 返回 null 表示算不出（时间格式不对 / 没有可用的重复日），调用方据此撤掉闹钟。
     */
    fun nextTriggerAt(nowMs: Long, startTime: String?, days: String?): Long? {
        val (hour, minute) = ChargeSchedule.parseTime(startTime) ?: return null
        val daySet = ChargePlanCyclesHelper.parseToDaySet(days)
        if (daySet.isEmpty()) return null

        for (offset in 0..7) {
            val candidate = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis <= nowMs) continue
            if (!daySet.contains(isoDayOfWeek(candidate.get(Calendar.DAY_OF_WEEK)))) continue
            return candidate.timeInMillis
        }
        return null
    }

    /**
     * Calendar 的 `DAY_OF_WEEK`（1=周日 … 7=周六）→ 本项目口径（1=周一 … 7=周日）。
     */
    internal fun isoDayOfWeek(calendarDay: Int): Int =
        if (calendarDay == Calendar.SUNDAY) 7 else calendarDay - 1
}
