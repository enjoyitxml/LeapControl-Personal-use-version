package com.leapcontrol.app

import com.leapcontrol.app.ui.SHOW_EXTERNAL_ENTRIES
import com.leapcontrol.app.ui.SHOW_UPDATE_CHECK
import com.leapcontrol.app.ui.USAGE_NOTICE_LINES
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「使用范围 / 禁止传播」这段声明本身，以及 2026-11-09 下线的四个对外入口。
 *
 * 为什么用源码断言：这几件事全在界面层，单测里既画不出「我的」页也画不出登录页。
 * 能钉死的是三件事：
 *
 * 1. **文案只有一份**（[USAGE_NOTICE_LINES]）。两处各写一版是这类声明最典型的坏味道 ——
 *    改一处漏一处，最后登录页写着「禁止传播」、关于页还留着「源码已公开，欢迎查阅」。
 * 2. **四个要件不能少**：基于开源、无商业元素、只给内部好友自用、禁止传播且风险自负。
 *    尤其最后一句，只写「请勿外传」是请求，写上责任归属才是界线。
 * 3. **入口是被开关藏起来的，不是删掉的**：将来要恢复只需改常量，
 *    但如果有人悄悄把 [SHOW_EXTERNAL_ENTRIES] 翻回 true，这条测试会先拦住他。
 *    「检查更新」是例外 —— 它 2026-11-09 晚些时候已恢复启用（仓库转私有后配了只读令牌），
 *    这里断言的方向相反：常量必须为 true，且启动检测仍受它约束。
 */
class UsageNoticeTest {

    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun ui(name: String): String =
        File(root, "app/src/main/java/com/leapcontrol/app/ui/$name").readText()

    private val mainSrc = ui("MainScreen.kt")
    private val profileSrc = ui("ProfileScreen.kt")
    private val aboutSrc = ui("PersonalizationScreen.kt")
    private val noticeSrc = ui("UsageNotice.kt")
    private val privacySrc = ui("PrivacyScreen.kt")
    private val loginSrc = ui("LoginScreen.kt")

    @Test
    fun `声明里四个要件都在`() {
        val text = USAGE_NOTICE_LINES.joinToString("\n")
        listOf(
            "开源代码",      // 基于开源
            "商业元素",      // 无商业元素
            "内部好友",      // 只给内部好友
            "严禁",          // 禁止传播
            "传播者自行承担", // 责任归属
            "与开发者无关",
        ).forEach { assertTrue("声明里缺了「$it」", text.contains(it)) }
        // 两句：事实陈述 + 责任划分。第二句一旦被删，这段就退化成一句客气的请求
        assertEquals("声明应当保留「事实 / 责任」两句", 2, USAGE_NOTICE_LINES.size)
    }

    @Test
    fun `登录页与我的页共用同一份文案`() {
        assertTrue("登录页没有引用共用文案", loginSrc.contains("USAGE_NOTICE_LINES"))
        // 2026-11-09：整块声明从「关于」页挪到「我的」页底部，共用组件在 UsageNotice.kt
        assertTrue("「我的」页没有用共用组件", profileSrc.contains("UsageNoticeCard()"))
        assertTrue("共用组件没读共用文案", noticeSrc.contains("USAGE_NOTICE_LINES"))
        // 各写一版 = 早晚不一致。用户抄一句原文进来，就是又开了一份副本
        assertTrue("登录页又自己写了一遍声明", !loginSrc.contains("严禁在任何公开社交平台"))
        assertTrue("「我的」页又自己写了一遍声明", !profileSrc.contains("严禁在任何公开社交平台"))
        assertTrue("关于页又自己写了一遍声明", !aboutSrc.contains("严禁在任何公开社交平台"))
        // 关于页不要再摆一份（用户要的是「挪」，不是两处各留一块）
        assertTrue("关于页还留着声明块", !aboutSrc.contains("\"使用范围\""))
    }

    @Test
    fun `三个对外入口已隐藏，检查更新恢复启用`() {
        assertTrue("SHOW_EXTERNAL_ENTRIES 应为 false", !SHOW_EXTERNAL_ENTRIES)
        // 2026-11-09 恢复：仓库转私有后配了只读令牌，检测与下载都有了着落
        assertTrue("SHOW_UPDATE_CHECK 应为 true", SHOW_UPDATE_CHECK)

        // 「我的」页两处：开源协议与致谢、意见反馈。
        // 2026-11-15 起前者对个人学习版是**必须显示**的（`|| PERSONAL_EDITION`），
        // 所以这里不再数 `if (SHOW_EXTERNAL_ENTRIES)` 的个数，改成逐处钉开关。
        assertTrue(
            "「我的」页的开源致谢入口没接开关",
            profileSrc.contains("if (SHOW_EXTERNAL_ENTRIES || PERSONAL_EDITION)"),
        )
        assertTrue(
            "「我的」页的意见反馈入口没接开关",
            profileSrc.contains("if (SHOW_EXTERNAL_ENTRIES) {"),
        )
        assertTrue(
            "个人学习版没接上「支持开发者」入口",
            profileSrc.contains("if (PERSONAL_EDITION)"),
        )
        assertTrue("「我的」页还残留硬编码的「开源协议与致谢」", profileSrc.contains("\"开源协议与致谢\""))
        assertTrue("「我的」页还残留硬编码的「意见反馈」", profileSrc.contains("\"意见反馈\""))
        assertTrue("检查更新入口没走开关", profileSrc.contains("if (SHOW_UPDATE_CHECK)"))

        // 「关于」页的开源项目地址
        assertTrue(
            "「关于」页的开源项目地址没走开关",
            aboutSrc.contains("if (SHOW_EXTERNAL_ENTRIES || PERSONAL_EDITION)"),
        )
        // 隐私条款页的关联入口
        assertTrue("隐私条款页的致谢入口没走开关", privacySrc.contains("if (SHOW_EXTERNAL_ENTRIES)"))

        // 恢复之后这道门禁仍要留着：用户把「设置更新频率」选成「关闭」时靠它不联网
        assertTrue(
            "启动时的自动检测必须仍然受 SHOW_UPDATE_CHECK 约束",
            mainSrc.contains("if (!SHOW_UPDATE_CHECK) return@LaunchedEffect"),
        )
    }

    @Test
    fun `我的页脚是与上面入口同款的小卡片`() {
        // 2026-11-09 之前这里只有一句「严禁传播」的结论；用户要求把关于页那块整块挪下来 ——
        // 只写结论是请求，写全「风险由传播者承担」才是界线，所以两句都留着。
        assertTrue("「我的」页少了声明", profileSrc.contains("UsageNoticeCard()"))
        assertTrue(
            "页脚不许再手写一句缩短版声明（会与共用文案分叉）",
            !profileSrc.contains("仅供内部好友交流学习与自用体验，严禁在任何公开渠道传播或二次分发"),
        )
        // 署名那行按用户要求删掉（2026-11-09：「这行文字去掉」）。
        // 断言带 `text = ` 前缀：文件注释里还会提到这行字（说明它为什么被删），别把注释也算成残留。
        assertTrue(
            "署名行应当已删除",
            !profileSrc.contains("text = \"由开发者个人开发"),
        )
        assertTrue("声明没渲染共用文案", noticeSrc.contains("USAGE_NOTICE_LINES.forEachIndexed"))

        // 与 ProfileItem 同一款底座：panelBg 底、14dp 圆角、水平 15dp 内边距。
        // 这里连 ProfileItem 一起断言 —— 哪天有人改了入口卡的圆角/内边距而没同步声明卡，
        // 「和上面统一」就又破了。
        listOf(
            "RoundedCornerShape(14.dp)" to "14dp 圆角",
            "panelBg" to "panelBg 底色",
        ).forEach { (token, name) ->
            assertTrue("声明卡没有用$name", noticeSrc.contains(token))
            assertTrue("入口卡自己都不是$name 了，两边对不齐", profileSrc.contains(token))
        }
        assertTrue("声明卡的水平内边距要和入口卡一致", noticeSrc.contains("padding(horizontal = 15.dp"))
        assertTrue("入口卡的水平内边距变了", profileSrc.contains("padding(horizontal = 15.dp"))
        // 间距也要跟上卡片节奏（ITEM_GAP），不能还是页脚用的 14dp
        val callAt = profileSrc.indexOf("UsageNoticeCard()")
        assertTrue("「我的」页没有调用声明卡", callAt > 0)
        assertTrue(
            "声明卡与上方卡片的间距应走 ITEM_GAP",
            profileSrc.substring(maxOf(0, callAt - 120), callAt).contains("ITEM_GAP"),
        )

        // 标题 / 正文取入口卡的标题与副标题两档：标题 14sp Medium 主文字，正文 11sp 次要文字。
        assertTrue("「使用范围」标题被删了", noticeSrc.contains("\"使用范围\""))
        assertTrue("标题字号应与其他卡标题一致（14sp）", noticeSrc.contains("fontSize = 14.sp"))
        assertTrue("标题字重应为 Medium", noticeSrc.contains("FontWeight.Medium"))
        assertTrue("标题应为主文字色", noticeSrc.contains("color = c.textPrimary"))
        assertTrue("正文字号应为 11sp", noticeSrc.contains("fontSize = 11.sp"))
        assertTrue("颜色应为次要文字", noticeSrc.contains("color = c.textSecondary"))
        // 长段落必须显式行高，否则挤成一坨（本项目在首页多行 Text 上踩过）
        assertTrue("缺显式行高", noticeSrc.contains("lineHeight = 17.sp"))

        // 但「强调」那套不能再回来：不许主题色、不许描边（用户要的是「和上面统一」，不是更显眼）
        assertTrue("声明卡又用上主题强调色了", !noticeSrc.contains("accent"))
        assertTrue("声明卡又加描边了", !noticeSrc.contains("border"))
        assertTrue("不许居中对齐（小字长段落居中每行末尾参差）", !noticeSrc.contains("TextAlign.Center"))
    }
}
