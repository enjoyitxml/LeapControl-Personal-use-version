package com.leapcontrol.app.map

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 全屏轨迹地图的相机：一个中心点 + 一个**连续**层级。
 *
 * ## 为什么中心点存在 18 级的全局像素坐标系里
 * 拖动 / 缩放时如果中心点存在「当前层级」的像素坐标里，每换一次层级都要重新折算，
 * 小数缩放再来回乘除，几十次手势之后就会漂出几十米。这里统一折算到 [REF_ZOOM] 级
 * 全局像素（Double，世界边长 2^18 × 256 ≈ 6700 万），任何层级都只是乘一个 2 的幂。
 *
 * ## 为什么 zoom 是 Float 不是 Int
 * 双指缩放要是整档跳，手感像在掰开关。连续 zoom 配「取最近整数层级瓦片 + 按小数比例
 * 放大」，视觉上是平滑的；倍率永远落在 1~√2 之间，不会糊。
 *
 * 纯计算，不碰 Bitmap 也不联网，JVM 单测直接守「初始取景 / 缩放锚点不动 / 边界夹取」。
 */
data class TripMapCamera(
    val centerX: Double,
    val centerY: Double,
    val zoom: Float,
) {

    /** 一个世界像素（[REF_ZOOM] 级）在屏幕上占几个像素。 */
    fun scale(): Double = 2.0.pow((zoom - REF_ZOOM).toDouble())

    fun screenX(worldX: Double, widthPx: Int): Float =
        ((worldX - centerX) * scale() + widthPx / 2.0).toFloat()

    fun screenY(worldY: Double, heightPx: Int): Float =
        ((worldY - centerY) * scale() + heightPx / 2.0).toFloat()

    fun worldX(screenX: Float, widthPx: Int): Double =
        centerX + (screenX - widthPx / 2.0) / scale()

    fun worldY(screenY: Float, heightPx: Int): Double =
        centerY + (screenY - heightPx / 2.0) / scale()

    /**
     * 单指拖动：[dx] / [dy] 是手指在屏幕上的位移，方向跟地图一致
     * （手指往右拖，地图跟着往右走，所以中心点要往左挪）。
     */
    fun panned(dx: Float, dy: Float): TripMapCamera {
        if (!dx.isFinite() || !dy.isFinite()) return this
        val k = scale()
        return copy(centerX = centerX - dx / k, centerY = centerY - dy / k).snapped()
    }

    /**
     * 双指缩放：[factor] > 1 放大。**锚点（[anchorX], [anchorY]）下的那块地必须钉住不动**，
     * 否则两指一捏地图整体往左上跑，手感非常差。
     */
    fun zoomedBy(
        factor: Float,
        anchorX: Float,
        anchorY: Float,
        widthPx: Int,
        heightPx: Int,
    ): TripMapCamera {
        if (!factor.isFinite() || factor <= 0f) return this
        val next = clampZoom(zoom + (ln(factor.toDouble()) / LN_2).toFloat())
        if (next == zoom) return this
        val wx = worldX(anchorX, widthPx)
        val wy = worldY(anchorY, heightPx)
        val k = 2.0.pow((next - REF_ZOOM).toDouble())
        return TripMapCamera(
            centerX = wx - (anchorX - widthPx / 2.0) / k,
            centerY = wy - (anchorY - heightPx / 2.0) / k,
            zoom = next,
        ).snapped()
    }

    /**
     * 算出这一帧该贴哪些瓦片、每块贴到哪。
     *
     * 取「离 zoom 最近的整数层级」的瓦片，再按 `2^(zoom - 层级)` 放大 —— 每块瓦片
     * 被拉伸的倍数永远在 0.71~1.41 之间，肉眼看不出发虚。
     */
    fun tileFrame(widthPx: Int, heightPx: Int): TileFrame {
        val z = zoom.roundToInt().coerceIn(TripTileMap.MIN_ZOOM, TripTileMap.MAX_ZOOM)
        val k = scale()
        // 一个 z 级瓦片像素 = 2^(18-z) 个世界像素 = 2^(zoom-z) 个屏幕像素
        val perTilePx = 2.0.pow((zoom - z).toDouble())
        val worldLeft = centerX - widthPx / 2.0 / k
        val worldTop = centerY - heightPx / 2.0 / k
        val worldPerZPx = 2.0.pow((REF_ZOOM - z).toDouble())
        val leftZ = worldLeft / worldPerZPx
        val topZ = worldTop / worldPerZPx
        val widthZ = widthPx / perTilePx
        val heightZ = heightPx / perTilePx
        // 右边 / 下边减一个极小值：正好压在瓦片边界上时别多取一列
        val eps = 1e-6
        return TileFrame(
            zoom = z,
            perTilePx = perTilePx.toFloat(),
            leftZ = leftZ,
            topZ = topZ,
            minCol = floor(leftZ / TripTileMap.TILE).toInt(),
            maxCol = floor((leftZ + widthZ - eps) / TripTileMap.TILE).toInt(),
            minRow = floor(topZ / TripTileMap.TILE).toInt(),
            maxRow = floor((topZ + heightZ - eps) / TripTileMap.TILE).toInt(),
        )
    }

    private fun snapped(): TripMapCamera = copy(
        centerX = centerX.coerceIn(0.0, WORLD_SIZE),
        centerY = centerY.coerceIn(0.0, WORLD_SIZE),
    )

    companion object {
        /**
         * 参考层级：中心点坐标就存在这一级的全局像素里。取瓦片上限 18（高德 z=19 只回纯色空图），
         * 拿它当基准可以省掉一层换算。
         */
        const val REF_ZOOM = 18

        /** 最远 3 级（世界 2048px）：跨省长途也装得下。 */
        const val MIN_ZOOM = 3f
        const val MAX_ZOOM = 18f

        private const val LN_2 = 0.6931471805599453

        val WORLD_SIZE: Double get() = TripTileMap.TILE.toDouble() * (1 shl REF_ZOOM)

        fun clampZoom(z: Float): Float =
            if (!z.isFinite()) MIN_ZOOM else z.coerceIn(MIN_ZOOM, MAX_ZOOM)

        /**
         * 初始取景：整段路线（含留白）刚好装进视口 —— 跟卡片里那张小图的取景口径一致，
         * 只是换成全屏尺寸重算一次，所以"点开放大"看到的就是同一个画面、更清楚。
         *
         * ## 为什么要留 [topInsetPx] / [bottomInsetPx]
         * 全屏查看是**地图铺满、标题栏和底部说明浮在上面**的版式（见 `TripMapViewer`）。
         * 只按整屏算取景的话，路线会装进屏幕正中、却正好被浮层压住 —— 用户看到的
         * 就是「点开放大后顶部的退出按钮和起终点都被盖住了」。所以取景要算在
         * 「两块浮层之间那条可见带」里，再把带子的中心补回画布中心。
         *
         * 三个留白都是 0 时，结果与「整屏居中取景」完全一致（老口径不变）。
         */
        fun fit(
            points: List<TripTileMap.Point>,
            widthPx: Int,
            heightPx: Int,
            topInsetPx: Int = 0,
            bottomInsetPx: Int = 0,
            sideInsetPx: Int = 0,
        ): TripMapCamera? {
            // 尺寸还没量出来（首帧、或者用户切横竖屏的中间态）就别给相机
            if (widthPx <= 0 || heightPx <= 0) return null
            val availW = (widthPx - 2 * sideInsetPx).coerceAtLeast(1)
            val availH = (heightPx - topInsetPx - bottomInsetPx).coerceAtLeast(1)
            val view = TripTileMap.view(points, availW, availH) ?: return null
            val worldPerZPx = 2.0.pow((REF_ZOOM - view.zoom).toDouble())
            val zoom = view.zoom.toFloat()
            val k = 2.0.pow((zoom - REF_ZOOM).toDouble())
            // [TripTileMap.view] 的原点是「可见带」左上角，带子的中心并不在画布中心。
            // 相机只认画布中心，所以把这半个差值折算成世界像素补回去。
            val bandCx = sideInsetPx + availW / 2.0
            val bandCy = topInsetPx + availH / 2.0
            return TripMapCamera(
                centerX = (view.originX + availW / 2.0) * worldPerZPx - (bandCx - widthPx / 2.0) / k,
                centerY = (view.originY + availH / 2.0) * worldPerZPx - (bandCy - heightPx / 2.0) / k,
                zoom = zoom,
            ).snapped()
        }
    }
}

/**
 * 一帧的贴图清单：[zoom] 是真正去取的瓦片层级，[perTilePx] 是一个瓦片像素占几个屏幕像素。
 *
 * [key] 只跟「层级 + 瓦片范围」有关，界面上拿它当协程 key：拖动没跨出这批瓦片就不重复加载。
 */
data class TileFrame(
    val zoom: Int,
    val perTilePx: Float,
    val leftZ: Double,
    val topZ: Double,
    val minCol: Int,
    val maxCol: Int,
    val minRow: Int,
    val maxRow: Int,
) {
    val cols: Int get() = maxCol - minCol + 1
    val rows: Int get() = maxRow - minRow + 1
    val tileCount: Int get() = cols * rows

    /** 一整块瓦片画到屏幕上的边长。 */
    val tileSize: Float get() = TripTileMap.TILE * perTilePx

    fun left(col: Int): Float = ((col * TripTileMap.TILE - leftZ) * perTilePx).toFloat()

    fun top(row: Int): Float = ((row * TripTileMap.TILE - topZ) * perTilePx).toFloat()

    val key: String get() = "$zoom/$minCol/$maxCol/$minRow/$maxRow"
}
