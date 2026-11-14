package com.leapcontrol.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 轨迹地图全屏查看的**版式守门**（2026-11-08 起，2026-11-13 两次追加）。
 *
 * ## 用户的原话
 * 「行程轨迹地图点击查看大图之后，会把顶部的退出按钮和起点重点都盖住，处理好。」
 * 「轨迹修正界面的按钮完全被遮盖住了，看不到，也无法正确操作。」
 * 「轨迹修正的按钮放在顶部轨迹详情的下方，另外删掉涂抹的方案，只保留选点的方案，
 *   轨迹修正的二级界面字体太小了，所有的字体和按钮都放大一点。」
 * 「修正了之后我好像没有看到『确认』、『保存』按钮。」
 *
 * ## 钉住的规则
 * 1. 地图层必须自己 `clipToBounds()`，标题栏 / 底部说明是浮在地图上的两层；
 * 2. 两层高度量出来喂给取景，起点终点才不会躲到浮层底下；
 * 3. 全屏窗口走系统安全区（关掉系统栏避让会让 `navigationBarsPadding()` 恒为 0，
 *    底部栏被手势区整排裁掉）；提示浮在栏外，不占按钮行；
 * 4. **修正态全部按钮在顶部「轨迹详情」那一层**，最后一个是主按钮「确认保存」；
 *    右上角胶囊只在浏览态出现；
 * 5. 字号与点击区整体放大，不许退回到 10.5sp / 30dp 那套小字；
 * 6. 涂抹方案彻底删除，界面只剩选点。
 */
class TripMapViewerLayoutTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val viewer: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/TripMapViewer.kt").readText()
    }

    @Test
    fun `地图层自己裁剪 不许画到标题栏上`() {
        assertTrue(
            "地图层少了 clipToBounds()，最上面一排瓦片会画到画布外盖住标题栏",
            viewer.contains(".clipToBounds()"),
        )
    }

    @Test
    fun `标题栏和底部说明是浮层 不再是竖排`() {
        assertTrue("标题栏没贴顶浮在地图上", viewer.contains(".align(Alignment.TopCenter)"))
        assertTrue("底部说明没贴底浮在地图上", viewer.contains(".align(Alignment.BottomCenter)"))
    }

    @Test
    fun `浮层高度喂给取景 起终点不被压住`() {
        assertTrue(
            "取景没带浮层留白，整段视角的起终点会压在标题栏 / 底部说明底下",
            viewer.contains("TripMapCamera.fit(line, size.width, size.height, headerPx, footerPx, sideInsetPx)"),
        )
        assertTrue("标题栏高度没量出来", viewer.contains("onSizeChanged { headerPx = it.height }"))
        assertTrue("底部说明高度没量出来", viewer.contains("onSizeChanged { footerPx = it.height }"))
    }

    @Test
    fun `整段胶囊与高德署名都按浮层让开`() {
        assertTrue("「整段」胶囊还压在标题栏下面", viewer.contains("padding(top = headerDp + 8.dp"))
        assertTrue("高德署名还压在底部说明里", viewer.contains("bottom = footerDp + 8.dp"))
    }

    @Test
    fun `用户拖过之后取景不再覆盖视角`() {
        assertTrue("手势里没标记 touched，用户拖完会被取景重置", viewer.contains("touched = true"))
        assertTrue(
            "取景没判断 touched，用户的手势会被后来的浮层测量打断",
            viewer.contains("if (!touched) camera = fit"),
        )
        assertFalse(
            "「整段」按钮必须重新把 touched 放回 false，否则再也不会自动贴合",
            viewer.contains("camera = fitCamera\n                            touched = true"),
        )
    }

    // ---------------- 系统安全区与提示 ----------------

    @Test
    fun `全屏窗口必须走系统安全区 不许把栏贴到手势区上`() {
        assertFalse(
            "关掉系统栏避让会让 navigationBarsPadding() 恒为 0，按钮被整排裁掉",
            viewer.contains("decorFitsSystemWindows = false"),
        )
        assertTrue("全屏宽度得靠 usePlatformDefaultWidth", viewer.contains("usePlatformDefaultWidth = false"))
        assertTrue("顶部标题栏还要避让状态栏", viewer.contains("statusBarsPadding()"))
        assertTrue("底部说明还要避让导航栏", viewer.contains("navigationBarsPadding()"))
    }

    @Test
    fun `临时提示浮在底部栏上方 不再挤占按钮行`() {
        assertTrue("提示没钉在量出来的栏高之上", viewer.contains("bottom = footerDp + 12.dp"))
        val footer = viewer.substringAfter("// ---------------- 底部说明：浮层")
        assertFalse(
            "提示塞回栏内会多出一行，出现 / 消失时按钮还会上下跳",
            footer.contains("hint?.let"),
        )
    }

    @Test
    fun `底部栏铺足底色 地图线条不许从说明背后透上来`() {
        assertTrue(
            "渐变起始不能是透明，否则浅色底图上像按钮被盖住",
            viewer.contains("0f to C.bgTop.copy(alpha = 0.86f)"),
        )
    }

    // ---------------- 2026-11-13：按钮上移 + 放大 + 只留选点 ----------------

    @Test
    fun `修正按钮全在顶部轨迹详情那一层 不在底部栏里`() {
        val detail = viewer.indexOf("顶部：轨迹详情 + 修正操作栏")
        val headerMeasure = viewer.indexOf("onSizeChanged { headerPx = it.height }")
        val save = viewer.indexOf("text = \"确认保存\"")
        val footerMeasure = viewer.indexOf("onSizeChanged { footerPx = it.height }")
        assertTrue("顶部详情 + 操作栏的标记没了", detail >= 0)
        assertTrue("确认保存按钮不见了", save >= 0)
        assertTrue(
            "按钮必须排在顶部标题栏那一层（header 量高之后、footer 之前）",
            save > headerMeasure && headerMeasure > detail && save < footerMeasure,
        )
    }

    @Test
    fun `编辑态七个按钮都在 确认保存是主按钮且有写回与反馈`() {
        listOf("撤一点", "撤销", "恢复原状", "取消", "确认保存").forEach { label ->
            assertTrue("少了按钮「$label」", viewer.contains("text = \"$label\""))
        }
        assertTrue("少了「按导航补齐」", viewer.contains("\"按导航补齐\""))
        assertTrue("少了「修正路线」入口", viewer.contains("text = \"修正路线\""))
        val saveBlock = viewer.substringAfter("text = \"确认保存\"").take(140)
        assertTrue("确认保存必须是实心主按钮", saveBlock.contains("primary = true"))
        assertTrue("确认保存没接写回", viewer.contains("onSaveFix?.invoke(editedLine ?: line)"))
        assertTrue("保存后没有明确反馈", viewer.contains("hint = \"路线已保存\""))
        assertTrue("右上角胶囊编辑态要隐藏", viewer.contains("右上角胶囊：只在浏览态"))
    }

    @Test
    fun `字号与按钮点击区整体放大 不许退回小字`() {
        assertTrue("标题没放大到 18sp", viewer.contains("fontSize = 18.sp"))
        assertTrue("说明没放大到 14sp", viewer.contains("fontSize = 14.sp"))
        assertTrue("按钮与口径没放大到 13sp", viewer.contains("fontSize = 13.sp"))
        assertTrue("按钮点击区至少 38dp", viewer.contains("heightIn(min = 38.dp)"))
        assertTrue("加高后文字要居中", viewer.contains("wrapContentHeight(Alignment.CenterVertically)"))
        assertTrue("按钮水平留白要跟着放大", viewer.contains("padding(horizontal = 14.dp, vertical = 8.dp)"))
        assertFalse("不许再出现 10.5sp 的小字", viewer.contains("fontSize = 10.5.sp"))
        assertFalse("点击区不许退回 30dp", viewer.contains("heightIn(min = 30.dp)"))
    }

    @Test
    fun `涂抹方案已删除 只剩轻点选点手势`() {
        listOf(
            "FixMode", "routeEditGestures", "draft =", "RouteSnap.Mode",
            "text = \"涂抹\"", "text = \"选点\"", "仍按手画线保留",
        ).forEach { leftover ->
            assertFalse("涂抹残留：$leftover", viewer.contains(leftover))
        }
        assertTrue("选点手势没了", viewer.contains("routePickGestures("))
        assertTrue("手势要按 editing 重启", viewer.contains("pointerInput(editing)"))
    }
}
