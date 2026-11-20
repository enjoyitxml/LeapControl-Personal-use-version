package com.leapcontrol.app.trip

import kotlin.math.min

/**
 * 把一段行程的采样点读成「形状」：一条速度曲线 + 一句人话摘要。
 *
 * ### 为什么需要它
 * 旧版行程条只画「采样点分布」：一条灰线、两端圆点、最多 6 个刻度。一趟 33 分钟、
 * 13.8 km 的行程和一趟 3 公里的行程长得一模一样 —— 用户 2026-11-20 的原话是
 * 「不管我跑还是不跑都是这样显示的，没有正确的反馈」。
 *
 * 采样数据其实很全：每个点都带时间、车速、是否行驶。曲线和摘要只做两件事，
 * 不引入任何新接口：
 *  - **曲线**：横轴是时间（0 到 1）、纵轴是速度，走得快就拱起来、堵着就贴底，
 *    一眼能看出这趟是市区走走停停还是高速巡航；
 *  - **摘要**：把「行驶多久 / 停了几次 / 最长停多久 / 最高速度」压成一行，
 *    这几项指标卡上原来一格都没有。
 *
 * 纯函数、不碰 SharedPreferences，全部可单测。
 */
object TripShape {

    /**
     * 相邻两点超过这个间隔就当**断档**，不参与计时。
     *
     * 采样档最大是 5 分钟（[TripSampling.INTERVALS_SEC]），留一倍余量到 10 分钟；
     * 断档的来源是手动暂停、后台被杀、或者用户中途改了采样档 —— 那段时间没有观测，
     * 既不能算行驶也不能算停车，硬算会把「我停了两小时」写进行程。
     */
    const val MAX_GAP_MS = 10 * 60_000L

    /**
     * 一段停住至少要有这么久才算「停了一次」。
     *
     * 采样间隔最小 10 秒、默认 45 秒，单帧掉到 0 再起来是红绿灯起步前的常态抖动；
     * 用它当阈值正好等于「至少连续两帧都在停」，不会把每个红灯都数成一次停留。
     */
    const val STOP_MIN_MS = 45_000L

    /**
     * 曲线纵轴的下限刻度（km/h）。
     *
     * 纵轴如果严格按本趟最高速度归一化，20 km/h 的拥堵路段也会被拉满、看着像高速。
     * 给一个 30 的地板之后，低速行程的曲线自然压在下方，「这趟确实跑不快」看得出来。
     */
    const val MIN_AXIS_KMH = 30.0

    /** 少于这么多有效速度点就不画曲线：两个点只是一条斜线，不如退回采样条。 */
    const val MIN_CURVE_POINTS = 3

    /** 曲线最多分这么多段：再密就是逐点连线，240 个点的抖动画出来像噪声。 */
    const val MAX_CURVE_POINTS = 48

    /** 有效行程时长下限：更短的一趟不足以支撑「停了几次」这种统计。 */
    private const val MIN_SUMMARY_MS = 30_000L

    /**
     * 一段行程的节奏统计。
     *
     * [movingMs] + [stoppedMs] 是**有效观测时长**，可能小于行程总时长（断档被排除）。
     * 界面只拿它做说明，不要拿它当「时长」那一格 —— 那一格用的是行程自己算的时长。
     */
    data class Rhythm(
        val movingMs: Long,
        val stoppedMs: Long,
        val stopCount: Int,
        val longestStopMs: Long,
        /** 本趟最高车速；速度全没读到、或全程为 0 时是 null。 */
        val topKmh: Double?,
    ) {
        val spanMs: Long get() = movingMs + stoppedMs
        val hasStops: Boolean get() = stopCount > 0
    }

    /** 曲线上的一个点：`at` 是时间位置（0..1），`kmh` 是那一刻的车速。 */
    data class CurvePoint(val at: Float, val kmh: Float)

    /**
     * 画曲线要用的全部数据。
     *
     * [topKmh] 取的是**平滑之前**的真实峰值 —— 平滑只影响线条观感，不该把
     * 「最高 78」削成 74；[axisKmh] 是纵轴刻度，等于 `max(topKmh, MIN_AXIS_KMH)`。
     */
    data class Curve(
        val points: List<CurvePoint>,
        val topKmh: Double,
        val axisKmh: Double,
    ) {
        /** 某点的高度比例（0 = 贴底，1 = 顶格）。 */
        fun ratio(point: CurvePoint): Float =
            (point.kmh / axisKmh).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 统计这趟的行驶 / 停留节奏；数据不够判断时返回 null（界面就不出摘要行）。
     *
     * 判据只看两样：`driving` 标记，或者车速 ≥ 1 km/h。车端在停车时 `driving` 会是
     * false、车速是 0，两者同时成立；只要有一项能证明在动就算行驶。
     */
    fun rhythm(points: List<TripSample>): Rhythm? {
        if (points.size < 2) return null
        // 既没有 driving 标记、也没有任何车速：这趟的采样全是空壳，统计出来会是
        // 「全程未行驶」这种假结论，不如不出。
        if (points.none { it.driving || it.speedKmh != null }) return null

        var movingMs = 0L
        var runMs = 0L
        val runs = ArrayList<Long>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dt = b.at - a.at
            if (dt <= 0L || dt > MAX_GAP_MS) continue
            // 用**前一个点**的状态代表这两帧之间的那段：状态是那一刻观测到的事实，
            // 拿后一个点代表会让「刚停下」的那段时间被算成静止、把停留时长整体拉长一格。
            if (isMoving(a)) {
                movingMs += dt
                if (runMs > 0L) {
                    runs += runMs
                    runMs = 0L
                }
            } else {
                runMs += dt
            }
        }
        if (runMs > 0L) runs += runMs

        val stops = runs.filter { it >= STOP_MIN_MS }
        val stoppedMs = stops.sum()
        // 短于阈值的零散停车当成「还在路上」并回行驶，否则 moving + stopped 会小于
        // 实际观测时长、摘要里的两个数对不上。
        movingMs += runs.sum() - stoppedMs

        val top = points.mapNotNull { it.speedKmh }
            .filter { it.isFinite() && it >= 0.0 }
            .maxOrNull()
            ?.takeIf { it > 0.0 }

        return Rhythm(
            movingMs = movingMs,
            stoppedMs = stoppedMs,
            stopCount = stops.size,
            longestStopMs = stops.maxOrNull() ?: 0L,
            topKmh = top,
        )
    }

    /**
     * 摘要那句话，形如 `行驶 21 分 · 停 6 次共 12 分 · 最高 78`。
     *
     * 放在指标行下方当补充，所以**不重复**那三格已有的时长 / 均速 / 耗电；
     * 只讲「堵不堵、跑多快」。数据不足（太短、没有速度）时返回 null。
     */
    fun summaryText(rhythm: Rhythm): String? {
        if (rhythm.spanMs < MIN_SUMMARY_MS) return null
        // 全程没动：这时候「行驶 0 分 · 停 1 次」是句废话，直接说明没动
        if (rhythm.movingMs <= 0L) {
            return "全程没动 · 停留 ${span(rhythm.stoppedMs)}"
        }
        val parts = ArrayList<String>(3)
        parts += "行驶 ${span(rhythm.movingMs)}"
        if (rhythm.hasStops) {
            parts += "停 ${rhythm.stopCount} 次共 ${span(rhythm.stoppedMs)}"
        } else {
            parts += "中途没停"
        }
        rhythm.topKmh?.let { parts += "最高 ${TripFormat.speed(it)}" }
        return parts.joinToString(" · ")
    }

    /**
     * 把采样点变成一条速度曲线；点太少或者时间跨度为零时返回 null。
     *
     * 步骤：挑出有速度的点 → 按时间等分桶取均值（最多 [maxPoints] 段）→ 三点滑动平均。
     * 桶内的均值是为了压掉单帧抖动，滑动平均是让线条不像锯齿 —— 两者都不改变
     * 「哪一段快、哪一段慢」这个形状。
     */
    fun curve(points: List<TripSample>, maxPoints: Int = MAX_CURVE_POINTS): Curve? {
        val valid = points.filter { (it.speedKmh ?: -1.0).let { v -> v.isFinite() && v >= 0.0 } }
        if (valid.size < MIN_CURVE_POINTS) return null
        val first = valid.first().at
        val span = valid.last().at - first
        if (span <= 0L) return null

        val buckets = min(valid.size, maxPoints.coerceAtLeast(2))
        val sums = DoubleArray(buckets)
        val counts = IntArray(buckets)
        val timeSums = LongArray(buckets)
        for (p in valid) {
            val f = ((p.at - first).toDouble() / span).coerceIn(0.0, 0.999999)
            val idx = (f * buckets).toInt().coerceIn(0, buckets - 1)
            sums[idx] += p.speedKmh ?: 0.0
            timeSums[idx] += p.at - first
            counts[idx]++
        }
        val raw = (0 until buckets).mapNotNull { i ->
            if (counts[i] == 0) return@mapNotNull null
            CurvePoint(
                at = (timeSums[i].toDouble() / counts[i] / span).toFloat().coerceIn(0f, 1f),
                kmh = (sums[i] / counts[i]).toFloat().coerceAtLeast(0f),
            )
        }
        if (raw.size < MIN_CURVE_POINTS) return null

        // 三点滑动平均：只在点足够多时做，三四个点时平滑会把形状抹平
        val shaped = if (raw.size >= 5) {
            raw.mapIndexed { i, p ->
                val lo = (i - 1).coerceAtLeast(0)
                val hi = (i + 1).coerceAtMost(raw.size - 1)
                var sum = 0f
                for (j in lo..hi) sum += raw[j].kmh
                CurvePoint(p.at, sum / (hi - lo + 1))
            }
        } else {
            raw
        }

        val top = valid.mapNotNull { it.speedKmh }.maxOrNull() ?: return null
        if (top <= 0.0) return null
        return Curve(
            points = shaped,
            topKmh = top,
            axisKmh = maxOf(top, MIN_AXIS_KMH),
        )
    }

    private fun isMoving(sample: TripSample): Boolean =
        sample.driving || (sample.speedKmh ?: 0.0) >= 1.0

    /** 「12 分」/「1 小时 5 分」/「不到 1 分」。摘要里空间紧，不写秒。 */
    private fun span(ms: Long): String {
        val minutes = ms / 60_000L
        if (minutes < 1L) return "不到 1 分"
        if (minutes < 60L) return "$minutes 分"
        val hours = minutes / 60L
        val rest = minutes % 60L
        return if (rest == 0L) "$hours 小时" else "$hours 小时 $rest 分"
    }
}
