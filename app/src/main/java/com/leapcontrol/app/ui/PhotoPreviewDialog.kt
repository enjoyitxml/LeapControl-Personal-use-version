package com.leapcontrol.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.leapcontrol.app.OfficialParkingPhotoStore
import com.leapcontrol.app.ParkingPhotoSource
import com.leapcontrol.app.ParkingPhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一块矩形尺寸（px）。纯数据，给 [PhotoZoom] 的算式用，不掺 Compose 类型方便单测。 */
internal data class PhotoBox(val width: Float, val height: Float)

/** 一次手势的结算结果：目标缩放倍数 + 相对画面中心的位移（px）。 */
internal data class PhotoPan(val scale: Float, val x: Float, val y: Float)

/**
 * 全屏看照片的缩放/平移规则（纯逻辑，可单测）。
 *
 * ## 为什么单独抽出来
 * 这套算式（缩放锚点 + 边界收敛）错一点就是「放大后画面窜到屏幕外」或者
 * 「拖到一边露出黑边」，而这两件事在模拟器上不一定复现得出来。抽成纯函数后
 * 用边界值钉住，改动时能立刻发现。
 *
 * ## 坐标约定
 * 缩放与平移都相对**画面中心**：Compose 的 `graphicsLayer` 默认以中心为变换原点，
 * 于是「屏幕位置 = scale × 内容坐标 + 位移」，[transform] 的反解就按这个约定来。
 */
internal object PhotoZoom {
    /** 1 倍 = 图片正好铺满可用区域（Fit），再小就是留白，没有意义。 */
    const val MIN_SCALE = 1f

    /** 上限 6 倍：手机拍的 4000px 原图放大 6 倍已经能看到像素点了，再大没有信息量。 */
    const val MAX_SCALE = 6f

    /** 双击放到的倍数：够看清柱子号，又不至于一下失去方位感。 */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** 浮点比较余量：捏合回弹后 scale 常是 1.0000001，不能让「已放大」判成 true。 */
    private const val EPSILON = 0.01f

    fun clampScale(scale: Float): Float =
        if (scale.isNaN()) MIN_SCALE else scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun isZoomed(scale: Float): Boolean = scale > MIN_SCALE + EPSILON

    /**
     * 结算一次「缩放 + 拖动」。
     *
     * [centroidX] / [centroidY] 是双指中心（相对视图左上角），缩放以它为锚点 ——
     * 手指下方那块画面必须原地不动，否则放大的同时画面会往一个方向窜，
     * 用户就得一边捏一边追着图跑。
     *
     * 位移的边界按**内容与视图的实际尺寸**算：只有放大到内容超出视图时才允许拖动，
     * 而且最多拖到边缘对齐。这样横图竖放、竖图横放都不会露出黑边。
     */
    fun transform(
        scale: Float,
        x: Float,
        y: Float,
        zoom: Float,
        dragX: Float,
        dragY: Float,
        centroidX: Float,
        centroidY: Float,
        centerX: Float,
        centerY: Float,
        content: PhotoBox,
        view: PhotoBox,
    ): PhotoPan {
        val next = clampScale(scale * zoom)
        // 用**实际生效**的倍数算锚点：捏过头被夹到上限时，按夹后的值算才不会偏
        val k = if (scale > 0f) next / scale else 1f
        val anchorX = centroidX - centerX
        val anchorY = centroidY - centerY
        val nx = x * k + anchorX * (1f - k) + dragX
        val ny = y * k + anchorY * (1f - k) + dragY
        return clampToBounds(next, nx, ny, content, view)
    }

    /** 把位移收敛到「不露黑边」的范围内。 */
    fun clampToBounds(
        scale: Float,
        x: Float,
        y: Float,
        content: PhotoBox,
        view: PhotoBox,
    ): PhotoPan {
        val limitX = ((scale * content.width - view.width) / 2f).coerceAtLeast(0f)
        val limitY = ((scale * content.height - view.height) / 2f).coerceAtLeast(0f)
        return PhotoPan(scale, x.coerceIn(-limitX, limitX), y.coerceIn(-limitY, limitY))
    }
}

/** 大图的解码尺寸（长边）。缩略图只有 ~1000px，放大三倍就是马赛克，看不了柱子号。 */
private const val LARGE_MAX_SIZE = 2560

/**
 * 打开大图时再解一张更大的图；两种来源各读各的文件。
 *
 * 抽成函数是为了两个入口（找车页、驻车拍照页）不必各写一遍来源分支 ——
 * 那种重复到最后总会有一处忘了改，于是同一张图在一处能放大看清、另一处不能。
 */
internal fun loadParkingPhotoLarge(
    context: Context,
    source: ParkingPhotoSource,
    vin: String,
): Bitmap? = when (source) {
    ParkingPhotoSource.PHONE -> ParkingPhotoStore.load(context, LARGE_MAX_SIZE)
    ParkingPhotoSource.OFFICIAL -> OfficialParkingPhotoStore.load(context, vin, LARGE_MAX_SIZE)
}

/**
 * 全屏看那一张照片，可双指缩放 / 双击放大 / 单指拖动。
 *
 * ## 为什么必须能缩放
 * 地库灯暗、照片又是斜着拍的，缩略图里常认不出柱子上的字 —— 找车站在那里时，
 * 放大看是唯一能确认「是不是这格」的办法。只做 Fit 展示等于把这张图变成墙纸。
 *
 * ## 关闭的三种入口
 * - 右上角关闭键：一直可用；
 * - 单指轻点：**没放大时**收起整页；已放大时退回 1 倍（此时用户想退的是放大，不是这张图）；
 * - 返回键：与轻点同一套判断（见 `onDismissRequest`）。
 *
 * 轻点要等双击判定（约 300ms）才触发，这是保留双击放大的代价：两者共用同一串
 * 触摸事件，不先等一等就分不清「再看一眼」和「想放大」。
 *
 * @param bitmap 已解码的缩略图，大图没解出来之前先顶一阵。
 * @param loadLarge 可选：打开时再解一张更大的图。解不出来（文件被删 / 解码失败）就用 [bitmap]。
 */
@Composable
internal fun PhotoPreviewDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    loadLarge: (suspend () -> Bitmap?)? = null,
) {
    // 长边 ~2000px 的一张图约 12MB，只在这张全屏图存在时持有；关掉即随组合释放。
    // 用 remember + LaunchedEffect 而不是 produceState：后者的 lint 规则
    // （ProduceStateDoesNotAssignValue）对「先判空再赋值」这种写法会误报。
    val loader by rememberUpdatedState(loadLarge)
    var large by remember(bitmap) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bitmap) {
        val load = loader ?: return@LaunchedEffect
        large = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
    }
    val shown = large ?: bitmap

    var scale by remember { mutableFloatStateOf(PhotoZoom.MIN_SCALE) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // 用 derivedStateOf 而不是直接读 scale：每帧捏合都会改 scale，但「是否已放大」
    // 只在跨过阈值时变一次，没必要为它整页重组
    val zoomed by remember { derivedStateOf { PhotoZoom.isZoomed(scale) } }

    fun resetZoom() {
        scale = PhotoZoom.MIN_SCALE
        pan = Offset.Zero
    }

    Dialog(
        onDismissRequest = {
            // 放大状态下按返回先复位：用户想退的通常只是放大，不该连带把照片也关了
            if (zoomed) resetZoom() else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                // 放大后的图不该画到窗口外：否则贴着屏幕边缘拖动会看到图溢出到状态栏
                .clipToBounds(),
        ) {
            val density = LocalDensity.current
            val viewW = constraints.maxWidth.toFloat()
            val viewH = constraints.maxHeight.toFloat()
            // Fit 之后的实际占位：缩放边界与图形尺寸都按它算，不能用视图尺寸硬套
            val ratio = shown.width.toFloat() / shown.height.coerceAtLeast(1)
            val fit = if (viewH <= 0f || viewW / viewH > ratio) {
                PhotoBox(viewH.coerceAtLeast(1f) * ratio, viewH.coerceAtLeast(1f))
            } else {
                PhotoBox(viewW, viewW / ratio)
            }
            // pointerInput(Unit) 的 lambda 只在首次组合捕获参数，图换了一张也要用最新尺寸
            val fitNow = rememberUpdatedState(fit)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, drag, zoom, _ ->
                            val content = fitNow.value
                            val view = PhotoBox(size.width.toFloat(), size.height.toFloat())
                            val next = PhotoZoom.transform(
                                scale = scale,
                                x = pan.x,
                                y = pan.y,
                                zoom = zoom,
                                dragX = drag.x,
                                dragY = drag.y,
                                centroidX = centroid.x,
                                centroidY = centroid.y,
                                centerX = size.width / 2f,
                                centerY = size.height / 2f,
                                content = content,
                                view = view,
                            )
                            scale = next.scale
                            pan = Offset(next.x, next.y)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { point ->
                                val target = if (PhotoZoom.isZoomed(scale)) {
                                    PhotoZoom.MIN_SCALE
                                } else {
                                    PhotoZoom.DOUBLE_TAP_SCALE
                                }
                                val content = fitNow.value
                                val next = PhotoZoom.transform(
                                    scale = scale,
                                    x = pan.x,
                                    y = pan.y,
                                    zoom = target / scale,
                                    dragX = 0f,
                                    dragY = 0f,
                                    centroidX = point.x,
                                    centroidY = point.y,
                                    centerX = size.width / 2f,
                                    centerY = size.height / 2f,
                                    content = content,
                                    view = PhotoBox(size.width.toFloat(), size.height.toFloat()),
                                )
                                scale = next.scale
                                pan = Offset(next.x, next.y)
                            },
                            onTap = {
                                if (PhotoZoom.isZoomed(scale)) resetZoom() else onDismiss()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = remember(shown) { shown.asImageBitmap() },
                    contentDescription = "停车位照片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(with(density) { fit.width.toDp() })
                        .height(with(density) { fit.height.toDp() })
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = pan.x
                            translationY = pan.y
                        },
                )
            }

            // 首次进来给一句操作提示：不写的话「能双指缩放」只能靠试出来。
            // 离底边的高度走 dialogBottomGap：这一页自己踩过「提示下半截压在导航栏里」的坑，
            // 固定 dp 在不同导航方式下都不对（见 DialogInsets.kt）。
            if (!zoomed) {
                Text(
                    text = "双指缩放 · 双击放大",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = dialogBottomGap(extra = 16.dp)),
                )
            }

            // 关闭按钮不贴顶边：留出状态栏那一截，不然手指够不到还会被手势区吃掉
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(14.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
