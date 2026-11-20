package com.leapcontrol.app.journal

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 充电历史的时段汇总（纯逻辑，无 Android 依赖，可单测）。
 *
 * ## 三档 Tab + 可指定的区间
 * 2026-11-20 第一版只有「近 7 天 / 本月 / 今年」三张并列卡片；同一天用户又要求
 * 改成官方那种可选形式：点「月」能选具体哪个月、点「年」能选哪一年。
 * 于是拆成两层：[ChargeTab] 只管顶部三格（近 7 天 / 月 / 年），真正参与计算的是
 * [ChargeRange] —— 它带着具体的年月，窗口边界由它自己算。
 *
 * ## 归到哪一段，只看**开始时间**
 * 一次充电可能横跨零点（23:10 插枪、07:40 拔枪）。按月 / 按年统计时把同一段拆到两段里
 * 反而不像人话 —— 用户问的是「这个月充了几次、充了多少」，答案就是「有几次是在这个月开始的」。
 * 所以窗口一律按 [ChargeSession.startAt] 判定。
 *
 * ## 度数只能是**估算**
 * 车端不给充电计量，`容量 × 充进的百分点 ÷ 100` 是唯一算得出来的口径，且不含充电损耗。
 * 所以文案必须带「约」，别让它看起来像电表读数。
 */
/** 毫秒 → 本地日期。窗口边界与分桶都按设备时区算，跨时区出行时以当时时区为准。 */
private fun localDateOf(millis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

enum class ChargeTab(val label: String) {
    /** 近 7 天：含今天在内的 7 个自然日（不是「最近 168 小时」）。 */
    WEEK("近 7 天"),

    /** 月：默认当月，可切到任意有记录的月份。 */
    MONTH("月"),

    /** 年：默认今年，可切到任意有记录的年份。 */
    YEAR("年"),
    ;

    /** 切到这一档时默认选中的区间。 */
    fun defaultRange(now: Long, zone: ZoneId = ZoneId.systemDefault()): ChargeRange {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (this) {
            WEEK -> ChargeRange.Last7Days
            MONTH -> ChargeRange.Month(today.year, today.monthValue)
            YEAR -> ChargeRange.Year(today.year)
        }
    }
}

/**
 * 一段可选的统计区间。
 *
 * [endMillis] 一律截到 `now`：未来时间戳是脏数据，算进去会让「今年充了 8 次」凭空多一次。
 * 历史月份 / 年份的右边界是下个月 / 下一年的 1 日 00:00（不含）。
 */
sealed class ChargeRange {

    /** 汇总卡与下拉框上显示的名字。 */
    abstract val title: String

    /** 窗口起点（含），毫秒。 */
    abstract fun startMillis(now: Long, zone: ZoneId): Long

    /** 窗口终点（不含），毫秒。 */
    abstract fun endMillis(now: Long, zone: ZoneId): Long

    data object Last7Days : ChargeRange() {
        override val title: String get() = "近 7 天"

        override fun startMillis(now: Long, zone: ZoneId): Long = localDateOf(now, zone)
            .minusDays(6)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        override fun endMillis(now: Long, zone: ZoneId): Long = now + 1
    }

    data class Month(val year: Int, val month: Int) : ChargeRange() {
        override val title: String get() = "${year}年${month}月"

        override fun startMillis(now: Long, zone: ZoneId): Long = LocalDate.of(year, month, 1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        override fun endMillis(now: Long, zone: ZoneId): Long {
            val next = LocalDate.of(year, month, 1)
                .plusMonths(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            return minOf(next, now + 1)
        }
    }

    data class Year(val year: Int) : ChargeRange() {
        override val title: String get() = "${year}年"

        override fun startMillis(now: Long, zone: ZoneId): Long = LocalDate.of(year, 1, 1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        override fun endMillis(now: Long, zone: ZoneId): Long {
            val next = LocalDate.of(year + 1, 1, 1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            return minOf(next, now + 1)
        }
    }
}

/**
 * 一段时间的汇总。
 *
 * [sessions] 是这段时间内的**全部**次数；[measurable] 是其中能算出度数的条数
 * —— 两者不等时界面要能解释「为什么数字比次数少」（缺起点电量 / 没填电池容量）。
 */
data class ChargePeriodStat(
    val range: ChargeRange,
    val sessions: Int,
    val measurable: Int,
    /** 充进去多少度（约）；一条都算不出来时为 null，不是 0。 */
    val kwh: Double?,
    /** 表显续航一共涨了多少 km（约）；没有续航记录时为 null。 */
    val rangeGainKm: Double?,
) {
    val empty: Boolean get() = sessions == 0
}

/**
 * 柱状图上的一格。
 *
 * [label] 是 x 轴文字（按天就是日号，按年就是月份）；[value] 的量纲由
 * [ChargeBars.unit] 决定（有电池容量时是 kWh，没有时退回次数 —— 次数永远算得出来）。
 */
data class ChargeBar(val label: String, val value: Double, val count: Int)

/** 一段区间的分布图数据。 */
data class ChargeBars(
    /** `kWh` 或 `次`；界面直接当 y 轴单位显示。 */
    val unit: String,
    val items: List<ChargeBar>,
) {
    val peak: Double get() = items.maxOfOrNull { it.value } ?: 0.0
    val empty: Boolean get() = peak <= 0.0
}

object ChargeStats {

    /**
     * 汇总一段。
     *
     * @param sessions 历史 + 正在进行的那一次（正在充的也要计入，否则「今天充了一次」
     *   会等到拔枪之后才出现，看着像没记上）
     * @param capacityKwh 用户在设置里填的电池容量；没有就只给次数和续航
     */
    fun of(
        sessions: List<ChargeSession>,
        range: ChargeRange,
        now: Long,
        capacityKwh: Double?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ChargePeriodStat {
        val inWindow = inWindow(sessions, range, now, zone)
        var kwh = 0.0
        var rangeGain = 0.0
        var measurable = 0
        var ranged = 0
        inWindow.forEach { session ->
            ChargeHistory.kwhOf(session, capacityKwh)?.let {
                kwh += it
                measurable++
            }
            session.rangeGainKm?.let {
                rangeGain += it
                ranged++
            }
        }
        return ChargePeriodStat(
            range = range,
            sessions = inWindow.size,
            measurable = measurable,
            kwh = kwh.takeIf { measurable > 0 },
            rangeGainKm = rangeGain.takeIf { ranged > 0 },
        )
    }

    /** 落在区间内的一次充电。窗口是左闭右开，未来时间戳自然被排除。 */
    fun inWindow(
        sessions: List<ChargeSession>,
        range: ChargeRange,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ChargeSession> {
        val start = range.startMillis(now, zone)
        val end = range.endMillis(now, zone)
        return sessions.filter { it.startAt in start until end }
    }

    /**
     * 有记录的月份，倒序（最新在前）。
     *
     * 当前月永远排在第一位，哪怕这个月还没记到 —— 用户切到「月」时第一眼要看到的是本月，
     * 不是翻回上个月。
     */
    fun monthsWith(
        sessions: List<ChargeSession>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<YearMonth> {
        val thisMonth = YearMonth.from(localDateOf(now, zone))
        val months = sessions.map { YearMonth.from(Instant.ofEpochMilli(it.startAt).atZone(zone)) }
            .filter { it <= thisMonth }
            .toMutableSet()
        months += thisMonth
        return months.sortedDescending()
    }

    /** 有记录的年份，倒序；当年永远在第一位（理由同 [monthsWith]）。 */
    fun yearsWith(
        sessions: List<ChargeSession>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Int> {
        val thisYear = localDateOf(now, zone).year
        val years = sessions.map { Instant.ofEpochMilli(it.startAt).atZone(zone).year }
            .filter { it <= thisYear }
            .toMutableSet()
        years += thisYear
        return years.sortedDescending()
    }

    /**
     * 分布图：近 7 天/按月按天、按年按月。
     *
     * 桶的口径是**充电开始时间**，与汇总一致 —— 否则柱状图加起来和上面的总数对不上。
     * 有电池容量时柱高是度数；一条都算不出来时退回次数，并在 y 轴写「次」。
     */
    fun bars(
        sessions: List<ChargeSession>,
        range: ChargeRange,
        now: Long,
        capacityKwh: Double?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ChargeBars {
        val inWindow = inWindow(sessions, range, now, zone)
        val useKwh = capacityKwh != null && inWindow.any { ChargeHistory.kwhOf(it, capacityKwh) != null }

        val keys = mutableListOf<Any>()
        val labels = mutableListOf<String>()
        when (range) {
            // 近 7 天：7 根柱，标签是日号
            is ChargeRange.Last7Days -> {
                val first = localDateOf(range.startMillis(now, zone), zone)
                for (i in 0 until 7) {
                    val day = first.plusDays(i.toLong())
                    keys += day
                    labels += day.dayOfMonth.toString()
                }
            }
            // 按月：一天一根，标签只标 1 号 / 逢 5 / 月末，否则 31 个数字会糊成一片
            is ChargeRange.Month -> {
                val last = YearMonth.of(range.year, range.month).lengthOfMonth()
                for (d in 1..last) {
                    keys += LocalDate.of(range.year, range.month, d)
                    labels += if (d == 1 || d % 5 == 0 || d == last) d.toString() else ""
                }
            }
            // 按年：12 根柱，标签是月份
            is ChargeRange.Year -> for (m in 1..12) {
                keys += m
                labels += m.toString()
            }
        }

        val index = keys.withIndex().associate { it.value to it.index }
        val values = DoubleArray(keys.size)
        val counts = IntArray(keys.size)
        inWindow.forEach { session ->
            val local = Instant.ofEpochMilli(session.startAt).atZone(zone)
            val key: Any = if (range is ChargeRange.Year) local.monthValue else local.toLocalDate()
            val i = index[key] ?: return@forEach
            counts[i]++
            if (useKwh) ChargeHistory.kwhOf(session, capacityKwh)?.let { values[i] += it }
        }

        val items = keys.indices.map { i ->
            ChargeBar(
                label = labels[i],
                // 退回次数时柱高就是次数；两者口径不同，y 轴单位会跟着变
                value = if (useKwh) values[i] else counts[i].toDouble(),
                count = counts[i],
            )
        }
        return ChargeBars(unit = if (useKwh) "kWh" else "次", items = items)
    }
}
