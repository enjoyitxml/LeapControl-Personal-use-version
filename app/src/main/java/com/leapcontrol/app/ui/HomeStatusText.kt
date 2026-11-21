package com.leapcontrol.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.DrivingBadge
import com.leapcontrol.app.DrivingStateRules
import com.leapcontrol.app.DrivingTone
import java.util.Locale

/**
 * 首页右上角的车辆状态区 —— **双行文本流**（用户 2026-11-05 从 14 个方案里选定的第 2 号）。
 *
 * ## 为什么推倒重来
 * 之前这里是「驾驶状态胶囊 + 车辆已闭锁（图标+文字）+ 若干已开启提醒（图标+文字）」
 * 三套不同外形硬堆成一列：胶囊有内边距、裸文字没有，右边缘实际参差；整列没有容器，
 * 直接浮在夜景上，越往下越糊；而且所有「已开启」项都走警示色，空调开着看着像报警。
 *
 * 现在的结构：
 * - **第一行**：5dp 圆点 + 15sp 主状态（已驻车 / 行驶中 / 充电中…），后面可跟 11sp 小字数值
 *   （`42 km/h`、`剩 42 分钟`）；
 * - **第二行**：11sp 次要色，把闭锁 / 空调 / 车窗 / 后备箱 / 遮阳帘用「 · 」串成一句。
 *   只有真需要看的问题（未闭锁、车窗开、后备箱开）才转红，其余一律灰色 —— 这才是
 *   「空调开着是常态，不该报警」的修正。
 *
 * ## 窄屏：主行会折成两行，而不是裁字
 * 2026-11-08 用户截图里「充电中　剩 3 小时 5 分钟」的尾巴被切掉了：主行原来是 `Row` +
 * `softWrap = false`，宽度不够时 Compose 既不折行也不省略，直接画出去再裁掉。
 * 现在主行改用 [FlowRow]：圆点 + 主状态是**一个不可拆的单元**，`detail` 是另一个单元，
 * 一行放不下就整块掉到下一行并保持右对齐 —— 宽屏看起来和原来一模一样，窄屏信息不丢。
 * 同时这一列的上限从固定 168dp 改成「最多占本行 46%」，把宽度让给左边的续航大字。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeStatusText(
    status: CarStatus?,
    modifier: Modifier = Modifier,
    flash: ControlFeedback.Flash? = null,
    /**
     * 「车窗是否开着」的显示值。默认走车况，首页会传入
     * [WindowControl.openForDisplay] 的结果：车况读不到时用本地档位兜底，
     * 否则刚开完窗、车端信号又是中间态的话，右上角会什么都不写（2026-11-12）。
     */
    windowOpen: Boolean? = status?.windowOpen,
    /**
     * 「哨兵是否开着」的显示值。null = 原厂方案，照旧取车况；
     * 副厂方案（后加装）车端永远报不出这个信号，传本地记录进来。
     */
    sentryOn: Boolean? = null,
) {
    val C = LocalAppColors.current
    val view = rememberHomeStatusView(status, flash, windowOpen, sentryOn)

    BoxWithConstraints(modifier = modifier) {
        // 宽屏就是原来的 168dp（大屏视觉一字不差）；只在窄屏上才收缩，条件是给左边
        // 40sp 的续航大字留足 150dp —— 2026-11-08 一度按屏宽 46% 算，
        // 结果 393dp 的常规屏也被压到 156dp，明明放得下却提前折行。
        val columnMax = minOf(MAX_WIDTH, (maxWidth - 150.dp).coerceAtLeast(96.dp))
        Column(
            modifier = Modifier.widthIn(max = columnMax),
            horizontalAlignment = Alignment.End,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(toneColor(view.tone, C)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = view.primary,
                        color = C.textPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (view.detail != null) {
                    // 数值比结论轻一档：`行驶中 42 km/h` 的重心应该落在「行驶中」上，
                    // 也不至于像「已驻车」这种没有数值时右侧空一块
                    Text(
                        text = view.detail,
                        color = C.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (view.tokens.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildAnnotatedString {
                        view.tokens.forEachIndexed { index, token ->
                            if (index > 0) {
                                withStyle(SpanStyle(color = C.textSecondary.copy(alpha = 0.55f))) {
                                    append(" · ")
                                }
                            }
                            withStyle(
                                SpanStyle(
                                    color = when (token.emphasis) {
                                        StatusEmphasis.ALERT -> AlertRed
                                        StatusEmphasis.ACCENT -> C.accent
                                        StatusEmphasis.MUTED -> C.textSecondary.copy(alpha = 0.7f)
                                        StatusEmphasis.NORMAL -> C.textSecondary
                                    },
                                ),
                            ) { append(token.text) }
                        }
                    },
                    fontSize = 11.sp,
                    // 三条以上就折到第二行，超过两行省略：这一列只有半屏宽，
                    // 撑高了会把左边的续航大字挤扁
                    lineHeight = 15.sp,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 右列宽度上限：约为页面的一半，超长的「剩 1 小时 15 分钟」宁可折行也不挤左边。 */
private val MAX_WIDTH = 168.dp

/**
 * 把车况折成状态区要渲染的内容。
 *
 * 抽出来是因为有两个调用方（经典右上角的 [HomeStatusText]、分置版车模下方的
 * [DrivingStatusBadge]），两处必须**同源**：以后加一项状态只改 [HomeStatusLines.build]
 * 一处，不会出现「车模下能看到、右上角看不到」这种分裂。
 */
@Composable
internal fun rememberHomeStatusView(
    status: CarStatus?,
    flash: ControlFeedback.Flash? = null,
    /** 见 [HomeStatusText] 的 `windowOpen`：显示用的车窗开启判断，已经过本地档位兜底。 */
    windowOpen: Boolean? = status?.windowOpen,
    /** 见 [HomeStatusText] 的 `sentryOn`：null = 原厂，取车况；副厂传本地记录。 */
    sentryOn: Boolean? = null,
): HomeStatusView =
    // 数据类做 key：车况刷新时整体换一个新对象，等价于按内容比对
    remember(status, flash, windowOpen, sentryOn) {
        HomeStatusLines.build(
            badge = status?.let {
                DrivingStateRules.badge(
                    gearStatus = it.gearStatus,
                    speedKmh = DrivingStateRules.parseSpeed(it.speed),
                    chargeState = it.chargeState,
                    chargeRemainMinutes = it.chargeRemainMinutes,
                    chargeTimeLeft = it.chargeTimeLeft,
                    isShutDown = it.isShutDown,
                )
            },
            locked = status?.locked,
            acOn = status?.acOn,
            acTemp = status?.acTargetTemp,
            windowOpen = windowOpen,
            trunkOpen = status?.trunkOpen,
            sunshadeOpen = status?.sunshadeOpen,
            sentryOn = sentryOn ?: status?.sentryOn,
            // 刚控过车：把结果并进第二行（闭锁那句直接被顶掉），
            // 车端报文跟上之前不要还写着「已闭锁」
            flashText = flash?.text,
            flashTopic = flash?.command?.let { ControlFeedback.topicOf(it) },
        )
    }

/** 行驶中不抢眼但要有生气；充电沿用首页电量条的绿；倒车 / 故障用琥珀。 */
private val ChargeGreen = Color(0xFF2FCC71)
private val Amber = Color(0xFFE0A048)

/** 真需要看一眼的问题（未闭锁 / 车窗开 / 后备箱开）。三个布局共用同一个红。 */
internal val AlertRed = Color(0xFFE5484D)

internal fun toneColor(tone: DrivingTone?, C: AppColors): Color = when (tone) {
    DrivingTone.MOVING -> C.accent
    DrivingTone.PARKED, null -> C.accent
    DrivingTone.CHARGING -> ChargeGreen
    DrivingTone.REVERSING -> Amber
    DrivingTone.WARNING -> AlertRed
}

/** 一行里每个词的强调等级（纯逻辑，见 [HomeStatusLines]）。 */
internal enum class StatusEmphasis { NORMAL, ALERT, MUTED, ACCENT }

internal data class StatusToken(val text: String, val emphasis: StatusEmphasis = StatusEmphasis.NORMAL)

/** 状态区要渲染的两行内容。 */
internal data class HomeStatusView(
    val primary: String,
    val detail: String? = null,
    val tone: DrivingTone? = null,
    val tokens: List<StatusToken> = emptyList(),
)

/**
 * 把车况字段折成「一行结论 + 一行从属项」（纯函数，可单测）。
 * 规则：
 * - 有 [DrivingBadge] → 结论取它，闭锁降级为从属项；
 * - 没有 → 说明挡位和车速都没有，这时只靠闭锁也能说清（`已闭锁` / `未闭锁`），
 *   不再写「状态未知」；
 * - 空调 / 遮阳帘「开着」是常态，用普通次要色；车窗开、后备箱开、未闭锁才转红。
 */
internal object HomeStatusLines {

    fun build(
        badge: DrivingBadge?,
        locked: Boolean?,
        acOn: Boolean?,
        acTemp: String?,
        windowOpen: Boolean?,
        trunkOpen: Boolean?,
        sunshadeOpen: Boolean?,
        sentryOn: Boolean? = null,
        flashText: String? = null,
        flashTopic: String? = null,
    ): HomeStatusView {
        val lockToken = when (locked) {
            // 口径统一（用户 2026-11-05）：整套界面只说「已上锁 / 已解锁」，
            // 不再出现「已闭锁 / 未闭锁」—— 同一个状态两种说法，读的人要反应一下。
            true -> StatusToken("已上锁")
            false -> StatusToken("已解锁", StatusEmphasis.ALERT)
            null -> StatusToken("锁车未知", StatusEmphasis.MUTED)
        }
        val openTokens = buildList {
            // 哨兵排最前：它是安防状态，和闭锁同类；开着的时候要能一眼看到
            // （分置版右上角用的是 [vehicleStatusRows]，文案是「哨兵已开启」，
            // 这边受第二行宽度限制只写「哨兵开」）
            if (sentryOn == true) add(StatusToken("哨兵开"))
            if (acOn == true) add(StatusToken(acText(acTemp)))
            if (windowOpen == true) add(StatusToken("车窗开", StatusEmphasis.ALERT))
            if (trunkOpen == true) add(StatusToken("后备箱开", StatusEmphasis.ALERT))
            if (sunshadeOpen == true) add(StatusToken("遮阳帘开"))
        }
        // 刚控过车：这一句用主色提亮，并顶掉同类项（否则会出现
        // 「车辆已解锁 · 未闭锁」这种自相矛盾的两句并排）。
        val flashToken = flashText?.let { StatusToken(it, StatusEmphasis.ACCENT) }
        val restTokens = openTokens.filterNot { token ->
            when (flashTopic) {
                ControlFeedback.TOPIC_AC -> token.text.startsWith("空调")
                ControlFeedback.TOPIC_WINDOW -> token.text == "车窗开"
                ControlFeedback.TOPIC_TRUNK -> token.text == "后备箱开"
                ControlFeedback.TOPIC_SUNSHADE -> token.text == "遮阳帘开"
                ControlFeedback.TOPIC_SENTRY -> token.text == "哨兵开"
                else -> false
            }
        }
        val lockReplaced = flashToken != null && flashTopic == ControlFeedback.TOPIC_LOCK
        return if (badge != null) {
            HomeStatusView(
                primary = badge.label,
                detail = badge.detail,
                tone = badge.tone,
                tokens = buildList {
                    if (flashToken != null) add(flashToken)
                    if (!lockReplaced) add(lockToken)
                    addAll(restTokens)
                },
            )
        } else {
            // 没有行驶状态时，闭锁那句就是主状态：控车提示直接占主行
            HomeStatusView(
                primary = if (lockReplaced) flashText!! else lockToken.text,
                tokens = buildList {
                    if (flashToken != null && !lockReplaced) add(flashToken)
                    addAll(restTokens)
                },
            )
        }
    }

    /** `24.0` / `24°` / `24℃` 都说成「空调 24°」；温度拿不到就只说「空调开」。 */
    fun acText(raw: String?): String {
        val cleaned = raw
            ?.trim()
            ?.removeSuffix("°C")
            ?.removeSuffix("℃")
            ?.removeSuffix("°")
            ?.trim()
        val value = cleaned?.toDoubleOrNull()
        val label = when {
            value == null -> cleaned?.takeIf { it.isNotEmpty() }
            value % 1.0 == 0.0 -> String.format(Locale.CHINA, "%.0f", value)
            else -> String.format(Locale.CHINA, "%.1f", value)
        }
        return if (label != null) "空调 $label°" else "空调开"
    }
}
