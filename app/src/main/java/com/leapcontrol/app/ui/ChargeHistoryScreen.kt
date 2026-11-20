package com.leapcontrol.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.journal.ChargeAnchor
import com.leapcontrol.app.journal.ChargeBars
import com.leapcontrol.app.journal.ChargeHistory
import com.leapcontrol.app.journal.ChargePeriodStat
import com.leapcontrol.app.journal.ChargeRange
import com.leapcontrol.app.journal.ChargeSession
import com.leapcontrol.app.journal.ChargeStats
import com.leapcontrol.app.journal.ChargeTab
import com.leapcontrol.app.ui.theme.statusGood
import com.leapcontrol.app.ui.theme.statusGoodDark
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 充电历史（充电中心 → 右上角「充电历史」）。
 *
 * ## 这份数据是零控自己攒的
 * 车端和云端都没有充电记录接口：`signalMap` 里充电域只有实时值、没有时间戳，
 * `drivingrecord/...` 只给按天的里程与能耗。所以每条记录都是「零控看见它在充电」时
 * 记下来的那一格 —— 开始时间晚于真正插枪的时刻，误差取决于当时多久刷一次车况。
 *
 * 页面顶部必须把这件事说清楚，否则用户看到「开始 07:12」而自己明明是 23:00 插的枪，
 * 只会觉得是 App 记错了。宁可写明白限制，也不要让它看起来像官方数据。
 *
 * ## 可选时段（2026-11-20 第二版）
 * 顶部三格「近 7 天 / 月 / 年」是同一份数据的不同窗口，切档只换窗口、不换页面：
 * - 月档右上角能选具体哪个月，年档能选哪一年（列表只列有记录的 + 当前那个）；
 * - 柱状图跟着窗口走：近 7 天/按月按天，按年按月；
 * - 下面的记录列表也只留窗口内的条目，否则切到 9 月却看见 11 月的记录会很怪。
 */
@Composable
fun ChargeHistoryScreen(
    history: List<ChargeSession>,
    anchor: ChargeAnchor?,
    currentSoc: Int?,
    charging: Boolean,
    now: Long,
    onBack: () -> Unit,
    /** 电池容量（kWh）：有它才能把「充了几个百分点」换算成度数。 */
    batteryKwh: Double? = null,
) {
    val C = LocalAppColors.current
    val live = ChargeHistory.liveSession(anchor, currentSoc, charging)
    // 已在历史里的不动，只把「正在进行 / 最近这一次」贴在最新的位置
    val rows = (history + listOfNotNull(live)).sortedByDescending { it.startAt }

    val months = ChargeStats.monthsWith(rows, now)
    val years = ChargeStats.yearsWith(rows, now)

    // 切页面回来保持上次看的时段：查月度数据时被打断，回来不用重新点一遍
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var monthIndex by rememberSaveable { mutableIntStateOf(0) }
    var yearIndex by rememberSaveable { mutableIntStateOf(0) }

    val tab = ChargeTab.entries[tabIndex.coerceIn(0, ChargeTab.entries.lastIndex)]
    val month = months[monthIndex.coerceIn(0, months.lastIndex)]
    val year = years[yearIndex.coerceIn(0, years.lastIndex)]
    val range: ChargeRange = when (tab) {
        ChargeTab.WEEK -> ChargeRange.Last7Days
        ChargeTab.MONTH -> ChargeRange.Month(month.year, month.monthValue)
        ChargeTab.YEAR -> ChargeRange.Year(year)
    }

    // 正在充的那一次也要计入：否则用户一边充一边看，今天的数字是 0
    val stat = ChargeStats.of(rows, range, now, batteryKwh)
    val bars = ChargeStats.bars(rows, range, now, batteryKwh)
    val inRange = ChargeStats.inWindow(rows, range, now)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        ChargeTopBar(title = "充电历史", onBack = onBack)

        Spacer(Modifier.height(4.dp))
        Text(
            text = "记录的是零控看到它正在充电的时刻，开始时间可能晚于实际插枪；" +
                "整段充电期间一次都没打开过零控，那一次就没有记录 —— " +
                "可以在首页「自上次充电行驶里程」卡里手动补上。",
            color = C.textSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        if (rows.isEmpty()) {
            Spacer(Modifier.height(60.dp))
            EmptyHistory()
        } else {
            Spacer(Modifier.height(14.dp))
            JournalSegmented(
                options = ChargeTab.entries.map { it.label },
                selectedIndex = tabIndex,
                onSelect = { tabIndex = it },
            )
            Spacer(Modifier.height(10.dp))
            ChargePeriodCard(
                stat = stat,
                tab = tab,
                months = months.map { "${it.year}年${it.monthValue}月" },
                monthIndex = monthIndex,
                onMonth = { monthIndex = it },
                years = years.map { "${it}年" },
                yearIndex = yearIndex,
                onYear = { yearIndex = it },
                batteryKwh = batteryKwh,
            )
            if (!bars.empty) {
                Spacer(Modifier.height(10.dp))
                ChargeDistributionCard(bars)
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "充电详情",
                    color = C.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (inRange.isEmpty()) "没有记录" else "${inRange.size} 次",
                    color = C.textSecondary,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (inRange.isEmpty()) {
                ChargePanel(padding = 16.dp, background = C.chipBg) {
                    Text(
                        text = "这个时段还没有充电记录。",
                        color = C.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            } else {
                inRange.forEachIndexed { index, session ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    ChargeSessionCard(
                        session = session,
                        live = live != null && session === live,
                        now = now,
                        batteryKwh = batteryKwh,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun EmptyHistory() {
    val C = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "还没有充电记录",
            color = C.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "插上充电枪后打开一次零控，这次充电就会自动记下来；" +
                "充电期间零控会定时看一眼进度，不用一直开着。",
            color = C.textSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 时段汇总卡：左边两个大数字（度数 / 次数），右上角是区间的选择器。
 *
 * 近 7 天只有一个窗口，选择器退化成静态文字；月 / 年档才可点开下拉。
 * 两个数字的量纲写在数字后面（「448.5 度」「12 次」），不做单位标签 —— 一眼能读完。
 */
@Composable
private fun ChargePeriodCard(
    stat: ChargePeriodStat,
    tab: ChargeTab,
    months: List<String>,
    monthIndex: Int,
    onMonth: (Int) -> Unit,
    years: List<String>,
    yearIndex: Int,
    onYear: (Int) -> Unit,
    batteryKwh: Double?,
) {
    val C = LocalAppColors.current
    ChargePanel(padding = 16.dp, background = C.chipBg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "充电统计",
                color = C.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            when (tab) {
                ChargeTab.WEEK -> Text(
                    text = ChargeRange.Last7Days.title,
                    color = C.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                ChargeTab.MONTH -> RangeDropdown(
                    label = months.getOrElse(monthIndex) { "" },
                    options = months,
                    selectedIndex = monthIndex,
                    onSelect = onMonth,
                )
                ChargeTab.YEAR -> RangeDropdown(
                    label = years.getOrElse(yearIndex) { "" },
                    options = years,
                    selectedIndex = yearIndex,
                    onSelect = onYear,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            ChargeBigNumber(
                value = chargePeriodKwhValue(stat),
                unit = "度",
                caption = "累计充电",
                modifier = Modifier.weight(1f),
            )
            ChargeBigNumber(
                value = "${stat.sessions}",
                unit = "次",
                caption = "充电次数",
                modifier = Modifier.weight(1f),
            )
        }
        // 有续航就多说一句；没有就整行不出现，别留虚线空壳
        val gain = stat.rangeGainKm
        if (gain != null && gain > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = chargePeriodRangeGain(stat),
                color = C.textSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        // 两句提示二选一：没容量时先说「为什么是 --」，
        // 有容量却仍有算不出来的，再说「缺的是起点电量」—— 同时出现会互相打岔
        val note = if (batteryKwh == null && stat.sessions > 0) {
            "没填电池容量，度数算不出来，只统计次数（设置 → 高级设置 → 电池容量）"
        } else if (stat.sessions > 0 && stat.measurable < stat.sessions) {
            chargePeriodCountNote(stat)
        } else {
            null
        }
        if (note != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = note,
                color = C.textSecondary,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun ChargeBigNumber(
    value: String,
    unit: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    val C = LocalAppColors.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = C.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 28.sp,
                maxLines = 1,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                text = unit,
                color = C.textSecondary,
                fontSize = 12.sp,
                lineHeight = 26.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(text = caption, color = C.textSecondary, fontSize = 11.sp)
    }
}

/**
 * 月份 / 年份下拉。
 *
 * 只列「有记录的 + 当前那个」，不铺一张十年月历 —— 用户要看的是自己的充电数据，
 * 不是选日历。列表可能很长，限高加内部滚动。
 */
@Composable
private fun RangeDropdown(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val C = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(C.chipBgActive)
                .clickable { expanded = true }
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = C.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.size(5.dp))
            // 下拉三角：用 path 画，别依赖字体里的 ▾（不同 ROM 字形差很多）
            Canvas(Modifier.size(7.dp)) {
                val path = Path().apply {
                    moveTo(0f, size.height * 0.28f)
                    lineTo(size.width, size.height * 0.28f)
                    lineTo(size.width / 2f, size.height * 0.82f)
                    close()
                }
                drawPath(path, color = C.textSecondary)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = C.dialogBg,
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            options.forEachIndexed { index, text ->
                val selected = index == selectedIndex
                DropdownMenuItem(
                    text = {
                        Text(
                            text = text,
                            color = if (selected) C.accent else C.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}

/**
 * 分布柱状图。
 *
 * 柱子口径与上方总数一致（都按开始时间归桶），否则两处数字对不上会让人怀疑记录。
 * 最高那根用实色、其余半透明 —— 一眼能找出充得最多的那一天，不用加交互。
 */
@Composable
private fun ChargeDistributionCard(bars: ChargeBars) {
    val C = LocalAppColors.current
    val accent = C.accent
    ChargePanel(padding = 16.dp, background = C.chipBg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "充电分布",
                color = C.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(text = bars.unit, color = C.textSecondary, fontSize = 10.5.sp)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(82.dp)) {
            val n = bars.items.size.coerceAtLeast(1)
            val slot = size.width / n
            val barWidth = minOf(slot * 0.5f, 12.dp.toPx())
            val peak = bars.peak.coerceAtLeast(1e-6)
            bars.items.forEachIndexed { index, bar ->
                if (bar.value <= 0.0) return@forEachIndexed
                val h = (size.height - 1f) * (bar.value / peak).toFloat()
                val x = slot * index + (slot - barWidth) / 2f
                drawRoundRect(
                    color = if (bar.value >= peak - 1e-6) accent else accent.copy(alpha = 0.3f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            bars.items.forEach { bar ->
                Text(
                    text = bar.label,
                    color = C.textSecondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChargeSessionCard(
    session: ChargeSession,
    live: Boolean,
    now: Long,
    batteryKwh: Double?,
) {
    val C = LocalAppColors.current
    val accent = C.accent
    ChargePanel(padding = 16.dp, background = C.chipBg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = chargeHistoryDate(session.startAt, now),
                color = C.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            ChargeBadge(session = session, live = live)
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = chargeHistoryRange(session),
                color = C.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
            )
            chargeHistoryDuration(session.durationMs)?.let {
                Text(
                    text = "  ·  $it",
                    color = C.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 22.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // 电量区间：左边「从多少」、右边「充到多少」，中间一条只画区间的小条
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.socFrom?.let { "从 $it%" } ?: "起点未记录",
                color = if (session.socFrom != null) C.textPrimary else C.textSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = chargeHistorySocTo(session),
                color = C.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(7.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = C.barTrack, cornerRadius = radius)
            val from = session.socFrom ?: return@Canvas
            val to = session.socTo ?: return@Canvas
            val x0 = size.width * (from / 100f)
            val x1 = size.width * (to / 100f)
            if (x1 <= x0) return@Canvas
            drawRoundRect(
                color = accent,
                topLeft = Offset(x0, 0f),
                // 区间再窄也留一个圆的宽度，否则「充了 1%」看起来像没画
                size = Size((x1 - x0).coerceAtLeast(size.height), size.height),
                cornerRadius = radius,
            )
        }
        // 电量之外的两个数：充进去多少度（按容量估算）、表显续航从多少涨到多少。
        // 都没有就整行不出现，别留一条空壳把卡片撑高。
        val detail = chargeHistoryDetail(session, batteryKwh)
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                color = C.textSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun ChargeBadge(session: ChargeSession, live: Boolean) {
    val C = LocalAppColors.current
    val (text, tint) = when {
        // 手动修正的条目没有可信的起止过程，别标成「充电中」或「已结束」
        session.edited -> "手动修正" to C.accent
        // live 且没有结束时间 = 正在充
        live && session.endAt == null -> "充电中" to C.accent
        session.full -> "已充满" to (if (C.isDark) statusGoodDark else statusGood)
        else -> "已结束" to C.textSecondary
    }
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------- 文案（纯函数，单测可直接钉） ----------------

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val ZONE: ZoneId = ZoneId.systemDefault()

private fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate()

/**
 * 「今天 / 昨天 / 9月14日 / 2025年9月14日」。
 *
 * 今天和昨天直接说，比让用户自己换算日期直观；跨年才写年份，其余年份写出来只是噪音。
 */
internal fun chargeHistoryDate(millis: Long, now: Long): String {
    val day = localDate(millis)
    val today = localDate(now)
    return when {
        day == today -> "今天"
        day == today.minusDays(1) -> "昨天"
        day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
        else -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
    }
}

/**
 * 「23:10 → 07:42」。结束时间不知道就只写开始。
 *
 * 跨天时补「次日」/日期：慢充一夜是常态，只写「23:10 → 07:42」会被看成同一天。
 */
internal fun chargeHistoryRange(session: ChargeSession): String {
    val start = Instant.ofEpochMilli(session.startAt).atZone(ZONE)
    val startText = start.format(CLOCK)
    // 手动修正只有一个用户填的时刻，没有观测到的充电过程 —— 别写成「22:30 → 22:30」
    if (session.edited) return "$startText · 手动填入"
    val end = session.endAt ?: return "$startText 开始"
    val endZoned = Instant.ofEpochMilli(end).atZone(ZONE)
    val endText = endZoned.format(CLOCK)
    return when {
        endZoned.toLocalDate() == start.toLocalDate() -> "$startText → $endText"
        endZoned.toLocalDate() == start.toLocalDate().plusDays(1) -> "$startText → 次日 $endText"
        else -> "$startText → ${endZoned.monthValue}月${endZoned.dayOfMonth}日 $endText"
    }
}

/** 「8 小时 32 分」/「45 分钟」；结束时间不知道时为 null（不写「0 分钟」充数）。 */
internal fun chargeHistoryDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs < 0) return null
    val minutes = durationMs / 60_000L
    return when {
        minutes < 1L -> "不足 1 分钟"
        minutes < 60L -> "$minutes 分钟"
        else -> {
            val h = minutes / 60L
            val m = minutes % 60L
            if (m == 0L) "$h 小时" else "$h 小时 $m 分"
        }
    }
}

/** 右侧那半句：「充到 88%」；只知道充满了就写「充到满电」，不拿 100% 冒认。 */
internal fun chargeHistorySocTo(session: ChargeSession): String = when {
    session.socTo != null -> "充到 ${session.socTo}%"
    session.full -> "充到满电"
    else -> "电量未知"
}

/**
 * 汇总卡上的度数（只给数字，「度」是旁边那个小字）：`448.5`；算不出来时 `--`，不拿 0 顶。
 */
internal fun chargePeriodKwhValue(stat: ChargePeriodStat): String {
    val kwh = stat.kwh ?: return "--"
    return String.format(Locale.CHINA, "%.1f", kwh)
}

/**
 * 「3 次 · 2 段可算」那句话。
 *
 * 能算度数的条数少于总次数时要解释一句 —— 否则用户会以为
 * 「充了 3 次、怎么只有两段的电量」是算错了。缺的要么是没看到起点电量，
 * 要么是没填电池容量。
 */
internal fun chargePeriodCountNote(stat: ChargePeriodStat): String =
    if (stat.measurable == 0) {
        "${stat.sessions} 次都缺起点电量，电量算不出来"
    } else {
        "${stat.sessions} 次里有 ${stat.measurable} 次能算出电量，其余缺起点电量"
    }

/** 区间内表显续航合计：`表显续航一共涨了 260 km`。 */
internal fun chargePeriodRangeGain(stat: ChargePeriodStat): String {
    val gain = stat.rangeGainKm ?: return ""
    return "表显续航一共涨了 " + String.format(Locale.CHINA, "%.0f", gain) + " km"
}

/**
 * 卡片底部那行细节：`充入约 18.4 kWh · 续航 320 → 410 km`。
 *
 * 续航口径写「表显」：那是车主仪表上看到的数，和电池百分比不是一回事
 * （工况口径一换，同一个电量能差几十公里）。两样都没有时返回 null，整行不出现。
 */
internal fun chargeHistoryDetail(session: ChargeSession, batteryKwh: Double?): String? {
    val parts = mutableListOf<String>()
    ChargeHistory.kwhOf(session, batteryKwh)?.let {
        parts += "充入约 " + String.format(Locale.CHINA, "%.1f", it) + " kWh"
    }
    chargeHistoryRangeGain(session)?.let { parts += it }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** 表显续航变化：`续航 320 → 410 km`；缺一头就只说一头，都没有给 null。 */
internal fun chargeHistoryRangeGain(session: ChargeSession): String? {
    val from = session.rangeFromKm
    val to = session.rangeToKm
    fun km(v: Double) = String.format(Locale.CHINA, "%.0f", v)
    return when {
        from != null && to != null && to > from -> "表显续航 ${km(from)} → ${km(to)} km"
        from != null && to != null -> "表显续航 ${km(to)} km"
        to != null -> "表显续航充到 ${km(to)} km"
        from != null -> "表显续航 ${km(from)} km 起充"
        else -> null
    }
}
