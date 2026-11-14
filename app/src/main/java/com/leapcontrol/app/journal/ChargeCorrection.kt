package com.leapcontrol.app.journal

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「手动修正上次充电」的输入解析与校验（纯逻辑，无 Android 依赖，可单测）。
 *
 * ## 为什么需要它
 * 充电起点是零控自己「看见」的：整段充电期间一次都没拿到车况（插枪前没打开 App、
 * 充完直接开走），那次充电就漏了，卡上的「自上次充电行驶里程」会一直算到更早那次。
 * 车端与云端都没有充电记录接口可查（见 [ChargeHistory]），补不回来 —— 只能让用户
 * 照着车机 / 保养单，把三个数填进来：
 *
 *  - 上次充满电（拔枪）是什么时候 → [ChargeAnchor.at]
 *  - 那次充到了百分之多少 → [ChargeAnchor.socPeak]
 *  - 充电时的车辆总里程 → [ChargeAnchor.km]，卡片拿当前总里程减它
 *
 * ## 校验原则
 * 三格一起校验、一起写：任何一格不合法就整条不落盘，绝不写「半截修正」。
 * 日期时间给几种常见写法（含中文「11月10日」），但**不猜年份以外的东西** ——
 * 缺年份时默认今年，因为用户修的是「上次」充电。
 */
object ChargeCorrection {

    /** 一条完整的修正值。 */
    data class Values(
        /** 上次充电的时刻（毫秒，本地时区）。 */
        val at: Long,
        /** 充到百分之多少；null = 用户不知道 / 不想填。 */
        val socPeak: Int?,
        /** 充电时的表显总里程（km）。 */
        val km: Double,
    )

    /** 哪格填错了，界面照着它给红字。 */
    enum class Field { DATE, TIME, PERCENT, KM, FUTURE }

    sealed interface Parse {
        data class Ok(val values: Values) : Parse
        data class Err(val field: Field) : Parse
    }

    /** 允许一点点时钟偏差（毫秒）：手机快 30 秒不该让「刚才」变成非法值。 */
    private const val FUTURE_TOLERANCE_MS = 60_000L

    private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE

    /** 弹窗回填：`2026-11-10`。 */
    fun formatDate(millis: Long): String =
        JournalDates.localDateTimeOf(millis).toLocalDate().format(ISO_DATE)

    /** 弹窗回填：`22:30`。 */
    fun formatTime(millis: Long): String {
        val t = JournalDates.localDateTimeOf(millis)
        return String.format(Locale.CHINA, "%02d:%02d", t.hour, t.minute)
    }

    /**
     * 解析日期。支持 `2026-11-10` / `2026/11/10` / `11-10` / `11月10日`；
     * 后两种补今年。年份缺失时不往前推一年 —— 跨年时用户会自己把年份写上，
     * 悄悄猜「去年」比让用户改一格更糟。
     */
    fun parseDate(raw: String, today: LocalDate = JournalDates.today()): LocalDate? {
        val text = raw.trim().replace(" ", "")
        if (text.isEmpty()) return null
        val chinese = Regex("""^(\d{1,2})月(\d{1,2})日?$""").find(text)
        if (chinese != null) {
            return dateOrNull(today.year, chinese.groupValues[1].toInt(), chinese.groupValues[2].toInt())
        }
        val parts = text.split("-", "/")
        return when (parts.size) {
            3 -> dateOrNull(
                parts[0].toIntOrNull() ?: return null,
                parts[1].toIntOrNull() ?: return null,
                parts[2].toIntOrNull() ?: return null,
            )
            2 -> dateOrNull(
                today.year,
                parts[0].toIntOrNull() ?: return null,
                parts[1].toIntOrNull() ?: return null,
            )
            else -> null
        }
    }

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /** 解析时间：`22:30` / `9:05` / `2230`。返回 (时, 分)。 */
    fun parseTime(raw: String): Pair<Int, Int>? {
        val text = raw.trim().replace(" ", "").replace("：", ":")
        if (text.isEmpty()) return null
        val hour: Int
        val minute: Int
        if (text.contains(":")) {
            val parts = text.split(":")
            if (parts.size != 2) return null
            hour = parts[0].toIntOrNull() ?: return null
            minute = parts[1].toIntOrNull() ?: return null
        } else {
            if (text.length != 4) return null
            hour = text.substring(0, 2).toIntOrNull() ?: return null
            minute = text.substring(2).toIntOrNull() ?: return null
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /** 电量：`90` / `90%` 都行；留空返回 null（允许不填「充到多少」）。 */
    fun parsePercent(raw: String): Int? {
        val text = raw.trim().replace("%", "").replace("％", "").replace(" ", "")
        if (text.isEmpty()) return null
        return text.toIntOrNull()?.takeIf { it in 0..100 }
    }

    /** 百分号写出来了但数字越界时，要和「没填」区分开。 */
    fun percentRawMeaningful(raw: String): Boolean = raw.trim()
        .replace("%", "").replace("％", "").replace(" ", "").isNotEmpty()

    /** 总里程：`12345.6` / `12,345` / `12345km` 都行。 */
    fun parseKm(raw: String): Double? {
        val text = raw.trim()
            .replace(",", "")
            .replace("，", "")
            .replace(" ", "")
            .replace("km", "", ignoreCase = true)
            .replace("公里", "")
        val value = text.toDoubleOrNull() ?: return null
        return value.takeIf { ChargeCycle.isUsableKm(it) }
    }

    /**
     * 把四个输入框折成一条修正值。任何一格不合法都返回 [Parse.Err]，调用方不写盘。
     *
     * 未来时间单独一档错误：卡片上的「跑了几天」按自然日算，填成明天会让数字变成 0，
     * 而且「上次充电」不该发生在未来。
     */
    fun build(
        dateRaw: String,
        timeRaw: String,
        percentRaw: String,
        kmRaw: String,
        now: Long = System.currentTimeMillis(),
        today: LocalDate = JournalDates.today(),
    ): Parse {
        val date = parseDate(dateRaw, today) ?: return Parse.Err(Field.DATE)
        val (hour, minute) = parseTime(timeRaw) ?: return Parse.Err(Field.TIME)
        if (percentRawMeaningful(percentRaw) && parsePercent(percentRaw) == null) {
            return Parse.Err(Field.PERCENT)
        }
        val socPeak = parsePercent(percentRaw)
        val km = parseKm(kmRaw) ?: return Parse.Err(Field.KM)
        val at = JournalDates.timestampOf(date, hour, minute)
        if (at > now + FUTURE_TOLERANCE_MS) return Parse.Err(Field.FUTURE)
        return Parse.Ok(Values(at = at, socPeak = socPeak, km = km))
    }

    /** 错误提示，界面只负责显示。 */
    fun errorText(field: Field): String = when (field) {
        Field.DATE -> "日期填成「2026-11-10」或「11月10日」这种格式"
        Field.TIME -> "时间填成「22:30」这种格式（24 小时制）"
        Field.PERCENT -> "充到多少填 0–100 之间的整数，不知道就留空"
        Field.KM -> "总里程填仪表盘上的数字，例如 12345.6"
        Field.FUTURE -> "充电时间不能晚于现在"
    }
}
