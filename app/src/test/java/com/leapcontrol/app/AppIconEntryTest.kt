package com.leapcontrol.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用图标：入口位置 + 默认值（2026-11-09 需求）。
 *
 * 用户要求两件事：
 * 1. 图标切换从「设置 → 高级设置」移出来，直接放在「个性化」里；
 * 2. 默认图标改成零跑官方 LOGO。
 *
 * 这两件事都容易「改一半」：
 * - 只把入口挪过去、忘了关掉解锁开关 → 个性化里那一段仍被 `if` 包着，不点设置就看不见；
 * - 只改 Manifest 的默认 alias、忘了改 `LauncherIcon.current()` 的判定方向 → 界面选中项
 *   和桌面图标不一致（默认其实是官方，界面却显示选中方向盘），用户点「官方」还会因为
 *   「已经是官方」被当成没变化而不下发。
 * 所以这里把四处（Manifest / SessionStore / current() / 两个页面）一起钉住。
 */
class AppIconEntryTest {

    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun src(path: String) = File(root, path).readText()

    private val manifest = src("app/src/main/AndroidManifest.xml")
    private val launcherSrc = src("app/src/main/java/com/leapcontrol/app/LauncherIcon.kt")
    private val storeSrc = src("app/src/main/java/com/leapcontrol/app/SessionStore.kt")
    private val settingsSrc = src("app/src/main/java/com/leapcontrol/app/ui/SettingsScreen.kt")
    private val personalizationSrc =
        src("app/src/main/java/com/leapcontrol/app/ui/PersonalizationScreen.kt")

    /** 取出某段 `<activity-alias ...>` 整块，便于单独断言它的 enabled 值。 */
    private fun aliasBlock(name: String): String {
        val start = manifest.indexOf("android:name=\"$name\"")
        assertTrue("Manifest 里找不到 $name", start >= 0)
        val begin = manifest.lastIndexOf("<activity-alias", start)
        val end = manifest.indexOf("</activity-alias>", start)
        assertTrue("$name 没有闭合标签", end > begin)
        return manifest.substring(begin, end)
    }

    @Test
    fun `默认 alias 是零跑官方 logo`() {
        // 2026-11-15：alias 的 enabled 改成构建占位符（个人学习版只有方向盘一套）。
        // 自用版的默认值仍然是官方 —— 由构建脚本里的 `!personalEdition` 保证。
        assertTrue(
            "官方 logo 的 alias 没接占位符，个人学习版就没法把官方那套默认关掉",
            aliasBlock(".LauncherOfficial").contains("android:enabled=\"\${officialIconEnabled}\""),
        )
        assertTrue(
            "方向盘 alias 没接占位符，个人学习版就没法把它默认打开",
            aliasBlock(".LauncherSteering").contains("android:enabled=\"\${steeringIconEnabled}\""),
        )
        val gradle = src("app/build.gradle.kts")
        assertTrue(
            "自用版必须让官方 alias 默认启用",
            gradle.contains("manifestPlaceholders[\"officialIconEnabled\"] = (!personalEdition).toString()"),
        )
        assertTrue(
            "个人学习版必须让方向盘 alias 默认启用",
            gradle.contains("manifestPlaceholders[\"steeringIconEnabled\"] = personalEdition.toString()"),
        )
    }

    @Test
    fun `清单默认值与 LauncherIcon 的默认常量一致`() {
        // prefs 没记录时启动校准用这个默认值；它与 Manifest 不一致的话，
        // 每次启动都会把图标来回改一次
        assertTrue(
            "loadLauncherIconStyle 的兜底必须是 STYLE_OFFICIAL",
            storeSrc.contains("?: LauncherIcon.STYLE_OFFICIAL"),
        )
        assertTrue(
            "STYLE_OFFICIAL 的注释里应标明它是默认值",
            launcherSrc.contains("默认样式：零跑官方 logo"),
        )
    }

    @Test
    fun `当前样式按方向盘是否被显式启用来判`() {
        // 默认翻到官方之后，判定方向必须跟着翻：两个 alias 都处于 DEFAULT 时
        // （新装、从没切过），只能判「方向盘有没有被显式打开」，否则永远判成方向盘。
        val body = launcherSrc.substring(
            launcherSrc.indexOf("fun current("),
            launcherSrc.indexOf("fun apply("),
        )
        assertTrue(
            "current() 要读方向盘组件的状态",
            body.contains("component(context, STYLE_STEERING)"),
        )
        assertTrue(
            "方向盘启用时返回 STYLE_STEERING",
            body.contains("STYLE_STEERING else STYLE_OFFICIAL"),
        )
    }

    @Test
    fun `选项列表官方在前`() {
        // 默认项排第一，用户进「个性化」第一眼看到的选中项就是它
        assertEquals(
            listOf(LauncherIcon.STYLE_OFFICIAL, LauncherIcon.STYLE_STEERING),
            LauncherIcon.allStyles(),
        )
    }

    @Test
    fun `解锁开关已从高级设置移除`() {
        assertTrue(
            "「设置 → 高级设置」里不许再留「解锁应用图标切换」",
            !settingsSrc.contains("解锁应用图标切换"),
        )
        assertTrue(
            "开关删了，runtime 上的 setter 也要跟着删",
            !settingsSrc.contains("setIconSwitcherVisible"),
        )
    }

    @Test
    fun `个性化页按版本显示应用图标段`() {
        assertTrue(
            "个性化页必须出现「应用图标」",
            personalizationSrc.contains("SettingsSection(title = \"应用图标\""),
        )
        // 个人学习版只有方向盘一套，这一段整段不出现（2026-11-15 用户要求）
        assertTrue(
            "应用图标段没按版本收起",
            personalizationSrc.contains("if (!PERSONAL_EDITION) {"),
        )
        assertTrue(
            "不许再用 iconSwitcherVisible 把它包起来",
            !personalizationSrc.contains("iconSwitcherVisible"),
        )
        assertTrue(
            "两个选项都来自 LauncherIcon.allStyles()，别在页面里另写一份列表",
            personalizationSrc.contains("LauncherIcon.allStyles()"),
        )
    }
}
