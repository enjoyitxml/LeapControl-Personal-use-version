package com.leapcontrol.app

import java.util.Locale

/**
 * 车型代号归一。
 *
 * 车辆列表接口下发的 `carType` 常常是**项目代号**而不是宣传名 —— 零跑 Lafa5
 * 下发的是 `B05`。这个代号会一路流到界面（「我的」页车型行、车辆下拉、能耗卡）、
 * 车漆色目录、以及车型配置确认，每处各写一份映射必然漏掉几处（能耗卡那处
 * 就漏过一回，车主看到的是「超越全国 50% 的 B05 车主」）。
 *
 * 所以统一从这里出去：**内部一律用 [canonical] 归一后的键，展示一律用 [displayName]**。
 * 新增车型只改 [ALIASES] 一行。
 */
object VehicleModels {

    /** 内部代号 → 官方宣传名（键必须大写，值用官方写法）。 */
    private val ALIASES = mapOf(
        "B05" to "Lafa5", // 零跑 Lafa5：车辆接口下发的是项目代号 B05
        "LAFA5" to "Lafa5", // 已经是宣传名（大小写可能不同）时统一成官方写法
    )

    /**
     * 归一后的车型名：去掉「零跑」前缀、查别名表；查不到就原样返回（只去空白）。
     *
     * 保留原大小写是因为有车型在接口里就是大小写混排（`Lafa5`），
     * 需要比较的地方请用 [key]。
     */
    fun canonical(raw: String?): String {
        val text = raw?.trim().orEmpty()
            .removePrefix("零跑")
            .trim()
        if (text.isEmpty()) return ""
        return ALIASES[text.uppercase(Locale.ROOT)] ?: text
    }

    /** 比较用的键：归一后再统一大写。 */
    fun key(raw: String?): String = canonical(raw).uppercase(Locale.ROOT)

    /**
     * 展示用车型名。接口没给车型时返回「零跑」—— 界面上不能出现空白，
     * 也不能把内部代号（B05）直接摆给车主看。
     */
    fun displayName(raw: String?): String = canonical(raw).ifEmpty { "零跑" }
}
