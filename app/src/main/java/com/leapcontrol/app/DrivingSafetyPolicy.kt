package com.leapcontrol.app

import com.leapcontrol.app.ui.CarStatus

/**
 * 行驶中禁止远程控车的判定（纯逻辑，可单测）。
 *
 * ## 口径与官方对齐
 * 上游 `VehicleDrivingSafetyPolicy.isDrivingGear` 只看**挡位**：D / R 即视为行驶中，
 * 不看车速。这不是偷懒 —— 等红灯、低速跟车时车速可能是 0，但车随时会起步，
 * 此时远程开后备箱 / 降车窗 / 解锁都是真实的误触风险。所以「挂 D/R」本身就是结论，
 * 不需要再等车速信号。
 *
 * ## 但挡位残值必须排除
 * 本项目 2026-11-04 踩过的坑：车端熄火后并不总把 1010 刷成 P，有些车型停止刷新、
 * 残值还是熄火前的挡位（见 [DrivingStateRules] 的类注释）。若照抄「D/R 就拦」，
 * 会出现「人都锁车走了，首页所有控车按钮全灰、一个都点不了」——比不拦更糟。
 * 所以只要 [isShutDown] 成立（BCM 电源 OFF / EPB 拉起 / 非 D、R 挡的闭锁），
 * 挡位与车速一律当残值，不拦。
 *
 * ## 车速作为补充证据
 * 挡位缺失（老车况没有 1010）但报出车速 > 0.5 km/h，同样是「车在动」。
 * 阈值沿用 [DrivingStateRules.MOVING_THRESHOLD_KMH]：0.1、0.2 这种是 GPS / 报文抖动。
 *
 * ## 判不出来就放行
 * 车况为 null（还没拉到、后台冷进程读不到快照）时返回 false —— 宁可少拦一次，
 * 也不要因为「读不到车况」把整车控车功能锁死。
 */
object DrivingSafetyPolicy {

    /**
     * 拦截时给用户的那句话。
     *
     * 与官方 `DRIVING_OPERATION_PROHIBITED_HINT`（「为了您的安全，请停车后在操作」）
     * 同义，但按用户 2026-11-09 的原话写：「车辆正在行驶中，无法进行此项操作」。
     * 用户已经指明这一句，三处入口（首页 / 磁贴 / 小组件）必须**逐字相同**，
     * 否则同一个限制在两个地方说法不一样，会被当成两个 bug。
     */
    const val HINT = "车辆正在行驶中，无法进行此项操作"

    /**
     * 是否处于「行驶中、禁止控车」。
     *
     * @param gearStatus 挡位原始值（P / R / N / D，也可能是 P挡 / 前进 / 3 这类别名）
     * @param speedRaw   车速原始值（可能是 42、42.5、"42 km/h"）
     * @param isShutDown 已下电 / 已离车（见 [VehicleHomeStatus.isVehicleShutDown]）
     */
    fun isDriving(
        gearStatus: String?,
        speedRaw: String?,
        isShutDown: Boolean,
    ): Boolean {
        // 已熄火离车：挡位与车速都只能是休眠前留下的残值
        if (isShutDown) return false
        val gear = DrivingStateRules.normalizeGear(gearStatus)
        if (gear == "D" || gear == "R") return true
        val speed = DrivingStateRules.parseSpeed(speedRaw) ?: return false
        return speed > DrivingStateRules.MOVING_THRESHOLD_KMH
    }

    /** 车况版本。车况为 null（读不到）时放行，见类注释「判不出来就放行」。 */
    fun isDriving(status: CarStatus?): Boolean {
        if (status == null) return false
        return isDriving(status.gearStatus, status.speed, status.isShutDown)
    }
}
