package com.leapcontrol.app.bluetooth

/**
 * Keeps the controller's GATT setup order explicit and independently testable.
 * The current compatibility path negotiates MTU before service discovery, matching
 * the recovered 1PAO 0.10 controller. A missing MTU callback is remembered for one
 * reconnect so that the next connection can continue safely with the default MTU.
 */
internal object BleGattInitializationPolicy {
    const val requestedMtu = 200
    const val mtuCallbackTimeoutMs = 2_500L

    /**
     * 释放旧句柄到重新 connectGatt 之间的等待。
     *
     * 系统蓝牙在假死（服务发现无回包、MTU 不回包）时，句柄虽然 close 了，
     * 协议栈里那条链路还要一小段时间才真正掉，紧接着连会直接拿到 133。
     * 400ms 是实测能稳定重连的最小值，再小就开始出现「重连也失败」。
     */
    const val relinkDelayMs = 400L

    fun initialStep(skipMtuOnce: Boolean): BleGattInitializationStep =
        if (skipMtuOnce) BleGattInitializationStep.DISCOVER_SERVICES
        else BleGattInitializationStep.REQUEST_MTU

    fun afterMtuRequest(accepted: Boolean): BleGattInitializationStep =
        if (accepted) BleGattInitializationStep.WAIT_FOR_MTU
        else BleGattInitializationStep.DISCOVER_SERVICES
}

internal enum class BleGattInitializationStep {
    REQUEST_MTU,
    WAIT_FOR_MTU,
    DISCOVER_SERVICES
}
