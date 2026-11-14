package com.leapcontrol.app.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 车模行驶动效的参数（个性化 → 车模外观 → 行驶动效）。
 *
 * ## 一个总开关 + 三个可调项
 *
 * - 开不开（[enabled]，默认开）
 * - 抖多狠（[shakeDp]）
 * - 风往哪斜（[windAngle]，默认 −7°：风从左上往右下掠，比水平更有「迎面」感）
 * - 风多密（[windDensity]，1.0 是标准，直接乘每层的条数）
 *
 * 其余（线长、线宽、透明度、速度系数、颜色、Y 分布）全部写死在 [CarMotionFx] 里。
 *
 * ## 总开关是"真关"
 *
 * [enabled] 为假时 [animatesAt] 直接返回 false，[CarMotionFx] 那一层连
 * `withFrameNanos` 都不申请 —— 不是把幅度调到 0 那种"还在算、只是画不出来"，
 * 而是整条动画循环不存在。关掉之后首页车模就是一张静态图。
 *
 * ## 开关之外跟着行驶状态走
 *
 * 车速低于 [MIN_SPEED_KMH] 一帧都不画。**倒车只抖不吹风**，见 [windVisible]。
 */
data class CarMotionSettings(
    /** 行驶动效总开关。关掉后车模完全静止（不抖、不画风）。 */
    val enabled: Boolean = true,
    /** 车身抖动幅度（dp），默认 [DEFAULT_SHAKE_DP]。 */
    val shakeDp: Float = DEFAULT_SHAKE_DP,
    /**
     * 风线倾角（度）：0 水平，正值往下斜，负值往上掀。
     *
     * 车模朝左，所以风一律**从左往右**吹（车往左开，空气相对车往右走），
     * 这个角度只决定「风往上飘还是往下压」。
     */
    val windAngle: Float = DEFAULT_WIND_ANGLE,
    /**
     * 风的密度：乘在每层的基准条数上。
     *
     * 0.1 → 4 条（最稀，几乎只有几丝）、0.35 → 14 条（默认）、0.6 → 23 条、
     * 1.0 → 38 条、1.6 → 61 条。
     */
    val windDensity: Float = DEFAULT_WIND_DENSITY,
) {
    /** 夹紧到合法范围，并按 UI 精度取整（滑块给的是 0.1dp / 1° / 0.05 密度）。 */
    fun normalized(): CarMotionSettings = copy(
        shakeDp = (shakeDp * 10f).roundToInt() / 10f,
        windAngle = windAngle.roundToInt().toFloat(),
        windDensity = (windDensity * 20f).roundToInt() / 20f,
    ).let {
        it.copy(
            shakeDp = it.shakeDp.coerceIn(MIN_SHAKE_DP, MAX_SHAKE_DP),
            windAngle = it.windAngle.coerceIn(-MAX_WIND_ANGLE, MAX_WIND_ANGLE),
            windDensity = it.windDensity.coerceIn(MIN_WIND_DENSITY, MAX_WIND_DENSITY),
        )
    }

    /**
     * 这一帧该不该动。
     *
     * **总开关在这里生效**：关掉之后整车静止，连帧都不申请。
     * 速度读不到（null / NaN）也按静止处理：宁可不动，也不要拿一个猜出来的
     * 速度让首页莫名其妙地抖。
     */
    fun animatesAt(speedKmh: Float?): Boolean {
        if (!enabled) return false
        val s = speedKmh ?: return false
        return s.isFinite() && s >= MIN_SPEED_KMH
    }

    /**
     * 这一帧画不画风。
     *
     * 倒车时**只抖不吹风**：车模朝左画的是「往前开」，倒车时空气其实是从右往左走，
     * 再按同一套往右掠的线画出来，视觉上就是在往前冲。倒车速度低、画面本来就安静，
     * 干脆把风关掉，只留抖动 —— 抖动仍然说明「车在动」。
     */
    fun windVisible(speedKmh: Float?, reversing: Boolean): Boolean =
        !reversing && animatesAt(speedKmh)

    fun toJson(): JSONObject = JSONObject().apply {
        // 键名刻意用 `en` 而不是 `on`：上一版三层特效那份老 JSON 里 `on` 是布尔，
        // 虽然存在另一个 prefs 键下不会被读到，但同名迟早有人误连，换个名省得将来踩
        put("en", enabled)
        put("sh", shakeDp.toDouble())
        put("wa", windAngle.toDouble())
        put("wd", windDensity.toDouble())
    }

    companion object {
        /** 低于这个车速整层不画（蠕行、等红灯、刚挂挡还没走）。 */
        const val MIN_SPEED_KMH = 3f

        /** 抖动幅度上限。再高一停车一开车就像「车在跳」，不是「在跑」。 */
        const val MIN_SHAKE_DP = 0f
        const val MAX_SHAKE_DP = 5f

        /**
         * 默认 0.2dp（2026-11-08 用户要求再压一档）。
         *
         * 0.2dp 在 60 km/h 时纵向峰值约 ±0.2dp：一像素的三分之一，正常距离看几乎
         * 察觉不到位移，但车模边缘在动，眼睛能感到"活着"。想要明显一点就自己拉滑杆，
         * 上限仍是 5dp。
         */
        const val DEFAULT_SHAKE_DP = 0.2f

        /** 风向超过 ±45° 就变成斜着刮的雨了，不像迎面气流。 */
        const val MAX_WIND_ANGLE = 45f

        /** −7°：风略微往下压，比纯水平更像「顶着一股气流前进」。 */
        const val DEFAULT_WIND_ANGLE = -7f

        /**
         * 密度下限 10%（2026-11-08 用户要求「风的密度最低支持调到 10%」）。
         *
         * 10% 时三层合计 4 条（14×0.1→1、16×0.1→2、8×0.1→1），是"偶尔掠过一丝"的
         * 程度。**不能再往下调**：0 条就等于把风关掉了，而风有独立的视觉含义
         * （说明车在跑），要关风应该关总开关。
         */
        const val MIN_WIND_DENSITY = 0.1f
        const val MAX_WIND_DENSITY = 1.6f

        /**
         * 默认 35%（2026-11-08 用户：「风的密度默认 35%」）。
         *
         * 14 条：比原来 100%（38 条）稀一半多，是「看得出有风、但不糊住车」的密度。
         * 35 正好落在滑杆 5% 步进的刻度上，显示 35% 不会出现 34%/36% 这种怪值。
         */
        const val DEFAULT_WIND_DENSITY = 0.35f

        /** 更早两版的默认值，只用于老数据迁移（见 [CarMotionPrefs]）。 */
        internal const val LEGACY_DEFAULT_SHAKE_DP = 2.0f
        /** 上一版的默认抖动（v1.2.2 发出去的那个），同样要迁到新默认。 */
        internal const val PREVIOUS_DEFAULT_SHAKE_DP = 1.2f
        internal const val LEGACY_DEFAULT_WIND_ANGLE = 0f
        internal const val LEGACY_DEFAULT_WIND_DENSITY = 1.0f

        val DEFAULT = CarMotionSettings()

        fun fromJson(raw: JSONObject?): CarMotionSettings {
            if (raw == null) return DEFAULT
            return CarMotionSettings(
                enabled = raw.optBoolean("en", true),
                shakeDp = raw.optDouble("sh", DEFAULT_SHAKE_DP.toDouble()).toFloat(),
                windAngle = raw.optDouble("wa", DEFAULT_WIND_ANGLE.toDouble()).toFloat(),
                windDensity = raw.optDouble("wd", DEFAULT_WIND_DENSITY.toDouble()).toFloat(),
            ).normalized()
        }
    }
}

/**
 * 行驶动效的落盘。
 *
 * 和车辆命名共用 `leapcontrol_naming`，按 VIN 分键：抖动幅度跟车模图的大小有关
 * （同样的 dp 在窄屏上更明显），换车不该沿用上一辆的手感。
 *
 * 键名带 `_v2`：三层沙尘那套用的是 `car_motion_<vin>`，结构完全不同，直接复用会让
 * 老数据被当成新结构读出一堆默认值，不如另起一个键，老键留着不解析。
 */
object CarMotionPrefs {
    private const val PREFS = "leapcontrol_naming"

    private fun key(vin: String): String = "car_motion_v2_${vin.ifBlank { "default" }}"

    /**
     * 一次性迁移标记（按 VIN）。
     *
     * **改默认值对老设备一律无效**：用户装的是新版，但 prefs 里存着上一版写下的
     * `{"sh":1.2,...}`，读出来还是老手感，等于这次改动白做。所以这里比一次：
     * 值**恰好等于旧默认**的字段才换成新默认（用户自己调过的不动），比完打标记，
     * 只做一次。样板见 `KEY_BLE_ICON_MIGRATED`。
     *
     * 标记名带版本号，每次改默认值都要 +1 —— v3 那批设备早就被标过了，
     * 沿用同一个键会让 1.2dp 那一版用户读不到 0.2dp。
     */
    private fun migrateKey(vin: String): String = "car_motion_v4_${vin.ifBlank { "default" }}"

    fun load(context: Context, vin: String): CarMotionSettings {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateLegacyDefaults(sp, vin)

        val raw = sp.getString(key(vin), null) ?: return CarMotionSettings.DEFAULT
        return runCatching { CarMotionSettings.fromJson(JSONObject(raw)) }
            .getOrElse { CarMotionSettings.DEFAULT }
    }

    fun save(context: Context, vin: String, settings: CarMotionSettings) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key(vin), settings.normalized().toJson().toString())
                .apply()
        }
    }

    /**
     * 把「旧默认值」平移到「新默认值」，只做一次。
     *
     * 老版本没有 `wd` 字段，[CarMotionSettings.fromJson] 会自动补 1.0，不用在这里补。
     */
    private fun migrateLegacyDefaults(
        sp: SharedPreferences,
        vin: String,
    ) {
        val mark = migrateKey(vin)
        if (sp.getBoolean(mark, false)) return
        val raw = sp.getString(key(vin), null)
        if (raw != null) {
            val moved = migrateLegacyDefaultsJson(raw)
            if (moved != raw) {
                // 这里必须 commit：紧接着 load() 就要读它，apply 是异步的会读到旧值
                sp.edit().putString(key(vin), moved).commit()
            }
        }
        sp.edit().putBoolean(mark, true).apply()
    }
}

/**
 * 旧默认值 → 新默认值的一次性平移（纯函数，方便单测）。
 *
 * 只动**恰好等于旧默认值**的字段：
 * - `sh` = 2.0（三层特效那版）或 1.2（v1.2.2 那版）→ **0.2**（用户两次要求「抖动小一点」）
 * - `wa` = 0 → −7（风改默认倾角）
 * - `wd` = 1.0 → **0.35**（用户要求「风的密度默认 35%」）
 *
 * 两个旧抖动默认都要认：v3 标记只在 v1.2.2 那批设备上打过一次，跳过 v1.2.2 直接装新版的
 * 设备存的是 2.0，而 v1.2.2 用户存的是 1.2，少认一个就有一半人看不到这次改动。
 *
 * 用户自己调过的不许覆盖 —— 他调成 1.2 又调回 1.2，和「从来没调过」在这份数据里
 * 长得一样，这是这类迁移没法避免的取舍；宁可少迁一次，也不要悄悄改掉用户的设置。
 *
 * 解析失败 / 不是对象时原样返回，绝不抛异常（读设置的路径不能因为一份脏 JSON 崩掉）。
 */
internal fun migrateLegacyDefaultsJson(raw: String): String {
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
    var changed = false
    runCatching {
        val sh = o.optDouble("sh", Double.NaN)
        val nearOldDefault = abs(sh - CarMotionSettings.LEGACY_DEFAULT_SHAKE_DP) < 0.01 ||
            abs(sh - CarMotionSettings.PREVIOUS_DEFAULT_SHAKE_DP) < 0.01
        if (o.has("sh") && nearOldDefault) {
            o.put("sh", CarMotionSettings.DEFAULT_SHAKE_DP.toDouble())
            changed = true
        }
        if (o.has("wa") &&
            abs(o.optDouble("wa") - CarMotionSettings.LEGACY_DEFAULT_WIND_ANGLE) < 0.5
        ) {
            o.put("wa", CarMotionSettings.DEFAULT_WIND_ANGLE.toDouble())
            changed = true
        }
        if (o.has("wd") &&
            abs(o.optDouble("wd") - CarMotionSettings.LEGACY_DEFAULT_WIND_DENSITY) < 0.001
        ) {
            o.put("wd", CarMotionSettings.DEFAULT_WIND_DENSITY.toDouble())
            changed = true
        }
    }
    return if (changed) o.toString() else raw
}

/** 车速原始值 → km/h。车端信号 1319 可能是 "60"、"60.5"、"60km/h"、"60 km/h"。 */
private val MOTION_SPEED_NUMBER = Regex("""\d+(?:\.\d+)?""")

internal fun parseSpeedKmh(raw: String?): Float? {
    if (raw.isNullOrBlank()) return null
    val hit = MOTION_SPEED_NUMBER.find(raw) ?: return null
    return hit.value.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
}

/**
 * 速度 → 0..1 的强度系数（120 km/h 封顶）。
 *
 * 风线的速度、长度、亮度都乘它：60 km/h 时大约是满速的一半观感，
 * 高速上才该有「呼啸而过」的样子。
 */
internal fun motionFactor(speedKmh: Float): Float =
    (speedKmh / 120f).coerceIn(0f, 1f)

/**
 * 挡位原文（信号 1010）→ 是否倒车。
 *
 * 车端可能下发 "R" / "R挡" / "R 挡" / "REVERSE" / "倒车"。**必须逐个精确匹配**，
 * 不能用 `contains("R")`：`UNKNOWN` / `NONE` / `PARK` 里都有 R，一进来就判定倒车、
 * 风特效永远不出现（这是 2026-10-xx 在 [com.leapcontrol.app.trip.TripRules.isDriving]
 * 上已经踩过的坑，同样的写法不能在这里再犯一次）。
 */
internal fun String?.isReversing(): Boolean {
    val g = this?.trim()?.uppercase(java.util.Locale.ROOT)?.replace(" ", "") ?: return false
    return g == "R" || g == "R挡" || g == "REVERSE" || g == "倒车"
}
