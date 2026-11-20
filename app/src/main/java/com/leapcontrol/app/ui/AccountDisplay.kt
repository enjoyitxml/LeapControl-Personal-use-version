package com.leapcontrol.app.ui

/**
 * 「我的」页顶部那张账号卡要显示的两样东西：**登录账号**与**完整车架号**。
 *
 * 为什么单独抽出来：手机号是敏感信息，页面上会被人截图、也可能出现在用户
 * 发给别人的截屏里。项目里不存明文手机号到日志、也不该在界面上整串摆出来，
 * 所以统一在这里做一次「中间四位打码」——规则只写一处，单测也只测这一处。
 *
 * 换账号的识别需求仍然满足：前 3 位 + 后 4 位足够区分自己名下的几个手机号。
 */
object AccountDisplay {

    /** 手机号脱敏：`13812345678` → `138****5678`。 */
    fun maskPhone(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        // 有的接口会带国际区号（+86 138…）：先摘掉区号，否则 13 位会落到「位数不对」分支、
        // 整串号码原样上屏，打码就白做了
        val local = when {
            digits.length == 11 -> digits
            digits.length == 13 && digits.startsWith("86") -> digits.substring(2)
            // 位数不对（固话、邮箱账号、或者拿到的就是个昵称）时不做「打码」，
            // 硬套会给出 `138****` 这种更让人困惑的结果，原样返回反而更清楚
            else -> return raw.trim()
        }
        return local.take(3) + "****" + local.takeLast(4)
    }

    /**
     * 车架号归一：去掉空格与连字符、转大写。
     *
     * 网关和用户在车型配置里各写过一次 VIN，出现过带空格的版本；17 位车架号
     * 本身没有空格，去掉不会误伤。空串仍然是空串，由调用方决定显示「未选择车辆」。
     */
    fun normalizeVin(raw: String): String =
        raw.filterNot { it == ' ' || it == '-' || it == '\u00A0' }.uppercase()

    /** 卡片上的展示值：空值统一给一个占位符，避免出现「登录账号：」后面什么都没有。 */
    fun reveal(value: String, placeholder: String = "—"): String =
        value.trim().ifEmpty { placeholder }
}
