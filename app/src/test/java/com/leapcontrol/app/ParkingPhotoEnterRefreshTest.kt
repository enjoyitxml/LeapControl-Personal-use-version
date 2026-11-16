package com.leapcontrol.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 驻车拍照页的「进来就刷新 + 显示驻车时间」守门（2026-11-15）。
 *
 * ## 用户的原话
 * 「驻车拍照使用车端照片方案时，每次进入要自动刷新一下，不然会显示上一次的照片。」
 * 「驻车拍照增加显示一下驻车的时间，手动方案时显示的就是拍照时间，车端方案就是车端上报的停车时间。」
 * 「点击驻车照片时，下方的提示文字显示不完全，下半部分会被遮挡。」
 *
 * ## 为什么用文本断言而不是跑 UI
 * 这三件事都是「接线 + 一句话」的错法，全在当前文件的几行里：
 *  - 刷新要是退回到 `force = false`，界面上完全看不出来，只会一直显示上一次那趟的车位；
 *  - 时间要是退回相对时长（「12 分钟前」），用户还得自己换算；
 *  - 底部提示要是退回固定 dp，换个导航方式就又被压住。
 * 这些都不会崩，只会悄悄错，所以钉在这里。
 */
class ParkingPhotoEnterRefreshTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(path: String): String = File(projectDir, path).readText()

    private val state = read("app/src/main/java/com/leapcontrol/app/ui/VehicleLocationCard.kt")
    private val screen = read("app/src/main/java/com/leapcontrol/app/ui/ParkingPhotoScreen.kt")
    private val preview = read("app/src/main/java/com/leapcontrol/app/ui/PhotoPreviewDialog.kt")

    @Test
    fun `驻车拍照页每次进入都强制取一次车端照片`() {
        assertTrue(
            "驻车拍照页没传 refreshOfficialOnEnter，进来看到的还是上一次停车缓存的那张",
            screen.contains("refreshOfficialOnEnter = true"),
        )
        assertTrue(
            "刷新没按「进入即强制」分支，会退回到每辆车每次启动只试一次",
            state.contains("runtime.refreshOfficialPhoto(force = refreshOfficialOnEnter || !slot.hasPhoto)"),
        )
        assertTrue(
            "副作用没把 refreshOfficialOnEnter 算进 key，切方案 / 换车时才触发，进来不触发",
            state.contains("LaunchedEffect(source, vin, refreshOfficialOnEnter)"),
        )
    }

    @Test
    fun `首页卡与找车页不跟着强制刷新`() {
        // 这两个入口只在默认参数下调用 rememberParkingPhoto：不传就是 false
        assertTrue(
            "首页卡 / 找车页传了 refreshOfficialOnEnter，会跟着每次重组打云端",
            !read("app/src/main/java/com/leapcontrol/app/ui/FindCarScreen.kt")
                .contains("refreshOfficialOnEnter"),
        )
    }

    @Test
    fun `两种方案都写具体驻车时刻 不写几小时前`() {
        assertTrue(
            "手机方案那行没用 parkedAt",
            screen.contains("source = ParkingPhotoSource.PHONE"),
        )
        assertTrue(
            "车端方案那行没用 parkedAt",
            screen.contains("source = ParkingPhotoSource.OFFICIAL"),
        )
        assertTrue(
            "旧的「拍摄于 x 前」说法又回来了",
            !screen.contains("ParkingPhotoRules.takenAt(") &&
                !screen.contains("officialTakenAt("),
        )
    }

    @Test
    fun `照片预览底部提示不再贴着屏幕底边`() {
        assertTrue(
            "提示文字的底部留白没按真实导航栏高度算，下半截会压进导航栏",
            preview.contains("bottom = dialogBottomGap(extra = 16.dp)"),
        )
        assertFalse(
            "navigationBarsPadding() 在全屏 Dialog 里是 0，靠它避让等于没避",
            preview.contains(".navigationBarsPadding()"),
        )
    }
}
