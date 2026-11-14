package com.leapcontrol.app

import com.leapcontrol.app.ui.CarStatus
import java.util.Locale

/**
 * 「这一帧车况是从什么时候起就是这些数」——首页左上角与桌面小组件显示的时间。
 *
 * ## 为什么不能直接用「我们拉取的时刻」
 *
 * 车况接口 `signal/info/query` 的报文里**没有车端时间戳**，能拿到的只有
 * 「这次请求什么时候返回的」。车机休眠时网关会把上一次的报文原样回给 App：
 * HTTP 200、请求成功，但数据其实是几小时前车机最后一次上报的。
 *
 * 于是 [AppRuntime.refreshStatus] 每成功一次就把时间写成「现在」，界面就会写着
 * 「更新于 刚刚」——**假新鲜**。用户据此判断「车还醒着、锁还在、电量还是这个数」，
 * 一旦数据是旧的，就会误判车辆状态（这正是位置卡当初加
 * [com.leapcontrol.app.LocationAnchorRule] 的原因，这里把同一套判断推广到整车况）。
 *
 * ## 判断口径
 *
 * 只认一个事实：**这帧数据有没有变过**。
 *
 * - 关键信号变了 → 车机肯定现场上报过 → 时间记为现在；
 * - 关键信号一模一样 → 无法证明是车机刚上报的（很可能就是网关缓存）→ **沿用上一次的时间**。
 *
 * 车真的在跑时 SOC / 续航 / 车速每帧都变，时间照样是「刚刚」；
 * 车停在车库、车机已休眠时，时间会一直往回长，长到「3 小时前」——
 * 那正是该怀疑这条车况的时候。
 *
 * 刻意**不把易漂的信号算进指纹**（如定位精度、信号强度），否则每帧都被判成「变了」，
 * 等于没做。
 */
data class StatusAnchor(
    /** 关键信号的摘要，用来判断两帧是不是同一份数据。 */
    val fingerprint: String,
    /** 这串指纹第一次被看到的时刻（毫秒）。 */
    val at: Long,
)

/** [StatusAnchor] 的推进规则（纯逻辑，可单测）。 */
object StatusFreshness {

    /**
     * 用这一帧车况算摘要。
     *
     * 只放**会随车辆状态变化**的字段：电量、续航、里程、门窗锁、空调、挡位车速、
     * 充电读数、座椅、胎压、坐标。放进来的字段越多，「车机真的上报了」的证据越充分；
     * 但绝不能放时间类字段（[CarStatus.locationSeenAt]），否则自己跟自己比永远不等。
     */
    fun fingerprint(status: CarStatus): String = buildString {
        // 电量 / 续航 / 里程
        append("soc=").append(status.soc)
        append("|socS=").append(decimal(status.socSignal))
        append("|rng=").append(status.range.orEmpty())
        append("|eRng=").append(status.electricRange.orEmpty())
        append("|fRng=").append(status.fuelRange.orEmpty())
        append("|fSoc=").append(status.fuelSoc ?: "")
        append("|odo=").append(status.totalMileage.orEmpty())
        // 锁 / 门窗 / 后备箱
        append("|lk=").append(status.locked ?: "")
        append("|dr=").append(status.doors?.toString().orEmpty())
        append("|win=").append(status.windowOpen ?: "")
        append("|tr=").append(status.trunkOpen ?: "")
        append("|fr=").append(status.frunkOpen ?: "")
        append("|sr=").append(status.sunroofOpen ?: "")
        append("|sh=").append(status.sunshadeOpen ?: "")
        append("|sentry=").append(status.sentryOn ?: "")
        // 空调 / 舒适件
        append("|ac=").append(status.acOn ?: "")
        append("|acT=").append(status.acTargetTemp.orEmpty())
        append("|acW=").append(status.acWindLevel.orEmpty())
        append("|acC=").append(status.acCircle.orEmpty())
        append("|acP=").append(status.acPosition.orEmpty())
        append("|acM=").append(status.acMode.orEmpty())
        append("|acD=").append(status.windshieldDefrost ?: "")
        append("|tmp=").append(integer(status.interiorTemp))
        append("|seatH=").append(status.seatHeating?.toString().orEmpty())
        append("|seatV=").append(status.seatVentilation?.toString().orEmpty())
        append("|swh=").append(status.steeringWheelHeating ?: "")
        append("|rmh=").append(status.rearviewMirrorHeating ?: "")
        append("|fridge=").append(status.fridge?.toString().orEmpty())
        // 挡位 / 车速 / 上下电：行驶中每帧都在变，是最有力的「车机醒着」证据
        append("|gear=").append(status.gearStatus.orEmpty())
        append("|spd=").append(integer(status.speed))
        append("|drv=").append(status.drivingStatus.orEmpty())
        append("|run=").append(status.isDriving ?: "")
        append("|off=").append(status.isShutDown)
        // 充电：充电中电压电流在变，不充电时也保持稳定
        append("|cs=").append(status.chargeState ?: "")
        append("|cg=").append(status.charging ?: "")
        append("|cb=").append(status.chargeCableConnected ?: "")
        append("|cp=").append(status.chargingPower.orEmpty())
        append("|cv=").append(status.chargeVoltage.orEmpty())
        append("|cc=").append(status.chargeCurrent.orEmpty())
        append("|bt=").append(status.batteryTemp.orEmpty())
        append("|cm=").append(status.chargeRemainMinutes ?: "")
        append("|ht=").append(status.healthyChargeEnabled ?: "")
        append("|pre=").append(status.batteryPreheatOn ?: "")
        // 胎压：慢漏气 / 温度变化能反映车机在实时上报
        append("|ti=").append(
            status.tires.joinToString(",") { "${it.position}:${it.pressure.orEmpty()}/${it.temperature.orEmpty()}" },
        )
        // 坐标取 5 位小数（约 1 米）：车在跑就会变，停着不动 GPS 漂移也不会每帧都变
        append("|lat=").append(coordinate(status.latitude))
        append("|lng=").append(coordinate(status.longitude))
    }

    /**
     * 用这一帧推进锚点。
     *
     * 指纹和上一帧一模一样就**原样返回旧锚点**（时间不前进）。
     * [previous] 为空（冷启动且没有存过锚点）时记成 [now]。
     */
    fun next(previous: StatusAnchor?, fingerprint: String, now: Long): StatusAnchor {
        if (previous != null && previous.at > 0L && previous.fingerprint == fingerprint) return previous
        return StatusAnchor(fingerprint, now)
    }

    /** `22` 与 `22.0` 是同一个温度，别因为格式差异判成「变了」。 */
    private fun integer(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val value = text.toDoubleOrNull() ?: return text
        return String.format(Locale.US, "%.0f", value)
    }

    private fun decimal(raw: Double?): String =
        if (raw == null) "" else String.format(Locale.US, "%.2f", raw)

    private fun coordinate(raw: Double?): String =
        if (raw == null) "" else String.format(Locale.US, "%.5f", raw)
}
