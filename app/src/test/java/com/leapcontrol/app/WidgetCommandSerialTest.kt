package com.leapcontrol.app

import com.leapcontrol.app.widget.WidgetCommandClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 短时间连发多条控车指令的守门。
 *
 * 2026-11-13 用户反馈：点完解锁，很快又点开后备箱，卡片写着「后备箱已开启」，
 * 车其实没动。这一条问题在源码里分三层、每层单独看都「自洽」，所以用两段测试钉死：
 *
 * 1. **纯逻辑**：下发节拍的间隔计算（[WidgetCommandClock]）；
 * 2. **接线**：Provider 必须真的用了它（串行等待 / 瞬时动作去重 / 事后求证），
 *    并且不许再拿网关结果码宣布「已执行」。
 */
class WidgetCommandSerialTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun source(path: String): String = File(projectDir, path).readText()

    /** 去掉注释，只留代码 —— 这些文件里注释写满了「为什么」，断言只看行为。 */
    private fun codeOnly(text: String): String = text.lines()
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n") { it.substringBefore("//") }

    private val providerSource = source("app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt")
    private val providerCode = codeOnly(providerSource)
    private val runtimeCode = codeOnly(source("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt"))
    private val widgetCode = codeOnly(source("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt"))

    // ---------------- 纯逻辑 ----------------

    @Test
    fun `从没点过时不用等`() {
        assertEquals(0L, WidgetCommandClock.gapRemainingMs(lastSendAt = 0L, now = 1_000L))
    }

    @Test
    fun `两条指令之间至少隔 MIN_GAP`() {
        val now = 100_000L
        // 紧接着再点：要等满一整个间隔
        assertEquals(WidgetCommandClock.MIN_GAP_MS, WidgetCommandClock.gapRemainingMs(now, now))
        // 过了一半：只等剩下的那一半
        assertEquals(
            WidgetCommandClock.MIN_GAP_MS / 2,
            WidgetCommandClock.gapRemainingMs(now - WidgetCommandClock.MIN_GAP_MS / 2, now),
        )
        // 已经过了一个完整间隔：不等
        assertEquals(0L, WidgetCommandClock.gapRemainingMs(now - WidgetCommandClock.MIN_GAP_MS, now))
        assertEquals(0L, WidgetCommandClock.gapRemainingMs(now - 60_000L, now))
    }

    /** 边界必须左闭右开：正好 2.5 秒算窗口外，否则「第 2.5 秒点下」会被永远拦住。 */
    @Test
    fun `间隔窗口是左闭右开`() {
        val now = 100_000L
        assertTrue(WidgetCommandClock.gapRemainingMs(now - WidgetCommandClock.MIN_GAP_MS + 1, now) > 0)
        assertEquals(0L, WidgetCommandClock.gapRemainingMs(now - WidgetCommandClock.MIN_GAP_MS, now))
    }

    /** 时钟回拨不能算成「过了很久」—— 那会让两条指令直接并发出去。 */
    @Test
    fun `时钟回拨按刚点过处理`() {
        assertEquals(WidgetCommandClock.MIN_GAP_MS, WidgetCommandClock.gapRemainingMs(lastSendAt = 200_000L, now = 100_000L))
    }

    @Test
    fun `瞬时动作重复窗口同样左闭右开`() {
        val now = 100_000L
        val w = WidgetCommandClock.REPEAT_WINDOW_MS
        assertTrue(WidgetCommandClock.elapsedInWindow(now - 1, now, w))
        assertTrue(WidgetCommandClock.elapsedInWindow(now, now, w))
        assertFalse(WidgetCommandClock.elapsedInWindow(now - w, now, w))
        assertFalse(WidgetCommandClock.elapsedInWindow(0L, now, w))
    }

    // ---------------- 接线 ----------------

    @Test
    fun `跨按键串行门必须在 execute 里真的用上`() {
        // 记下本次点按并拿到需要等待的时间 —— 少了这一步，两条指令会并发冲出去
        assertTrue(
            "Provider 没有走下发节拍，跨按键连发会并发下发（车端丢后一条、网关照样回受理码）",
            providerCode.contains("WidgetCommandClock.noteSend("),
        )
        assertTrue("拿到了等待量却没有真的等", providerCode.contains("delay(waitMs)"))
        // 等待上限是为了守住广播 10 秒预算，不能省
        assertTrue("等待没有上限，会把广播拖超时", providerCode.contains("SERIAL_WAIT_MAX_MS"))
        // 顺序：先给「正在…」再等，否则排队期间卡片毫无反应
        val preparingAt = providerCode.indexOf("preparingLabelOf(action)?.let")
        val waitAt = providerCode.indexOf("WidgetCommandClock.noteSend(")
        assertTrue("「正在…」这一帧丢了，排队期间用户以为没点上", preparingAt in 0 until waitAt)
    }

    @Test
    fun `瞬时动作连点不去重就会重复下发`() {
        assertTrue(
            "没有瞬时动作去重集合（解锁 / 上锁 / 鸣笛连点会发两遍）",
            providerCode.contains("REPEAT_ACTIONS"),
        )
        assertTrue(
            "去重窗口没接上",
            providerCode.contains("WidgetCommandClock.withinRepeatWindow("),
        )
        listOf("ACTION_UNLOCK", "ACTION_LOCK", "ACTION_HORN").forEach {
            assertTrue("$it 没进 REPEAT_ACTIONS", providerCode.contains(it))
        }
    }

    @Test
    fun `网关受理不许写成已执行`() {
        // 这是 2026-11-13 那条反馈的正脸：网关回 0 就写「后备箱已开启」
        assertFalse(
            "controlForWidget 又把网关回执当成执行结果了",
            runtimeCode.contains("已执行"),
        )
        assertFalse(
            "Provider 又按「已执行」判成功了",
            providerCode.contains("endsWith(\"已执行\")"),
        )
        assertTrue(
            "结果文案必须走未确认态（「开后备箱已下发」）",
            providerCode.contains("doneLabelOf(command, confirmed = false)"),
        )
        // 按键高亮不能因为「没确认」就丢掉：否则从点下到车况回来之间按键是灭的
        assertTrue(
            "写结果时没给 hint，按键不会立刻跟意图高亮",
            Regex("""hint = command""").containsMatchIn(providerCode),
        )
    }

    @Test
    fun `事后必须用车况求证，求证通过才写完成式`() {
        assertTrue("没有事后求证，卡片只能永远停在「已下发」", providerCode.contains("confirmOutcomeLater("))
        assertTrue(
            "事后求证没用车况信号判定",
            providerCode.contains("ControlFeedback.reached("),
        )
        assertTrue(
            "求证通过后没写完成式（车况确认了却不告诉用户）",
            providerCode.contains("doneLabelOf(command, confirmed = true)"),
        )
        assertTrue(
            "求证到车端没动却不说，用户只能自己去车边确认",
            providerCode.contains("notDoneLabelOf("),
        )
        // 每轮采样前确认卡片上还是自己那条，否则连点两个键时提示会乱跳
        assertTrue(
            "没有「还是我写的那条」判断，连点两个键时旧结论会盖住新动作",
            providerCode.contains("WidgetFlash.stillCurrent("),
        )
        // 求证跑在广播之外：广播只有 10 秒，等不到车端 5~15 秒的执行
        assertTrue(
            "事后求证没有挂到进程级协程，广播结束后活不下来",
            providerCode.substringAfter("private fun confirmOutcomeLater")
                .contains("work.launch"),
        )
    }

    @Test
    fun `判向的时间戳要按动作分开取`() {
        // 取全局最大值会让「解锁后点后备箱」白拉一次车况（2.5 秒预算就这么没了）
        assertTrue(
            "lastDirectionActionAt 没有按动作取时间戳",
            providerCode.contains("lastDirectionActionAt(context, vin, action)"),
        )
        assertTrue(
            "车窗档位的时间戳没进判向依据",
            providerCode.contains("WindowControl.savedAt(context, vin)"),
        )
    }

    @Test
    fun `并发重画要串行，否则旧帧会盖住新帧`() {
        assertTrue(
            "VehicleWidget.render 没有同步，点击 / 到期恢复 / 刷新三条协程会互相覆盖",
            Regex("""@Synchronized\s*\n\s*fun render\(""").containsMatchIn(widgetCode),
        )
    }
}
