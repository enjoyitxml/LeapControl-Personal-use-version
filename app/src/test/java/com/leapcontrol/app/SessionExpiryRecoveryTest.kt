package com.leapcontrol.app

import com.leapcontrol.app.ui.ControlFailure
import com.leapcontrol.app.ui.ControlFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「别再误报登录失效」+「重登之后提示要消失」的守门测试（2026-11-08 第二次反馈）。
 *
 * ## 现场的怪事
 * 桌面卡片写着「登录已失效」，点进 App 弹了框，可用户点掉之后**照样能控车**。
 * 一个提示如果既能弹、又拦不住、还能被证明是假的，那它唯一的作用就是吓人。
 *
 * ## 查出来的真因
 * 控车这条链路是全项目唯一一条「既不提前续期 token、也没有鉴权失败重试」的接口：
 * token 一过期，网关回 `result=39`，上层就按「登录失效」落了标记；
 * 而同期任何一次车况刷新（走 `gatewayFetch`）都会把同一个 token 续上 ——
 * 于是「说失效、其实能用」。
 *
 * 所以这里钉住四件事：
 * 1. 控车下发前先续期；真被拒就续期重发一次，续不上才算失效；
 * 2. 拦之前先在线核实（`SessionProbePolicy` 的三态判定）；
 * 3. 弹框之前也核实，核实不过才弹；
 * 4. 登录成功要把桌面卡片重画一遍，别让「登录已失效」在登完之后还挂着。
 */
class SessionExpiryRecoveryTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun file(path: String): String = File(projectDir, path).readText()

    private val api: String by lazy { file("app/src/main/java/com/leapcontrol/app/LeapmotorApi.kt") }
    private val runtime: String by lazy { file("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt") }

    /** 从签名到**同缩进**的右花括号，即整个函数体（嵌套函数缩进更深，不会误收）。 */
    private fun bodyOf(text: String, signature: String): String {
        val at = text.indexOf(signature)
        assertTrue("找不到 $signature —— 函数被改名了，这条守门要跟着更新", at >= 0)
        val lineStart = text.lastIndexOf('\n', at) + 1
        val indent = text.substring(lineStart, at).takeWhile { it == ' ' }
        val end = text.indexOf("\n$indent}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    private fun codeOnly(text: String): String = text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }
        .joinToString("\n")

    // ---------------- 1. 控车别再错报登录失效 ----------------

    @Test
    fun `控车下发前先续期，鉴权失败续期重发一次`() {
        val send = codeOnly(bodyOf(api, "fun sendControl("))
        assertTrue(
            "sendControl 没有提前续期：token 一过期就会回 result=39，被上层当成登录失效",
            send.contains("ensureFreshOldToken()"),
        )
        assertTrue(
            "没有「鉴权失败」的独立 stage：调用方分不清「续期就能好」和「业务失败」",
            send.contains("CONTROL_AUTH_STAGE"),
        )
        assertTrue("鉴权失败后没有续期旧 token", send.contains("refreshOldToken()"))
        assertTrue("鉴权失败后没有续期网关 token", send.contains("refreshAccessTokenWithFallback()"))
        assertTrue(
            "续期后没有重发：这一下等于白续，用户还是看到「登录已失效」",
            Regex("""attempt\(\)""").findAll(send).count() >= 2,
        )
        assertTrue(
            "续期都失败时要给一句和「登录失效」对得上的话（否则不会被认成 RELOGIN）",
            send.contains("SESSION_EXPIRED_MESSAGE"),
        )
    }

    @Test
    fun `401 这种只有状态码的拒绝也要说成登录失效`() {
        val body = codeOnly(bodyOf(api, "private fun authMessageOf("))
        assertTrue("authMessageOf 不见了", body.isNotBlank())
        assertTrue(
            "只有状态码、没有中文 msg 的响应必须补上「登录已失效」，否则既看不懂也不被认成登录问题",
            body.contains("SESSION_EXPIRED_MESSAGE"),
        )
    }

    // ---------------- 2. 三态判定 ----------------

    @Test
    fun `没有 token 直接算失效，不浪费一次探针`() {
        assertEquals(
            SessionVerdict.EXPIRED,
            SessionProbePolicy.verdict(hasToken = false, failure = null, isAuthFailure = { true }),
        )
    }

    @Test
    fun `探针成功说明会话还能用`() {
        assertEquals(
            SessionVerdict.ALIVE,
            SessionProbePolicy.verdict(hasToken = true, failure = null, isAuthFailure = { true }),
        )
    }

    @Test
    fun `鉴权类失败才算真失效`() {
        val expired = IllegalStateException("登录已失效，请重新登录")
        assertEquals(
            SessionVerdict.EXPIRED,
            SessionProbePolicy.verdict(hasToken = true, failure = expired) { raw ->
                ControlFailure.isAuthFailure(raw)
            },
        )
    }

    @Test
    fun `弱网判不了，不能当失效`() {
        // 判成 EXPIRED 的代价很具体：弹一个关不掉的框，而离线时他连登录都登录不了
        listOf(
            "Unable to resolve host app-gw-global-master.leapmotor.com",
            "timeout",
            "failed to connect to /10.0.0.2:443",
        ).forEach { message ->
            assertEquals(
                "「$message」被当成了登录失效",
                SessionVerdict.UNKNOWN,
                SessionProbePolicy.verdict(
                    hasToken = true,
                    failure = IllegalStateException(message),
                ) { raw -> ControlFailure.isAuthFailure(raw) },
            )
        }
    }

    @Test
    fun `核实通过要顺手撤标记、重画桌面卡片`() {
        val body = codeOnly(bodyOf(runtime, "suspend fun verifySessionVerdict("))
        assertTrue("verdict 不见了", body.isNotBlank())
        assertTrue("核实通过没撤标记：卡片会一直写着「登录已失效」", body.contains("clearSessionExpired("))
        assertTrue("核实通过没重画桌面卡片：桌面上那行字要等下一轮刷新才消失", body.contains("VehicleWidget.render("))
        assertTrue("探针必须有超时：不然小组件那 10 秒广播预算会被它吃光", body.contains("withTimeoutOrNull("))
        assertTrue(
            "同一个失效结论会被连打两次探针（门禁一次、弹框前一次），用户要多等一轮网络",
            body.contains("VERDICT_TTL_MS"),
        )
    }

    // ---------------- 3. 重新登录后不能还挂着提示 ----------------

    @Test
    fun `登录成功要把桌面卡片改回车况`() {
        val login = codeOnly(bodyOf(runtime, "suspend fun login("))
        assertTrue("登录成功没撤「登录已失效」标记", login.contains("clearSessionExpired("))
        assertTrue(
            "登录成功没重画桌面卡片：用户刚登完，桌面还印着「登录已失效」，会以为没登上",
            login.contains("VehicleWidget.render("),
        )
        assertTrue("登录成功没刷新控制中心磁贴", login.contains("QuickTiles.refreshAll("))
    }

    @Test
    fun `桌面卡片点刷新时会顺手核实一次`() {
        val widget = file("app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt")
        assertTrue(
            "刷新时没核实：误报的标记只能等用户打开 App 才会被撤，卡片会一直写着失效",
            widget.contains("verifySessionVerdict()"),
        )
    }

    // ---------------- 4. 文案别再劝人「稍后」 ----------------

    @Test
    fun `续不上时的提示指向重新登录`() {
        val hint = ControlFailure.ofMessage("登录已失效，请重新登录")
        assertEquals(ControlFix.RELOGIN, hint.action)
        assertTrue(hint.message.contains("重新登录"))
        assertFalse("不能让用户以为等一会儿会自己好", hint.message.contains("稍后重试"))
    }
}
