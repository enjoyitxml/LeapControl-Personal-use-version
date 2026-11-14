package com.leapcontrol.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3D 车模「拖不动」的守门测试（2026-11-08 第二次返工）。
 *
 * 用户反馈了两轮：第一轮「开了 3D 没法拖动」，改完仍然拖不动，并且明确要求
 * 「上下左右都能拖」。所以这一版不再赌任何一环，测试也按「每一环都可能单独失效」
 * 来写 —— 任何一条断言被改回去，都会重新变回「画面正常、手指划过去毫无反应」。
 *
 * 覆盖的五个失效点：
 *  1. `isEnabled = false` 挡不住 WebView（它重写了 onTouchEvent，不看这个标志）；
 *  2. 触摸要穿过 AndroidView 的 interop 才轮到 Compose，顺序不由我们定；
 *  3. `spin()` 被 `!sceneReady` 拦住，首帧回调晚一点就等于第一下白拖；
 *  4. 只做了横向，纵向被让给页面滚动 —— 用户要的是上下左右；
 *  5. 就算 JS 收到，实例换个挂法整段就 return；就算转了，引擎下一帧把相机复位。
 */
class CarModel3DDragTest {

    private val viewFile: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }
        .let { File(it, "app/src/main/java/com/leapcontrol/app/ui/CarModel3DView.kt") }

    private val source: String = viewFile.readText()

    private fun bodyOf(signature: String, until: String = "\n    private fun "): String {
        val start = source.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        val rest = source.substring(start + signature.length)
        val end = rest.indexOf(until).let { if (it < 0) rest.length else it }
        return rest.substring(0, end)
    }

    /** 去掉注释行后的代码文本（注释里会引用错误写法做反例，不能参与断言）。 */
    private fun codeOnly(text: String): String = text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }
        .joinToString("\n")

    // ---------------------------------------------------------------- 触摸能不能到手

    @Test
    fun `WebView 自己一票否决所有触摸`() {
        // isEnabled=false 只让基类 View.onTouchEvent 提前返回，WebView 重写了它，
        // 照样把 DOWN 吞掉 —— 这是第一版的死因，必须在 View 身上关死
        assertTrue(
            "WebView 没有关掉 dispatchTouchEvent：它会抢在触摸面之前把 DOWN 吃掉",
            source.contains("override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false"),
        )
        assertTrue(
            "WebView 没有关掉 onTouchEvent",
            source.contains("override fun onTouchEvent(ev: MotionEvent): Boolean = false"),
        )
        assertTrue(
            "WebView 又变回可点击状态，事件会被它拦下",
            source.contains("isClickable = false") && source.contains("isLongClickable = false"),
        )
    }

    @Test
    fun `触摸面是 WebView 的兄弟 View 且后添加`() {
        val stage = bodyOf("internal class CarModel3DStage(")
        val web = stage.indexOf("addView(web,")
        val drag = stage.indexOf("addView(drag,")
        assertTrue("CarModel3DStage 没有把 WebView 加进来", web >= 0)
        assertTrue("CarModel3DStage 没有把透明触摸面加进来", drag >= 0)
        assertTrue(
            "触摸面必须在 WebView 之后 addView（后加的在上层、先拿到事件），" +
                "顺序反了就等于把它压在 WebView 底下",
            drag > web,
        )
        // 用原生 onTouchEvent 而不是 Compose pointerInput：命中顺序由 FrameLayout 保证，
        // 不经过 interop，也不受 Compose 版本影响
        assertTrue(
            "触摸面没有用原生 onTouchEvent",
            source.contains("private class DragSurface(context: Context) : View(context)") &&
                source.contains("override fun onTouchEvent(event: MotionEvent): Boolean"),
        )
        assertTrue(
            "DOWN 必须被吃掉，否则后面收不到 MOVE",
            source.contains("// 必须吃掉 DOWN，否则后面收不到 MOVE"),
        )
    }

    @Test
    fun `Compose 兜底与原生触摸面互斥`() {
        // 两条路同时生效会转两倍。DragSurface 收到过触摸就把兜底关掉
        assertTrue("触摸面没有记录「是否收到过触摸」", source.contains("var sawTouch: Boolean"))
        assertTrue(
            "Compose 兜底没有跟原生触摸面互斥，可能一份拖动转两次",
            source.contains("stage?.nativeGestureActive != true"),
        )
    }

    // ---------------------------------------------------------------- 上下左右都要能拖

    @Test
    fun `拖动是全向的 不再只拦横向`() {
        assertFalse(
            "又用回 detectHorizontalDragGestures：纵向会被让给页面滚动，上下就拖不动了",
            source.contains("detectHorizontalDragGestures"),
        )
        assertTrue(
            "Compose 兜底也要全向",
            source.contains("detectDragGestures("),
        )
        // 两个轴都要真的传下去
        assertTrue(
            "拖动增量没有同时给 dx 和 dy",
            source.contains("drag.onDrag = { dx, dy -> web.orbit(dx, dy) }") &&
                source.contains("stage?.orbit(amount.x, amount.y)"),
        )
        assertTrue(
            "JS 入口不是双轴的 __leapOrbit",
            source.contains("window.__leapOrbit&&window.__leapOrbit(") &&
                source.contains("fun orbit(dxPx: Float, dyPx: Float)"),
        )
    }

    @Test
    fun `上下方向的旋转是抬机位 而且夹住不钻车底`() {
        val install = bodyOf("private fun installApi(")
        assertTrue("纵向增量没有叠加到 phi", install.contains("st.phi += ay"))
        assertTrue("横向增量没有叠加到 theta", install.contains("st.theta -= ax"))
        assertTrue("仰角上限没夹", install.contains("elev > 1.30"))
        assertTrue("仰角下限没夹（会钻到车底）", install.contains("elev < -0.05"))
        assertTrue("phi 累计值没夹", install.contains("st.phi > 1.05"))
    }

    @Test
    fun `旋转入口不再被 sceneReady 拦住`() {
        val orbit = bodyOf("fun orbit(dxPx: Float, dyPx: Float)", "\n    fun setDark(")
        // 只看代码：注释里会引用那行旧写法做反例
        assertFalse(
            "orbit 又被 !sceneReady 拦住了：首帧回调晚一两秒，第一下拖动就白拖",
            codeOnly(orbit).contains("!sceneReady"),
        )
        assertTrue("orbit 没调 JS", orbit.contains("window.__leapOrbit"))
    }

    // ---------------------------------------------------------------- JS 侧转不转得动

    @Test
    fun `旋转函数只定义一次 且在无守卫的 installApi 里`() {
        assertEquals(
            "__leapOrbit 被定义了多处，容易一边是守卫版一边是无守卫版",
            1,
            Regex("window\\.__leapOrbit\\s*=\\s*function").findAll(source).count(),
        )
        val install = bodyOf("private fun installApi(")
        assertTrue("installApi 里没有定义 __leapOrbit", install.contains("window.__leapOrbit = function"))

        // 就是这一行让车模拖不动：拿 window.viewer 当唯一入口，取不到就整段 return
        val code = source.lineSequence()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            .joinToString("\n")
        assertFalse(
            "又出现 `var v = window.viewer;` 这种单一入口写法，实例换个名字就整段失效",
            code.contains("var v = window.viewer;"),
        )
    }

    @Test
    fun `包住 renderer_render 才不会被引擎复位`() {
        val install = bodyOf("private fun installApi(")
        assertTrue(
            "没有包住 renderer.render：引擎每帧自己算机位，拖完下一帧就被盖回去" +
                "（现象是「拖的时候动一下、松手弹回去」）",
            install.contains("r.render = function(scene, camera)"),
        )
        assertTrue("包完之后没有标记，会重复包装", install.contains("__leapOrbitHooked"))
        assertTrue(
            "包装体里没有在画之前落地机位",
            install.contains("if (st.theta || st.phi) applyOrbit();"),
        )
        // 每帧把 render 传进来的相机记下来 —— 这样连实例叫什么都不用知道
        assertTrue("没有从 render 参数里取相机", install.contains("st.cam = camera"))
        assertTrue("没有常驻的兜底帧循环", install.contains("requestAnimationFrame(pump)"))
    }

    @Test
    fun `认得 three 的对象特征 不靠名字`() {
        val install = bodyOf("private fun installApi(")
        assertTrue("没有按 isCamera / render+domElement 这些特征认人", install.contains("looksLikeRenderer"))
        assertTrue("没有扫 window 的兜底", install.contains("scan(window, 2,"))
        assertTrue("扫 window 没有预算上限，会卡住 JS 线程", install.contains("budget = 4000"))
        assertTrue(
            "orbit 必须落到相机的 position 与 lookAt",
            install.contains("cam.position.set(b.tx + x * k, b.ty + ny, b.tz + z * k)") &&
                install.contains("cam.lookAt(b.tx, b.ty, b.tz)"),
        )
    }

    @Test
    fun `连相机都摸不到时退化为转车身`() {
        val install = bodyOf("private fun installApi(")
        assertTrue("没有转车身的兜底", install.contains("body.rotation.y -= ax"))
        assertTrue("兜底没有先找车身", install.contains("var body = carObject(v)"))
    }

    @Test
    fun `画质调优改过机位后要重取基准`() {
        val tune = bodyOf("private fun tuneViewer(")
        assertTrue(
            "tuneViewer 改了机位却没让轨道重取基准：第一次拖动会被拽回调优前的位置",
            tune.contains("window.__leapResetBase()"),
        )
        assertTrue(
            "没有 __leapResetBase 定义",
            source.contains("window.__leapResetBase = function()"),
        )
    }

    // ---------------------------------------------------------------- 首帧链路

    @Test
    fun `首帧和页面加载完都要装一遍 API`() {
        val ready = bodyOf("private fun markReady(")
        assertTrue("首帧就绪时没有安装 JS API", ready.contains("installApi(view)"))
        assertTrue(
            "页面加载完没有安装，H5 自行 reload 后 API 会丢",
            source.contains("override fun onPageFinished") && source.contains("installApi(view)\n            }"),
        )
    }

    @Test
    fun `画质调优复用惰性解析结果`() {
        assertTrue(
            "画质调优没有复用惰性解析结果",
            source.contains("var findV = window.__leapViewer"),
        )
    }
}
