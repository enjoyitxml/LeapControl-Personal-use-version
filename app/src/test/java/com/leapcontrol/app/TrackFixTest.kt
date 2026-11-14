package com.leapcontrol.app

import com.leapcontrol.app.map.MiniTileMap
import com.leapcontrol.app.map.TrackFix
import com.leapcontrol.app.map.TripMapCamera
import com.leapcontrol.app.map.TripTileMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「选点修正轨迹」的纯逻辑与接线守门。
 *
 * 用户原话：「自己画的路线要重新吸附道路 … 如果手画不好用可以用户自己选择采样点位置，
 * 然后根据导航路线补齐」，随后又明确要求「删掉涂抹的方案，只保留选点的方案」，
 * 并把修正按钮放到顶部轨迹详情下方、整体字号放大。
 *
 * 这里守四件事：
 *  1. 屏幕坐标 → 全局像素 → 经纬度是严格互逆的（不然点出来的钉子整体偏出道路）；
 *  2. 只替换首尾钉子之间那一小段，其余路线与首尾端点原样保留；
 *  3. 修正结果能落盘、能读回、能恢复，且不碰采样点与里程；
 *  4. 涂抹方案**已彻底删除**，界面只剩选点，且有明确的「确认保存」入口。
 */
class TrackFixTest {

    private fun p(lat: Double, lng: Double) = TripTileMap.Point(lat, lng)

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(relative: String): String = File(projectDir, relative).readText()

    // ---------------- 坐标换算 ----------------

    @Test
    fun `全局像素与经纬度来回换算误差在厘米级`() {
        val samples = listOf(
            28.6829 to 115.8575,  // 南昌
            39.9042 to 116.4074,  // 北京
            31.2304 to 121.4737,  // 上海
            -22.9068 to -43.1729,
            0.0 to 0.0,
        )
        for ((lat, lng) in samples) {
            val (x, y) = MiniTileMap.globalPixel(lat, lng, TripMapCamera.REF_ZOOM)
            val (backLat, backLng) = MiniTileMap.latLngOf(x, y, TripMapCamera.REF_ZOOM)
            assertTrue("纬度偏差过大：$lat → $backLat", kotlin.math.abs(lat - backLat) < 1e-9)
            assertTrue("经度偏差过大：$lng → $backLng", kotlin.math.abs(lng - backLng) < 1e-9)
        }
    }

    @Test
    fun `屏幕坐标经相机逆投影再转回经纬度 误差不到几米`() {
        val camera = TripMapCamera(
            centerX = 88_000_000.0,
            centerY = 26_000_000.0,
            zoom = 16.5f,
        )
        val (wx, wy) = MiniTileMap.globalPixel(28.6829, 115.8575, TripMapCamera.REF_ZOOM)
        val sx = camera.screenX(wx, 1080)
        val sy = camera.screenY(wy, 1920)
        // screenX/Y 返回 Float（Canvas 只吃 Float），在 18 级全局像素 ≈ 8800 万的量级上
        // 会有 1~2 个世界像素的舍入。按「屏幕上差多少」衡量才有意义。
        val backX = camera.worldX(sx, 1080)
        val backY = camera.worldY(sy, 1920)
        assertTrue(kotlin.math.abs(backX - wx) * camera.scale() < 2.0)
        assertTrue(kotlin.math.abs(backY - wy) * camera.scale() < 2.0)
        val (lat, lng) = MiniTileMap.latLngOf(backX, backY, TripMapCamera.REF_ZOOM)
        assertTrue(kotlin.math.abs(lat - 28.6829) * 111_320.0 < 5.0)
        assertTrue(kotlin.math.abs(lng - 115.8575) * 111_320.0 < 5.0)
    }

    // ---------------- 抽稀 ----------------

    @Test
    fun `抽稀丢掉原地打转的点但首尾一定保住`() {
        val first = p(28.6800, 115.8500)
        val last = p(28.6900, 115.8600)
        val points = listOf(
            first,
            p(28.680001, 115.850001), // 约 0.1 米
            p(28.680002, 115.850002),
            p(28.6850, 115.8550),
            last,
        )
        val out = TrackFix.simplify(points, 5.0)
        assertEquals(first, out.first())
        assertEquals(last, out.last())
        assertTrue(out.size < points.size)
    }

    @Test
    fun `超限等距抽稀保首尾 不许丢掉出发帧`() {
        val points = List(5000) { i -> p(28.68 + i * 0.000001, 115.85) }
        val out = TrackFix.cap(points, 200)
        assertEquals(200, out.size)
        assertEquals(points.first(), out.first())
        assertEquals(points.last(), out.last())
    }

    // ---------------- 区间替换 ----------------

    /** 一条「路口被判成往右绕」的线：0-1 正常，1/2/3 是错的绕路，4-5 回到正路。 */
    private fun wrongTurnLine() = listOf(
        p(28.6800, 115.8500), // 0 起点
        p(28.6810, 115.8500), // 1 路口前
        p(28.6815, 115.8515), // 2 往右绕（错）
        p(28.6820, 115.8515), // 3 往右绕（错）
        p(28.6825, 115.8500), // 4 回到正路
        p(28.6840, 115.8500), // 5 终点
    )

    /** 用户在「正确的左拐路」上点的两个途经点。 */
    private fun leftTurnPicks() = listOf(p(28.6815, 115.8497), p(28.6820, 115.8497))

    @Test
    fun `选点替换掉中间那段错的 首尾端点原样`() {
        val line = wrongTurnLine()
        val picks = leftTurnPicks()
        val planned = TrackFix.planFromPicks(line, picks)
        assertTrue(planned.ok)
        // 模拟导航补齐：首尾被高德挪走，中间是一串贴路的点
        val navigated = listOf(p(28.7000, 115.8700)) + picks + listOf(p(28.6000, 115.8000))
        val fixed = TrackFix.compose(line, planned.plan!!, navigated).line!!
        assertEquals("起点必须还钉在原始路线上", line.first(), fixed.first())
        assertEquals("终点必须还钉在原始路线上", line.last(), fixed.last())
        assertFalse("错的绕路点还在新线里", line[2] in fixed)
        assertFalse("错的绕路点还在新线里", line[3] in fixed)
        assertTrue("用户点的钉子要进线", picks[0] in fixed)
        assertFalse("被挪走的首尾不能进线", navigated.first() in fixed)
        assertFalse("被挪走的首尾不能进线", navigated.last() in fixed)
    }

    @Test
    fun `compose 只把补齐后的 middle 填回区间 其余段一个点都不动`() {
        val line = wrongTurnLine()
        val planned = TrackFix.planFromPicks(line, leftTurnPicks()).plan!!
        val snapped = listOf(p(28.7000, 115.8700)) +
            planned.middle.drop(1).dropLast(1).map { p(it.lat, it.lng + 0.0002) } +
            listOf(p(28.6000, 115.8000))
        val fixed = TrackFix.compose(line, planned, snapped).line!!
        assertEquals(line.first(), fixed.first())
        assertEquals(line.last(), fixed.last())
        assertEquals("接缝必须原样", line[planned.startIndex], fixed[planned.startIndex])
        assertEquals("区间之前不动", line.subList(0, planned.startIndex), fixed.subList(0, planned.startIndex))
        val tail = line.subList(planned.endIndex + 1, line.size)
        assertEquals("区间之后不动", tail, fixed.subList(fixed.size - tail.size, fixed.size))
    }

    @Test
    fun `选点规划保留用户每一个钉子 前后各补一个接缝点`() {
        val line = wrongTurnLine()
        val picks = leftTurnPicks()
        val planned = TrackFix.planFromPicks(line, picks)
        assertTrue(planned.ok)
        val seg = planned.plan!!
        assertTrue(seg.startIndex < seg.endIndex)
        assertEquals(line[seg.startIndex], seg.middle.first())
        assertEquals(line[seg.endIndex], seg.middle.last())
        assertTrue(picks[0] in seg.middle)
        assertTrue(picks[1] in seg.middle)
        assertEquals("首尾补锚点，中间原样", picks.size + 2, seg.middle.size)
    }

    @Test
    fun `倒着点途经点会自动反转 导航方向不受点选顺序影响`() {
        val line = wrongTurnLine()
        val picks = leftTurnPicks().reversed()
        val planned = TrackFix.planFromPicks(line, picks)
        assertTrue(planned.ok)
        val seg = planned.plan!!
        assertTrue(seg.startIndex < seg.endIndex)
        assertEquals(picks.last(), seg.middle[1])
        assertEquals(picks.first(), seg.middle[2])
    }

    @Test
    fun `选点太少 两点落同一处或挨太近都要拒绝并给提示`() {
        val line = wrongTurnLine()
        assertFalse(TrackFix.planFromPicks(line, emptyList()).ok)
        assertFalse(TrackFix.planFromPicks(line, listOf(p(28.6815, 115.8497))).ok)
        val same = TrackFix.planFromPicks(
            line,
            listOf(p(28.6810, 115.8500), p(28.6810001, 115.8500001)),
        )
        assertFalse(same.ok)
        assertNotNull(same.reason)
        val tooClose = TrackFix.planFromPicks(
            line,
            listOf(p(28.6810, 115.8500), p(28.68100002, 115.85000002)),
        )
        assertFalse(tooClose.ok)
    }

    @Test
    fun `修正不改变首尾端点 也就不影响起终点地址`() {
        val line = wrongTurnLine()
        val planned = TrackFix.planFromPicks(line, leftTurnPicks()).plan!!
        val fixed = TrackFix.compose(line, planned).line!!
        assertEquals(line.first().lat, fixed.first().lat, 0.0)
        assertEquals(line.first().lng, fixed.first().lng, 0.0)
        assertEquals(line.last().lat, fixed.last().lat, 0.0)
        assertEquals(line.last().lng, fixed.last().lng, 0.0)
    }

    // ---------------- 接线守门 ----------------

    @Test
    fun `修正结果能写进 TripRecord 也能读回来`() {
        val models = read("app/src/main/java/com/leapcontrol/app/trip/TripModels.kt")
        assertTrue("toJson 没写 fix", models.contains("put(\"fix\", TripRouteFix.toJson(it))"))
        assertTrue("fromJson 没读 fix", models.contains("TripRouteFix.fromJson(raw.optJSONArray(\"fix\"))"))
    }

    @Test
    fun `保存修正走 TripTracker 而不是直接改采样点`() {
        val tracker = read("app/src/main/java/com/leapcontrol/app/trip/TripTracker.kt")
        assertTrue("没接写回入口", tracker.contains("fun setRouteFix("))
        assertTrue("没有用 copy 覆盖 routeFix", tracker.contains("trip.copy(routeFix = fix)"))
        assertFalse("不许改采样点", tracker.contains("trip.copy(points ="))
    }

    @Test
    fun `有修正线时小图直接用它 不再请求高德规划`() {
        val preview = read("app/src/main/java/com/leapcontrol/app/ui/TripMapPreview.kt")
        assertTrue("没把修正线传进渲染", preview.contains("route = fix ?: route"))
        assertTrue("有 fix 时没跳过规划", preview.contains("val route = if (fix != null) null"))
        assertTrue("没把 fixKey 放进 produceState 的 key", preview.contains("fixKey,"))
        assertTrue("全屏层没拿到修正线", preview.contains("fixedRoute = fix"))
        assertTrue("全屏层没有保存回调", preview.contains("onSaveFix = onSaveFix"))
    }

    @Test
    fun `全屏页只留选点 且有明确的确认保存入口`() {
        val viewer = read("app/src/main/java/com/leapcontrol/app/ui/TripMapViewer.kt")
        assertTrue("没显示修正路线按钮", viewer.contains("text = \"修正路线\""))
        assertTrue("没把钉子交给选点规划", viewer.contains("TrackFix.planFromPicks(currentLine, picks)"))
        assertTrue("没把补齐结果拼回原线", viewer.contains("TrackFix.compose(baseLine, plan,"))
        assertTrue("没有按导航补齐", viewer.contains("\"按导航补齐\""))
        assertTrue("没有撤一点", viewer.contains("text = \"撤一点\""))
        assertTrue("没有撤销", viewer.contains("text = \"撤销\""))
        assertTrue("没有恢复原状", viewer.contains("text = \"恢复原状\""))
        assertTrue("没有取消", viewer.contains("text = \"取消\""))
        assertTrue("没有确认保存按钮", viewer.contains("text = \"确认保存\""))
        assertTrue("确认保存没接写回", viewer.contains("onSaveFix?.invoke(editedLine ?: line)"))
        assertTrue("保存后没有明确反馈", viewer.contains("hint = \"路线已保存\""))
        assertTrue("没有清掉修正的入口", viewer.contains("onSaveFix?.invoke(null)"))
        assertTrue("手势协程没按 editing 重启", viewer.contains("pointerInput(editing)"))
        val history = read("app/src/main/java/com/leapcontrol/app/ui/TripHistoryScreen.kt")
        assertTrue("历史页没接保存回调", history.contains("onSaveFix = saveFix"))
        val live = read("app/src/main/java/com/leapcontrol/app/ui/TripLivePanel.kt")
        assertFalse("进行中的行程不该能修（还没落档）", live.contains("onSaveFix"))
    }

    @Test
    fun `吸附回调由卡片层注入 全屏层不碰 Key`() {
        val preview = read("app/src/main/java/com/leapcontrol/app/ui/TripMapPreview.kt")
        val viewer = read("app/src/main/java/com/leapcontrol/app/ui/TripMapViewer.kt")
        assertTrue("没把补齐回调注入全屏层", preview.contains("onSnap = snap"))
        assertTrue("没用 Key 建补齐器", preview.contains("RouteSnap.build(amapKey, segment)"))
        assertFalse("全屏层不该收 Key", viewer.contains("amapKey"))
    }

    @Test
    fun `涂抹方案已彻底删除 不许残留任何画笔画法`() {
        val viewer = read("app/src/main/java/com/leapcontrol/app/ui/TripMapViewer.kt")
        val fix = read("app/src/main/java/com/leapcontrol/app/map/TrackFix.kt")
        val snap = read("app/src/main/java/com/leapcontrol/app/map/RouteSnap.kt")
        listOf(
            "FixMode", "routeEditGestures", "draft =", "RouteSnap.Mode",
            "text = \"涂抹\"", "text = \"选点\"", "仍按手画线保留", "graspRoad",
        ).forEach { leftover ->
            assertFalse("涂抹残留（界面）：$leftover", viewer.contains(leftover))
        }
        listOf("fun apply(", "fun plan(").forEach { leftover ->
            assertFalse("涂抹残留（纯逻辑）：$leftover", fix.contains(leftover))
        }
        assertFalse("补齐器里不该有轨迹纠偏", snap.contains("graspRoad"))
        assertFalse("补齐器里不该有模式枚举", snap.contains("enum class Mode"))
    }

    @Test
    fun `两个入口都把保存接到 runtime`() {
        val home = read("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt")
        val journal = read("app/src/main/java/com/leapcontrol/app/ui/JournalScreen.kt")
        assertTrue(home.contains("runtime.saveTripRouteFix(id, points)"))
        assertTrue(journal.contains("runtime.saveTripRouteFix(id, points)"))
    }
}
