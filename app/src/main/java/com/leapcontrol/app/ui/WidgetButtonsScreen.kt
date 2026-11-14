package com.leapcontrol.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.widget.VehicleWidget
import com.leapcontrol.app.widget.WidgetButtonId
import com.leapcontrol.app.widget.WidgetButtonLayout

/**
 * 「桌面小组件 → 控车按钮」：替换动作 + 调整 5 个槽位的顺序。
 *
 * 整页覆盖在「个性化」里（入口在 [WidgetSettingsGroup]）。布局 id / 版式 / 尺寸都不动，
 * 改的只是「哪个槽位绑哪个动作」这一份配置，见 [WidgetButtonLayout]。
 *
 * 本页被挂在一根 `verticalScroll` Column 里，所以**不** `fillMaxSize`、不自带滚动、
 * 不重复 `statusBarsPadding`，只按内容铺开，跟着父容器一起滚。
 */
@Composable
fun WidgetButtonsScreen(onBack: () -> Unit) {
    val C = LocalAppColors.current
    val context = LocalContext.current.applicationContext
    // prefs 不是可观察对象：本地 state 驱动重组，改完顺手回读一次与磁盘对齐
    var buttons by remember { mutableStateOf(WidgetButtonLayout.load(context)) }
    var replaceIndex by remember { mutableStateOf<Int?>(null) }

    // 二级页必须拦返回：弹层开着先收弹层，否则退回设置
    BackHandler(enabled = true) {
        if (replaceIndex != null) replaceIndex = null else onBack()
    }

    fun commit(next: List<WidgetButtonId>) {
        WidgetButtonLayout.save(context, next)
        buttons = WidgetButtonLayout.load(context)
        // 点击区 / 图标属于静态部分，增量更新改不了 —— 配置变更必须全量重画
        VehicleWidget.render(context)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        SettingsTopBar(title = "控车按钮", onBack = onBack)
        Spacer(Modifier.height(10.dp))
        Text(
            "桌面小组件上的 5 个按钮：点一行可以换成车窗、电池预热、遮阳帘、哨兵模式等动作，用右侧箭头调整顺序。",
            color = C.textSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(14.dp))

        buttons.forEachIndexed { index, id ->
            ButtonRow(
                index = index,
                total = buttons.size,
                id = id,
                onMoveUp = { commit(WidgetButtonLayout.move(buttons, index, index - 1)) },
                onMoveDown = { commit(WidgetButtonLayout.move(buttons, index, index + 1)) },
                onReplace = { replaceIndex = index },
            )
            if (index != buttons.lastIndex) Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "恢复默认",
            color = C.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { commit(WidgetButtonLayout.DEFAULT) }
                .padding(horizontal = 2.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "改动会立刻同步到桌面上已有的卡片，不用删掉重加。",
            color = C.textSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(24.dp))
    }

    // 整行点击后弹的替换弹层：全部动作都列出来（遍历枚举），已占用的动作走「互换」而非复制
    val idx = replaceIndex
    if (idx != null) {
        AlertDialog(
            onDismissRequest = { replaceIndex = null },
            containerColor = C.dialogBg,
            title = { Text("替换第 ${idx + 1} 个按钮", color = C.textPrimary) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "选一个动作放进来。已经占了别的格子的动作会与当前按钮互换，不会出现两个相同的按钮。",
                        color = C.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    WidgetButtonId.entries.forEach { option ->
                        val usedAt = buttons.indexOf(option)
                        val desc = when {
                            usedAt == idx -> "当前这一格"
                            usedAt >= 0 -> "已用于第 ${usedAt + 1} 格 · 点此互换"
                            else -> "未使用"
                        }
                        SettingOptionRow(
                            label = option.title,
                            desc = desc,
                            selected = usedAt == idx,
                        ) {
                            if (usedAt != idx) {
                                val next = buttons.toMutableList()
                                val old = next[idx]
                                // 目标动作已在别的格子 → 把旧按钮挪过去，保持 5 项互不相同
                                if (usedAt >= 0) next[usedAt] = old
                                next[idx] = option
                                commit(next)
                            }
                            replaceIndex = null
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { replaceIndex = null }) {
                    Text("关闭", color = C.textSecondary)
                }
            },
        )
    }
}

/** 一格：序号 + 图标 + 名称 + 上移 / 下移。整行点击打开替换弹层。 */
@Composable
private fun ButtonRow(
    index: Int,
    total: Int,
    id: WidgetButtonId,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onReplace: () -> Unit,
) {
    val C = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(C.panelBg) // 列表卡：8dp 圆角 + panelBg，无描边
            .clickable(onClick = onReplace)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1}",
            color = C.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            painter = painterResource(id.iconRes),
            contentDescription = null,
            tint = C.textPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            id.title,
            color = C.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMoveUp, enabled = index > 0) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "上移",
                tint = if (index > 0) C.textPrimary else C.barTrack,
            )
        }
        IconButton(onClick = onMoveDown, enabled = index < total - 1) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "下移",
                tint = if (index < total - 1) C.textPrimary else C.barTrack,
            )
        }
    }
}
