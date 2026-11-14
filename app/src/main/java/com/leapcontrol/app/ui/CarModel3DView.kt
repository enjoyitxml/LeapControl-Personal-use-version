package com.leapcontrol.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.leapcontrol.app.BuildConfig
import com.leapcontrol.app.CarModel3DManager
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.math.hypot

/**
 * 首页那块车模的 3D 版本。
 *
 * ## 它解决的是什么
 *
 * 2D 车模是一张服务端**预先渲染**的定妆照：接口只收 VIN，车型配色、轮毂、车顶
 * 全在服务端那一次渲染里定死。所以用户看到「颜色和配置跟我车不一样」时，改不了。
 * 3D 是拿官方下发的 `modelParam`（车型 / 年款 / 车漆色号 / 车顶色 / 座椅）在设备上
 * 实时渲染，这才是「跟官方一致」的做法。
 *
 * ## 拖动旋转（2026-11-08 第二次返工）
 *
 * 第一版是「WebView `isEnabled = false` + 盖一层 Compose 手势层」，真机上依然
 * 完全拖不动。复盘下来这一步有**四处**都可能单独把拖动变成静默空操作，任何一处
 * 命中，表现都是「画面正常、手指划过去毫无反应」：
 *
 *  1. `isEnabled = false` 挡不住 WebView。它只让基类 `View.onTouchEvent` 提前返回，
 *     WebView 重写了那个方法，压根不看 enabled 标志，DOWN 照样被它吞掉 ——
 *     上层的 Compose 手势层一个事件都收不到。
 *  2. 触摸要经过 AndroidView 的 interop 才会轮到 Compose，命中顺序不受我们控制。
 *  3. `spin()` 有 `if (!sceneReady) return`：首帧回调晚一点或没回调，拖了也白拖。
 *  4. 就算 JS 收到了，`window.viewer` 这个名字官方并不保证；实例换个挂法，
 *     整段旋转脚本第一行就 return。
 *
 * 这一版的做法是**每一步都不赌**：
 *  - 触摸面从一个 Compose 节点换成 WebView 的**兄弟 View**（[CarModel3DStage] 里的
 *    [DragSurface]，后 addView = 先拿事件），命中顺序由 FrameLayout 保证，
 *    不再依赖 interop；WebView 本人再叠一层 `dispatchTouchEvent → false` 兜底；
 *  - 手势改成**全向**：横向绕 Y、纵向抬机位，上下左右都能转；
 *  - JS 侧不再依赖任何「实例叫什么名字」：直接**包住 `renderer.render`**，
 *    每帧真正画之前那一瞬把机位写进去 —— 这同时解决了「引擎下一帧把相机复位」
 *    这个最隐蔽的问题（画面能转、松手就弹回去）；
 *  - 仍然保留「转车身」的兜底，作为最后的降级。
 *
 * 代价：车模这块区域里纵向拖动不再是翻页滚动（要让位给上下转动）。页面其它位置
 * 照常滚，车模区域四周的空隙也照常滚。
 *
 * ## 失败怎么办
 *
 * 25 秒还没首帧、或 H5 主动报初始化失败 → 回调 [onError]，首页换回 2D 图。
 * 宁可退回一张静态图，也不能在首屏留一块黑框。
 */
@Composable
fun CarModel3D(
    meta: CarModel3DMeta,
    /** 官方参数里没给车型时的兜底（取车辆档案的 carType）。 */
    fallbackCarType: String,
    /** 深浅色：只影响官方引擎的点灯与展台亮度。 */
    isDark: Boolean,
    /**
     * 页面正在滚动。
     *
     * 3D 是持续重绘的，滚动时还按 60 帧跑会和列表抢 GPU —— 降到 15 帧，
     * 车模看着还是静止的（它本来就不动），滚完再拉回来。
     *
     * 收 `State` 而不是 `Boolean`：滚动每变一次都传值的话，重组的是整个首页
     * （3000 多行的 Column），为了一个帧率数字不值当。这里只在自身作用域里读。
     */
    paused: State<Boolean>? = null,
    /** 点一下车模（和 2D 时一样：换主题壁纸）。null = 这个模式下不需要点击。 */
    onTap: (() -> Unit)? = null,
    /**
     * 车速（km/h）。
     *
     * 2D 时代这里叠的是自己画的抖动 + 风线；3D 用官方引擎自带的
     * `handleVehicleMove` —— 它会转车轮、让地面标线往后退，比抖一张图自然得多。
     */
    speedKmh: Float = 0f,
    /**
     * 场景还没画出来之前不要吃触摸。
     *
     * 否则 WebView 建场景那一两秒里，2D 图还显示着、点它换壁纸却点不动 ——
     * 用户只会觉得「这块坏了」。
     */
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onError: () -> Unit = {},
) {
    // 名字叫 stage 而不是 webView：现在这一层是「WebView + 触摸面」的组合体，
    // 只留一个 WebView 引用的话，手势会又被塞回 Compose 树里（见类注释）
    var stage by remember(meta.h5Key) { mutableStateOf<CarModel3DStage?>(null) }
    val pausedNow = paused?.value ?: false

    // 每次 meta 变了就重新来一个 WebView：换车型 / 换了资产包时旧的场景已经无效
    DisposableEffect(meta.h5Key, meta.srcKey) {
        onDispose {
            runCatching { stage?.web?.destroy() }
            stage = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CarModel3DStage(
                    context = ctx,
                    modelDir = CarModel3DManager.modelDir(ctx, meta.h5Key),
                    modelParam = meta.modelParam,
                    fallbackCarType = fallbackCarType,
                    lastDark = isDark,
                    onReady = onReady,
                    onError = onError,
                ).also { stage = it }
            },
            update = { s ->
                s.setDark(isDark)
                s.web.setActive(!pausedNow)
                s.web.setSpeed(speedKmh)
                s.onTap = onTap
                s.setInteractive(interactive)
                // factory 里已经 set 过一次；这里再兜一次是因为 factory 只跑一次，
                // 而回调是 Composable 每次重组都可能换新的 lambda
                s.web.onReadyCallback = onReady
                s.web.onErrorCallback = onError
            },
        )

        // 兜底手势层：正常情况下事件的命中早被 [CarModel3DStage] 里的原生触摸面拿走了，
        // 这一段一次都不会被触发。留着是因为「3D 拖不动」已经连着翻车两次 ——
        // 万一某个机型 / 某个 Compose 版本上 interop 还是把事件截在别处，
        // 这里能兜住（[DragSurface.sawTouch] 保证两条路不会同时生效，不会转两倍）。
        if (interactive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (onTap != null) {
                            Modifier.pointerInput(onTap, meta.h5Key) {
                                detectTapGestures {
                                    if (stage?.nativeGestureActive != true) onTap()
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(meta.h5Key) {
                        var mine = false
                        detectDragGestures(
                            onDragStart = {
                                // 原生那层已经接过手的事件，这里绝不能再转一次
                                mine = stage?.nativeGestureActive != true
                            },
                            onDragEnd = { mine = false },
                            onDragCancel = { mine = false },
                            onDrag = { change, amount ->
                                if (mine) {
                                    change.consume()
                                    stage?.orbit(amount.x, amount.y)
                                }
                            },
                        )
                    },
            )
        }
    }
}

/** 给 Compose 侧用的元数据别名，避免 UI 层到处 import 内嵌类。 */
typealias CarModel3DMeta = CarModel3DManager.Meta

/**
 * 3D 车模那一整层：WebView 打底，透明触摸面盖在上面。
 *
 * ## 为什么把触摸面做进 View 树，而不是用 Compose 的 pointerInput
 *
 * 这块区域里有一个真实存在的 Android View（WebView）。在 Compose 里给它盖一层
 * 「兄弟节点 Box」时，事件的命中顺序是：先交给子 View 树（interop），子 View 都不吃
 * 才轮到 Compose 的 pointerInput —— 只要 WebView 把 DOWN 吞了，Compose 那层
 * 永远收不到事件，表现就是「画面正常、怎么拖都没反应」，而且不报错。
 *
 * 放进同一个 FrameLayout 之后，命中顺序变成「后 addView 的先拿事件」，
 * 由 ViewGroup 保证，跟 Compose 版本、interop 实现都没关系。
 *
 * 触摸面是 [DragSurface]（透明、不吃绘制），WebView 自己再叠一层
 * `dispatchTouchEvent → false`（[CarModelWebView] 里），双保险。
 */
internal class CarModel3DStage(
    context: Context,
    modelDir: File,
    modelParam: JSONObject?,
    fallbackCarType: String,
    lastDark: Boolean,
    onReady: () -> Unit,
    onError: () -> Unit,
) : FrameLayout(context) {

    val web = CarModelWebView(
        context = context,
        modelDir = modelDir,
        modelParam = modelParam,
        fallbackCarType = fallbackCarType,
        lastDark = lastDark,
        onReady = onReady,
        onError = onError,
    )

    private val drag = DragSurface(context)

    /** 原生触摸面是否真的收到过触摸；Compose 的兜底手势靠它决定要不要接手。 */
    val nativeGestureActive: Boolean get() = drag.sawTouch

    /** 点一下换壁纸（和 2D 车模同一个行为）。 */
    var onTap: (() -> Unit)? = null
        set(value) {
            field = value
            drag.onTap = value
        }

    init {
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 后加的在上层，也先拿到触摸事件
        addView(drag, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        drag.onDrag = { dx, dy -> web.orbit(dx, dy) }
        drag.onTap = onTap
    }

    fun setInteractive(on: Boolean) {
        drag.acceptTouch = on
    }

    fun setDark(dark: Boolean) {
        web.setDark(dark)
    }

    /** 拖动 → 转车模。Compose 兜底那条路也用这个入口，两条路只有一个会生效。 */
    fun orbit(dx: Float, dy: Float) {
        web.orbit(dx, dy)
    }
}

/**
 * 盖在 WebView 上的透明触摸面。
 *
 * 只做三件事：判「是点还是拖」、把拖动增量交给 [CarModelWebView.orbit]、
 * 记下自己到底有没有收到触摸（给 Compose 兜底那层做互斥）。
 *
 * 用 Android 原生 `onTouchEvent` 而不是 Compose 手势：这一层是真实的 View，
 * 命中顺序由父 FrameLayout 决定，不经过 interop，不受 Compose 版本影响。
 */
private class DragSurface(context: Context) : View(context) {

    var onDrag: ((Float, Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    /** 场景还没画好之前不吃触摸：那会儿用户看到的是 2D 图，点它是换壁纸。 */
    var acceptTouch: Boolean = true

    /** 是否真的收到过触摸。第一次 DOWN 就会置位，之后不再复位。 */
    @Volatile
    var sawTouch: Boolean = false
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var dragging = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!acceptTouch) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sawTouch = true
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downAt = SystemClock.uptimeMillis()
                dragging = false
                // 必须吃掉 DOWN，否则后面收不到 MOVE
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                // 超过 touchSlop 才算拖动：手指按下去的抖动不该让车模晃
                if (!dragging && hypot(event.x - downX, event.y - downY) > slop) {
                    dragging = true
                }
                if (dragging) onDrag?.invoke(dx, dy)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging && SystemClock.uptimeMillis() - downAt < TAP_TIMEOUT_MS) {
                    performClick()
                    onTap?.invoke()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val TAP_TIMEOUT_MS = 450L
    }
}

/**
 * 加载官方 3D 包并驱动它。
 *
 * 所有本地资源走 `https://appassets.androidplatform.net/...` 这个伪域名，
 * 由 [shouldInterceptRequest] 映射到磁盘文件 —— 这样 H5 里那些相对路径、
 * `fetch()`、`XMLHttpRequest` 全都能用，而且不会给 WebView 开 `file://` 权限。
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class CarModelWebView(
    context: Context,
    private val modelDir: File,
    private val modelParam: JSONObject?,
    private val fallbackCarType: String,
    private var lastDark: Boolean,
    onReady: () -> Unit,
    onError: () -> Unit,
) : WebView(context) {

    var onReadyCallback: () -> Unit = onReady
    var onErrorCallback: () -> Unit = onError

    private var sceneReady = false
    private var failed = false
    private var lastActive: Boolean? = null
    private var lastSpeed: Float? = null
    private var spinDiagChecked = false

    init {
        if (BuildConfig.DEBUG) setWebContentsDebuggingEnabled(true)
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        // 不吃触摸：旋转由上层触摸面注入（见 [CarModel3DStage]）。
        //
        // 注意 `isEnabled = false` **挡不住 WebView**：它只让基类 `View.onTouchEvent`
        // 在 DISABLED 时提前返回，而 WebView 重写了那个方法、根本不看这个标志，
        // DOWN 照样被它吞掉。真正的开关是下面的 [dispatchTouchEvent] / [onTouchEvent]。
        // 这一行只是顺手关掉焦点与无障碍，别把它当成"已经禁用了"。
        isEnabled = false
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = serveLocal(request?.url)

            // 页面每次加载完都装一遍（脚本里有 __leapApiInstalled 兜底，重复调用无副作用）：
            // H5 内部若自行 reload，之前的全局函数会跟着文档一起丢掉
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                installApi(view)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "3D JS: ${message?.message()} (${message?.lineNumber()})")
                }
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                result?.confirm()
                when (message) {
                    "onFirstFrame", "onFullCarLoaded" -> markReady(view)
                    "onInitThrow", "onInitTimeout" -> fail("初始化失败: $message")
                }
                return true
            }
        }

        loadUrl("https://$ASSETS_HOST/index.html")

        // 兜底：H5 里任何一步卡住（WebGL 创建失败、资产 404）都不会回调。
        // 25 秒还没首帧就当失败 —— 低端机解 12MB 资源确实要十几秒，判太紧会把
        // 「慢」误判成「坏」，这块就永远回不到 3D 了。
        postDelayed({
            if (!sceneReady) fail("25 秒内没有首帧")
        }, READY_TIMEOUT_MS)
    }

    // ------------------------------------------------------------ 触摸一票否决

    /**
     * 这一层永远不吃触摸。
     *
     * 上层的 [DragSurface] 本来就会先把 DOWN 拿走，这里是**第二道闸**：
     * 万一同一个 FrameLayout 里的顺序被谁改了、或者宿主换成了别的容器，
     * 只要 WebView 一吃事件，上面的手势层就整段收不到 —— 不报错、不崩、就是拖不动，
     * 是这类问题里最难查的一种。所以在 View 自己身上关死，比在外面排队靠得住。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean = false

    // ------------------------------------------------------------ 对外操作

    /** 拖动 → 转车模。[dxPx] / [dyPx] 是像素位移（右 / 下为正）。 */
    fun orbit(dxPx: Float, dyPx: Float) {
        if (dxPx == 0f && dyPx == 0f) return
        // 这里**故意不再等 sceneReady**：JS API 在 onPageFinished 就已经装好，
        // 而首帧回调晚一两秒很常见（低端机解 12MB 资源）。旧版在入口就
        // `if (!sceneReady) return`，恰好把「刚进首页就想拖」那一下吃掉。
        val dx = String.format(Locale.US, "%.5f", dxPx * YAW_PER_PX)
        val dy = String.format(Locale.US, "%.5f", dyPx * PITCH_PER_PX)
        evaluateJavascript("window.__leapOrbit&&window.__leapOrbit($dx,$dy)", null)
        // 第一次拖动后回报一次「到底转没转」：真机上拖不动时，logcat 里一行 diag
        // 就能区分「事件没到」（不会有这行）、「JS 没装上 / 找不到实例」（会这行但 diag=no-viewer）、
        // 「转了但被引擎复位」（diag=orbit 却看不见）。
        if (BuildConfig.DEBUG && !spinDiagChecked) {
            spinDiagChecked = true
            postDelayed({
                evaluateJavascript(
                    "(function(){var s=window.__leapOrbitState||{};return " +
                        "(window.__leapDiag||'ok')+'|count='+(window.__leapSpinCount||0)+" +
                        "'|renderer='+(s.renderer?'Y':'N')+'|cam='+(s.cam?'Y':'N')+" +
                        "'|theta='+(s.theta||0).toFixed(3)+'|phi='+(s.phi||0).toFixed(3);})()",
                ) { result -> Log.d(TAG, "orbit diag: $result") }
            }, 800)
        }
    }

    fun setDark(dark: Boolean) {
        if (dark == lastDark) return
        lastDark = dark
        if (!sceneReady) return
        evaluateJavascript("window.__leapDark && window.__leapDark($dark)", null)
    }

    /** 滚动时降帧、停下来恢复。首次调用是 null → 会真的下发一次，正好把初始帧率定下来。 */
    fun setActive(active: Boolean) {
        if (lastActive == active) return
        lastActive = active
        if (!sceneReady) return
        evaluateJavascript("window.__leapFps && window.__leapFps(${if (active) 60 else 15})", null)
    }

    /** 车速：官方引擎转车轮 + 地面标线后退。停车时传 0，什么都不动。 */
    fun setSpeed(kmh: Float) {
        if (kmh == lastSpeed) return
        lastSpeed = kmh
        if (!sceneReady) return
        val value = String.format(Locale.US, "%.1f", kmh)
        evaluateJavascript("window.__leapSpeed && window.__leapSpeed($value)", null)
    }

    // ------------------------------------------------------------ 内部

    private fun markReady(view: WebView?) {
        if (sceneReady) return
        sceneReady = true
        // 先把 JS API 装上：tuneViewer 与 spin 都依赖它，而且它必须在任何
        // 「实例还解析不出来」的时刻也已经在位（见 installApi 注释）
        installApi(view)
        onReadyCallback()
        view?.postDelayed({ tuneViewer(view) }, 120)
        view?.postDelayed({
            tuneViewer(view)
            applyParams(view)
        }, 420)
    }

    private fun fail(reason: String) {
        if (sceneReady || failed) return
        failed = true
        Log.w(TAG, "3D 车模不可用：$reason")
        onErrorCallback()
    }

    /**
     * 把伪域名映射到磁盘。
     *
     * 路径先做 canonical 校验再读：`..%2f` 这类 URL 编码穿越会被 normalize 掉，
     * 但多一层判断不亏 —— 这个目录里的内容全部来自服务端 zip。
     */
    private fun serveLocal(url: Uri?): WebResourceResponse? {
        if (url == null || url.host != ASSETS_HOST) return null
        val rawPath = url.path?.removePrefix("/") ?: return notFound()
        val decoded = runCatching { Uri.decode(rawPath) }.getOrDefault(rawPath)
        val root = modelDir.canonicalFile
        val file = File(root, decoded).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return notFound()

        val mime = when {
            decoded.endsWith(".html", true) -> "text/html"
            decoded.endsWith(".js", true) -> "application/javascript"
            decoded.endsWith(".json", true) -> "application/json"
            decoded.endsWith(".css", true) -> "text/css"
            decoded.endsWith(".png", true) -> "image/png"
            decoded.endsWith(".jpg", true) || decoded.endsWith(".jpeg", true) -> "image/jpeg"
            decoded.endsWith(".webp", true) -> "image/webp"
            decoded.endsWith(".gltf", true) -> "model/gltf+json"
            decoded.endsWith(".glb", true) -> "model/gltf-binary"
            decoded.endsWith(".fbx", true) -> "application/octet-stream"
            decoded.endsWith(".bin", true) -> "application/octet-stream"
            else -> "application/octet-stream"
        }
        return runCatching {
            // 二进制资源（贴图 / 模型 / 字体）不能带 charset：部分 WebView 会按文本解码，
            // 出来是花屏或者直接加载失败
            val isText = mime.startsWith("text/") ||
                mime == "application/javascript" ||
                mime == "application/json"
            WebResourceResponse(mime, if (isText) "UTF-8" else null, FileInputStream(file))
        }.getOrElse { notFound() }
    }

    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found",
        null, ByteArrayInputStream(ByteArray(0)),
    )

    /**
     * 官方 H5 的启动协议：等 `window.newInit` 出现，然后把渲染参数和视口尺寸递进去。
     *
     * `window.mobile` 那组回调是官方页面用来通知宿主机的（首帧、全车加载完、截图），
     * 我们只接首帧与错误 —— 截图给小组件那套这次不做。
     */
    private fun applyParams(view: WebView?) {
        val param = buildParamJson(modelParam, fallbackCarType, lastDark)
            .toString()
            .replace("'", "\\'")
        val script = """
        (function() {
            try {
                window.IOSWebview = false;
                window.AndroidWebview = true;
                window.mobile = window.mobile || {};
                window.mobile.onFirstFrame = function() { window.prompt('onFirstFrame'); };
                window.mobile.onFullCarLoaded = function() { window.prompt('onFullCarLoaded'); };
                window.mobile.printLog = function(l) { console.log(l); };
                var tries = 0;
                function start() {
                    if (typeof window.newInit === 'function') {
                        if (window.innerWidth > 0) {
                            var app = JSON.stringify({
                                width: window.innerWidth,
                                height: window.innerHeight,
                                energy: 0,
                                inland: 0
                            });
                            try {
                                window.newInit('$param', app);
                            } catch (e) {
                                window.prompt('onInitThrow', String(e && (e.message || e)));
                            }
                            return;
                        }
                        setTimeout(start, 100);
                        return;
                    }
                    if (++tries > 300) { window.prompt('onInitTimeout'); return; }
                    setTimeout(start, 50);
                }
                start();
            } catch (e) {
                window.prompt('onInitThrow', String(e));
            }
        })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    /**
     * 装上给 Kotlin 用的 JS API：轨道旋转 / 明暗 / 帧率 / 车速。
     *
     * ## 这一版为什么长这样
     *
     * 「拖不动」连着返工两次，每次根因都不在同一个地方，所以这里每一步都不赌：
     *
     *  1. **函数无条件装上**。上一版把它们写在 [tuneViewer] 里，而 tuneViewer 第一行是
     *     `var v = window.viewer; if (!v) return;` —— 实例换个挂法整段就没了，
     *     Kotlin 侧 `window.__leapSpin && ...` 于是静默空操作。
     *  2. **不靠名字靠特征**。除了常见全局名，还会扫一遍 window（含下一层），
     *     按 `isCamera` / `isScene` / `render` + `domElement` 这些 three.js 的对象标记认人。
     *  3. **必须包住 `renderer.render`**。引擎（以及它自带的 OrbitControls / 视角补间）
     *     每帧都会自己算一遍机位，只在手指挪动那一刻改相机，下一帧就被盖回去 ——
     *     现象是「拖的时候动一下、松手弹回原位」。包住 render 之后，我们是在
     *     **真正画之前那一瞬**写机位，谁也算不过我们。
     *  4. **摸不到相机就转车身**，至少看得见在动。
     */
    private fun installApi(view: WebView?) {
        val script = """
        (function() {
            if (window.__leapApiInstalled) return;
            window.__leapApiInstalled = true;

            function isObj(o) { return !!o && typeof o === 'object'; }
            function isNum(x) { return typeof x === 'number' && isFinite(x); }
            function isViewer(o) {
                return isObj(o) && !!o.scene && !!o.camera && isObj(o.camera.position);
            }
            function looksLikeRenderer(o) {
                return isObj(o) && typeof o.render === 'function' &&
                    isObj(o.domElement) && o.domElement.nodeType === 1;
            }

            // 轨道状态：theta 绕 Y、phi 抬机位；base 是「第一下拖动时的机位」，
            // 之后每帧都从 base 重新算 —— 误差不累积，也不会出现「引擎写一半、我们转一半」
            var st = window.__leapOrbitState || {
                theta: 0, phi: 0, cam: null, base: null, renderer: null, scene: null, lastProbe: 0
            };
            window.__leapOrbitState = st;

            // 扫一层对象的属性，命中就记进 st。深度限 2，且不碰 DOM 节点。
            // 有预算上限：窗口里那几个巨型对象（document / performance）扫起来很贵
            var budget = 0;
            function scan(root, depth, hit) {
                var keys;
                try { keys = Object.keys(root); } catch (e) { return false; }
                for (var i = 0; i < keys.length; i++) {
                    if (budget-- <= 0) return false;
                    var k = keys[i];
                    if (k.lastIndexOf('__leap', 0) === 0) continue;
                    var val;
                    try { val = root[k]; } catch (e) { continue; }
                    if (!isObj(val) || val === window || val.nodeType) continue;
                    try { if (hit(val)) return true; } catch (e) {}
                    if (depth > 1 && scan(val, depth - 1, hit)) return true;
                }
                return false;
            }

            // 认人：官方包把实例挂在哪、叫什么，全凭它自己。所以不靠名字靠特征 ——
            // three.js 的对象都带 isCamera / isScene / render + domElement 这些标记
            function probe() {
                var now = Date.now ? Date.now() : 0;
                // 扫全局有成本，限速 700ms；实例晚一点挂上来也不会被永久判成「没有」
                if (st.lastProbe && now - st.lastProbe < 700) return;
                st.lastProbe = now;
                budget = 4000;

                var names = ['viewer', 'view', 'app', 'app3d', 'car', 'carModel',
                             'engine', 'webgl', 'scene3d', 'stage', 'main', 'renderer'];
                for (var i = 0; i < names.length; i++) {
                    var o = window[names[i]];
                    if (!isObj(o)) continue;
                    if (isViewer(o)) window.__leapViewerRef = o;
                    if (looksLikeRenderer(o)) st.renderer = o;
                    if (isObj(o.renderer) && typeof o.renderer.render === 'function') {
                        st.renderer = o.renderer;
                    }
                    if (o.isCamera === true) st.cam = o;
                }

                if (!st.renderer || !st.cam) {
                    scan(window, 2, function(val) {
                        if (!st.renderer && looksLikeRenderer(val)) st.renderer = val;
                        if (!st.cam && val.isCamera === true) st.cam = val;
                        if (isViewer(val) && !window.__leapViewerRef) window.__leapViewerRef = val;
                        return !!st.renderer && !!st.cam;
                    });
                }
            }

            // 官方包把实例挂在哪是它自己的事，这里按可能性从近到远找，找到就缓存
            window.__leapViewer = function() {
                var cached = window.__leapViewerRef;
                if (isViewer(cached)) return cached;
                if (isViewer(window.viewer)) {
                    window.__leapViewerRef = window.viewer;
                    return window.viewer;
                }
                probe();
                if (isViewer(window.__leapViewerRef)) return window.__leapViewerRef;
                return null;
            };

            // 车身（THREE.Object3D）。官方把控制器的实例叫 car，但不保证它就是 Object3D
            function carObject(v) {
                if (!v) return null;
                var cs = [v.car, window.car];
                for (var i = 0; i < cs.length; i++) {
                    var c = cs[i];
                    if (!isObj(c) || c === v) continue;
                    if (c.isObject3D) return c;
                    if (isObj(c.model) && c.model.isObject3D) return c.model;
                    if (isObj(c.group) && c.group.isObject3D) return c.group;
                }
                return null;
            }

            function controlsOf(v) {
                if (!v) return null;
                var c = v.car || window.car;
                return v.controls || v.control || v.orbitControls ||
                    (isObj(c) && (c.controls || c.orbitControls)) || null;
            }

            function usable(cam) {
                return isObj(cam) && isObj(cam.position) && typeof cam.position.set === 'function';
            }

            function camNow() {
                var v = window.__leapViewer();
                if (v && usable(v.camera)) return v.camera;
                var ctrl = controlsOf(v);
                if (ctrl && usable(ctrl.object)) return ctrl.object;
                if (usable(st.cam)) return st.cam;
                probe();
                if (usable(st.cam)) return st.cam;
                return null;
            }

            // 环绕锚点：轨道控制器的 target → 车身位置抬 0.9 → 原点抬 0.9
            // 最后一档是官方机位本身围绕的点（见 tuneViewer 里写的 target），差不到半米
            function targetNow() {
                var v = window.__leapViewer();
                var ctrl = controlsOf(v);
                if (ctrl && isObj(ctrl.target) && isNum(ctrl.target.x)) {
                    return { x: ctrl.target.x, y: ctrl.target.y, z: ctrl.target.z };
                }
                var c = carObject(v);
                if (c && isObj(c.position) && isNum(c.position.x)) {
                    return { x: c.position.x, y: c.position.y + 0.9, z: c.position.z };
                }
                return { x: 0, y: 0.9, z: 0 };
            }

            function captureBase(cam) {
                var t = targetNow();
                var ox = cam.position.x - t.x;
                var oy = cam.position.y - t.y;
                var oz = cam.position.z - t.z;
                var r = Math.sqrt(ox * ox + oy * oy + oz * oz);
                if (!(r > 0.001)) return false;
                st.base = { ox: ox, oy: oy, oz: oz, r: r, tx: t.x, ty: t.y, tz: t.z, cam: cam };
                return true;
            }

            // theta / phi 落到相机：绕 Y 转 theta，按 phi 抬高机位，仰角夹在合理区间
            function applyOrbit() {
                var cam = camNow();
                if (!cam) return false;
                if (!st.base || st.base.cam !== cam) { if (!captureBase(cam)) return false; }
                var b = st.base;
                var cs = Math.cos(st.theta), sn = Math.sin(st.theta);
                var x = b.ox * cs + b.oz * sn;
                var z = -b.ox * sn + b.oz * cs;
                var horiz = Math.sqrt(x * x + z * z);
                var elev = Math.atan2(b.oy, horiz) + st.phi;
                if (elev > 1.30) elev = 1.30;    // 剩 15 度到正上方就够了，再多看不出东西
                if (elev < -0.05) elev = -0.05;  // 也不许钻到车底下去
                var nh = Math.cos(elev) * b.r;
                var ny = Math.sin(elev) * b.r;
                var k = horiz > 0.000001 ? nh / horiz : 0;
                cam.position.set(b.tx + x * k, b.ty + ny, b.tz + z * k);
                if (typeof cam.lookAt === 'function') cam.lookAt(b.tx, b.ty, b.tz);
                return true;
            }

            // 包住 render：每帧真正画之前那一瞬把机位写进去。
            // 这是「拖的时候动一下、松手弹回去」的根治手段 —— 引擎与它自带的
            // OrbitControls 都在我们前面更新，但没人在 render 之后还能改写画面
            function hookRender() {
                if (!looksLikeRenderer(st.renderer)) {
                    probe();
                }
                var r = st.renderer;
                if (!looksLikeRenderer(r)) return false;
                if (r.__leapOrbitHooked) return true;
                var orig = r.render;
                r.render = function(scene, camera) {
                    try {
                        if (scene) st.scene = scene;
                        if (camera && isObj(camera.position)) st.cam = camera;
                        if (st.theta || st.phi) applyOrbit();
                    } catch (e) {
                        window.__leapDiag = 'hook-err';
                    }
                    return orig.apply(this, arguments);
                };
                r.__leapOrbitHooked = true;
                return true;
            }

            // 拿不到 renderer 时的兜底：每帧自己写一次相机。
            // 被引擎盖掉也没关系，下一帧又写回去，肉眼仍然是跟手的
            function pump() {
                try {
                    if (st.theta || st.phi) { if (!hookRender()) applyOrbit(); }
                } catch (e) {}
                requestAnimationFrame(pump);
            }

            window.__leapOrbit = function(dx, dy) {
                var ax = Number(dx) || 0;
                var ay = Number(dy) || 0;
                if (!ax && !ay) return;
                // 手感对齐 three 的 OrbitControls：向右拖 = 相机逆着走（车跟着手指转），
                // 向下拖 = 抬高机位看车顶
                st.theta -= ax;
                st.phi += ay;
                if (st.phi > 1.05) st.phi = 1.05;
                if (st.phi < -1.05) st.phi = -1.05;
                window.__leapSpinCount = (window.__leapSpinCount || 0) + 1;

                if (hookRender()) { window.__leapDiag = 'orbit'; return; }
                if (applyOrbit()) { window.__leapDiag = 'cam'; return; }
                // 相机都摸不到：直接转车身（每帧被复位也不要紧，pump 会补）
                var v = window.__leapViewer();
                var body = carObject(v) || (v && v.scene) || st.scene;
                if (body && isObj(body.rotation)) {
                    body.rotation.y -= ax;
                    window.__leapDiag = 'body';
                } else {
                    window.__leapDiag = 'no-viewer';
                }
            };

            // 老名字留一个别名：别处万一还在调 __leapSpin，不至于又变成空操作
            window.__leapSpin = function(dRad) { window.__leapOrbit(dRad, 0); };

            // 画面调优动过相机之后基准要重取，否则车会跳回调优前的机位
            window.__leapResetBase = function() { st.base = null; st.cam = null; };

            window.__leapDark = function(dark) {
                var v = window.__leapViewer(); if (!v) return;
                var sky = v.frameState && v.frameState.skyBox;
                if (sky && typeof sky.setDayOrNight === 'function') sky.setDayOrNight(!!dark);
                var c = v.car || window.car;
                if (isObj(c) && typeof c.handleLowBeamLight === 'function') c.handleLowBeamLight(!!dark, true);
            };

            // 首页滚动时降到 15 帧：车模本身静止，帧率只影响拖动的跟手度，
            // 滚动那一两秒里它和列表抢 GPU 才是真的卡。
            window.__leapFps = function(fps) {
                var v = window.__leapViewer(); if (!v) return;
                if (typeof v.setFPS === 'function') v.setFPS(fps);
                else if (v.fpSetter && typeof v.fpSetter.setFPS === 'function') v.fpSetter.setFPS(fps);
            };

            // 行驶动效交给官方引擎：转车轮 + 地面标线后退。
            // 2D 时代这里是自己画的抖动和风线，3D 车模叠那个会露出背景边。
            window.__leapSpeed = function(kmh) {
                var v = window.__leapViewer(); if (!v) return;
                var c = v.car || window.car;
                if (!isObj(c) || typeof c.handleVehicleMove !== 'function') return;
                var n = Number(kmh) || 0;
                c.handleVehicleMove(n);
                if (c.__laneLine__) c.__laneLine__.visible = n > 0;
            };

            // 先把探测做一次，能摸到 renderer 的话尽早把 render 包上；
            // pump 之后常驻（空闲时每帧只做一次布尔判断，代价可以忽略）
            probe();
            hookRender();
            pump();
        })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    /**
     * 首帧之后的画面调优。
     *
     * 三件事，缺一不可：
     *  1. **透明**：关掉官方天空盒与 `scene.background`，否则车模带着一整套天空贴图，
     *     盖在我们的壁纸主题上像贴错图；
     *  2. **机位**：前侧 45° 俯视 + 放大到 1.12，车模体量才撑得住首页那 200dp；
     *  3. **锁视角动画**：官方加载完会自己补间一次正侧面，和上面定好的机位打架。
     *
     * 这里**只做画面调优**，不再定义 [installApi] 那几个函数 —— 那些必须无条件装上。
     */
    private fun tuneViewer(view: WebView?) {
        val speedLiteral = String.format(Locale.US, "%.1f", lastSpeed ?: 0f)
        val script = """
        (function() {
            var findV = window.__leapViewer || function() { return window.viewer || null; };
            var v = findV();
            if (!v) return;
            var c = window.car || (v && v.car);

            if (v.frameState && v.frameState.skyBox) v.frameState.skyBox.visible = false;
            if (v.scene) v.scene.background = null;
            if (v.renderer) {
                v.renderer.setClearColor(0x000000, 0);
                v.renderer.setClearAlpha(0);
                var dpr = Math.min(Math.max(window.devicePixelRatio || 2.5, 2.5), 3.5);
                v.renderer.setPixelRatio(dpr);
                if (v.renderer.shadowMap) v.renderer.shadowMap.enabled = true;
            }
            if (document.body) document.body.style.background = 'transparent';
            if (document.documentElement) document.documentElement.style.background = 'transparent';

            var cam = (v.controls && v.controls.object) || v.camera;
            var ctrl = v.controls || v.control || v.orbitControls;
            if (c) c.__lockViewAnim__ = true;

            if (cam && ctrl && ctrl.target) {
                if (c && c.__viewAnimSet__ && typeof c.__viewAnimSet__.clear === 'function') {
                    c.__viewAnimSet__.clear();
                }
                ctrl.target.set(-0.35, 0.98, -0.35);
                cam.position.set(-11.15, 3.13, 10.45);
                cam.lookAt(ctrl.target);
                if (ctrl.target0) ctrl.target0.copy(ctrl.target);
                if (ctrl.position0) ctrl.position0.set(-11.15, 3.13, 10.45);
                if (typeof ctrl.saveState === 'function') ctrl.saveState();
                if (typeof ctrl.update === 'function') ctrl.update();
                if (c) { c.__oTheta__ = -0.785398; c.__oPhi__ = 1.32; }

                var zoom = 1.12;
                cam.zoom = zoom;
                if (!cam.__leapZoomHooked) {
                    cam.__leapZoomHooked = true;
                    var orig = cam.updateProjectionMatrix;
                    cam.updateProjectionMatrix = function() {
                        this.zoom = zoom;
                        orig.call(this);
                    };
                }
                cam.updateProjectionMatrix();
            }

            window.__leapSpeed($speedLiteral);
            window.__leapDark($lastDark);
            // 机位被这里改过：轨道旋转的基准要按新机位重取，否则第一次拖动
            // 会把车拽回调优之前的位置（用户看到的是「一拖就跳」）
            if (window.__leapResetBase) window.__leapResetBase();
        })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private companion object {
        const val TAG = "CarModel3D"
        const val ASSETS_HOST = "appassets.androidplatform.net"
        const val READY_TIMEOUT_MS = 25_000L

        /**
         * 横向 0.006 rad/px：横穿一屏（约 400px）转 137°，转半圈不用拖两次。
         * 纵向 0.0045 rad/px：上下拉 150px 抬约 39°，一屏之内能把车看到顶。
         */
        const val YAW_PER_PX = 0.006f
        const val PITCH_PER_PX = 0.0045f
    }
}

/**
 * 拼官方 `newInit()` 的渲染参数。
 *
 * 两个坑，都是踩出来的：
 *  - **年款必须 ≥ 2026**：官方引擎的动画参数表以 2026/2027 为基线，
 *    传 2025 会让 `this.params[year].door` 变成 undefined，整车动画初始化直接中断；
 *  - **不要自己映射字段**：`modelParam` 里服务端给什么就透传什么（车顶色、座椅、
 *    别名），少传一个就等于把那项配置打回默认值 —— 那正是「配置和官方不一样」的来源。
 */
internal fun buildParamJson(
    param: JSONObject?,
    fallbackCarType: String,
    isDark: Boolean,
): JSONObject {
    val obj = JSONObject()
    val carType = param?.optString("carType").orEmpty()
        .ifBlank { fallbackCarType }
        .ifBlank { "C16" }
    val rawYear = param?.optInt("year") ?: 2026
    val year = if (rawYear < 2026) 2026 else rawYear

    obj.put("carType", carType)
    obj.put("year", year)
    obj.put("carTypeCode", param?.optString("carTypeCode").orEmpty())
    obj.put("colorCode", param?.optInt("colorCode") ?: 0)
    obj.put("rudder", param?.optInt("rudder") ?: 0)
    // 固定机位：不让官方引擎自己算环绕半径与偏移
    obj.put("radius", 7.2)
    obj.put("offset", 0.0)
    obj.put("theme", "default")
    obj.put("isDark", isDark)

    param ?: return obj
    if (param.has("roofColorCode") && !param.isNull("roofColorCode")) {
        obj.put("roofColorCode", param.optInt("roofColorCode"))
    } else if (param.has("roofColor") && !param.isNull("roofColor")) {
        obj.put("roofColorCode", param.optInt("roofColor"))
    }
    if (param.has("seat") && !param.isNull("seat")) obj.put("seat", param.optInt("seat"))
    if (param.has("alias") && !param.isNull("alias")) obj.put("alias", param.optString("alias"))
    return obj
}
