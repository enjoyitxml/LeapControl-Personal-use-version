package com.leapcontrol.app.journal

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 一条充电记录：什么时候开始、什么时候结束、从多少充到多少。
 *
 * ## 为什么只能由 App 自己攒
 * 车端没有充电历史接口，云端也没有（`drivingrecord/...` 只给按天的里程与能耗）。
 * `signalMap` 里充电域就 9 个信号，全是最新一帧的实时值，**没有时间戳**。
 * 所以这份历史完全来自零控在充电期间「看见」的那些车况 —— 详见 [ChargeAnchor]。
 *
 * 这意味着两件事，界面上必须让用户知道：
 *  - 开始时间是「第一次看见它在充电」，不是插枪那一刻（晚多少，取决于上一次刷新）；
 *  - 整段充电一次都没被看见（插枪后没打开过零控、闹钟也被系统拦了）就**没有这条记录**。
 *
 * ## 为什么结束时间用 `lastChargingAt` 而不是「发现停了」的时刻
 * 夜里充完、早上才打开 App：「发现停了」会是早上 7 点，而实际 1 点就停了。
 * `ChargeWatch` 在充电期间每 1 分钟看一眼，最后一格离真正结束最多差 1 分钟，
 * 拿它当结束时间既更准，也和「充到多少」那一格自洽（同一个观测点）。
 */
data class ChargeSession(
    /** 开始时刻（毫秒）：第一次看见这次充电的时间。 */
    val startAt: Long,
    /** 结束时刻（毫秒）：最后一次看见它还在充电的时间；null = 还没结束 / 不知道。 */
    val endAt: Long? = null,
    /** 「从 X% 充起」：没看到起点（例如只赶上了「已充满」）时为 null。 */
    val socFrom: Int? = null,
    /** 「充到 Y%」。 */
    val socTo: Int? = null,
    /**
     * 起点表显续航（km）。用户 2026-11-20 要求记录「续航从多少涨到多少」——
     * 增程车不记（综合续航含油），老数据没有这一格就是 null。
     */
    val rangeFromKm: Double? = null,
    /** 终点表显续航（km）：和 [socTo] 同一格。 */
    val rangeToKm: Double? = null,
    /** 车端报过「已完成」。不一定是 100%：健康充电上限 90% 时也会报。 */
    val full: Boolean = false,
    /** 起点表显里程（km）。只用来判断两条记录是不是同一次充电被「暂停」切开的。 */
    val km: Double? = null,
    /** true = 这条是用户手动修正出来的，不是观测到的充电过程（没有可信的起止区间）。 */
    val edited: Boolean = false,
) {
    /** 有起点也有终点、且电量真的涨了 —— 能写成「32% → 88%」。 */
    val hasRange: Boolean
        get() = socFrom != null && socTo != null && socTo > socFrom!!

    /** 只知道充到多少，不知道从多少充起。 */
    val onlyPeak: Boolean
        get() = socFrom == null && (socTo != null || full)

    /**
     * 这次充进去几个百分点；说不出来（缺起点 / 缺终点 / 没涨）时为 null。
     *
     * 只做减法，不掺电池容量 —— 容量是用户的输入，换算成度数由
     * [ChargeHistory.kwhOf] 现算，这样用户后来把容量填对，历史数字也跟着变对。
     */
    val socGain: Int?
        get() = if (hasRange) socTo!! - socFrom!! else null

    /** 表显续航涨了多少 km；两格都有且真的涨了才算。 */
    val rangeGainKm: Double?
        get() = if (rangeFromKm != null && rangeToKm != null) {
            (rangeToKm - rangeFromKm).takeIf { it > 0.0 }
        } else null

    /** 时长（毫秒）；结束时间不知道时为 null。 */
    val durationMs: Long?
        get() = endAt?.takeIf { it >= startAt }?.minus(startAt)

    fun toJson(): JSONObject = JSONObject().apply {
        put("startAt", startAt)
        endAt?.let { put("endAt", it) }
        socFrom?.let { put("socFrom", it) }
        socTo?.let { put("socTo", it) }
        rangeFromKm?.let { put("rngFrom", it) }
        rangeToKm?.let { put("rngTo", it) }
        if (full) put("full", true)
        km?.let { put("km", it) }
        if (edited) put("edited", true)
    }

    companion object {
        fun fromJson(raw: JSONObject?): ChargeSession? {
            if (raw == null) return null
            val start = raw.opt("startAt")?.toString()?.toLongOrNull() ?: return null
            if (start <= 0L) return null
            val end = raw.opt("endAt")?.toString()?.toLongOrNull()?.takeIf { it >= start }
            val socFrom = raw.opt("socFrom")?.toString()?.toIntOrNull()?.takeIf { it in 0..100 }
            val socTo = raw.opt("socTo")?.toString()?.toIntOrNull()?.takeIf { it in 0..100 }
            val full = raw.optBoolean("full", false)
            val edited = raw.optBoolean("edited", false)
            // 一条既没有电量区间、也没说到顶、又不是手动修正的记录没有任何可读信息，别留在列表里
            if (socFrom == null && socTo == null && !full && !edited) return null
            return ChargeSession(
                startAt = start,
                endAt = end,
                socFrom = socFrom,
                socTo = socTo,
                rangeFromKm = raw.opt("rngFrom")?.toString()?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0.0 },
                rangeToKm = raw.opt("rngTo")?.toString()?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0.0 },
                full = full,
                km = raw.opt("km")?.toString()?.toDoubleOrNull(),
                edited = edited,
            )
        }

        fun listToJson(list: List<ChargeSession>): JSONArray = JSONArray().apply {
            list.forEach { put(it.toJson()) }
        }

        fun listFromJson(raw: JSONArray?): List<ChargeSession> {
            if (raw == null) return emptyList()
            val out = ArrayList<ChargeSession>(raw.length())
            for (i in 0 until raw.length()) {
                fromJson(raw.optJSONObject(i))?.let { out.add(it) }
            }
            return out
        }
    }
}

/**
 * 充电历史的归档规则（纯逻辑，无 Android 依赖，可单测）。
 *
 * 归档时机只有一个：**上一次的起点被新的起点顶掉那一刻**（[ChargeCycle] 里
 * 「新建 anchor 覆盖旧的」）。所以正在进行的这一次不在历史里，由界面用当前
 * 起点实时渲染（[ChargeHistory.sessionOf] 同一个转换）。
 */
object ChargeHistory {

    /**
     * 最多留多少条。
     *
     * 一条约 100 字节，200 条也就 20KB；再多的意义不大 —— 半年后谁还翻两年前的充电记录。
     * 超出按时间丢掉最老的（列表按时间正序存，丢头部）。
     */
    const val MAX_ENTRIES = 200

    /**
     * 两次记录能不能合并成一次充电。
     *
     * 车端在「充电暂停」（`chargeState=6`）时会让我们收尾一次，恢复后又开一段 ——
     * 同一个车位、电量接着涨、间隔一小时以内，那显然是同一次充电被切成了两段。
     * 不合并的话历史里会出现「40%→68%」「68%→90%」两条，看起来像记错了。
     */
    private const val MERGE_GAP_MS = 60 * 60 * 1000L

    /** 合并要求两次的起点里程几乎一致（充电时车不会动）。 */
    private const val MERGE_KM = 0.5

    /**
     * 把「被顶掉的上一次起点」归档进历史。
     *
     * @param previous 归档前的起点
     * @param next 新的起点（只用来在缺结束时间时兜底、以及判断是不是同一次）
     */
    fun archive(
        history: List<ChargeSession>,
        previous: ChargeAnchor?,
        next: ChargeAnchor?,
    ): List<ChargeSession> {
        if (previous == null) return history
        // 同一场充电（起点没换）不该归档
        if (next != null && next.at == previous.at) return history
        val base = sessionOf(previous) ?: return history
        // 没记到「最后看见充电」也没收尾（比如车直接开走了）：用下一次开始兜底，
        // 至少不会写出一个横跨几天的时长
        val session = if (base.endAt != null) {
            base
        } else {
            base.copy(endAt = next?.at?.takeIf { it >= base.startAt })
        }
        return mergeOrAppend(history, session)
    }

    /**
     * 把一个起点换算成一条记录；**不值得记的返回 null**。
     *
     * 丢掉的只有一种：电量区间说不出来（起点电量没看到、峰值也没涨）。
     * 「只赶上已充满」的那次**要留**：至少告诉用户「那天充到 100% 了」，
     * 只是起点写「未记录」，不编一个假数字。
     */
    fun sessionOf(anchor: ChargeAnchor): ChargeSession? {
        if (anchor.at <= 0L) return null
        val from = anchor.soc?.takeIf { it in 0..100 }
        val to = anchor.socPeak?.takeIf { it in 0..100 }
        // 手动修正过的记录即使只填了「充到多少」也要能进历史：用户填的就是这一格，
        // 因为缺「从多少充起」而把整条丢掉，看起来就像修正没生效。
        val meaningful = anchor.full || (from != null && to != null && to > from) ||
            (anchor.edited && (from != null || to != null))
        if (!meaningful) return null
        val end = (anchor.lastChargingAt ?: anchor.endedAt)?.takeIf { it >= anchor.at }
        return ChargeSession(
            startAt = anchor.at,
            endAt = end,
            socFrom = from,
            socTo = to,
            rangeFromKm = anchor.rangeKm,
            rangeToKm = anchor.rangePeakKm,
            full = anchor.full,
            km = anchor.km,
            edited = anchor.edited,
        )
    }

    /**
     * 正在进行的这一次（还没归档，界面上跟历史拼在同一个列表里）。
     *
     * 充电中时电量取「已记到的峰值」和「当前读数」里更大的那个 —— 峰值是采样到的，
     * 当前读数是点开这一页时的，两者都算「看见过」。结束时按归档口径原样返回。
     */
    fun liveSession(
        anchor: ChargeAnchor?,
        currentSoc: Int?,
        charging: Boolean,
    ): ChargeSession? {
        if (anchor == null || anchor.at <= 0L) return null
        val stored = sessionOf(anchor)
        if (!charging) return stored
        val soc = currentSoc?.takeIf { it in 0..100 }
        return ChargeSession(
            startAt = anchor.at,
            endAt = null,
            socFrom = anchor.soc ?: soc,
            socTo = largerSoc(anchor.socPeak, soc),
            rangeFromKm = anchor.rangeKm,
            rangeToKm = anchor.rangePeakKm,
            full = anchor.full,
            km = anchor.km,
            edited = anchor.edited,
        )
    }

    /**
     * 两份历史合到一起（备份还原 / 两台手机之间合并）。
     *
     * 按开始时刻去重：同一次充电在两边各记一条时，时间戳就是我们能拿到的最接近的身份。
     * 合并后仍按时间正序、仍受 [MAX_ENTRIES] 限制。
     */
    fun union(a: List<ChargeSession>, b: List<ChargeSession>): List<ChargeSession> {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val byStart = LinkedHashMap<Long, ChargeSession>()
        (a + b).sortedBy { it.startAt }.forEach { byStart[it.startAt] = it }
        return byStart.values.toList().takeLast(MAX_ENTRIES)
    }

    /** 追加一条记录（能合并就合并），并执行条数上限。 */
    fun mergeOrAppend(
        history: List<ChargeSession>,
        session: ChargeSession,
    ): List<ChargeSession> {
        val last = history.lastOrNull()
        if (last == null || !canMerge(last, session)) {
            return (history + session).takeLast(MAX_ENTRIES)
        }
        val merged = last.copy(
            endAt = session.endAt ?: last.endAt,
            socTo = largerSoc(last.socTo, session.socTo),
            // 续航跟着 socTo 往上走：两段里后一段才是「充到」的那一格
            rangeToKm = largerRange(last.rangeToKm, session.rangeToKm),
            full = last.full || session.full,
        )
        return (history.dropLast(1) + merged).takeLast(MAX_ENTRIES)
    }

    private fun canMerge(last: ChargeSession, next: ChargeSession): Boolean {
        val end = last.endAt ?: return false
        if (next.startAt < end) return false
        if (next.startAt - end > MERGE_GAP_MS) return false
        // 已经充满之后又插上：那是另一次，别并进上一条
        if (last.full) return false
        val a = last.km
        val b = next.km
        if (a == null || b == null) return false
        if (abs(a - b) > MERGE_KM) return false
        // 电量要接着上一格往上走（看不到起点时无从判断，按「接着」算）
        if (next.socFrom != null && last.socTo != null && next.socFrom > last.socTo!! + 1) return false
        if (next.socTo != null && last.socTo != null && next.socTo!! <= last.socTo!!) return false
        return true
    }

    private fun largerRange(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    /**
     * 这次充电充进去多少度电（kWh）。
     *
     * 口径：`容量 × 充进的百分点 ÷ 100`，也就是**进电池的能量**，不含充电损耗
     * （交流慢充全程损耗 10~15%，车端也不给这一路的计量）。所以界面上一律写「约」，
     * 不写成「电表走了几度」。
     *
     * 没有电池容量、或这次记录说不清电量区间 → null，绝不拿 0 顶。
     */
    fun kwhOf(session: ChargeSession, capacityKwh: Double?): Double? {
        val gain = session.socGain ?: return null
        val capacity = capacityKwh?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return capacity * gain / 100.0
    }

    private fun largerSoc(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}
