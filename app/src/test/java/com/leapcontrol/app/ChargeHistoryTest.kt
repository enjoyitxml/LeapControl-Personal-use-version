package com.leapcontrol.app

import com.leapcontrol.app.journal.ChargeAnchor
import com.leapcontrol.app.journal.ChargeHistory
import com.leapcontrol.app.journal.ChargeSession
import com.leapcontrol.app.journal.ChargeCycle
import com.leapcontrol.app.journal.ChargeSample
import com.leapcontrol.app.journal.JournalData
import com.leapcontrol.app.ui.chargeHistoryDate
import com.leapcontrol.app.ui.chargeHistoryDuration
import com.leapcontrol.app.ui.chargeHistoryRange
import com.leapcontrol.app.ui.chargeHistorySocTo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 充电历史的归档与文案。
 *
 * 这块最容易出的两类错，都要钉死：
 *  1. **记重**（同一次充电被「暂停」切成两条、或者起点没换却归档一遍）；
 *  2. **编数**（只看到「已充满」就编一个起点出来、结束时间拿「发现停了」的时刻冒充）。
 */
class ChargeHistoryTest {

    private val min = 60_000L
    private val hour = 60 * min

    /** 用本地时区构造时刻，测试换到任何时区的机器上都成立。 */
    private fun at(year: Int, month: Int, day: Int, hourOfDay: Int, minuteOfHour: Int = 0): Long =
        LocalDate.of(year, month, day)
            .atTime(hourOfDay, minuteOfHour)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val today = at(2026, 11, 10, 9, 0)

    private fun anchor(
        startAt: Long,
        soc: Int? = null,
        peak: Int? = null,
        lastChargingAt: Long? = null,
        endedAt: Long? = null,
        full: Boolean = false,
        km: Double = 12_000.0,
    ) = ChargeAnchor(
        km = km,
        at = startAt,
        soc = soc,
        socPeak = peak,
        full = full,
        ended = endedAt != null,
        lastChargingAt = lastChargingAt,
        endedAt = endedAt,
    )

    // ---------------- 归档 ----------------

    @Test
    fun `起点被顶掉时归档成一条记录`() {
        val prev = anchor(
            startAt = at(2026, 11, 9, 23, 10),
            soc = 32,
            peak = 88,
            lastChargingAt = at(2026, 11, 10, 7, 42),
            endedAt = at(2026, 11, 10, 7, 45),
        )
        val next = anchor(startAt = at(2026, 11, 11, 20, 0), soc = 40)
        val list = ChargeHistory.archive(emptyList(), prev, next)
        assertEquals(1, list.size)
        val s = list[0]
        assertEquals(at(2026, 11, 9, 23, 10), s.startAt)
        // 结束时间取「最后一次看见它在充电」，不是「发现它停了」的 7:45
        assertEquals(at(2026, 11, 10, 7, 42), s.endAt)
        assertEquals(32, s.socFrom ?: -1)
        assertEquals(88, s.socTo ?: -1)
        assertTrue(s.hasRange)
    }

    @Test
    fun `起点没换就不归档`() {
        val prev = anchor(startAt = today, soc = 30, peak = 60, lastChargingAt = today + hour)
        val list = ChargeHistory.archive(emptyList(), prev, prev.copy(socPeak = 70))
        assertTrue(list.isEmpty())
    }

    @Test
    fun `电量没涨也没有充满的不留记录`() {
        val prev = anchor(startAt = today, soc = 50, peak = 50, endedAt = today + min, lastChargingAt = today + min)
        assertTrue(ChargeHistory.archive(emptyList(), prev, anchor(today + 5 * hour)).isEmpty())
    }

    @Test
    fun `只赶上已充满也留一条，起点不编`() {
        // 夜里插枪、早上才打开 App：只看到 chargeState=2 和 100%
        val prev = anchor(
            startAt = at(2026, 11, 10, 7, 0),
            soc = null,
            peak = 100,
            endedAt = at(2026, 11, 10, 7, 0),
            full = true,
        )
        val list = ChargeHistory.archive(emptyList(), prev, anchor(at(2026, 11, 11, 22, 0)))
        assertEquals(1, list.size)
        assertNull(list[0].socFrom)
        assertEquals(100, list[0].socTo ?: -1)
        assertTrue(list[0].onlyPeak)
        // 结束时间退回「收尾时刻」：这时没有「最后看见充电」的样本可用
        assertEquals(at(2026, 11, 10, 7, 0), list[0].endAt)
    }

    @Test
    fun `缺结束时间时用下一次开始兜底`() {
        // 充完直接开走，中途一次都没再刷到车况
        val prev = anchor(startAt = today, soc = 40, peak = 70)
        val next = anchor(startAt = today + 6 * hour, soc = 45)
        val list = ChargeHistory.archive(emptyList(), prev, next)
        assertEquals(today + 6 * hour, list[0].endAt)
    }

    // ---------------- 合并（暂停 / 恢复） ----------------

    @Test
    fun `暂停后接着充合并成一条`() {
        val first = ChargeSession(
            startAt = today,
            endAt = today + 40 * min,
            socFrom = 40,
            socTo = 68,
            km = 12_000.0,
        )
        val second = ChargeSession(
            startAt = today + 50 * min,
            endAt = today + 2 * hour,
            socFrom = 68,
            socTo = 90,
            km = 12_000.0,
        )
        val list = ChargeHistory.mergeOrAppend(listOf(first), second)
        assertEquals(1, list.size)
        assertEquals(40, list[0].socFrom ?: -1)
        assertEquals(90, list[0].socTo ?: -1)
        assertEquals(today + 2 * hour, list[0].endAt)
    }

    @Test
    fun `隔了两个小时的两次不合并`() {
        val first = ChargeSession(today, today + 40 * min, 40, 68, km = 12_000.0)
        val second = ChargeSession(today + 3 * hour, today + 4 * hour, 68, 90, km = 12_000.0)
        assertEquals(2, ChargeHistory.mergeOrAppend(listOf(first), second).size)
    }

    @Test
    fun `换了车位不合并`() {
        val first = ChargeSession(today, today + 40 * min, 40, 68, km = 12_000.0)
        val second = ChargeSession(today + 45 * min, today + hour, 68, 90, km = 12_060.0)
        assertEquals(2, ChargeHistory.mergeOrAppend(listOf(first), second).size)
    }

    @Test
    fun `已经充满之后再插上不合并`() {
        val first = ChargeSession(today, today + 40 * min, 40, 100, full = true, km = 12_000.0)
        val second = ChargeSession(today + 45 * min, today + hour, 100, 100, km = 12_000.0)
        assertEquals(2, ChargeHistory.mergeOrAppend(listOf(first), second).size)
    }

    @Test
    fun `超过上限丢掉最老的`() {
        var list = emptyList<ChargeSession>()
        repeat(ChargeHistory.MAX_ENTRIES + 3) { i ->
            list = ChargeHistory.mergeOrAppend(
                list,
                ChargeSession(
                    startAt = today + i * 24 * hour,
                    endAt = today + i * 24 * hour + hour,
                    socFrom = 20,
                    socTo = 80,
                    km = 12_000.0 + i * 100,
                ),
            )
        }
        assertEquals(ChargeHistory.MAX_ENTRIES, list.size)
        // 最新一条在末尾，最老的被丢掉
        assertEquals(today + (ChargeHistory.MAX_ENTRIES + 2) * 24 * hour, list.last().startAt)
        assertEquals(today + 3 * 24 * hour, list.first().startAt)
    }

    // ---------------- 正在进行的那一次 ----------------

    @Test
    fun `充电中电量取峰值与当前读数里更大的`() {
        val live = ChargeHistory.liveSession(
            anchor(startAt = today, soc = 30, peak = 55, lastChargingAt = today + min),
            currentSoc = 62,
            charging = true,
        )!!
        assertEquals(30, live.socFrom ?: -1)
        assertEquals(62, live.socTo ?: -1)
        assertNull(live.endAt)
    }

    @Test
    fun `不在充电时按归档口径返回`() {
        val live = ChargeHistory.liveSession(
            anchor(startAt = today, soc = 30, peak = 80, lastChargingAt = today + 3 * hour),
            currentSoc = 80,
            charging = false,
        )!!
        assertEquals(today + 3 * hour, live.endAt)
    }

    @Test
    fun `没有起点就没有正在进行的记录`() {
        assertNull(ChargeHistory.liveSession(null, 50, true))
    }

    // ---------------- 落盘 ----------------

    @Test
    fun `充电历史能往返 JSON`() {
        val data = JournalData(
            chargeHistory = listOf(
                ChargeSession(at(2026, 11, 9, 23, 10), at(2026, 11, 10, 7, 42), 32, 88, km = 12_000.0),
                ChargeSession(at(2026, 11, 8, 21, 0), at(2026, 11, 8, 23, 0), null, 100, full = true),
            ),
        )
        val back = JournalData.fromJson(JSONObject(data.toJson().toString()))
        assertEquals(data.chargeHistory, back.chargeHistory)
    }

    // ---------------- 观测里补的两个时间 ----------------

    @Test
    fun `充电中的观测刷新最后充电时刻`() {
        val start = at(2026, 11, 9, 23, 0)
        val first = ChargeCycle.observe(
            null,
            ChargeSample(chargeState = 1, soc = 40, totalKm = 12_000.0),
            start,
        )!!
        assertEquals(start, first.anchor!!.lastChargingAt)
        val second = ChargeCycle.observe(
            first,
            ChargeSample(chargeState = 1, soc = 46, totalKm = 12_000.0),
            start + 20 * min,
        )!!
        assertEquals(start + 20 * min, second.anchor!!.lastChargingAt)
        // 起点不能被推着走
        assertEquals(start, second.anchor!!.at)
    }

    @Test
    fun `看到未充电时记下收尾时刻`() {
        val start = at(2026, 11, 9, 23, 0)
        val charging = ChargeCycle.observe(
            null,
            ChargeSample(chargeState = 1, soc = 40, totalKm = 12_000.0),
            start,
        )!!
        val done = ChargeCycle.observe(
            charging,
            ChargeSample(chargeState = 0, soc = 80, totalKm = 12_000.0),
            start + 4 * hour,
        )!!
        assertEquals(start + 4 * hour, done.anchor!!.endedAt)
        // 收尾那次观测本身不算「看见充电」，所以结束时间仍是最后那条充电样本
        assertEquals(start, done.anchor!!.lastChargingAt)
    }

    @Test
    fun `车端报已完成也记收尾时刻`() {
        val start = at(2026, 11, 9, 23, 0)
        val charging = ChargeCycle.observe(
            null,
            ChargeSample(chargeState = 1, soc = 40, totalKm = 12_000.0),
            start,
        )!!
        val done = ChargeCycle.observe(
            charging,
            ChargeSample(chargeState = 2, soc = 100, totalKm = 12_000.0),
            start + 5 * hour,
        )!!
        assertEquals(start + 5 * hour, done.anchor!!.endedAt)
        assertTrue(done.anchor!!.full)
        assertEquals(100, done.anchor!!.socPeak ?: -1)
        // 收尾了，但 lastChargingAt 仍是上一次看见充电的时刻
        assertEquals(start, done.anchor!!.lastChargingAt)
    }

    // ---------------- 文案 ----------------

    @Test
    fun `日期文案`() {
        assertEquals("今天", chargeHistoryDate(today, today))
        assertEquals("昨天", chargeHistoryDate(today - 24 * hour, today))
        assertEquals("3月5日", chargeHistoryDate(at(2026, 3, 5, 12), today))
        assertEquals("2025年12月31日", chargeHistoryDate(at(2025, 12, 31, 12), today))
    }

    @Test
    fun `区间文案`() {
        val sameDay = ChargeSession(at(2026, 11, 9, 9, 5), at(2026, 11, 9, 11, 30))
        assertEquals("09:05 → 11:30", chargeHistoryRange(sameDay))

        val overnight = ChargeSession(at(2026, 11, 9, 23, 10), at(2026, 11, 10, 7, 42))
        assertEquals("23:10 → 次日 07:42", chargeHistoryRange(overnight))

        val multiDay = ChargeSession(at(2026, 11, 9, 23, 10), at(2026, 11, 11, 7, 0))
        assertEquals("23:10 → 11月11日 07:00", chargeHistoryRange(multiDay))

        val live = ChargeSession(at(2026, 11, 9, 23, 10), null)
        assertEquals("23:10 开始", chargeHistoryRange(live))
    }

    @Test
    fun `时长文案`() {
        assertNull(chargeHistoryDuration(null))
        assertEquals("不足 1 分钟", chargeHistoryDuration(30_000L))
        assertEquals("45 分钟", chargeHistoryDuration(45 * min))
        assertEquals("2 小时", chargeHistoryDuration(2 * hour))
        assertEquals("8 小时 32 分", chargeHistoryDuration(8 * hour + 32 * min))
    }

    @Test
    fun `右侧电量文案`() {
        assertEquals("充到 88%", chargeHistorySocTo(ChargeSession(today, null, 32, 88)))
        // 只看到「充满」但没读到百分比：写满电，不拿 100% 冒认
        assertEquals("充到满电", chargeHistorySocTo(ChargeSession(today, null, null, null, full = true)))
        assertEquals("电量未知", chargeHistorySocTo(ChargeSession(today, null)))
    }
}
