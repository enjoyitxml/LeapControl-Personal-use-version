package com.leapcontrol.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 窄屏 / 大字体下的「一行文字放不下」兜底。
 *
 * ## 为什么需要这一层
 * 零控里大量文字是 `maxLines = 1` + `softWrap = false`（里程数字、状态结论、胶囊标题…）。
 * 这种写法在宽屏上最整齐，但一旦可用宽度不够，Compose **不会折行也不会报错**，
 * 它会照宽画出去、把超出的部分裁掉 —— 用户看到的就是「7%」变成「7」、「剩 3 小时 5 分钟」
 * 被切掉尾巴（2026-11-08 用户反馈的截图就是这个）。
 *
 * 折行会把定高卡片撑高、把整页版式挤塌；省略号又会丢信息。对这种「必须单行、而且字越少越好读」
 * 的数值型文本，最合适的是**按宽度把字号收一档**：40sp 的续航大字收到 34sp 依然醒目，
 * 但不会压到旁边的状态文字。
 *
 * ## 实现
 * 宽度与字号是严格线性的，所以一次测量 + 一次比例就能算出精确值，不需要反复试排
 * （同一套算法已经在轮胎卡上跑了半年，见 [fittedValueFontSp]）。
 *
 * @param measuredWidthPx 基准字号下的渲染宽度（px）
 * @param availableWidthPx 实际可用宽度（px）
 * @param baseSp 期望字号
 * @param minSp 下限：再挤也不小于它，否则字小到看不清，不如让它省略
 */
internal fun fittedFontSp(
    measuredWidthPx: Int,
    availableWidthPx: Int,
    baseSp: Float,
    minSp: Float,
): Float = when {
    measuredWidthPx <= 0 || availableWidthPx <= 0 -> baseSp
    measuredWidthPx <= availableWidthPx -> baseSp
    else -> (baseSp * availableWidthPx.toFloat() / measuredWidthPx).coerceAtLeast(minSp)
}

/**
 * 单行文本，按可用宽度自动缩字号。
 *
 * 放得下时与普通 [Text] 完全一致（字号、字重、颜色都不变），放不下才缩，最小到
 * [minFontSize]。宽度由父布局决定，所以调用方**不要**再套一层 `width(定宽)`。
 */
@Composable
internal fun AutoFitText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    minFontSize: TextUnit = fontSize * 0.72f,
    textAlign: TextAlign? = null,
) {
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = modifier) {
        val available = constraints.maxWidth
        val sizeSp = remember(text, fontScale, available, fontSize, fontWeight) {
            val measured = runCatching {
                measurer.measure(
                    text = text,
                    style = baseStyle.copy(
                        fontSize = fontSize,
                        fontWeight = fontWeight ?: FontWeight.Normal,
                    ),
                    maxLines = 1,
                    softWrap = false,
                ).size.width
            }.getOrDefault(0)
            fittedFontSp(
                measuredWidthPx = measured,
                availableWidthPx = available,
                baseSp = fontSize.value,
                minSp = minFontSize.value,
            )
        }
        Text(
            text = text,
            color = color,
            fontSize = sizeSp.sp,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            textAlign = textAlign,
        )
    }
}
