package com.leapcontrol.app.map

import com.leapcontrol.app.AmapPoint
import com.leapcontrol.app.AmapWebApi

/**
 * 把用户「点选的几个途经点」变成**贴着道路**的线。
 *
 * ## 为什么只走驾车路径规划
 * 用户来修的正是「导航把路口判反」——明明左拐却画成往右绕。手画涂抹方案已按用户要求
 * 删除：画哪里都算、还得再纠偏吸附一次，不如直接让用户在正确的路上点几个钉子，
 * 由 [AmapWebApi.drivingRoute] 把点与点之间的道路补出来。途经点是用户亲手放的，
 * 方向被强制住，不会重演判反。
 *
 * ## 边界
 * 这里只处理「被替换的那一小段」，不碰整条路线、更不碰采样点 / 里程 / 时长 / 能耗。
 * 返回的第一个点和最后一个点会被 [TrackFix.compose] 强制换回原线上的接缝点，
 * 所以高德把首尾挪了几十米也不会跟两侧的原线裂开。
 *
 * 同步阻塞，调用方必须放在 IO 线程；没 Key / 没开通服务 / 没网一律给 [Result.reason]，
 * 由调用方决定降级（默认不落线，钉子留着让用户重试），**绝不让一张附加能力毁掉整张图**。
 */
object RouteSnap {

    /** 整段最多几个点 = 起点 + 16 个途经点 + 终点。 */
    const val MAX_PICKS = AmapWebApi.MAX_WAYPOINTS + 2

    /** 存盘 / 画线共用的点数上限，与 `TripRouteFix.MAX_POINTS` 对齐。 */
    const val MAX_POINTS = 2000

    /** 补齐的结果。points 为 null 表示没成，reason 是给用户看的一句话。 */
    data class Result(
        val points: List<TripTileMap.Point>? = null,
        val reason: String? = null,
    ) {
        val ok: Boolean get() = points != null && points.size >= 2
    }

    fun build(key: String, points: List<TripTileMap.Point>): Result {
        val valid = points.filter {
            it.lat.isFinite() && it.lng.isFinite() &&
                it.lat in -85.0..85.0 && it.lng in -180.0..180.0
        }
        if (valid.size < 2) return Result(reason = "这一段没有可用的点")
        if (key.isBlank()) {
            return Result(reason = "没配置高德 Web Key，没法按导航补齐（在设置里填）")
        }
        if (valid.size > MAX_PICKS) {
            return Result(reason = "途经点最多 ${AmapWebApi.MAX_WAYPOINTS} 个，删掉几个再试")
        }
        return runCatching { completePicks(key, valid) }
            .getOrElse { Result(reason = "导航补齐没成功，检查网络后再试") }
    }

    /**
     * 按高德途经点上限切段，逐段规划再拼起来。任何一段失败就整体退回，
     * 不画一半路线、也不重试 —— 半截路线比没有更误导人。
     */
    private fun completePicks(key: String, points: List<TripTileMap.Point>): Result {
        val out = ArrayList<TripTileMap.Point>(MAX_POINTS)
        for (range in segments(points.size, AmapWebApi.MAX_WAYPOINTS)) {
            val chunk = points.subList(range.first, range.last + 1)
            val segment = AmapWebApi.drivingRoute(
                key = key,
                origin = chunk.first().toAmap(),
                destination = chunk.last().toAmap(),
                waypoints = chunk.subList(1, chunk.size - 1).map { it.toAmap() },
            ) ?: return Result(reason = "导航补齐没成功（需要开通驾车路径规划），删掉几个途经点再试")
            val part = segment.map { TripTileMap.Point(it.latitude, it.longitude) }
            // 相邻段共享端点，拼接时丢掉重复的那个首点
            out += if (out.isEmpty()) part else part.drop(1)
        }
        return finish(out)
    }

    private fun finish(points: List<TripTileMap.Point>): Result {
        val clean = TrackFix.cap(TrackFix.simplify(points, 1.0), MAX_POINTS)
        if (clean.size < 2) return Result(reason = "没取到可用的道路点，删掉几个途经点再试")
        return Result(points = clean)
    }

    /**
     * 把 [size] 个点切成若干段，每段最多 `maxWaypoints + 2` 个点（首 + 途经 + 尾），
     * 相邻段**共享一个端点**避免拼起来断线。纯函数，可单测。
     *
     * 例：19 个点、上限 16 → `0..17`（16 个途经点）、`17..18`（0 个途经点）。
     */
    fun segments(size: Int, maxWaypoints: Int = AmapWebApi.MAX_WAYPOINTS): List<IntRange> {
        if (size < 2 || maxWaypoints < 0) return emptyList()
        val out = ArrayList<IntRange>()
        var index = 0
        while (index < size - 1) {
            val end = minOf(index + maxWaypoints + 1, size - 1)
            out += index..end
            index = end
        }
        return out
    }

    private fun TripTileMap.Point.toAmap(): AmapPoint = AmapPoint(lat, lng)
}
