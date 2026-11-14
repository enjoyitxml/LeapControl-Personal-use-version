package com.leapcontrol.app.trip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「退出登录不再删行程 + 三个备份出口」的守门测试（用户 2026-11-09）。
 *
 * ## 用户反馈
 * 每次退出登录，行程轨迹里的数据就没了。
 *
 * ## 真因
 * `AppRuntime.logout()` 调的是 `TripTracker.clear()`，它把 `filesDir/trip/trips.json`
 * 整份删掉。登出只是想换账号 / 排查问题，同一辆车登回来就该还在。
 *
 * 所以钉住四件事：
 * 1. 登出只 `detach()`（清内存），**绝不** `clear()`（删文件）；
 * 2. 每次落盘之后顺手判一次「今天备份过没有」（每天一份到下载目录）；
 * 3. 导出 / 导入 / 立即备份三个入口都在，并挂在设置页上；
 * 4. 自动备份默认开启、只留 7 份（和零记一致）。
 */
class TripBackupGateTest {

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun file(path: String): String = File(projectDir, path).readText()

    private val runtime by lazy { file("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt") }
    private val tracker by lazy { file("app/src/main/java/com/leapcontrol/app/trip/TripTracker.kt") }
    private val settings by lazy { file("app/src/main/java/com/leapcontrol/app/ui/SettingsScreen.kt") }
    private val autoBackup by lazy { file("app/src/main/java/com/leapcontrol/app/trip/TripAutoBackup.kt") }
    private val backup by lazy { file("app/src/main/java/com/leapcontrol/app/trip/TripBackup.kt") }

    /** 从签名到同缩进的右花括号，即整个函数体。 */
    private fun bodyOf(text: String, signature: String): String {
        val at = text.indexOf(signature)
        assertTrue("找不到 $signature —— 函数被改名了，这条守门要跟着更新", at >= 0)
        val lineStart = text.lastIndexOf('\n', at) + 1
        val indent = text.substring(lineStart, at).takeWhile { it == ' ' }
        val end = text.indexOf("\n$indent}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    /** 去掉行注释：注释里会引用 `TripTracker.clear` 说明「为什么不再这么干」。 */
    private fun stripComments(text: String): String =
        text.lines().joinToString("\n") { line ->
            val at = line.indexOf("//")
            if (at >= 0) line.substring(0, at) else line
        }

    @Test
    fun logoutDetachesInsteadOfDeleting() {
        val body = stripComments(bodyOf(runtime, "suspend fun logout()"))
        assertTrue("登出必须放下内存里的行程", body.contains("TripTracker.detach()"))
        assertFalse("登出不能再删盘上的行程文件", body.contains("TripTracker.clear("))
    }

    @Test
    fun detachKeepsTheFile() {
        val body = stripComments(bodyOf(tracker, "fun detach()"))
        assertFalse("detach 只清内存，不能动文件", body.contains("TripStore.clear"))
        assertTrue(body.contains("loadedVin = null"))
    }

    @Test
    fun persistTriggersDailyBackup() {
        val body = stripComments(bodyOf(tracker, "private fun persist("))
        assertTrue("每次落盘后要判一次「今天备份过没有」", body.contains("autoBackupIfDue(context)"))
    }

    @Test
    fun threeBackupExitsExist() {
        assertTrue(tracker.contains("fun exportBackup("))
        assertTrue(tracker.contains("fun importBackup("))
        assertTrue(tracker.contains("fun backupToDownloadsNow("))
        // 导入必须走合并，不能覆盖本机
        assertTrue(tracker.contains("TripBackup.merge("))
    }

    @Test
    fun settingsHasTheEntry() {
        assertTrue("设置页要有行程备份入口", settings.contains("TripBackupRows(runtime = runtime"))
        // 2026-11-13 版式整理：零记与行程的出口合并进同一张「备份与找回」卡，
        // 用户丢数据时只需要记一个地方（原来两张卡分在「零记」「行程轨迹」两个分组里）。
        assertTrue(
            "行程备份应与零记备份同卡，卡名是「备份与找回」",
            settings.contains("SettingsCard(\"备份与找回\")"),
        )
    }

    @Test
    fun autoBackupDefaultsOnAndKeepsSeven() {
        assertTrue("自动备份默认开启", autoBackup.contains("getBoolean(KEY_ENABLED, true)"))
        assertTrue("只留最近 7 份", autoBackup.contains("KEEP = 7"))
        assertTrue("文件名要能认出来是行程", autoBackup.contains("零控行程-自动备份-"))
    }

    @Test
    fun backupFormatIsSigned() {
        // kind 让「随便一个 JSON」在解析前就被挡住
        assertTrue(backup.contains("const val KIND = \"leapcontrol-trip\""))
        assertTrue(backup.contains("fun merge("))
    }
}
