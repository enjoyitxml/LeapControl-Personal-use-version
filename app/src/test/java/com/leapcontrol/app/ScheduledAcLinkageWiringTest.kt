package com.leapcontrol.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时空调联动的「接线」守门。
 *
 * 纯逻辑已经由 [ScheduledAcLinkageTest] 覆盖，这里只盯住**漏接**：
 * 逻辑写完了但某处没调用、调度器没注册、保存时忘了落盘，
 * 表现都是「功能看起来做了，到点什么也没发生」，靠手测很难发现。
 */
class ScheduledAcLinkageWiringTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun read(path: String): String = File(projectDir, path).readText(Charsets.UTF_8)

    private val runtime = "app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt"
    private val home = "app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt"
    private val screens = "app/src/main/java/com/leapcontrol/app/ui/VehicleScreens.kt"
    private val manifest = "app/src/main/AndroidManifest.xml"

    @Test
    fun `保存定时空调时同时落盘联动并重排闹钟`() {
        val src = read(runtime)
        val body = src.substringAfter("suspend fun applyScheduledAc").take(1600)
        assertTrue("没把联动配置写进本地", body.contains("saveScheduledAcLinkage"))
        assertTrue("没重排闹钟，改完设置要等下次开机才生效", body.contains("ScheduledAcLinkageScheduler.reschedule"))
        // 计划本身也要落本地：接收器到点只看本地，不请求云端
        assertTrue("没存开启状态", body.contains("saveScheduledAcEnabled"))
        assertTrue("没存开始时间", body.contains("saveScheduledAcStartTime"))
        assertTrue("没存重复日", body.contains("saveScheduledAcDays"))
    }

    @Test
    fun `接收器与开机广播都已注册`() {
        val src = read(manifest)
        assertTrue("ScheduledAcLinkageReceiver 未注册，闹钟会打到空处", src.contains(".ScheduledAcLinkageReceiver"))
        assertTrue("ScheduledAcLinkageBootReceiver 未注册", src.contains(".ScheduledAcLinkageBootReceiver"))
        // 开机广播只能挂在 BootReceiver 上，不能挂到执行端
        val bootBlock = src.substringAfter(".ScheduledAcLinkageBootReceiver").take(300)
        assertTrue("开机广播没接上", bootBlock.contains("BOOT_COMPLETED"))
        assertTrue("升级后没重排", bootBlock.contains("MY_PACKAGE_REPLACED"))
    }

    @Test
    fun `编辑页有联动卡片且保存时带上`() {
        val page = read(screens)
        assertTrue("页面没接 linkage 参数", page.contains("linkage: ScheduledAcLinkage"))
        assertTrue("没有到点联动卡片", page.contains("ScheduledAcLinkageCard"))
        assertTrue("没有关闭车窗开关", page.contains("linkage.closeWindows"))
        assertTrue("没有方向盘加热开关", page.contains("linkage.steeringWheelHeating"))

        val host = read(home)
        assertTrue("首页没读本地联动配置", host.contains("loadScheduledAcLinkage"))
        assertTrue("保存时没把联动传下去", host.contains("applyScheduledAc(plan, acLinkage)"))
    }

    @Test
    fun `接收器到点只读本地不先请求云端`() {
        val src = read("app/src/main/java/com/leapcontrol/app/ScheduledAcLinkageReceiver.kt")
        // 到点可能没网，先请求云端会让网络抖动等于功能失效
        assertTrue("接收器里不该出现 loadScheduledAc 之类的云端回读", !src.contains("loadScheduledAc()"))
        assertTrue("没做重复日判断", src.contains("parseToDaySet"))
        assertTrue("没通知用户执行结果", src.contains("notify("))
        assertTrue("执行完没排下一次，只有第一天会生效", src.contains("ScheduledAcLinkageScheduler.reschedule"))
    }
}
