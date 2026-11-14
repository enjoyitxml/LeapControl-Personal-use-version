package com.leapcontrol.app

import com.leapcontrol.app.map.MiniTileMap
import com.leapcontrol.app.map.TripMapCamera
import com.leapcontrol.app.map.TripTileMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * 全屏轨迹图的相机（2026-11-08）。
 *
 * ## 用户的需求
 * 「行程轨迹里的历史轨迹地图，支持点击放大查看。」
 *
 * ## 这里钉的是什么
 * 放大查看不是把位图拉大，而是换层级重新取瓦片，所以核心是一套相机换算：
 * 1. **点开就是整段**：初始取景跟卡片里那张小图同一个口径（[TripTileMap.view]），
 *    只是按全屏尺寸重算；
 * 2. **双指缩放时锚点下的地必须钉住**：两指一捏地图整体往左上跑，是这类界面最常见的 bug；
 * 3. **拖动方向跟手指一致**，拖到世界边界会被夹住而不是甩出空白；
 * 4. **贴图帧必须盖满视口**，否则边上会露出 1px 的缝或一条没铺到的底。
 */
class TripMapCameraTest {

    private fun pt(lat: Double, lng: Double) = TripTileMap.Point(lat, lng)

    private fun world(p: TripTileMap.Point): Pair<Double, Double> =
        MiniTileMap.globalPixel(p.lat, p.lng, TripMapCamera.REF_ZOOM)

    private val nanchangA = pt(28.6842, 115.8210)
    private val nanchangB = pt(28.7186, 115.8594)

    // ---------------- 初始取景 ----------------

    @Test
    fun `没有坐标时给不出相机`() {
        assertNull(TripMapCamera.fit(emptyList(), 1080, 1600))
        assertNull(TripMapCamera.fit(listOf(nanchangA), 0, 1600))
    }

    @Test
    fun `单点取景到街区级并且落在正中央`() {
        val cam = TripMapCamera.fit(listOf(nanchangA), 1080, 1600)!!
        assertEquals(TripTileMap.SINGLE_POINT_ZOOM.toFloat(), cam.zoom)
        val (wx, wy) = world(nanchangA)
        assertEquals(wx, cam.centerX, 1.0)
        assertEquals(wy, cam.centerY, 1.0)
        assertEquals(540f, cam.screenX(wx, 1080), 1f)
        assertEquals(800f, cam.screenY(wy, 1600), 1f)
    }

    @Test
    fun `两点取景两个端点都留在留白内`() {
        val w = 1080
        val h = 1600
        val cam = TripMapCamera.fit(listOf(nanchangA, nanchangB), w, h)!!
        listOf(nanchangA, nanchangB).forEach { p ->
            val (wx, wy) = world(p)
            val x = cam.screenX(wx, w)
            val y = cam.screenY(wy, h)
            assertTrue("x=$x 超出留白", x >= TripTileMap.PADDING_PX - 2 && x <= w - TripTileMap.PADDING_PX + 2)
            assertTrue("y=$y 超出留白", y >= TripTileMap.PADDING_PX - 2 && y <= h - TripTileMap.PADDING_PX + 2)
        }
    }

    @Test
    fun `全屏取景与卡片小图同一层级`() {
        // 同一段路，只是视口变大 —— 层级应当一致或更近一档（视口更宽时装得下更近的层级）
        val small = TripTileMap.view(listOf(nanchangA, nanchangB), 320, 168)!!
        val big = TripMapCamera.fit(listOf(nanchangA, nanchangB), 1080, 1600)!!
        assertTrue(big.zoom >= small.zoom.toFloat())
        assertTrue(big.zoom <= TripMapCamera.MAX_ZOOM)
    }

    // ---------------- 浮层避让（2026-11-08「点开放大后顶部按钮和起终点被盖住」） ----------------

    @Test
    fun `顶部底部有浮层时起终点都落在浮层之间`() {
        val w = 1080
        val h = 2400
        val top = 300      // 标题栏（含状态栏）
        val bottom = 260   // 底部说明（含导航条）
        val side = 40
        val cam = TripMapCamera.fit(
            listOf(nanchangA, nanchangB),
            w, h,
            topInsetPx = top,
            bottomInsetPx = bottom,
            sideInsetPx = side,
        )!!
        listOf(nanchangA, nanchangB).forEach { p ->
            val (wx, wy) = world(p)
            val x = cam.screenX(wx, w)
            val y = cam.screenY(wy, h)
            assertTrue("x=$x 被左侧留白压住", x >= side)
            assertTrue("x=$x 被右侧留白压住", x <= w - side)
            assertTrue("y=$y 被顶部标题栏盖住", y >= top)
            assertTrue("y=$y 被底部说明盖住", y <= h - bottom)
        }
    }

    @Test
    fun `浮层留白把可见带整体下移而不是缩放`() {
        // 只有顶部留白时，可见带的下移量 = 留白的一半（带心相对画布中心偏了 top/2）
        val w = 1080
        val h = 2400
        val plain = TripMapCamera.fit(listOf(nanchangA, nanchangB), w, h)!!
        val padded = TripMapCamera.fit(listOf(nanchangA, nanchangB), w, h, topInsetPx = 400)!!
        assertEquals("层级不该被浮层改变", plain.zoom, padded.zoom, 0f)
        val (wx, wy) = world(nanchangA)
        val dy = padded.screenY(wy, h) - plain.screenY(wy, h)
        assertEquals("可见带下移量不对", 200f, dy, 1f)
    }

    @Test
    fun `浮层留白不改变整段装下的结论`() {
        // 留白归零时必须跟老口径一模一样，别把卡片小图那套取景算歪
        val w = 1080
        val h = 1600
        val a = TripMapCamera.fit(listOf(nanchangA, nanchangB), w, h)!!
        val b = TripMapCamera.fit(
            listOf(nanchangA, nanchangB), w, h,
            topInsetPx = 0, bottomInsetPx = 0, sideInsetPx = 0,
        )!!
        assertEquals(a.zoom, b.zoom, 0f)
        assertEquals(a.centerX, b.centerX, 1e-6)
        assertEquals(a.centerY, b.centerY, 1e-6)
    }

    // ---------------- 手势 ----------------

    @Test
    fun `拖动方向跟手指一致`() {
        val cam = TripMapCamera(3.0e7, 3.0e7, 16f)
        val k = cam.scale()
        val moved = cam.panned(40f, -25f)
        // 手指往右下走，地图跟着走，中心点往左上挪
        assertEquals(cam.centerX - 40.0 / k, moved.centerX, 1e-3)
        assertEquals(cam.centerY + 25.0 / k, moved.centerY, 1e-3)
        // 已经贴在图上的那个点，屏幕坐标正好跟着手指走了同样多
        val w = 1080
        val h = 1600
        val wx = 3.0e7
        assertEquals(cam.screenX(wx, w) + 40f, moved.screenX(wx, w), 0.01f)
        assertEquals(cam.screenY(3.0e7, h) - 25f, moved.screenY(3.0e7, h), 0.01f)
        assertEquals(1.0, k / moved.scale(), 1e-9)
    }

    @Test
    fun `缩放时锚点下的地钉住不动`() {
        val w = 1080
        val h = 1600
        val cam = TripMapCamera(3.0e7, 3.0e7, 15f)
        val ax = 300f
        val ay = 900f
        val beforeX = cam.worldX(ax, w)
        val beforeY = cam.worldY(ay, h)
        val zoomed = cam.zoomedBy(1.8f, ax, ay, w, h)
        assertTrue("放大没生效", zoomed.zoom > cam.zoom)
        assertEquals(beforeX, zoomed.worldX(ax, w), 1e-6)
        assertEquals(beforeY, zoomed.worldY(ay, h), 1e-6)
        // 同一块地缩放后仍然画在锚点上
        assertEquals(ax, zoomed.screenX(beforeX, w), 0.01f)
        assertEquals(ay, zoomed.screenY(beforeY, h), 0.01f)
    }

    @Test
    fun `缩放层级被夹在可用范围内`() {
        var inn = TripMapCamera(3.0e7, 3.0e7, 17.5f)
        repeat(20) { inn = inn.zoomedBy(2f, 540f, 800f, 1080, 1600) }
        assertEquals(TripMapCamera.MAX_ZOOM, inn.zoom, 0f)
        var out = TripMapCamera(3.0e7, 3.0e7, 4.2f)
        repeat(20) { out = out.zoomedBy(0.5f, 540f, 800f, 1080, 1600) }
        assertEquals(TripMapCamera.MIN_ZOOM, out.zoom, 0f)
    }

    @Test
    fun `缩小再放大能回到原处不漂移`() {
        val base = TripMapCamera(3.0e7, 3.0e7, 16f)
        var cam = base
        repeat(10) { cam = cam.zoomedBy(0.83f, 500f, 700f, 1080, 1600) }
        repeat(10) { cam = cam.zoomedBy(1f / 0.83f, 500f, 700f, 1080, 1600) }
        assertEquals(base.zoom, cam.zoom, 0.05f)
        // 屏幕上漂动不到 2px 就算没漂（连续缩放的浮点误差不该被用户看出来）
        val px = abs(cam.centerX - base.centerX) * base.scale()
        assertTrue("横向漂了 $px px", px < 2.0)
    }

    @Test
    fun `非法手势输入不会把相机带坏`() {
        val cam = TripMapCamera(3.0e7, 3.0e7, 16f)
        assertEquals(cam, cam.zoomedBy(0f, 1f, 1f, 1080, 1600))
        assertEquals(cam, cam.zoomedBy(Float.NaN, 1f, 1f, 1080, 1600))
        assertEquals(cam, cam.panned(Float.NaN, 0f))
        assertEquals(cam, cam.panned(0f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `拖到世界外面会被夹回来`() {
        val broken = TripMapCamera(10.0, 10.0, 16f).panned(100_000f, 100_000f)
        assertTrue(broken.centerX >= 0.0)
        assertTrue(broken.centerY >= 0.0)
        val over = TripMapCamera(TripMapCamera.WORLD_SIZE - 10, 0.0, 16f).panned(-100_000f, 0f)
        assertTrue(over.centerX <= TripMapCamera.WORLD_SIZE)
    }

    // ---------------- 贴图帧 ----------------

    @Test
    fun `贴图帧盖满视口且瓦片之间无缝`() {
        val w = 1080
        val h = 1600
        val cam = TripMapCamera(3.0e7, 3.0e7, 15.4f)
        val frame = cam.tileFrame(w, h)
        assertEquals(15, frame.zoom)
        assertTrue("左边没盖住", frame.left(frame.minCol) <= 0f)
        assertTrue("上面没盖住", frame.top(frame.minRow) <= 0f)
        assertTrue("右边没盖住", frame.left(frame.maxCol) + frame.tileSize >= w)
        assertTrue("下面没盖住", frame.top(frame.maxRow) + frame.tileSize >= h)
        // 一个瓦片像素的屏幕尺寸永远在 1~√2 倍：不会糊，也不会缩出摩尔纹
        assertTrue(frame.perTilePx in 0.70f..1.42f)
        assertEquals(256f * frame.perTilePx, frame.tileSize, 0.01f)
        assertTrue(frame.tileCount > 0)
    }

    @Test
    fun `贴图帧跟着连续缩放换整档层级`() {
        val cam = TripMapCamera(3.0e7, 3.0e7, 17.9f)
        assertEquals(18, cam.tileFrame(1080, 1600).zoom)
        assertEquals(12, TripMapCamera(3.0e7, 3.0e7, 12.4f).tileFrame(1080, 1600).zoom)
    }

    @Test
    fun `同一视野内平移不换层级 只有跨瓦片才换 key`() {
        val cam = TripMapCamera(3.0e7, 3.0e7, 15f)
        val w = 1080
        val h = 1600
        val before = cam.tileFrame(w, h)
        // 横向挪 20px 还在这几块瓦片里
        val slight = cam.panned(20f, 0f).tileFrame(w, h)
        assertEquals(before.zoom, slight.zoom)
        assertEquals(before.key, slight.key)
        // 挪一整屏肯定出范围
        val far = cam.panned(1080f, 0f).tileFrame(w, h)
        assertTrue(before.key != far.key)
    }

    /**
     * 最容易犯的错：瓦片贴图和折线投影各用一套坐标系，差一格 / 差半块瓦片。
     * 这张图看不出错（都盖满屏幕），但车明明在路上、线却偏出去几百米。
     *
     * 做法：从屏幕上的任意位置反算经纬度，再正着投影回来，要求落回原处；
     * 同时要求它落在**自己那块瓦片**的绘制矩形里。
     */
    @Test
    fun `瓦片拼贴与折线投影共用同一套坐标`() {
        val cam = TripMapCamera(3.0e7, 3.0e7, 15.3f)
        val w = 1080
        val h = 1600
        val frame = cam.tileFrame(w, h)
        val worldPerZ = 2.0.pow((TripMapCamera.REF_ZOOM - frame.zoom).toDouble())
        listOf(120f to 300f, 540f to 800f, 900f to 1300f).forEach { (sx, sy) ->
            val lat = latitudeOf(cam.worldY(sy, h))
            val lng = longitudeOf(cam.worldX(sx, w))
            val (bx, by) = MiniTileMap.globalPixel(lat, lng, TripMapCamera.REF_ZOOM)
            assertEquals("经度往返丢了", sx, cam.screenX(bx, w), 0.5f)
            assertEquals("纬度往返丢了", sy, cam.screenY(by, h), 0.5f)

            val col = (bx / worldPerZ / 256).toInt()
            val row = (by / worldPerZ / 256).toInt()
            assertTrue("点落在帧外的列 $col", col in frame.minCol..frame.maxCol)
            assertTrue("点落在帧外的行 $row", row in frame.minRow..frame.maxRow)
            val left = frame.left(col)
            val top = frame.top(row)
            assertTrue("x=$sx 不在瓦片 $col 的 [${left}, ${left + frame.tileSize}] 里", sx >= left - 0.5f && sx <= left + frame.tileSize + 0.5f)
            assertTrue("y=$sy 不在瓦片 $row 的 [${top}, ${top + frame.tileSize}] 里", sy >= top - 0.5f && sy <= top + frame.tileSize + 0.5f)
        }
    }

    /** Web Mercator 逆变换（跟 [MiniTileMap.globalPixel] 严格互逆），测试里用来"造一个屏幕点"的经纬度。 */
    private fun longitudeOf(worldX: Double): Double = worldX / WORLD_PX * 360.0 - 180.0

    private fun latitudeOf(worldY: Double): Double {
        val yn = worldY / WORLD_PX
        return Math.toDegrees(2.0 * kotlin.math.atan(Math.exp(Math.PI * (1.0 - 2.0 * yn))) - Math.PI / 2.0)
    }

    private companion object {
        val WORLD_PX: Double = 256.0 * (1 shl TripMapCamera.REF_ZOOM)
    }
}
