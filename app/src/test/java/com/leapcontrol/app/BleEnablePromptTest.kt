package com.leapcontrol.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「蓝牙钥匙开启提示」的守门测试。
 *
 * 这个弹层的意义就是**在开之前把丑话说清楚**（适配性一般、会和官方 App 抢蓝牙信道），
 * 以及「总开关不开，下面的设置项不该看起来是能用的」。
 * 两条都是纯界面行为、没有可测的状态机，所以按项目里既有的做法直接读源码断言接线还在
 * （同 [DoubleConfirmRulesTest] 里那条守门用例）。
 *
 * 哪天有人觉得「多一层弹层太烦」把它删掉，这里会先红。
 */
class BleEnablePromptTest {

    private val projectDir = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun source(path: String) = File(projectDir, path).readText()

    @Test
    fun `开启提示写明了适配性一般并建议用官方钥匙`() {
        val text = source("app/src/main/java/com/leapcontrol/app/ui/BleEnablePrompt.kt")
        assertTrue("提示里没有「适配性一般」这句", text.contains("适配性一般"))
        assertTrue("提示里没有引导用户去用官方 App", text.contains("官方零跑 App"))
        assertTrue("提示里没有说明两端抢蓝牙信道", text.contains("争抢蓝牙信道"))
        // 两个按钮：坚持开启 / 保持关闭（推荐）
        assertTrue("缺少「仍要开启」按钮", text.contains("仍要开启"))
        assertTrue("缺少「保持关闭（推荐）」按钮", text.contains("保持关闭（推荐）"))
        // 返回键与遮罩都要能关掉，否则弹层会把人锁在页面上
        assertTrue("弹层没接 BackHandler", text.contains("BackHandler"))
    }

    @Test
    fun `两个总开关入口都在开之前弹提示`() {
        // 详情页里的开关
        val detail = source("app/src/main/java/com/leapcontrol/app/ui/BluetoothKeyScreen.kt")
        assertTrue("详情页没接开启提示", detail.contains("BleEnablePromptDialog"))
        assertTrue("详情页的开关没先弹提示就直接下发", detail.contains("showEnablePrompt = true"))
        // 设置页里那个是同一份状态，也必须过同一道闸
        val settings = source("app/src/main/java/com/leapcontrol/app/ui/SettingsScreen.kt")
        assertTrue("设置页没接开启提示", settings.contains("BleEnablePromptDialog"))
        assertTrue("设置页的开关没先弹提示就直接下发", settings.contains("showBlePrompt = true"))
    }

    @Test
    fun `总开关关着时详情页的设置整组压暗并给说明`() {
        val detail = source("app/src/main/java/com/leapcontrol/app/ui/BluetoothKeyScreen.kt")
        assertTrue(
            "总开关关着时设置项没压暗，看上去像已经生效了",
            detail.contains("alpha(if (desired.enabled) 1f else 0.5f)"),
        )
        assertTrue(
            "缺少「先打开总开关」的说明",
            detail.contains("下面的设置才会生效"),
        )
    }

    @Test
    fun `图标默认隐藏对老设备也要生效`() {
        // 默认值只对新安装生效：老设备 prefs 里存着旧默认的 true，光改 getBoolean 的兜底值
        // 是看不到效果的（用户装上后图标还在，以为这次改动没做进去）。必须有一次迁移。
        val runtime = source("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt")
        assertTrue("没有迁移标记 key", runtime.contains("KEY_BLE_ICON_MIGRATED"))
        assertTrue(
            "迁移分支没有把旧值强制收起来",
            runtime.contains(".putBoolean(KEY_BLE_ICON_VISIBLE, false)"),
        )
        assertTrue(
            "兜底值不是 false",
            runtime.contains("namingPrefs.getBoolean(KEY_BLE_ICON_VISIBLE, false)"),
        )
        // 首页那颗图标必须真的受这个开关门禁，否则开关本身是个摆设
        val home = source("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt")
        assertTrue(
            "首页蓝牙图标没受 bleIconVisible 门禁",
            home.contains("if (bleHost != null && bleIconVisible)"),
        )
    }
}
