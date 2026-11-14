package com.leapcontrol.app

import com.leapcontrol.app.widget.WidgetStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 小组件右上角提示的**纯逻辑 + 接线守门**。
 *
 * 2026-11-13 用户反馈：右上角只认「未锁车 / 充电中」，空调、后备箱、车窗、电池预热、
 * 遮阳帘开着时一律写成「12:30 更新」，充电时也看不到剩余时长。这里把文案折行规则、
 * 优先级、以及「高亮色真的被用到 / 设置里真的能改」全部钉死 —— 这类改动编译得过，
 * 但少接一处就表现为「卡片上什么都不变」。
 */
class WidgetStatusTextTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(path: String) = File(projectDir, path).readText()

    // ========================================================================
    // 开着的功能：文案 / 顺序 / 折叠
    // ========================================================================

    @Test
    fun `七类已开启状态都要有提示 且顺序是安全优先`() {
        val hints = WidgetStatusText.openHints(
            WidgetStatusText.State(
                locked = false,
                acOn = true,
                trunkOpen = true,
                windowOpen = true,
                batteryPreheatOn = true,
                sunshadeOpen = true,
                sentryOn = true,
            ),
        )
        assertEquals(
            listOf(
                "车辆已解锁", "后备箱已开启", "车窗已开启", "空调已开启",
                "电池预热已开启", "遮阳帘已开启", "哨兵已开启",
            ),
            hints,
        )
    }

    @Test
    fun `信号读不到或明确是关 一律不写 不猜`() {
        assertEquals(emptyList<String>(), WidgetStatusText.openHints(null))
        assertEquals(
            emptyList<String>(),
            WidgetStatusText.openHints(
                WidgetStatusText.State(
                    locked = true, acOn = false, trunkOpen = null,
                    windowOpen = false, sunshadeOpen = null, sentryOn = null,
                ),
            ),
        )
    }

    @Test
    fun `多项同时开着折成一句 不让桌面截半句`() {
        assertNull(WidgetStatusText.hintsText(emptyList()))
        assertEquals("空调已开启", WidgetStatusText.hintsText(listOf("空调已开启")))
        assertEquals(
            "车辆已解锁 · 等3项",
            WidgetStatusText.hintsText(listOf("车辆已解锁", "后备箱已开启", "车窗已开启")),
        )
    }

    // ========================================================================
    // 充电剩余时长：短写法
    // ========================================================================

    @Test
    fun `只有充电中才写剩余时长 且分钟数走短写法`() {
        val charging = WidgetStatusText.State(chargeState = 1)
        assertEquals("即将充满", WidgetStatusText.chargeRemain(charging.copy(chargeRemainMinutes = 0)))
        assertEquals("剩23分", WidgetStatusText.chargeRemain(charging.copy(chargeRemainMinutes = 23)))
        assertEquals("剩1时23分", WidgetStatusText.chargeRemain(charging.copy(chargeRemainMinutes = 83)))
        assertEquals("剩2时", WidgetStatusText.chargeRemain(charging.copy(chargeRemainMinutes = 120)))
        assertNull(WidgetStatusText.chargeRemain(charging))
        assertEquals(
            "剩 1 小时 23 分",
            WidgetStatusText.chargeRemain(charging.copy(chargeTimeLeft = " 1 小时 23 分 ")),
        )
        // 充电完成 / 未插枪 都不该再写「剩…」
        assertNull(WidgetStatusText.chargeRemain(WidgetStatusText.State(chargeState = 2)))
        assertNull(WidgetStatusText.chargeRemain(null))
    }

    @Test
    fun `充电中带剩余时长且不再挂更新时间`() {
        val line = WidgetStatusText.resolve(
            WidgetStatusText.Input(
                clock = "12:30",
                state = WidgetStatusText.State(chargeState = 1, chargeRemainMinutes = 83),
            ),
        )
        assertEquals("充电中 · 剩1时23分", line.text)
        assertEquals(WidgetStatusText.Tone.HIGHLIGHT, line.tone)
    }

    @Test
    fun `读不到剩余时长时仍带更新时间`() {
        val line = WidgetStatusText.resolve(
            WidgetStatusText.Input(
                clock = "12:30",
                state = WidgetStatusText.State(chargeState = 1),
            ),
        )
        assertEquals("充电中 · 12:30", line.text)
    }

    @Test
    fun `充电完成即便 charging 仍为 true 也不说充电中`() {
        val done = WidgetStatusText.Input(
            state = WidgetStatusText.State(chargeState = 2, charging = true),
        )
        assertEquals("充电完成", WidgetStatusText.resolve(done).text)
        assertEquals("已充满", WidgetStatusText.resolve(done.copy(fullConfirmed = true)).text)
        assertFalse(WidgetStatusText.isCharging(done.state))
        // chargeState 缺失时才认 charging 兜底
        assertTrue(WidgetStatusText.isCharging(WidgetStatusText.State(charging = true)))
    }

    // ========================================================================
    // 优先级与色调
    // ========================================================================

    @Test
    fun `常态写更新时间 有开着的功能时改成高亮提示`() {
        // 车况已经同步到、但没有任何信号要提示 —— 才是「12:30 更新」这一常态
        val plain = WidgetStatusText.resolve(
            WidgetStatusText.Input(clock = "12:30", state = WidgetStatusText.State()),
        )
        assertEquals("12:30 更新", plain.text)
        assertEquals(WidgetStatusText.Tone.PLAIN, plain.tone)

        val hinted = WidgetStatusText.resolve(
            WidgetStatusText.Input(
                clock = "12:30",
                state = WidgetStatusText.State(locked = true, acOn = true),
            ),
        )
        assertEquals("空调已开启", hinted.text)
        assertEquals(WidgetStatusText.Tone.HIGHLIGHT, hinted.tone)
    }

    @Test
    fun `busy 登录失效 未设密码 控车结果 都排在车况提示之前`() {
        val base = WidgetStatusText.Input(
            clock = "12:30",
            state = WidgetStatusText.State(trunkOpen = true),
        )
        assertEquals("正在上锁…", WidgetStatusText.resolve(base.copy(busy = "正在上锁…")).text)
        assertEquals("点此登录", WidgetStatusText.resolve(base.copy(signedIn = false)).text)
        assertEquals(
            WidgetStatusText.Tone.WARN,
            WidgetStatusText.resolve(base.copy(signedIn = false, sessionExpired = true)).tone,
        )
        assertEquals("未设控车密码", WidgetStatusText.resolve(base.copy(pinReady = false)).text)
        assertEquals("车辆已上锁", WidgetStatusText.resolve(base.copy(flashText = "车辆已上锁")).text)
        assertEquals(
            WidgetStatusText.Tone.WARN,
            WidgetStatusText.resolve(base.copy(flashText = "上锁已下发", flashOk = false)).tone,
        )
        assertEquals("正在同步…", WidgetStatusText.resolve(WidgetStatusText.Input()).text)
    }

    @Test
    fun `充电中的控车结果仍补一句充电中`() {
        val line = WidgetStatusText.resolve(
            WidgetStatusText.Input(
                flashText = "空调已开启",
                state = WidgetStatusText.State(chargeState = 1),
            ),
        )
        assertEquals("空调已开启 · 充电中", line.text)
    }

    @Test
    fun `没有时钟也没有状态时不编造数据`() {
        val line = WidgetStatusText.resolve(WidgetStatusText.Input(state = WidgetStatusText.State()))
        assertEquals("尚未同步", line.text)
        assertEquals(WidgetStatusText.Tone.PLAIN, line.tone)
    }

    // ========================================================================
    // 源码守门：高亮色真的被接上、设置页真的能改
    // ========================================================================

    @Test
    fun `右上角文字走 WidgetStatusText 且高亮色来自设置`() {
        val widget = read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt")
        assertTrue("bindLive 没用纯逻辑层", widget.contains("WidgetStatusText.resolve("))
        assertTrue("状态行没用可自定义的高亮色", widget.contains("Tone.HIGHLIGHT -> p.highlight"))
        assertTrue("Palette 少了 highlight 字段", widget.contains("val highlight: Int"))
        // 只染状态行，别把五个按键一起染了
        assertTrue("按键高亮仍要走 active", widget.contains("tintIcon(views, iconId, if (on) p.active else p.ink)"))
        assertTrue(
            "车况信号没喂全（含充电剩余时长）",
            listOf("sentryOn = it.sentryOn", "batteryPreheatOn = it.batteryPreheatOn",
                "chargeRemainMinutes = it.chargeRemainMinutes").all { widget.contains(it) },
        )
    }

    @Test
    fun `高亮色三套版式都能设 且默认不落盘`() {
        val style = read("app/src/main/java/com/leapcontrol/app/widget/WidgetStyle.kt")
        assertTrue("没有 highlight 偏好", style.contains("fun highlight(context: Context): Int?"))
        assertTrue("没有 setHighlight", style.contains("fun setHighlight"))
        assertTrue("恢复默认颜色没清高亮色", style.contains("remove(KEY_HIGHLIGHT)"))
        assertTrue("没有版式默认色", style.contains("fun defaultHighlight(context: Context): Int"))

        val settings = read("app/src/main/java/com/leapcontrol/app/ui/WidgetSettings.kt")
        assertTrue("设置页没有「状态提示高亮色」", settings.contains("title = \"状态提示高亮色\""))
        val rowAt = settings.indexOf("title = \"状态提示高亮色\"")
        val transparentOnlyAt = settings.indexOf("if (style == WidgetStyle.STYLE_T)")
        assertTrue("高亮色被塞进了「仅全透明版式」分支，另外两套改不了", rowAt < transparentOnlyAt)
        assertTrue("选色没落盘", settings.contains("WidgetStyle.setHighlight(context, color)"))
        assertTrue("没有恢复默认提示色", settings.contains("WidgetStyle.resetHighlight(context)"))
    }
}
