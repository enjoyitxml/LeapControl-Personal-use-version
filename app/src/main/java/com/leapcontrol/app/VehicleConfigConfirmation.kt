package com.leapcontrol.app

enum class PostLoginPrompt {
    VEHICLE_CONFIG,
    NONE
}

/** Shared per-VIN preference keys for the non-sensitive vehicle display configuration. */
object VehicleConfigStorageKeys {
    fun prefix(vin: String): String? =
        vin.trim().takeIf { it.isNotEmpty() }?.let { "vehicle_config_${it}_" }

    fun field(vin: String, name: String): String? =
        prefix(vin)?.plus(name)
}

/** Pure policy for the per-vehicle model and power configuration confirmation. */
object VehicleConfigConfirmationPolicy {
    /**
     * Schema 2 remains compatible with existing persisted vehicle configuration.
     */
    const val SCHEMA_VERSION = 2

    fun confirmationPreferenceKey(vin: String): String? =
        VehicleConfigStorageKeys.field(vin, "confirmation_schema")

    /**
     * 把接口/用户给的车型写法归一成 [SUPPORTED_VEHICLE_MODELS] 里的标准项。
     *
     * 先过 [VehicleModels.canonical]：Lafa5 的接口代号是 B05，不先转成宣传名
     * 就匹配不到列表里的 `Lafa5`，老用户存过 B05 的配置还会被判成「无效」而反复弹确认框。
     */
    fun normalizeModel(model: String): String? {
        val candidate = VehicleModels.canonical(model)
        if (candidate.isEmpty()) return null
        return SUPPORTED_VEHICLE_MODELS.firstOrNull { option ->
            candidate.equals(option, ignoreCase = true) || candidate.contains(option, ignoreCase = true)
        }
    }

    fun isConfirmed(storedSchemaVersion: Int): Boolean =
        storedSchemaVersion >= SCHEMA_VERSION

    fun isValid(
        model: String,
        modelYear: String,
        powerType: SessionStore.VehiclePowerType?,
        color: String? = null
    ): Boolean = normalizeModel(model) != null &&
        modelYear.trim().matches(Regex("\\d{4}")) &&
        powerType != null

    fun schemaToPersist(
        model: String,
        modelYear: String,
        powerType: SessionStore.VehiclePowerType?,
        color: String? = null
    ): Int? = if (
        color != null &&
        isValid(model, modelYear, powerType, color)
    ) {
        SCHEMA_VERSION
    } else {
        null
    }

    fun initialPrompt(vehicleConfigConfirmed: Boolean): PostLoginPrompt =
        if (vehicleConfigConfirmed) PostLoginPrompt.NONE else PostLoginPrompt.VEHICLE_CONFIG
}
