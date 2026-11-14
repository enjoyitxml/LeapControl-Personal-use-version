package com.leapcontrol.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「行驶中禁止远程控车」的守门测试（2026-11-09）。
 *
 * 用户要求（同步官方 `VehicleDrivingSafetyPolicy`）：
 * 1. 首页所有控车按钮**变灰不可点击**，点了提示「车辆正在行驶中，无法进行此项操作」；
 * 2. 控制中心磁贴与桌面小组件**不变灰**，但点击同样提示。
 *
 * 变灰只是外观，**点击必须保留** —— 真用 `clickable(enabled = false)` 会把提示一起吞掉，
 * 用户只会觉得按钮坏了。这条也由下面的断言钉住。
 *
 * 判定只允许有一份（[DrivingSafetyPolicy]）：磁贴 / 小组件各自再写一套 D 挡判断，
 * 迟早会出现「App 里点不动、桌面却能点」。
 */
class DrivingGateTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun file(path: String): String = File(projectDir, path).readText()

    private val home: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt")
    }
    private val tile: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/tiles/VehicleTileService.kt")
    }
    private val widget: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt")
    }
    private val notice: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/tiles/TileNotice.kt")
    }

    /** 从签名到**同缩进**的右花括号，即整个函数体（嵌套函数缩进更深，不会误收）。 */
    private fun bodyOf(text: String, signature: String): String {
        val at = text.indexOf(signature)
        if (at < 0) return ""
        val lineStart = text.lastIndexOf('\n', at) + 1
        val indent = text.substring(lineStart, at).takeWhile { it == ' ' }
        val end = text.indexOf("\n$indent}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    // ---------------- 1. 首页：变灰但不摘点击 ----------------

    @Test
    fun `首页控车按钮按行驶状态变灰`() {
        assertTrue(
            "首页没有算出 drivingBlock：按钮不会变灰",
            home.contains("val drivingBlock = DrivingSafetyPolicy.isDriving("),
        )
        assertTrue(
            "QuickButton 调用没有传 enabled：行驶中按钮还是原样",
            home.contains("enabled = !drivingBlock"),
        )
        val button = bodyOf(home, "private fun QuickButton(")
        assertTrue("找不到 QuickButton 定义", button.isNotBlank())
        assertTrue(
            "QuickButton 没有 enabled 参数",
            button.contains("enabled: Boolean = true"),
        )
        assertTrue(
            "变灰没有落到外观上（图标 / 文字仍按原色画）",
            button.contains("dimTint"),
        )
    }

    @Test
    fun `变灰的按钮仍然能点，点了给行驶中提示`() {
        val button = bodyOf(home, "private fun QuickButton(")
        assertTrue(
            "把 enabled 传给了 clickable：点击会被一起吞掉，用户点灰按钮毫无反应",
            button.contains("clickable(\n                enabled = !busy"),
        )
        val onClickAt = home.indexOf("enabled = !drivingBlock")
        assertTrue("找不到首页控车按钮的调用点", onClickAt >= 0)
        val call = home.substring(onClickAt, minOf(home.length, onClickAt + 900))
        assertTrue(
            "点击分支没有在行驶中直接给提示",
            call.contains("actionMessage = DrivingSafetyPolicy.HINT"),
        )
        assertTrue(
            "提示完没有 return：拦下之后照样会弹确认框 / 下发指令",
            call.contains("return@QuickButton"),
        )
    }

    @Test
    fun `下发出口再拦一道，挡住 PIN 重放与二次确认期间起步`() {
        val runControl = bodyOf(home, "fun runControl(")
        assertTrue("找不到 runControl 定义", runControl.isNotBlank())
        assertTrue(
            "runControl 没判行驶：PIN 通过后的重放 / 确认弹窗期间起步都能发出去",
            runControl.contains("DrivingSafetyPolicy.isDriving(status)"),
        )
        assertTrue(
            "runControl 拦下时没给提示",
            runControl.contains("actionMessage = DrivingSafetyPolicy.HINT"),
        )
    }

    // ---------------- 2. 磁贴：不变灰，只提示 ----------------

    @Test
    fun `磁贴点击前判行驶并提示`() {
        val onClick = bodyOf(tile, "override fun onClick()")
        assertTrue("找不到磁贴 onClick", onClick.isNotBlank())
        assertTrue(
            "磁贴没有判行驶：控制中心里照样能远程开车窗 / 后备箱",
            onClick.contains("DrivingSafetyPolicy.isDriving(StatusSnapshot.get("),
        )
        assertTrue(
            "磁贴拦下时没给提示：用户只会觉得点了没反应",
            onClick.contains("TileNotice.blocked(ctx, DrivingSafetyPolicy.HINT)"),
        )
        assertTrue(
            "磁贴拦下后没有 return：指令会继续下发",
            onClick.indexOf("DrivingSafetyPolicy.HINT") < onClick.indexOf("TileCommandRunner.execute"),
        )
    }

    @Test
    fun `磁贴不需要为这次点击额外拉车况`() {
        // 项目纪律：别频繁拉车况（费车电）。行驶判断只用进程内快照。
        val onClick = bodyOf(tile, "override fun onClick()")
        assertFalse(
            "onClick 里出现了 refreshStatus / getVehicleState：为一次点击多烧一份车电",
            onClick.contains("refreshStatus(") || onClick.contains("getVehicleState("),
        )
    }

    @Test
    fun `磁贴被拦下的通知标题是安全限制，不是控车失败`() {
        val blocked = bodyOf(notice, "fun blocked(")
        assertTrue("TileNotice 没有 blocked() —— 行驶中会被说成「控车未成功」", blocked.isNotBlank())
        assertTrue(
            "blocked() 的标题应说明这是安全限制，而不是让用户去排查故障",
            blocked.contains("安全限制"),
        )
    }

    // ---------------- 3. 桌面小组件：不变灰，只提示 ----------------

    @Test
    fun `小组件在执行前判行驶并提示`() {
        val execute = bodyOf(widget, "private suspend fun execute(")
        assertTrue("找不到小组件 execute", execute.isNotBlank())
        assertTrue(
            "小组件没有判行驶：桌面照样能远程控车",
            execute.contains("DrivingSafetyPolicy.isDriving(safetyStatus(runtime))"),
        )
        assertTrue(
            "小组件拦下时没给提示",
            execute.contains("toast(context, DrivingSafetyPolicy.HINT)"),
        )
        assertTrue(
            "小组件判行驶用了联网车况：桌面广播只有 10 秒预算，只该读本地快照",
            widget.contains("private fun safetyStatus(runtime: AppRuntime): CarStatus?"),
        )
    }

    // ---------------- 4. 只允许一份判定 ----------------

    @Test
    fun `三处入口共用同一份判定与同一句话`() {
        listOf("ui/HomeScreen.kt", "tiles/VehicleTileService.kt", "widget/VehicleWidgetProvider.kt")
            .forEach { path ->
                val text = file("app/src/main/java/com/leapcontrol/app/$path")
                assertTrue(
                    "$path 没引用 DrivingSafetyPolicy —— 说明它自己写了一套挡位判断",
                    text.contains("DrivingSafetyPolicy."),
                )
                assertFalse(
                    "$path 里出现了手写的挡位判断（第二份口径，迟早与首页对不上）",
                    text.contains("\"D\", \"DRIVE\"") || text.contains("== \"D\" &&"),
                )
            }
    }
}
