package com.leapcontrol.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.DEFAULT_VEHICLE_TITLE
import com.leapcontrol.app.widget.VehicleWidget
import com.leapcontrol.app.widget.WidgetButtonLayout
import com.leapcontrol.app.widget.WidgetMetrics
import com.leapcontrol.app.widget.WidgetStyle
import com.leapcontrol.app.widget.WidgetWallpaper
import kotlin.math.roundToInt

/**
 * 「个性化 → 桌面小组件」。
 *
 * 三套版式（素纸 / 沉浸 / 全透明，默认全透明）是三份独立的 RemoteViews 布局，选了立刻重画桌面上已有的卡片；
 * 沉浸样式额外给一个背景图入口 —— 素纸那张卡没有「底图」这个概念，选了也没用，所以只在 D 下出现。
 *
 * 缩略图是**示意图**不是真实渲染：真正的卡片要由桌面进程用 RemoteViews 画出来，
 * Compose 里再实现一遍只会跟实际效果不一致。这里只用色块交代「哪边是车模、下面几格」。
 */
@Composable
fun WidgetSettingsGroup() {
    val context = LocalContext.current
    val C = LocalAppColors.current
    var style by remember { mutableStateOf(WidgetStyle.style(context)) }
    var revision by remember { mutableIntStateOf(WidgetStyle.revision(context)) }
    var customBg by remember { mutableStateOf(WidgetWallpaper.exists(context)) }
    var ink by remember { mutableIntStateOf(WidgetStyle.ink(context)) }
    var ctrl by remember { mutableIntStateOf(WidgetStyle.ctrl(context)) }
    // 控车按钮的顺序摘要：prefs 不可观察，退回本页时回读一次
    var buttonSummary by remember {
        mutableStateOf(WidgetButtonLayout.load(context).joinToString(" · ") { it.title })
    }
    var showButtons by remember { mutableStateOf(false) }
    // 按键的两个外观开关：底色与分隔线互相独立，都存 prefs，回来时回读一次
    var cellBg by remember { mutableStateOf(WidgetStyle.cellBgVisible(context)) }
    var dividers by remember { mutableStateOf(WidgetStyle.dividersVisible(context)) }
    // 左上角车标显隐（2026-11-16）：关掉后车名往左占满那一行
    var logoVisible by remember { mutableStateOf(WidgetStyle.logoVisible(context)) }
    // 信息区大小（字号 + 进度条粗细共用这一个倍率）：拖动时只更新本地 + 写 prefs，
    // 松手才整卡重画（全量重绘在沉浸样式下会让桌面重新解码一次壁纸，跟着手指每帧重画会卡）
    var infoScale by remember { mutableFloatStateOf(WidgetStyle.infoScale(context)) }
    // 三根细节微调：各自独立存 prefs，默认 1.0（老设备升级后卡片不变）
    var iconScale by remember { mutableFloatStateOf(WidgetStyle.iconScale(context)) }
    var headerScale by remember { mutableFloatStateOf(WidgetStyle.headerScale(context)) }
    // 左上角车标（2026-11-13 需求）：与上面两根同档范围，但左边不跟着动
    var logoScale by remember { mutableFloatStateOf(WidgetStyle.logoScale(context)) }

    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            val bitmap = decodeScaled(context, uri, 1600) ?: return@runCatching false
            WidgetWallpaper.save(context, bitmap)
        }.getOrDefault(false)
        Toast.makeText(
            context,
            if (ok) "背景已保存，桌面上立刻生效" else "这张图读不出来，换一张试试",
            Toast.LENGTH_SHORT,
        ).show()
        if (ok) {
            revision = WidgetStyle.revision(context)
            customBg = true
            VehicleWidget.render(context)
        }
    }

    // 整页覆盖：仿 SettingsScreen 里二级页的写法。放在 pickBg 之后 return，
    // 那些 remember / rememberLauncher 都还在组合里，进出页面不会反复注册。
    if (showButtons) {
        WidgetButtonsScreen(onBack = {
            showButtons = false
            buttonSummary = WidgetButtonLayout.load(context).joinToString(" · ") { it.title }
        })
        return
    }

    WidgetSection(
        style = style,
        revision = revision,
        customBg = customBg,
        ink = ink,
        ctrl = ctrl,
        buttonSummary = buttonSummary,
        cellBg = cellBg,
        dividers = dividers,
        logoVisible = logoVisible,
        infoScale = infoScale,
        iconScale = iconScale,
        headerScale = headerScale,
        logoScale = logoScale,
        onOpenButtons = { showButtons = true },
        onToggleCellBg = { on ->
            cellBg = on
            WidgetStyle.setCellBgVisible(context, on)
            // 按键底是结构属性，只能走全量重画（增量不碰背景那一层）
            VehicleWidget.render(context)
        },
        onToggleDividers = { on ->
            dividers = on
            WidgetStyle.setDividersVisible(context, on)
            VehicleWidget.render(context)
        },
        onToggleLogo = { on ->
            logoVisible = on
            WidgetStyle.setLogoVisible(context, on)
            // 显隐用 GONE 改的是布局结构（车名要跟着左移），增量更新碰不到
            VehicleWidget.render(context)
        },
        onChangeInfoScale = { v ->
            infoScale = v
            // 拖动中只落盘，不动桌面卡片：松手才重画（见 onCommitSize）
            WidgetStyle.setInfoScale(context, v)
        },
        onChangeIconScale = { v ->
            iconScale = v
            WidgetStyle.setIconScale(context, v)
        },
        onChangeHeaderScale = { v ->
            headerScale = v
            WidgetStyle.setHeaderScale(context, v)
        },
        onChangeLogoScale = { v ->
            logoScale = v
            WidgetStyle.setLogoScale(context, v)
        },
        onCommitSize = { VehicleWidget.render(context) },
        onResetSize = {
            WidgetStyle.resetSize(context)
            infoScale = WidgetStyle.infoScale(context)
            iconScale = WidgetStyle.iconScale(context)
            headerScale = WidgetStyle.headerScale(context)
            logoScale = WidgetStyle.logoScale(context)
            VehicleWidget.render(context)
            Toast.makeText(context, "已恢复默认大小", Toast.LENGTH_SHORT).show()
        },
        onSelectStyle = { picked ->
            style = picked
            WidgetStyle.setStyle(context, picked)
            VehicleWidget.render(context)
        },
        onPickInk = { color ->
            ink = color
            WidgetStyle.setInk(context, color)
            VehicleWidget.render(context)
        },
        onPickCtrl = { color ->
            ctrl = color
            WidgetStyle.setCtrl(context, color)
            VehicleWidget.render(context)
        },
        onResetColors = {
            WidgetStyle.resetColors(context)
            ink = WidgetStyle.ink(context)
            ctrl = WidgetStyle.ctrl(context)
            VehicleWidget.render(context)
            Toast.makeText(context, "已恢复默认颜色", Toast.LENGTH_SHORT).show()
        },
        onPickBg = {
            pickBg.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onClearBg = {
            WidgetWallpaper.clear(context)
            revision = WidgetStyle.revision(context)
            customBg = false
            VehicleWidget.render(context)
            Toast.makeText(context, "已恢复默认背景", Toast.LENGTH_SHORT).show()
        },
    )

    Spacer(Modifier.height(10.dp))
    Text(
        "三套版式随时可以换，桌面上已有的卡片会立刻跟着变，不用删掉重加。",
        color = C.textSecondary,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun WidgetSection(
    style: String,
    revision: Int,
    customBg: Boolean,
    ink: Int,
    ctrl: Int,
    buttonSummary: String,
    cellBg: Boolean,
    dividers: Boolean,
    logoVisible: Boolean,
    infoScale: Float,
    iconScale: Float,
    headerScale: Float,
    logoScale: Float,
    onOpenButtons: () -> Unit,
    onToggleCellBg: (Boolean) -> Unit,
    onToggleDividers: (Boolean) -> Unit,
    onToggleLogo: (Boolean) -> Unit,
    onChangeInfoScale: (Float) -> Unit,
    onChangeIconScale: (Float) -> Unit,
    onChangeHeaderScale: (Float) -> Unit,
    onChangeLogoScale: (Float) -> Unit,
    onCommitSize: () -> Unit,
    onResetSize: () -> Unit,
    onSelectStyle: (String) -> Unit,
    onPickInk: (Int) -> Unit,
    onPickCtrl: (Int) -> Unit,
    onResetColors: () -> Unit,
    onPickBg: () -> Unit,
    onClearBg: () -> Unit,
) {
    val C = LocalAppColors.current
    val context = LocalContext.current
    SettingsSection(title = "桌面小组件", desc = "续航 · 五个控车按键") {
        SettingsCard {
            Row(modifier = Modifier.fillMaxWidth()) {
                WidgetStyle.ALL.forEachIndexed { index, item ->
                    if (index > 0) Spacer(Modifier.width(7.dp))
                    WidgetStyleThumb(
                        style = item,
                        selected = style == item,
                        ink = ink,
                        ctrl = ctrl,
                        cellBg = cellBg,
                        dividers = dividers,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectStyle(item) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                when (style) {
                    WidgetStyle.STYLE_D -> "沉浸：一张图铺满卡片，下半部分压暗保证白字读得清。"
                    WidgetStyle.STYLE_T -> "全透明：没有卡片底，字和按键直接浮在壁纸上，下面可以自己调颜色。"
                    else -> "素纸：浅色卡 + 发丝分隔线，跟系统自带的卡片最接近。"
                },
                color = C.textSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- 卡 2：五个按键换什么、长什么样 ----
        SettingsCard("控车按键") {
            ClickRow(
                title = "控车按钮",
                desc = buttonSummary,
                onClick = onOpenButtons,
            )

            // ---- 按键的两个外观开关（互相独立）----
            // 「圆角底」和「竖线」是两件事：有人只想去掉色块、保留分组；也有人两样都不要。
            // 素纸样式的按键本来就没底色，那里打开这个开关看不到任何变化 ——
            // 说明文字跟着版式换，否则用户会以为开关坏了。
            Spacer(Modifier.height(14.dp))
            SwitchRow(
                label = "按键背景",
                desc = if (style == WidgetStyle.STYLE_A) {
                    "素纸样式的按键本来就没有底色，这个开关只对沉浸 / 全透明有效。"
                } else {
                    "五个按键背后那层圆角矩形。关掉后只剩图标，看着更轻。"
                },
                checked = cellBg,
                onCheckedChange = onToggleCellBg,
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow(
                label = "分隔线",
                desc = "按键之间那四条竖向细线。关掉后五个按键连成一整条。",
                checked = dividers,
                onCheckedChange = onToggleDividers,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- 卡 3：大小（整体一根 + 三根细节微调）----
        SettingsCard("大小") {
            // ---- 大小：一根整体 + 三根细节微调 ----
            // 信息区（车名 / 续航 / 进度条）仍然只有一个倍率，不恢复「粗细单开一根」那套。
            // 下面三根是**独立**微调：五个控车图标、右上角时间与刷新键、左上角车标。
            // 三者都只动「容器里的内容」—— 格子、分隔线、点击区、行的均分全不变，
            // 所以拖到头也不会把版式撑歪（预览里特意把格子边框画出来给用户看）。
            Spacer(Modifier.height(14.dp))
            // 拖动时桌面卡片要等松手才重画，这里给一块跟着滑杆实时缩放的示意 ——
            // 不然滑杆拖到头也看不出变化，用户会以为没生效。
            WidgetInfoPreview(infoScale = infoScale)
            Spacer(Modifier.height(10.dp))
            WidgetSliderRow(
                label = "信息区大小",
                desc = "车名、续航数字和两根进度条一起放大缩小；右上角时间也跟着，" +
                    "另外还能在下面单独再微调。",
                value = infoScale,
                range = WidgetStyle.MIN_INFO_SCALE..WidgetStyle.MAX_INFO_SCALE,
                steps = 9,
                display = "${(infoScale * 100).roundToInt()}%",
                onChange = onChangeInfoScale,
                onCommit = onCommitSize,
            )

            Spacer(Modifier.height(14.dp))
            WidgetFinePreview(
                iconScale = iconScale,
                headerScale = headerScale,
                logoScale = logoScale,
                logoVisible = logoVisible,
            )
            Spacer(Modifier.height(10.dp))
            WidgetSliderRow(
                label = "控车图标大小",
                desc = "五个按键里的图标单独放大缩小。格子、分隔线和点按范围都不变，只在格子里收放图形。",
                value = iconScale,
                range = WidgetStyle.MIN_FINE_SCALE..WidgetStyle.MAX_FINE_SCALE,
                steps = 8,
                display = "${(iconScale * 100).roundToInt()}%",
                onChange = onChangeIconScale,
                onCommit = onCommitSize,
            )
            Spacer(Modifier.height(6.dp))
            WidgetSliderRow(
                label = "更新时间与刷新键",
                desc = "右上角那行时间和旁边那颗刷新键一起微调。文字太长会自动省略，" +
                    "刷新键的点按范围保持原样，不会挤到车模。",
                value = headerScale,
                range = WidgetStyle.MIN_FINE_SCALE..WidgetStyle.MAX_FINE_SCALE,
                steps = 8,
                display = "${(headerScale * 100).roundToInt()}%",
                onChange = onChangeHeaderScale,
                onCommit = onCommitSize,
            )
            Spacer(Modifier.height(6.dp))
            // 车标的显隐与大小放在一起：关掉之后那根「车标大小」滑杆就没有对象了，
            // 所以直接收起来，换成一句说明 —— 留着一根拖了没反应的滑杆比不显示更糟。
            // 默认关着（2026-11-16 用户定稿），要用的人自己打开。
            SwitchRow(
                label = "显示车标",
                desc = if (logoVisible) {
                    "车名前面那个车标。关掉后车名向左占满这一行，行首不留空档。"
                } else {
                    "默认不显示。打开后车名前面会出现一枚车标，车名相应往右让位。"
                },
                checked = logoVisible,
                onCheckedChange = onToggleLogo,
            )
            if (logoVisible) {
                Spacer(Modifier.height(6.dp))
                WidgetSliderRow(
                    label = "左上角车标大小",
                    desc = "车名前面那个车标单独放大缩小。它的左缘固定不动（车名不会被推走），" +
                        "字号和其它按键都不受影响。",
                    value = logoScale,
                    range = WidgetStyle.MIN_FINE_SCALE..WidgetStyle.MAX_FINE_SCALE,
                    steps = 8,
                    display = "${(logoScale * 100).roundToInt()}%",
                    onChange = onChangeLogoScale,
                    onCommit = onCommitSize,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "恢复默认大小",
                color = C.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onResetSize() }
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- 卡 4：颜色与背景（全透明字色与沉浸底图按需出现）----
        SettingsCard("颜色与背景") {
            // 这里原来有一条「状态提示高亮色」。2026-11-20 用户要求右上角那行提示与刷新键
            // 一律跟「字体颜色」走，那一项就不再有任何消费者 —— 留着只会让人调了没反应。
            // 老设备 prefs 里的旧值由 WidgetStyle.resetColors 顺手清掉。

            // ---- 全透明主题的颜色：只在选中它时出现 ----
            // 素纸 / 沉浸的配色是跟着「卡片底」定的，开放了反而会把可读性调坏，
            // 所以这两个不摆入口，界面也就不必解释「为什么这里不能调」。
            if (style == WidgetStyle.STYLE_T) {
                Spacer(Modifier.height(14.dp))
                WidgetColorRow(
                    title = "字体颜色",
                    desc = "车名、续航数字、百分比，以及五个按键图标的常态色。壁纸偏深就选浅色，反之选深色。",
                    color = ink,
                    onPick = onPickInk,
                )
                Spacer(Modifier.height(14.dp))
                WidgetColorRow(
                    title = "控件颜色",
                    desc = "五个按键的图标、按键之间的分隔线，以及按键生效时的高亮。" +
                        "默认是白色（和字色一致）；想一眼看出「哪个键正开着」，换成绿或橙更醒目。",
                    color = ctrl,
                    onPick = onPickCtrl,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "恢复默认颜色",
                    color = C.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onResetColors() }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                )
            }

            // 背景图只在沉浸样式下有意义，别的版式里不摆这个入口
            if (style == WidgetStyle.STYLE_D) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val preview: ImageBitmap? = remember(revision, customBg) {
                        runCatching { WidgetWallpaper.preview(context)?.asImageBitmap() }.getOrNull()
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 52.dp, height = 30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF3F5266), Color(0xFF9DB3C9)),
                                ),
                            )
                            .border(1.dp, C.border, RoundedCornerShape(8.dp)),
                    ) {
                        if (preview != null) {
                            Image(
                                bitmap = preview,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("卡片背景", color = C.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (customBg) "已用自定义图 · 下半部分会自动压暗" else "默认黄昏渐变 · 可以换成自己的图",
                            color = C.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPickBg() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            tint = C.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("换图", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (customBg) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "恢复默认背景",
                        color = C.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onClearBg() }
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                    )
                }
            }

        }

        Spacer(Modifier.height(10.dp))

        // ---- 卡 5：怎么把它放到桌面（说明，不是按钮）----
        SettingsCard {
            // 这里曾经是「添加到桌面」按钮（走 AppWidgetManager.requestPinAppWidget）。
            // 那个接口要求桌面自己实现「一键钉卡片」，MIUI / 澎湃桌面不实现 ——
            // 有的版本直接返回 false，有的返回 true 却毫无反应，且它不需要任何权限，
            // 应用这一侧无从修复。所以改成写清楚手动路径，比一个点了没反应的按钮诚实。
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = C.textSecondary,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("放到桌面", color = C.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "长按桌面空白处 → 添加小部件 → 选「零控」→ 拖到桌面。" +
                            "列表里有 4×2 和 5×2 两种：5 列的桌面选「零控 · 5×2」才铺满一行，" +
                            "其余用 4×2。放着就能看续航，五个按键点一下直接控车。",
                        color = C.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/**
 * 一张版式卡：上面是示意图，下面是名字。
 *
 * 示意图只画结构（标题行 / 续航 / 右侧车模 / 底部五格），不画文字 —— 在 56dp 高的框里
 * 任何文字都只会变成噪点，反而看不出几者的差别。
 *
 * 全透明那张用「棋盘格」当底：这是设计工具里表示透明的通用画法，
 * 比涂成纯色更能一眼看出「这一版没有卡片底」。格子里的字块与按键块直接用用户选的两个颜色，
 * 换色时缩略图跟着变，不用先应用到桌面才知道效果。
 */
@Composable
private fun WidgetStyleThumb(
    style: String,
    selected: Boolean,
    ink: Int,
    ctrl: Int,
    cellBg: Boolean,
    dividers: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val C = LocalAppColors.current
    val shape = RoundedCornerShape(14.dp)
    val immersive = style == WidgetStyle.STYLE_D
    val clear = style == WidgetStyle.STYLE_T
    val barInk = when {
        clear -> Color(ink)
        immersive -> Color.White
        else -> C.textPrimary
    }
    val barCtrl = when {
        clear -> Color(ctrl)
        immersive -> Color.White
        else -> C.accent
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(shape)
                .then(
                    when {
                        clear -> Modifier
                        immersive -> Modifier.background(
                            Brush.linearGradient(listOf(Color(0xFF35506B), Color(0xFF8FA9C4))),
                        )
                        else -> Modifier.background(C.panelBg)
                    },
                )
                .border(1.5.dp, if (selected) C.accent else C.border, shape)
                .clickable(onClick = onClick)
                .padding(6.dp),
        ) {
            if (clear) {
                CheckerBoard(Modifier.matchParentSize())
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Bar(13.dp, 3.dp, barInk.copy(alpha = 0.85f))
                            Spacer(Modifier.width(3.dp))
                            Bar(8.dp, 3.dp, barInk.copy(alpha = 0.45f))
                        }
                        Spacer(Modifier.height(4.dp))
                        Bar(26.dp, 9.dp, barInk.copy(alpha = 0.9f))
                        Spacer(Modifier.height(3.dp))
                        Bar(30.dp, 2.dp, barCtrl.copy(alpha = 0.75f))
                    }
                    Spacer(Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .weight(0.95f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    clear -> barInk.copy(alpha = 0.10f)
                                    immersive -> Color.White.copy(alpha = 0.16f)
                                    else -> Color.Transparent
                                },
                            ),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.height(9.dp)) {
                    repeat(5) { index ->
                        // 开关在这里要有即时反馈：桌面上还没放卡片时，
                        // 这张缩略图就是用户唯一能看见「关掉之后长什么样」的地方。
                        if (index > 0) Spacer(Modifier.width(if (dividers) 3.dp else 0.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (!cellBg) {
                                        Color.Transparent
                                    } else {
                                        when {
                                            // 透明主题的按键是一层淡玻璃，缩略图照这个比例示意
                                            clear -> barCtrl.copy(alpha = 0.22f)
                                            immersive -> Color.White.copy(alpha = 0.26f)
                                            else -> barInk.copy(alpha = 0.07f)
                                        }
                                    },
                                ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            WidgetStyle.displayName(style),
            color = if (selected) C.accent else C.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * 透明底纹：8dp 方格交替。
 *
 * 用**中性灰**而不是面板色：全透明版式的默认字色是白色，白字块压在白面板上
 * 等于没画 —— 而「全透明」现在是默认选中那一张，缩略图必须一眼看得出是透明底、
 * 字和按键浮在上面。两档灰在深浅色模式下都够淡，不抢缩略图里那些示意色块。
 */
@Composable
private fun CheckerBoard(modifier: Modifier = Modifier) {
    val C = LocalAppColors.current
    Canvas(modifier = modifier) {
        val cell = 8.dp.toPx()
        val light = if (C.isDark) Color(0xFF2C3037) else Color(0xFFE7E9ED)
        val dark = if (C.isDark) Color(0xFF353A42) else Color(0xFFD2D5DB)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = 0
            while (x < size.width) {
                drawRect(
                    color = if ((row + col) % 2 == 0) light else dark,
                    topLeft = Offset(x, y),
                    size = Size(cell, cell),
                )
                x += cell
                col++
            }
            y += cell
            row++
        }
    }
}

@Composable
private fun Bar(width: Dp, height: Dp, color: Color) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(color),
    )
}

/**
 * 两根细节滑杆的实时示意：**只让格子里的内容变，格子边框画出来固定不动**。
 *
 * 用户 2026-11-13 的要求是「支持微调大小，但不能影响排版显示效果」。真机上靠的是
 * 「容器尺寸不变 + fitCenter + 收放内边距」，光看数字看不出来，所以这里把 20dp 的
 * 刷新键格、五个按键格的边框都描出来：拖滑杆时只有里面的方块/文字在长，框一直不动。
 */
@Composable
private fun WidgetFinePreview(
    iconScale: Float,
    headerScale: Float,
    logoScale: Float,
    logoVisible: Boolean,
) {
    val C = LocalAppColors.current
    // 与真机同一份换算：刷新键图形 = 20dp 格子 − 2 × 内边距
    val refreshGraphic = WidgetMetrics.iconGraphicDp(
        WidgetMetrics.REFRESH_BOX_DP,
        WidgetMetrics.refreshPaddingDp(headerScale),
    )
    // 预览里的按键格只有 24dp 高，图标基准取 14dp（真机是 27dp / 38dp 格），
    // 比例一致即可 —— 这里交代的是「框不变、内容变」。
    val iconGraphic = (14f * iconScale).coerceIn(8f, 22f)
    // 车标：框恒 18dp，图形从同一份换算里拿（1.0 → 14dp）
    val logoGraphic = WidgetMetrics.logoGraphicDp(logoScale)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(C.panelBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // 标题行：左边车标（框 18dp 不动，图形按倍率长、左缘贴框），右边时间 + 刷新键。
        // 车标关掉时整格都不画，车名像真机一样往左顶到头 —— 示意图要能看出这一下位移。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (logoVisible) {
                Box(
                    modifier = Modifier
                        .size(WidgetMetrics.LOGO_BOX_DP.dp)
                        .border(0.6.dp, C.border, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .size(logoGraphic.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(C.accent.copy(alpha = 0.85f)),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = DEFAULT_VEHICLE_TITLE,
                color = C.textPrimary,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            // 右上角：文字跟着倍率，刷新键外框恒 20dp
            Text(
                text = "17:55 更新",
                color = C.textSecondary,
                fontSize = (10f * headerScale).sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .size(WidgetMetrics.REFRESH_BOX_DP.dp)
                    .border(0.6.dp, C.border, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(refreshGraphic.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(C.textSecondary.copy(alpha = 0.7f)),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 五格按键：边框是格子，永远不变；里面的方块按倍率缩放
        Row(modifier = Modifier.fillMaxWidth().height(24.dp)) {
            repeat(5) { index ->
                if (index > 0) Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(0.6.dp, C.border, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(iconGraphic.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(C.accent.copy(alpha = 0.75f)),
                    )
                }
            }
        }
    }
}

/**
 * 信息区示意：车名 / 更新时间 / 续航行按当前倍率画一遍。
 *
 * 为什么用示意而不是真实卡片：真实卡片只能由桌面进程用 RemoteViews 渲染，
 * Compose 里再实现一遍本来会跟实际有偏差 —— 但这里只需要交代**比例**，
 * 字号和条高都直接用同一份换算（[WidgetMetrics]），拖动时能立刻看出
 * 「字大了多少、条变粗了多少」。
 */
@Composable
private fun WidgetInfoPreview(infoScale: Float) {
    // 与桌面卡片同一份换算：9dp × 倍率（不再有单独的粗细设置）
    val barThick = WidgetMetrics.barThicknessDp(infoScale)
    val C = LocalAppColors.current
    val num = (13f * infoScale).sp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(C.panelBg)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DEFAULT_VEHICLE_TITLE,
                color = C.textPrimary,
                fontSize = num,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "10:24 更新",
                color = C.textSecondary,
                fontSize = (10f * infoScale).sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(5.dp))
        // 与真机同构：里程列定宽、列内「数字贴左 · km 贴右」、条吃剩余、百分比右对齐。
        // 两行的数字左端、km 右端、条的两端、百分号必须各自落在同一条竖线上 ——
        // 用户 2026-11-09 提「纯电和燃油一定要对齐」，2026-11-10 又补「数字部分两端对齐」。
        // 这里画两行就是为了让人拖滑杆时能当场看出对齐有没有散。
        // 真机上还多一个 15dp 的电池 / 油滴图标（这里省掉，不影响对齐判断）。
        PreviewRangeRow(
            value = "418km",
            pct = "95%",
            fraction = 0.95f,
            num = 13f * infoScale,
            barThick = barThick,
            barColor = C.accent,
            trackColor = C.barTrack,
            textColor = C.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        PreviewRangeRow(
            value = "126km",
            pct = "32%",
            fraction = 0.32f,
            num = 13f * infoScale,
            barThick = barThick,
            barColor = C.textSecondary,
            trackColor = C.barTrack,
            textColor = C.textPrimary,
        )
    }
}

/**
 * 预览里的一行续航：里程（「418km」连写、贴左）· 条（吃剩余）· 百分比（右对齐）。
 *
 * 与六份布局一一对应：里程列 `widthIn(min = VALUE_COLUMN_WIDTH_DP)` 且文字左对齐、
 * 百分比 `minWidth` = [WidgetMetrics.PCT_MIN_WIDTH_DP]、条 weight=1、里程与条之间 5dp。
 * 与零控首页 `MileageRow` 同一个口径：整行靠左，数字与单位连写，不拆格、不拉字距
 * （2026-11-10 用户否掉了「两端对齐」那版）。
 * 只要这里的比例对，真机上就是对的 —— 预览与真机用的是同一份常量。
 */
@Composable
private fun PreviewRangeRow(
    value: String,
    pct: String,
    fraction: Float,
    num: Float,
    barThick: Float,
    barColor: Color,
    trackColor: Color,
    textColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            color = textColor,
            fontSize = num.sp,
            maxLines = 1,
            textAlign = TextAlign.Start,
            modifier = Modifier.widthIn(min = WidgetMetrics.VALUE_COLUMN_WIDTH_DP.dp),
        )
        Spacer(Modifier.width(5.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(barThick.dp),
        ) {
            val radius = size.height / 2f
            drawRoundRect(
                color = trackColor,
                cornerRadius = CornerRadius(radius, radius),
            )
            drawRoundRect(
                color = barColor,
                size = Size(size.width * fraction, size.height),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
        Spacer(Modifier.width(3.dp))
        Text(
            text = pct,
            color = textColor,
            fontSize = num.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = WidgetMetrics.PCT_MIN_WIDTH_DP.dp),
        )
    }
}

/**
 * 一档滑杆：标题 + 当前值 + 说明。
 *
 * 拖动中只回调 [onChange]（更新界面并写 prefs），松手才 [onCommit]（整卡重画）——
 * 桌面小组件的一次**全量**重绘在沉浸样式下要重新解码用户的壁纸，
 * 跟着手指每帧重画会明显发涩，所以分成两步。
 *
 * `onValueChangeFinished` 不带参数：它只说明「松手了」，
 * 值已经通过 [onChange] 落进状态，这里用 `rememberUpdatedState` 防止拿到旧闭包。
 */
@Composable
private fun WidgetSliderRow(
    label: String,
    desc: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    val C = LocalAppColors.current
    val commit by rememberUpdatedState(onCommit)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = C.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(text = display, color = C.accent, fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = { commit() },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = C.accent,
                activeTrackColor = C.accent,
                inactiveTrackColor = C.barTrack,
            ),
        )
        Text(text = desc, color = C.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

/** 把相册里那张图按最长边 [maxSize] 采样解码 —— 原图动辄 4000px，整个读进来会直接 OOM。 */
private fun decodeScaled(context: Context, uri: Uri, maxSize: Int): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
        sample *= 2
    }
    resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}.getOrNull()
