package com.leapcontrol.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.trip.TripCloseResult
import com.leapcontrol.app.trip.TripFormat
import com.leapcontrol.app.trip.TripOverview
import com.leapcontrol.app.trip.TripPauseResult
import com.leapcontrol.app.trip.TripResumeResult
import com.leapcontrol.app.trip.TripRecord
import com.leapcontrol.app.trip.TripRules
import com.leapcontrol.app.trip.TripSample
import com.leapcontrol.app.trip.TripSampling
import com.leapcontrol.app.trip.TripShape
import com.leapcontrol.app.trip.TripStartResult
import kotlinx.coroutines.delay

/**
 * 零控首页「行程轨迹」小卡（设计方案 ② 行程条 + 2026-11-20 的速度曲线改版）。
 *
 * 版式：有进行中的行程时优先显示实时面板（可手动结束），否则是
 * 标题 → 时间与里程 → 速度曲线 → 起终点 → 三格指标 → 一句节奏摘要。
 * 曲线是**按采样时间排布的车速折线**，不是地图上的路线；采样点不足三个时
 * 退回采样分布条。这一点必须在卡上写清楚，否则用户会以为是高德那种完整轨迹。
 */
@Composable
fun TripTrackCard(
    overview: TripOverview?,
    onClick: () -> Unit,
    onFinishTrip: () -> TripCloseResult,
    onStartTrip: () -> TripStartResult,
    /** 用户确认「这趟不要了」：清掉进行中的一段、不写历史（不到 30 秒那种）。 */
    onDiscardTrip: () -> Boolean = { false },
    /** 后台采样间隔（秒）：空态那句「多久采一个点」要与行程页设置一致。 */
    sampleIntervalSec: Int = TripSampling.DEFAULT_INTERVAL_SEC,
    modifier: Modifier = Modifier,
) {
    val C = LocalAppColors.current
    val trip = overview?.latest
    val live = overview?.let { TripRules.live(it) }
    val shape = RoundedCornerShape(8.dp)
    // 结束结果挂在卡片上而不是实时面板里：成功后面板会被移除，反馈得留在还在的父节点上。
    var finishNotice by remember { mutableStateOf<TripCloseResult?>(null) }
    LaunchedEffect(finishNotice) {
        if (finishNotice != null) {
            delay(4_000)
            finishNotice = null
        }
    }
    // 开始 / 结束的短提示共用一行：开段成功后面板会直接换成「正在记录」，不用再说话。
    var startNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(startNotice) {
        if (startNotice != null) {
            delay(4_000)
            startNotice = null
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(C.panelBg, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // 整卡不再 clickable：卡里有「开始 / 结束」按钮，整卡抢手势会让按钮时灵时不灵。
        // 只有标题行和右侧入口负责进二级页。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "行程轨迹",
                color = C.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (live != null) {
                Spacer(Modifier.width(6.dp))
                RecordingDot(color = C.accent)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "查看全部 ›",
                color = C.accent,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(10.dp))

        if (finishNotice != null) {
            Text(
                text = finishNotice!!.message(),
                color = C.accent,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
        }

        when {
            live != null -> TripLivePanel(
                live = live,
                onFinish = onFinishTrip,
                onResult = { finishNotice = it },
                onDiscard = onDiscardTrip,
                compact = true,
                sampleIntervalSec = sampleIntervalSec,
            )

            trip == null -> {
                Text(
                    text = "还没有行程记录",
                    color = C.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "开车前点下面的「开始记录行程」，点完就可以把手机收起来：" +
                        "零控会每 ${TripSampling.label(sampleIntervalSec)}在后台采一个点，" +
                        "锁屏也照采；点「结束行程」或锁车收一段。",
                    color = C.textSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "怎么才能记到行程 ›",
                    color = C.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            else -> {
                TripHeadline(trip, C.textPrimary, C.textSecondary)
                Spacer(Modifier.height(6.dp))
                TripSpeedTrend(
                    points = trip.points,
                    accent = C.accent,
                    track = C.barTrack,
                )
                Spacer(Modifier.height(7.dp))
                // 起终点各占一头：起点圆点在左、终点圆点在右，地址各自封顶后省略。
                // 一行放不下完整地址是这一版接受的取舍 —— 明细在二级页展开就能看全。
                TripEndsLine(trip, C.textSecondary, C.accent)
                Spacer(Modifier.height(9.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TripMetric(TripFormat.duration(trip.durationMs), "时长")
                    MetricDivider()
                    TripMetric(
                        trip.averageKmh?.let { "${TripFormat.speed(it)} km/h" } ?: "--",
                        "均速",
                    )
                    MetricDivider()
                    TripMetric(
                        energyValue(trip.energy?.kwh, trip.energy?.usedPercent),
                        "耗电",
                    )
                    MetricDivider()
                    // 续航达成率：实际里程 ÷ 表显续航消耗。数据不全时写 --，
                    // 这一格常驻，位置固定，不会因为某趟读不到续航就左右乱跳。
                    TripMetric(
                        TripFormat.achievement(trip.rangeAchievement),
                        "续航达成",
                    )
                }
                TripSummaryLine(trip.points, C.textSecondary)
            }
        }

        // 没在记录时才出现「开始」—— 行程只由用户开始，不再自动开段。
        if (live == null) {
            Spacer(Modifier.height(10.dp))
            TripStartButton(onStart = onStartTrip, onResult = { startNotice = it })
        }
        startNotice?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                color = C.accent,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

/**
 * 「开始记录行程」按钮。首页卡和行程二级页共用。
 *
 * 只把**结果**回传给外层显示文案：开段成功时 TripTracker 的 StateFlow 会立刻换一份状态，
 * 卡片自己就会切成「正在记录」，按钮也随之消失，不需要再说一句。
 */
@Composable
internal fun TripStartButton(
    onStart: () -> TripStartResult,
    onResult: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val C = LocalAppColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(C.accent.copy(alpha = 0.14f))
            .clickable {
                onResult(startTripNotice(onStart()))
            }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "开始记录行程",
            color = C.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 开始一段行程的结果文案；成功时返回 null（界面直接切成「正在记录」，不用再说话）。 */
internal fun startTripNotice(result: TripStartResult): String? = when (result) {
    TripStartResult.STARTED -> null
    TripStartResult.ALREADY_OPEN -> "已经在记录了"
    TripStartResult.NO_STATUS -> "先刷新一次车况，再点开始"
}

/** 暂停的结果文案；暂停后面板会切成「已暂停」，成功不必再说一句。 */
internal fun pauseTripNotice(result: TripPauseResult): String? = when (result) {
    TripPauseResult.PAUSED -> "已暂停：这段时间不记录，后台也不再采点"
    TripPauseResult.NO_TRIP -> "现在没有正在记录的行程"
    TripPauseResult.ALREADY_PAUSED -> "已经在暂停中"
}

/** 恢复的结果文案；成功后面板会切回「正在记录」。 */
internal fun resumeTripNotice(result: TripResumeResult): String? = when (result) {
    TripResumeResult.RESUMED -> null
    TripResumeResult.NOT_PAUSED -> "现在没有暂停中的行程"
    TripResumeResult.NO_STATUS -> "先刷新一次车况，再点恢复"
}

/**
 * 卡片头信息（设计方案 A）：左边是这趟的时间段，右边把**里程**做成主数字。
 *
 * 里程原先挤在四格指标的第一格里，和「时长 / 耗电 / 均速」一样大，看不出这是最关键的那个数。
 * 现在提到 13sp 加粗，时间降成辅助色；里程没读到或者只是估算时，在数字左边挂一个小标签，
 * 免得「0.0 km」被当成真的没跑。
 */
@Composable
private fun TripHeadline(
    trip: TripRecord,
    primary: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val tag = when {
        trip.distanceKm == 0.0 -> "未读到"
        trip.distanceEstimated -> "估算"
        else -> null
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${TripFormat.dayTime(trip.effectiveStartAt, now)} → ${TripFormat.time(trip.endAt)}",
            color = secondary,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tag != null) {
            Text(
                text = tag,
                color = secondary,
                fontSize = 9.5.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = "${TripFormat.km(trip.distanceKm)} km",
            color = primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * 起终点一行 `● 起点 → 终点 ●`：起点灰点在左、终点主色点在右。
 *
 * 两侧地址各自封顶 128dp（约 11 个字）再省略，箭头靠 [Arrangement.SpaceBetween] 落在正中：
 * 短地址时不会像两端对齐那样在中缝留出一大块空白、把箭头晾在左边；
 * 长地址时两端同时省略，也不会一方被挤成零宽。
 *
 * 这一行只有 128dp，地址本身是完整的「江西省南昌市新建区蛟桥街道榆树村」，
 * 所以先过一遍 [TripFormat.shortPlace] 扔掉省市前缀 —— 直接省略的话留下的正好是
 * 前缀、地名全被切掉（2026-11-08 用户反馈）。
 */
@Composable
private fun TripEndsLine(
    trip: TripRecord,
    secondary: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val label = androidx.compose.ui.text.TextStyle(
        fontSize = 11.sp,
        color = secondary,
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EndDot(secondary)
            Spacer(Modifier.width(5.dp))
            Text(
                text = trip.startLabel?.let { TripFormat.shortPlace(it) } ?: "起点",
                style = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 128.dp),
            )
        }
        Text(
            text = "→",
            color = secondary.copy(alpha = 0.55f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = trip.endLabel?.let { TripFormat.shortPlace(it) } ?: "终点",
                style = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.widthIn(max = 128.dp),
            )
            Spacer(Modifier.width(5.dp))
            EndDot(accent)
        }
    }
}

@Composable
private fun EndDot(color: Color) {
    Box(
        modifier = Modifier
            .size(5.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 指标之间的竖分隔，让三格不靠猜边界。 */
@Composable
private fun MetricDivider() {
    val C = LocalAppColors.current
    Box(
        modifier = Modifier
            .padding(horizontal = 9.dp)
            .width(1.dp)
            .height(20.dp)
            .background(C.border),
    )
}

/**
 * 行程形状（2026-11-20 用户选定的「①+⑤ 组合」）：上面一条速度曲线、下面一句摘要。
 *
 * 旧版这里只画采样点分布，一趟跑完和一趟没跑看起来一样。现在换成速度曲线：
 * 横轴是时间、纵轴是车速 —— 堵着就贴底、跑起来就拱上去，形状本身就是反馈。
 *
 * 速度点不足三个（短行程 / 信号没读到）时**退回原来的采样条**：两三个点画出来
 * 只是一条斜线，比灰条更容易误解成「全程匀速」。
 */
@Composable
private fun TripSpeedTrend(
    points: List<TripSample>,
    accent: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    val curve = remember(points) { TripShape.curve(points) }
    if (curve != null) {
        TripSpeedCurve(
            curve = curve,
            accent = accent,
            track = track,
            modifier = modifier.fillMaxWidth().height(34.dp),
        )
    } else {
        TripSampleStrip(
            points = points,
            accent = accent,
            track = track,
            modifier = modifier.fillMaxWidth().height(18.dp),
        )
    }
}

/**
 * 速度曲线：淡基线 + 折线 + 渐变填充，两端各一个主色圆点。
 *
 * 高度由调用方给（34dp）：18dp 装不下「快慢起伏」，曲线会被压成一条几乎平的线。
 * 线宽 1.8dp、填充从 24% 渐隐到 2%，压在卡片浅底上不会糊成一团深色。
 */
@Composable
internal fun TripSpeedCurve(
    curve: TripShape.Curve,
    accent: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val pts = curve.points
        if (pts.isEmpty()) return@Canvas
        val padX = 4.dp.toPx()
        val left = padX
        val right = (size.width - padX).coerceAtLeast(left)
        val width = right - left
        val baseY = size.height - 1.5.dp.toPx()
        val topPad = 3.dp.toPx()
        val usable = (baseY - topPad).coerceAtLeast(1f)

        fun xOf(at: Float): Float = left + width * at.coerceIn(0f, 1f)
        fun yOf(point: TripShape.CurvePoint): Float = baseY - usable * curve.ratio(point)

        // 极淡基线：速度为 0 时曲线会压在它上面，有条参照才知道「到底动了没有」
        drawLine(
            color = track.copy(alpha = 0.55f),
            start = Offset(left, baseY),
            end = Offset(right, baseY),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )

        val line = Path()
        val area = Path()
        pts.forEachIndexed { i, point ->
            val x = xOf(point.at)
            val y = yOf(point)
            if (i == 0) {
                line.moveTo(x, y)
                area.moveTo(x, baseY)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(xOf(pts.last().at), baseY)
        area.close()

        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.02f)),
                startY = topPad,
                endY = baseY,
            ),
        )
        drawPath(
            path = line,
            color = accent,
            style = Stroke(
                width = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // 首尾圆点与旧采样条同款，视觉上仍能认出这是同一张卡上的那条
        listOf(pts.first(), pts.last()).forEach { point ->
            drawCircle(
                color = accent,
                radius = 2.6.dp.toPx(),
                center = Offset(xOf(point.at), yOf(point)),
            )
        }
    }
}

/**
 * 摘要行（①+⑤ 里的 ⑤）：`行驶 21 分 · 停 6 次共 12 分 · 最高 78`。
 *
 * 挂在指标行下方：那三格讲的是「跑了多久 / 多快 / 耗多少」，这一行补的是
 * 「堵不堵」—— 同一趟跑 33 分钟，全程高速和堵了 12 分钟，指标完全一样，
 * 但用户的体感天差地别。数据不足时整行不出，不留空位。
 */
@Composable
private fun TripSummaryLine(points: List<TripSample>, color: Color) {
    val text = remember(points) {
        TripShape.rhythm(points)?.let { TripShape.summaryText(it) }
    }
    if (text == null) return
    Spacer(Modifier.height(5.dp))
    Text(
        text = text,
        color = color,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 行程条（设计方案 ②）：一条干净的实心圆角细条 + 起终点主色圆点 + **最多 6 个**采样刻度。
 *
 * 2026-11-20 起首页卡的默认展示换成了 [TripSpeedCurve]，这条只在速度点不足时兜底；
 * 行程二级页、实时面板、零记小卡仍在用它。
 *
 * 旧版把每个采样点都画成圆点、底下再垫一条虚线：一趟 99 个点挤在 18dp 高的条上，
 * 虚线和灰点叠成一片，真机上就是用户说的「跟马赛克一样」。现在：
 *  - 底条改成**实线圆头**，不再用 dashPathEffect（虚线本身就是那层噪点）；
 *  - 采样点按时间分桶抽稀，只画有点的桶中心，2 个点和 200 个点的视觉密度一样；
 *  - 中间刻度用主色半透明、比端点小，端点带一圈柔光，一眼能看出头尾。
 * 采样密度已经写在指标行「N 点」里，这条只负责时间分布的示意，不承担数点的功能。
 */
@Composable
internal fun TripSampleStrip(
    points: List<TripSample>,
    accent: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val y = size.height / 2f
        val pad = 5.dp.toPx()
        val left = pad
        val right = (size.width - pad).coerceAtLeast(left)
        val width = right - left

        drawLine(
            color = track,
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (points.isEmpty()) return@Canvas

        TripRules.stripMarks(points).forEach { f ->
            drawCircle(
                color = accent.copy(alpha = 0.7f),
                radius = 1.8.dp.toPx(),
                center = Offset(left + width * f, y),
            )
        }

        listOf(left, right).forEach { x ->
            drawCircle(accent.copy(alpha = 0.18f), 6.5.dp.toPx(), Offset(x, y))
            drawCircle(accent, 3.8.dp.toPx(), Offset(x, y))
        }
    }
}

@Composable
private fun RowScope.TripMetric(value: String, label: String) {
    val C = LocalAppColors.current
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = value,
            color = C.textPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = C.textSecondary,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}
