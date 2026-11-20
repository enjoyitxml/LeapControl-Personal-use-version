package com.leapcontrol.app

import com.leapcontrol.app.widget.WidgetStatusText
import com.leapcontrol.app.widget.WidgetStatusText.HintIcon
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
 *
 * 2026-11-20 又加了「每句提示前面挂一颗 10dp 小图标」：这里同时钉死图标映射，
 * 以及「更新时间 / 尚未同步不给图标」这条用户明确提的要求。
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
            hints.map { it.text },
        )
        // 每条都得带自己的图标，且不能是 NONE —— 少接一颗就是「有的状态有图、有的没有」
        assertEquals(
            listOf(
                HintIcon.UNLOCK, HintIcon.TRUNK, HintIcon.WINDOW, HintIcon.AC,
                HintIcon.PREHEAT, HintIcon.SUNSHADE, HintIcon.SENTRY,
            ),
            hints.map { it.icon },
        )
    }

    @Test
    fun `信号读不到或明确是关 一律不写 不猜`() {
        assertEquals(emptyList<WidgetStatusText.Hint>(), WidgetStatusText.openHints(null))
        assertEquals(
            emptyList<WidgetStatusText.Hint>(),
            WidgetStatusText.openHints(
                WidgetStatusText.State(
                    locked = true, acOn = false, trunkOpen = null,
                    windowOpen = false, sunshadeOpen = null, sentryOn = null,
                ),
            ),
        )
    }

    @Test
    fun `多项同时开着折成一句 图标跟着第一条走`() {
        assertNull(WidgetStatusText.hintsLine(emptyList()))

        val one = WidgetStatusText.hintsLine(
            WidgetStatusText.openHints(WidgetStatusText.State(acOn = true)),
        )!!
        assertEquals("空调已开启", one.text)
        assertEquals(HintIcon.AC, one.icon)

        val many = WidgetStatusText.hintsLine(
            WidgetStatusText.openHints(
                WidgetStatusText.State(locked = false, trunkOpen = true, windowOpen = true),
            ),
        )!!
        assertEquals("车辆已解锁 · 等3项", many.text)
        // 多处开着时图标仍取第一条（解锁），不加「数字角标」那种折中
        assertEquals(HintIcon.UNLOCK, many.icon)
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
    // 源码守门：颜色跟字体颜色、图标真的挂上
    // ========================================================================

    @Test
    fun `状态文字与刷新键都取字体颜色 异常才用警示色`() {
        val widget = read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt")
        assertTrue("bindLive 没用纯逻辑层", widget.contains("WidgetStatusText.resolve("))
        assertTrue(
            "状态文字没跟字体颜色走",
            widget.contains(
                "val statusColor = if (line.tone == WidgetStatusText.Tone.WARN) p.warn else p.ink",
            ),
        )
        assertTrue(
            "刷新键没跟字体颜色走",
            widget.contains("R.id.wg_refresh, \"setColorFilter\", p.ink"),
        )
        assertTrue("Palette 里还留着旧的高亮色字段", !widget.contains("val highlight: Int"))
        // 只染状态行，别把五个按键一起染了
        assertTrue(
            "按键高亮仍要走 active",
            widget.contains("tintIcon(views, iconId, if (on) p.active else p.ink)"),
        )
        assertTrue(
            "车况信号没喂全（含充电剩余时长）",
            listOf(
                "sentryOn = it.sentryOn", "batteryPreheatOn = it.batteryPreheatOn",
                "chargeRemainMinutes = it.chargeRemainMinutes",
            ).all { widget.contains(it) },
        )
    }

    @Test
    fun `每类提示都映射到自己的图标资源`() {
        val widget = read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt")
        // 新画的 5 颗
        listOf("ic_hint_charge", "ic_hint_charged", "ic_hint_ok", "ic_hint_warn", "ic_hint_user")
            .forEach { assertTrue("$it 没接到 R.drawable", widget.contains("R.drawable.$it")) }
        // 载具类 8 颗复用按键那套官方线稿（2026-11-20 用户要求「不要加粗」，副本已删）
        listOf(
            "ic_qc_unlock", "ic_qc_lock", "ic_qc_trunk", "ic_qc_window",
            "ic_qc_ac", "ic_qc_preheat", "ic_qc_sunshade", "ic_qc_sentry",
        ).forEach { assertTrue("$it 没接到 R.drawable", widget.contains("R.drawable.$it")) }
        assertTrue("ic_widget_refresh 没接到 R.drawable", widget.contains("R.drawable.ic_widget_refresh"))

        assertTrue(
            "图标没在 bindLive 里绑定",
            widget.contains("bindHintIcon(views, line, statusColor)"),
        )
        // 换图必须排在染色之前：反了某些桌面会拿上一次的滤镜去染新图，出现串色
        val setImage = widget.indexOf("views.setImageViewResource(R.id.wg_hint_icon, res)")
        val setFilter = widget.indexOf("views.setInt(R.id.wg_hint_icon, \"setColorFilter\", color)")
        assertTrue("图标没先设图再染色", setImage in 0 until setFilter)
    }

    @Test
    fun `更新时间这类提示不给图标`() {
        // 用户明确要求：右上角写「12:30 更新」「尚未同步」时前面不要小图标
        assertEquals(
            HintIcon.NONE,
            WidgetStatusText.resolve(
                WidgetStatusText.Input(clock = "12:30", state = WidgetStatusText.State()),
            ).icon,
        )
        assertEquals(
            HintIcon.NONE,
            WidgetStatusText.resolve(WidgetStatusText.Input(state = WidgetStatusText.State())).icon,
        )
        // 但「正在同步…」是加载态，不是时间戳，给刷新箭头
        assertEquals(
            HintIcon.SYNC,
            WidgetStatusText.resolve(WidgetStatusText.Input()).icon,
        )
    }

    @Test
    fun `充电 登录 密码 异常 各自对上图标`() {
        fun icon(input: WidgetStatusText.Input) = WidgetStatusText.resolve(input).icon
        val base = WidgetStatusText.Input(state = WidgetStatusText.State())

        assertEquals(HintIcon.CHARGE, icon(base.copy(state = WidgetStatusText.State(chargeState = 1))))
        assertEquals(HintIcon.CHARGED, icon(base.copy(state = WidgetStatusText.State(chargeState = 2))))
        assertEquals(HintIcon.USER, icon(base.copy(signedIn = false)))
        assertEquals(
            HintIcon.WARN,
            icon(base.copy(signedIn = false, sessionExpired = true)),
        )
        assertEquals(HintIcon.LOCK, icon(base.copy(pinReady = false)))
        // 控车结果：成功用「这件事」的图标，失败一律警示
        assertEquals(HintIcon.UNLOCK, icon(base.copy(flashText = "已解锁")))
        assertEquals(
            HintIcon.WARN,
            icon(base.copy(flashText = "上锁已下发", flashOk = false)),
        )
        // 控车进行中按动作认
        assertEquals(HintIcon.TRUNK, icon(base.copy(busy = "正在开后备箱…")))
        assertEquals(HintIcon.AC, icon(base.copy(busy = "正在开空调…")))
    }
}

