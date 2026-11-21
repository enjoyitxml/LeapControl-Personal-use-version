package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 哨兵模式方案：**原厂默认，副厂手动开**（2026-11-21）。
 *
 * 背景：后加装的哨兵能执行 `sentryOn`，但车端永远不会把「已开启」写进信号，
 * 原厂那套「等回执 + 读车况求证」在它身上必然失败 —— 用户点开只看到「已下发」，
 * 右上角也不出现「哨兵已开启」。
 *
 * 这个文件守两件事：
 * 1. 纯逻辑（显示 / 判向 / 跳过求证）按方案分叉，原厂口径一点不变；
 * 2. 源码里三处接线不能漏：默认值必须是原厂、下发链路要在求证之前短路、
 *    小组件判向不能再去对车况（那台车根本没有这个信号）。
 */
class SentrySchemeTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun source(path: String): String = File(projectDir, path).readText()

    // ---------------- 纯逻辑 ----------------

    @Test
    fun `原厂方案显示以车况为准 不看本地`() {
        assertFalse(SentrySchemeRules.usesLocalState(SentryScheme.FACTORY))
        // 车况报开着 → 开着；报关着 → 关着，本地记录一律不参与
        assertEquals(true, SentrySchemeRules.onForDisplay(SentryScheme.FACTORY, statusOn = true, localOn = false))
        assertEquals(false, SentrySchemeRules.onForDisplay(SentryScheme.FACTORY, statusOn = false, localOn = true))
        // 读不到就还是读不到（不能凭空说开）
        assertEquals(null, SentrySchemeRules.onForDisplay(SentryScheme.FACTORY, statusOn = null, localOn = true))
    }

    @Test
    fun `副厂方案显示只看本地 车况连 false 都不采纳`() {
        assertTrue(SentrySchemeRules.usesLocalState(SentryScheme.AFTERMARKET))
        // 后加装车的 sentryOn 常常恒为 false，采纳它会把刚开的哨兵打回「没开」
        assertEquals(true, SentrySchemeRules.onForDisplay(SentryScheme.AFTERMARKET, statusOn = false, localOn = true))
        assertEquals(false, SentrySchemeRules.onForDisplay(SentryScheme.AFTERMARKET, statusOn = true, localOn = false))
    }

    @Test
    fun `副厂方案 开着就发关 关着就发开`() {
        assertEquals("sentryOff", SentrySchemeRules.nextAftermarket(localOn = true))
        assertEquals("sentryOn", SentrySchemeRules.nextAftermarket(localOn = false))
    }

    @Test
    fun `只有副厂的开关哨兵跳过回执与求证`() {
        assertTrue(SentrySchemeRules.skipsReceipt(SentryScheme.AFTERMARKET, "sentryOn"))
        assertTrue(SentrySchemeRules.skipsReceipt(SentryScheme.AFTERMARKET, "sentryOff"))
        assertFalse(SentrySchemeRules.skipsReceipt(SentryScheme.FACTORY, "sentryOn"))
        assertFalse(SentrySchemeRules.skipsReceipt(SentryScheme.FACTORY, "sentryOff"))
        // 别的指令一点都不受影响
        assertFalse(SentrySchemeRules.skipsReceipt(SentryScheme.AFTERMARKET, "lock"))
        assertFalse(SentrySchemeRules.skipsReceipt(SentryScheme.AFTERMARKET, "trunkOpen"))
    }

    @Test
    fun `跳过求证的结论是完成式`() {
        // 状态就是本地那一笔，没有「车端还没回话」这回事
        assertEquals("哨兵已开启", SentrySchemeRules.doneText("sentryOn"))
        assertEquals("哨兵已关闭", SentrySchemeRules.doneText("sentryOff"))
    }

    // ---------------- 源码接线 ----------------

    @Test
    fun `默认必须是原厂方案`() {
        val store = source("app/src/main/java/com/leapcontrol/app/SentryScheme.kt")
        // 读不到 / 存坏了都落到 FACTORY；默认值一旦改成副厂，
        // 等于给所有原厂车凭空写一个「哨兵已开启」
        assertTrue(
            "SentrySchemeStore 的兜底分支不再返回 FACTORY",
            Regex("""else -> SentryScheme\.FACTORY""").containsMatchIn(store),
        )
        assertFalse(
            "SentrySchemeStore 里出现了把 AFTERMARKET 当默认值的写法",
            Regex("""else -> SentryScheme\.AFTERMARKET""").containsMatchIn(store),
        )
    }

    @Test
    fun `App 与小组件的下发链路都在求证之前短路`() {
        val runtime = source("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt")
        val skips = Regex("""SentrySchemeRules\.skipsReceipt\(SentrySchemeStore\.get\(appContext\), commandName\)""")
        // control() 与 controlForWidget() 各一处：漏掉任何一处，那条入口就会白等 20 秒 / 3.5 秒
        assertEquals(
            "App 与小组件两条下发链路没有同时短路副厂哨兵",
            2,
            skips.findAll(runtime).count(),
        )
        // 短路必须发生在轮询回执之前：先 return 才不会去 web 端等那 20 秒
        val controlStart = runtime.indexOf("suspend fun control(")
        val widgetStart = runtime.indexOf("suspend fun controlForWidget(")
        assertTrue("control() 里没有副厂短路", runtime.indexOf("SentrySchemeRules.skipsReceipt", controlStart) in controlStart..runtime.length)
        assertTrue("controlForWidget() 里没有副厂短路", runtime.indexOf("SentrySchemeRules.skipsReceipt", widgetStart) > widgetStart)
    }

    @Test
    fun `小组件判向与显示都按方案分叉`() {
        val provider = source("app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt")
        assertTrue(
            "小组件判向没有副厂分支（会去等一个永远不存在的车况信号）",
            provider.contains("SentrySchemeRules.usesLocalState(SentrySchemeStore.get(context))"),
        )
        assertTrue(
            "副厂哨兵仍在拉车况判向，白等 2.5 秒",
            provider.contains("aftermarketSentry -> null"),
        )
        assertTrue(
            "副厂哨兵仍会跑车况求证，可能写出与本地记录相反的结论",
            provider.contains("if (!aftermarketSentry) confirmOutcomeLater(context, runtime, command)"),
        )
        val widget = source("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt")
        assertTrue(
            "小组件右上角没有按方案取哨兵状态",
            widget.contains("sentryOnOverride(context)"),
        )
    }

    @Test
    fun `首页右上角与按钮高亮都吃本地状态`() {
        val home = source("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt")
        // 右上角：副厂传入本地值，原厂传 null（保持只看车况）
        assertTrue(
            "首页右上角没有把副厂本地态传给状态区",
            Regex("""sentryOn = if \(SentrySchemeRules\.usesLocalState\(sentryScheme\)\) sentryLocalOn else null""")
                .findAll(home).count() >= 2,
        )
        assertTrue(
            "首页哨兵按钮判向没有按副厂本地态取反",
            home.contains("SentrySchemeRules.nextAftermarket(sentryLocalOn)"),
        )
    }

    @Test
    fun `副厂首次开启的说明必须提到后视镜`() {
        val home = source("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt")
        assertTrue(
            "说明弹窗没写「自行观察车辆后视镜」—— 没有车端回执时，这是用户唯一的确认手段",
            home.contains("需自行观察车辆后视镜是否已打开"),
        )
        val settings = source("app/src/main/java/com/leapcontrol/app/ui/SettingsScreen.kt")
        assertTrue(
            "设置页的方案说明没提后视镜",
            settings.contains("需自行观察车辆后视镜是否已打开"),
        )
        assertTrue(
            "设置页没有「哨兵模式方案」这一行",
            settings.contains("\"哨兵模式方案\""),
        )
    }
}
