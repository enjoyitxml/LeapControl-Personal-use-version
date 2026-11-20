package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 小组件右上角那颗 10dp 状态图标的**布局守门**。
 *
 * 2026-11-20 用户要求：状态提示前面挂一颗小图标（充电中→闪电、解锁→开锁、
 * 后备箱→后备箱…），但「更新时间 / 尚未同步」不要图标。
 *
 * 图标本身由 `VehicleWidget.bindHintIcon` 按 `WidgetStatusText` 给的语义换图，
 * 这里只钉住三件容易在改版式时丢掉的事：
 *
 * 1. **六份布局都得有 `wg_hint_icon`。** `RemoteViews` 找不到 id 是**静默失效** ——
 *    三套版式 × 宽/窄共六份，漏一份就是「那个版式上永远没有图标」，编译和单测都不会报。
 * 2. **尺寸与间距不许改。** 10dp + 3dp 间距是照 10sp 文字高度配的；改大了会把
 *    `wg_time` 往左挤，118dp 里能放的字变少。
 * 3. **`wg_time` 的 118dp / 单行 / 省略号三个属性一个都不能动。** 它们本来就是
 *    「字再大也只会省略号、不会把刷新键挤出卡片」的兜底，加图标时最容易顺手改掉。
 */
class WidgetHintIconTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val layouts = listOf(
        "widget_a", "widget_a_wide",
        "widget_d", "widget_d_wide",
        "widget_t", "widget_t_wide",
    )

    private fun read(name: String) =
        File(projectDir, "app/src/main/res/layout/$name.xml").readText()

    @Test
    fun `六份布局都有状态图标槽位`() {
        layouts.forEach { name ->
            val xml = read(name)
            assertTrue("$name 里没有 wg_hint_icon", xml.contains("@+id/wg_hint_icon"))
            assertEquals("$name 里 wg_hint_icon 不止一处", 1, Regex("@\\+id/wg_hint_icon").findAll(xml).count())
        }
    }

    @Test
    fun `图标 10dp 加 3dp 间距 默认藏起来`() {
        layouts.forEach { name ->
            val xml = read(name)
            val block = xml.substring(
                xml.indexOf("@+id/wg_hint_icon") - 260,
                xml.indexOf("@+id/wg_hint_icon") + 700,
            )
            assertTrue("$name 图标不是 10dp 宽", block.contains("android:layout_width=\"10dp\""))
            assertTrue("$name 图标不是 10dp 高", block.contains("android:layout_height=\"10dp\""))
            assertTrue("$name 图标没留 3dp 间距", block.contains("android:layout_marginEnd=\"3dp\""))
            assertTrue("$name 图标没默认隐藏", block.contains("android:visibility=\"gone\""))
            assertTrue("$name 图标没按等比缩放", block.contains("android:scaleType=\"fitCenter\""))
        }
    }

    @Test
    fun `wg_time 的单行省略与 118dp 宽度没被顺手改掉`() {
        layouts.forEach { name ->
            val xml = read(name)
            val start = xml.indexOf("@+id/wg_time")
            assertTrue("$name 里没有 wg_time", start >= 0)
            val block = xml.substring(start, (start + 700).coerceAtMost(xml.length))
            assertTrue("$name 的 wg_time 丢了 maxWidth=118dp", block.contains("android:maxWidth=\"118dp\""))
            assertTrue("$name 的 wg_time 丢了 maxLines=1", block.contains("android:maxLines=\"1\""))
            assertTrue("$name 的 wg_time 丢了 ellipsize", block.contains("android:ellipsize=\"end\""))
        }
    }

    @Test
    fun `图标摆在文字左边而不是右边`() {
        layouts.forEach { name ->
            val xml = read(name)
            assertTrue(
                "$name 里图标跑到 wg_time 右边去了（应该先图标再文字）",
                xml.indexOf("@+id/wg_hint_icon") < xml.indexOf("@+id/wg_time"),
            )
            // 刷新键仍在文字右边，图标夹在中间会点错
            assertTrue(
                "$name 里 wg_refresh 不在 wg_time 右边",
                xml.indexOf("@+id/wg_refresh") > xml.indexOf("@+id/wg_time"),
            )
        }
    }
}
