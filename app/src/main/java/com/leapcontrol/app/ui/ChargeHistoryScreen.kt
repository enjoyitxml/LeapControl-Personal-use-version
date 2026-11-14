package com.leapcontrol.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.journal.ChargeAnchor
import com.leapcontrol.app.journal.ChargeHistory
import com.leapcontrol.app.journal.ChargeSession
import com.leapcontrol.app.ui.theme.statusGood
import com.leapcontrol.app.ui.theme.statusGoodDark
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 * ## 列表口径
 * 历史（已归档） + 正在进行的那一次（用当前起点实时渲染），按时间倒序。
 */
@Composable
fun ChargeHistoryScreen(
    history: List<ChargeSession>,
    anchor: ChargeAnchor?,
    currentSoc: Int?,
    charging: Boolean,
    now: Long,
    onBack: () -> Unit,
) {
    val C = LocalAppColors.current
    val live = ChargeHistory.liveSession(anchor, currentSoc, charging)
    // 已在历史里的不动，只把「正在进行 / 最近这一次」贴在最新的位置
    val rows = (history + listOfNotNull(live)).sortedByDescending { it.startAt }

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
            rows.forEachIndexed { index, session ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                ChargeSessionCard(
                    session = session,
                    live = live != null && session === live,
                    now = now,
                )
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

@Composable
private fun ChargeSessionCard(session: ChargeSession, live: Boolean, now: Long) {
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
