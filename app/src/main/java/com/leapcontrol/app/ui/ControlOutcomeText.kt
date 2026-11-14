package com.leapcontrol.app.ui

/**
 * 「这次控车到底成了没有」的一句话结论（纯逻辑，单测钉住）。
 *
 * ## 为什么单独拎出来
 * 这段判定原来写在 [AppRuntime.control] 里，夹在两条网络轮询中间，谁也不敢动、也测不了。
 * 而它恰恰是最该被钉死的一段：**说错一个字的代价是用户以为车已经开了窗、锁了门。**
 *
 * ## 三档事实，不能混
 * - 网关受理（结果码 0 / 200）只代表指令进了队列，**不等于车端执行**；
 * - 车况信号变了（`verified == true`）才算真成功；
 * - 读得到信号但没变（`verified == false`）＝ 车端大概没执行，别替它说成成功；
 * - 压根读不到这个信号，要再分两种（见下）。
 *
 * 2026-11-08 用户反馈「点完车窗一直响应，刷新一下弹个成功，其实窗没开」就是最后这一格
 * 说错了话：有可回读信号的指令却一次都没读到，代码仍然照网关回执回了「执行成功」。
 */
internal object ControlOutcomeText {

    /**
     * @param label 指令名的用户说法（`车窗全开`）
     * @param accepted 网关是否受理（结果码 0 / 200）
     * @param verified 车况求证：true 已变成意图值 / false 读得到但没变 / null 没读到
     * @param readable 这条指令在车况里**有没有**可回读的信号
     *   （鸣笛、电池预热、充电枪这几条本来就没有，只能按网关回执说话）
     * @param notAccepted 网关都没受理时的兜底文案（由调用方按指令再判一次）
     */
    fun of(
        label: String,
        accepted: Boolean,
        verified: Boolean?,
        readable: Boolean,
        notAccepted: String,
    ): String = when {
        verified == true -> "${label}执行成功"
        // 读得到、但窗口内没变成意图值：既不能说成功（车没动），也不该说失败（可能只是慢）
        verified == false ->
            "${label}指令已发出，但车辆状态还没变化，请下拉刷新或到车上确认"
        // 本来就没有可回读的状态：只能按网关回执说话
        accepted && !readable -> "${label}执行成功"
        // 有该信号却一次都没读到 —— 绝不替车端宣布成功
        accepted -> "${label}已发出，但读不到车况，请到车上确认"
        else -> notAccepted
    }

    /**
     * 这句话是不是「车端已经确认执行了」。
     *
     * 只有 [of] 里那两条会以「执行成功」结尾；「已发出 / 还没变化」都不算 ——
     * 车窗档位就按这一条决定记不记（记错了菜单会少列一档，用户就没法重试）。
     */
    fun isSuccess(message: String): Boolean = message.endsWith("执行成功")
}
