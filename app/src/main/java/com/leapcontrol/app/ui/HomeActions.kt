package com.leapcontrol.app.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 从首页外面发进来的两个一次性动作：刷新，以及回到顶部。
 *
 * 下拉过一屏后底部第一格会换成用户给的「回到顶部」图标，**默认只发回顶**（不刷新、
 * 不费车电）；是否顺便发刷新由「设置 → 首页外观 → 回到顶部按钮」决定，见
 * `AppRuntime.backToTopWithRefresh`。没过一屏时它仍是普通「零控」格，
 * 重复点按更早定下的口径：刷新 + 回顶。
 *
 * 刻意用 **不带 replay 的 SharedFlow**，不用 StateFlow / 自增 tick：
 * `MainScreen` 里 `when(tab)` 会重建整棵页面子树，首页每次从别的 tab 切回来都是
 * 一次全新的组合。若把请求存成状态（tick），刚切回首页时 `LaunchedEffect` 会读到
 * 上一次那个值并**再执行一次** —— 表现为切个 tab 回来就自己刷新、自己滚回顶部，
 * 把 `rememberSaveable` 保下来的滚动位置冲掉。事件式发一次、被当前订阅者收一次，
 * 没有订阅者（首页没组合）就直接丢，才是这里要的语义。
 *
 * 右下角悬浮球方案已删（2026-11-07 用户改用底部导航第一格）。
 */
object HomeActions {

    // 流名带 Events 后缀：和下面的发送函数区分开，否则 `HomeActions.scrollTop()`
    // 会被解析成「把 SharedFlow 当函数调用」。
    private val _scrollTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollTopEvents: SharedFlow<Unit> = _scrollTop.asSharedFlow()

    private val _refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshEvents: SharedFlow<Unit> = _refresh.asSharedFlow()

    /** 只滚动到顶部；要不要同时刷新由调用方按设置决定，[refresh] 单独发。 */
    fun scrollToTop() {
        _scrollTop.tryEmit(Unit)
    }

    /** 用户主动刷新：走下拉刷新同一条路径（车况 + 能耗，带指示器）。 */
    fun refresh() {
        _refresh.tryEmit(Unit)
    }
}
