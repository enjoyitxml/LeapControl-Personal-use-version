package com.leapcontrol.app.widget

import kotlin.math.roundToInt

/**
 * 小组件「信息区」的尺寸换算（纯逻辑，单测钉住）。
 *
 * 为什么单独拎出来：字号与进度条粗细最后都要落成 RemoteViews 的数值
 * （dp 字号、px 内边距）。这两步换算错一格，在真机上就是「文字顶出里程槽」
 * 或「条贴着上下边框」，而它们在单测里能算得很死，不必每次都装到手机上看。
 *
 * 2026-11-09 起**只剩一个用户旋钮**（设置页「信息区大小」）：字号和条粗细都从它推出来。
 * 上一版把「进度条粗细」单开一根滑杆，结果是同一块区域有两套互不相干的倍率，
 * 用户调完看到的是「字大了、条没变」，反而更乱。
 */
internal object WidgetMetrics {

    /**
     * 进度条「槽」在布局里占的高度（dp）。
     *
     * 比默认粗细（9dp）多留 3dp：要能往上调粗，槽位就必须先有余量。
     * 实际粗细由 [barPaddingPx] 换算出的上下内边距收回 —— 注意**内边距加在槽上，
     * 不是加在 ProgressBar 上**：ProgressBar 把 drawable 收成
     * `高度 - paddingTop - paddingBottom` 之后是从内容区**顶部**开始画的，
     * 给它自己加对称内边距只会让条往上缩、顶边不动（2026-11-09 用户截图：
     * 「条比文字高一截」）。加在定高的槽上，条作为 match_parent 子视图跟着变矮，
     * 槽仍由行的 `gravity="center_vertical"` 居中，粗细 / 字号怎么调都在中线上。
     */
    const val BAR_SLOT_DP = 12f

    /**
     * 默认粗细（dp）。9dp 是 2026-11-09 用户点名要的默认值 ——
     * 7dp 在 3x 屏上只有 21px，缩略图里几乎看不出填充比例。
     *
     * 用户调「信息区大小」时它跟着一起乘：整块区域是同一个倍率，
     * 不会出现「字变大了、条还是原来那么细」。
     */
    const val BAR_BASE_DP = 9f

    /** 再细就该看不见了；再粗则会顶满 [BAR_SLOT_DP]，所以两头都夹住。 */
    const val MIN_BAR_DP = 3f

    /** 文字倍率的下限：再小在卡片上就只剩一坨灰点。 */
    const val MIN_SCALE = 0.7f

    /** 标题与时间的上限：这两处是 ellipsize / maxWidth 兜着，可以比数字宽松一点。 */
    const val TEXT_MAX_SCALE = 1.45f

    /**
     * 续航数字与百分比的上限：只是「别无限放大」的粗上限，
     * 真正决定字号的是 [numberSizeScale] 拿系统字体实测出来的宽度。
     */
    const val NUM_MAX_SCALE = 1.32f

    /**
     * 里程列的最小宽度（dp）：**必须与布局里 `wg_ev_value` / `wg_fuel_value` 的
     * `android:minWidth` 一模一样**（[com.leapcontrol.app.WidgetNumberSizeTest] 钉住）。
     *
     * 2026-11-10 用户拿首页对比后定了口径：「按照零控首页的效果来，续航靠左对齐」。
     * 也就是说这一列就是一个文字块（「195km」数字与单位连写、贴左起排），
     * 和首页 `MileageRow` 的 48dp 里程槽一个意思 —— 给足最小宽度，两行的起点才对齐，
     * 后面的进度条才一样长；但字本身保持自然间距，不拆格、不拉字距
     * （拆两格拉字距那版被用户判为「效果太差」）。
     *
     * 为什么是 minWidth 而不是定宽：字一放大（信息区大小拉满 / 大卡）定宽就会切字，
     * `wrap_content + minWidth` 是「装得下就对齐，真装不下就撑开让条让位」，
     * 永远不会切掉 km。
     *
     * 46dp：「195km」在 13dp 下实测约 41dp（MiSans 比 Roboto 宽），留 5dp 余量。
     */
    const val VALUE_COLUMN_WIDTH_DP = 46f

    /** 宽版（5×2）信息区更宽，里程列给到 52dp，同一句话能放得更大。 */
    const val VALUE_COLUMN_WIDTH_WIDE_DP = 52f

    /**
     * 百分比列的最小宽度（dp），同样与布局里的 `android:minWidth` 一致。
     *
     * 要按最宽的「100%」算（13dp 下约 34dp），否则一旦满电满油，两行的百分号就会
     * 一前一后错开 —— 用户 2026-11-09 明确要求「百分比的百分号一定要对齐」。
     */
    const val PCT_MIN_WIDTH_DP = 34f

    /**
     * 最终用于落字的倍率。
     *
     * @param cardScale 卡片实际尺寸带来的系数（见 `VehicleWidget.textScale`，0.82~1.28）
     * @param infoScale 用户在设置页调的整体大小（0.8~1.3）
     */
    fun textScale(cardScale: Float, infoScale: Float): Float =
        (cardScale * infoScale).coerceIn(MIN_SCALE, TEXT_MAX_SCALE)

    /**
     * 进度条粗细（dp）= 默认 9dp × 用户倍率。
     *
     * 卡片尺寸不参与：图标、按键都不随卡片缩放，条也不必单独跟一份系数，
     * 否则同一张卡上「字按用户倍率、条按卡片倍率」，两者会越差越远。
     */
    fun barThicknessDp(infoScale: Float): Float =
        (BAR_BASE_DP * infoScale).coerceIn(MIN_BAR_DP, BAR_SLOT_DP)

    /**
     * 槽的上下内边距（px）。条的粗细 = [BAR_SLOT_DP] - 2×该值。
     *
     * @param thicknessDp 实际粗细，越界值夹回可用范围
     * @param density 屏幕密度，dp → px
     */
    fun barPaddingPx(thicknessDp: Float, density: Float): Int {
        val thick = thicknessDp.coerceIn(MIN_BAR_DP, BAR_SLOT_DP)
        val padDp = ((BAR_SLOT_DP - thick) / 2f).coerceAtLeast(0f)
        return (padDp * density).roundToInt().coerceAtLeast(0)
    }

    /**
     * 续航数字的最终字号倍率：先夹上限，再按**系统字体实测宽度**收。
     *
     * 为什么不能只靠常量：同一个字号在不同 ROM 上宽度差得很远 ——
     * 同一串「145km」，Roboto 约 43dp、MIUI 的 MiSans 能到 50dp 以上。
     * 只按「0.56em/字符」估算、把宽度按某个字体定死，换个手机就切掉一个字母
     * （2026-11-09 用户截图正是这种情况：单位 m 整个没了）。
     *
     * 所以让调用方把「这段文字在某个字号下有多宽」量出来（[measureDp]），
     * 超过允许宽度就等比缩回去 —— 只有真的放不下才缩，默认倍率不会被误伤。
     *
     * 里程列有个预算宽度（[VALUE_COLUMN_WIDTH_DP]）：超过它就把字号等比收回来，
     * 让进度条还有地方，也让「两行的条等长」这条不因为位数变化被破坏。
     *
     * @param merged 卡片系数 × 用户系数（[textScale] 的结果）
     * @param baseDp 基准字号（dp），与布局里的 textSize 一致
     * @param limitDp 里程列宽度（dp），传 [VALUE_COLUMN_WIDTH_DP] / [VALUE_COLUMN_WIDTH_WIDE_DP]
     * @param measureDp 给定字号（dp）返回文字宽度（dp）
     */
    // ---- 细节微调：图标与右上角那一组（2026-11-13）----
    //
    // 口径只有一条：**容器尺寸一个字节都不改**。RemoteViews 在 minSdk 26 上改不了
    // LayoutParams（setViewLayoutWidth / Height 是 API 31），而容器本身就写着
    // match_parent + fitCenter —— 所以缩放只能是「加大 / 收小 ImageView 的四边内边距」。
    // 这样格子边界、五格 weight 均分、分隔线位置、点击热区全都原样不动。

    /** 按键行高（dp），与六份布局里 `wg_act_row` 的 `layout_height` 一致。 */
    const val CELL_HEIGHT_DP = 38f
    const val CELL_HEIGHT_WIDE_DP = 42f

    /** 右上角刷新键的格子与默认内边距（六份布局一致）：图形 15dp。 */
    const val REFRESH_BOX_DP = 20f
    const val REFRESH_PAD_DP = 2.5f

    /** 更新时间字号的绝对上限：状态行是浮层，再大会压到车模与标题区。 */
    const val TIME_MAX_DP = 16f

    /**
     * 左上角车标的格子（六份布局里 `wg_logo` 的定宽高）。
     *
     * 2026-11-13 由 14dp 扩到 18dp：车标原来「格子 = 图形 = 14dp」，四边一点余量都没有，
     * 想放大只能改 LayoutParams —— 而那是 API 31 的方法，minSdk 26 上无效。
     * 扩到 18dp 后按 [logoPad] 收放四边留白，**图形在 1.0 时仍是 14dp**。
     *
     * 18dp 也是放大上限的来源：14 × 1.25 = 17.5dp，刚好还在格子里。
     */
    const val LOGO_BOX_DP = 18f

    /** 车标在倍率 1.0 时的图形高度（旧版 14dp，零回归的基准）。 */
    const val LOGO_BASE_DP = 14f

    /** 车标四边留白（dp）。 */
    data class LogoPad(
        val leftDp: Float,
        val topDp: Float,
        val rightDp: Float,
        val bottomDp: Float,
    )

    /**
     * 车标按倍率缩放时的四边留白。
     *
     * 与按键图标同一套「容器内缩放」：格子恒为 [LOGO_BOX_DP]，只收放留白。
     * 区别只有一条 —— **左边恒 0**：车标是整行文字的起点，用户拖滑杆时它必须
     * 贴在原来的左基线上长大 / 缩小，不能像按键那样往两边对称长（那样一放大
     * 车名就被推走，缩小时车标又会跟卡片左缘错开）。
     *
     * 于是右边吃掉全部横向余量、上下各分一半：内容区正好是 `图形 × 图形`，
     * `fitCenter` 画出来的高度就是目标值，且在格子里垂直居中。
     *
     * @param scale 用户倍率（0.8~1.25，越界值夹回格子里）
     */
    fun logoPad(scale: Float): LogoPad {
        val graphic = (LOGO_BASE_DP * scale).coerceIn(0f, LOGO_BOX_DP)
        val side = ((LOGO_BOX_DP - graphic) / 2f).coerceAtLeast(0f)
        return LogoPad(
            leftDp = 0f,
            topDp = side,
            rightDp = (LOGO_BOX_DP - graphic).coerceAtLeast(0f),
            bottomDp = side,
        )
    }

    /** 车标最终画出来的高度（dp）：单测断言「永远不越过 18dp 格子」。 */
    fun logoGraphicDp(scale: Float): Float {
        val pad = logoPad(scale)
        return (LOGO_BOX_DP - pad.topDp - pad.bottomDp).coerceIn(0f, LOGO_BOX_DP)
    }

    /**
     * 按倍率缩放一个 `match_parent + fitCenter` 图标时，四边该留多少内边距（dp）。
     *
     * 反着推：容器里真正画出来的图形 = `cellDp - 2 × 基准留白`，
     * 把它乘上 [scale] 得到目标图形，再用 `(cellDp - 目标) / 2` 反算留白。
     * 于是 **scale = 1.0 时结果严格等于 [basePadDp]**（老卡片零回归），
     * 而放大时留白变小、缩小时留白变大，图形始终待在容器里。
     *
     * 两头都夹住：留白不许为负（fitCenter 会自己缩，但负 padding 在部分 ROM 上
     * 会让 drawable 画到格子外），也不许超过半个容器（图形不能缩成 0）。
     *
     * @param basePadDp 该动作在 scale=1 时的留白（空调 8、鸣笛 4、其余 5.5）
     * @param cellDp 按键格高度，4×2 用 [CELL_HEIGHT_DP]、5×2 用 [CELL_HEIGHT_WIDE_DP]
     */
    fun iconPaddingDp(basePadDp: Float, cellDp: Float, scale: Float): Float {
        val graphic = (cellDp - 2f * basePadDp).coerceIn(0f, cellDp)
        return ((cellDp - graphic * scale) / 2f).coerceIn(0f, cellDp / 2f)
    }

    /** 算完留白后图形的实际尺寸（dp）：单测用它断言「永远不越过容器」。 */
    fun iconGraphicDp(cellDp: Float, padDp: Float): Float =
        (cellDp - 2f * padDp).coerceIn(0f, cellDp)

    /**
     * 鸣笛喇叭那个「上移量」也跟着倍率走，但**不能超过留白** ——
     * 放大后留白可能只剩 0.25dp，还按 1.5dp 顶上去会让上边为负、图形偏心。
     */
    fun liftDp(baseLiftDp: Float, scale: Float, padDp: Float): Float =
        (baseLiftDp * scale).coerceIn(0f, padDp.coerceAtLeast(0f))

    /** 刷新键的留白：同一个「容器内缩放」公式，格子恒为 [REFRESH_BOX_DP]。 */
    fun refreshPaddingDp(scale: Float): Float =
        iconPaddingDp(REFRESH_PAD_DP, REFRESH_BOX_DP, scale)

    /**
     * 右上角更新时间最终字号（dp）。
     *
     * 仍然吃「卡片系数 × 信息区大小」（[textScale] 的结果），再乘细节倍率 ——
     * 老用户调过信息区大小的话，这一处照样跟着变，新滑杆只是在它上面再微调。
     * 绝对上限 [TIME_MAX_DP]：状态行有 `maxWidth=118dp + ellipsize=end + maxLines=1`
     * 兜着不会挤走刷新键，但字太高会把右上角那块胶囊撑得压住车模。
     */
    fun timeSizeDp(baseDp: Float, mergedScale: Float, headerScale: Float): Float =
        (baseDp * mergedScale * headerScale).coerceIn(6f, TIME_MAX_DP)

    fun numberSizeScale(
        merged: Float,
        baseDp: Float,
        limitDp: Float,
        measureDp: (Float) -> Float,
    ): Float {
        val wanted = merged.coerceIn(MIN_SCALE, NUM_MAX_SCALE)
        val widthDp = measureDp(baseDp * wanted)
        // 量不出来（字体还没就绪 / 返回 0）时按上限走，别把字缩成 0
        if (widthDp <= 0f || widthDp <= limitDp) return wanted
        return wanted * limitDp / widthDp
    }
}
