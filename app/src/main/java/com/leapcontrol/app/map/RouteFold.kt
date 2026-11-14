package com.leapcontrol.app.map

import kotlin.math.cos
import kotlin.math.hypot

/**
 * 把「采样点连不出来、规划又自己补出来」的折返从**显示线**上剪掉。
 *
 * ## 为什么需要这一层（2026-11-13 用户反馈）
 *
 * 用户截了一张 1.0 km、6 分钟的行程图：橙色轨迹在路口绕了一个大圈又绕回来，
 * 而车根本没掉头。这类假折返有两个来源，都不是用户的错：
 *
 * 1. **采样点尖刺**：车端 GPS 偶尔给一个偏出去一两百米的点（高架下、地库口、刚唤醒的
 *    T-Box 都有可能）。45 秒一个点，前后两个点又都在原路上 —— 连起来就是「出去再回来」。
 * 2. **导航规划补出来的绕行**：`TripRoute` 拿最多 10 个采样点当途经点做驾车路径规划。
 *    两个途经点贴在分隔带两侧、或其中一个落在单行道逆行侧时，规划器为了「合法到达」
 *    会绕一个街区 —— 这条绕行是算法编的，采样点一秒都没在那儿。
 *
 * ## 口径（用户 2026-11-13 明确要求）
 *
 * **判断不出真的折返，就按没有折返画。** 所以这里只做减法：
 *  - 采样点层面：只删「出去又几乎回到原处」的尖刺，正常拐弯、正常掉头（沿途有点）都不动；
 *  - 路线层面：只有当整条线**比表显里程还长出一截**时，才去剪它自己绕回来的那个环。
 *    车真的绕了个街区（表显里程里含这一段）时预算够用，环会被留下。
 *
 * 只影响画出来的线：采样点、里程、时长、能耗一律不动（里程本来就取表显总里程差）。
 */
object RouteFold {

    /** 只用经纬度：不依赖 `AmapPoint` / `TripTileMap.Point`，纯 JVM 可测。 */
    data class Pt(val lat: Double, val lng: Double)

    /** 带时间戳的采样点：判尖刺时要按采样间隔放宽标准。 */
    data class Sample(val lat: Double, val lng: Double, val at: Long)

    /** 8 米以内当成同一个点：原地等灯时抖出来的重复点连起来就是一团线疙瘩。 */
    const val SAME_PLACE_M = 8.0

    /** 尖刺判据：回来点距出发点不足「更长那条腿」的这个比例，才算出去又回来。 */
    const val SPIKE_RETURN_RATIO = 0.8

    /**
     * 尖刺判据：这个点必须**甩在路的一侧**，离「前一点→后一点」这条直线至少这么远。
     *
     * 这一条是用来放过真掉头的：真的掉头回来，三个点在同一条路上，`B` 落在 `A→C` 的延长线上
     * （垂距 ≈ 0）；GPS 尖刺则是横向甩出去几百米。少了这一条，真掉头会被当成尖刺删掉。
     */
    const val SPIKE_MIN_PERP_M = 60.0

    /** 单程短于这个距离的折返不管：路口拐弯、进出辅路本来就是这个形状。 */
    const val SPIKE_MIN_LEG_M = 80.0

    /**
     * 允许「绕出去」的余量 = 采样间隔(秒) × 这个速度。
     *
     * 2.5 m/s ≈ 9 km/h 的额外游走：45 秒间隔只允许 112 米（夹到下限 120 米），
     * 5 分钟间隔放到 500 米封顶 —— 长间隔下绕路是正常的，不能拿 45 秒的尺子去量。
     */
    const val SPIKE_SPEED_MS = 2.5
    const val SPIKE_MIN_EXCESS_M = 120.0
    const val SPIKE_MAX_EXCESS_M = 500.0

    /** 线上两点相距不到这个距离 = 拐回原处，构成一个环。 */
    const val LOOP_NEAR_M = 35.0

    /** 短于这个长度的环不动：正常掉头、掉头专用道都是这个量级。 */
    const val MIN_LOOP_M = 150.0

    /** 读不到表显里程时只剪「一眼假」的大环：没有预算就从严，别把真路线剪了。 */
    const val HARD_LOOP_M = 400.0

    /** 一个环最长算到 3 公里：再长就不叫绕一圈了，那是另一条路。 */
    const val MAX_LOOP_M = 3000.0

    /** 一次剪几个环：正常行程里不会有第二个。 */
    const val MAX_PRUNE = 4

    /** 规划线允许的长度：表显里程 × 1.2 + 100 米。 */
    const val BUDGET_RATIO = 1.2
    const val BUDGET_EXTRA_M = 100.0

    // ---------------- 采样点：去尖刺 ----------------

    /**
     * 删掉「出去又回来」的尖刺点，其余原样保留（首末一定保留）。
     *
     * 判据用三点 A → B → C，四条同时成立才算尖刺：
     *  - `A` 到 `C` 的直线距离不到更远那条腿的 [SPIKE_RETURN_RATIO]，即 B 是「甩出去」的；
     *  - `B` 离 `A→C` 这条直线至少 [SPIKE_MIN_PERP_M] —— **真的掉头是共线的**，不会命中这一条；
     *  - 多走的路（`AB + BC - AC`）超过「采样间隔 × [SPIKE_SPEED_MS]」；
     *  - 两条腿都不短于 [SPIKE_MIN_LEG_M]（几十米的抖动就是路口拐弯）。
     *
     * 正常拐弯（L 形）里 `AC` 比任一腿都长，第一条就不成立；
     * 真的掉头（沿原路返回、沿途每 45 秒都有点）里 `AC ≈ AB + BC`，第三条不成立。纯函数。
     */
    fun cleanSamples(samples: List<Sample>): List<Sample> {
        if (samples.size < 3) return dedupe(samples)
        val drop = BooleanArray(samples.size)
        // 两轮：删掉一个尖刺后，原本隔着它的两个点可能又构成新的尖刺
        repeat(2) {
            for (i in 1 until samples.lastIndex) {
                if (drop[i - 1] || drop[i] || drop[i + 1]) continue
                val a = samples[i - 1]
                val b = samples[i]
                val c = samples[i + 1]
                val ab = distanceM(Pt(a.lat, a.lng), Pt(b.lat, b.lng))
                val bc = distanceM(Pt(b.lat, b.lng), Pt(c.lat, c.lng))
                val ac = distanceM(Pt(a.lat, a.lng), Pt(c.lat, c.lng))
                if (minOf(ab, bc) < SPIKE_MIN_LEG_M) continue
                if (ac > maxOf(ab, bc) * SPIKE_RETURN_RATIO) continue
                if (perpM(Pt(a.lat, a.lng), Pt(b.lat, b.lng), Pt(c.lat, c.lng)) < SPIKE_MIN_PERP_M) continue
                val dt = ((b.at - a.at).coerceAtLeast(0L) + (c.at - b.at).coerceAtLeast(0L)) / 1000.0
                val allowed = (SPIKE_SPEED_MS * dt)
                    .coerceIn(SPIKE_MIN_EXCESS_M, SPIKE_MAX_EXCESS_M)
                if (ab + bc - ac >= allowed) drop[i] = true
            }
        }
        return dedupe(samples.filterIndexed { i, _ -> !drop[i] })
    }

    /** B 到直线 A→C 的垂直距离（米）。平面近似，几十上百米的判断够用。 */
    fun perpM(a: Pt, b: Pt, c: Pt): Double {
        val k = cos(Math.toRadians(a.lat))
        val bx = (b.lng - a.lng) * 111_320.0 * k
        val by = (b.lat - a.lat) * 110_540.0
        val cx = (c.lng - a.lng) * 111_320.0 * k
        val cy = (c.lat - a.lat) * 110_540.0
        val len = hypot(cx, cy)
        // A、C 几乎是同一个点：没有方向可言，直接看 B 甩出去多远
        if (len < 1.0) return hypot(bx, by)
        return kotlin.math.abs(bx * cy - by * cx) / len
    }

    /** 8 米内的连续重复点只留一个；**首末不删**（端点要钉在车真的停过的地方）。 */
    private fun dedupe(samples: List<Sample>): List<Sample> {
        if (samples.size < 3) return samples
        val out = ArrayList<Sample>(samples.size)
        samples.forEachIndexed { i, s ->
            val prev = out.lastOrNull()
            val last = i == samples.lastIndex
            if (!last && prev != null &&
                distanceM(Pt(prev.lat, prev.lng), Pt(s.lat, s.lng)) < SAME_PLACE_M
            ) return@forEachIndexed
            out += s
        }
        return out
    }

    // ---------------- 规划线：剪自绕的环 ----------------

    /**
     * 剪掉「自己绕回原处」的环。
     *
     * @param budgetM 这条线最多允许多长（[budgetM] 算出来的表显里程预算）。
     *   给了预算就只在**超预算**时动手 —— 车真的绕了个街区，里程里含这一段，预算够、环就留着；
     *   传 null（读不到表显里程）时从严：只剪超过 [HARD_LOOP_M] 的大环。
     *
     * 纯函数，不联网。
     */
    fun pruneLoops(line: List<Pt>, budgetM: Double?): List<Pt> {
        if (line.size < 4) return line
        var work = line
        repeat(MAX_PRUNE) {
            if (budgetM != null && lengthM(work) <= budgetM) return work
            val loop = largestLoop(work) ?: return work
            val floor = if (budgetM != null) MIN_LOOP_M else HARD_LOOP_M
            if (loop.lengthM < floor) return work
            work = work.subList(0, loop.from + 1) + work.subList(loop.to, work.size)
        }
        return work
    }

    private class Loop(val from: Int, val to: Int, val lengthM: Double)

    /**
     * 找最长的那一个环：`line[i]` 与 `line[j]` 相距不到 [LOOP_NEAR_M]，
     * 而 i→j 之间走了 [MIN_LOOP_M] 以上（上限 [MAX_LOOP_M]）。
     *
     * 用一次投影后的平面坐标算距离：几百个顶点做全配对，不能每条边都跑三角函数。
     */
    private fun largestLoop(line: List<Pt>): Loop? {
        val px = DoubleArray(line.size)
        val py = DoubleArray(line.size)
        val k = cos(Math.toRadians(line[0].lat))
        line.forEachIndexed { i, p ->
            px[i] = p.lng * 111_320.0 * k
            py[i] = p.lat * 110_540.0
        }
        var best: Loop? = null
        for (i in 0 until line.size - 3) {
            var travelled = 0.0
            for (j in i + 1 until line.size) {
                travelled += hypot(px[j] - px[j - 1], py[j] - py[j - 1])
                if (travelled > MAX_LOOP_M) break
                if (j - i < 3) continue
                if (travelled <= (best?.lengthM ?: MIN_LOOP_M)) continue
                if (hypot(px[j] - px[i], py[j] - py[i]) > LOOP_NEAR_M) continue
                best = Loop(i, j, travelled)
            }
        }
        return best
    }

    // ---------------- 长度预算 ----------------

    /**
     * 表显总里程差 → 显示线的长度预算。读不到 / 不合理时返回 null（调用方退到从严口径）。
     *
     * 规划出来的路本来就该和车实际开的里程差不多，所以允许 20% 的富余；
     * 富余吃满还超出的部分，只可能是规划器自己绕出来的。
     */
    fun budgetM(startKm: Double?, endKm: Double?): Double? {
        if (startKm == null || endKm == null) return null
        if (!startKm.isFinite() || !endKm.isFinite()) return null
        val km = endKm - startKm
        // 差值为负是回绕 / 换表；超过 1000 km 明显是字段串了（和 TripRules 的口径一致）
        if (km <= 0.05 || km > 1000.0) return null
        return km * 1000.0 * BUDGET_RATIO + BUDGET_EXTRA_M
    }

    // ---------------- 几何 ----------------

    /** 两点大圆距离（米）。阈值都是几十上百米，公式精度远远够。 */
    fun distanceM(a: Pt, b: Pt): Double {
        val r = 6_371_008.8
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h.coerceAtMost(1.0)))
    }

    /** 折线总长（米）。 */
    fun lengthM(line: List<Pt>): Double {
        var sum = 0.0
        for (i in 1 until line.size) sum += distanceM(line[i - 1], line[i])
        return sum
    }
}
