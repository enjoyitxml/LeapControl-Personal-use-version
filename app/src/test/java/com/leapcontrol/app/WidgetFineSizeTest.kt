package com.leapcontrol.app

import com.leapcontrol.app.widget.WidgetMetrics
import com.leapcontrol.app.widget.WidgetStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件「细节微调」（2026-11-13 需求）：控车图标、右上角更新时间与刷新键、左上角车标
 * 能各自调大小，**但不能影响排版显示效果**。
 *
 * 真机上这三根滑杆只落在 RemoteViews 的 padding / textSize 上，单测既画不出卡片也调不到
 * 桌面进程，所以钉死四件事：
 *
 * 1. 换算纯函数：倍率 1.0 严格等于现状（老设备零回归），放大缩小都夹在容器里；
 * 2. 六份布局的**容器**（38/42dp 按键行、20dp 刷新格、18dp 车标格）与点击区一个都不许动，
 *    图标仍是 match_parent + fitCenter —— 这是「撑不破排版」的物理前提；
 * 3. `wg_time` 的 maxWidth / ellipsize / maxLines 必须在，否则字一放大就把刷新键挤出去；
 * 4. 渲染层不许改用 `setViewLayoutWidth / Height`（API 31，minSdk 26 会静默失效）。
 */
class WidgetFineSizeTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(path: String) = File(projectDir, path).readText()

    /** 去掉 KDoc / 行注释，只留真代码 —— 注释里提到 API 31 的方法名不该被误判成用了它。 */
    private fun codeOnly(src: String): String {
        val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(src, "")
        return noBlock.lineSequence().map { it.substringBefore("//") }.joinToString("\n")
    }

    private val layouts = listOf(
        "widget_a.xml" to 38,
        "widget_a_wide.xml" to 42,
        "widget_d.xml" to 38,
        "widget_d_wide.xml" to 42,
        "widget_t.xml" to 38,
        "widget_t_wide.xml" to 42,
    )

    private val widgetSrc = codeOnly(
        read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt"),
    )
    private val settingsSrc = codeOnly(
        read("app/src/main/java/com/leapcontrol/app/ui/WidgetSettings.kt"),
    )
    private val styleSrc = codeOnly(
        read("app/src/main/java/com/leapcontrol/app/widget/WidgetStyle.kt"),
    )

    // ---------------- 换算：零回归 ----------------

    @Test
    fun `倍率 1_0 时五个动作与刷新键的留白严格等于现状`() {
        // 三个基准留白：空调 8、鸣笛 4、其余 5.5（见 VehicleWidget.BASE_ICON_PAD）
        listOf(4f, 5.5f, 8f).forEach { base ->
            assertEquals(base, WidgetMetrics.iconPaddingDp(base, 38f, 1f), 0.001f)
            assertEquals(base, WidgetMetrics.iconPaddingDp(base, 42f, 1f), 0.001f)
        }
        assertEquals(2.5f, WidgetMetrics.refreshPaddingDp(1f), 0.001f)
        assertEquals(10f, WidgetMetrics.timeSizeDp(10f, 1f, 1f), 0.001f)
        // 鸣笛那 1.5dp 上移也原样保留
        assertEquals(1.5f, WidgetMetrics.liftDp(1.5f, 1f, 4f), 0.001f)
    }

    @Test
    fun `倍率 1_0 时车标仍是 14dp 且左缘贴住格子左边`() {
        // 旧版格子 = 图形 = 14dp，所以 1.0 必须严格回到 14dp，否则升级后车标突然变大
        assertEquals(14f, WidgetMetrics.logoGraphicDp(1f), 0.001f)
        val pad = WidgetMetrics.logoPad(1f)
        assertEquals("左边留白不许变，否则车名会被推走", 0f, pad.leftDp, 0.001f)
        // 18dp 格子 − 14dp 图形 = 4dp：全给右边，上下各 2dp
        assertEquals(4f, pad.rightDp, 0.001f)
        assertEquals(2f, pad.topDp, 0.001f)
        assertEquals(2f, pad.bottomDp, 0.001f)
        // 横向占位与旧版一致：18 + 1（marginEnd）= 14 + 5
        assertEquals(
            WidgetMetrics.LOGO_BOX_DP + 1f,
            14f + 5f,
            0.001f,
        )
    }

    @Test
    fun `放大确实变大缩小确实变小，且刷新键同步`() {
        fun graphic(base: Float, scale: Float) =
            WidgetMetrics.iconGraphicDp(38f, WidgetMetrics.iconPaddingDp(base, 38f, scale))
        val mid = graphic(5.5f, 1f)
        assertTrue("放大没生效", graphic(5.5f, 1.25f) > mid)
        assertTrue("缩小没生效", graphic(5.5f, 0.8f) < mid)
        // 空调 / 鸣笛那两个「不齐」的相对修正不能因为缩放被抹平
        assertTrue(graphic(8f, 1.25f) < graphic(5.5f, 1.25f))
        assertTrue(graphic(4f, 1.25f) > graphic(5.5f, 1.25f))

        val rMid = WidgetMetrics.iconGraphicDp(
            WidgetMetrics.REFRESH_BOX_DP, WidgetMetrics.refreshPaddingDp(1f),
        )
        val rBig = WidgetMetrics.iconGraphicDp(
            WidgetMetrics.REFRESH_BOX_DP, WidgetMetrics.refreshPaddingDp(1.25f),
        )
        assertTrue("刷新键放大没生效", rBig > rMid)
        assertTrue("时间字号放大没生效", WidgetMetrics.timeSizeDp(10f, 1f, 1.25f) > 10f)
        assertTrue("时间字号缩小没生效", WidgetMetrics.timeSizeDp(10f, 1f, 0.8f) < 10f)
        // 车标：放大变大、缩小变小，且左缘始终不动
        assertTrue("车标放大没生效", WidgetMetrics.logoGraphicDp(1.25f) > 14f)
        assertTrue("车标缩小没生效", WidgetMetrics.logoGraphicDp(0.8f) < 14f)
        listOf(0.8f, 1f, 1.25f).forEach { s ->
            assertEquals("车标左边留白必须恒 0（scale=$s）", 0f, WidgetMetrics.logoPad(s).leftDp, 0.001f)
        }
    }

    @Test
    fun `任何倍率下图形都待在容器里`() {
        // 连越界值一起测：prefs 可能被手改，读出来虽然有夹取这里也不能算出负留白
        listOf(0.01f, 0.8f, 1f, 1.1f, 1.25f, 5f).forEach { scale ->
            listOf(38f, 42f).forEach { cell ->
                listOf(4f, 5.5f, 8f).forEach { base ->
                    val pad = WidgetMetrics.iconPaddingDp(base, cell, scale)
                    val graphic = WidgetMetrics.iconGraphicDp(cell, pad)
                    assertTrue("scale=$scale cell=$cell base=$base 算出负留白", pad >= 0f)
                    assertTrue("scale=$scale 图形越过容器", graphic <= cell + 0.001f)
                    assertTrue("scale=$scale 图形缩没了", graphic > 0f)
                }
            }
            val pad = WidgetMetrics.refreshPaddingDp(scale)
            val graphic = WidgetMetrics.iconGraphicDp(WidgetMetrics.REFRESH_BOX_DP, pad)
            assertTrue("刷新键算出负留白", pad >= 0f)
            assertTrue("刷新键图形越过 20dp 格子", graphic <= WidgetMetrics.REFRESH_BOX_DP + 0.001f)
            // 车标同理：四边留白都不许为负，图形不许超出 18dp
            val logo = WidgetMetrics.logoPad(scale)
            listOf(logo.leftDp, logo.topDp, logo.rightDp, logo.bottomDp).forEach {
                assertTrue("scale=$scale 车标算出负留白", it >= 0f)
            }
            assertTrue(
                "scale=$scale 车标图形越过 18dp 格子",
                WidgetMetrics.logoGraphicDp(scale) <= WidgetMetrics.LOGO_BOX_DP + 0.001f,
            )
            assertTrue("scale=$scale 车标缩没了", WidgetMetrics.logoGraphicDp(scale) > 0f)
        }
    }

    @Test
    fun `鸣笛上移量放大后不会把上边留白顶成负的`() {
        val pad = WidgetMetrics.iconPaddingDp(4f, 38f, WidgetStyle.MAX_FINE_SCALE)
        val lift = WidgetMetrics.liftDp(1.5f, WidgetStyle.MAX_FINE_SCALE, pad)
        assertTrue(lift <= pad)
        assertTrue(pad - lift >= 0f)
        // 默认倍率下仍是原来的 1.5dp
        assertEquals(1.5f, WidgetMetrics.liftDp(1.5f, 1f, 4f), 0.001f)
    }

    @Test
    fun `时间字号有绝对上限，不会把右上角撑到车模上`() {
        // 大卡 1.45 × 细节 1.25 = 18.1dp，必须被夹回 16dp
        assertEquals(
            WidgetMetrics.TIME_MAX_DP,
            WidgetMetrics.timeSizeDp(10f, 1.45f, 1.25f),
            0.001f,
        )
        assertTrue(WidgetMetrics.timeSizeDp(10f, 0.7f, 0.8f) >= 6f)
        assertTrue(WidgetMetrics.TIME_MAX_DP > 10f)
    }

    // ---------------- 偏好：默认值与恢复 ----------------

    @Test
    fun `三根细节键默认 1_0，老设备不需要迁移`() {
        assertEquals(1.0f, WidgetStyle.DEFAULT_FINE_SCALE, 0.001f)
        assertTrue(WidgetStyle.MIN_FINE_SCALE < 1f)
        assertTrue(WidgetStyle.MAX_FINE_SCALE > 1f)
        assertTrue(
            "控车图标倍率的默认值不是 1.0，老用户升级后图标会突然变样",
            styleSrc.contains("getFloat(KEY_ICON_SCALE, DEFAULT_FINE_SCALE)"),
        )
        assertTrue(
            "右上角倍率的默认值不是 1.0",
            styleSrc.contains("getFloat(KEY_HEADER_SCALE, DEFAULT_FINE_SCALE)"),
        )
        assertTrue(
            "车标倍率的默认值不是 1.0，老用户升级后车标会突然变大",
            styleSrc.contains("getFloat(KEY_LOGO_SCALE, DEFAULT_FINE_SCALE)"),
        )
        assertTrue("写入要夹回范围", styleSrc.contains("putFloat(KEY_ICON_SCALE, fineScale(scale))"))
        assertTrue("写入要夹回范围", styleSrc.contains("putFloat(KEY_HEADER_SCALE, fineScale(scale))"))
        assertTrue("写入要夹回范围", styleSrc.contains("putFloat(KEY_LOGO_SCALE, fineScale(scale))"))
        // 1.25 倍时 14dp → 17.5dp，还在 18dp 格子里：再大就得改 LayoutParams 了
        assertTrue(
            "放大上限已经超出车标格",
            14f * WidgetStyle.MAX_FINE_SCALE <= WidgetMetrics.LOGO_BOX_DP,
        )
    }

    @Test
    fun `恢复默认大小一次清掉四根滑杆`() {
        val body = styleSrc.substring(
            styleSrc.indexOf("fun resetSize"),
            styleSrc.indexOf("fun fineScale"),
        )
        listOf("KEY_INFO_SCALE", "KEY_ICON_SCALE", "KEY_HEADER_SCALE", "KEY_LOGO_SCALE").forEach { key ->
            assertTrue("$key 没被清掉，恢复默认后卡片还是旧的", body.contains("remove($key)"))
        }
    }

    // ---------------- 布局：容器与点击区一个都不动 ----------------

    @Test
    fun `六份布局的按键行、刷新格与点击区尺寸都保持原样`() {
        layouts.forEach { (name, rowDp) ->
            val xml = read("app/src/main/res/layout/$name")
            assertTrue(
                "$name 的按键行高度被改了（微调只能改内容，不能改容器）",
                xml.contains("""android:layout_height="${rowDp}dp""""),
            )
            assertEquals("$name 的点击区数量变了", 6, Regex("""android:clickable="true"""").findAll(xml).count())
            // 五个图标必须仍是 match_parent + fitCenter：容器不变、由 padding 收图形
            listOf("wg_icon_unlock", "wg_icon_lock", "wg_icon_ac", "wg_icon_trunk", "wg_icon_horn")
                .forEach { id ->
                    val tag = tagOf(xml, id)
                    assertTrue(
                        "$name/$id 不再是 match_parent + fitCenter",
                        tag.contains("""android:layout_width="match_parent"""") &&
                            tag.contains("""android:layout_height="match_parent"""") &&
                            tag.contains("""android:scaleType="fitCenter""""),
                    )
                }
            val refresh = tagOf(xml, "wg_refresh")
            assertTrue(
                "$name 的刷新键格子不再是 20dp",
                refresh.contains("""android:layout_width="20dp"""") &&
                    refresh.contains("""android:layout_height="20dp"""") &&
                    refresh.contains("""android:scaleType="fitCenter""""),
            )
            // 车标格 18dp 是「容器」：图形靠 padding 收放，格子本身不许被改来改去
            val logo = tagOf(xml, "wg_logo")
            assertTrue(
                "$name 的车标格不再是 18dp + fitCenter",
                logo.contains("""android:layout_width="18dp"""") &&
                    logo.contains("""android:layout_height="18dp"""") &&
                    logo.contains("""android:scaleType="fitCenter""""),
            )
            assertTrue(
                "$name 的车标外边距不是 1dp：18+1 才是旧版的 14+5，改了会把车名推走",
                logo.contains("""android:layout_marginEnd="1dp""""),
            )
        }
    }

    @Test
    fun `六份布局的时间文字都留着省略号兜底`() {
        layouts.forEach { (name, _) ->
            val tag = tagOf(read("app/src/main/res/layout/$name"), "wg_time")
            assertTrue(
                "$name 的时间没了 maxWidth，字一放大就会把刷新键挤出卡片",
                tag.contains("""android:maxWidth="118dp""""),
            )
            assertTrue("$name 的时间没了 ellipsize", tag.contains("""android:ellipsize="end""""))
            assertTrue("$name 的时间没了 maxLines", tag.contains("""android:maxLines="1""""))
        }
    }

    // ---------------- 渲染与设置页接线 ----------------

    @Test
    fun `渲染层只改 padding 与字号，不许碰 LayoutParams`() {
        assertTrue("不许用 setViewLayoutWidth（API 31）", !widgetSrc.contains("setViewLayoutWidth"))
        assertTrue("不许用 setViewLayoutHeight（API 31）", !widgetSrc.contains("setViewLayoutHeight"))
        assertTrue("按键循环没读控车图标倍率", widgetSrc.contains("WidgetStyle.iconScale(context)"))
        assertTrue("按键留白没走统一换算", widgetSrc.contains("WidgetMetrics.iconPaddingDp(basePad, cellDp, iconScale)"))
        assertTrue("宽版没用 42dp 的格子", widgetSrc.contains("WidgetMetrics.CELL_HEIGHT_WIDE_DP"))
        assertTrue("刷新键没接独立函数", widgetSrc.contains("private fun applyRefreshPadding"))
        assertTrue("刷新键留白没用 setViewPadding", widgetSrc.contains("views.setViewPadding(R.id.wg_refresh, pad, pad, pad, pad)"))
        assertTrue("全量渲染没调刷新键缩放", widgetSrc.contains("applyRefreshPadding(context = context, views = views)"))
        assertTrue("时间字号没走统一换算", widgetSrc.contains("WidgetMetrics.timeSizeDp(TIME_SIZE_SP, textScale"))
        // 点击仍绑在固定容器上，不是绑在会被缩放的图形上
        assertTrue("按键点击区被挪走了", widgetSrc.contains("views.setOnClickPendingIntent(container, pendingIntent)"))
        assertTrue("刷新键点击区被挪走了", widgetSrc.contains("R.id.wg_refresh,\n            broadcast(context, ACTION_REFRESH, RC_REFRESH)"))
        // 车标：同一套「容器内缩放」，只改 padding
        assertTrue("车标没接缩放函数", widgetSrc.contains("private fun applyLogoPadding"))
        assertTrue(
            "车标留白没走统一换算",
            widgetSrc.contains("WidgetMetrics.logoPad(WidgetStyle.logoScale(context))"),
        )
        assertTrue("车标留白没用 setViewPadding", widgetSrc.contains("views.setViewPadding(\n                R.id.wg_logo,"))
        assertTrue("全量渲染没调车标缩放", widgetSrc.contains("applyLogoPadding(context = context, views = views)"))
    }

    @Test
    fun `细节倍率不串进信息区`() {
        val textBody = widgetSrc.substring(
            widgetSrc.indexOf("private fun applyTextScale"),
            widgetSrc.indexOf("private fun applyBarThickness"),
        )
        assertEquals("时间之外不该吃 headerScale", 1, Regex("headerScale").findAll(textBody).count())
        assertTrue("信息区不该吃 iconScale", !textBody.contains("iconScale"))
        val numBody = widgetSrc.substring(
            widgetSrc.indexOf("private fun numberSizeDp"),
            widgetSrc.indexOf("private fun measureTextDp"),
        )
        assertTrue("续航字号不该吃细节倍率", !numBody.contains("iconScale") && !numBody.contains("headerScale"))
    }

    @Test
    fun `设置页四根滑杆各接各的偏好，共用一次全量重画`() {
        listOf("信息区大小", "控车图标大小", "更新时间与刷新键", "左上角车标大小").forEach { label ->
            assertTrue("少了「$label」滑杆", settingsSrc.contains("""label = "$label""""))
        }
        assertTrue("信息区滑杆没接 setInfoScale", settingsSrc.contains("WidgetStyle.setInfoScale(context, v)"))
        assertTrue("图标滑杆没接 setIconScale", settingsSrc.contains("WidgetStyle.setIconScale(context, v)"))
        assertTrue("右上角滑杆没接 setHeaderScale", settingsSrc.contains("WidgetStyle.setHeaderScale(context, v)"))
        assertTrue("车标滑杆没接 setLogoScale", settingsSrc.contains("WidgetStyle.setLogoScale(context, v)"))
        assertEquals(
            "四根滑杆都必须松手才整卡重画",
            4,
            Regex("""onCommit = onCommitSize""").findAll(settingsSrc).count(),
        )
        // 恢复默认后四个 state 都要回读，否则滑杆会停在旧位置
        listOf("infoScale", "iconScale", "headerScale", "logoScale").forEach { name ->
            assertTrue("$name 恢复后没回读", settingsSrc.contains("$name = WidgetStyle.${name}(context)"))
        }
        assertTrue("少了「格子不变」示意", settingsSrc.contains("private fun WidgetFinePreview"))
        // 示意图里也要有车标框，否则拖车标那根滑杆看不出任何变化
        assertTrue(
            "示意图没画车标格",
            settingsSrc.contains("WidgetMetrics.LOGO_BOX_DP.dp") &&
                settingsSrc.contains("WidgetMetrics.logoGraphicDp(logoScale)"),
        )
    }

    /** 取某个 `@+id/xxx` 所在标签后面的一段，够把该标签的属性都包进来。 */
    private fun tagOf(xml: String, id: String): String {
        val at = xml.indexOf("""@+id/$id"""")
        assertTrue("$id 不在布局里", at >= 0)
        return xml.substring(at, minOf(at + 460, xml.length))
    }
}
