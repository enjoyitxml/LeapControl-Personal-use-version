package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底部导航「零控 ⇄ 回到顶部」的守门测试。
 *
 * 2026-11-07 用户把方案改了两次：先否掉「通知栏回顶」，再否掉「右下角悬浮球」，
 * 定稿是：首页下拉过一屏后，**底部导航第一格**换成用户给的那张回顶图片，
 * 只留图标、下面不写文案（「回到顶部」只作读屏用的描述），图标在导航栏里高度居中，
 * **点一下默认只回顶、不刷新**；设置 → 首页外观 → 回到顶部按钮 可以选成「回顶 + 刷新」。
 * 不足一屏时恢复「零控」原图标（重复点仍是刷新 + 回顶）。两种状态之间淡入淡出，不许硬切。
 *
 * 这里最要防的是「用状态当触发器」：MainScreen 的 when(tab) 会重建首页子树，
 * 状态式 tick 在切回首页时会重放上一次动作。
 */
class HomeScrollTopTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun ui(name: String) =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/$name.kt").readText()

    @Test
    fun `跨层动作是一次性事件，不是会被重放的状态`() {
        val actions = ui("HomeActions")
        assertTrue("刷新事件缺失", actions.contains("fun refresh()"))
        assertTrue("回顶事件缺失", actions.contains("fun scrollToTop()"))
        assertTrue("刷新流缺失", actions.contains("val refreshEvents: SharedFlow<Unit>"))
        assertTrue("回顶流缺失", actions.contains("val scrollTopEvents: SharedFlow<Unit>"))
        assertTrue("缺少无 replay 的 SharedFlow", actions.contains("MutableSharedFlow<Unit>(extraBufferCapacity = 1)"))
        assertFalse("事件带了 replay 会在切 Tab 回来时重放", actions.contains("replay ="))
        assertFalse("事件带了 replay 会在切 Tab 回来时重放", actions.contains("MutableStateFlow"))

        val home = ui("HomeScreen")
        assertFalse("首页又改回状态式 tick 了", home.contains("scrollToTopTick"))
        assertFalse("首页又改回状态式 tick 了", home.contains("refreshTick"))
        assertTrue("首页没订阅刷新事件", home.contains("HomeActions.refreshEvents.collect"))
        assertTrue("首页没订阅回顶事件", home.contains("HomeActions.scrollTopEvents.collect"))
    }

    @Test
    fun `回顶按钮默认只回顶，设置开启才顺带刷新`() {
        val main = ui("MainScreen")
        assertTrue("没有判断「点的是不是当前 tab」", main.contains("if (target == tab)"))
        assertTrue("没有限定只在零控格生效", main.contains("if (target == 0)"))
        assertTrue("没读设置里的回顶模式", main.contains("val backToTopWithRefresh by runtime.backToTopWithRefresh"))
        assertTrue(
            "回顶图标态没按设置决定要不要刷新",
            main.contains("if (!dockBackToTop || backToTopWithRefresh)"),
        )
        assertEquals("刷新调用点不唯一", 1, main.split("HomeActions.refresh()").size - 1)
        assertEquals("回顶调用点不唯一", 1, main.split("HomeActions.scrollToTop()").size - 1)
        val home = ui("HomeScreen")
        assertTrue("刷新没走用户主动刷新的口径", home.contains("refresh(force = true, silent = false)"))
    }

    @Test
    fun `设置里有回顶按钮的二选一，默认落盘值是不刷新`() {
        val runtime = ui("AppRuntime")
        assertTrue("设置项没落盘", runtime.contains("namingPrefs.getBoolean(\"back_to_top_with_refresh\", false)"))
        assertTrue("默认不是「只回顶」", runtime.contains("getBoolean(\"back_to_top_with_refresh\", false)"))
        assertTrue("缺少写设置的入口", runtime.contains("fun setBackToTopWithRefresh(withRefresh: Boolean)"))
        assertTrue("设置值改动没写回 prefs", runtime.contains("putBoolean(\"back_to_top_with_refresh\", withRefresh)"))

        val settings = ui("SettingsScreen")
        assertTrue("设置页没订阅这个开关", settings.contains("val backToTopWithRefresh by runtime.backToTopWithRefresh"))
        assertTrue("设置里没有回到顶部按钮这一行", settings.contains("title = \"回到顶部按钮\""))
        assertTrue("缺少「只回到顶部」选项", settings.contains("label = \"只回到顶部\""))
        assertTrue("缺少「回到顶部 + 刷新」选项", settings.contains("label = \"回到顶部 + 刷新\""))
        assertTrue("选项没写回 runtime", settings.contains("runtime.setBackToTopWithRefresh(true)"))
        assertTrue("选项没写回 runtime", settings.contains("runtime.setBackToTopWithRefresh(false)"))
    }

    @Test
    fun `右下角悬浮球已删除，超过一屏只上报一次布尔值`() {
        val home = ui("HomeScreen")
        assertFalse("右下角悬浮球又回来了", home.contains("AnimatedVisibility"))
        assertFalse("右下角悬浮球又回来了", home.contains("Alignment.BottomEnd"))
        assertFalse("旧的显隐状态没删干净", home.contains("showBackToTop"))
        assertTrue("没有记录可视区高度", home.contains("onGloballyPositioned { viewportPx = it.size.height.toFloat() }"))
        assertTrue(
            "显隐阈值不是「下拉过一屏」",
            home.contains("viewportPx > 0f && homeScrollState.value >= viewportPx"),
        )
        assertTrue("首页没把阈值上报给底部导航", home.contains("onPastOneScreenChange(it)"))
        assertTrue("阈值变化没去重，滚动会拖着 MainScreen 反复重组", home.contains(".distinctUntilChanged()"))
        assertEquals(
            "首页只该剩事件订阅这一处回顶滚动",
            1,
            home.split("homeScrollState.animateScrollTo(0)").size - 1,
        )
    }

    @Test
    fun `超过一屏时导航第一格换成用户给的图片，且不写文案`() {
        val main = ui("MainScreen")
        assertTrue("没引用用户给的回顶图片资源", main.contains("R.drawable.ic_nav_back_to_top"))
        assertTrue("读屏用的「回到顶部」描述丢了", main.contains("\"回到顶部\""))
        assertTrue("图片被主题色染掉了", main.contains("tintIcon = false"))
        assertTrue("图标下面又在写字了", main.contains("showLabel = false"))
        assertTrue("没用上 125% 的回顶图标尺寸", main.contains("iconSize = BACK_TO_TOP_ICON_SIZE"))
        assertTrue("没按超过一屏切换", main.contains("val dockBackToTop = tab == 0 && homeOnMainPage && homePastOneScreen"))
        assertTrue("首页没把阈值传上来", main.contains("onPastOneScreenChange = { homePastOneScreen = it }"))
        assertTrue("切到别页没清掉回顶态", main.contains("if (tab != 0) homePastOneScreen = false"))

        val dock = ui("UiComponents")
        assertTrue("NavItem 缺少原图开关", dock.contains("tintIcon: Boolean = true"))
        assertTrue("NavItem 缺少无文案开关", dock.contains("showLabel: Boolean = true"))
        assertTrue("NavItem 缺少逐格图标尺寸", dock.contains("iconSize: Dp = 26.dp"))
        assertTrue(
            "回顶图标不是「36dp 的 125%」了",
            dock.contains("BACK_TO_TOP_ICON_SIZE: Dp = 36.dp * 1.25f"),
        )
        assertTrue("NavItem 没有深色版资源位", dock.contains("iconResDark: Int? = null"))
        assertTrue("DockBar 没按明暗挑资源", dock.contains("if (C.isDark) current.iconResDark else null"))
        assertTrue(
            "MainScreen 没把深色版素材传给导航",
            main.contains("iconResDark = R.drawable.ic_nav_back_to_top_dark"),
        )
        assertTrue("DockBar 没有按逐格尺寸画图标", dock.contains("Modifier.size(iconSize)"))
        assertTrue("DockBar 没有按开关隐藏整行文案", dock.contains("if (current.showLabel) {"))
        assertFalse(
            "无文案的格子又用空格占了一行，图标就居中不了了",
            dock.contains("else \" \""),
        )
        assertTrue("DockBar 没有原样显示图片资源的分支", dock.contains("iconId != null && !current.tintIcon -> Image("))
        assertTrue("DockBar 没把图标垂直居中", dock.contains("verticalArrangement = Arrangement.Center"))
        assertTrue(
            "过渡里的格子没撑满整格，点击区会缩到图标那么宽",
            dock.contains("Modifier.fillMaxWidth().fillMaxHeight()"),
        )

        val icon = File(projectDir, "app/src/main/res/drawable-nodpi/ic_nav_back_to_top.png")
        assertTrue("用户给的回顶图片资源不见了", icon.isFile)
    }

    @Test
    fun `两种状态之间是淡入淡出过渡，图标尺寸同步平滑缩放`() {
        val dock = ui("UiComponents")
        assertTrue("切换又变回硬切了", dock.contains("Crossfade("))
        assertTrue("过渡没绑定导航项状态", dock.contains("targetState = item"))
        assertTrue("缺少 260ms 过渡时长", dock.contains("durationMillis = 260"))
        assertTrue("图标尺寸没有跟着过渡缩放", dock.contains("animateDpAsState("))
        assertTrue("过渡用的尺寸没绑到目标 NavItem", dock.contains("targetValue = item.iconSize"))
        assertTrue("淡入淡出没有缓动", dock.contains("FastOutSlowInEasing"))
    }

    @Test
    fun `浅色深色两版回顶图标都是带 alpha 的透明底 PNG`() {
        // 用户给的第二版素材里白箭头 / TOP 都是「挖空的透明洞」，圆边还有一圈硬白描边。
        // 两版都由 8 倍超采样重新合成：圆内填实、圆外四角保持透明，
        // 换成不带透明通道的图，深色底栏上会糊出一个白方块。
        for (name in listOf("ic_nav_back_to_top", "ic_nav_back_to_top_dark")) {
            val icon = File(projectDir, "app/src/main/res/drawable-nodpi/$name.png")
            assertTrue("$name 资源不见了", icon.isFile)
            val bytes = icon.readBytes()
            assertTrue(
                "$name 不是 PNG",
                bytes.take(4).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
            )
            // PNG IHDR 的第 25 字节是 color type：6 = RGBA。
            assertEquals("$name 缺 alpha 通道", 6, bytes[25].toInt() and 0xFF)
            // 直接从 IHDR 读宽高（16..23 大端），不依赖 javax.imageio ——
            // Android 的 local unit test 里那个包是 stub，用不了。
            assertEquals("$name 不是超采样后的 400px 图", 400, pngInt(bytes, 16))
            assertEquals("$name 不是正方形", 400, pngInt(bytes, 20))
        }
    }

    /**
     * 2026-11-08 用户两条口径：浅色版「上一轮调浅 100%，这次回补 50%」——
     * 纯黑圆 → #A3ACB6 的中点，不能再退回上一版那么浅；深色版「白色描边删除」，
     * 源图圆外那圈硬白描边（400px 图里 r≈196~202）必须整圈透明。
     * 两版都是 8bit RGBA PNG，这里只用 JDK 解码 —— Android 的 local unit test
     * 里没有 javax.imageio。
     */
    @Test
    fun `浅色圆不再过浅，深色版圆外没有白色描边`() {
        val light = decodeRgba(iconBytes("ic_nav_back_to_top"))
        val dark = decodeRgba(iconBytes("ic_nav_back_to_top_dark"))
        val lightCenter = (
            (light[(200 * 400 + 200) * 4].toInt() and 0xFF) +
                (light[(200 * 400 + 200) * 4 + 1].toInt() and 0xFF) +
                (light[(200 * 400 + 200) * 4 + 2].toInt() and 0xFF)
            ) / 3
        assertTrue(
            "浅色圆又被调回过浅的灰了（上一版 #A3ACB6 平均亮度 172）",
            lightCenter < 130,
        )
        assertTrue("浅色圆不该退回到纯黑（用户之前嫌太黑抢镜）", lightCenter > 40)

        var brightRing = 0
        for (y in 0 until 400) {
            for (x in 0 until 400) {
                val radius = kotlin.math.hypot(x - 199.5, y - 199.5)
                if (radius < 199.0 || radius > 210.0) continue
                val at = (y * 400 + x) * 4
                if ((dark[at + 3].toInt() and 0xFF) <= 40) continue
                val lum = (
                    (dark[at].toInt() and 0xFF) +
                        (dark[at + 1].toInt() and 0xFF) +
                        (dark[at + 2].toInt() and 0xFF)
                    ) / 3
                if (lum > 150) brightRing++
            }
        }
        assertEquals("深色版圆外又出现白色描边了", 0, brightRing)
    }

    private fun iconBytes(name: String) =
        File(projectDir, "app/src/main/res/drawable-nodpi/$name.png").readBytes()

    /** 解一张 8bit RGBA、非隔行的 PNG，返回宽 × 高 × 4 的原始像素。 */
    private fun decodeRgba(bytes: ByteArray): ByteArray {
        val idat = mutableListOf<ByteArray>()
        var width = 0
        var height = 0
        var off = 8
        while (off + 8 <= bytes.size) {
            val len = pngInt(bytes, off)
            val type = String(bytes, off + 4, 4, Charsets.US_ASCII)
            if (type == "IHDR") {
                width = pngInt(bytes, off + 8)
                height = pngInt(bytes, off + 12)
            }
            if (type == "IDAT") idat += bytes.copyOfRange(off + 8, off + 8 + len)
            off += 12 + len
            if (type == "IEND") break
        }
        require(width == 400 && height == 400) { "回顶图标应是 400×400" }
        val compressed = ByteArray(idat.sumOf { it.size })
        var cursor = 0
        for (chunk in idat) {
            chunk.copyInto(compressed, cursor)
            cursor += chunk.size
        }
        val stride = width * 4
        val raw = ByteArray((stride + 1) * height)
        val inflater = java.util.zip.Inflater()
        inflater.setInput(compressed)
        var filled = 0
        while (filled < raw.size && !inflater.finished()) {
            val n = inflater.inflate(raw, filled, raw.size - filled)
            if (n == 0) break
            filled += n
        }
        inflater.end()
        require(filled == raw.size) { "PNG 像素数据不完整" }

        val out = ByteArray(stride * height)
        var p = 0
        for (y in 0 until height) {
            val filter = raw[p++].toInt() and 0xFF
            for (x in 0 until stride) {
                val v = raw[p++].toInt() and 0xFF
                val a = if (x >= 4) out[y * stride + x - 4].toInt() and 0xFF else 0
                val b = if (y > 0) out[(y - 1) * stride + x].toInt() and 0xFF else 0
                val c = if (y > 0 && x >= 4) out[(y - 1) * stride + x - 4].toInt() and 0xFF else 0
                val recon = when (filter) {
                    0 -> v
                    1 -> v + a
                    2 -> v + b
                    3 -> v + (a + b) / 2
                    4 -> v + paeth(a, b, c)
                    else -> error("不支持的 PNG filter: $filter")
                }
                out[y * stride + x] = (recon and 0xFF).toByte()
            }
        }
        return out
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    /** 读 PNG 里 4 字节大端整数（IHDR 的宽 / 高）。 */
    private fun pngInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
