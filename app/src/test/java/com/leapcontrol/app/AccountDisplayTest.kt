package com.leapcontrol.app

import com.leapcontrol.app.ui.AccountDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「我的」页账号卡的两项取值口径（2026-11-20 用户要求）。
 *
 * 需求原话：「我的界面增加显示目前登录的账号、车架号」。
 *
 * 这里只钉两件事：
 * 1. 手机号**必须打码**——这张卡有人会截图发出去，整串号码不该出现在界面上；
 * 2. 车架号**必须完整**——报故障、找客服时念的就是这 17 位，缩成后 6 位等于没有。
 */
class AccountDisplayTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val profile: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/ProfileScreen.kt").readText()
    }

    /** 账号卡必须真的挂在「我的」页上，并且取的是**这两样**数据（不是昵称、不是 VIN 后六位）。 */
    @Test
    fun `我的页展示登录账号与完整车架号`() {
        assertTrue(
            "「我的」页没有展示登录账号",
            profile.contains("AccountDisplay.maskPhone(runtime.session.phone)"),
        )
        assertTrue(
            "「我的」页没有展示完整车架号",
            profile.contains("AccountDisplay.normalizeVin(runtime.session.selectedVin)"),
        )
    }

    /**
     * 2026-11-20 第二版：这两行不再常驻成一张卡，改成点头像卡弹详情。
     *
     * 同一份信息在页面上只留一处 —— 既在卡上又在弹窗里，截图发给别人时会显得重复。
     */
    @Test
    fun `账号与车架号收进头像卡的详情弹窗`() {
        assertTrue(
            "头像卡没做成可点",
            profile.contains("clickable { showAccountDetail = true }"),
        )
        assertTrue("详情弹窗没接上", profile.contains("AccountDetailDialog("))
        assertTrue(
            "弹窗里没放完整车架号（等宽字体便于逐位核对）",
            profile.contains("AccountInfoRow(label = \"车架号\", value = vin, monospace = true)"),
        )
        // 页面上只该出现一次「登录账号」标签：常驻卡拆掉了、弹窗里留了一处
        assertEquals(
            "「登录账号」出现了不止一次，说明常驻的账号卡没拆干净",
            1,
            Regex("\"登录账号\"").findAll(profile).count(),
        )
    }

    @Test
    fun `11 位手机号中间四位打码`() {
        assertEquals("138****5678", AccountDisplay.maskPhone("13812345678"))
        assertEquals("199****0000", AccountDisplay.maskPhone("19912340000"))
    }

    @Test
    fun `带空格或连字符的手机号也认`() {
        // 网关下发过的号里带过空格；只取数字，别因为格式问题就整串露出
        assertEquals("138****5678", AccountDisplay.maskPhone("138 1234 5678"))
        assertEquals("138****5678", AccountDisplay.maskPhone("+86 13812345678"))
    }

    @Test
    fun `不是手机号的账号原样返回`() {
        // 位数不对时硬套模板会给出「use****ple.com」这种更绕的东西
        assertEquals("user@example.com", AccountDisplay.maskPhone("user@example.com"))
        assertEquals("", AccountDisplay.maskPhone(""))
        // 全是空白：trim 之后就是空串，别把空格原样搬到界面上
        assertEquals("", AccountDisplay.maskPhone("  "))
    }

    @Test
    fun `车架号去空格并转大写`() {
        assertEquals(
            "LGBH12E0XNY123456",
            AccountDisplay.normalizeVin(" lgbh12e0xny123456 "),
        )
        assertEquals(
            "LGBH12E0XNY123456",
            AccountDisplay.normalizeVin("LGBH 12E0 XNY1-23456"),
        )
    }

    @Test
    fun `空值给占位符而不是空白`() {
        assertEquals("未选择车辆", AccountDisplay.reveal("", placeholder = "未选择车辆"))
        assertEquals("未取到账号", AccountDisplay.reveal("   ", placeholder = "未取到账号"))
        assertEquals("138****5678", AccountDisplay.reveal("138****5678"))
    }
}
