package com.leapcontrol.app

import com.leapcontrol.app.widget.WidgetButtonId
import com.leapcontrol.app.widget.WidgetButtonLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「桌面小组件控车按钮自定义 + 排序」的回归测试。
 *
 * 前半段跑纯逻辑（parse / serialize / move / 默认五个），后半段是源码守门：
 * 广播 action 必须进 ACTIONS、指令必须仍走 controlForWidget、完成式文案必须覆盖全部动作、
 * 配置改动必须触发全量重画 —— 这些「编译得过、跑起来却悄悄失效」的点只能靠静态断言钉住。
 */
class WidgetButtonLayoutTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(path: String) = File(projectDir, path).readText()

    // ========================================================================
    // 纯逻辑
    // ========================================================================

    @Test
    fun `默认就是五个按钮且顺序一字不改`() {
        assertEquals(
            listOf(
                WidgetButtonId.UNLOCK, WidgetButtonId.LOCK, WidgetButtonId.AC,
                WidgetButtonId.TRUNK, WidgetButtonId.HORN,
            ),
            WidgetButtonLayout.DEFAULT,
        )
        assertEquals(5, WidgetButtonLayout.SLOT_COUNT)
        assertEquals(WidgetButtonLayout.DEFAULT, WidgetButtonLayout.parse(null))
    }

    @Test
    fun `9 个动作各有中文名 广播 action 与图标`() {
        assertEquals(9, WidgetButtonId.entries.size)
        WidgetButtonId.entries.forEach { id ->
            assertTrue("${id.name} 缺中文名", id.title.isNotBlank())
            assertTrue(
                "${id.name} 的 action 不在 widget 命名空间",
                id.action.startsWith("com.leapcontrol.app.widget."),
            )
            assertTrue("${id.name} 缺图标", id.iconRes != 0)
        }
        assertEquals(9, WidgetButtonId.entries.map { it.action }.toSet().size)
        assertEquals(9, WidgetButtonId.entries.map { it.title }.toSet().size)
    }

    @Test
    fun `替换成三个新动作后 serialize 与 parse 往返一致`() {
        val custom = listOf(
            WidgetButtonId.WINDOW, WidgetButtonId.LOCK, WidgetButtonId.BATTERY_PREHEAT,
            WidgetButtonId.SUNSHADE, WidgetButtonId.HORN,
        )
        assertEquals(5, custom.size)
        assertEquals(custom.toSet().size, custom.size)
        assertEquals(custom, WidgetButtonLayout.parse(WidgetButtonLayout.serialize(custom)))
        // 三个可替换项都能进配置，默认的 5 个也仍被 DEFAULT 钉着
        assertTrue(WidgetButtonId.WINDOW in custom)
        assertTrue(WidgetButtonId.BATTERY_PREHEAT in custom)
        assertTrue(WidgetButtonId.SUNSHADE in custom)
    }

    @Test
    fun `move 语义与 CardReorder movedItem 一致`() {
        val d = WidgetButtonLayout.DEFAULT // [UNLOCK, LOCK, AC, TRUNK, HORN]
        // 原地不动 / from 越界 → 原样返回
        assertEquals(d, WidgetButtonLayout.move(d, 2, 2))
        assertEquals(d, WidgetButtonLayout.move(d, -1, 0))
        assertEquals(d, WidgetButtonLayout.move(d, 9, 0))
        // to 越界钳到边界
        assertEquals(
            listOf(
                WidgetButtonId.UNLOCK, WidgetButtonId.AC, WidgetButtonId.TRUNK,
                WidgetButtonId.HORN, WidgetButtonId.LOCK,
            ),
            WidgetButtonLayout.move(d, 1, 99),
        )
        // 往下 / 往上各移一格，且不增删项
        assertEquals(
            listOf(
                WidgetButtonId.UNLOCK, WidgetButtonId.AC, WidgetButtonId.LOCK,
                WidgetButtonId.TRUNK, WidgetButtonId.HORN,
            ),
            WidgetButtonLayout.move(d, 1, 2),
        )
        assertEquals(
            listOf(
                WidgetButtonId.UNLOCK, WidgetButtonId.LOCK, WidgetButtonId.TRUNK,
                WidgetButtonId.AC, WidgetButtonId.HORN,
            ),
            WidgetButtonLayout.move(d, 3, 2),
        )
        assertEquals(d.size, WidgetButtonLayout.move(d, 0, 4).size)
        assertEquals(d.toSet(), WidgetButtonLayout.move(d, 0, 4).toSet())
    }

    @Test
    fun `parse 容错 null 空 非法 重复 超长 不足 恒为 5 项无重复`() {
        // null / 空 / 全空白
        assertEquals(WidgetButtonLayout.DEFAULT, WidgetButtonLayout.parse(null))
        assertEquals(WidgetButtonLayout.DEFAULT, WidgetButtonLayout.parse(""))
        assertEquals(WidgetButtonLayout.DEFAULT, WidgetButtonLayout.parse("   "))
        // 全非法 → 默认
        assertEquals(WidgetButtonLayout.DEFAULT, WidgetButtonLayout.parse("NOPE,!!!,,"))
        // 非法混在合法之间：忽略非法，再用 DEFAULT 补齐
        assertEquals(
            listOf(
                WidgetButtonId.WINDOW, WidgetButtonId.UNLOCK, WidgetButtonId.LOCK,
                WidgetButtonId.AC, WidgetButtonId.TRUNK,
            ),
            WidgetButtonLayout.parse("WINDOW,NOPE,??"),
        )
        // 重复去重，保留首次出现的顺序
        assertEquals(
            WidgetButtonLayout.DEFAULT,
            WidgetButtonLayout.parse("UNLOCK,UNLOCK,LOCK,LOCK,LOCK,AC,AC,TRUNK,HORN"),
        )
        // 超长（9 个全列）截断到 5
        val all = WidgetButtonId.entries.joinToString(",") { it.name }
        assertEquals(5, WidgetButtonLayout.parse(all).size)
        // 不足补齐
        assertEquals(5, WidgetButtonLayout.parse("WINDOW").size)

        // 不管输入多离谱，结果都恒为 5 项且无重复
        listOf(
            null, "", "  ", "NOPE", "WINDOW", "UNLOCK,UNLOCK", all,
            "WINDOW,SUNSHADE,BATTERY_PREHEAT", "HORN,TRUNK,AC,LOCK,UNLOCK,WINDOW",
        ).forEach { raw ->
            val parsed = WidgetButtonLayout.parse(raw)
            assertEquals("长度不对：<$raw>", 5, parsed.size)
            assertEquals("出现重复：<$raw>", parsed.size, parsed.toSet().size)
        }
    }

    @Test
    fun `serialize 也做去重补齐截断 不会写坏配置`() {
        // 三个重复项去重后只剩 WINDOW，必须补齐到 5 项
        val normalized = WidgetButtonLayout.serialize(
            listOf(WidgetButtonId.WINDOW, WidgetButtonId.WINDOW, WidgetButtonId.WINDOW),
        )
        assertEquals(5, normalized.split(",").size)
        assertEquals(
            listOf(
                WidgetButtonId.WINDOW, WidgetButtonId.UNLOCK, WidgetButtonId.LOCK,
                WidgetButtonId.AC, WidgetButtonId.TRUNK,
            ),
            WidgetButtonLayout.parse(normalized),
        )
        // 任何输入回读仍是长度 5、无重复
        val roundTrip = WidgetButtonLayout.parse(
            WidgetButtonLayout.serialize(listOf(WidgetButtonId.WINDOW, WidgetButtonId.SUNSHADE)),
        )
        assertEquals(5, roundTrip.size)
        assertEquals(roundTrip.size, roundTrip.toSet().size)
    }

    // ========================================================================
    // 指令名真实存在
    // ========================================================================

    @Test
    fun `9 个动作会下发的指令名在 Commands build 都能找到`() {
        val byButton = mapOf(
            WidgetButtonId.UNLOCK to listOf("unlock"),
            WidgetButtonId.LOCK to listOf("lock"),
            WidgetButtonId.AC to listOf("acOn", "acOff"),
            WidgetButtonId.TRUNK to listOf("trunkOpen", "trunkClose"),
            WidgetButtonId.HORN to listOf("horn"),
            WidgetButtonId.WINDOW to listOf("windowVent", "windowOpen", "windowFull", "windowClose"),
            WidgetButtonId.BATTERY_PREHEAT to listOf("batteryPreheat", "batteryPreheatOff"),
            WidgetButtonId.SUNSHADE to listOf("sunshadeOpen", "sunshadeClose"),
            // 哨兵与首页同一组指令：cmdid 400，别被误改成 40（那是云端权限结果码）
            WidgetButtonId.SENTRY to listOf("sentryOn", "sentryOff"),
        )
        assertEquals("9 个动作都要列出指令", 9, byButton.size)
        assertEquals(WidgetButtonId.entries.toSet(), byButton.keys)

        val expectedCmdid = mapOf(
            "unlock" to "110", "lock" to "110",
            "acOn" to "170", "acOff" to "170",
            "trunkOpen" to "130", "trunkClose" to "130",
            "horn" to "120",
            "windowVent" to "230", "windowOpen" to "230", "windowFull" to "230", "windowClose" to "230",
            "sunshadeOpen" to "240", "sunshadeClose" to "240",
            "batteryPreheat" to "160", "batteryPreheatOff" to "160",
            "sentryOn" to "400", "sentryOff" to "400",
        )
        byButton.values.flatten().forEach { name ->
            val built = runCatching { Commands.build(name) }.getOrNull()
            assertNotNull("指令 $name 不在 Commands.build", built)
            assertEquals("$name 的 cmdid 不对", expectedCmdid[name], built!!.cmdid)
            assertTrue("$name 没有中文标签", built.label.isNotBlank())
        }
    }

    // ========================================================================
    // 源码守门：编译得过但会悄悄失效的地方
    // ========================================================================

    @Test
    fun `VehicleWidget ACTIONS 含 9 个 action 且新动作都有常量`() {
        val source = read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt")
        val start = source.indexOf("val ACTIONS = setOf(")
        assertTrue("找不到 ACTIONS 声明", start >= 0)
        val block = source.substring(start, source.indexOf(')', start))
        val actions = Regex("ACTION_\\w+").findAll(block).map { it.value }.toList()
        assertEquals("ACTIONS 必须正好 9 个", 9, actions.size)
        assertEquals("ACTIONS 不该有重复", 9, actions.toSet().size)
        listOf(
            "ACTION_UNLOCK", "ACTION_LOCK", "ACTION_TOGGLE_AC", "ACTION_TOGGLE_TRUNK",
            "ACTION_HORN", "ACTION_WINDOW", "ACTION_BATTERY_PREHEAT", "ACTION_SUNSHADE",
            "ACTION_SENTRY",
        ).forEach { assertTrue("$it 不在 ACTIONS", it in actions) }

        // 枚举里的 action 字符串也要和 VehicleWidget 里那 9 个常量值对得上
        WidgetButtonId.entries.forEach { id ->
            assertTrue("${id.name} 的 action 字符串没在 VehicleWidget 里定义", source.contains("\"${id.action}\""))
        }
    }

    @Test
    fun `Provider 仍用 controlForWidget 且不含 resultWaitMs 0 与 control`() {
        val source = read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt")
        // 注释里有一句「不能退回 resultWaitMs = 0」的说明，先去掉 // 之后的内容只看代码
        val code = source.lines().joinToString("\n") { it.substringBefore("//") }
        assertTrue(
            "小组件必须走 controlForWidget(command, RESULT_BUDGET_MS)",
            code.contains("runtime.controlForWidget(command, RESULT_BUDGET_MS)"),
        )
        assertFalse(
            "别把结果等待改成 0（广播只有 10 秒预算，但 0 等于一个字的结果都不等）",
            Regex("resultWaitMs\\s*=\\s*0").containsMatchIn(code),
        )
        assertFalse("别退回不带结果等待的 control()", code.contains(".control("))
        // 二次确认门禁不许挪走 / 改名（DoubleConfirmRulesTest 也盯着这两处）
        assertTrue("二次确认开关没被读", code.contains("ConfirmActions.isEnabled"))
        assertTrue("确认门禁没了", code.contains("ConfirmGate.step"))
    }

    @Test
    fun `labelOf 与 doneLabelOf 覆盖 9 个动作的全部指令 不落兜底`() {
        val source = read("app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt")
        val doing = functionBody(source, "labelOf")
        val done = functionBody(source, "doneLabelOf")
        val commands = listOf(
            "unlock", "lock", "acOn", "acOff", "trunkOpen", "trunkClose", "horn",
            "batteryPreheat", "batteryPreheatOff", "sunshadeOpen", "sunshadeClose",
            "windowVent", "windowOpen", "windowFull", "windowClose",
            "sentryOn", "sentryOff",
        )
        commands.forEach { cmd ->
            assertTrue("labelOf 漏了「进行中」文案：$cmd", doing.contains("\"$cmd\""))
            assertTrue("doneLabelOf 漏了完成式文案：$cmd", done.contains("\"$cmd\""))
        }
        // 每条指令都必须写 confirmed / 未 confirmed 两态，否则会落到 else 的「指令已下发」兜底
        val confirmedCount = Regex("if \\(confirmed\\)").findAll(done).count()
        assertEquals("doneLabelOf 每条指令都要有完成式两态", commands.size, confirmedCount)
    }

    @Test
    fun `配置改动后 save 并全量重画 且入口挂在 WidgetSettingsGroup`() {
        val screen = read("app/src/main/java/com/leapcontrol/app/ui/WidgetButtonsScreen.kt")
        assertTrue("改动没落盘", screen.contains("WidgetButtonLayout.save(context, next)"))
        assertTrue("改动没重画桌面卡片", screen.contains("VehicleWidget.render(context)"))
        assertFalse("配置变更不能走增量（点击区属于静态部分）", screen.contains("partial = true"))
        assertTrue("必须有恢复默认", screen.contains("WidgetButtonLayout.DEFAULT"))
        assertTrue("二级页必须有 BackHandler", screen.contains("BackHandler"))
        assertTrue("替换弹层要列出全部动作（含新增的哨兵）", screen.contains("WidgetButtonId.entries"))

        val settings = read("app/src/main/java/com/leapcontrol/app/ui/WidgetSettings.kt")
        assertTrue("没有「控车按钮」入口", settings.contains("title = \"控车按钮\""))
        assertTrue("没有整页覆盖", settings.contains("WidgetButtonsScreen("))
        assertTrue("摘要没读配置", settings.contains("WidgetButtonLayout.load(context)"))
    }

    @Test
    fun `六份布局的 5 个槽位与图标 id 没被动过`() {
        val ids = listOf(
            "wg_cell_unlock", "wg_cell_lock", "wg_cell_ac", "wg_cell_trunk", "wg_cell_horn",
            "wg_icon_unlock", "wg_icon_lock", "wg_icon_ac", "wg_icon_trunk", "wg_icon_horn",
        )
        listOf("a", "a_wide", "d", "d_wide", "t", "t_wide").forEach { key ->
            val xml = read("app/src/main/res/layout/widget_$key.xml")
            ids.forEach { id -> assertTrue("$key 布局少了 $id", xml.contains("@+id/$id")) }
        }
    }

    /** 从 `private fun <name>` 起，按花括号配对截出整个函数体（含表达式体 `= when {}`）。 */
    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("private fun $name")
        require(start >= 0) { "找不到函数 $name" }
        val open = source.indexOf('{', start)
        require(open >= 0) { "$name 没有函数体" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        return source.substring(start)
    }
}
