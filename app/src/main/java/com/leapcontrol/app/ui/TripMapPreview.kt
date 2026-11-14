package com.leapcontrol.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.AmapPoint
import com.leapcontrol.app.map.RouteFold
import com.leapcontrol.app.map.RouteSnap
import com.leapcontrol.app.map.TripRoute
import com.leapcontrol.app.map.TripTileMap
import com.leapcontrol.app.trip.TripSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 一次渲染的产物：位图 + 到底有没有用上导航路线（决定底下那句口径怎么写）。 */
private data class TripMapState(
    val bitmap: Bitmap?,
    val routed: Boolean,
    /**
     * 这次画出来的路线（纠偏 / 规划后的点）。
     *
     * 存下来是为了「点开全屏」时**直接复用**：全屏层要重画折线，如果它自己再调一次
     * `TripRoute.build`，每点一次就多吃一次高德额度，还要多等一次网络。
     */
    val route: List<TripTileMap.Point>? = null,
)

/** 小图 / 全屏共用的口径说明：修正过的线要明说「里程不受影响」，别让用户以为数字也跟着改了。 */
private fun mapCaption(routed: Boolean, fixed: Boolean): String = when {
    fixed -> "已按你修正的路线显示 · 里程仍按表显里程计算，不受影响"
    routed -> "采样点已纠偏贴路，点间按导航路线补齐 · 不是车辆实际行驶轨迹"
    else -> "采样点直线连接示意，非实际行驶路线"
}

/**
 * 一段行程的地图轨迹卡：静态位图 + 起终点 + 折线 + 高德署名 + 口径说明。
 *
 * 复用 [MiniMapThumb] 那套「IO 线程拼瓦片 → produceState 出 Bitmap」的做法：
 *  - key 是（采样点列表 + 视口像素 + 深浅主题 + 主题色 + Key），不随重组反复联网；
 *  - 返回的是**普通图片**，不是 MapView —— 行程清单在 LazyColumn 里，MapView 会抢滚动手势；
 *  - 瓦片走高德公开栅格地址，不需要 Web Key；**只有「把点纠偏贴路 + 把直线补成道路」这一步要 Key**
 *    （[TripRoute] 先调轨迹纠偏，再调驾车路径规划）。没有 Key 或请求失败就退回直线，图上照实写。
 *
 * 两种画法的口径必须分开写，否则用户会把规划路线当成车辆真实轨迹：
 *  - 有 Key：「采样点已纠偏贴路，点间按导航路线补齐，非实际行驶轨迹」；
 *  - 没 Key：「采样点直线连接示意」。
 */
@Composable
internal fun TripMapPreview(
    points: List<TripSample>,
    modifier: Modifier = Modifier,
    mapHeight: Dp = 168.dp,
    /** 高德 Web Key：有它才把采样点之间的直线换成真实道路。 */
    amapKey: String = "",
    /** 自定义口径文案；null 时按「有没有用上导航路线」自动选一句。 */
    caption: String? = null,
    /** 点开放大后全屏页的标题（时间段 / 里程之类的摘要）。 */
    title: String = "行程轨迹",
    /** 点开放大后全屏页的副标题（起终点地址）。 */
    subtitle: String? = null,
    /**
     * 用户手工修正过的显示路线。非空时**直接用它画线，不再请求高德规划**：
     * 用户已经把这一段亲手改对了，再跑一次规划只会把它改回去、还白吃一次额度。
     */
    routeFix: List<TripTileMap.Point>? = null,
    /** 非空才在全屏页露出「修正路线」入口；传 null 表示清掉修正。 */
    onSaveFix: ((List<TripTileMap.Point>?) -> Unit)? = null,
) {
    val C = LocalAppColors.current
    val density = LocalDensity.current
    val dark = C.isDark
    val accentArgb = C.accent.toArgb()
    val strokePx = with(density) { 4.dp.toPx() }
    val dotPx = with(density) { 7.dp.toPx() }
    // 先去掉采样点里的「尖刺」（出去又几乎回到原处的点），再交给取景 / 规划 / 画线：
    // 那个点只要留在列表里，直线连接和导航补齐都会乖乖地画一个折返出来，
    // 而用户根本没有掉头过（2026-11-13 反馈）。
    val samples = remember(points) {
        RouteFold.cleanSamples(
            points.mapNotNull { p ->
                val lat = p.latitude
                val lng = p.longitude
                if (lat != null && lng != null) RouteFold.Sample(lat, lng, p.at) else null
            },
        )
    }
    val coords = remember(samples) {
        samples.map { TripTileMap.Point(it.lat, it.lng) }
    }
    // 显示线的长度预算：按表显总里程算，超出的部分只可能是规划器自己绕出来的
    val budgetM = remember(samples) {
        RouteFold.budgetM(
            points.firstOrNull { it.odometerKm != null }?.odometerKm,
            points.lastOrNull { it.odometerKm != null }?.odometerKm,
        )
    }
    val routeKey = remember(coords) {
        // 采样点变了才重新规划；同一段来回展开不重复吃额度
        coords.joinToString(";") { "${it.lat},${it.lng}" }
    }
    // 手工修正线也要进 produceState 的 key：保存 / 恢复原状之后小图得立刻重画
    val fixKey = remember(routeFix) {
        routeFix?.joinToString(";") { "${it.lat},${it.lng}" } ?: ""
    }
    val fix = remember(routeFix) { routeFix?.takeIf { it.size >= 2 } }
    // 点开全屏查看（见 [TripMapViewer]）
    var viewing by remember { mutableStateOf(false) }
    // 导航补齐只服务全屏的「修正路线」。Key 由这一层持有，全屏层只拿到一个
    // 无参回调，不碰 Key、也不自己再规划一遍整条路线（那样会把修好的路口又改回去）。
    val snap: ((List<TripTileMap.Point>) -> RouteSnap.Result)? = remember(amapKey) {
        if (amapKey.isBlank()) null
        else { segment -> RouteSnap.build(amapKey, segment) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        var routed by remember(routeKey, amapKey, fixKey) { mutableStateOf(false) }
        // 底部说明在这层 Box 外面，口径文案得在 Column 这一层算好
        val autoCaption = mapCaption(routed, fix != null)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(mapHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(C.barTrack.copy(alpha = 0.45f)),
        ) {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { mapHeight.roundToPx() }
            val state by produceState<TripMapState?>(
                initialValue = null,
                routeKey,
                widthPx,
                heightPx,
                dark,
                accentArgb,
                amapKey,
                fixKey,
                // 换了车 / 补上了表显里程，预算就变了，剪环的结果也会变
                budgetM ?: -1.0,
            ) {
                value = null
                value = withContext(Dispatchers.IO) {
                    if (coords.isEmpty() || widthPx <= 0 || heightPx <= 0) {
                        TripMapState(null, routed = false)
                    } else {
                        // 用户亲手改过的路线优先，而且不再联网规划：再规划一次只会把它改回去。
                        // 没修正才走原来的路径规划，失败（没 Key / 没网 / 额度用尽）退回直线。
                        val route = if (fix != null) null
                        else if (amapKey.isNotBlank() && coords.size >= 2) {
                            runCatching {
                                TripRoute.build(
                                    amapKey,
                                    coords.map { AmapPoint(it.lat, it.lng) },
                                    // 长度预算：规划线超出表显里程的那一截只可能是它自己绕出来的
                                    budgetM = budgetM,
                                )
                            }.getOrNull()?.map { TripTileMap.Point(it.latitude, it.longitude) }
                        } else null
                        val bitmap = runCatching {
                            TripTileMap.render(
                                points = coords,
                                widthPx = widthPx,
                                heightPx = heightPx,
                                dark = dark,
                                lineColor = accentArgb,
                                strokePx = strokePx,
                                dotPx = dotPx,
                                route = fix ?: route,
                            )
                        }.getOrNull()
                        TripMapState(bitmap, routed = route != null || fix != null, route = route)
                    }
                }
            }

            // 瓦片全 404 / 断网时不能一直停在「加载中」，给一句能看懂的兜底。
            val timedOut = remember(routeKey, widthPx, heightPx, dark, accentArgb) {
                mutableStateOf(false)
            }
            LaunchedEffect(routeKey, widthPx, heightPx, dark, accentArgb) {
                timedOut.value = false
                delay(15_000)
                timedOut.value = true
            }

            val image = state?.bitmap
            // 是否用上了导航路线要提到 Box 外面给底下的说明用
            LaunchedEffect(state) { routed = state?.routed == true }
            if (image != null) {
                Image(
                    bitmap = remember(image) { image.asImageBitmap() },
                    contentDescription = "这一段行程的地图轨迹",
                    // 点开全屏：小图只有 168dp 高，看不清具体拐到哪条路
                    modifier = Modifier
                        .fillMaxSize()
                        .noRippleClickable { viewing = true },
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            coords.isEmpty() -> "这一段没有读到坐标，画不出地图"
                            timedOut.value -> "地图暂时取不到，稍后再试"
                            else -> "地图加载中"
                        },
                        color = C.textSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Text(
                text = "© 高德地图",
                color = C.textSecondary,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(C.panelBg.copy(alpha = 0.85f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            // 右上角「放大」角标：既提示这张图可以点开，也当一个明确的入口
            if (image != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(C.panelBg.copy(alpha = 0.85f))
                        .noRippleClickable { viewing = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.OpenInFull,
                        contentDescription = "放大查看",
                        tint = C.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            if (viewing) {
                TripMapViewer(
                    points = coords,
                    // 复用已经规划好的路线：全屏层不再请求一次高德，点开是即时的
                    route = state?.route,
                    title = title,
                    subtitle = subtitle,
                    caption = caption ?: autoCaption,
                    fixedRoute = fix,
                    onSaveFix = onSaveFix,
                    onSnap = snap,
                    onClose = { viewing = false },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = caption ?: autoCaption,
            color = C.textSecondary,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
        )
    }
}

/**
 * 行程清单左侧的方形轨迹缩略图（方案 E：一段一张）。
 *
 * 跟 [TripMapPreview] 的区别：
 *  - **不走高德路径规划**。清单里一个月可能有几十段，每段都请求一次驾车路径既慢又吃额度；
 *    66dp 的方图只需要「看得出这趟往哪边开」，采样点连线就够了。
 *  - 不带署名与口径说明（那两行在展开后的完整地图里有），形状只有 66dp，塞不下。
 *
 * 取不到坐标（老数据 / 地库没信号）时给一句「无轨迹」，不留一块空白 —— 空白会被当成加载中。
 */
@Composable
internal fun TripThumbMap(
    points: List<TripSample>,
    modifier: Modifier = Modifier,
    side: Dp = 66.dp,
    /** 手工修正过的显示路线：清单里那张 48dp 小图也跟着改，别出现「缩略图和展开图不一样」。 */
    route: List<TripTileMap.Point>? = null,
) {
    val C = LocalAppColors.current
    val density = LocalDensity.current
    val dark = C.isDark
    val accentArgb = C.accent.toArgb()
    val sidePx = with(density) { side.roundToPx() }
    val strokePx = with(density) { 2.2.dp.toPx() }
    val dotPx = with(density) { 3.6.dp.toPx() }
    // 和展开图同一套清洗：缩略图里那个折返看起来更像「绕了一圈」，更得删
    val coords = remember(points) {
        RouteFold.cleanSamples(
            points.mapNotNull { p ->
                val lat = p.latitude
                val lng = p.longitude
                if (lat != null && lng != null) RouteFold.Sample(lat, lng, p.at) else null
            },
        ).map { TripTileMap.Point(it.lat, it.lng) }
    }
    val routeKey = remember(coords) { coords.joinToString(";") { "${it.lat},${it.lng}" } }
    val fixKey = remember(route) { route?.joinToString(";") { "${it.lat},${it.lng}" } ?: "" }

    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        routeKey,
        fixKey,
        sidePx,
        dark,
        accentArgb,
    ) {
        value = withContext(Dispatchers.IO) {
            if (coords.isEmpty() || sidePx <= 0) {
                null
            } else {
                runCatching {
                    TripTileMap.render(
                        points = coords,
                        widthPx = sidePx,
                        heightPx = sidePx,
                        dark = dark,
                        lineColor = accentArgb,
                        strokePx = strokePx,
                        dotPx = dotPx,
                        route = route?.takeIf { it.size >= 2 },
                    )
                }.getOrNull()
            }
        }
    }

    Box(
        modifier = modifier
            .size(side)
            .clip(RoundedCornerShape(8.dp))
            .background(C.barTrack.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = remember(image) { image.asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = if (coords.isEmpty()) "无轨迹" else "加载中",
                color = C.textSecondary.copy(alpha = 0.65f),
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
