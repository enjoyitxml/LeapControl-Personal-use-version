package com.leapcontrol.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双版本分叉的守门测试。
 *
 * ## 背景
 * 2026-11-15 起同一个工程出两个包（见 [PERSONAL_EDITION]）：
 *  - **自用版** `full`：私有仓库，零跑官方 logo、登录标语、只有小圈子用；
 *  - **个人学习版** `personal`：公开仓库，去掉全部品牌 logo、「我的」页恢复支持开发者与致谢、
 *    使用范围改成「仅限个人学习研究使用」。
 *
 * 两条线共用一份源码，差异只能收敛在 `PERSONAL_EDITION` 这一个开关上。
 * 这些断言钉的是「分叉点有没有真的接线」—— 漏一处不会编译报错，只会让某一版悄悄
 * 带着另一版的观感发出去。
 *
 * 全部是源码断言，因为单测跑的是 `full` 变体：`personal` 那一支的界面在测试进程里根本走不到。
 */
class EditionTest {

    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun file(path: String): String = File(root, path).readText()
    private fun ui(name: String): String = file("app/src/main/java/com/leapcontrol/app/ui/$name")

    private val buildGradle = file("app/build.gradle.kts")
    private val manifest = file("app/src/main/AndroidManifest.xml")
    private val loginSrc = ui("LoginScreen.kt")
    private val profileSrc = ui("ProfileScreen.kt")
    private val personalizationSrc = ui("PersonalizationScreen.kt")
    private val usageSrc = ui("UsageNotice.kt")
    private val mainSrc = ui("MainScreen.kt")
    private val widgetSrc = file("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt")
    private val updateSrc = file("app/src/main/java/com/leapcontrol/app/GitHubUpdate.kt")

    @Test
    fun `构建开关只有一个来源`() {
        assertTrue("构建脚本没接 edition 属性", buildGradle.contains("leapcontrol.edition"))
        assertTrue("没生成 PERSONAL_EDITION 构建字段", buildGradle.contains("\"PERSONAL_EDITION\""))
        assertTrue("没生成 UPDATE_REPO 构建字段", buildGradle.contains("\"UPDATE_REPO\""))
        assertTrue(
            "个人学习版必须用独立包名，否则两个版本装在同一台手机上会互相覆盖",
            buildGradle.contains("\"com.leapcontrol.personal\""),
        )
        // 默认（不传属性）仍必须是自用版：改错这里会让私有仓库发出去的包变成个人版
        assertTrue(
            "edition 缺省值被改掉了",
            buildGradle.contains("?.trim().orEmpty().ifEmpty { \"full\" }"),
        )
    }

    @Test
    fun `公开包不携带私有仓库令牌`() {
        assertTrue(
            "个人学习版构建时令牌必须为空",
            buildGradle.contains("personalEdition -> \"\""),
        )
        // 密文已经挪到 gradle.properties（公开仓库的那份不带这个键）
        assertTrue(
            "构建脚本里又出现源码内置的令牌明文/密文常量",
            !buildGradle.contains("BUILTIN_GH_TOKEN_ENCRYPTED = \"1cc9"),
        )
        assertTrue(
            "更新检测的仓库地址没走构建字段",
            updateSrc.contains("private val REPO: String = UPDATE_REPO"),
        )
        assertFalse(
            "更新检测又把仓库地址写死了",
            updateSrc.contains("private const val REPO = \"enjoyitxml/"),
        )
    }

    @Test
    fun `桌面图标的默认 alias 由构建占位符决定`() {
        assertTrue(
            "方向盘 alias 的 enabled 没接占位符",
            manifest.contains("android:enabled=\"\${steeringIconEnabled}\""),
        )
        assertTrue(
            "官方 alias 的 enabled 没接占位符",
            manifest.contains("android:enabled=\"\${officialIconEnabled}\""),
        )
        assertTrue(
            "构建脚本没写这两个占位符",
            buildGradle.contains("manifestPlaceholders[\"officialIconEnabled\"]") &&
                buildGradle.contains("manifestPlaceholders[\"steeringIconEnabled\"]"),
        )
        val launcher = file("app/src/main/java/com/leapcontrol/app/LauncherIcon.kt")
        assertTrue(
            "个人学习版启动校准没有固定方向盘（会被偏好里的官方默认值翻回去）",
            launcher.contains("if (PERSONAL_EDITION)"),
        )
    }

    @Test
    fun `登录页在个人学习版固定方向盘且没有标语`() {
        assertTrue(
            "登录页没按版本切 logo（个人版这一支必须能常量折叠成 false，否则官方图与品牌文案会留在公开包里）",
            loginSrc.contains("val officialIcon = if (PERSONAL_EDITION) {"),
        )
        assertTrue(
            "登录标语没按版本收起",
            loginSrc.contains("if (!PERSONAL_EDITION)") &&
                loginSrc.contains("\"零跑控车，自在随行\""),
        )
    }

    @Test
    fun `小组件左上角车标按版本切换`() {
        assertTrue(
            "小组件车标没按版本切",
            widgetSrc.contains("if (PERSONAL_EDITION) R.drawable.ic_widget_logo_steering else R.drawable.ic_leap_logo"),
        )
        // 方向盘图形是专门重画的实心版，不是把 24dp 线性图标缩下来
        val drawable = File(
            root,
            "app/src/main/res/drawable/ic_widget_logo_steering.xml",
        )
        assertTrue("缺少小组件用的方向盘图形", drawable.exists())
    }

    @Test
    fun `个性化在个人学习版不再提供图标切换`() {
        assertTrue(
            "应用图标整段没按版本收起",
            personalizationSrc.contains("if (!PERSONAL_EDITION) {") &&
                personalizationSrc.contains("SettingsSection(title = \"应用图标\""),
        )
    }

    @Test
    fun `我的页在个人学习版恢复支持开发者与开源致谢`() {
        assertTrue(
            "「支持开发者」入口没接版本开关",
            profileSrc.contains("if (PERSONAL_EDITION)") &&
                profileSrc.contains("\"支持开发者\""),
        )
        assertTrue(
            "开源致谢入口在个人学习版也要显示",
            profileSrc.contains("if (SHOW_EXTERNAL_ENTRIES || PERSONAL_EDITION)"),
        )
        assertTrue(
            "「支持开发者」页没接路由",
            mainSrc.contains("SettingsPage.SUPPORT -> SupportScreen("),
        )
        assertTrue(
            "收款码页或资源没恢复",
            File(root, "app/src/main/java/com/leapcontrol/app/ui/SupportScreen.kt").exists() &&
                File(root, "app/src/main/res/drawable-nodpi/pay_qr_alipay.jpg").exists() &&
                File(root, "app/src/main/res/drawable-nodpi/pay_qr_wechat.png").exists(),
        )
    }

    @Test
    fun `车名兜底按版本切换且只有一处定义`() {
        assertTrue(
            "车名兜底常量没按版本切",
            file("app/src/main/java/com/leapcontrol/app/Edition.kt")
                .contains("val DEFAULT_VEHICLE_TITLE: String = if (PERSONAL_EDITION) \"我的爱车\" else \"我的零跑\""),
        )
        // 写死的地方漏一处，个人学习版就会出现「方向盘车标 + 我的零跑」这种自相矛盾的画面
        // （小组件标题、小组件设置页的两个预览、首页车名都吃过这个亏）
        val writers = mapOf(
            "小组件" to widgetSrc,
            "小组件设置页" to ui("WidgetSettings.kt"),
            "首页运行时" to ui("AppRuntime.kt"),
        )
        for ((where, src) in writers) {
            assertFalse("$where 又把车名写死了，应改用 DEFAULT_VEHICLE_TITLE", src.contains("\"我的零跑\""))
        }
        assertTrue(
            "小组件标题的兜底没走常量",
            widgetSrc.contains("title.ifBlank { DEFAULT_VEHICLE_TITLE }"),
        )
    }

    @Test
    fun `使用范围在个人学习版改成学习研究口径`() {
        assertTrue(
            "个人学习版声明没改口径",
            usageSrc.contains("本软件仅限个人学习研究使用，请在学习研究后及时卸载。"),
        )
        // 公开版本自己就是「公开传播」的结果，再写「严禁传播」自相矛盾
        val personalBlock = usageSrc.substringAfter("if (PERSONAL_EDITION)").substringBefore("} else {")
        assertFalse("个人学习版还在写「严禁在任何公开社交平台…传播」", personalBlock.contains("严禁在任何公开社交平台"))
        // 两句结构不能丢：一句说范围、一句划责任
        assertTrue("个人学习版少了责任划分那句", personalBlock.contains("自行承担"))
    }
}
