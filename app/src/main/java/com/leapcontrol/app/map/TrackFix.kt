package com.leapcontrol.app.map

import kotlin.math.cos
import kotlin.math.hypot

/**
 * 「选点修正轨迹」的纯逻辑：把用户点的几个途经点，替换掉原路线里对应的那一段。
 *
 * ## 为什么是「替换一段」而不是「整条重画」
 * 用户反馈的是「我在路口明明左拐了，路线却判定我往右走」——错的往往只有百来米。
 * 让他从起点一路重画到终点既不现实，也会把本来对的路线一起毁掉。所以规则是：
 * 在正确的路上点几个途经点 → 系统把首尾钉子对应的那段原始路线换成按导航补齐的线，
 * 其余一段不动。手画涂抹方案已按用户要求删除。
 *
 * ## 口径边界（必须守住）
 * 这里改的只是「地图上画出来的那条线」。**采样点、里程、时长、能耗一个都不动**：
 * 里程来自表显总里程差，本来就不是按轨迹长度算的；改线更不该反过来影响统计。
 * 原始采样点始终完整留在 `TripRecord.points` 里，修正是附加的一层，随时能撤。
 *
 * 纯 Kotlin，不碰 Android / 网络 / Bitmap，全部逻辑可以 JVM 单测。
 */
object TrackFix {

    /** 一条路线最多留这么多点：不设上限会把 trips.json 撑爆。 */
    const val MAX_POINTS = 2000

    /** 一段修正至少要走这么远（米），否则算误触。 */
    const val MIN_STROKE_METERS = 20.0

    /** 一笔的结果：成功给新线，失败给用户看得懂的一句话。 */
    data class Result(val line: List<TripTileMap.Point>?, val reason: String? = null) {
        val ok: Boolean get() = line != null
    }

    /**
     * 一次修正的「待替换区间」：`[startIndex, endIndex]` 是原线上的下标，
     * [middle] 是准备填进去的那一段（首尾已经强制换成原线上的接缝点）。
     *
     * 单独留这一层是为了让导航补齐**只作用于中间段**：用户来修的是「路口判反」，
     * 要是把整条线重新送去规划，本来对的那些段又会被改回去。
     */
    data class Plan(
        val startIndex: Int,
        val endIndex: Int,
        val middle: List<TripTileMap.Point>,
    )

    /** [planFromPicks] 的结果：失败时给一句用户看得懂的话。 */
    data class PlanResult(val plan: Plan? = null, val reason: String? = null) {
        val ok: Boolean get() = plan != null
    }

    /**
     * 用户在正确的路上点了几个途经点，算出要替换哪一段。
     *
     * **用户点的每一个钉子都要留着当途经点**，不能把首尾两个点丢掉换成接缝点。
     * 所以这里在钉子序列的前后各补一个 `line[i]` / `line[j]`，让导航从原线出发、
     * 穿过用户指定的那些点、再回到原线。同样支持倒着点（先点靠近终点的路口、
     * 再点靠近起点的路口），会自动反转。
     */
    fun planFromPicks(
        line: List<TripTileMap.Point>,
        picks: List<TripTileMap.Point>,
    ): PlanResult {
        if (line.size < 2) return PlanResult(reason = "这一段没有可修正的路线")
        val valid = picks.filter { it.lat.isFinite() && it.lng.isFinite() && it.lat in -85.0..85.0 }
        if (valid.size < 2) return PlanResult(reason = "先在正确的路上点至少 2 个途经点")

        var i = nearestIndex(line, valid.first())
        var j = nearestIndex(line, valid.last())
        if (i == j) return PlanResult(reason = "两个途经点挨得太近了，沿正确的路分开点")
        var middle = valid
        if (j < i) {
            val t = i
            i = j
            j = t
            middle = valid.reversed()
        }
        val anchored = buildList {
            add(line[i])
            addAll(middle)
            add(line[j])
        }
        if (lengthMeters(anchored) < MIN_STROKE_METERS) {
            return PlanResult(reason = "途经点挨得太近了，沿正确的路分开点")
        }
        return PlanResult(Plan(i, j, anchored))
    }

    /**
     * 把（可能已经被导航补齐过的）[middle] 填进原线。
     *
     * 首尾**再次强制换回** `line[i]` / `line[j]`：路径规划会把端点挪几十米，
     * 不换回来接缝就会裂开一道口子。其余段原样保留，所以连修几段不会互相破坏。
     */
    fun compose(
        line: List<TripTileMap.Point>,
        plan: Plan,
        middle: List<TripTileMap.Point> = plan.middle,
    ): Result {
        val mid = if (middle.size >= 2) {
            withEndpoints(middle, line[plan.startIndex], line[plan.endIndex])
        } else plan.middle
        val out = ArrayList<TripTileMap.Point>(line.size + mid.size)
        out.addAll(line.subList(0, plan.startIndex))
        out.addAll(mid)
        if (plan.endIndex + 1 < line.size) {
            out.addAll(line.subList(plan.endIndex + 1, line.size))
        }
        val cleaned = cap(simplify(out, 1.0), MAX_POINTS)
        if (cleaned.size < 2) return Result(null, "这一段没修出可用的路线，再试一次")
        return Result(cleaned)
    }

    /** 把一段的首尾钉死在接缝点上，中间原样保留。 */
    private fun withEndpoints(
        middle: List<TripTileMap.Point>,
        start: TripTileMap.Point,
        end: TripTileMap.Point,
    ): List<TripTileMap.Point> {
        val out = ArrayList<TripTileMap.Point>(middle.size.coerceAtLeast(2))
        out.add(start)
        if (middle.size > 2) out.addAll(middle.subList(1, middle.size - 1))
        out.add(end)
        return out
    }

    /**
     * 等距抽稀：只保留相距至少 [minMeters] 的点，**首尾一定保住**。
     *
     * 首尾很关键：首点是起点锚、尾点是终点锚，丢掉任一个都会让修正线
     * 和原始线之间裂开一道口子。
     */
    fun simplify(points: List<TripTileMap.Point>, minMeters: Double): List<TripTileMap.Point> {
        val valid = points.filter { it.lat.isFinite() && it.lng.isFinite() && it.lat in -85.0..85.0 }
        if (valid.size <= 2) return valid
        val out = ArrayList<TripTileMap.Point>(valid.size)
        for (p in valid) {
            val last = out.lastOrNull()
            if (last == null || meters(last, p) >= minMeters) out += p
        }
        val tail = valid.last()
        if (out.lastOrNull() != tail) out += tail
        return out
    }

    /** 超过 [limit] 就等距取样，仍然保住首尾（跟 `TripModels.trimOpen` 同一套思路，别用 takeLast）。 */
    fun cap(points: List<TripTileMap.Point>, limit: Int): List<TripTileMap.Point> {
        if (points.size <= limit || limit < 2) return points
        val span = points.size - 1
        val step = span.toDouble() / (limit - 1)
        return List(limit) { k -> points[(k * step).toInt().coerceIn(0, span)] }
    }

    /** 折线总长（米）。 */
    fun lengthMeters(points: List<TripTileMap.Point>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += meters(points[i - 1], points[i])
        return sum
    }

    private fun nearestIndex(line: List<TripTileMap.Point>, point: TripTileMap.Point): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in line.indices) {
            val d = meters(line[i], point)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** 小范围内等距近似就够用（只是比距离 / 找最近点），不必上大圆公式。 */
    private fun meters(a: TripTileMap.Point, b: TripTileMap.Point): Double {
        val dLat = (b.lat - a.lat) * 111_320.0
        val meanLat = Math.toRadians((a.lat + b.lat) / 2.0)
        val dLng = (b.lng - a.lng) * 111_320.0 * cos(meanLat)
        return hypot(dLat, dLng)
    }
}
