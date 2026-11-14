package com.leapcontrol.app

import com.leapcontrol.app.journal.ChargeAnchor
import com.leapcontrol.app.journal.ChargeCorrection
import com.leapcontrol.app.journal.ChargeCycle
import com.leapcontrol.app.journal.ChargeCycleState
import com.leapcontrol.app.journal.ChargeEvidence
import com.leapcontrol.app.journal.ChargeHistory
import com.leapcontrol.app.journal.ChargeSample
import com.leapcontrol.app.journal.ChargeSession
import com.leapcontrol.app.journal.JournalDates
import com.leapcontrol.app.ui.chargeHistoryRange
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * 「手动修正上次充电」的纯逻辑 + 接线守门。
 *
 * 用户反馈（2026-11-13）：结束充电时没打开 App，那次充电就没被看见，
 * 「自上次充电行驶里程」会一直算到更早那次。修完之后最怕的不是算错，而是
 * **下一次刷新车况把用户刚填的三个数顶回去** —— 车端 `chargeState` 会残留，
 * 所以修正值必须带锁（[ChargeAnchor.edited]），这里把每一条解锁路径都钉住。
 */
class ChargeCorrectionTest {

    private val day = LocalDate.of(2026, 11, 10)
    private val at = JournalDates.timestampOf(day, 22, 30)
    private val now = at + 60 * 60 * 1000L

    private val projectDir: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun codeOf(path: String): String =
        File(projectDir, path).readText().lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    private fun build(
        date: String = "2026-11-10",
        time: String = "22:30",
        percent: String = "90",
        km: String = "12345.6",
        clock: Long = now,
    ) = ChargeCorrection.build(date, time, percent, km, now = clock, today = day)

    private fun corrected() = ChargeAnchor(
        km = 12_000.0,
        at = at,
        socPeak = 90,
        manual = true,
        edited = true,
        ended = true,
    )

    private fun sample(
        state: Int?,
        soc: Int? = null,
        km: Double? = null,
        moving: Boolean = false,
        evidence: ChargeEvidence = ChargeEvidence.UNKNOWN,
    ) = ChargeSample(chargeState = state, soc = soc, totalKm = km, moving = moving, evidence = evidence)

    private fun observe(anchor: ChargeAnchor, s: ChargeSample, clock: Long = now) =
        ChargeCycle.observe(ChargeCycleState(anchor = anchor), s, clock)

    // ---------------- 日期 / 时间的解析 ----------------

    @Test
    fun `预填的日期时间能原样读回来`() {
        assertEquals("2026-11-10", ChargeCorrection.formatDate(at))
        assertEquals("22:30", ChargeCorrection.formatTime(at))
    }

    @Test
    fun `日期支持横线斜杠中文和省略年份`() {
        assertEquals(day, ChargeCorrection.parseDate("2026-11-10", day))
        assertEquals(day, ChargeCorrection.parseDate("2026/11/10", day))
        assertEquals(day, ChargeCorrection.parseDate("11-10", day))
        assertEquals(day, ChargeCorrection.parseDate("11月10日", day))
        assertNull(ChargeCorrection.parseDate("", day))
        assertNull(ChargeCorrection.parseDate("2026-02-30", day))
        assertNull(ChargeCorrection.parseDate("随便写的", day))
    }

    @Test
    fun `时间支持 24 小时制与全角冒号`() {
        assertEquals(22 to 30, ChargeCorrection.parseTime("22:30"))
        assertEquals(9 to 5, ChargeCorrection.parseTime("9:05"))
        assertEquals(22 to 30, ChargeCorrection.parseTime("2230"))
        assertEquals(22 to 30, ChargeCorrection.parseTime("22：30"))
        assertNull(ChargeCorrection.parseTime("25:00"))
        assertNull(ChargeCorrection.parseTime("22:70"))
        assertNull(ChargeCorrection.parseTime(""))
    }

    @Test
    fun `电量和里程宽容解析但不放过非法值`() {
        assertEquals(90, ChargeCorrection.parsePercent("90"))
        assertEquals(90, ChargeCorrection.parsePercent("90%"))
        assertEquals(90, ChargeCorrection.parsePercent("90％"))
        assertNull(ChargeCorrection.parsePercent("")) // 不知道就留空
        assertNull(ChargeCorrection.parsePercent("101"))
        assertNull(ChargeCorrection.parsePercent("-1"))

        assertEquals(12_345.6, ChargeCorrection.parseKm("12345.6")!!, 0.0001)
        assertEquals(12_345.0, ChargeCorrection.parseKm("12,345")!!, 0.0001)
        assertEquals(12_345.0, ChargeCorrection.parseKm("12345 km")!!, 0.0001)
        assertNull(ChargeCorrection.parseKm("0"))
        assertNull(ChargeCorrection.parseKm("abc"))
    }

    // ---------------- 四格一起校验 ----------------

    @Test
    fun `三个数填全就折成一条修正值`() {
        val ok = build() as ChargeCorrection.Parse.Ok
        assertEquals(at, ok.values.at)
        assertEquals(90, ok.values.socPeak)
        assertEquals(12_345.6, ok.values.km, 0.0001)
    }

    @Test
    fun `充到多少留空是合法的 不填就不显示`() {
        val ok = build(percent = "") as ChargeCorrection.Parse.Ok
        assertNull(ok.values.socPeak)
    }

    @Test
    fun `任何一格错都不落盘 并能说清是哪格`() {
        listOf(
            build(date = "不是日期") to ChargeCorrection.Field.DATE,
            build(time = "99:99") to ChargeCorrection.Field.TIME,
            build(percent = "120") to ChargeCorrection.Field.PERCENT,
            build(km = "0") to ChargeCorrection.Field.KM,
            build(date = "2026-11-11", clock = at) to ChargeCorrection.Field.FUTURE,
        ).forEach { (parsed, field) ->
            assertTrue("$field 应该被拦下", parsed is ChargeCorrection.Parse.Err)
            assertEquals(field, (parsed as ChargeCorrection.Parse.Err).field)
            assertTrue(ChargeCorrection.errorText(field).isNotBlank())
        }
    }

    // ---------------- 修正值的锁定：最关键的回归 ----------------

    @Test
    fun `修正后未充电的观测不会改写起点`() {
        assertNull(observe(corrected(), sample(0, soc = 80, km = 12_100.0)))
        assertNull(observe(corrected(), sample(6, soc = 80, km = 12_100.0)))
    }

    @Test
    fun `修正后已完成的残影不会改写起点`() {
        assertNull(observe(corrected(), sample(2, soc = 90, km = 12_100.0)))
        assertNull(observe(corrected(), sample(2, soc = 90, km = 12_000.0)))
    }

    @Test
    fun `修正后充电中但没旁证 或者车没动过 都不改写起点`() {
        assertNull(observe(corrected(), sample(1, soc = 90, km = 12_100.0)))
        assertNull(
            observe(
                corrected(),
                sample(1, soc = 90, km = 12_000.0, evidence = ChargeEvidence.CONFIRMED),
            ),
        )
        assertNull(
            observe(
                corrected(),
                sample(5, soc = 90, km = 12_100.0, evidence = ChargeEvidence.REFUTED),
            ),
        )
    }

    @Test
    fun `修正后车在动的观测一律不作数`() {
        assertNull(
            observe(
                corrected(),
                sample(1, soc = 60, km = 12_200.0, moving = true,
                    evidence = ChargeEvidence.CONFIRMED),
            ),
        )
    }

    @Test
    fun `明确看见下一次真充电才解锁 新起点不再带修正标记`() {
        val next = observe(
            corrected(),
            sample(1, soc = 30, km = 12_200.0, evidence = ChargeEvidence.CONFIRMED),
        )
        assertNotNull(next)
        assertEquals(12_200.0, next!!.anchor!!.km, 0.0001)
        assertEquals(30, next.anchor!!.soc)
        assertFalse(next.anchor!!.edited)
        assertNull(next.pending)
    }

    @Test
    fun `修正值直接决定卡片上的行驶里程`() {
        val ui = ChargeCycle.summary(corrected(), 12_137.4)
        assertTrue(ui is com.leapcontrol.app.journal.ChargeCycleUi.Ready)
        assertEquals(137.4, (ui as com.leapcontrol.app.journal.ChargeCycleUi.Ready).km, 0.0001)
        assertEquals(at, ui.at)
        assertEquals(90, ui.socPeak)
    }

    // ---------------- 持久化与历史 ----------------

    @Test
    fun `修正标记要跟着锚点存进 JSON`() {
        val back = ChargeAnchor.fromJson(JSONObject(corrected().toJson().toString()))
        assertEquals(corrected(), back)
        assertTrue(back!!.edited)
    }

    @Test
    fun `手动修正的历史条目即使只有充到多少也留得住`() {
        val session = ChargeSession(startAt = at, socTo = 90, km = 12_000.0, edited = true)
        assertEquals(session, ChargeSession.fromJson(JSONObject(session.toJson().toString())))
        assertNotNull(ChargeHistory.sessionOf(corrected()))
        // 自动观测出来的「只有峰值、没有起点电量」仍然按老规矩不进历史
        assertNull(ChargeHistory.sessionOf(corrected().copy(edited = false)))
    }

    @Test
    fun `历史里手动修正的条目不写成零时长的充电区间`() {
        val text = chargeHistoryRange(ChargeSession(startAt = at, socTo = 90, edited = true))
        assertTrue(text, text.contains("手动填入"))
        assertFalse(text.contains("→"))
    }

    // ---------------- 接线守门 ----------------

    @Test
    fun `修正不会把旧起点归档成一条假充电记录`() {
        val source = codeOf("app/src/main/java/com/leapcontrol/app/journal/JournalStore.kt")
        val body = source.substringAfter("fun correctChargeCycle").substringBefore("private fun mutate")
        assertTrue("修的是同一起点，不能归档", "ChargeHistory.archive" !in body)
        assertTrue(body, "edited = true" in body)
        assertTrue(body, "chargePending = null" in body)
    }

    @Test
    fun `观测入口确实认这把锁 且只给真充电开一个口子`() {
        val source = codeOf("app/src/main/java/com/leapcontrol/app/journal/ChargeCycle.kt")
        val body = source.substringAfter("fun observe(").substringBefore("private fun settlePending")
        assertTrue(body, "it.edited" in body)
        assertTrue(body, "ChargeEvidence.CONFIRMED" in body)
        assertTrue(body, "newCharge" in body)
    }

    @Test
    fun `首页卡片弹层与修正对话框都接上了线`() {
        val sheet = codeOf("app/src/main/java/com/leapcontrol/app/ui/HomeChargeCycleCard.kt")
        assertTrue(sheet, "onCorrect" in sheet)
        assertTrue(sheet, "手动修正上次充电" in sheet)

        val dialog = codeOf("app/src/main/java/com/leapcontrol/app/ui/ChargeCycleCorrectionDialog.kt")
        assertTrue(dialog, "ChargeCorrection.build" in dialog)

        val home = codeOf("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt")
        assertTrue(home, "ChargeCycleCorrectionDialog(" in home)
        assertTrue(home, "correctChargeCycle(values)" in home)
    }
}
