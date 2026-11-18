package com.leapcontrol.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 玩法说明里不许再出现「意见反馈」（2026-11-16）。
 *
 * ## 背景
 * 「我的」页的意见反馈入口早已由 [com.leapcontrol.app.ui.SHOW_EXTERNAL_ENTRIES] 收起，
 * 但玩法说明「零记」那一节末尾还留着「可以在「我的 → 意见反馈」里告诉我」。
 * 用户截图圈出后要求删掉：入口都不在了，再教人去点，只会让人翻半天。
 *
 * ## 钉住的规则
 * 1. 玩法说明整页不得出现「意见反馈」四个字（含注释）；
 * 2. 「零记」那节「设置里能关掉」的说明必须留着 —— 删句子时别把整段一起砍了。
 */
class PlaybookNoFeedbackTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val playbook: String =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/PlaybookScreen.kt").readText()

    @Test
    fun `玩法说明不再提意见反馈`() {
        assertFalse(
            "玩法说明里还留着「意见反馈」：入口已下线，这句会让人去翻一个不存在的入口",
            playbook.contains("意见反馈"),
        )
    }

    @Test
    fun `零记的可关闭说明仍在`() {
        assertTrue(
            "「零记」那节的「设置里能整个关掉」被误删了",
            playbook.contains("不想用的话可以在「设置 → 零记」里整个关掉") &&
                playbook.contains("首页的零记卡都会收起"),
        )
    }
}
