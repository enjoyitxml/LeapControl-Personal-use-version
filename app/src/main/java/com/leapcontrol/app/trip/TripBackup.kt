package com.leapcontrol.app.trip

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 行程数据的备份文件：把本机**所有车辆**的行程打成一个 JSON，用来换手机 / 重装时搬数据。
 *
 * 与零记的 [com.leapcontrol.app.journal.JournalBackup] 同一套思路：
 *  - 文件走系统「保存到… / 打开文件」，不经过任何服务器；
 *  - 导入是**合并**，不覆盖本机已有记录（选错文件最多「没变化」，不会把数据冲掉）；
 *  - 文件头带 `kind` 签名，随便拿一个 JSON 进来能立刻被认出来。
 *
 * 文件长这样（[FORMAT] = 1）：
 * ```
 * { "kind": "leapcontrol-trip", "format": 1, "app": "零控", "appVersion": "2.0.4",
 *   "exportedAt": 1761..., "vehicles": [ { "vin": "L...", "data": { ...TripOverview.toJson()... } } ] }
 * ```
 *
 * ⚠️ 这个文件里有**完整的行驶轨迹与停车位置**，比零记的账本更敏感：
 * 文案里必须提醒用户别随手转发。
 */
object TripBackup {

    const val KIND = "leapcontrol-trip"

    /** 当前格式版本。导入放行 `format <= FORMAT`，以后加字段不必升版本。 */
    const val FORMAT = 1

    /** 一辆车的行程数据。 */
    data class Vehicle(val vin: String, val data: TripOverview)

    /** 一份备份文件的全部内容。 */
    data class Payload(
        val exportedAt: Long,
        val appVersion: String,
        val vehicles: List<Vehicle>,
    ) {
        val vehicleCount: Int get() = vehicles.size
        val tripCount: Int get() = vehicles.sumOf { it.data.trips.size }
        val km: Double get() = vehicles.sumOf { v -> v.data.trips.sumOf { it.distanceKm } }
    }

    /** 一次导入实际新增了多少（备份里本机已有的部分不算）。 */
    data class Summary(
        val vehicles: Int = 0,
        val newTrips: Int = 0,
    )

    /** 文件读不懂时抛它：`message` 是给用户看的原话。 */
    class BackupException(message: String) : Exception(message)

    // ---------------- 读写 ----------------

    fun encode(payload: Payload): String {
        val root = JSONObject()
        root.put("kind", KIND)
        root.put("format", FORMAT)
        root.put("app", "零控")
        root.put("appVersion", payload.appVersion)
        root.put("exportedAt", payload.exportedAt)
        root.put(
            "vehicles",
            JSONArray().apply {
                payload.vehicles.forEach { vehicle ->
                    put(
                        JSONObject().apply {
                            put("vin", vehicle.vin)
                            put("data", vehicle.data.toJson())
                        },
                    )
                }
            },
        )
        // 缩进两格：出问题时用户拿文本编辑器就能看懂
        return root.toString(2)
    }

    fun decode(text: String): Result<Payload> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(BackupException("这个文件是空的"))
        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            return Result.failure(BackupException("这不是零控的行程备份文件"))
        }
        if (root.optString("kind") != KIND) {
            return Result.failure(BackupException("这不是零控的行程备份文件"))
        }
        val format = root.optInt("format", 0)
        if (format <= 0 || format > FORMAT) {
            return Result.failure(
                BackupException("备份文件的版本（$format）比当前 App 新，先把零控更新到最新版"),
            )
        }
        val array = root.optJSONArray("vehicles")
        if (array == null || array.length() == 0) {
            return Result.failure(BackupException("备份里没有行程数据"))
        }
        val vehicles = ArrayList<Vehicle>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val vin = item.optString("vin").trim()
                .ifBlank { "default" }
                .take(64)
            val data = TripOverview.fromJson(vin, item.optJSONObject("data"))
            // 一段行程都没有的车不必带进来，否则「车辆数」会虚高
            if (data.trips.isEmpty() && data.open.isEmpty()) continue
            vehicles += Vehicle(vin, data)
        }
        if (vehicles.isEmpty()) return Result.failure(BackupException("备份里没有可用的行程数据"))
        return Result.success(
            Payload(
                exportedAt = root.opt("exportedAt")?.toString()?.toLongOrNull() ?: 0L,
                appVersion = root.optString("appVersion").trim(),
                vehicles = vehicles,
            ),
        )
    }

    // ---------------- 合并 ----------------

    /**
     * 把备份合并进本机数据，**不删任何本机记录**。
     *
     * 规则：
     *  - 已收尾的行程按 `id`（= 这段第一个采样点的时刻）去重，本机那份优先 ——
     *    本机可能后来又补过起终点中文地址；
     *  - 「正在进行的那一段」（open / 分段 / 暂停状态）只可能有一份是对的：
     *    哪边的最后一帧车况更晚就用哪边，**不能把两段拼一起**（会算出双倍里程）；
     *  - 电池容量这类设置项本机有就保留本机（可能是后来改过的）。
     */
    fun merge(local: TripOverview, incoming: TripOverview): TripOverview {
        val seen = local.trips.mapTo(HashSet()) { it.id }
        val trips = (local.trips + incoming.trips.filter { seen.add(it.id) })
            .sortedBy { it.startAt }

        val localLast = local.lastSample?.at ?: 0L
        val incomingLast = incoming.lastSample?.at ?: 0L
        val ongoing = if (incomingLast > localLast) incoming else local

        return local.copy(
            vin = local.vin.ifBlank { incoming.vin },
            trips = trips,
            batteryKwh = local.batteryKwh ?: incoming.batteryKwh,
            open = ongoing.open,
            pausedSince = ongoing.pausedSince,
            lastSample = ongoing.lastSample,
            manualPausedAt = ongoing.manualPausedAt,
            pausedTotalMs = ongoing.pausedTotalMs,
            segments = ongoing.segments,
            startCloudKwh = ongoing.startCloudKwh,
        )
    }

    // ---------------- 文案 ----------------

    /** 默认文件名：`零控行程-20261109-1930.json`。 */
    fun fileName(now: Long = System.currentTimeMillis()): String {
        val time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        return "零控行程-" + time.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US)) + ".json"
    }

    /** 导入确认框里的正文：先让用户看清这份备份是什么、有多少。 */
    fun describe(payload: Payload): String = buildString {
        append("导出时间：")
        append(timeLabel(payload.exportedAt))
        append('\n')
        append("车辆：").append(payload.vehicleCount).append(" 辆")
        append(" · 行程 ").append(payload.tripCount).append(" 段")
        if (payload.km > 0.0) append(" · ").append(String.format(Locale.CHINA, "%.1f", payload.km)).append(" km")
    }

    /** 导入结果：只报真正新增的部分。 */
    fun describe(summary: Summary): String {
        if (summary.newTrips <= 0) return "备份里的行程本机都已经有了，没有需要新增的内容"
        return "已导入 ${summary.newTrips} 段行程"
    }

    private fun timeLabel(millis: Long): String {
        if (millis <= 0L) return "未知"
        val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return time.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA))
    }
}
