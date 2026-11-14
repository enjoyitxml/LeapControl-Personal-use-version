package com.leapcontrol.app

import com.leapcontrol.app.trip.TripFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行程起终点地址的守门测试（2026-11-08）。
 *
 * 用户反馈：行程卡里的地址显示成「江西省南昌市新建区蛟桥街道榆…」，地名正好被切掉，
 * 而那一行后面明明还有位置。根因不在排版 —— 是**存数据的时候**就按 14 个字硬切了，
 * 所以卡片再宽也只能显示那 14 个字。
 *
 * 这里分两类：
 * 1. 纯函数：正文不截断、小卡短地址去省市前缀；
 * 2. 源码断言：存库不许再用截断版、老数据的「…」要被补全、起终点行不许设 maxLines。
 */
class TripAddressTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun source(relative: String): String =
        File(projectDir, "app/src/main/java/com/leapcontrol/app/$relative").readText()

    @Test
    fun `存进档案的地址不截断 完整保留地名`() {
        val full = "江西省南昌市新建区蛟桥街道榆树村216号"
        assertEquals(full, TripFormat.placeText("  $full  "))
        assertFalse(
            "地址正文又出现了省略号，地名会被切掉",
            TripFormat.placeText(full).endsWith("…"),
        )
        // 换行 / 连续空格折叠成一个空格，避免地址正文里出现奇怪的空隙
        assertEquals("南昌市 新建区 榆树村", TripFormat.placeText("南昌市\n新建区  榆树村"))
    }

    @Test
    fun `小卡短地址去掉省市前缀 只留能认出地点的部分`() {
        assertEquals(
            "新建区蛟桥街道榆树村",
            TripFormat.shortPlace("江西省南昌市新建区蛟桥街道榆树村"),
        )
        assertEquals("朝阳区望京SOHO", TripFormat.shortPlace("北京市朝阳区望京SOHO"))
        // 没有行政前缀的短名（用户可能存过「家」「公司」这种）不能被剥成空
        assertEquals("家", TripFormat.shortPlace("家"))
        assertEquals("城市广场", TripFormat.shortPlace("城市广场"))
    }

    @Test
    fun `存库不再用 14 字硬切`() {
        val tracker = source("trip/TripTracker.kt")
        assertTrue(
            "TripTracker 没改用不截断的 placeText",
            tracker.contains("TripFormat.placeText("),
        )
        assertFalse(
            "存库还在调 shortPlace —— 地址会被切成 14 个字 + …，卡片再宽也看不出地名",
            tracker.contains("TripFormat.shortPlace("),
        )
    }

    @Test
    fun `老数据里以省略号结尾的地址会被重新补全`() {
        val tracker = source("trip/TripTracker.kt")
        assertTrue(
            "没有识别「…」结尾的旧地址",
            tracker.contains("label.endsWith(\"…\")"),
        )
        assertTrue(
            "没有补全入口 repairTruncatedLabels",
            tracker.contains("fun repairTruncatedLabels("),
        )
        assertTrue(
            "App 启动时没有触发补全，老行程的地址永远修不回来",
            source("ui/AppRuntime.kt").contains("TripTracker.repairTruncatedLabels("),
        )
    }

    @Test
    fun `行程卡的起终点行不设 maxLines`() {
        val screen = source("ui/TripHistoryScreen.kt")
        val body = screen.substringAfter("private fun EndPointRow(", "")
        assertTrue("找不到 EndPointRow", body.isNotEmpty())
        val row = body.substringBefore("\n}\n")
        assertFalse(
            "起终点行又设了 maxLines，长地址会重新出现省略号",
            row.contains("maxLines"),
        )
        assertTrue("地址没有占满整行剩余宽度，放不下的字会被挤掉", row.contains("weight(1f)"))
    }
}
