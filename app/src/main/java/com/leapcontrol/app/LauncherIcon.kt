package com.leapcontrol.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标两套之间的切换。
 *
 * 为什么是两套预制图标、而不是「随便选张图」：
 * adaptive icon 的图是**编译期资源**，运行时改不了。想让桌面图标变，
 * 唯一的标准做法是在 Manifest 里为同一个 [MainActivity] 声明多个
 * `activity-alias`，每个 alias 挂一套 icon，切换时用
 * [PackageManager.setComponentEnabledSetting] 启用其一、禁用另一个。
 *
 * 所以这里只有两个固定选项：
 *  - [STYLE_OFFICIAL] 零跑官方 logo（默认）—— 与零跑 App、官网的观感一致
 *  - [STYLE_STEERING] 方向盘 —— 不带任何品牌标识的通用图形
 *
 * 两套都直接摆在「个性化 → 应用图标」里，没有隐藏开关（2026-11-09 按要求移出「高级设置」）。
 * Manifest 里两个 alias 的名字必须与 [ALIAS_STEERING] / [ALIAS_OFFICIAL] 一致。
 */
object LauncherIcon {

    /** 默认样式：零跑官方 logo。 */
    const val STYLE_OFFICIAL = "official"

    /** 可选样式：方向盘（不带品牌标识）。 */
    const val STYLE_STEERING = "steering"

    /** 与 AndroidManifest.xml 里的 `activity-alias android:name` 对应。 */
    const val ALIAS_STEERING = ".LauncherSteering"
    const val ALIAS_OFFICIAL = ".LauncherOfficial"

    /** 选项顺序：官方 logo 在前 —— 它现在是默认值，第一眼就该看到。 */
    fun allStyles(): List<String> = listOf(STYLE_OFFICIAL, STYLE_STEERING)

    /** 人类可读的名字，UI 与提示文案共用。 */
    fun displayName(style: String): String = when (style) {
        STYLE_OFFICIAL -> "官方 Logo"
        else -> "方向盘"
    }

    fun desc(style: String): String = when (style) {
        STYLE_OFFICIAL -> "零跑官方标志，与官网一致"
        else -> "通用方向盘图形，不带任何品牌标识"
    }

    private fun component(context: Context, style: String): ComponentName {
        val alias = if (style == STYLE_OFFICIAL) ALIAS_OFFICIAL else ALIAS_STEERING
        return ComponentName(context.packageName, context.packageName + alias)
    }

    /**
     * 当前生效的样式。
     *
     * 判断依据只能是 PackageManager 里的组件状态 —— 清单里 [STYLE_STEERING] 默认
     * `enabled=false`，未切换过时查询结果是 DEFAULT 而不是 DISABLED，所以要反过来判
     * 「方向盘那个是不是被显式启用了」，而不是判「官方是不是被禁用了」。
     *
     * 默认值翻到官方（2026-11-09）后这个方向必须跟着翻：否则新装用户两个 alias 都是
     * DEFAULT，会永远判成方向盘，界面显示的选中项和桌面图标不一致。
     *
     * 公开版只有方向盘一套（[PERSONAL_EDITION]），不存在「正在用哪个」的问题，
     * 直接返回方向盘 —— 否则界面（若还有残留入口）会显示成官方、与桌面图标对不上。
     */
    fun current(context: Context): String {
        if (PERSONAL_EDITION) return STYLE_STEERING
        val state = runCatching {
            context.packageManager.getComponentEnabledSetting(component(context, STYLE_STEERING))
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) STYLE_STEERING else STYLE_OFFICIAL
    }

    /**
     * 切换图标。**先启用目标、再禁用另一个**，避免中途出现「一个 launcher 都没有」的空窗。
     *
     * 返回是否切换成功。注意：被禁用的正是当前进程的入口组件，部分 ROM 会顺手把进程收掉，
     * 所以调用方拿到 true 后应提示用户「重启 App 生效」，不要假设界面还能接着用。
     */
    fun apply(context: Context, style: String): Boolean {
        val target = if (style == STYLE_OFFICIAL) STYLE_OFFICIAL else STYLE_STEERING
        val other = if (target == STYLE_OFFICIAL) STYLE_STEERING else STYLE_OFFICIAL
        val pm = context.packageManager
        return runCatching {
            pm.setComponentEnabledSetting(
                component(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                component(context, other),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 启动时校准：清单里的默认值不可靠（升级安装会保留旧的组件状态），
     * 以持久化记录为准重放一次，保证桌面图标与用户上次的选择一致。
     *
     * 公开版没有「选择」这回事：[PERSONAL_EDITION] 下固定方向盘，
     * 而且**不能**照 [style] 回放 —— 偏好里存的默认值仍是官方，
     * 照它回放会把刚装好的方向盘图标又翻回零跑 logo。
     */
    fun ensureApplied(context: Context, style: String) {
        if (PERSONAL_EDITION) {
            // 清单默认已是方向盘；这里只处理「装过完整版、组件状态被改过」的残留
            if (!isExplicitlyDisabled(context, STYLE_OFFICIAL) || !isExplicitlyEnabled(context, STYLE_STEERING)) {
                runCatching { apply(context, STYLE_STEERING) }
            }
            return
        }
        if (current(context) != style) runCatching { apply(context, style) }
    }

    private fun isExplicitlyDisabled(context: Context, style: String): Boolean =
        runCatching { context.packageManager.getComponentEnabledSetting(component(context, style)) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun isExplicitlyEnabled(context: Context, style: String): Boolean =
        runCatching { context.packageManager.getComponentEnabledSetting(component(context, style)) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
}
