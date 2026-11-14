package com.leapcontrol.app.trip

import org.json.JSONArray
import kotlin.math.roundToLong

/** 用户手工修正后路线上的一个点。[TripRecord.routeFix] 存的就是这一串。 */
data class TripFixPoint(val lat: Double, val lng: Double)

/**
 * 手工修正路线的序列化。
 *
 * ## 为什么单独一层，不塞进采样点
 * `TripRecord.points` 是车端上报的事实，永不改写；用户涂抹出来的线是「显示层的一层覆盖」。
 * 两者分开，才能随时「恢复原状」，也不会让手画的点混进里程 / 时长 / 速度的计算。
 *
 * 格式是 `[[lat,lng],[lat,lng],…]`（各 6 位小数，约 0.1 米精度）。写进 `trips.json`
 * 的 `fix` 字段后，备份导出（`TripOverview.toJson`）与导入（`fromJson`）自动带上，
 * 不需要额外改备份格式版本。
 */
object TripRouteFix {

    const val MAX_POINTS = 2000

    /** 存盘前统一收敛：过滤非法值、压到 6 位小数、限制点数（等距保首尾）。 */
    fun normalize(points: List<TripFixPoint>?): List<TripFixPoint>? {
        if (points == null) return null
        val clean = points.filter {
            it.lat.isFinite() && it.lng.isFinite() && it.lat in -85.0..85.0 && it.lng in -180.0..180.0
        }.map { TripFixPoint(round6(it.lat), round6(it.lng)) }
        if (clean.size < 2) return null
        if (clean.size <= MAX_POINTS) return clean
        val span = clean.size - 1
        val step = span.toDouble() / (MAX_POINTS - 1)
        return List(MAX_POINTS) { i -> clean[(i * step).toInt().coerceIn(0, span)] }
    }

    fun toJson(points: List<TripFixPoint>): JSONArray = JSONArray().apply {
        points.forEach { put(JSONArray().apply { put(it.lat); put(it.lng) }) }
    }

    fun fromJson(raw: JSONArray?): List<TripFixPoint>? {
        if (raw == null || raw.length() < 2) return null
        val out = ArrayList<TripFixPoint>(raw.length())
        for (i in 0 until raw.length()) {
            val pair = raw.optJSONArray(i) ?: continue
            if (pair.length() < 2) continue
            val lat = pair.optDouble(0, Double.NaN)
            val lng = pair.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lng.isFinite() || lat !in -85.0..85.0 || lng !in -180.0..180.0) {
                continue
            }
            out += TripFixPoint(lat, lng)
        }
        return normalize(out)
    }

    private fun round6(v: Double): Double = (v * 1_000_000.0).roundToLong() / 1_000_000.0
}
