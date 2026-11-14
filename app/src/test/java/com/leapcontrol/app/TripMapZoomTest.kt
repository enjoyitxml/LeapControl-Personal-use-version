package com.leapcontrol.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「点开地图放大查看」的接线守门（2026-11-08）。
 *
 * ## 用户的原话
 * 「行程轨迹里的历史轨迹地图，支持点击放大查看。」
 *
 * ## 为什么是源码断言
 * 这几件事都在 Compose 的接线里，没有可以拿到 JVM 上跑的值：
 * 点小图点不点得开、全屏是不是真的自己画（而不是把位图拉大）、有没有顺手多请求一次高德。
 * 相机换算的正确性由 [TripMapCameraTest] 用数值守，这里只守"线接没接对"。
 */
class TripMapZoomTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun file(path: String): String = File(projectDir, path).readText()

    private val preview: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/ui/TripMapPreview.kt")
    }
    private val viewer: String by lazy {
        file("app/src/main/java/com/leapcontrol/app/ui/TripMapViewer.kt")
    }

    @Test
    fun `点小图能打开全屏`() {
        assertTrue(
            "地图没挂点击",
            preview.contains("noRippleClickable { viewing = true }"),
        )
        assertTrue("没有全屏层", preview.contains("if (viewing)") && preview.contains("TripMapViewer("))
        assertTrue("关不掉", viewer.contains("onDismissRequest = onClose"))
    }

    @Test
    fun `全屏层自己画瓦片 不是把位图拉大`() {
        // 用 Canvas + 相机投影现画，才能做到"放大看得更细"
        assertTrue(viewer.contains("Canvas("))
        assertTrue(viewer.contains("detectTransformGestures"))
        assertTrue(viewer.contains("zoomedBy("))
        assertTrue(viewer.contains("panned("))
        assertTrue(viewer.contains("MiniTileMap.tile("))
        // 反例：如果这里出现 graphicsLayer 的 scale，就是拿位图硬放大，糊
        assertTrue("不该用 graphicsLayer 缩放位图", !viewer.contains("graphicsLayer"))
    }

    @Test
    fun `全屏层不再多请求一次高德`() {
        // 路线直接用卡片里已经规划好的那份，点开是即时的、不额外吃额度
        assertTrue("没把已算好的路线传过去", preview.contains("route = state?.route"))
        assertTrue("全屏层不该再规划路线", !viewer.contains("TripRoute"))
        assertTrue("全屏层不该收 amapKey", !viewer.contains("amapKey"))
    }

    @Test
    fun `全屏层给得出操作指引和口径`() {
        assertTrue(viewer.contains("双指缩放"))
        assertTrue("口径说明必须跟着进全屏", viewer.contains("caption"))
        assertTrue("高德署名不能丢", viewer.contains("© 高德地图"))
    }

    @Test
    fun `夜间底图与小图用同一套滤镜`() {
        assertTrue(viewer.contains("MiniTileMap.nightMatrix()"))
    }

    @Test
    fun `历史清单与实时轨迹都传了标题`() {
        val history = file("app/src/main/java/com/leapcontrol/app/ui/TripHistoryScreen.kt")
        assertTrue(history.contains("title = \"\${TripFormat.time(trip.startAt)}"))
        assertTrue(history.contains("subtitle ="))
        val live = file("app/src/main/java/com/leapcontrol/app/ui/TripLivePanel.kt")
        assertTrue(live.contains("title = \"实时轨迹\""))
    }
}
