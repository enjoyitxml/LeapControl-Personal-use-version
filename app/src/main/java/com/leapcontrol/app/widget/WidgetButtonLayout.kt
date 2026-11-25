package com.leapcontrol.app.widget

import android.content.Context
import com.leapcontrol.app.R

/**
 * 桌面小组件上「五个控车按钮」是哪几个、按什么顺序。
 *
 * 纯逻辑：只认枚举名与顺序，不碰车况、不判断车辆能力、不算下发方向 —— 那些是
 * [VehicleWidgetProvider] 的事。这样这一类可以在纯 JVM 单测里直接跑，不用 Android stub。
 *
 * ## 为什么恒定 5 槽
 * 六份 `widget_*` 布局里只有 5 个槽位 id（wg_cell_unlock / lock / ac / trunk / horn）
 * 与 5 个图标 id，版式、尺寸、id 集合都不许动（`WidgetSizesTest` 盯着 4×2 与 wide 的 id 一致）。
 * 所以「替换」只是换某个槽位绑定的动作，「排序」只是换 5 个动作的先后顺序，槽位 id 永远按位置取。
 *
 * ## 为什么用独立的一项偏好
 * 与 [WidgetStyle] 共用 `widget_style` 这份 prefs（命名跟它一致），但只用自己那一个
 * key [KEY]，不和版式 / 壁纸 / 颜色那些值混在一起，load / save 也只放在这个文件里。
 */
object WidgetButtonLayout {

    /** 槽位数：布局里写死的 5 格，永远不变。 */
    const val SLOT_COUNT = 5

    private const val PREFS = "widget_style"
    private const val KEY = "widget_buttons"
    private const val SEP = ","

    /** 默认五个：解锁 / 上锁 / 空调 / 后备箱 / 鸣笛，一字不改、顺序不改。 */
    val DEFAULT: List<WidgetButtonId> = listOf(
        WidgetButtonId.UNLOCK,
        WidgetButtonId.LOCK,
        WidgetButtonId.AC,
        WidgetButtonId.TRUNK,
        WidgetButtonId.HORN,
    )

    /**
     * 把落盘字符串还原成 5 个按钮。
     *
     * 容错顺序：去重 → 只留合法 id → 不足 5 个用 [DEFAULT] 里还没出现的补齐 → 超长截断。
     * 结果**恒为长度 5 且无重复**，所以渲染层可以放心按下标取槽位 / 图标 id。
     */
    fun parse(raw: String?): List<WidgetButtonId> {
        if (raw.isNullOrBlank()) return DEFAULT
        val ids = raw.split(SEP).mapNotNull { token ->
            WidgetButtonId.entries.firstOrNull { it.name == token.trim() }
        }
        return normalize(ids)
    }

    /** 落盘。先 [normalize] 一遍，保证存进去的永远是可安全回读的 5 项。 */
    fun serialize(list: List<WidgetButtonId>): String =
        normalize(list).joinToString(SEP) { it.name }

    /**
     * 把 [from] 位置的一项挪到 [to]；越界或原地不动时原样返回。
     * 语义与 `ui/CardReorder.kt` 的 `movedItem` 完全一致（这里内聚一份，纯逻辑层不依赖 Compose）。
     */
    fun move(list: List<WidgetButtonId>, from: Int, to: Int): List<WidgetButtonId> {
        if (from !in list.indices) return list
        val target = to.coerceIn(0, list.size - 1)
        if (from == target) return list
        val out = list.toMutableList()
        out.add(target, out.removeAt(from))
        return out
    }

    fun load(context: Context): List<WidgetButtonId> =
        parse(prefs(context).getString(KEY, null))

    fun save(context: Context, list: List<WidgetButtonId>) {
        prefs(context).edit().putString(KEY, serialize(list)).apply()
    }

    /** 去重 + 用 DEFAULT 补齐 + 截断到 5 项。去重靠 LinkedHashSet 保留首次出现的顺序。 */
    private fun normalize(ids: List<WidgetButtonId>): List<WidgetButtonId> {
        val out = LinkedHashSet<WidgetButtonId>(SLOT_COUNT)
        out.addAll(ids)
        for (id in DEFAULT) {
            if (out.size >= SLOT_COUNT) break
            out.add(id)
        }
        return out.take(SLOT_COUNT)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 一个可放进小组件槽位的控车动作。
 *
 * @param title 中文标签（写进 `setContentDescription`，也是配置页里显示的名字）
 * @param action 广播 action 字符串。前 5 个沿用 [VehicleWidget] 里既有的常量值，后续新增的与
 *   `VehicleWidget.ACTIONS` / `VehicleWidgetProvider` 的 `when` 一一对应。
 * @param iconRes 复用现成的 `ic_qc_*` 图标；唯一例外是空调 —— 雪花在冬天看着冷，
 *   小组件里换成三叶风扇（`ic_widget_ac_fan`，位图，见 [AC] 那行的说明）。
 */
enum class WidgetButtonId(
    val title: String,
    val action: String,
    val iconRes: Int,
) {
    UNLOCK("解锁", "com.leapcontrol.app.widget.UNLOCK", R.drawable.ic_qc_unlock),
    LOCK("上锁", "com.leapcontrol.app.widget.LOCK", R.drawable.ic_qc_lock),
    // 空调：小组件里不用雪花（冬天看着冷），换成三叶风扇。
    // 这张是**位图**不是矢量：图形取 78% 画布、与同组线稿图标的外接框一致，
    // 渲染走 setImageViewResource + setColorFilter（SRC_ATOP 只换颜色、保留抗锯齿），
    // 位图跨进程到桌面进程染色比矢量稳；首页 / 右上角提示仍是雪花，两边互不影响。
    AC("空调", "com.leapcontrol.app.widget.TOGGLE_AC", R.drawable.ic_widget_ac_fan),
    TRUNK("后备箱", "com.leapcontrol.app.widget.TOGGLE_TRUNK", R.drawable.ic_qc_trunk),
    HORN("鸣笛寻车", "com.leapcontrol.app.widget.HORN", R.drawable.ic_qc_horn),
    WINDOW("车窗", "com.leapcontrol.app.widget.WINDOW", R.drawable.ic_qc_window),
    BATTERY_PREHEAT("电池预热", "com.leapcontrol.app.widget.BATTERY_PREHEAT", R.drawable.ic_qc_preheat),
    SUNSHADE("遮阳帘", "com.leapcontrol.app.widget.SUNSHADE", R.drawable.ic_qc_sunshade),
    // 哨兵模式：指令解析直接复用首页那份 ControlActions.resolveCommand("sentry", status)，
    // 见 VehicleWidgetProvider.execute。默认五格里没有它，需要的人在「控车按钮」里自行替换。
    SENTRY("哨兵模式", "com.leapcontrol.app.widget.SENTRY", R.drawable.ic_qc_sentry),
}
