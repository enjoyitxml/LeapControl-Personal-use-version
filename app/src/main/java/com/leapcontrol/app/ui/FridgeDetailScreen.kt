package com.leapcontrol.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.Commands
import com.leapcontrol.app.FridgeControlCommand
import com.leapcontrol.app.FridgeMode
import com.leapcontrol.app.FridgeStyle
import com.leapcontrol.app.R
import com.leapcontrol.app.ui.theme.statusWarn
import com.leapcontrol.app.ui.theme.statusWarnDark

/**
 * 车载冰箱**二级页**：上游 cmdid=500 的全部功能都在这一页。
 *
 * 2026-11-08 用户口径：「冰箱卡片太大了，还是做成二级界面吧，卡片做成和其他卡片一样的大小，
 * 点开二级界面再展现详细功能」。首页只留摘要卡 [FridgeCard]，这里承接全部设置。
 *
 * ## 功能（对齐 leapauto-ref 的 FridgeControlBottomSheet）
 *  ① 电源：底部「关闭冰箱 / 开启车载冰箱 / 应用设置」
 *  ② 工作模式：智能制冷 / 恒温暖饮 50°C（切制热强制 50，切回制冷重置为 4）
 *  ③ 目标温度：制冷 -6~15℃ 步进 1 + 冰镇/冷饮/果蔬/保鲜 四个预设
 *  ④ 运行模式：标准节能 / 急速温控
 *  ⑤ 离车持续运行：开关 + 1/2/4/8 小时 + 仅单次 / 每次离车自动开启
 *  ⑥ 车况状态回显（含「离车保持至 HH:mm」）
 *
 * 上游冰箱只有一个指令：8 个字段（`cycles / duration / enable / mode / parkEnable /
 * style / temp / value`）必须全量带齐，没有「单独调温」这种指令。所以这里所有改动都先攒在
 * 草稿里（[FridgeDraft]），点底部按钮才合成**一条** [FridgeCardRules.command]。
 *
 * ## 两条硬约束
 * - **不做车辆能力判断**：车况没有冰箱信号（[FridgeCardState.hasSignal] = false）时，
 *   状态行写「状态待同步」，但所有控件照常可用、照常能下发。
 * - **草稿不拿车况覆盖**：头部状态行读车况真值，草稿是「待下发」，两者不混淆；
 *   `rememberSaveable(vin)` 让切 Tab 重建首页子树时不丢刚调好的参数，换车才重置。
 *
 * ## 视觉
 * 与空调 07 页同一套语言：56dp 顶栏（返回圆钮 + 居中标题）、16dp 实心分区卡、无阴影；
 * 冷蓝 / 暖橙只出现在状态卡与温度数字上，其余选中态统一主题色。图标复用 MingCute 矢量。
 */
@Composable
fun FridgeDetailScreen(
    state: FridgeCardState,
    busy: Boolean,
    onControl: (FridgeControlCommand) -> Unit,
    /** 草稿按车架号隔离：换车时重新以那辆车的车况初始化。 */
    vin: String,
    onBack: () -> Unit,
) {
    // 二级页自己的返回门禁（项目纪律：每个二级页都要有，否则侧滑会直接 finish Activity）。
    // HomeScreen 里还有一条兜底链，这里注册得更靠内，优先级更高
    BackHandler(enabled = true) { onBack() }

    val C = LocalAppColors.current
    val initial = FridgeCardRules.draftOf(state)

    var modeRaw by rememberSaveable(vin) { mutableStateOf(initial.mode.raw) }
    var styleRaw by rememberSaveable(vin) { mutableStateOf(initial.style.raw) }
    var temp by rememberSaveable(vin) { mutableIntStateOf(initial.temp) }
    var parkEnable by rememberSaveable(vin) { mutableStateOf(initial.parkEnable) }
    var durationHours by rememberSaveable(vin) { mutableIntStateOf(initial.durationHours) }
    var everyDeparture by rememberSaveable(vin) { mutableStateOf(initial.everyDeparture) }

    val mode = FridgeMode.fromRaw(modeRaw)
    val style = FridgeStyle.fromRaw(styleRaw)
    val isHot = mode == FridgeMode.HOT
    val shownTemp = if (isHot) Commands.FRIDGE_HOT_TEMP else temp
    val draft = FridgeDraft(
        enabled = state.enabled == true,
        mode = mode,
        temp = temp,
        style = style,
        parkEnable = parkEnable,
        durationHours = durationHours,
        everyDeparture = everyDeparture,
    )

    val cold = if (C.isDark) FridgeColdDark else FridgeCold
    val warm = if (C.isDark) statusWarnDark else statusWarn
    val tempTint = if (isHot) warm else cold
    val parkEnd = FridgeCardRules.parkEndLabel(state.parkEndTimeEpochSeconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            FridgeTopBar(onBack = onBack)

            Spacer(Modifier.height(12.dp))
            FridgeStatusCard(
                state = state,
                isHot = isHot,
                parkEnd = parkEnd,
                busy = busy,
            )

            // ---- ② 设定温度：制冷可步进 + 四个预设；制热固定 50°C ----
            Spacer(Modifier.height(10.dp))
            FridgeSection {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 制热固定 50°C，上游这里也不给步进按钮，只留等宽占位让温度仍居中
                    if (isHot) {
                        Spacer(Modifier.size(40.dp))
                    } else {
                        TempStepButton(
                            plus = false,
                            enabled = temp > FridgeCardRules.TEMP_MIN,
                            onClick = { temp = FridgeCardRules.stepTemp(temp, -1) },
                            touch = 40.dp,
                            arm = 13.dp,
                            label = "降低目标温度",
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "$shownTemp",
                                color = tempTint,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Light,
                                letterSpacing = (-1).sp,
                                maxLines = 1,
                            )
                            Text(
                                text = "℃",
                                color = tempTint.copy(alpha = 0.72f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier.padding(bottom = 12.dp, start = 1.dp),
                            )
                        }
                        Text(
                            text = if (isHot) "恒温暖饮 · 固定 50°C" else "目标温度 · -6~15°C",
                            color = C.textSecondary,
                            fontSize = 11.5.sp,
                        )
                    }
                    if (isHot) {
                        Spacer(Modifier.size(40.dp))
                    } else {
                        TempStepButton(
                            plus = true,
                            enabled = temp < FridgeCardRules.TEMP_MAX,
                            onClick = { temp = FridgeCardRules.stepTemp(temp, 1) },
                            touch = 40.dp,
                            arm = 13.dp,
                            label = "升高目标温度",
                        )
                    }
                }

                if (!isHot) {
                    Spacer(Modifier.height(12.dp))
                    FridgeChipRow(
                        options = FridgeCardRules.PRESETS.map {
                            FridgeChip("${it.label} ${it.temp}°", null, temp == it.temp)
                        },
                        onSelect = { index -> temp = FridgeCardRules.PRESETS[index].temp },
                    )
                }
            }

            // ---- ③ 工作模式 ----
            Spacer(Modifier.height(10.dp))
            FridgeSection(title = "工作模式") {
                FridgeChipRow(
                    options = listOf(
                        FridgeChip("智能制冷", AcGlyph.Snowflake, !isHot),
                        FridgeChip("恒温暖饮 50°C", AcGlyph.Sun, isHot),
                    ),
                    onSelect = { index ->
                        val next = if (index == 1) FridgeMode.HOT else FridgeMode.COLD
                        modeRaw = next.raw
                        // 上游：切到制热强制 50°C，切回制冷重置为 4°C
                        temp = if (next == FridgeMode.HOT) Commands.FRIDGE_HOT_TEMP
                        else Commands.FRIDGE_DEFAULT_TEMP
                    },
                )
            }

            // ---- ④ 运行模式 ----
            Spacer(Modifier.height(10.dp))
            FridgeSection(title = "运行模式") {
                FridgeChipRow(
                    options = listOf(
                        FridgeChip("标准节能", AcGlyph.Circulation, style == FridgeStyle.NORMAL),
                        FridgeChip("急速温控", AcGlyph.Airflow, style == FridgeStyle.TURBO),
                    ),
                    onSelect = { index ->
                        styleRaw = (if (index == 1) FridgeStyle.TURBO else FridgeStyle.NORMAL).raw
                    },
                )
            }

            // ---- ⑤ 离车持续运行 ----
            Spacer(Modifier.height(10.dp))
            FridgeSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AcIcon(glyph = AcGlyph.Timer, tint = C.textSecondary, size = 17.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("离车持续运行", color = C.textPrimary, fontSize = 13.5.sp)
                        Text(
                            "车辆熄火下电后继续保持工作",
                            color = C.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FridgeSwitchPair(on = parkEnable, onToggle = { parkEnable = it })
                }

                if (parkEnable) {
                    Spacer(Modifier.height(12.dp))
                    Text("保持运行时长", color = C.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    FridgeChipRow(
                        options = FridgeCardRules.DURATION_HOURS.map {
                            FridgeChip("${it}小时", null, durationHours == it)
                        },
                        onSelect = { index -> durationHours = FridgeCardRules.DURATION_HOURS[index] },
                        compact = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("运行频次", color = C.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    FridgeChipRow(
                        options = listOf(
                            FridgeChip("仅单次生效", null, !everyDeparture),
                            FridgeChip("每次离车自动开启", null, everyDeparture),
                        ),
                        onSelect = { index -> everyDeparture = index == 1 },
                        compact = true,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
        }

        // ---- ⑥ 唯一下发入口：整条贴在底部，滚到哪里都能点到 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.enabled == true) {
                FridgeActionButton(
                    text = if (busy) "下发中…" else "关闭冰箱",
                    background = C.chipBg,
                    ink = C.textPrimary,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { onControl(FridgeCardRules.command(draft, enable = false)) },
                )
            }
            FridgeActionButton(
                text = when {
                    busy -> "下发中…"
                    state.enabled == true -> "应用设置"
                    else -> "开启车载冰箱"
                },
                background = C.accent,
                ink = Color.White,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = { onControl(FridgeCardRules.command(draft, enable = true)) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 页面骨架
// ---------------------------------------------------------------------------

/** 顶栏：左侧返回圆钮 + 居中标题（与空调页同一套）。 */
@Composable
private fun FridgeTopBar(onBack: () -> Unit) {
    val C = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(34.dp)
                .clip(CircleShape)
                .background(C.panelBg)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            AcIcon(AcGlyph.ChevronLeft, C.textPrimary, size = 17.dp)
        }
        Text(
            text = "车载冰箱",
            color = C.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * 一块实心分区卡（16dp 圆角、无描边、无阴影，与空调 07 页一致）。
 * `heightIn` 不设固定高度：大字体下条目换行不会被裁掉。
 */
@Composable
private fun FridgeSection(
    title: String? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val C = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.panelBg, RoundedCornerShape(16.dp))
            .padding(padding),
    ) {
        if (title != null) {
            Text(
                text = title,
                color = C.textSecondary,
                fontSize = 12.sp,
                letterSpacing = 0.3.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

/**
 * 顶部状态卡：整页唯一带冷暖渐变的一块（与空调 07 的状态卡同源）。
 *
 * 左边是冰箱图标与当前状态，右边的胶囊只做**指示**，不做下发 —— 唯一下发入口是底部按钮，
 * 避免出现两个开冰箱的地方。制冷用冷蓝、暖饮用暖橙，关着 / 没信号用次要灰。
 */
@Composable
private fun FridgeStatusCard(
    state: FridgeCardState,
    isHot: Boolean,
    parkEnd: String?,
    busy: Boolean,
) {
    val C = LocalAppColors.current
    val cool = Color(0xFF56A4DE).copy(alpha = if (C.isDark) 0.13f else 0.22f)
    val middle = C.accent.copy(alpha = if (C.isDark) 0.07f else 0.10f)
    val warmBg = Color(0xFFEE9E56).copy(alpha = if (C.isDark) 0.11f else 0.24f)
    val on = state.enabled == true
    val tint = when {
        !state.hasSignal || !on -> C.textSecondary
        isHot -> if (C.isDark) statusWarnDark else statusWarn
        else -> C.accent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(cool, middle, warmBg)),
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(C.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_mod_fridge),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(C.accent),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "车载冰箱",
                    color = C.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = FridgeCardRules.statusText(state),
                    color = tint,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            // 纯指示胶囊：状态点 + 文案，不接任何点击
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (on || busy) C.accent.copy(alpha = 0.14f)
                        else C.textPrimary.copy(alpha = 0.06f),
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (on || busy) C.accent else C.textSecondary.copy(alpha = 0.5f),
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        // 刚点完按钮、车端还没回报：胶囊先跟着「正在开/正在关」走，
                        // 否则点完一秒内页面还写着「已关闭」，看起来就像没点上
                        busy && !on -> "开启中"
                        busy -> "同步中"
                        !state.hasSignal -> "待同步"
                        on -> "运行中"
                        else -> "已关闭"
                    },
                    color = if (on || busy) C.accent else C.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (parkEnd != null && on) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AcIcon(glyph = AcGlyph.Timer, tint = C.textSecondary, size = 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(text = parkEnd, color = C.textSecondary, fontSize = 11.5.sp)
            }
        }

        if (!on) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (busy) {
                    "正在下发…"
                } else {
                    "冰箱当前已关闭，调好参数后点下方「开启车载冰箱」"
                },
                color = C.textSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 组件
// ---------------------------------------------------------------------------

private data class FridgeChip(
    val label: String,
    val glyph: AcGlyph?,
    val selected: Boolean,
)

/**
 * 等分胶囊行：选中态用主题色 + 浅底，未选中用 chipBg。
 * `heightIn(min=...)` 而不是固定 height —— 大字体下文字换行也不会被裁掉。
 */
@Composable
private fun FridgeChipRow(
    options: List<FridgeChip>,
    onSelect: (Int) -> Unit,
    compact: Boolean = false,
) {
    val C = LocalAppColors.current
    // 选中态一律用主题色：和空调 07 页的功能格一致；冷暖色只留给状态卡与温度数字，
    // 满页蓝/橙会和其它分区卡抢眼
    val tint = C.accent
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, option ->
            val bg = if (option.selected) tint.copy(alpha = 0.14f) else C.chipBg
            val ink = if (option.selected) tint else C.textSecondary
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (compact) 38.dp else 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (option.glyph != null) {
                    AcIcon(glyph = option.glyph, tint = ink, size = 16.dp)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = option.label,
                    color = ink,
                    fontSize = if (compact) 12.sp else 12.5.sp,
                    fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 离车持续运行的两档开关（不用 Material Switch，保持和项目里其它 chip 同一套视觉）。 */
@Composable
private fun FridgeSwitchPair(on: Boolean, onToggle: (Boolean) -> Unit) {
    val C = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(false to "关", true to "开").forEach { (value, text) ->
            val selected = on == value
            Box(
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) C.accent.copy(alpha = 0.14f) else C.chipBg)
                    .clickable { onToggle(value) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    color = if (selected) C.accent else C.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 底部下发按钮。 */
@Composable
private fun FridgeActionButton(
    text: String,
    background: Color,
    ink: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ink.copy(alpha = if (enabled) 1f else 0.55f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** 制冷用的冷蓝：与空调页 07「冷暖分区」同一组冷色，深色下提亮一档。 */
private val FridgeCold = Color(0xFF2E7CD6)
private val FridgeColdDark = Color(0xFF7CB7FF)
