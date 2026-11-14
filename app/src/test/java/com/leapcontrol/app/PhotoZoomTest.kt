package com.leapcontrol.app

import com.leapcontrol.app.ui.PhotoBox
import com.leapcontrol.app.ui.PhotoZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全屏看图「双指缩放」的守门测试（2026-11-12）。
 *
 * ## 用户的原话
 * 「这个驻车照片点击放大后无法双指缩放查看照片，修复好。」
 *
 * 根因是那个弹层只做了 `ContentScale.Fit` 的静态展示，一个手势都没接 —— 而这一页的
 * 整段文案都写着「点图放大看编号」。
 *
 * 这里钉两件事：
 * 1. 缩放/平移的算式（纯函数）：锚点不跑、不露黑边、倍数有上下限；
 * 2. 接线（源码）：弹层必须真的挂上双指与双击，且**只有一份**实现
 *    （曾经它住在 FindCarScreen 里，两处入口共用；移走后别再冒出第二份）。
 */
class PhotoZoomTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    /** 去掉纯注释行：注释里会写「不许出现 xxx」这类反例，不能算命中。 */
    private fun codeOf(path: String): String =
        File(projectDir, path).readText().lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    // 1:1 的内容放在 1:1 的视图里：Fit 之后正好铺满，算式最直观
    private val square = PhotoBox(1000f, 1000f)

    // ---------------- 倍数 ----------------

    @Test
    fun `倍数被夹在 1 到 6 之间`() {
        assertEquals(PhotoZoom.MIN_SCALE, PhotoZoom.clampScale(0.2f), 0.0001f)
        assertEquals(PhotoZoom.MIN_SCALE, PhotoZoom.clampScale(-3f), 0.0001f)
        assertEquals(PhotoZoom.MAX_SCALE, PhotoZoom.clampScale(99f), 0.0001f)
        assertEquals(2f, PhotoZoom.clampScale(2f), 0.0001f)
        // NaN 只能回落到 1 倍：让它漏进去，graphicsLayer 会把整张图画没
        assertEquals(PhotoZoom.MIN_SCALE, PhotoZoom.clampScale(Float.NaN), 0.0001f)
    }

    @Test
    fun `一倍不算已放大`() {
        assertFalse(PhotoZoom.isZoomed(PhotoZoom.MIN_SCALE))
        // 捏合回弹后常留下 1.0000001 这类尾巴，不能判成「已放大」
        assertFalse(PhotoZoom.isZoomed(1.0000001f))
        assertTrue(PhotoZoom.isZoomed(1.2f))
    }

    // ---------------- 锚点 ----------------

    @Test
    fun `双指中心那块画面原地不动`() {
        // 视图中心 (500,500)，手指中心在 (750,500)：x 方向偏 +250
        val r = PhotoZoom.transform(
            scale = 1f, x = 0f, y = 0f,
            zoom = 2f, dragX = 0f, dragY = 0f,
            centroidX = 750f, centroidY = 500f,
            centerX = 500f, centerY = 500f,
            content = square, view = square,
        )
        assertEquals(2f, r.scale, 0.0001f)
        // 位移后，手指下那一点的内容坐标（250）应仍落在屏幕 750 处：2*250 + (-250) = 250
        assertEquals(-250f, r.x, 0.0001f)
        assertEquals(0f, r.y, 0.0001f)
    }

    @Test
    fun `从中间的捏合只放大不位移`() {
        val r = PhotoZoom.transform(
            scale = 1f, x = 0f, y = 0f,
            zoom = 1.5f, dragX = 0f, dragY = 0f,
            centroidX = 500f, centroidY = 500f,
            centerX = 500f, centerY = 500f,
            content = square, view = square,
        )
        assertEquals(1.5f, r.scale, 0.0001f)
        assertEquals(0f, r.x, 0.0001f)
        assertEquals(0f, r.y, 0.0001f)
    }

    @Test
    fun `缩小到一倍时位移归零`() {
        // 已放大 3 倍并拖到边角，再缩回 1 倍：必须回到正中，否则会留着一段黑边
        val r = PhotoZoom.transform(
            scale = 3f, x = 1000f, y = -800f,
            zoom = 1f / 3f, dragX = 0f, dragY = 0f,
            centroidX = 500f, centroidY = 500f,
            centerX = 500f, centerY = 500f,
            content = square, view = square,
        )
        assertEquals(1f, r.scale, 0.0001f)
        assertEquals(0f, r.x, 0.0001f)
        assertEquals(0f, r.y, 0.0001f)
    }

    // ---------------- 边界 ----------------

    @Test
    fun `放大后最多拖到边缘对齐`() {
        // 2 倍下内容宽 2000、视图 1000 → 单边最多移 500
        val r = PhotoZoom.clampToBounds(2f, 9999f, -9999f, square, square)
        assertEquals(500f, r.x, 0.0001f)
        assertEquals(-500f, r.y, 0.0001f)
    }

    @Test
    fun `内容没超出视图时不许拖动`() {
        // 竖图放进横屏视图：2 倍后宽度仍然小于视图宽度，横向就该锁死
        val tall = PhotoBox(400f, 1000f)
        val landscape = PhotoBox(1200f, 900f)
        val r = PhotoZoom.clampToBounds(2f, 300f, 120f, tall, landscape)
        assertEquals(0f, r.x, 0.0001f)
        // 纵向已经超出：(2*1000 - 900)/2 = 550
        assertEquals(120f, r.y, 0.0001f)
        assertEquals(550f, PhotoZoom.clampToBounds(2f, 0f, 9999f, tall, landscape).y, 0.0001f)
    }

    @Test
    fun `拖动的位移会累加`() {
        val r = PhotoZoom.transform(
            scale = 2f, x = 0f, y = 0f,
            zoom = 1f, dragX = 120f, dragY = -80f,
            centroidX = 500f, centroidY = 500f,
            centerX = 500f, centerY = 500f,
            content = square, view = square,
        )
        assertEquals(2f, r.scale, 0.0001f)
        assertEquals(120f, r.x, 0.0001f)
        assertEquals(-80f, r.y, 0.0001f)
    }

    @Test
    fun `连续捏合不回弹`() {
        // 两帧各放大 1.5 倍，最终应是 2.25 倍（位移按各自锚点累计）
        val first = PhotoZoom.transform(
            scale = 1f, x = 0f, y = 0f, zoom = 1.5f, dragX = 0f, dragY = 0f,
            centroidX = 500f, centroidY = 500f, centerX = 500f, centerY = 500f,
            content = square, view = square,
        )
        val second = PhotoZoom.transform(
            scale = first.scale, x = first.x, y = first.y, zoom = 1.5f, dragX = 0f, dragY = 0f,
            centroidX = 500f, centroidY = 500f, centerX = 500f, centerY = 500f,
            content = square, view = square,
        )
        assertEquals(2.25f, second.scale, 0.0001f)
    }

    @Test
    fun `捏过头被夹到上限时锚点按实际倍数算`() {
        // 5 倍时再捏 3 倍：实际只能到 6 倍，锚点必须按 6/5 而不是 3 来算
        val r = PhotoZoom.transform(
            scale = 5f, x = 0f, y = 0f, zoom = 3f, dragX = 0f, dragY = 0f,
            centroidX = 750f, centroidY = 500f, centerX = 500f, centerY = 500f,
            content = square, view = square,
        )
        assertEquals(PhotoZoom.MAX_SCALE, r.scale, 0.0001f)
        // k = 6/5 = 1.2；anchor = 250 → 250*(1-1.2) = -50
        assertEquals(-50f, r.x, 0.0001f)
    }

    // ---------------- 接线 ----------------

    @Test
    fun `弹层必须真的接上双指缩放`() {
        val dialog = codeOf("app/src/main/java/com/leapcontrol/app/ui/PhotoPreviewDialog.kt")
        assertTrue("弹层没接 detectTransformGestures，双指缩放不会生效", dialog.contains("detectTransformGestures"))
        assertTrue("弹层没接双击放大", dialog.contains("onDoubleTap"))
        assertTrue("缩放没走统一的 PhotoZoom 算式", dialog.contains("PhotoZoom.transform("))
    }

    @Test
    fun `全屏看图只有一份实现`() {
        val findCar = codeOf("app/src/main/java/com/leapcontrol/app/ui/FindCarScreen.kt")
        assertFalse(
            "FindCarScreen 里又冒出了一份 PhotoPreviewDialog：两份实现最后只会有一份坏掉",
            findCar.contains("fun PhotoPreviewDialog("),
        )
    }

    @Test
    fun `两个入口都要求打开时解大图`() {
        listOf(
            "app/src/main/java/com/leapcontrol/app/ui/FindCarScreen.kt",
            "app/src/main/java/com/leapcontrol/app/ui/ParkingPhotoScreen.kt",
        ).forEach { path ->
            val code = codeOf(path)
            assertTrue("$path 打开大图时没解大图，放大看编号只会更糊", code.contains("loadParkingPhotoLarge("))
        }
    }
}
