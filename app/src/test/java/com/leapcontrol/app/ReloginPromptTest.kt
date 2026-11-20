package com.leapcontrol.app

import com.leapcontrol.app.ui.ControlFailure
import com.leapcontrol.app.ui.ControlFix
import com.leapcontrol.app.ui.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「登录已过期」提示必须能直接去重新登录（2026-11-20 用户反馈）。
 *
 * ## 用户的原话
 * 「提示登录已过期时，应该直接去引导用户重新登录，而不是只弹一个提示」
 *
 * 截图里那条红字是首页的结果条（[com.leapcontrol.app.ui.HomeScreen] 里的 ActionBanner），
 * 它原来 5 秒后自己淡掉，点一下也只是收起 —— 用户看完等于什么都没得到，
 * 还得自己去「我的」页翻退出登录。
 *
 * ## 现在的口径（三条，缺一不可）
 * 1. 文案不再指路「我的」，只说「登录已过期，请重新登录」；
 * 2. 首页结果条判定为登录失效时**不自动消失**，右侧挂「重新登录 ›」，点整条就走重登；
 * 3. 同一条路顺手落一次失效标记，让主屏核实后弹出那个关不掉的「重新登录」框 ——
 *    万一结果条被别的东西顶掉，用户仍有出路。
 */
class ReloginPromptTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val home: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt").readText()
    }

    private val userMessage: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/UserMessage.kt").readText()
    }

    // ---------------- 判定口径 ----------------

    @Test
    fun `网关那句鉴权失败会翻成统一的重登文案`() {
        assertEquals(
            UserMessage.RELOGIN_MESSAGE,
            UserMessage.ofMessage("登录已过期，请重新登录"),
        )
        // 旧文案（带指路）自己也要被判成重登，否则历史字符串一出现就漏判
        assertEquals(
            UserMessage.RELOGIN_MESSAGE,
            UserMessage.ofMessage("登录已过期，请到「我的」重新登录"),
        )
    }

    @Test
    fun `重登文案自身必须被判成 RELOGIN`() {
        // 首页拿这段文案回头再判一次（bannerText 已经换过），口径必须闭合
        assertEquals(
            ControlFix.RELOGIN,
            ControlFailure.ofMessage(UserMessage.RELOGIN_MESSAGE).action,
        )
        // 门禁拦下来的那一句同理
        assertEquals(
            ControlFix.RELOGIN,
            ControlFailure.ofMessage("登录已失效，请重新登录后再控车").action,
        )
    }

    @Test
    fun `网络与车端故障不能被当成登录失效`() {
        assertFalse(
            "网络错误被当成登录失效会把人白踢回登录页",
            ControlFailure.ofMessage("连不上服务器，请检查手机网络后重试").action == ControlFix.RELOGIN,
        )
        assertFalse(
            ControlFailure.ofMessage("控车失败(3021): 车辆不在线").action == ControlFix.RELOGIN,
        )
    }

    @Test
    fun `文案不再把用户指去某个页面`() {
        assertTrue(
            "「请到「我的」重新登录」把下一步推给了用户，和「点提示就能重登」的新交互矛盾",
            // 查的是**返回给界面的字面量**，注释里留着旧说法做背景说明不算
            !userMessage.contains("\"登录已过期，请到「我的」重新登录\""),
        )
    }

    // ---------------- 源码守门 ----------------

    @Test
    fun `首页结果条为登录失效提供直接重登的出口`() {
        assertTrue(
            "ActionBanner 缺少 onRelogin 参数",
            home.contains("onRelogin: (() -> Unit)? = null"),
        )
        assertTrue(
            "登录失效时整条提示必须变成重登按钮（点击不再只是收起）",
            home.contains("onClick = onRelogin ?: onDismiss"),
        )
        assertTrue(
            "缺少右侧「重新登录 ›」的可见入口",
            home.contains("重新登录 ›"),
        )
        assertTrue(
            "首页没有把「点提示 = 重新登录」接上：必须 endExpiredSession 后回登录页",
            home.contains("runtime.endExpiredSession()"),
        )
    }

    @Test
    fun `登录失效的提示不许自动消失`() {
        assertTrue(
            "ActionBanner 的自动收起必须给 onRelogin 让路，否则提示几秒就淡掉、等于只弹了一下",
            home.contains("if (onRelogin != null) return@LaunchedEffect"),
        )
    }

    @Test
    fun `登录失效必须按错误样式上色`() {
        // isError 原来只看 refreshError：控车被拒走的是 actionMessage，
        // 不补这一条就会出现「绿勾 + 登录已过期」
        assertTrue(
            "登录失效没有并入 isError，会被画成成功的绿条",
            home.contains("isError = refreshError != null || bannerNeedsRelogin"),
        )
    }

    @Test
    fun `首页一判定到登录失效就把失效标记落下`() {
        assertTrue(
            "没有 markSessionExpired，主屏就不会弹那个关不掉的「重新登录」框",
            home.contains("ControlFailure.markSessionExpired(coachContext)"),
        )
    }
}
