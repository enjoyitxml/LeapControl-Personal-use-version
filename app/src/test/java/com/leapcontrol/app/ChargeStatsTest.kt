package com.leapcontrol.app

import com.leapcontrol.app.journal.ChargeCycle
import com.leapcontrol.app.journal.ChargeHistory
import com.leapcontrol.app.journal.ChargeSample
import com.leapcontrol.app.journal.ChargeSession
import com.leapcontrol.app.journal.ChargeRange
import com.leapcontrol.app.journal.ChargeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 充电历史的两个新口径（2026-11-20）：
 *  1. **表显续航**从多少涨到多少 —— 观测链路要一路透传，不能只存百分比；
 *  2. **近 7 天 / 本月 / 今年**各充了几次、充了多少度（按电池容量估算）。
 *
 * 最容易出错的不是算术，而是「把没有的数写成 0」：缺起点电量、缺电池容量、
 * 缺续航时都必须留 null，界面才会显示 `--` 而不是一个看起来真实的假数字。
 */
class ChargeStatsTest {

    private val hour = 60 * 60 * 1000L

    private fun at(year: Int, month: Int, day: Int, hourOfDay: Int = 12): Long =
        LocalDate.of(year, month, day)
            .atTime(hourOfDay, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val now = at(2026, 11, 20, 9)

    private fun session(
        startAt: Long,
        from: Int? = 30,
        to: Int? = 80,
        rangeFrom: Double? = null,
        rangeTo: Double? = null,
    ) = ChargeSession(
        startAt = startAt,
        endAt = startAt + hour,
        socFrom = from,
        socTo = to,
        rangeFromKm = rangeFrom,
        rangeToKm = rangeTo,
        // 合并判据要看里程（充电时车不动，同一个车位才算同一次）
        km = 12_000.0,
    )

    // ---------------- 换算 ----------------

    @Test
    fun `充了几个百分点与度数`() {
        assertEquals(50, session(now).socGain)
        assertEquals(30.0, ChargeHistory.kwhOf(session(now), 60.0)!!, 0.001)
        // 说不清区间时是 null，不是 0
        assertNull(session(now, from = null).socGain)
        assertNull(ChargeHistory.kwhOf(session(now, from = null), 60.0))
        // 没有容量算不出来，也不许拿 0 顶
        assertNull(ChargeHistory.kwhOf(session(now), null))
        // 容量离谱（0 或负数）同样不算
        assertNull(ChargeHistory.kwhOf(session(now), 0.0))
    }

    @Test
    fun `续航涨了多少要与电量一起记`() {
        val s = session(now, rangeFrom = 320.0, rangeTo = 410.0)
        assertEquals(90.0, s.rangeGainKm!!, 0.001)
        // 只有一头：不算涨幅
        assertNull(session(now, rangeFrom = 320.0).rangeGainKm)
        // 续航没涨（读数抖动 / 没读到）也不算
        assertNull(session(now, rangeFrom = 400.0, rangeTo = 400.0).rangeGainKm)
    }

    // ---------------- 观测链路透传 ----------------

    @Test
    fun `观测会把起点与充到的表显续航一起记下`() {
        val km = 12_000.0
        val first = ChargeCycle.observe(
            null,
            ChargeSample(chargeState = 1, soc = 30, totalKm = km, rangeKm = 320.0),
            now,
        )!!
        assertEquals(320.0, first.anchor!!.rangeKm!!, 0.001)

        // 同一场充电往上充：峰值续航跟着涨
        val mid = ChargeCycle.observe(
            first,
            ChargeSample(chargeState = 1, soc = 60, totalKm = km, rangeKm = 388.0),
            now + hour,
        )!!
        assertEquals(320.0, mid.anchor!!.rangeKm!!, 0.001)
        assertEquals(388.0, mid.anchor!!.rangePeakKm!!, 0.001)

        // 停充那一下定格：最后看见的续航就是「充到」的那一格
        val closed = ChargeCycle.observe(
            mid,
            ChargeSample(chargeState = 0, soc = 80, totalKm = km, rangeKm = 410.0),
            now + 3 * hour,
        )!!
        assertEquals(410.0, closed.anchor!!.rangePeakKm!!, 0.001)

        val archived = ChargeHistory.archive(emptyList(), closed.anchor, null)
        assertEquals(1, archived.size)
        assertEquals(320.0, archived[0].rangeFromKm!!, 0.001)
        assertEquals(410.0, archived[0].rangeToKm!!, 0.001)
    }

    @Test
    fun `手动修正的起点也带表显续航`() {
        val anchor = com.leapcontrol.app.journal.ChargeAnchor(
            km = 12_000.0,
            at = now,
            soc = 20,
            socPeak = 90,
            rangeKm = 90.0,
            rangePeakKm = 420.0,
            edited = true,
        )
        val s = ChargeHistory.sessionOf(anchor)!!
        assertEquals(90.0, s.rangeFromKm!!, 0.001)
        assertEquals(420.0, s.rangeToKm!!, 0.001)
    }

    @Test
    fun `续航与电量的 JSON 往返不丢`() {
        val raw = session(now, rangeFrom = 300.0, rangeTo = 405.0).toJson()
        val back = ChargeSession.fromJson(raw)!!
        assertEquals(300.0, back.rangeFromKm!!, 0.001)
        assertEquals(405.0, back.rangeToKm!!, 0.001)
    }

    @Test
    fun `被暂停切开的两段合并后续航取后一段`() {
        val first = session(now, from = 20, to = 60, rangeFrom = 80.0, rangeTo = 240.0)
            .copy(endAt = now + hour)
        val second = session(now + 2 * hour, from = 60, to = 90, rangeFrom = 240.0, rangeTo = 420.0)
        val merged = ChargeHistory.mergeOrAppend(listOf(first), second).last()
        assertEquals(90, merged.socTo)
        assertEquals(420.0, merged.rangeToKm!!, 0.001)
        // 起点不能跟着后一段走，否则「从 20% 充起」就丢了
        assertEquals(20, merged.socFrom)
        assertEquals(80.0, merged.rangeFromKm!!, 0.001)
    }

    // ---------------- 时段汇总 ----------------

    @Test
    fun `近七天含今天在内的七个自然日`() {
        val zone = ZoneId.systemDefault()
        // 11-14 00:00 起（今天 11-20 往前数 6 天）
        assertEquals(at(2026, 11, 14, 0), ChargeRange.Last7Days.startMillis(now, zone))
        // 右边界截到 now：未来时间戳的脏数据不该算进来
        assertEquals(now + 1, ChargeRange.Last7Days.endMillis(now, zone))
        assertEquals(at(2026, 11, 1, 0), ChargeRange.Month(2026, 11).startMillis(now, zone))
        assertEquals(at(2026, 1, 1, 0), ChargeRange.Year(2026).startMillis(now, zone))
    }

    @Test
    fun `历史月份的右边界是下月一号`() {
        val zone = ZoneId.systemDefault()
        // 9 月已经过完，窗口是 [10-01 之外的全月)
        assertEquals(at(2026, 10, 1, 0), ChargeRange.Month(2026, 9).endMillis(now, zone))
        // 当前月没结束，右边界只能截到 now
        assertEquals(now + 1, ChargeRange.Month(2026, 11).endMillis(now, zone))
        // 过完的年份同理取到次年一号；当年同样截到 now
        assertEquals(at(2026, 1, 1, 0), ChargeRange.Year(2025).endMillis(now, zone))
        assertEquals(now + 1, ChargeRange.Year(2026).endMillis(now, zone))
    }

    @Test
    fun `三档窗口各算各的次数与度数`() {
        val list = listOf(
            session(at(2026, 11, 20, 8)), // 今天，在近 7 天内
            session(at(2026, 11, 16, 8)), // 在近 7 天内
            session(at(2026, 11, 15, 8)), // 近 7 天内（窗口从 11-14 起算）
            session(at(2026, 11, 2, 8)), // 本月
            session(at(2026, 3, 8, 8)), // 今年
            session(at(2025, 12, 8, 8)), // 去年：一个窗口都不算
        )
        val d7 = ChargeStats.of(list, ChargeRange.Last7Days, now, 60.0)
        val month = ChargeStats.of(list, ChargeRange.Month(2026, 11), now, 60.0)
        val year = ChargeStats.of(list, ChargeRange.Year(2026), now, 60.0)

        // 11-14 起的 7 天：11-15 / 11-16 / 11-20 三条
        assertEquals(3, d7.sessions)
        assertEquals(90.0, d7.kwh!!, 0.001) // 3 条 × 50% × 60kWh

        assertEquals(4, month.sessions)
        assertEquals(120.0, month.kwh!!, 0.001)

        assertEquals(5, year.sessions)
        assertEquals(150.0, year.kwh!!, 0.001)
    }

    @Test
    fun `算不出度数的段不计入度数但计入次数`() {
        val list = listOf(
            session(at(2026, 11, 20, 8)),
            session(at(2026, 11, 19, 8), from = null), // 没看到起点电量
        )
        val stat = ChargeStats.of(list, ChargeRange.Month(2026, 11), now, 60.0)
        assertEquals(2, stat.sessions)
        assertEquals(1, stat.measurable)
        assertEquals(30.0, stat.kwh!!, 0.001)
    }

    @Test
    fun `没有电池容量时次数照给、度数为空`() {
        val stat = ChargeStats.of(listOf(session(now)), ChargeRange.Last7Days, now, null)
        assertEquals(1, stat.sessions)
        assertEquals(0, stat.measurable)
        assertNull(stat.kwh)
        assertTrue(!stat.empty)
    }

    @Test
    fun `窗口内一条都没有时不编数字`() {
        val stat = ChargeStats.of(emptyList(), ChargeRange.Year(2026), now, 60.0)
        assertTrue(stat.empty)
        assertEquals(0, stat.sessions)
        assertNull(stat.kwh)
        assertNull(stat.rangeGainKm)
    }

    @Test
    fun `正在充电的那一次也算进今天`() {
        // 界面把 live 会话一起传进来，就是为了「一边充一边看，数字不是 0」
        val live = session(now, from = 40, to = 45, rangeFrom = 200.0, rangeTo = 225.0)
            .copy(endAt = null)
        val stat = ChargeStats.of(listOf(live), ChargeRange.Last7Days, now, 60.0)
        assertEquals(1, stat.sessions)
        assertEquals(3.0, stat.kwh!!, 0.001)
        assertEquals(25.0, stat.rangeGainKm!!, 0.001)
    }

    @Test
    fun `未来时间戳的记录不计入`() {
        val future = session(now + 5 * hour)
        assertEquals(0, ChargeStats.of(listOf(future), ChargeRange.Last7Days, now, 60.0).sessions)
    }

    @Test
    fun `增程车没有续航读数时只少一格、不影响次数`() {
        val s = session(at(2026, 11, 20, 8), rangeFrom = null, rangeTo = null)
        val stat = ChargeStats.of(listOf(s), ChargeRange.Month(2026, 11), now, 60.0)
        assertEquals(1, stat.sessions)
        assertEquals(30.0, stat.kwh!!, 0.001)
        assertNull(stat.rangeGainKm)
    }

    // ---------------- 可选时段（2026-11-20 第二版） ----------------

    @Test
    fun `可以指定具体某个月份`() {
        val list = listOf(
            session(at(2026, 9, 3, 8)),
            session(at(2026, 9, 30, 23)), // 月末最后一小时仍算 9 月
            session(at(2026, 10, 1, 0)), // 10 月第一秒不算 9 月
            session(at(2026, 11, 20, 8)),
        )
        val sep = ChargeStats.of(list, ChargeRange.Month(2026, 9), now, 60.0)
        assertEquals(2, sep.sessions)
        assertEquals(60.0, sep.kwh!!, 0.001)
        assertEquals("2026年9月", sep.range.title)
        // 窗口是左闭右开，不能把 10 月第一秒算进来
        assertEquals(1, ChargeStats.of(list, ChargeRange.Month(2026, 10), now, 60.0).sessions)
    }

    @Test
    fun `可以指定具体某一年 跨年边界不串`() {
        val list = listOf(
            session(at(2024, 12, 31, 23)),
            session(at(2025, 1, 1, 0)),
            session(at(2025, 6, 1, 12)),
            session(at(2026, 1, 5, 12)),
        )
        assertEquals(2, ChargeStats.of(list, ChargeRange.Year(2025), now, 60.0).sessions)
        // 跨年夜那条属于 2024，别被算进 2025
        assertEquals(1, ChargeStats.of(list, ChargeRange.Year(2024), now, 60.0).sessions)
        assertEquals(1, ChargeStats.of(list, ChargeRange.Year(2026), now, 60.0).sessions)
    }

    @Test
    fun `月份列表倒序 当前月永远在第一`() {
        val list = listOf(
            session(at(2026, 9, 3, 8)),
            session(at(2026, 3, 8, 8)),
            session(at(2025, 12, 8, 8)),
        )
        assertEquals(
            listOf(
                YearMonth.of(2026, 11), // 当前月还没记录，也要排第一
                YearMonth.of(2026, 9),
                YearMonth.of(2026, 3),
                YearMonth.of(2025, 12),
            ),
            ChargeStats.monthsWith(list, now),
        )
        // 一条记录都没有时只剩当前月，下拉框不该是空的
        assertEquals(listOf(YearMonth.of(2026, 11)), ChargeStats.monthsWith(emptyList(), now))
    }

    @Test
    fun `年份列表倒序 当年永远在第一`() {
        val list = listOf(session(at(2024, 5, 1, 8)), session(at(2025, 8, 1, 8)))
        assertEquals(listOf(2026, 2025, 2024), ChargeStats.yearsWith(list, now))
        assertEquals(listOf(2026), ChargeStats.yearsWith(emptyList(), now))
    }

    @Test
    fun `年份列表不含未来年份`() {
        // 脏数据里出现 2027 的戳，不该出现在下拉里，也不该被算进任何窗口
        val list = listOf(session(at(2027, 1, 2, 8)))
        assertEquals(listOf(2026), ChargeStats.yearsWith(list, now))
    }

    @Test
    fun `分布图的桶与汇总同一个口径`() {
        val list = listOf(
            session(at(2026, 11, 18, 8)),
            session(at(2026, 11, 18, 20)),
            session(at(2026, 11, 20, 8)),
            session(at(2026, 11, 10, 8)), // 近 7 天窗口外
        )
        val bars = ChargeStats.bars(list, ChargeRange.Last7Days, now, 60.0)
        assertEquals(7, bars.items.size)
        assertEquals("kWh", bars.unit)
        // 柱高合计必须等于汇总的度数，否则上下两处数字对不上
        assertEquals(
            ChargeStats.of(list, ChargeRange.Last7Days, now, 60.0).kwh!!,
            bars.items.sumOf { it.value },
            0.001,
        )
        assertEquals(2, bars.items.first { it.label == "18" }.count)
        // 窗口外那条（11-10）一根柱都不该进
        assertEquals(3, bars.items.sumOf { it.count })
    }

    @Test
    fun `没有容量时柱高退回次数`() {
        val bars = ChargeStats.bars(listOf(session(at(2026, 11, 20, 8))), ChargeRange.Last7Days, now, null)
        assertEquals("次", bars.unit)
        assertEquals(1.0, bars.peak, 0.001)
    }

    @Test
    fun `按月按年各自的分桶数`() {
        assertEquals(30, ChargeStats.bars(emptyList(), ChargeRange.Month(2026, 9), now, 60.0).items.size)
        assertEquals(31, ChargeStats.bars(emptyList(), ChargeRange.Month(2026, 10), now, 60.0).items.size)
        assertEquals(12, ChargeStats.bars(emptyList(), ChargeRange.Year(2026), now, 60.0).items.size)
        assertTrue(ChargeStats.bars(emptyList(), ChargeRange.Year(2026), now, 60.0).empty)
    }
}

/**
 * 界面接线守门：数字算得再对，页面没接上也白搭。
 *
 * 这里只钉「该出现的那一行还在」——版式细节交给真机看，别在测试里复制布局。
 */
class ChargeStatsWiringTest {

    private val projectDir: java.io.File = generateSequence(
        java.io.File(requireNotNull(System.getProperty("user.dir"))),
    ) { it.parentFile }.first { java.io.File(it, "app").isDirectory }

    private fun read(path: String): String =
        java.io.File(projectDir, path).readText(charset = java.nio.charset.StandardCharsets.UTF_8)

    @Test
    fun `充电历史页要按月按年可选并显示每条记录的度数`() {
        val screen = read("app/src/main/java/com/leapcontrol/app/ui/ChargeHistoryScreen.kt")
        assertTrue(
            "月份 / 年份列表必须由数据算出来（只列有记录的 + 当前那个）",
            screen.contains("ChargeStats.monthsWith(rows, now)") &&
                screen.contains("ChargeStats.yearsWith(rows, now)"),
        )
        assertTrue(
            "汇总与分布图都要走 ChargeRange，别在页面里手写窗口判断",
            screen.contains("ChargeStats.of(rows, range, now, batteryKwh)") &&
                screen.contains("ChargeStats.bars(rows, range, now, batteryKwh)"),
        )
        assertTrue(
            "三档 Tab 要来自 ChargeTab，别在页面里另写一份列表",
            screen.contains("ChargeTab.entries.map { it.label }"),
        )
        assertTrue(
            "每条记录要显示充入度数（按容量估算）",
            screen.contains("chargeHistoryDetail(session, batteryKwh)"),
        )
        assertTrue(
            "没有电池容量时要给一条「去哪里填」的提示，否则度数永远是 --",
            screen.contains("电池容量"),
        )
    }

    @Test
    fun `首页调用处要把电池容量传进去`() {
        val home = read("app/src/main/java/com/leapcontrol/app/ui/HomeScreen.kt")
        assertTrue(
            "不传容量，充电历史永远算不出度数",
            home.contains("batteryKwh = runtime.batteryKwh()"),
        )
    }

    @Test
    fun `观测链路必须把表显续航带进起点`() {
        val cycle = read("app/src/main/java/com/leapcontrol/app/journal/ChargeCycle.kt")
        assertTrue(
            "ChargeSample 里要有续航，否则观测这一层就丢了",
            cycle.contains("val rangeKm: Double? = null"),
        )
        assertTrue(
            "充到的那一格（rangePeakKm）要和 socPeak 一起抬",
            cycle.contains("next = next.copy(rangePeakKm = range)"),
        )
        val runtime = read("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt")
        assertTrue(
            "车况里的表显续航要喂给充电观测，增程车跳过",
            runtime.contains("rangeKm = if (status.isRangeExtender) null"),
        )
    }
}
