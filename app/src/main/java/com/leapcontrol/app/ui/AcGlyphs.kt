package com.leapcontrol.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leapcontrol.app.R

/**
 * 空调页功能图标：**整套从网络图标库抓取，不再手绘**（2026-11-07 用户定稿）。
 *
 * 之前那版是 `Canvas` 里逐条线手画的，十几个图标的线宽、圆角、留白靠肉眼对齐，
 * 摆在 4 连格 / 2×2 舒适格里始终有点不齐；用户明确要求「画好看点、风格统一、
 * 建议不要自己画，而是去网络上抓取」。
 *
 * ## 来源与授权
 * MingCute Icon（MingCute Design）· **Apache-2.0**
 * - 项目：https://github.com/Richard9394/MingCute
 * - 抓取入口：`https://api.iconify.design/mingcute/<name>.svg`（Iconify CDN）
 * - 每个 `res/drawable/ic_ac_*.xml` 的注释里写了它对应的原始 URL。
 *
 * 整套都是 **24×24 视口、单条 path、同一套圆角线宽**，并且覆盖了车机特有的
 * 「前挡除霜 / 座椅加热 / 方向盘 / 后视镜」这些语义 —— Material Icons 凑不齐这些，
 * 硬凑会让四个不同功能共用同一个太阳图标（旧版踩过）。
 *
 * ## 转换方式
 * SVG 的 `pathData` **原样**搬进 Android VectorDrawable，只把 `fill="currentColor"`
 * 换成 `android:fillColor="#FF000000"`；真正的颜色由 [AcIcon] 的 `tint` 用
 * `ColorFilter.tint` 注入，因此选中态 / 禁用态 / 深浅色主题都能共用同一份资源。
 * 没有二次描边、没有改路径，也不会出现「抓来的图被染成一整块」。
 *
 * ## 唯一的例外：后视镜加热
 * [AcGlyph.Mirror] 用的是用户 2026-11-08 给的 PNG（后视镜 + 三道向上热浪），
 * 不用 MingCute 的 `mirror-line` —— 那个造型是「镜面里一条竖线」，放大了才看得出是后视镜，
 * 和旁边的方向盘加热摆在一起语义不够直白。原图 200×200 透明底黑线，
 * 这里按 alpha 边界裁掉四周空白、居中做成 192×192（`drawable-nodpi`），
 * 深色 / 浅色主题照旧由 [AcIcon] 的 tint 统一上色。
 */
enum class AcGlyph(@DrawableRes val iconRes: Int) {
    /** 雪花 —— 制冷 / 极速降温（mingcute:snow-line）。 */
    Snowflake(R.drawable.ic_ac_snowflake),

    /** 太阳 —— 制热 / 一键制热（mingcute:sun-line）。 */
    Sun(R.drawable.ic_ac_sun),

    /** 四叶风扇 —— 风量档位（mingcute:fan-line）。 */
    Airflow(R.drawable.ic_ac_airflow),

    /** 前挡风玻璃 + 热浪 —— 前挡除霜 / 前风挡出风（mingcute:front-windshield-defroster-line）。 */
    Defrost(R.drawable.ic_ac_defrost),

    /** 环形箭头 —— 内外循环 / 刷新（mingcute:refresh-1-line）。 */
    Circulation(R.drawable.ic_ac_circulation),

    /** 星芒 —— 快速除味 / 空气净化（mingcute:sparkles-line）。 */
    Fresh(R.drawable.ic_ac_fresh),

    /** 时钟 —— 定时空调（mingcute:clock-2-line）。 */
    Timer(R.drawable.ic_ac_timer),

    /** 座椅 + 热浪 —— 座椅加热（mingcute:seat-heated-line）。 */
    SeatHeat(R.drawable.ic_ac_seat_heat),

    /** 座椅 —— 座椅通风（mingcute:seat-line）。 */
    SeatVent(R.drawable.ic_ac_seat_vent),

    /** 方向盘（mingcute:steering-wheel-line）。 */
    Steering(R.drawable.ic_ac_steering),

    /** 后视镜 + 三道热浪 —— 后视镜加热（2026-11-08 用户提供 PNG，见下方说明）。 */
    Mirror(R.drawable.ic_ac_mirror_heat),

    /** 电源符号（mingcute:power-line）。 */
    Power(R.drawable.ic_ac_power),

    /** 风线 —— 全车出风（mingcute:wind-line）。 */
    Wind(R.drawable.ic_ac_wind),

    /** 向左折线 —— 返回（mingcute:left-line）。 */
    ChevronLeft(R.drawable.ic_ac_chevron_left),

    /** 向右折线 —— 「进入二级页」的提示（mingcute:right-line）。 */
    ChevronRight(R.drawable.ic_ac_chevron_right),
}

/**
 * 画一个空调图标：整张图来自 drawable 资源，按 [size] 等比缩放、按 [tint] 上色。
 *
 * 旧的 `stroke` 参数已随之删除：矢量资源自带统一线宽，运行时不再需要（也不该）
 * 给每个调用点各传一个线宽，那正是上一版风格对不齐的根源。
 */
@Composable
fun AcIcon(
    glyph: AcGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Image(
        painter = painterResource(glyph.iconRes),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}
