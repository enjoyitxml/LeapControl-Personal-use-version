package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面小组件「9 个动作」的接线守门。
 *
 * 2026-11-08 用户反馈：小组件上换成车窗 / 电池预热 / 遮阳帘之后，点下去提示「已下发」车却不动。
 * 根因是判方向只信本地记住的开合态（桌面广播常落在冷进程，本地记忆可能早就跟车不一致），
 * 于是发出去的是一条「关一个本来就关着的东西」的空指令。
 *
 * 这类问题在源码里**看不出来**（三层各自都自洽）：动作字符串、`ACTIONS` 白名单、
 * `execute` 的 `when`、二次确认表、判向逻辑，任何一处漏了一项，表现都是「点了没反应」
 * 或「发了但没用」。所以这里用读源码的方式把四处钉死，加动作时漏一处就会红。
 */
class WidgetDispatchTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun source(path: String): String = File(projectDir, path).readText()

    private val widgetSource = source("app/src/main/java/com/leapcontrol/app/widget/VehicleWidget.kt")
    private val providerSource =
        source("app/src/main/java/com/leapcontrol/app/widget/VehicleWidgetProvider.kt")
    private val buttonsSource =
        source("app/src/main/java/com/leapcontrol/app/widget/WidgetButtonLayout.kt")
    private val tileRunnerSource =
        source("app/src/main/java/com/leapcontrol/app/tiles/TileCommandRunner.kt")

    /** `const val ACTION_XXX = "..."` → name to value */
    private val widgetActions: Map<String, String> =
        Regex("""const val (ACTION_[A-Z_]+) = "([^"]+)"""")
            .findAll(widgetSource)
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** `ACTIONS = setOf(ACTION_X, ...)` 里的常量名 */
    private val actionsWhitelist: Set<String> = run {
        val block = Regex("""val ACTIONS = setOf\(([\s\S]*?)\)""").find(widgetSource)
            ?: error("VehicleWidget.ACTIONS 不见了")
        Regex("ACTION_[A-Z_]+").findAll(block.groupValues[1]).map { it.value }.toSet()
    }

    /** `WidgetButtonId` 每个枚举项绑定的广播 action */
    private val buttonActions: List<String> = Regex("""\b[A-Z_]+\(\s*"[^"]*",\s*"([^"]+)"""")
        .findAll(buttonsSource)
        .map { it.groupValues[1] }
        .toList()

    /** `execute` 的 `when` 里认领了哪些动作（`VehicleWidget.ACTION_X ->`） */
    private val handledByExecute: Set<String> =
        Regex("""VehicleWidget\.(ACTION_[A-Z_]+)\s*->""")
            .findAll(providerSource)
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `按钮枚举里的 action 全部登记进了 ACTIONS`() {
        // onReceive 拿 ACTIONS 过滤广播，漏一个就是「点了没反应」——
        // 这正是这条最容易漏、又最难从界面上看出来的地方
        assertTrue("WidgetButtonId 一个都没解析到，正则该更新了", buttonActions.size >= 9)
        buttonActions.forEach { action ->
            assertTrue("$action 没进 VehicleWidget.ACTIONS，点了不会有任何反应", action in widgetActions.values)
            val name = widgetActions.entries.first { it.value == action }.key
            assertTrue("$name 没进 ACTIONS 白名单", name in actionsWhitelist)
        }
    }

    @Test
    fun `execute 认领了全部 9 个动作`() {
        // 白名单放行之后还得有人处理：`when` 里漏一个，`else -> return` 会把它悄悄吞掉
        assertEquals("ACTIONS 里应是 9 个控车动作", 9, actionsWhitelist.size)
        actionsWhitelist.forEach { name ->
            assertTrue("$name 在 execute 里没有分支，点了不会下发", name in handledByExecute)
        }
    }

    @Test
    fun `每个动作都有独立的 requestCode`() {
        // FLAG_UPDATE_CURRENT 下相同 requestCode + 相同 action 会互相覆盖，
        // 但 requestCode 撞车本身不致命 —— 真正要守的是「别共用同一个」，
        // 否则后面改 action 时容易把两个按钮并成一个。
        val codes = Regex("""private const val (RC_[A-Z_]+) = (\d+)""")
            .findAll(widgetSource)
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .toList()
        assertEquals("requestCode 有重复：$codes", codes.size, codes.map { it.second }.toSet().size)
        // 9 个动作各占一个，另有刷新 / 打开 App / 打开 App 带目标
        assertTrue("requestCode 少了，新增动作要单独给一个", codes.size >= 12)
    }

    @Test
    fun `判方向前必须先对一次车况`() {
        // 这条是 2026-11-08 那个 bug 的正脸：只按本地记忆判向 → 发出空指令
        assertTrue(
            "execute 没有先取判向用车况，点按方向会退回本地过期记忆",
            providerSource.contains("directionStatus(context, runtime, lastAction)"),
        )
        assertTrue(
            "判向用的车况没做「只认宽限期之后的报文」，会把旧值当成刚执行完的结果",
            providerSource.contains("lastDirectionActionAt("),
        )
        assertTrue(
            "判向前没走 QuickTiles.calibrateFromStatus，本地记忆不会跟车对上",
            providerSource.contains("QuickTiles.calibrateFromStatus"),
        )
        // 四个开关键 + 车窗 + 哨兵：都是「靠车况才知道往哪切」的动作
        listOf(
            "ACTION_TOGGLE_AC", "ACTION_TOGGLE_TRUNK",
            "ACTION_BATTERY_PREHEAT", "ACTION_SUNSHADE", "ACTION_WINDOW",
            "ACTION_SENTRY",
        ).forEach { name ->
            assertTrue("$name 没进 DIRECTION_ACTIONS，判向时拿不到车况", providerSource.contains(name))
        }
        assertTrue(
            "别再退回「只喂一次初值」的老写法（seedToggleState）",
            !providerSource.contains("seedToggleState"),
        )
    }

    @Test
    fun `哨兵按钮与零控首页同一条判向通路`() {
        // 用户要求「控制开关通路和零控首页的哨兵按钮保持一致」：
        // 不能在 Provider 里另写一份 if (sentryOn) ...，必须直接调首页那份 resolveCommand。
        assertTrue(
            "哨兵没有走 ControlActions.resolveCommand，会与首页的开/关判定分叉",
            providerSource.contains("ControlActions.resolveCommand(\"sentry\", status)"),
        )
        assertTrue(
            "哨兵没进 DIRECTION_TILES，判向前不会先对一次车况（冷进程会发反方向）",
            Regex("ACTION_SENTRY to \"sentry\"").containsMatchIn(providerSource),
        )
        assertTrue(
            "哨兵指令发出后没写本地开合态，8 秒意图窗口与按键高亮都会失效",
            providerSource.contains("WidgetSentryState.setOn(context, command == \"sentryOn\")"),
        )
        assertTrue(
            "哨兵没接 8 秒意图窗口，连点两下会开完立刻关",
            providerSource.contains("WidgetSentryState.lastActionAt(context)"),
        )
        assertTrue(
            "哨兵没有用车况校准本地态",
            providerSource.contains("WidgetSentryState.calibrateFromStatus(context, status.sentryOn)"),
        )
        // 共享账号权限门禁仍在 controlForWidget 里（result=40 是云端权限，别改 cmdid）
        val runtime = source("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt")
        assertTrue("controlForWidget 丢了哨兵权限门禁", runtime.contains("guardSentryPermission(commandName)"))
        val models = source("app/src/main/java/com/leapcontrol/app/Models.kt")
        assertTrue("哨兵指令的 cmdid 被改了", models.contains("\"sentryOn\" -> ControlCommand(\"400\""))
    }

    @Test
    fun `磁贴与小组件共用同一份车况校准`() {
        // 两处各写一份，迟早一边改了一边没改（这个项目已经踩过好几回）
        assertTrue(
            "TileCommandRunner 没复用 QuickTiles.calibrateFromStatus",
            tileRunnerSource.contains("QuickTiles.calibrateFromStatus"),
        )
        val specs = source("app/src/main/java/com/leapcontrol/app/tiles/TileSpecs.kt")
        assertTrue("TileSpecs 里没有 calibrateFromStatus", specs.contains("fun calibrateFromStatus"))
        // 宽限期只能有一份定义
        assertEquals(
            "ACTION_GRACE_MS 被重复定义了",
            1,
            listOf(specs, tileRunnerSource).count { it.contains("ACTION_GRACE_MS = ") },
        )
    }
}
