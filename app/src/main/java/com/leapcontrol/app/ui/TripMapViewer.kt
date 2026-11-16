package com.leapcontrol.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.leapcontrol.app.map.MiniTileMap
import com.leapcontrol.app.map.RouteSnap
import com.leapcontrol.app.map.TileFrame
import com.leapcontrol.app.map.TrackFix
import com.leapcontrol.app.map.TripMapCamera
import com.leapcontrol.app.map.TripTileMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** 地图底色的兜底（瓦片还没回来时铺的底）：跟 [TripTileMap.render] 里那两行保持一致。 */
private val MAP_BG_DARK = Color(0xFF1C1E22)
private val MAP_BG_LIGHT = Color(0xFFEEF0EE)

/** 用户放的途经点用橙色，跟蓝色主路线分开。 */
private val PICK_COLOR = Color(0xFFFFA640)

/**
 * 行程轨迹的**全屏查看**（点卡片里那张地图进来）。
 *
 * ## 为什么自己画瓦片，不用 MapView
 * 项目里所有地图都是「拼瓦片成图」的路子（见 [MiniTileMap] / [TripTileMap] 的注释）：
 * MapView 会抢单指拖动，而行程清单在 LazyColumn 里。全屏虽然脱离了列表，但为一次查看
 * 引入一个地图控件 + 一套生命周期并不划算。这里直接用 Compose `Canvas` + 手势：
 * 瓦片按当前层级重新取、折线按相机投影现画，所以放大是"看得更细"，不是把位图拉糊。
 *
 * ## 版式（2026-11-13 按用户要求重排）
 * **地图铺满整屏，标题栏和底部说明是浮在地图上的两层。** 不要改回「标题栏 + 地图 + 说明」
 * 的竖排：Compose 的 `Canvas` 默认不裁剪，最上面一排瓦片会往画布外多画整整一格（最多
 * 256px），竖排时正好糊掉标题栏，连关闭按钮都点不到。
 *
 * **轨迹修正的全部按钮都在顶部「轨迹详情」下面**：标题行（时间段 / 里程 / 起终点地址）
 * 之后接着两行说明和两排按钮，最后一个是醒目的「确认保存」。原来放在底部会被系统手势区
 * 和窄屏裁掉，用户根本找不到保存入口。两层浮层的高度用 `onSizeChanged` 量出来交给
 * [TripMapCamera.fit] 当避让留白，所以"整段"取景的起终点永远落在两块浮层之间。
 *
 * ## 窗口避让
 * **别再给这个 Dialog 关掉系统栏避让**（把 `decorFitsSystemWindows` 设成 false）：那个模式下窗口铺到系统手势区
 * 后面，而 `navigationBarsPadding()` 拿到的 inset 恒为 0（同 `DestinationSearch` 里记过的坑）。
 * 用默认的窗口避让，系统自己把内容区收进安全区。
 *
 * 但默认避让下 `navigationBarsPadding()` **也是 0**（内容已在安全区内，Compose 再减一次就没了），
 * 于是「底部 8dp」在真机上就是贴着屏幕边 —— 用户反馈过「下方提示文字被手机遮挡」。
 * 现在底部留白走 [dialogBottomGap]：读窗口根 View 的导航栏高度（未经消费的那个值），
 * 读不到时给 32dp 兜底。提示浮层仍然钉在量出来的栏高之上，不占按钮行。
 *
 * ## 手势
 * 浏览态：单指拖动平移、双指捏合缩放（锚点下的地钉住不动，见 [TripMapCamera.zoomedBy]）。
 * 修正态：轻点放途经点、单指拖动平移、双指缩放。缩放值是连续的（Float），取瓦片时用最近整数
 * 层级 + 1~1.41 倍微放，所以不会跳档。
 *
 * ## 手工修正路线（只保留选点）
 * 路径规划偶尔把路口判反（该左拐却画成往右绕）。用户在正确的路上轻点几个途经点，
 * [onSnap] 按驾车导航把点与点之间的道路补齐；途经点是用户亲手放的，方向被强制住，
 * 不会重演判反。**手画涂抹方案已按用户要求删除** —— 画哪里都算、还得再吸附一次，
 * 不如直接让导航按途经点铺路。
 *
 * 只替换首尾钉子对应的那一小段，其余路线原样保留；可以连续修几段、逐段撤销，也能
 * 「恢复原状」退回自动路线。网络不可用时默认不落线（不画穿楼穿河的线），钉子留着重试。
 *
 * **只改「画出来的线」**：采样点、里程、时长、能耗都不动，里程本来就取表显里程差。
 * 保存走 [onSaveFix]，原始路线由调用方用 [fixedRoute] 传回来。
 *
 * ## 数据口径不变
 * 底部原样带上卡片里那句说明 —— 用户放大看的是「这趟大概怎么走的」，
 * 不能因为放大了就误以为是导航轨迹。
 */
@Composable
internal fun TripMapViewer(
    points: List<TripTileMap.Point>,
    route: List<TripTileMap.Point>?,
    title: String,
    subtitle: String?,
    caption: String,
    onClose: () -> Unit,
    /** 用户已经保存过的手工修正线；null = 用自动生成的那条。 */
    fixedRoute: List<TripTileMap.Point>? = null,
    /** 非空才显示「修正路线」入口。传 null 表示清掉修正、恢复自动路线。 */
    onSaveFix: ((List<TripTileMap.Point>?) -> Unit)? = null,
    /**
     * 把**被替换的那一小段**按导航补齐。阻塞调用，由本层放到 IO 线程。
     * null = 没配 Key：给一句提示，钉子留着让用户重试。
     */
    onSnap: ((List<TripTileMap.Point>) -> RouteSnap.Result)? = null,
) {
    val C = LocalAppColors.current
    val density = LocalDensity.current
    val dark = C.isDark
    val lineColor = C.accent
    val nightFilter = remember(dark) {
        if (dark) ColorFilter.colorMatrix(ColorMatrix(MiniTileMap.nightMatrix())) else null
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    // 画线用 route（有的话），端点仍钉在真实采样点上
    var editedLine by remember { mutableStateOf<List<TripTileMap.Point>?>(null) }
    var editing by remember { mutableStateOf(false) }
    var undoStack by remember { mutableStateOf<List<List<TripTileMap.Point>>>(emptyList()) }
    var hint by remember { mutableStateOf<String?>(null) }
    /** 用户点的途经点，存经纬度，缩放 / 平移时钉子跟着地图走。 */
    var picks by remember { mutableStateOf<List<TripTileMap.Point>>(emptyList()) }
    /** 补齐的请求在飞：锁住编辑，免得回来时区间下标已经对不上。 */
    var snapping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val line = editedLine
        ?: fixedRoute?.takeIf { it.size >= 2 }
        ?: route?.takeIf { it.size >= 2 }
        ?: points
    // 手势协程的 key 里有 editing，循环里读到的必须是「此刻」的线 / 尺寸，不能是进协程那一刻的旧值
    val currentLine by rememberUpdatedState(line)
    val currentSize by rememberUpdatedState(size)
    // 全局像素坐标预计算一次：拖动 / 缩放每帧都要用，别在 draw 里反复算三角函数
    val lineWorld = remember(line) {
        line.map { MiniTileMap.globalPixel(it.lat, it.lng, TripMapCamera.REF_ZOOM) }
    }
    val startWorld = remember(points) {
        points.firstOrNull()?.let { MiniTileMap.globalPixel(it.lat, it.lng, TripMapCamera.REF_ZOOM) }
    }
    val endWorld = remember(points) {
        points.lastOrNull()?.let { MiniTileMap.globalPixel(it.lat, it.lng, TripMapCamera.REF_ZOOM) }
    }
    val picksWorld = remember(picks) {
        picks.map { MiniTileMap.globalPixel(it.lat, it.lng, TripMapCamera.REF_ZOOM) }
    }

    // 顶部标题栏（含编辑按钮）/ 底部说明是浮在地图上的，量出高度交给取景避让（见 fitCamera）
    var headerPx by remember { mutableStateOf(0) }
    var footerPx by remember { mutableStateOf(0) }
    var camera by remember { mutableStateOf<TripMapCamera?>(null) }
    // 用户自己拖过 / 捏过了：就别再用取景结果覆盖他的视角
    var touched by remember { mutableStateOf(false) }
    val sideInsetPx = with(density) { 24.dp.roundToPx() }

    // "整段装进屏幕"那个视角：初始取景和「整段」按钮共用同一个结果
    val fitCamera = remember(line, size, headerPx, footerPx, sideInsetPx) {
        if (size.width > 0 && size.height > 0) {
            TripMapCamera.fit(line, size.width, size.height, headerPx, footerPx, sideInsetPx)
        } else null
    }
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    // 断网 / 瓦片全 404 时不能永远写着"加载中"：12 秒还没一块图回来就说清楚
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(12_000)
        slow = true
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2_600)
            hint = null
        }
    }

    /**
     * 把一次修正落到线上：先把这一小段交给上层按导航补齐（IO 线程），再拼回原线。
     *
     *  - 只把 [plan] 的 middle 送出去，整条线不动 —— 否则本来对的段会被重新规划改坏；
     *  - 请求回来时仍用发起那一刻的线，区间下标才有效；
     *  - 没补齐成功**默认不落线**（不贴路的线会穿楼穿河），钉子留着让用户重试。
     */
    fun commit(
        plan: TrackFix.Plan,
        middle: List<TripTileMap.Point>,
        clear: () -> Unit,
    ) {
        if (snapping) return
        val baseLine = currentLine
        val callback = onSnap
        if (callback == null) {
            hint = "按导航补齐需要高德 Web Key（在设置里填）"
            return
        }
        scope.launch(Dispatchers.Main) {
            snapping = true
            val res = withContext(Dispatchers.IO) {
                runCatching { callback(middle) }.getOrNull()
            }
            snapping = false
            val snapped = res?.points
            if (snapped == null) {
                hint = res?.reason ?: "导航补齐没成功，检查网络或删掉几个途经点再试"
                return@launch
            }
            val composed = TrackFix.compose(baseLine, plan, snapped)
            if (composed.line == null) {
                hint = composed.reason ?: "这一段没修成，再试一次"
                return@launch
            }
            undoStack = undoStack + listOf(baseLine)
            editedLine = composed.line
            clear()
            hint = "已按导航补齐，记得点「确认保存」"
        }
    }

    // 首次拿到尺寸、浮层量出高度、以及横竖屏切换后按整段取景；用户拖过之后不再覆盖
    LaunchedEffect(fitCamera, touched) {
        val fit = fitCamera ?: return@LaunchedEffect
        if (!touched) camera = fit
    }

    val headerDp = with(density) { headerPx.toDp() }
    val footerDp = with(density) { footerPx.toDp() }

    Dialog(
        onDismissRequest = onClose,
        // 默认开启系统栏避让：系统把窗口收进安全区（见文件头「窗口避让」）。
        // 跟 PhotoPreviewDialog 的全屏写法一致。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // ## 版式：地图铺满整屏，标题栏 / 说明是浮在地图上的
        // 原来用 Column（标题栏 + weight(1f) 地图 + 底部说明）叠着排，两个坑：
        //  1. Compose 的 Canvas **默认不裁剪**，最上面一排瓦片会往画布外多画整整一格
        //     （最多 256px），正好糊住标题栏——用户看到的就是「退出按钮和起终点都被盖住」；
        //  2. 对话框里带 weight 的列一旦算错高度，底部说明会被挤成一条缝。
        // 改成 Box + 贴边浮层之后：地图只有一层、自己裁自己，按钮和说明永远在最上面，
        // 取景再用「可见带」避让浮层（见 TripMapCamera.fit 的 top/bottomInsetPx）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(C.bgTop),
        ) {
            // ---------------- 地图层：整屏，手势只在这一层 ----------------
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { size = it },
            ) {
                val cam = camera
                if (cam == null) {
                    Text(
                        text = if (line.isEmpty()) "这一段没有读到坐标，画不出地图" else "地图加载中",
                        color = C.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    // 组合期算一次帧：既给协程当 key，绘制时也不用重复算
                    val frame = cam.tileFrame(size.width, size.height)
                    LaunchedEffect(frame.key) { loadTiles(frame, tiles) }
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(editing) {
                                if (!editing) {
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        val cur = camera ?: return@detectTransformGestures
                                        // 先按锚点缩放、再叠本帧的质心位移，两指同时捏 + 拖不会打架
                                        var next = cur
                                        if (zoom != 1f) {
                                            next = next.zoomedBy(
                                                zoom,
                                                centroid.x,
                                                centroid.y,
                                                size.width,
                                                size.height,
                                            )
                                        }
                                        if (pan != Offset.Zero) next = next.panned(pan.x, pan.y)
                                        touched = true
                                        camera = next
                                    }
                                } else {
                                    // 修正态：轻点放途经点，单指拖动平移、双指缩放，拖动结束绝不落点
                                    routePickGestures(
                                        onTap = { pos ->
                                            val camAtTap = camera
                                            val limit = RouteSnap.MAX_PICKS - 2
                                            if (!snapping && camAtTap != null && picks.size < limit) {
                                                val (lat, lng) = MiniTileMap.latLngOf(
                                                    camAtTap.worldX(pos.x, currentSize.width),
                                                    camAtTap.worldY(pos.y, currentSize.height),
                                                    TripMapCamera.REF_ZOOM,
                                                )
                                                picks = picks + TripTileMap.Point(lat, lng)
                                                hint = null
                                            } else if (picks.size >= limit) {
                                                hint = "途经点最多 $limit 个"
                                            }
                                        },
                                        onTransform = { centroid, pan, zoom ->
                                            val cur = camera
                                            if (cur != null) {
                                                var next = cur
                                                if (zoom != 1f) {
                                                    next = next.zoomedBy(
                                                        zoom,
                                                        centroid.x,
                                                        centroid.y,
                                                        currentSize.width,
                                                        currentSize.height,
                                                    )
                                                }
                                                if (pan != Offset.Zero) next = next.panned(pan.x, pan.y)
                                                touched = true
                                                camera = next
                                            }
                                        },
                                    )
                                }
                            },
                    ) {
                        drawRect(if (dark) MAP_BG_DARK else MAP_BG_LIGHT)
                        drawTiles(tiles, frame, nightFilter)
                        drawTrack(
                            camera = cam,
                            lineWorld = lineWorld,
                            startWorld = startWorld,
                            endWorld = endWorld,
                            size = size,
                            lineColor = lineColor,
                            strokePx = with(density) { 3.5.dp.toPx() },
                            dotPx = with(density) { 7.dp.toPx() },
                            picks = picksWorld,
                        )
                    }

                    // 瓦片还没回来时给一句话：不然是一整块纯色，看着像坏了
                    if (tiles.isEmpty()) {
                        Text(
                            text = if (slow) "地图暂时取不到，稍后再试" else "地图加载中",
                            color = if (slow) C.textPrimary else C.textSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }

            // ---------------- 署名：钉在地图上、让开底部说明 ----------------
            Text(
                text = "© 高德地图",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = footerDp + 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )

            // ---------------- 临时提示：浮在底部栏上方，不占栏内高度 ----------------
            // 塞进栏里会多出一行，出现 / 消失时按钮还会上下跳。
            hint?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 20.dp, end = 20.dp, bottom = footerDp + 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (dark) Color(0xF021242B) else Color(0xE6323338))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }

            // ---------------- 右上角胶囊：只在浏览态（修正按钮全在顶部详情下方） ----------------
            if (!editing) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = headerDp + 8.dp, end = 10.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (fitCamera != null && !sameView(camera, fitCamera)) {
                        Text(
                            text = "整段",
                            color = C.textPrimary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (dark) Color(0xE621242B) else Color(0xE6FFFFFF))
                                .noRippleClickable {
                                    camera = fitCamera
                                    touched = false
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (onSaveFix != null) {
                        MapChip(
                            text = "修正路线",
                            primary = true,
                            onClick = {
                                // 进编辑态先锁住视角：顶部操作栏会比浏览态标题栏高得多，
                                // 取景重算时不该把用户刚对准的那个路口又拉回「整段」。
                                editedLine = line
                                undoStack = emptyList()
                                hint = null
                                picks = emptyList()
                                touched = true
                                editing = true
                            },
                        )
                    }
                }
            }

            // ---------------- 顶部：轨迹详情 + 修正操作栏（都在这一层） ----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to C.bgTop,
                            0.52f to C.bgTop.copy(alpha = 0.94f),
                            1f to C.bgTop.copy(alpha = 0.86f),
                        ),
                    )
                    .onSizeChanged { headerPx = it.height }
                    .statusBarsPadding()
                    .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .noRippleClickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = C.textPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = C.textPrimary,
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = subtitle,
                                color = C.textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 修正态：说明与全部按钮紧跟在轨迹详情下方，字大、点击区大、永远完整可见
                if (editing) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "在正确的路上轻点放途经点，至少 2 个",
                        color = C.textPrimary,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            snapping -> "正在按导航补齐这一段…"
                            else -> "已点 ${picks.size} 个 · 只改显示的路线，里程不变"
                        },
                        color = C.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 第一排：改钉子 + 按导航补齐
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MapChip(
                            text = "撤一点",
                            enabled = !snapping && picks.isNotEmpty(),
                            onClick = { picks = picks.dropLast(1) },
                        )
                        Spacer(Modifier.weight(1f))
                        MapChip(
                            text = if (snapping) "处理中…" else "按导航补齐",
                            primary = true,
                            enabled = !snapping && picks.size >= 2,
                            onClick = {
                                val planned = TrackFix.planFromPicks(currentLine, picks)
                                val segment = planned.plan
                                if (segment != null) {
                                    commit(segment, segment.middle) { picks = emptyList() }
                                } else {
                                    hint = planned.reason ?: "再点几个途经点试试"
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // 第二排：撤销 / 恢复原状 / 取消 / 确认保存 —— 保存是主按钮，最醒目
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MapChip(
                            text = "撤销",
                            enabled = undoStack.isNotEmpty() && !snapping,
                            onClick = {
                                val prev = undoStack.lastOrNull()
                                if (prev != null) {
                                    editedLine = prev
                                    undoStack = undoStack.dropLast(1)
                                    hint = null
                                }
                            },
                        )
                        MapChip(
                            text = "恢复原状",
                            enabled = !snapping,
                            onClick = {
                                onSaveFix?.invoke(null)
                                // 上层 StateFlow 重发要到下一帧才到，直接把线设成自动路线，
                                // 免得这一瞬间又闪回刚清掉的那份修正。
                                editedLine = route?.takeIf { it.size >= 2 } ?: points
                                undoStack = emptyList()
                                picks = emptyList()
                                editing = false
                                hint = "已恢复自动路线"
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        MapChip(
                            text = "取消",
                            enabled = !snapping,
                            onClick = {
                                editedLine = null
                                undoStack = emptyList()
                                hint = null
                                picks = emptyList()
                                editing = false
                            },
                        )
                        MapChip(
                            text = "确认保存",
                            primary = true,
                            enabled = !snapping,
                            onClick = {
                                onSaveFix?.invoke(editedLine ?: line)
                                undoStack = emptyList()
                                picks = emptyList()
                                editing = false
                                hint = "路线已保存"
                            },
                        )
                    }
                }
            }

            // ---------------- 底部说明：浮层 ----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            // 起始就给足底色：透明渐变会让地图线条从说明背后透上来。
                            0f to C.bgTop.copy(alpha = 0.86f),
                            0.32f to C.bgTop.copy(alpha = 0.96f),
                            1f to C.bgTop,
                        ),
                    )
                    .onSizeChanged { footerPx = it.height }
                    .padding(
                        start = 14.dp,
                        end = 14.dp,
                        top = 8.dp,
                        // 底部说明与屏幕底边之间要有**真实**的距离：这个全屏 Dialog 里
                        // `navigationBarsPadding()` 拿到的是 0（原因见 DialogInsets.kt），
                        // 只加 8dp 会让最后一行提示压在导航栏 / 手势条上（用户报过两次）。
                        bottom = dialogBottomGap(extra = 10.dp),
                    ),
            ) {
                if (editing) {
                    Text(
                        text = "拖动平移 · 双指缩放 · 点错了用顶部「撤一点」",
                        color = C.textPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                } else {
                    Text(
                        text = "双指缩放、单指拖动查看细节",
                        color = C.textPrimary,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = caption,
                        color = C.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

/**
 * 全屏地图上的按钮：字号 13sp、点击区至少 38dp。
 *
 * 用户反馈「二级界面字体太小、按钮看不到也点不到」——这一版所有按钮统一放大，
 * 文字垂直居中，primary = 主题色实心（「按导航补齐」和「确认保存」）。
 *
 * 2026-11-16 再改一次配色（用户截图圈了「撤一点 / 撤销 / 按导航补齐 / 取消」说看不清）：
 *  - 原来不可用的底色是「轨道色（20% 黑）再 `copy(alpha = 0.6f)`」。`copy` 是**替换** alpha 而不是乘，
 *    20% 黑被改成 60% 黑，于是按钮成了深灰块；文字又只有 60% 不透明度的灰，
 *    深灰压浅灰 —— 「撤一点」「撤销」「按导航补齐」三个全糊在一起。
 *  - 可用的次要按钮是 90% 白，压在同样近白的标题栏上，边界完全看不出来（「取消」）。
 * 现在：次要按钮白底 + 1dp 发丝描边定边界；不可用只降信息量、不换深色底，
 * 文字用完整的 [AppColors.textSecondary]（不再乘 alpha）；主按钮不可用时仍留在主题色系里。
 */
@Composable
private fun MapChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val C = LocalAppColors.current
    val shape = RoundedCornerShape(11.dp)
    val bg = when {
        primary && enabled -> C.accent
        // 处理中 / 还不满足条件：淡主题色，仍看得出「这是那个主操作」
        primary -> C.accent.copy(alpha = 0.16f)
        !enabled -> if (C.isDark) Color(0x0DFFFFFF) else Color(0x08000000)
        C.isDark -> Color(0x1CFFFFFF)
        else -> Color(0xFFFFFFFF)
    }
    val fg = when {
        primary && enabled -> Color.White
        primary -> C.accent
        enabled -> C.textPrimary
        // 不乘 alpha：灰字压灰底等于没字
        else -> C.textSecondary
    }
    // 次要按钮一律给出边界：白底 chip 压在近白标题栏上，没有描边就像一行裸字
    val edge = if (primary) null else if (C.isDark) Color(0x2EFFFFFF) else Color(0x2E000000)
    Text(
        text = text,
        color = fg,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        maxLines = 1,
        modifier = modifier
            .heightIn(min = 38.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .clip(shape)
            .background(bg)
            .then(edge?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
            .noRippleClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * 修正态的手势：**轻点放一个途经点、单指拖动平移、双指缩放**。
 *
 * 「轻点」用累计位移超过系统 [androidx.compose.ui.platform.ViewConfiguration.touchSlop] 来判：
 * 只看单帧位移的话，慢慢拖 100px、每帧才 1px，会被当成没动，抬手时凭空多落一个钉子。
 *
 * 双指一出现就把这次手势标记成「不是点击」，剩下那一根手指也只消费事件 ——
 * 缩放完松开一指时不会在地图上多冒出一个点。
 */
private suspend fun PointerInputScope.routePickGestures(
    onTap: (Offset) -> Unit,
    onTransform: (Offset, Offset, Float) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        while (true) {
            val first = awaitFirstDown(requireUnconsumed = false)
            val start = first.position
            var last = start
            var drift = 0f
            var multiTouch = false
            var prevCentroid: Offset? = null
            var prevSpan = 0f
            var alive = true
            while (alive) {
                val event = awaitPointerEvent()
                val down = event.changes.filter { it.pressed }
                when {
                    down.size >= 2 -> {
                        multiTouch = true
                        val centroid = Offset(
                            down.map { it.position.x }.average().toFloat(),
                            down.map { it.position.y }.average().toFloat(),
                        )
                        val a = down[0].position
                        val b = down[1].position
                        val span = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
                        val prev = prevCentroid
                        if (prev != null && prevSpan > 0f && span > 0f) {
                            onTransform(centroid, centroid - prev, span / prevSpan)
                        }
                        prevCentroid = centroid
                        prevSpan = span
                        event.changes.forEach { it.consume() }
                    }
                    down.size == 1 -> {
                        val p = down[0]
                        if (multiTouch) {
                            // 多指结束后余下那根手指只消费事件：不平移、更不落点
                            p.consume()
                        } else {
                            val dx = p.position.x - last.x
                            val dy = p.position.y - last.y
                            drift += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            if (dx != 0f || dy != 0f) {
                                // zoom 传 1f：单指只平移，相机跟着手指走
                                onTransform(p.position, Offset(dx, dy), 1f)
                            }
                            last = p.position
                            p.consume()
                        }
                    }
                    else -> alive = false
                }
            }
            if (!multiTouch && drift <= slop) onTap(start)
        }
    }
}

/** 相机是否还停在「整段装进屏幕」那个初始视角上（决定「整段」小胶囊要不要露出来）。 */
private fun sameView(camera: TripMapCamera?, fit: TripMapCamera?): Boolean {
    val cam = camera ?: return true
    fit ?: return true
    // 缩放差半档以内、中心点在屏幕上挪动不到 2px，就认为还是同一个视角
    if (kotlin.math.abs(cam.zoom - fit.zoom) > 0.5f) return false
    val k = cam.scale()
    return hypot(cam.centerX - fit.centerX, cam.centerY - fit.centerY) * k < 2.0
}

/**
 * 把当前帧需要的瓦片取回来填进 [tiles]。
 *
 * 分小批并发：一块一块串行取，一屏 40 块要等十几秒；一口气 40 个请求又容易把
 * 高德的公开瓦片地址打限流。8 个一批，体感最快也不至于被掐。
 *
 * 取图在 IO 线程，**写 map 回到调用方所在的（主）线程** —— 别在后台线程直接改
 * Compose 的 State，那属于自己给自己埋雷。
 */
private suspend fun loadTiles(frame: TileFrame, tiles: MutableMap<String, ImageBitmap>) {
    val missing = buildList {
        for (row in frame.minRow..frame.maxRow) {
            for (col in frame.minCol..frame.maxCol) {
                val key = "${frame.zoom}/$col/$row"
                if (tiles[key] == null) add(key to (col to row))
            }
        }
    }
    if (missing.isEmpty()) return
    missing.chunked(8).forEach { chunk ->
        val batch = withContext(Dispatchers.IO) {
            coroutineScope {
                chunk.map { (key, pos) ->
                    async {
                        MiniTileMap.tile(frame.zoom, pos.first, pos.second)
                            ?.let { key to it.asImageBitmap() }
                    }
                }.awaitAll()
            }
        }
        batch.forEach { loaded ->
            if (loaded != null) tiles[loaded.first] = loaded.second
        }
    }
}

/** 贴瓦片。左上向外取整、右下向外取整，避免相邻瓦片之间露 1px 的缝。 */
private fun DrawScope.drawTiles(
    tiles: Map<String, ImageBitmap>,
    frame: TileFrame,
    nightFilter: ColorFilter?,
) {
    if (frame.tileCount <= 0) return
    val side = frame.tileSize
    for (row in frame.minRow..frame.maxRow) {
        for (col in frame.minCol..frame.maxCol) {
            val image = tiles["${frame.zoom}/$col/$row"] ?: continue
            val left = frame.left(col)
            val top = frame.top(row)
            val l = floor(left).toInt()
            val t = floor(top).toInt()
            val r = ceil(left + side).toInt()
            val b = ceil(top + side).toInt()
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(l, t),
                dstSize = IntSize(r - l, b - t),
                filterQuality = FilterQuality.Medium,
                colorFilter = nightFilter,
            )
        }
    }
}

/** 折线（先白描边再主色）+ 起终点。线宽固定屏幕尺寸，放大地图时线不会粗成一条带。 */
private fun DrawScope.drawTrack(
    camera: TripMapCamera,
    lineWorld: List<Pair<Double, Double>>,
    startWorld: Pair<Double, Double>?,
    endWorld: Pair<Double, Double>?,
    size: IntSize,
    lineColor: Color,
    strokePx: Float,
    dotPx: Float,
    picks: List<Pair<Double, Double>> = emptyList(),
) {
    if (lineWorld.size >= 2) {
        val path = Path()
        lineWorld.forEachIndexed { i, (wx, wy) ->
            val x = camera.screenX(wx, size.width)
            val y = camera.screenY(wy, size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.86f),
            style = Stroke(width = strokePx + 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
    // 途经点之间连一条细橙线示意顺序，再画带白边的钉子。
    // 存的是经纬度换算出来的世界像素，所以缩放 / 平移时钉子会跟着地图走，不会漂在屏幕上。
    if (picks.isNotEmpty()) {
        if (picks.size >= 2) {
            val pickPath = Path()
            picks.forEachIndexed { i, (wx, wy) ->
                val x = camera.screenX(wx, size.width)
                val y = camera.screenY(wy, size.height)
                if (i == 0) pickPath.moveTo(x, y) else pickPath.lineTo(x, y)
            }
            drawPath(
                path = pickPath,
                color = PICK_COLOR,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        picks.forEach { (wx, wy) ->
            val at = Offset(camera.screenX(wx, size.width), camera.screenY(wy, size.height))
            drawCircle(Color.White, dotPx + 2f, at)
            drawCircle(PICK_COLOR, dotPx, at)
        }
    }
    startWorld?.let { (wx, wy) ->
        val at = Offset(camera.screenX(wx, size.width), camera.screenY(wy, size.height))
        drawCircle(Color.White, dotPx + 2f, at)
        drawCircle(lineColor, dotPx, at)
        drawCircle(Color.White, (dotPx - 2.5f).coerceAtLeast(1.5f), at)
    }
    if (startWorld != null && endWorld != null &&
        startWorld.first == endWorld.first && startWorld.second == endWorld.second
    ) {
        return
    }
    endWorld?.let { (wx, wy) ->
        val at = Offset(camera.screenX(wx, size.width), camera.screenY(wy, size.height))
        drawCircle(Color.White, dotPx + 2f, at)
        drawCircle(lineColor, dotPx, at)
    }
}
