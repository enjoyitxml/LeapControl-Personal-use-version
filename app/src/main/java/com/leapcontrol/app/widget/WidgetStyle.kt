package com.leapcontrol.app.widget

import android.content.Context
import android.content.res.Configuration

/**
 * 桌面小组件的外观偏好：**版式** + **背景** + **透明主题的字色 / 控件色**。
 *
 * ## 三种版式（用户在「个性化 → 桌面小组件」里选）
 * - [STYLE_A] 素纸：浅色卡 + 发丝分隔线，最像系统自带小组件；
 * - [STYLE_D] 沉浸：图上铺字，下半部分压暗，按键是半透明白玻璃；
 * - [STYLE_T] 全透明：没有卡片底，字和按键直接浮在壁纸上，字色与控件色可自己调。
 *   **默认就是它**（2026-11-16 用户定稿，见 [DEFAULT_STYLE]）。
 *
 * 三个布局是三份独立的 RemoteViews XML，不是同一份布局加开关：
 * 标准小组件只能组合基础控件，靠 visibility 切换做不出几种完全不同的结构。
 *
 * 早先还有过一版「色块」（STYLE_F）：车模坐在主题绿底板上、五格带文字。
 * 用户否决后已删除，布局与 drawable 一并移除；老用户 prefs 里残留的 "f"
 * 由 [style] 兜底折回 [STYLE_A]，不需要单独迁移。
 *
 * ## 背景（只对 [STYLE_D] 有意义）
 * 没选自定义图时用 `widget_d_fallback` 那段黄昏渐变；选了就换成用户的照片，
 * 见 [WidgetWallpaper]。照片走 URI 交给桌面进程解码，不受 Binder 1MB 限制。
 *
 * ## 透明主题的颜色
 * 壁纸深浅不可控，所以字色与控件色都交给用户。两色各自独立，默认都是白色。
 * 派生规则只有一条：**按颜色本身的亮度选亮版 / 暗版素材**（[isLight]）——
 * 浅色字配深玻璃底、深色字配浅玻璃底，否则浅壁纸上白字、深壁纸上黑字都会糊掉。
 * 进度条与按键底是 drawable 资源，运行时换不了颜色，所以各准备两份由这里挑。
 */
object WidgetStyle {

    const val STYLE_A = "a"
    const val STYLE_D = "d"
    const val STYLE_T = "t"

    val ALL = listOf(STYLE_A, STYLE_D, STYLE_T)

    /**
     * 默认版式：**全透明**（2026-11-16 用户定稿）。
     *
     * 素纸与沉浸都自带一层卡片底，而用户要的是「字和按键直接浮在壁纸上」。
     * 配套的两条默认值一起改：按键默认不带底色（[cellBgVisible]）、分隔线仍默认显示
     * —— 全透明版式里那四条细线是唯一还看得出「这是五个按键」的边界。
     *
     * 三套版式在设置页里的排列顺序（[ALL]）不动：顺序是「素纸 → 沉浸 → 全透明」，
     * 默认选中第三张，用户扫一眼就知道自己现在用的是哪套。
     */
    const val DEFAULT_STYLE = STYLE_T

    const val WALLPAPER_DEFAULT = "default"
    const val WALLPAPER_CUSTOM = "custom"

    /**
     * 透明主题的默认字色与默认控件色：**都是白色**（2026-11-16 用户定稿）。
     *
     * 全透明版式默认浮在任何壁纸上，白色 + 文字阴影是最稳的一套；
     * 控件色（按键图标常态 / 分隔线 / 提示高亮）跟着也用白，整张卡只有一种颜色，
     * 干净、也不用先想「绿配深壁纸还是浅壁纸」。
     *
     * 代价要知道：按键生效时的高亮也是白的，跟常态同一色 —— 于是「哪个键正开着」
     * 不再靠图标变色区分，而是看右上角那句提示（「车辆已解锁 / 空调已开启」）。
     * 想要绿高亮的人自己去「个性化 → 桌面小组件 → 颜色与背景 → 控件颜色」换。
     */
    const val DEFAULT_INK = 0xFFFFFFFF.toInt()
    const val DEFAULT_CTRL = 0xFFFFFFFF.toInt()

    private const val PREFS = "widget_style"
    private const val KEY_STYLE = "style"
    private const val KEY_WALLPAPER = "wallpaper"
    private const val KEY_REVISION = "revision"
    private const val KEY_INK = "t_ink"
    private const val KEY_CTRL = "t_ctrl"
    /**
     * 老版本存过的「状态提示高亮色」。
     *
     * 2026-11-20 起右上角提示与刷新键固定跟「字体颜色」走，这条设置不再有消费者，
     * 设置页入口已撤。键名留着只为 [resetColors] 能把老机器上残留的值清掉 ——
     * 别再把它挂回界面或渲染路径。
     */
    private const val KEY_HIGHLIGHT = "highlight"
    private const val KEY_CELL_BG = "cell_bg"
    private const val KEY_DIVIDER = "divider"
    /** 左上角车标是否显示（2026-11-16）。 */
    private const val KEY_LOGO_VISIBLE = "logo_visible"
    /**
     * 「默认版式改成全透明、按键默认不带底色、车标默认不显示」的一次性迁移标记。
     *
     * 光改默认常量对老设备无效：装过旧版的机器 prefs 里已经写着 `style="a"`、
     * `cell_bg=true`，升级后卡片依旧是素纸 + 带底的按键，看起来就像这次改动没做进去。
     * 所以第一次读外观设置时强制写成新默认一次并落标记，之后完全尊重用户自己的选择
     * （用户手动改回素纸 / 打开按键底 / 打开车标，就再也不动它）。与首页
     * 「蓝牙钥匙图标默认隐藏」是同一套做法（见 `AppRuntime` 里的 `KEY_BLE_ICON_MIGRATED`）。
     */
    private const val KEY_DEFAULTS_V2 = "defaults_clear_v2"
    private const val KEY_INFO_SCALE = "info_scale"
    /** 五个控车图标的独立微调倍率（2026-11-13）。 */
    private const val KEY_ICON_SCALE = "icon_scale"
    /** 右上角更新时间 + 刷新键的独立微调倍率（2026-11-13）。 */
    private const val KEY_HEADER_SCALE = "header_scale"
    /** 左上角车标的独立微调倍率（2026-11-13）。 */
    private const val KEY_LOGO_SCALE = "logo_scale"

    /**
     * 上一版单独存过的「进度条粗细」。
     *
     * 那个设置已经并入「信息区大小」（条粗细 = 9dp × 倍率），这里只留着键名，
     * 在 [resetSize] 里顺手清掉 —— 不清也不会被读到，但让老设备 prefs 里
     * 一直躺着一个没人认的值，下次翻代码的人会以为它还有用。
     */
    private const val LEGACY_KEY_BAR_THICK = "bar_thick"

    /**
     * 信息区（车名 / 更新时间 / 续航那一整块）的整体大小，1.0 = 原样。
     *
     * 上限 1.3：续航数字装在定宽里程槽（56dp）里，再大就要靠
     * [WidgetMetrics.numberSizeScale] 按实测宽度往回缩了；下限 0.8 是「还能读清」的边界。
     *
     * **进度条粗细也吃这个倍率**（9dp × 倍率）：上一版把它单开了一根滑杆，
     * 用户调完只觉得「字大了、条没变」，2026-11-09 明确要求并回一块。
     */
    const val DEFAULT_INFO_SCALE = 1.0f
    const val MIN_INFO_SCALE = 0.8f
    const val MAX_INFO_SCALE = 1.3f

    /**
     * 细节微调（控车图标 / 右上角时间与刷新键）的范围，1.0 = 现状。
     *
     * **默认必须是 1.0**：这两个键是新增的，老设备 prefs 里根本没有，
     * 读不到时自然落回这一档，升级前后卡片一模一样，不需要一次性迁移标记。
     *
     * 上限 1.25 不是随便定的：图标是 `match_parent + fitCenter`，放大靠减小
     * padding 实现，1.25 时鸣笛那颗（基准留白最小）刚好还留 0.25dp，
     * 再大就只能夹到 0、图形贴着格子边，玻璃底圆角会被切掉。
     */
    const val DEFAULT_FINE_SCALE = 1.0f
    const val MIN_FINE_SCALE = 0.8f
    const val MAX_FINE_SCALE = 1.25f

    /** 系统是不是深色模式。小组件自己判断，不跟 App 里的外观设置走。 */
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 把老设备上「旧默认值」写成新的一套（**只做一次**，之后不再覆盖）。
     *
     * 返回 true 表示这一次刚迁移完。增量更新的判据不直接用它，而是看
     * [VehicleWidget.render] 里的 `lastFullStyle`：版式换了就是换了一份布局，
     * 打成增量的那一帧桌面按 id 找不到视图会整帧丢掉，所以必须退回全量。
     */
    internal fun applyNewDefaults(context: Context): Boolean {
        val sp = prefs(context)
        if (sp.getBoolean(KEY_DEFAULTS_V2, false)) return false
        sp.edit()
            .putString(KEY_STYLE, DEFAULT_STYLE)
            .putBoolean(KEY_CELL_BG, false)
            .putBoolean(KEY_LOGO_VISIBLE, false)
            .putBoolean(KEY_DEFAULTS_V2, true)
            .apply()
        return true
    }

    fun style(context: Context): String {
        applyNewDefaults(context)
        val value = prefs(context).getString(KEY_STYLE, DEFAULT_STYLE)
        return if (value in ALL) value!! else DEFAULT_STYLE
    }

    fun setStyle(context: Context, value: String) {
        val safe = if (value in ALL) value else DEFAULT_STYLE
        prefs(context).edit().putString(KEY_STYLE, safe).apply()
    }

    fun displayName(style: String): String = when (style) {
        STYLE_D -> "沉浸"
        STYLE_T -> "全透明"
        else -> "素纸"
    }

    // ---- 按键外观：圆角底 / 竖向分隔线 ----

    /**
     * 五个按键背后那层圆角矩形（半透明底 + 描边）。
     *
     * 关掉后按键只剩图标，中间那条竖线也就没有「分组」的意思了 —— 两者互相独立，
     * 用户可以只留底色、只留线，也可以两样都不要（最干净的一套）。
     *
     * **默认关**（2026-11-16 用户定稿）：默认版式是全透明，按键再压一层玻璃底
     * 就跟壁纸糊在一起了；关着只剩图标 + 细线，最贴近「浮在壁纸上」。
     * 老设备的那份 true 由 [applyNewDefaults] 一次性改掉，之后完全听用户的。
     */
    fun cellBgVisible(context: Context): Boolean {
        applyNewDefaults(context)
        return prefs(context).getBoolean(KEY_CELL_BG, false)
    }

    fun setCellBgVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_CELL_BG, visible).apply()
    }

    /** 按键之间的四条竖向发丝线。默认开（全透明版式里靠它分出五个按键）。 */
    fun dividersVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DIVIDER, true)

    fun setDividersVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_DIVIDER, visible).apply()
    }

    /**
     * 左上角车标是否显示（2026-11-16 用户定稿：**默认不显示**）。
     *
     * 默认不显示：全透明版式本来就讲究「字直接浮在壁纸上」，车名前面再压一枚车标
     * 只会多一块视觉噪音；要用的人自己去个性化里打开。
     * 关掉后车标格整块 `GONE`（不占位），车名往左移到原来车标的左缘 ——
     * 那一格 18dp + 1dp 间距正好让给车名，不会在行首留一段空白。
     *
     * 老设备 prefs 里没有这个键，但新装默认就是关，所以由 [applyNewDefaults]
     * 跟着一起落一次标记，之后完全听用户的（打开过一次就再也不动它）。
     */
    fun logoVisible(context: Context): Boolean {
        applyNewDefaults(context)
        return prefs(context).getBoolean(KEY_LOGO_VISIBLE, false)
    }

    fun setLogoVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOGO_VISIBLE, visible).apply()
    }

    // ---- 信息区大小 ----

    /** 信息区整体倍率。prefs 里存的可能是手改过的越界值，读出来一律夹回范围。 */
    fun infoScale(context: Context): Float =
        prefs(context).getFloat(KEY_INFO_SCALE, DEFAULT_INFO_SCALE)
            .coerceIn(MIN_INFO_SCALE, MAX_INFO_SCALE)

    fun setInfoScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_INFO_SCALE, scale.coerceIn(MIN_INFO_SCALE, MAX_INFO_SCALE))
            .apply()
    }

    /** 恢复所有尺寸设置的默认值（顺带清掉已下线的粗细设置）。 */
    fun resetSize(context: Context) {
        prefs(context).edit()
            .remove(KEY_INFO_SCALE)
            .remove(LEGACY_KEY_BAR_THICK)
            .remove(KEY_ICON_SCALE)
            .remove(KEY_HEADER_SCALE)
            .remove(KEY_LOGO_SCALE)
            .apply()
    }

    // ---- 细节微调：控车图标 / 右上角时间与刷新键 ----

    /**
     * 这两根滑杆**不参与「信息区大小」**：车名、续航、进度条仍只认 [infoScale]，
     * 图标与右上角那一组各自单独调。三者叠乘时也不会互相写对方的 prefs。
     */
    private fun fineScale(raw: Float): Float =
        raw.coerceIn(MIN_FINE_SCALE, MAX_FINE_SCALE)

    /** 五个控车图标的大小倍率。 */
    fun iconScale(context: Context): Float =
        fineScale(prefs(context).getFloat(KEY_ICON_SCALE, DEFAULT_FINE_SCALE))

    fun setIconScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_ICON_SCALE, fineScale(scale)).apply()
    }

    /** 右上角更新时间文字与刷新键的大小倍率。 */
    fun headerScale(context: Context): Float =
        fineScale(prefs(context).getFloat(KEY_HEADER_SCALE, DEFAULT_FINE_SCALE))

    fun setHeaderScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_HEADER_SCALE, fineScale(scale)).apply()
    }

    /**
     * 左上角车标的大小倍率（开关见 [logoVisible]）。
     *
     * 与上面两根同一档范围、同一份默认值：老设备 prefs 里没有这个键，
     * 读不到就落回 1.0，升级后卡片一模一样，不配一次性迁移标记。
     */
    fun logoScale(context: Context): Float =
        fineScale(prefs(context).getFloat(KEY_LOGO_SCALE, DEFAULT_FINE_SCALE))

    fun setLogoScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_LOGO_SCALE, fineScale(scale)).apply()
    }

    // ---- 透明主题：字色与控件色 ----

    /** 文字（车名 / 续航数字 / 百分比 / 状态行）的颜色。 */
    fun ink(context: Context): Int =
        prefs(context).getInt(KEY_INK, DEFAULT_INK).let { if (it == 0) DEFAULT_INK else it }

    fun setInk(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_INK, color or 0xFF000000.toInt()).apply()
    }

    /** 控件（五个按键图标 / 刷新键 / 车标 / 分隔线）的颜色。 */
    fun ctrl(context: Context): Int =
        prefs(context).getInt(KEY_CTRL, DEFAULT_CTRL).let { if (it == 0) DEFAULT_CTRL else it }

    fun setCtrl(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_CTRL, color or 0xFF000000.toInt()).apply()
    }

    /** 恢复透明主题的两个默认色（顺带清掉老版本留下的提示高亮色）。 */
    fun resetColors(context: Context) {
        prefs(context).edit().remove(KEY_INK).remove(KEY_CTRL).remove(KEY_HIGHLIGHT).apply()
    }

    /**
     * 这个颜色算不算「浅」。
     *
     * 只用来挑素材：浅色字要压在深玻璃底上、深色字要压在浅玻璃底上；
     * 进度条同理（白条 / 深条）。
     *
     * 用 WCAG 的相对亮度自己算，不用 `ColorUtils.calculateLuminance()`：
     * 那个方法内部走 `android.graphics.Color` 的静态方法，在纯 JVM 单测里拿不到实现，
     * 阈值一旦写错只会在真机上表现为「浅色壁纸上的白字看不见」，很难复现。
     * 阈值取 0.38：纯白 1.0、亮绿 #3DD68C 约 0.51、亮蓝 #6FBEFF 约 0.48（都算浅）、
     * 深灰 #12141A 约 0.01、灰 #5A626E 约 0.12。（蓝的亮度天然偏低，阈值再高一档
     * 就会把「亮蓝」判成暗色（#4FA8FF 实测只有 0.37），于是配出深色进度条压在深壁纸上 —— 那才是真的看不见。）
     */
    fun isLight(color: Int): Boolean {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) > 0.38
    }

    fun wallpaper(context: Context): String {
        val value = prefs(context).getString(KEY_WALLPAPER, WALLPAPER_DEFAULT)
        return if (value == WALLPAPER_CUSTOM) WALLPAPER_CUSTOM else WALLPAPER_DEFAULT
    }

    fun setWallpaper(context: Context, value: String) {
        val safe = if (value == WALLPAPER_CUSTOM) WALLPAPER_CUSTOM else WALLPAPER_DEFAULT
        prefs(context).edit().putString(KEY_WALLPAPER, safe).apply()
    }

    /**
     * 背景图版本号。
     *
     * 每存一张图就 +1，文件名与 URI 都带上它 —— 桌面进程里的 ImageView 对同一个 URI
     * 有缓存，URI 不变的话换了图也不会重新解码，卡片会一直停在旧图上。
     */
    fun revision(context: Context): Int = prefs(context).getInt(KEY_REVISION, 0)

    internal fun bumpRevision(context: Context): Int {
        val next = revision(context) + 1
        prefs(context).edit().putInt(KEY_REVISION, next).apply()
        return next
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
