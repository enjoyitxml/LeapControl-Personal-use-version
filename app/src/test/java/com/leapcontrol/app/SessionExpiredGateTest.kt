package com.leapcontrol.app

import com.leapcontrol.app.ui.ControlFailure
import com.leapcontrol.app.ui.ControlFix
import com.leapcontrol.app.ui.SessionExpiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「登录已失效就必须拒绝控车」的守门测试（2026-11-08）。
 *
 * ## 用户反馈的原话
 * 弹了「登录已失效」，点「稍后」之后**照旧能继续用、还能控车**。
 *
 * ## 真因
 * 这道门禁以前只装在桌面小组件（[com.leapcontrol.app.widget.VehicleWidgetProvider]）
 * 和控制中心磁贴（[com.leapcontrol.app.tiles.VehicleTileService]）两个**点按入口**。
 * App 内的 [com.leapcontrol.app.ui.AppRuntime.control] / `controlForWidget` /
 * `controlFridge` / `controlChargeGun` / `runFotaCommand` 根本没看过这个标记：
 * 「稍后」只是关掉对话框，标记还在，但没有任何一处拿它去拦 —— 于是照样发出指令、
 * 等 5~20 秒、最后回一句「信息校验失败」。
 *
 * 所以这里钉住三件事：
 * 1. 每条下发入口都先过 [ControlFailure.isSessionExpired]；
 * 2. 发指令必须走 `sendControlWatched`（失败时把标记落下，让三处入口同步知道）；
 * 3. App 内被拒 / 进 App 时标记还在，都要把「重新登录」框重新弹出来。
 */
class SessionExpiredGateTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun file(path: String): String = File(projectDir, path).readText()

    private val runtime: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt")
    }
    private val main: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/ui/MainScreen.kt")
    }
    private val failure: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/ui/ControlFailure.kt")
    }

    /** 目标函数签名之后的一小段，够看清入口有没有先过门禁。 */
    private fun headAfter(signature: String, span: Int = 260): String {
        val at = runtime.indexOf(signature)
        assertTrue("找不到 $signature —— 函数被改名了，这条守门要跟着更新", at >= 0)
        return runtime.substring(at, minOf(runtime.length, at + span))
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

    /**
     * 去掉注释行之后的代码。
     *
     * 注释里会引用「稍后」这种反例来说明「为什么去掉」，直接 contains 会被自己的注释绊倒。
     */
    private fun codeOnly(text: String): String = text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }
        .joinToString("\n")

    // ---------------- 1. 每条下发入口都要先过门禁 ----------------

    @Test
    fun `五条下发入口都先检查登录已失效`() {
        listOf(
            "suspend fun control(",
            "suspend fun controlForWidget(",
            "suspend fun controlFridge(",
            "private suspend fun controlChargeGun(",
            "private suspend fun runFotaCommand(",
        ).forEach { signature ->
            assertTrue(
                "$signature 没有先过 guardSessionExpired()：登录失效后照样会把指令发出去，" +
                    "用户点「稍后」还能继续控车（2026-11-08 反馈）",
                headAfter(signature).contains("guardSessionExpired()"),
            )
        }
    }

    @Test
    fun `门禁先在线核实，核实不过才拦`() {
        val at = runtime.indexOf("private suspend fun guardSessionExpired(")
        assertTrue("guardSessionExpired 不见了（或不再是 suspend —— 核实要发网络请求）", at >= 0)
        val body = runtime.substring(at, minOf(runtime.length, at + 1_600))
        assertTrue(
            "门禁没读 ControlFailure.isSessionExpired，等于没拦",
            body.contains("ControlFailure.isSessionExpired("),
        )
        assertTrue(
            "门禁没先核实就拦：token 过期被当成登录失效，用户会看到「说失效、其实能用」",
            body.contains("verifySessionVerdict()"),
        )
        assertTrue(
            "门禁没有放行分支：会话其实还好时仍然会把控车拦掉",
            body.contains("SessionVerdict.ALIVE"),
        )
        assertTrue(
            "门禁抛的不是 SessionExpiredException，文案会落到「稍后重试」那种错误引导上",
            body.contains("throw SessionExpiredException()"),
        )
        assertTrue(
            "被拦下时没有重新发「弹框」信号：用户就再也看不到引导了",
            body.contains("notifyExpired("),
        )
    }

    // ---------------- 2. 发出失败要把标记落下 ----------------

    @Test
    fun `下发指令统一走 sendControlWatched`() {
        assertTrue("sendControlWatched 不见了", runtime.contains("private fun sendControlWatched("))
        // 只允许 sendControlWatched 内部保留一处裸调用
        val raw = Regex("""withNetworkRetry\s*\{\s*api\.sendControl\(""").findAll(runtime).count()
        assertEquals(
            "还有绕过 sendControlWatched 的直接下发：那条路的鉴权失败不会落下标记，" +
                "桌面卡片会继续显示正常车况",
            1,
            raw,
        )
    }

    @Test
    fun `sendControlWatched 认出登录失效就标记`() {
        val at = runtime.indexOf("private fun sendControlWatched(")
        assertTrue("sendControlWatched 不见了", at >= 0)
        val body = runtime.substring(at, minOf(runtime.length, at + 800))
        assertTrue(
            "sendControlWatched 没判 ControlFix.RELOGIN",
            body.contains("ControlFix.RELOGIN"),
        )
        assertTrue(
            "sendControlWatched 认出登录失效后没落标记",
            body.contains("markSessionExpired("),
        )
        assertTrue("sendControlWatched 吞了异常：调用方拿不到失败原因", body.contains("throw e"))
    }

    // ---------------- 3. App 内要重新把人送去登录 ----------------

    @Test
    fun `弹框前先核实，不是只信本地标记`() {
        assertTrue(
            "MainScreen 没订阅 expiredEvents：App 内被拒只会有一句提示，不会引导重新登录",
            main.contains("ControlFailure.expiredEvents.collect"),
        )
        assertTrue(
            "进 App 时没检查标记：从桌面点进来的人一按控车就被拒，却看不到任何提示",
            main.contains("if (ControlFailure.isSessionExpired(navContext))"),
        )
        assertTrue(
            "弹框前没核实（confirmExpired）：误报的标记会把人锁进一个关不掉的框里",
            main.contains("runtime.verifySessionVerdict() == SessionVerdict.EXPIRED"),
        )
    }

    @Test
    fun `登录已失效的框是强制的，没有稍后`() {
        // 用户 2026-11-08 的要求：报了这个错就必须重新登录。
        // 上一版留了「稍后」，他点完之后照样开着车、照样解锁 —— 有出口就等于没说清。
        val dialog = codeOnly(bodyOf(main, "private fun ReloginDialog("))
        assertTrue("找不到 ReloginDialog 定义", dialog.isNotBlank())
        assertFalse("框里还有「稍后」：用户点掉就能接着用，等于没强制", dialog.contains("稍后"))
        assertFalse("框里还有 dismissButton：等于给了一个绕过重新登录的出口", dialog.contains("dismissButton"))
        assertTrue(
            "onDismissRequest 必须是空实现（点框外 / 返回键都关不掉）",
            dialog.contains("onDismissRequest = {}"),
        )
        assertTrue("框里得留「重新登录」这个唯一出口", dialog.contains("重新登录"))
    }

    // ---------------- 4. 文案与异常映射 ----------------

    @Test
    fun `被门禁拦下时给的是重新登录，不是稍后重试`() {
        val hint = ControlFailure.of(SessionExpiredException())
        assertEquals(ControlFix.RELOGIN, hint.action)
        assertEquals(SessionExpiredException.MESSAGE, hint.message)
        assertFalse("文案里不该出现「稍后重试」", hint.message.contains("稍后重试"))
        assertTrue("文案要让用户去重新登录", hint.message.contains("重新登录"))
    }

    @Test
    fun `控车网关的鉴权报文仍然认成登录失效`() {
        // 这几句是真实报文，门禁和标记都靠它判定
        listOf(
            "控车失败(39): 信息校验失败",
            "第三方TOKEN失效",
            "未完成网关登录",
        ).forEach { raw ->
            assertEquals(
                "「$raw」没被认成登录失效",
                ControlFix.RELOGIN,
                ControlFailure.ofMessage(raw).action,
            )
        }
    }

    @Test
    fun `失效标记只在登录成功、登出、核实通过时才清`() {
        // 写标记的地方可以多，清标记的地方必须少而明确：
        // 登录成功 / 登出 / 切换会话收尾 / 在线核实出「其实还好」。
        // 多出来的「控车成功就清」会把状态搅成来回跳（2026-11-08 修掉的两处）。
        val clears = Regex("""ControlFailure\.clearSessionExpired\(""").findAll(runtime).count()
        assertEquals(
            "AppRuntime 里清标记的点位变成了 $clears 处（预期 4 处：登录成功 / 登出 / 失效会话收尾 / 核实通过）",
            4,
            clears,
        )
        assertTrue(
            "clearSessionExpired 定义不见了",
            failure.contains("fun clearSessionExpired(context: Context)"),
        )
        // 桌面小组件、磁贴这两条「控车成功就自愈」的点位必须保持移除
        listOf(
            "app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt",
            "app/src/main/java/com/leapcontrol/app/tiles/TileCommandRunner.kt",
        ).forEach { path ->
            assertFalse(
                "$path 又在「控车成功」里撤标记了：那会让标记在成功/失败之间来回跳，",
                file(path).contains("ControlFailure.clearSessionExpired("),
            )
        }
    }
}
