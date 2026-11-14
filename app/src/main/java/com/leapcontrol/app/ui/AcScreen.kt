package com.leapcontrol.app.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.AcSwitchIntent
import com.leapcontrol.app.Commands
import com.leapcontrol.app.SteeringWheelHeat
import com.leapcontrol.app.VehicleCapabilities
import kotlin.math.roundToInt

/**
 * 空调全屏二级页（点首页空调条进入）。
 *
 * ## 这一版：功能链路按上游 leapauto v4.3.6，版式按用户选中的「07 冷暖分区」
 * 分区顺序：顶部渐变状态卡（开关 + 车内温度 + 运行状态）→ 智能建议（仅车内极热 / 极冷）
 * → 设定温度条 → 四个快捷场景 → 快速除味 / 定时空调 → 风量滑杆 → 出风方向
 * → 座椅与座舱舒适 2×2。
 *
 * **07 的视觉主线**：只有顶部状态卡用低饱和「冷蓝 → 主题绿 → 暖橙」渐变承载
 * 冷暖语义，下面全部回到中性卡片；渐变按 [AppColors.isDark] 调透明度，不引入新的色系，
 * 也不接天气数据。功能图标整套换成从 MingCute（Apache-2.0）抓取的 24×24 线性矢量，
 * 见 [AcGlyph]；不再用 Canvas 手绘。
 *
 * ## 与上游唯一明确的版式差别：一屏展开
 * 上游用一个默认 false 的本地 state 把「出风方向 + 座椅与座舱舒适」藏在
 * 一条「更多设置」后面，要点一下才出现。这里**没有任何折叠**：整页 `verticalScroll`
 * 一次滚到底，最常用的座椅加热不需要先点一下展开。守门断言见 `AcScreenLayoutTest`。
 *
 * ## 为什么预设点击要同步整组编辑态
 * 上游的 `quickCool` / `quickHeat` / `defrost` / `deodorize` 都是**单条 cmdid=170
 * 全字段预设**，但随后任何一次细粒度操作（调风量、切循环、改出风）都会把当前
 * 温度/风量/模式/循环/除霜/出风**整组重发**。若不同步本地编辑态，点完「极速降温」
 * 再拖一下风量，就会用旧的 24℃ 把 18℃ 覆盖掉 —— 用户看到的是「预设白按了」。
 * 所以 [applyPreset] 会一次性写六个字段，并在 8 秒保护窗口内不接受旧遥测回灌。
 *
 * ## 接口链路
 * 所有细粒度调整经 [Commands.acSettingName] → `cmdid=170` 七字段 JSON，
 * 与上游 `buildDetailedAc(AirConditioningCommand)` 等价；预设直接走 [Commands.build]。
 * `defrost` 保留本项目实测过的参数组（32℃ / 7 挡 / hot / 外循环 / 前挡出风），
 * **不回退到上游文档里那条 24℃/5 挡/cold** —— 那组在真车上只改风量不出前挡风。
 *
 * ## 天气：不接实况天气，也不留常驻建议
 * 上游顶部副标题的「室外温度 / 天气」与「雨雪 · 潮湿」建议来自高德实况天气，
 * 本项目没有天气数据源（Key 口径只有逆地理 + 关键字搜索），用户已明确**不接实时天气接口**。
 * 因此建议条只在车内温度确实极热 / 极冷时出现；没有天气数据时不再挂一条常驻的
 * 除雾提示（2026-11-07 用户要求去掉）。除霜功能本身仍在「前挡除霜」场景磁贴里。
 *
 * ## 温度：完全按上游，连续点击、停手才下发
 * 上游 `pendingTemperatureJob`：每一下 ± 都立刻改显示值，取消上一份待发任务后
 * 重新 `delay(400L)`，停手才发**一条** 170（最后一个值）；`actionsEnabled = status != null`
 * 不拿回执锁按钮，网关受理即成功，不轮询判成败、不显示「正在下发…等待确认」。
 * 本项目旧的「每次调节都要有反馈 / 等回应才能继续调」约束已由用户撤销（2026-11-07）。
 * 加减号只受 16~32℃ 边界限制，不接任何 busy；失败时上层用 `temperatureRollbackTick`
 * 把乐观值退回车端真值（对应上游 `restoreEditedClimateFromStatus()`）。
 */
@Composable
fun AcScreen(
    status: CarStatus?,
    caps: VehicleCapabilities,
    busy: Boolean,
    targetTemp: Int,
    /** 下发失败时上层递增它，把本页的乐观温度一起退回车端真值。 */
    temperatureRollbackTick: Int = 0,
    /** 本地开关意图：用户刚点下的目标状态，null 表示以车况为准。 */
    pendingOn: Boolean? = null,
    /** 正在下发的指令文案（如「主驾加热2档」），null 表示空闲。 */
    busyLabel: String?,
    /** 重新回拉车况：运行状态区右上角的「刷新」。 */
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    /** 点设定温度加减号：传**绝对目标温度**，上层按 400ms 防抖合并下发。 */
    onTemperature: (Int) -> Unit,
    /** 点电源：true = 开空调（带当前设定温度）。 */
    onPower: (Boolean) -> Unit,
    /** 其它一切指令（场景 / 风量 / 循环 / 出风方向 / 座椅…）都走这里，参数是命令名。 */
    onCommand: (String, rollback: (() -> Unit)?) -> Unit,
    onOpenScheduledAc: () -> Unit,
    /** 预设改了设定温度时同步首页的草稿（只改显示，不额外下发指令）。 */
    onPresetTemperature: (Int) -> Unit = {},
) {
    val C = LocalAppColors.current

    val acOn = AcSwitchIntent.shown(status?.acOn, pendingOn)
    val indoor = normalizeTemp(status?.interiorTemp)
    val indoorC = indoor.toDoubleOrNull()?.takeIf { it in -40.0..80.0 }

    val remoteWind = status?.acWindLevel?.trim()?.toIntOrNull()
        ?.coerceIn(Commands.MIN_AC_WIND_LEVEL, Commands.MAX_AC_WIND_LEVEL)
    val remoteCircle = if (status?.acCircle?.contains("外") == true) "out" else "in"
    val remotePosition = status?.acPosition ?: "all"
    val remoteMode = status?.acMode ?: "nohotcold"
    val remoteDefog = status?.windshieldDefrost == true

    // ---- 整组编辑态（上游 editedTemperature / WindLevel / Circle / Defogging / Outlet） ----
    // 车端没有「只调风量」这种单字段指令，任何一次调整都要把七个字段一起发。
    // 预设点击后车况还没回来，这里必须先用预设值，否则下一次调整会把预设覆盖掉。
    val baseEdit = AcEdit(
        temperature = targetTemp,
        wind = remoteWind ?: DEFAULT_WIND,
        mode = remoteMode,
        circle = remoteCircle,
        defogging = remoteDefog,
        position = remotePosition,
    )
    var edit by remember { mutableStateOf(baseEdit) }
    var lastActionAt by remember { mutableStateOf(0L) }
    var activePreset by remember { mutableStateOf<String?>(null) }

    // 车况回来再回灌；刚点过按钮的 8 秒内不接受旧遥测，和上游的乐观保护窗口同口径
    LaunchedEffect(remoteWind, remoteCircle, remotePosition, remoteMode, remoteDefog, targetTemp) {
        if (System.currentTimeMillis() - lastActionAt < AC_OPTIMISTIC_WINDOW_MS) return@LaunchedEffect
        edit = baseEdit
        activePreset = null
    }

    // 温度指令没发出去：上层已经把草稿清掉，这里必须跟着退，不能靠上面那个 8 秒保护窗口
    // （连点期间 lastActionAt 一直是新的，光靠车况回灌永远盖不回来，页面会一直显示假温度）。
    LaunchedEffect(temperatureRollbackTick) {
        if (temperatureRollbackTick > 0) {
            edit = edit.copy(temperature = targetTemp)
            activePreset = null
        }
    }

    /** 记一次用户操作：更新保护窗口；细粒度调整会退出预设高亮（上游同理）。 */
    fun touch(clearPreset: Boolean = true) {
        lastActionAt = System.currentTimeMillis()
        if (clearPreset) activePreset = null
    }

    /** 拼一条「只改一项、其余沿用当前编辑态」的 170 指令名。 */
    fun adjustName(
        op: String,
        windLevel: Int = edit.wind,
        targetPosition: String = edit.position,
        targetCircle: String = edit.circle,
        temperature: Int = edit.temperature,
        defogging: Boolean = edit.defogging,
    ): String = Commands.acSettingName(
        op = op,
        temperature = temperature,
        windLevel = windLevel,
        mode = edit.mode,
        circle = targetCircle,
        windshield = defogging,
        position = targetPosition,
    )

    /**
     * 上游的预设按钮：一条指令 + 同步六个编辑态 + 清掉预设高亮的保护窗口。
     * `defrost` 的编辑态取本项目实测过的参数组，不是上游文档那条。
     */
    fun applyPreset(name: String) {
        val next = presetEditOf(name) ?: return
        touch(clearPreset = false)
        activePreset = name
        edit = next
        onPresetTemperature(next.temperature)
        onCommand(name, null)
    }

    // ---- 舒适件的本地档位 ----
    // 与风量同理：车端回报滞后甚至不上报，直接读 status 会一直显示 0 档，
    // 点第二下又发一次「开」，用户永远关不掉。
    val remoteSeatHeatDriver = status?.seatHeating?.driver ?: 0
    val remoteSeatHeatPassenger = status?.seatHeating?.passenger ?: 0
    val remoteSeatVentDriver = status?.seatVentilation?.driver ?: 0
    val remoteSeatVentPassenger = status?.seatVentilation?.passenger ?: 0
    val remoteSteering = status?.steeringWheelHeating ?: 0
    val remoteMirror = if (status?.rearviewMirrorHeating == true) 1 else 0
    var seatHeatDriver by remember(remoteSeatHeatDriver) { mutableStateOf(remoteSeatHeatDriver) }
    var seatHeatPassenger by remember(remoteSeatHeatPassenger) { mutableStateOf(remoteSeatHeatPassenger) }
    var seatVentDriver by remember(remoteSeatVentDriver) { mutableStateOf(remoteSeatVentDriver) }
    var seatVentPassenger by remember(remoteSeatVentPassenger) { mutableStateOf(remoteSeatVentPassenger) }
    var steeringLevel by remember(remoteSteering) { mutableStateOf(remoteSteering) }
    var mirrorLevel by remember(remoteMirror) { mutableStateOf(remoteMirror) }

    val fanProgress = rememberLoopProgress(
        cycleMs = FAN_CYCLE_MS,
        enabled = acOn,
        intervalNs = FRAME_INTERVAL_NS,
    )
    val idleProgress = remember { mutableFloatStateOf(0f) }
    val shownProgress: State<Float> = if (acOn) fanProgress else idleProgress

    val summary = listOfNotNull(
        if (remoteWind != null || lastActionAt > 0L) "${edit.wind} 挡风" else null,
        if (edit.circle == "out") "外循环" else "内循环",
        if (edit.position == "wshld") "前挡出风" else "全车出风",
    ).joinToString(" · ")

    val statusTitle = when {
        !acOn -> "空调已关闭"
        activePreset == "quickCool" -> "极速降温"
        activePreset == "quickHeat" -> "一键制热"
        activePreset == "defrost" -> "前挡除霜"
        activePreset == "deodorize" -> "快速除味"
        else -> "空调运行中"
    }

    // 本项目保留的「运行状态」清单：只列舒适件，空调状态已经在标题里写过一次
    val runningItems = buildList {
        if (caps.seatHeating.driver && seatHeatDriver > 0) add("主驾座椅加热")
        if (caps.seatVentilation.driver && seatVentDriver > 0) add("主驾座椅通风")
        if (caps.seatHeating.passenger && seatHeatPassenger > 0) add("副驾座椅加热")
        if (caps.seatVentilation.passenger && seatVentPassenger > 0) add("副驾座椅通风")
        if (caps.steeringWheelHeating && SteeringWheelHeat.isOn(steeringLevel)) add("方向盘加热")
        // 后视镜加热按用户 2026-11-07 的新口径常显：撤销「没有的功能不显示」，
        // 指令链路（cmdid=440）与 VehicleCapabilities 本身都不动。
        if (mirrorLevel > 0) add("后视镜加热")
    }

    val suggestion = acSuggestion(indoorC = indoorC, activePreset = activePreset, acOn = acOn)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        AcTopBar(onBack = onBack)

        if (busyLabel != null) {
            Spacer(Modifier.height(8.dp))
            AcBusyStrip(label = busyLabel)
        }

        // ① 07 的冷暖渐变状态卡：开关 + 车内温度 + 运行状态（电源仍在最上面）
        Spacer(Modifier.height(12.dp))
        AcStatusCard(
            title = statusTitle,
            summary = summary,
            indoorTemp = indoor,
            acOn = acOn,
            progress = shownProgress,
            busy = busy,
            runningItems = runningItems,
            onPower = { onPower(!acOn) },
            onRefresh = onRefresh,
        )

        // ② 智能建议：没有实况天气，只在车内极热 / 极冷时给一条预设建议
        if (suggestion != null) {
            Spacer(Modifier.height(10.dp))
            AcSuggestionCard(
                suggestion = suggestion,
                enabled = !busy,
                onAction = { applyPreset(suggestion.command) },
            )
        }

        // ③ 设定温度：07 把上游的大数字压成一条横向温度带，给下面的功能留出首屏空间
        Spacer(Modifier.height(10.dp))
        AcSection(padding = 12.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TempStepButton(
                    plus = false,
                    enabled = AcTempDraft.canStep(edit.temperature, -1),
                    onClick = {
                        val next = AcTempDraft.step(edit.temperature, -1)
                        if (next != edit.temperature) {
                            touch()
                            edit = edit.copy(temperature = next, defogging = false, position = "all")
                            onTemperature(next)
                        }
                    },
                    touch = 40.dp,
                    arm = 13.dp,
                    label = "降低设定温度",
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${edit.temperature}",
                            color = C.textPrimary,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = (-1).sp,
                            maxLines = 1,
                        )
                        Text(
                            text = "℃",
                            color = C.textPrimary.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.padding(bottom = 12.dp, start = 1.dp),
                        )
                    }
                    Text(
                        text = "设定温度",
                        color = C.textSecondary,
                        fontSize = 11.5.sp,
                    )
                }
                TempStepButton(
                    plus = true,
                    enabled = AcTempDraft.canStep(edit.temperature, 1),
                    onClick = {
                        val next = AcTempDraft.step(edit.temperature, 1)
                        if (next != edit.temperature) {
                            touch()
                            edit = edit.copy(temperature = next, defogging = false, position = "all")
                            onTemperature(next)
                        }
                    },
                    touch = 40.dp,
                    arm = 13.dp,
                    label = "升高设定温度",
                )
            }
        }

        // ④ 四个快捷场景：上游第 4 格标签写的是「当前循环状态」，点亮表示内循环
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AcSceneTile(
                glyph = AcGlyph.Snowflake,
                label = "极速降温",
                modifier = Modifier.weight(1f),
                active = activePreset == "quickCool",
                enabled = !busy,
                onClick = { applyPreset("quickCool") },
            )
            AcSceneTile(
                glyph = AcGlyph.Sun,
                label = "一键制热",
                modifier = Modifier.weight(1f),
                active = activePreset == "quickHeat",
                enabled = !busy,
                onClick = { applyPreset("quickHeat") },
            )
            AcSceneTile(
                glyph = AcGlyph.Defrost,
                label = "前挡除霜",
                modifier = Modifier.weight(1f),
                active = activePreset == "defrost" || remoteDefog,
                enabled = !busy,
                onClick = { applyPreset("defrost") },
            )
            AcSceneTile(
                glyph = AcGlyph.Circulation,
                label = if (edit.circle == "in") "内循环" else "外循环",
                modifier = Modifier.weight(1f),
                active = edit.circle == "in",
                enabled = !busy,
                onClick = {
                    val target = if (edit.circle == "in") "out" else "in"
                    val command = adjustName("circle", targetCircle = target)
                    val previous = edit
                    touch()
                    edit = edit.copy(circle = target)
                    onCommand(command) { edit = previous }
                },
            )
        }

        // ⑤ 快速除味 / 定时空调
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AcWideTile(
                glyph = AcGlyph.Fresh,
                label = "快速除味",
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = { applyPreset("deodorize") },
            )
            AcWideTile(
                glyph = AcGlyph.Timer,
                label = "定时空调",
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onOpenScheduledAc,
            )
        }

        // ⑥ 风量滑杆：上游是「拖动结束才下发、按整数档吸附」，不是 7 个格子
        Spacer(Modifier.height(12.dp))
        AcSection(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "风量", color = C.textSecondary, fontSize = 12.sp, letterSpacing = 0.3.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${edit.wind} 挡",
                    color = C.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            var windDraft by remember(edit.wind) { mutableFloatStateOf(edit.wind.toFloat()) }
            Slider(
                value = windDraft,
                onValueChange = { windDraft = it },
                // 松手才下发：拖动过程中每跳一档都打一次网关会被限流（上游同款处理）
                onValueChangeFinished = {
                    val level = windDraft.roundToInt()
                        .coerceIn(Commands.MIN_AC_WIND_LEVEL, Commands.MAX_AC_WIND_LEVEL)
                    val previous = edit
                    touch()
                    edit = edit.copy(wind = level)
                    onCommand(adjustName("wind", windLevel = level)) { edit = previous }
                },
                valueRange = Commands.MIN_AC_WIND_LEVEL.toFloat()..Commands.MAX_AC_WIND_LEVEL.toFloat(),
                steps = Commands.MAX_AC_WIND_LEVEL - Commands.MIN_AC_WIND_LEVEL - 1,
                enabled = !busy,
                colors = SliderDefaults.colors(
                    thumbColor = C.accent,
                    activeTrackColor = C.accent,
                    inactiveTrackColor = C.textPrimary.copy(alpha = 0.12f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (remoteWind == null && lastActionAt == 0L) {
                Text(
                    text = "车端未上报风量，滑杆从 ${edit.wind} 挡起调",
                    color = C.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        // ⑦ 出风方向：上游「全车出风」会顺带关除霜，「前风挡出风」会打开除霜位
        Spacer(Modifier.height(12.dp))
        AcSection(title = "出风方向") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AcChoiceTile(
                    glyph = AcGlyph.Wind,
                    label = "全车出风",
                    selected = edit.position != "wshld",
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (edit.position != "all") {
                        val command = adjustName(
                            "position",
                            targetPosition = "all",
                            defogging = false,
                        )
                        val previous = edit
                        touch()
                        edit = edit.copy(position = "all", defogging = false)
                        onCommand(command) { edit = previous }
                    }
                }
                AcChoiceTile(
                    glyph = AcGlyph.Defrost,
                    label = "前风挡出风",
                    selected = edit.position == "wshld",
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (edit.position != "wshld") {
                        val command = adjustName(
                            "position",
                            targetPosition = "wshld",
                            defogging = true,
                        )
                        val previous = edit
                        touch()
                        edit = edit.copy(position = "wshld", defogging = true)
                        onCommand(command) { edit = previous }
                    }
                }
            }
        }

        // ⑧ 座椅与座舱舒适：07 的 2×2 四宫格（主驾 / 副驾 / 方向盘 / 后视镜）。
        // 上游是 0/2 两态开关，开加热会把通风本地归零（不额外下发）；仍是一屏展开不折叠。
        // 后视镜加热常显：撤销「没有的功能不显示」，指令仍是 cmdid=440 那一条。
        val hasDriver = caps.seatHeating.driver || caps.seatVentilation.driver
        val hasPassenger = caps.seatHeating.passenger || caps.seatVentilation.passenger
        Spacer(Modifier.height(12.dp))
        AcSection(title = "座椅与座舱舒适") {
            if (hasDriver || hasPassenger) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (hasDriver) {
                            AcSeatBlock(
                                title = "主驾驶位",
                                heatingOn = if (caps.seatHeating.driver) seatHeatDriver > 0 else null,
                                ventilationOn = if (caps.seatVentilation.driver) seatVentDriver > 0 else null,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onHeating = { on ->
                                    val previousHeat = seatHeatDriver
                                    val previousVent = seatVentDriver
                                    seatHeatDriver = if (on) 2 else 0
                                    if (on) seatVentDriver = 0
                                    onCommand("driverSeatHeating_${if (on) 2 else 0}") {
                                        seatHeatDriver = previousHeat
                                        seatVentDriver = previousVent
                                    }
                                },
                                onVentilation = { on ->
                                    val previousHeat = seatHeatDriver
                                    val previousVent = seatVentDriver
                                    seatVentDriver = if (on) 2 else 0
                                    if (on) seatHeatDriver = 0
                                    onCommand("driverSeatVentilation_${if (on) 2 else 0}") {
                                        seatHeatDriver = previousHeat
                                        seatVentDriver = previousVent
                                    }
                                },
                            )
                        }
                        if (hasPassenger) {
                            AcSeatBlock(
                                title = "副驾驶位",
                                heatingOn = if (caps.seatHeating.passenger) seatHeatPassenger > 0 else null,
                                ventilationOn = if (caps.seatVentilation.passenger) seatVentPassenger > 0 else null,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onHeating = { on ->
                                    val previousHeat = seatHeatPassenger
                                    val previousVent = seatVentPassenger
                                    seatHeatPassenger = if (on) 2 else 0
                                    if (on) seatVentPassenger = 0
                                    onCommand("passengerSeatHeating_${if (on) 2 else 0}") {
                                        seatHeatPassenger = previousHeat
                                        seatVentPassenger = previousVent
                                    }
                                },
                                onVentilation = { on ->
                                    val previousHeat = seatHeatPassenger
                                    val previousVent = seatVentPassenger
                                    seatVentPassenger = if (on) 2 else 0
                                    if (on) seatHeatPassenger = 0
                                    onCommand("passengerSeatVentilation_${if (on) 2 else 0}") {
                                        seatHeatPassenger = previousHeat
                                        seatVentPassenger = previousVent
                                    }
                                },
                            )
                        }
                    }
                }

            // 后视镜加热常显：不再读能力门禁，也不再藏进「更多设置」
            if (hasDriver || hasPassenger) Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (caps.steeringWheelHeating) {
                    val steeringOn = SteeringWheelHeat.isOn(steeringLevel)
                    AcComfortTile(
                        glyph = AcGlyph.Steering,
                        title = "方向盘加热",
                        stateText = if (steeringOn) "开" else "关",
                        on = steeringOn,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val previous = steeringLevel
                            steeringLevel = if (steeringOn) 0 else 2
                            onCommand(
                                if (steeringOn) SteeringWheelHeat.OFF else SteeringWheelHeat.ON,
                            ) { steeringLevel = previous }
                        },
                    )
                }
                AcComfortTile(
                    glyph = AcGlyph.Mirror,
                    title = "后视镜加热",
                    stateText = if (mirrorLevel > 0) "开" else "关",
                    on = mirrorLevel > 0,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val next = if (mirrorLevel > 0) 0 else 1
                        val previous = mirrorLevel
                        mirrorLevel = next
                        onCommand(
                            if (next > 0) "rearviewMirrorHeating_on"
                            else "rearviewMirrorHeating_off",
                        ) { mirrorLevel = previous }
                    },
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** 没读到车端风量时的默认挡位（与 [Commands.AC_ON_STATE] 的 2 挡一致）。 */
private const val DEFAULT_WIND = 2

/** 用户刚点过按钮后，多久之内不接受旧车况把乐观值盖回去（上游是 8 秒）。 */
private const val AC_OPTIMISTIC_WINDOW_MS = 8_000L

/**
 * 上游 `editedTemperature / editedWindLevel / editedCircle / editedDefogging / editedOutlet`
 * 的本项目等价物：一次细粒度下发要把这六个值**整组**带上。
 */
internal data class AcEdit(
    val temperature: Int,
    val wind: Int,
    val mode: String,
    val circle: String,
    val defogging: Boolean,
    val position: String,
)

/**
 * 预设下发后，界面应该立刻切到的整组值。
 *
 * `defrost` 用的是本项目 `Commands.DEFROST_VARIANTS.first()`（32℃ / 7 挡 / hot /
 * 外循环 / 前挡出风）—— 上游文档那条 24℃/5 挡/cold 在真车上只改风量、前挡不出风，
 * 2026-11-03 用户实测后已改掉，别按上游改回去。
 */
internal fun presetEditOf(name: String): AcEdit? = when (name) {
    "quickCool" -> AcEdit(18, 7, "cold", "in", false, "all")
    "quickHeat" -> AcEdit(32, 7, "hot", "in", false, "all")
    "defrost" -> AcEdit(32, 7, "hot", "out", true, "wshld")
    "deodorize" -> AcEdit(24, 7, "nohotcold", "out", false, "all")
    else -> null
}

/** 顶部建议条的一条建议。 */
internal data class AcSuggestion(
    val title: String,
    val description: String,
    val actionLabel: String,
    val command: String,
)

/**
 * 上游 `ClimateSmartRecommendationPolicy.resolve` 的本地可判定部分。
 *
 * 上游候选优先级是「雨雪 → 车内 ≥32 → 车内 ≤12 → 潮湿」，其中雨雪/潮湿来自高德
 * 实况天气。本项目不接实时天气接口，只保留车内温度这两条；**没有可判定条件时返回 null，
 * 建议条整块不显示**，不再挂常驻提示（用户 2026-11-07 明确要求）。
 * 除霜功能仍在「前挡除霜」场景磁贴里，功能没少。
 *
 * 与上游一致的隐藏条件：空调已开且当前预设就是这条建议时不再显示。
 */
internal fun acSuggestion(
    indoorC: Double?,
    activePreset: String?,
    acOn: Boolean,
): AcSuggestion? {
    val candidates = buildList {
        if (indoorC != null && indoorC >= 32.0) {
            add(
                AcSuggestion(
                    title = "车内偏热 · 快速降温",
                    description = "车内温度偏高，可以一键开启极速降温",
                    actionLabel = "一键降温",
                    command = "quickCool",
                ),
            )
        }
        if (indoorC != null && indoorC <= 12.0) {
            add(
                AcSuggestion(
                    title = "车内偏冷 · 快速升温",
                    description = "车内温度偏低，可以一键开启制热",
                    actionLabel = "一键制热",
                    command = "quickHeat",
                ),
            )
        }
    }
    // 上游：!isAcOn || (activePreset != preset.name && activePreset != command)
    return candidates.firstOrNull { !acOn || activePreset != it.command }
}

/**
 * 把命令名翻成一句「人要读的话」，用于二次确认弹窗与「正在下发」提示。
 *
 * 命令名的中文标签统一由 [Commands.build] 提供（`driverSeatHeating_2` → 「主驾加热2档」），
 * 这里只是兜住非法命令名的异常，不让一个文案把页面搞崩。
 */
internal fun acCommandLabel(commandName: String): String = when (commandName) {
    "acTemp" -> "调整设定温度"
    else -> runCatching { Commands.build(commandName).label }.getOrElse { "空调操作" }
}

/** 「正在下发：xxx，等待车端确认…」提示条。 */
@Composable
private fun AcBusyStrip(label: String) {
    val C = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.accent.copy(alpha = 0.12f))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 1.8.dp,
            color = C.accent,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = "正在下发：$label，等待车端确认…",
            color = C.accent,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// 页面骨架
// ---------------------------------------------------------------------------

/** 顶栏：左侧返回圆钮 + 居中标题。 */
@Composable
private fun AcTopBar(onBack: () -> Unit) {
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
            text = "空调",
            color = C.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * 一块实心卡片。整页由若干块拼成，不画阴影：首页本身是渐变背景，
 * 这里再叠阴影会让页面显脏（与「控车提示」那次踩的坑同源）。
 */
@Composable
private fun AcSection(
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
 * 07「冷暖分区」的顶部状态卡：**整页唯一带颜色的卡片**。
 *
 * 低饱和「冷蓝 → 主题绿 → 暖橙」斜向渐变只负责冷暖语义，文字仍取主题的
 * [AppColors.textPrimary] / [AppColors.textSecondary]；深色下把三个颜色的 alpha
 * 一起压低，避免卡片比下面的中性卡亮一大截。渐变不依赖天气数据。
 *
 * 功能一个没少：电源、车内温度、运行状态清单、刷新都在，只是换了排版。
 */
@Composable
private fun AcStatusCard(
    title: String,
    summary: String,
    indoorTemp: String,
    acOn: Boolean,
    progress: State<Float>,
    busy: Boolean,
    runningItems: List<String>,
    onPower: () -> Unit,
    onRefresh: () -> Unit,
) {
    val C = LocalAppColors.current
    val cool = Color(0xFF56A4DE).copy(alpha = if (C.isDark) 0.13f else 0.22f)
    val middle = C.accent.copy(alpha = if (C.isDark) 0.07f else 0.10f)
    val warm = Color(0xFFEE9E56).copy(alpha = if (C.isDark) 0.11f else 0.24f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(cool, middle, warm)),
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AcPowerButton(
                acOn = acOn,
                busy = busy,
                progress = progress,
                size = 40.dp,
                iconSize = 22.dp,
                onClick = onPower,
            )
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = C.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = summary,
                    color = C.textSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (acOn) C.textPrimary.copy(alpha = 0.06f)
                        else C.accent.copy(alpha = 0.14f),
                    )
                    .clickable(onClick = onPower)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (acOn) "关闭" else "开启",
                    color = if (acOn) C.textSecondary else C.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // 07 的主角是车内温度；设定温度在下面那条温度带里，两处不重复
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$indoorTemp℃",
                color = C.textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "车内温度",
                color = C.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(C.textPrimary.copy(alpha = 0.07f)),
        )
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "运行状态",
                color = C.textSecondary,
                fontSize = 11.5.sp,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AcIcon(AcGlyph.Circulation, C.accent, size = 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "刷新",
                    color = C.accent,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (runningItems.isEmpty()) {
            Text(
                text = "当前没有开启的座舱舒适功能",
                color = C.textSecondary.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
        } else {
            // 一行排不下就换行，不省略：这些都是「开没开」的关键信息
            val rows = runningItems.chunked(2)
            rows.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(C.accent),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(text = item, color = C.textPrimary, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    // 奇数个时补一个空的 weight，避免最后一项被拉满整行
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 上游的「智能推荐」条：一句提示 + 一个动作按钮；没有条件可判定时整块不渲染。 */
@Composable
private fun AcSuggestionCard(
    suggestion: AcSuggestion,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    val C = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.textPrimary.copy(alpha = 0.045f), RoundedCornerShape(16.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(C.panelBg),
            contentAlignment = Alignment.Center,
        ) {
            AcIcon(AcGlyph.Defrost, C.accent, size = 18.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.title,
                color = C.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = suggestion.description,
                color = C.textSecondary,
                fontSize = 11.sp,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(C.accent.copy(alpha = 0.14f))
                .clickable(enabled = enabled, onClick = onAction)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = suggestion.actionLabel,
                color = C.accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 格子 / 行
// ---------------------------------------------------------------------------

/**
 * 场景格：图标放在淡色圆底里、文字在下。
 *
 * 图标单独加一层圆底，是为了让 4 个格子的视觉重心一致 —— 直接放裸图标时，
 * 雪花（满幅）与定时（方框）的墨量差异很大，一排看过去会一重一轻。
 */
@Composable
private fun AcSceneTile(
    glyph: AcGlyph,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val C = LocalAppColors.current
    val content = when {
        !enabled -> C.textSecondary.copy(alpha = 0.42f)
        active -> C.accent
        else -> C.textPrimary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (active) C.accent.copy(alpha = 0.14f) else C.textPrimary.copy(alpha = 0.045f),
                RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (active) C.accent.copy(alpha = 0.16f) else C.panelBg),
            contentAlignment = Alignment.Center,
        ) {
            AcIcon(glyph, content, size = 18.dp)
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            color = content,
            fontSize = 11.5.sp,
            maxLines = 1,
        )
    }
}

/** 宽行：图标 + 文字 + 右箭头，用于「除味 / 定时」这类一次性动作。 */
@Composable
private fun AcWideTile(
    glyph: AcGlyph,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val C = LocalAppColors.current
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(C.textPrimary.copy(alpha = 0.045f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AcIcon(
            glyph,
            if (enabled) C.accent else C.textSecondary.copy(alpha = 0.42f),
            size = 18.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = if (enabled) C.textPrimary else C.textSecondary.copy(alpha = 0.42f),
            fontSize = 12.5.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        AcIcon(AcGlyph.ChevronRight, C.textSecondary.copy(alpha = 0.6f), size = 13.dp)
    }
}

/** 出风方向的选择格：图标 + 文字横向排布，选中态淡主题色底 + 主题色文字。 */
@Composable
private fun AcChoiceTile(
    glyph: AcGlyph,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val C = LocalAppColors.current
    val content = when {
        !enabled -> C.textSecondary.copy(alpha = 0.42f)
        selected -> C.accent
        else -> C.textSecondary
    }
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) C.accent.copy(alpha = 0.14f) else C.textPrimary.copy(alpha = 0.045f),
                RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AcIcon(glyph, content, size = 18.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            color = content,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 一个座位的两块开关（加热 / 通风）。
 *
 * 上游是**两态开关**（只发 `_2` / `_0`），不是 0→1→2→3 档位循环：
 * 底层 301 / 370 虽然支持 0..3，但空调页这个卡片只给用户「开 / 关」。
 * 开加热时把通风本地归零（互斥），**不额外下发一条关闭通风的指令** —— 上游同理。
 */
@Composable
private fun AcSeatBlock(
    title: String,
    heatingOn: Boolean?,
    ventilationOn: Boolean?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onHeating: (Boolean) -> Unit,
    onVentilation: (Boolean) -> Unit,
) {
    val C = LocalAppColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(C.textPrimary.copy(alpha = 0.045f), RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        Text(text = title, color = C.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        if (heatingOn != null) {
            AcLevelRow(
                glyph = AcGlyph.SeatHeat,
                label = "座椅加热",
                level = if (heatingOn) 1 else 0,
                maxLevel = 1,
                stateText = if (heatingOn) "开" else "关",
                enabled = enabled,
                compact = true,
                onClick = { onHeating(!heatingOn) },
            )
            if (ventilationOn != null) Spacer(Modifier.height(6.dp))
        }
        if (ventilationOn != null) {
            AcLevelRow(
                glyph = AcGlyph.SeatVent,
                label = "座椅通风",
                level = if (ventilationOn) 1 else 0,
                maxLevel = 1,
                stateText = if (ventilationOn) "开" else "关",
                enabled = enabled,
                compact = true,
                onClick = { onVentilation(!ventilationOn) },
            )
        }
    }
}

/**
 * 07 舒适 2×2 里的单件格（方向盘 / 后视镜）：图标 + 名称 + 状态 + 右侧「开 / 关」。
 *
 * 与 [AcSeatBlock] 同底色、同圆角、同内边距，四格摆在一起才齐；整格都是点击区，
 * 比原来那两行列表更好按。图标统一走 [AcGlyph] 的网络矢量资源。
 */
@Composable
private fun AcComfortTile(
    glyph: AcGlyph,
    title: String,
    stateText: String,
    on: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val C = LocalAppColors.current
    val content = when {
        !enabled -> C.textSecondary.copy(alpha = 0.42f)
        on -> C.accent
        else -> C.textPrimary
    }
    Row(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(C.textPrimary.copy(alpha = 0.045f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AcIcon(glyph, content, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = content,
                fontSize = 11.5.sp,
                maxLines = 1,
            )
            Text(
                text = "状态",
                color = C.textSecondary,
                fontSize = 10.sp,
            )
        }
        Text(
            text = stateText,
            color = content,
            fontSize = 12.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 一行开关：图标 + 名称 + 右侧档位点阵 / 「开 关」文字。
 *
 * @param stateText 非空时右侧写这两个字（两态开关没有「档」可数）
 * @param compact 嵌在座位卡里时用（行高更低、左右内边距更小）
 */
@Composable
private fun AcLevelRow(
    glyph: AcGlyph,
    label: String,
    level: Int,
    maxLevel: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    stateText: String? = null,
    onClick: () -> Unit,
) {
    val C = LocalAppColors.current
    val on = level > 0
    val content = when {
        !enabled -> C.textSecondary.copy(alpha = 0.42f)
        on -> C.accent
        else -> C.textPrimary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (compact) C.panelBg.copy(alpha = 0.55f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 7.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AcIcon(glyph, content, size = if (compact) 16.dp else 18.dp)
        Spacer(Modifier.width(if (compact) 7.dp else 9.dp))
        Text(
            text = label,
            color = content,
            fontSize = if (compact) 11.5.sp else 13.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
        if (stateText != null) {
            Text(
                text = stateText,
                color = content,
                fontSize = if (compact) 11.5.sp else 12.5.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 1..maxLevel) {
                    val lit = i <= level
                    Box(
                        modifier = Modifier
                            .size(if (compact) 6.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    lit -> content
                                    else -> C.textPrimary.copy(alpha = if (enabled) 0.14f else 0.07f)
                                },
                            ),
                    )
                }
            }
        }
    }
}
