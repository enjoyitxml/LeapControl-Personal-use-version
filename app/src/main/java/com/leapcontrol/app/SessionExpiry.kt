package com.leapcontrol.app

/** Identifies terminal refresh-token failures without treating ordinary errors as logout. */
object SessionExpiry {
    fun isRefreshTokenInvalid(message: String?): Boolean {
        val value = message.orEmpty().lowercase()
        if (value.contains("需要手机号")) return false
        val refreshTokenFailure = value.contains("refresh token") ||
            value.contains("refresh_token") || value.contains("refreshtoken")
        val invalid = listOf("失效", "无效", "过期", "expired", "invalid")
            .any(value::contains)
        return refreshTokenFailure && invalid
    }
}

/** User actions for the blocking session-expired dialog. */
enum class SessionExpiredDialogAction {
    DISMISS_REQUESTED,
    CONFIRM_RELOGIN,
}

/** Keeps logout behind the explicit re-login confirmation button. */
object SessionExpiredDialogPolicy {
    fun shouldLogout(action: SessionExpiredDialogAction): Boolean =
        action == SessionExpiredDialogAction.CONFIRM_RELOGIN
}

/**
 * 「现在到底还登录着吗」的三种结论。
 *
 * 二值判断不够用：**核实不了不等于失效**。弱网、飞行模式下探针也会失败，
 * 这时候既不能放行控车（万一是真的失效），也不能把人锁在一个
 * 「登录已失效」的弹框里 —— 那时他连登录都登录不了。
 */
enum class SessionVerdict {
    /** 探针拿到了鉴权接口的肯定答复，或者根本不需要探针（没登录过直接算失效）。 */
    ALIVE,

    /** 鉴权类失败：续期也救不回来，必须重新登录。 */
    EXPIRED,

    /** 网络类失败：判不了。按「先不放行、但不弹强制框」处理。 */
    UNKNOWN,
}

/**
 * 把「探针结果」翻成 [SessionVerdict]。
 *
 * 独立成一个纯函数是为了能单测：这里判错的代价很具体 ——
 * 判成 ALIVE 会放行一条注定失败的控车；判成 EXPIRED 会把一个其实正常的账号
 * 锁进「必须重新登录」的弹框（而且那个框不可关闭）。
 */
object SessionProbePolicy {
    /**
     * @param hasToken 本地还有没有 token（旧 App token 或网关 accessToken 任一）
     * @param failure 探针抛出的异常；null = 探针成功
     */
    fun verdict(
        hasToken: Boolean,
        failure: Throwable?,
        isAuthFailure: (String) -> Boolean,
    ): SessionVerdict {
        // 本地一个 token 都没有：这不是「探针失败」，是压根没登录，直接算失效
        if (!hasToken) return SessionVerdict.EXPIRED
        if (failure == null) return SessionVerdict.ALIVE
        val message = failure.message.orEmpty().lowercase()
        return if (isAuthFailure(message)) SessionVerdict.EXPIRED else SessionVerdict.UNKNOWN
    }
}
