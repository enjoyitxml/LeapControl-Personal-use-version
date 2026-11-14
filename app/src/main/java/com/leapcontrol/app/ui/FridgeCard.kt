package com.leapcontrol.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.FridgeMode
import com.leapcontrol.app.R
import com.leapcontrol.app.ui.theme.statusWarn
import com.leapcontrol.app.ui.theme.statusWarnDark

/**
 * 首页整行卡「车载冰箱」—— **只做摘要，功能和设置全部在二级页 [FridgeDetailScreen]**。
 *
 * 版式（一行，和空调概览条 / 自上次充电里程卡同一套 8dp 圆角 + panelBg + 无描边）：
 *   - 左：冰箱圆图标
 *   - 中：标题「车载冰箱」+ 一行状态（[FridgeCardRules.summaryText]，取自**车况**而非草稿）
 *   - 右：电源状态点 + `›`（提示这里能点进去）
 *
 * 为什么改成摘要卡：2026-11-08 用户反馈「冰箱卡片太大了，还是做成二级界面吧，卡片做成和
 * 其他卡片一样的大小」。之前那张卡把上游 cmdid=500 的七类功能全铺在首页，高度是别的卡的
 * 好几倍，把首页拖得很长。现在首页只留「开没开、现在什么状态」，点开二级页再调。
 *
 * ## 三条仍然有效的约束
 * - **不做车辆能力判断**：不读 [com.leapcontrol.app.VehicleCapabilities]、不因
 *   [FridgeCardState.hasSignal] 为 false 而隐藏卡片。有没有这个硬件由用户自己决定加不加。
 * - **默认收起**：不进 [com.leapcontrol.app.HomeCardLayout.DEFAULT]，也不登记补入，
 *   只出现在编辑态「添加卡片」里。
 * - **卡片里不放任何下发入口**：开关与全部设置都在二级页，避免出现两个开冰箱的入口。
 */
@Composable
fun FridgeCard(
    state: FridgeCardState,
    /** 点整张卡：打开二级页 [FridgeDetailScreen]。 */
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val C = LocalAppColors.current
    val on = state.enabled == true
    val warm = if (C.isDark) statusWarnDark else statusWarn
    // 状态行配色和二级页一致：暖饮用暖橙、制冷用主题绿、关着 / 没信号用次要灰
    val statusTint = when {
        !state.hasSignal || !on -> C.textSecondary
        state.mode == FridgeMode.HOT -> warm
        else -> C.accent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CARD_MIN_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            // 首页整行卡统一 8dp + panelBg + 无描边（周能耗 / 空调 / 位置 / 相伴同一套）
            .background(C.panelBg, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(C.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_mod_fridge),
                contentDescription = null,
                colorFilter = ColorFilter.tint(C.accent),
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "车载冰箱",
                    color = C.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                // 电源状态点：一眼看出开没开。真正下发在二级页，这里只是个指示
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (on) C.accent else C.textSecondary.copy(alpha = 0.32f)
                        ),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = FridgeCardRules.summaryText(state),
                color = statusTint,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        // 和空调条同一个 `›`：告诉用户「点这里还有一页」
        AcIcon(
            glyph = AcGlyph.ChevronRight,
            tint = C.textSecondary.copy(alpha = 0.7f),
            size = 17.dp,
        )
    }
}

/** 卡片最小高度：略低于空调概览条的 74dp，与「自上次充电里程」这类两行卡同一量级。 */
private val CARD_MIN_HEIGHT = 68.dp
