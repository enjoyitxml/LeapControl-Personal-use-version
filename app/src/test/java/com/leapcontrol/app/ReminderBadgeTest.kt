package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页「保险续费 / 保养提醒」两张卡角标的守门测试。
 *
 * 2026-11-07 用户给了两张新图标，要求两个图标底下都垫主题色圆形。
 * 这类改动编译得过、但很容易在后续重构里被改回「六边形 / 盾牌各画各的」，
 * 所以这里钉住：统一走主题色圆 + 图片资源，不再有自绘徽章路径。
 */
class ReminderBadgeTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private val source: String by lazy {
        File(projectDir, "app/src/main/java/com/leapcontrol/app/ui/ReminderBadge.kt").readText()
    }

    private fun res(name: String) =
        File(projectDir, "app/src/main/res/drawable-nodpi/$name")

    @Test
    fun `两张图标资源都在，且是透明底 PNG`() {
        listOf("ic_reminder_insurance.png", "ic_reminder_service.png").forEach { name ->
            val file = res(name)
            assertTrue("缺少图标资源 $name", file.isFile)
            val head = file.readBytes().take(4).toByteArray()
            // 0x89 超过 Byte 上限，显式转换；后三个是 ASCII "PNG"
            assertTrue(
                "$name 不是 PNG",
                head.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
            )
        }
    }

    @Test
    fun `两张卡共用一个主题色实心圆`() {
        assertTrue("角标没有裁成圆形", source.contains(".clip(CircleShape)"))
        assertTrue("圆底没用主题色 accent", source.contains(".background(accent)"))
        assertEquals(
            "保险 / 保养必须都走同一个 ThemedIconBadge（定义 1 处 + 调用 2 处）",
            3,
            source.split("ThemedIconBadge(").size - 1,
        )
    }

    @Test
    fun `图形颜色要跟着主题色深浅翻转`() {
        assertTrue(
            "图标没有做现场染色：浅色主题圆上白图标会看不见",
            source.contains("ColorFilter.tint(onAccentInk(accent))"),
        )
        assertTrue("缺少亮度判断", source.contains("0.7152f * accent.green"))
    }

    @Test
    fun `保险与保养都是 80 百分比，圆底大小不变`() {
        assertTrue("圆里的图标没有按系数缩放", source.contains("Modifier.fillMaxSize(iconScale)"))
        assertTrue("保险没跟着保养调到 80%", source.contains("INSURANCE_ICON_SCALE = 0.80f"))
        assertTrue("缺少保养的 0.80 缩放系数", source.contains("SERVICE_ICON_SCALE = 0.80f"))
        assertFalse("保险还停在旧的 77%", source.contains("0.77f"))
        assertTrue("保险没有用上缩放系数", source.contains("iconScale = INSURANCE_ICON_SCALE"))
        assertTrue("保养没有用上缩放系数", source.contains("iconScale = SERVICE_ICON_SCALE"))
        assertTrue("圆底被一起缩了", source.contains(".size(size)"))
        assertFalse(
            "保险又退回默认 1f 了",
            source.contains("ThemedIconBadge(R.drawable.ic_reminder_insurance, size, modifier)"),
        )
    }

    @Test
    fun `旧的自绘六边形与盾牌不许回来`() {
        listOf("fun ShieldBadge(", "fun HexWrenchBadge(", "drawPath(", "Canvas(").forEach { banned ->
            assertFalse("又把手绘徽章加回来了：$banned", source.contains(banned))
        }
    }
}
