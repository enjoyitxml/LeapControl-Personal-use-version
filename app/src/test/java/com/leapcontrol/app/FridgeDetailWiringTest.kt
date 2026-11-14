package com.leapcontrol.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 冰箱二级页下发链路的守门测试（2026-11-08 用户反馈）。
 *
 * 反馈原话：「冰箱的指令下发应该在二级页面直接下发，而不是返回的时候才下发指令，
 * 目前点二级界面的开启冰箱按钮没有任何反馈」。
 *
 * 真凶不在冰箱页，而在 `HomeScreen` 的结构：`ActionBanner`（指令结果条）与
 * `ConfirmDialog`（二次确认弹窗）被写进了首页那个 `else` 分支里，二级页打开时它们
 * 根本不参与组合 —— 点「开启车载冰箱」既不弹确认框、按钮也不会变「下发中…」，
 * 退回首页才突然冒出确认框、指令到这时候才真正发出去。
 *
 * 所以这两条都用「源码顺序」钉住，防止以后又被人顺手挪回首页分支里。
 */
class FridgeDetailWiringTest {

    private val projectDir = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun source(path: String) = File(projectDir, path).readText()

    @Test
    fun `冰箱页的按钮直接下发指令，不靠返回触发`() {
        val page = source("app/src/main/java/com/leapcontrol/app/ui/FridgeDetailScreen.kt")
        assertTrue("冰箱页没有下发回调", page.contains("onControl"))
        // 两个底部按钮都必须就地 onControl：返回只是关页面，不能是下发入口
        assertTrue(
            "「开启车载冰箱」没有就地下发",
            page.contains("FridgeCardRules.command(draft, enable = true)"),
        )
        assertTrue(
            "「关闭冰箱」没有就地下发",
            page.contains("FridgeCardRules.command(draft, enable = false)"),
        )
        // 在途状态要有页内文案，否则点下去这一页看着毫无变化
        assertTrue("状态胶囊缺少在途文案", page.contains("开启中"))
    }

    @Test
    fun `结果条与二次确认弹窗挂在首页分支之外`() {
        val home = source("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt")
        val chainClose = home.indexOf("// 二级页分支链（else）")
        assertTrue("找不到二级页分支链的收尾标记", chainClose >= 0)

        val banner = home.indexOf("ActionBanner(")
        val confirm = home.indexOf("ConfirmDialog(")
        assertTrue("源码里没有 ActionBanner 调用", banner >= 0)
        assertTrue("源码里没有 ConfirmDialog 调用", confirm >= 0)

        // 必须在分支链收尾之后：说明它们是 Box 的直接子节点，二级页里也会参与组合
        assertTrue(
            "指令结果条又跑回首页分支里了，二级页下发指令时会看不到结果",
            banner > chainClose,
        )
        assertTrue(
            "二次确认弹窗又跑回首页分支里了，二级页点了会「没反应」",
            confirm > chainClose,
        )
    }
}
