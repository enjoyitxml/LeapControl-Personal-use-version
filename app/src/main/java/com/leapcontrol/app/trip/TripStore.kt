package com.leapcontrol.app.trip

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 行程数据的落盘：一个 `filesDir/trip/trips.json`，按 VIN 分车。
 *
 * 只用 App 私有目录，不上传、不进日志。写入走「新文件 → 旧文件改名备份 → 换名」，
 * 任何一步失败都保留上一份完整数据（和零记 journal.json 同一套思路）。
 */
object TripStore {
    private const val DIR = "trip"
    private const val NAME = "trips.json"
    private const val SCHEMA = 1

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR)

    private fun file(context: Context): File = File(dir(context), NAME)

    private fun readRoot(context: Context): JSONObject {
        val target = file(context)
        if (!target.exists()) return JSONObject()
        return runCatching { JSONObject(target.readText()) }.getOrDefault(JSONObject())
    }

    @Synchronized
    fun load(context: Context, vin: String): TripOverview {
        if (vin.isBlank()) return TripOverview()
        val cars = readRoot(context).optJSONObject("cars") ?: return TripOverview(vin)
        return TripOverview.fromJson(vin, cars.optJSONObject(vin))
    }

    /**
     * 读出**本机所有车辆**的行程（备份导出用）。
     *
     * 空壳不返回：`lastSample` 之类的残留会让备份里的「车辆数」虚高，
     * 用户看到「3 辆车」却只有两辆有行程会以为出了错。
     */
    @Synchronized
    fun loadAll(context: Context): Map<String, TripOverview> {
        val cars = readRoot(context).optJSONObject("cars") ?: return emptyMap()
        val out = LinkedHashMap<String, TripOverview>()
        val keys = cars.keys()
        while (keys.hasNext()) {
            val vin = keys.next()
            if (vin.isBlank()) continue
            val data = TripOverview.fromJson(vin, cars.optJSONObject(vin))
            if (data.trips.isEmpty() && data.open.isEmpty()) continue
            out[vin] = data
        }
        return out
    }

    @Synchronized
    fun save(context: Context, state: TripOverview) {
        if (state.vin.isBlank()) return
        val folder = dir(context)
        if (!folder.exists() && !folder.mkdirs()) return
        val root = readRoot(context)
        val cars = root.optJSONObject("cars") ?: JSONObject().also { root.put("cars", it) }
        cars.put(state.vin, state.toJson())
        root.put("schema", SCHEMA)

        val target = file(context)
        val tmp = File(folder, "$NAME.new")
        val backup = File(folder, "$NAME.bak")
        try {
            tmp.writeText(root.toString())
            if (target.exists() && !target.renameTo(backup)) {
                tmp.delete()
                return
            }
            if (tmp.renameTo(target)) {
                backup.delete()
            } else {
                backup.renameTo(target)
                tmp.delete()
            }
        } catch (_: Exception) {
            tmp.delete()
        }
    }

    /**
     * 把本机的行程文件整份删掉。
     *
     * ⚠️ **退出登录不走这里**（2026-11-09 用户反馈「每次退出登录数据就丢」）：
     * 登出只从内存里放下这辆车，盘上的数据要留着 —— 重新登录同一辆车能接着看。
     * 这个入口只留给「用户明确要求抹掉本机行程」的场景。
     */
    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
        runCatching { File(dir(context), "$NAME.bak").delete() }
        runCatching { File(dir(context), "$NAME.new").delete() }
    }
}
