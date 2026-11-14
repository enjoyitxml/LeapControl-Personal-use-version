package com.leapcontrol.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.leapcontrol.app.ConfirmAction
import com.leapcontrol.app.ConfirmActions
import com.leapcontrol.app.ConfirmGate
import com.leapcontrol.app.DoubleConfirmRules
import com.leapcontrol.app.DrivingSafetyPolicy
import com.leapcontrol.app.MainActivity
import com.leapcontrol.app.SessionVerdict
import com.leapcontrol.app.tiles.QuickTiles
import com.leapcontrol.app.ui.AppNavRequest
import com.leapcontrol.app.ui.AppRuntime
import com.leapcontrol.app.ui.CarStatus
import com.leapcontrol.app.ui.ControlActions
import com.leapcontrol.app.ui.ControlFeedback
import com.leapcontrol.app.ui.ControlFix
import com.leapcontrol.app.ui.ControlFailure
import com.leapcontrol.app.ui.StatusSnapshot
import com.leapcontrol.app.ui.WindowControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 桌面小组件的广播接收器。
 *
 * 点按键 → 本进程内直接下发控车指令 → 回读车况 → 重画卡片，不需要常驻服务，也不用打开 App。
 *
 * ⚠️ 这里所有网络动作都必须尽快结束：走 [goAsync] 的广播约 10 秒就会被系统判超时。
 * 所以整条链路都按「预算」分配：短轮询等车端回执 3.5s（[AppRuntime.controlForWidget]）
 * + 回读车况 2.5s + 车模 1s，合计约 7 秒（发送本身 1~2 秒），压住 10 秒这条线。
 *
 * 控车拿到网关受理后**立刻重画一帧**，右上角写「开后备箱已下发」这类中性短句，
 * 记录留在 [WidgetFlash] 里 2 秒 —— 车端执行要 5~15 秒，那一刻谁也不知道成没成，
 * 所以**不许**写「已开启」（2026-11-13 用户反馈的「提示开着、车没动」就是这条）。
 * 真正的结论由广播结束后的 [confirmOutcomeLater] 读车况给出：变成意图值才写完成式，
 * 到最后都没变就说「后备箱还没开」，读不到信号则一句都不说。
 *
 * ## 短时间连发多条指令
 * 点完解锁又马上点开后备箱，是**两个独立广播**，天然并发 —— 车端 T-Box 刚被唤醒时
 * 会把后一条丢掉，而网关照样回受理码。所以 [execute] 里加了一道「下发节拍」
 * （[WidgetCommandClock]）：跨按键至少隔 2.5 秒，第二条等一下再发。
 * 同按键仍有 8 秒意图窗口；解锁 / 上锁 / 鸣笛另有一个 2.5 秒的连点去重。
 *
 * 右上角的刷新键（[VehicleWidget.ACTION_REFRESH]）不走控车链路：只拉一次车况重画。
 *
 * **不能在这里调不带 resultWaitMs 的 control()** —— 它内部用 Thread.sleep 轮询车端回执，
 * 最长 20 秒，外面套 withTimeoutOrNull 也掐不住（没有可中断的挂起点），
 * 结果就是卡片来不及重画、提示弹不出来，甚至被系统当成广播超时掐掉。
 *
 * ## 4×2 与 5×2 是**两个** receiver
 * 安卓的「添加小部件」列表里，一个 receiver + 一份 provider-info 只对应一个固定格数，
 * 想让用户能挑 5 列版，只能再注册一个 receiver（[VehicleWidgetProviderWide]）。
 * 两者共用这里的全部逻辑 —— 它只是个子类，连一个方法都没重写；
 * 差别只在 [VehicleWidget.render] 里「这个 id 属于哪个 component」决定用哪份布局。
 */
open class VehicleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // 先用磁盘缓存的车况画一帧（冷启动也立刻有内容），再后台联网刷新
        runCatching { VehicleWidget.render(context) }
        refreshOnAppear(context)
    }

    /**
     * 卡片「出现」时刷一次车况（用户 2026-11-05 要求：每次出现都是新的）。
     *
     * 系统**没有**「小组件变得可见」的回调 —— `updatePeriodMillis` 故意设 0
     * （定时联网会反复唤醒车上 T-Box）。能感知到的出现时机只有三处：
     * 添加到桌面 / 改尺寸 / 桌面主动请求重画（onUpdate），它们都汇到这里。
     * 5 秒内只发一次：`onUpdate` 与 [onAppWidgetOptionsChanged] 常常前后脚一起到。
     */
    private fun refreshOnAppear(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_APPEAR_REFRESH_AT, 0L) < APPEAR_DEDUPE_MS) return
        prefs.edit().putLong(KEY_APPEAR_REFRESH_AT, now).apply()
        work.launch { refresh(appContext) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // 改尺寸后重画：字号是按卡片实际 dp 算的，不重画会停在旧尺寸的字上
        runCatching { VehicleWidget.render(context) }
        // 卡片刚被调整（多半是刚加到桌面）—— 顺手把车况拉新
        refreshOnAppear(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        // 刷新键只拉车况，不走控车那条（不需要登录校验、二次确认、结果轮询）
        if (action == VehicleWidget.ACTION_REFRESH) {
            runCatching { refreshFromTap(context) }
            return
        }
        if (action !in VehicleWidget.ACTIONS) return
        runCatching { handleAction(context, action) }
    }

    // ---------------- 内部实现 ----------------

    /**
     * 右上角刷新键：只拉一次车况重画。
     *
     * 车况接口正常 1~2 秒，6 秒是上限；加上车模 1 秒与重画，仍压在广播 10 秒预算内。
     * 一开始先把右上角写成「正在刷新…」—— 用户点完立刻有反应，
     * 不然要盯着旧时间等好几秒，看起来就像按钮没接上。
     */
    private fun refreshFromTap(context: Context) {
        val appContext = context.applicationContext
        val pending = goAsync()
        work.launch {
            try {
                val runtime = runCatching { AppRuntime(appContext) }.getOrNull()
                if (runtime == null) {
                    toast(appContext, "零控初始化失败，请打开 App 后重试")
                    return@launch
                }
                val session = runtime.session
                // 用户主动点刷新时，顺手核实一次「上次记下的失效还算不算数」：
                // 会话要是还能续回来（最常见的原因就是当初那条控车指令碰上 token 过期，
                // 而后台刷新已经把 token 续好了），标记就在这里撤掉，卡片立刻回到车况 ——
                // 不能让一张写着「登录已失效」的卡片一直挂着（2026-11-08 反馈）。
                if (ControlFailure.isSessionExpired(appContext) &&
                    runtime.verifySessionVerdict() == SessionVerdict.EXPIRED
                ) {
                    toast(appContext, "登录已失效，点卡片重新登录")
                    VehicleWidget.render(appContext, partial = false)
                    return@launch
                }
                if (session.oldAuth == null && session.newAuth == null) {
                    // 拉车况同样要登录态。这里不主动拉起 App（用户只是点了个刷新，
                    // 被弹到前台会更烦），但把卡片切成「登录已失效」——
                    // 点卡片 / 点五颗按键都会带 RELOGIN 直接进登录流程。
                    ControlFailure.markSessionExpired(appContext)
                    toast(appContext, "登录已失效，点卡片重新登录")
                    VehicleWidget.render(appContext, partial = false)
                    return@launch
                }
                if (session.selectedVin.isBlank()) {
                    toast(appContext, "还没选择车辆，点卡片打开零控后选择")
                    return@launch
                }
                // 用户主动刷新 = 想看真实车况，刚控车的结果提示立刻让位
                WidgetFlash.clear(appContext)
                VehicleWidget.render(appContext, runtime, busy = "正在刷新…", partial = true)
                val fresh = runCatching {
                    withTimeoutOrNull(REFRESH_TIMEOUT_MS) { runtime.refreshStatus() }
                }.getOrNull()
                if (fresh != null) runCatching { StatusSnapshot.put(fresh) }
                runCatching { withTimeoutOrNull(CAR_IMAGE_MS) { VehicleWidget.ensureCar(appContext, runtime) } }
                VehicleWidget.render(appContext, runtime, partial = true)
                if (fresh == null) toast(appContext, "刷新失败，请检查网络后重试")
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private fun handleAction(context: Context, action: String) {
        val appContext = context.applicationContext
        val pending = goAsync()
        val runtime = runCatching { AppRuntime(appContext) }.getOrNull()
        if (runtime == null) {
            pending.finish()
            toast(appContext, "零控初始化失败，请打开 App 后重试")
            return
        }
        // 登录态 / 控车密码同步检查：不满足就没必要起后台任务。
        // 这里的每一句都必须回答「为什么没成」+「下一步做什么」——
        // 2026-11-05 那波「小组件全部失效」实际是账号被退出登录，而当时只弹
        // 「下发失败：未登录」，用户以为是 App 坏了。
        val session = runtime.session
        val noAuth = session.oldAuth == null && session.newAuth == null
        if (noAuth || ControlFailure.isSessionExpired(appContext)) {
            pending.finish()
            // 光弹 toast 不够：桌面广播里弹的 toast 在不少 ROM 上会被直接吞掉，
            // 用户看到的就是「点了按钮毫无反应」。记下登录失效（卡片右上角随即改成
            // 「登录已失效」、五颗按键改成「点一下打开 App」），同时把 App 拉到前台。
            ControlFailure.markSessionExpired(appContext)
            toast(appContext, ControlFailure.HINT_RELOGIN.message)
            work.launch { runCatching { VehicleWidget.render(appContext, partial = false) } }
            openApp(appContext, AppNavRequest.RELOGIN)
            return
        }
        if (session.selectedVin.isBlank()) {
            pending.finish()
            toast(appContext, ControlFailure.HINT_NO_VEHICLE.message)
            openApp(appContext, null)
            return
        }
        if (runtime.sessionStore.loadOpPassword().isNullOrBlank()) {
            pending.finish()
            toast(appContext, ControlFailure.HINT_PIN.message)
            openApp(appContext, AppNavRequest.OP_PASSWORD)
            return
        }

        work.launch {
            try {
                execute(appContext, runtime, action)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private suspend fun execute(context: Context, runtime: AppRuntime, action: String) {
        // ---- 行驶中禁控（2026-11-09，与官方 VehicleDrivingSafetyPolicy 同步）----
        // 桌面按键**不变灰**（用户明确要求），但点击要拦下并说明原因。
        // 挂在最前面：确认弹窗、判方向、下发全部不做 —— 一句话的提示不应该先花 2.5 秒去对车况。
        //
        // 车况只读本地（[safetyStatus]），不为这次点击额外联网：
        // 桌面广播本来就压在 10 秒预算里，而且项目纪律不让为判状态反复拉车况。
        if (DrivingSafetyPolicy.isDriving(safetyStatus(runtime))) {
            // 卡片右上角借来说一句原因（只走增量，别为一个提示重画壁纸）
            VehicleWidget.render(context, runtime, busy = DRIVING_HINT_SHORT, partial = true)
            toast(context, DrivingSafetyPolicy.HINT)
            resetHintLater(context, runtime)
            return
        }

        // 8 秒内再点同一个开关键：上一次还在路上（车端 5~15 秒才执行完）。
        //
        // 这时**不能**按「取反」判方向：下发成功那一刻本地开合态已经翻过去了，
        // 再取反得到的是**反方向**指令 —— 点两下就是「开完立刻关」，两条指令互相抵消，
        // 用户看到的就是「按了没反应」，而且比只按一下更糟（车可能先开又关）。
        // 也**不能**靠再拉一次车况定夺：拉回来还是上一轮报文（[directionStatus] 的宽限期同理）。
        //
        // 所以窗口内一条指令都不发，只在卡片上说清「上一次还在执行」；过了窗口再点就正常。
        // 车窗不受此限：它是档位循环，连点两下 = 有意进两档，见 [TOGGLE_TILES]。
        // 哨兵没有对应磁贴，但同样要守 8 秒意图窗口：开完立刻再点一次会算出「关」，
        // 两条指令互相抵消，用户看到的就是「按了没反应」。
        val toggle = TOGGLE_TILES[action]?.let { QuickTiles.byId(it) }
        val intentAt = when {
            // 同时看「上一次下发成功」与「上一次开始下发」：后者在发送前就写了，
            // 覆盖住「已决定要发、车端还没回执」这段时间 —— 只看前者的话，
            // 这一秒内再点同一个键会按「取反」算出反方向，连点两下就成了「开 + 开」。
            // 车窗**不**纳入：它是档位循环，连点两下是有意进两档。
            toggle != null -> maxOf(
                QuickTiles.lastActionAt(context, toggle),
                WidgetCommandClock.lastSendAt(context, action),
            )
            action == VehicleWidget.ACTION_SENTRY -> maxOf(
                WidgetSentryState.lastActionAt(context),
                WidgetCommandClock.lastSendAt(context, action),
            )
            else -> 0L
        }
        val now = System.currentTimeMillis()
        if (WidgetTapRules.withinIntentWindow(intentAt, now)) {
            showRunningHint(context, runtime)
            return
        }
        // 解锁 / 上锁 / 鸣笛这类瞬时动作没有「反向」可切，连点两次就是**同一条指令发两遍**：
        // 白多唤醒一次车，车端还要再执行一遍电机动作。2.5 秒内的重复点击直接忽略，
        // 卡片上说一句「正在执行」，别让用户以为没点上。
        //
        // 开关型不走这条（它们有 8 秒窗口，而且「开完再关」是合法意图）。
        if (action in REPEAT_ACTIONS && WidgetCommandClock.withinRepeatWindow(context, action, now)) {
            showRunningHint(context, runtime)
            return
        }

        // 开关式按键（空调 / 后备箱 / 电池预热 / 遮阳帘 / 哨兵）得先知道「现在是什么状态」才知道往哪个方向切；
        // 车窗还得知道「现在有没有窗开着」才知道下一档是关还是微开。
        //
        // **判方向之前先对一次车况**（[directionStatus]），这一步不能省：
        // 桌面广播常常落在冷进程里，内存里没有车况，本地 `state_<id>` 又是上一次点击
        // （或磁贴、或几天前）留下的 —— 用户在车里手动开了遮阳帘、或在 App 首页开了电池预热，
        // 本地一无所知，于是点下去发出的是一条「关一个本来就关着的东西」的空指令：
        // 网关照样受理（卡片写着「已下发」），车端一动不动
        // —— 2026-11-08「小组件上的车窗 / 电池预热 / 遮阳帘指令不通」就是这个。
        //
        // 对车况要等一小会儿（最多 2.5 秒），这段时间不能什么都不显示：
        // 用户点完盯着卡片，两秒没变化就是「点了没反应」。先挂一句中性的「…调节中」，
        // 指令名定下来之后再换成带方向的精确文案（[labelOf]）。
        //
        // 这一帧同时覆盖下面「跨按键等待」的那段时间 —— 第二条指令排队时，
        // 卡片上写的就是它自己的「后备箱调节中…」，用户看得出点按被接住了。
        preparingLabelOf(action)?.let {
            VehicleWidget.render(context, runtime, busy = it, partial = true)
        }

        val vin = runtime.session.selectedVin
        val lastAction = lastDirectionActionAt(context, vin, action)
        // 8 秒内刚发过**任何**一条指令：车端还在执行上一条，这时候拉回来的必然还是旧报文。
        // 为它多等 2.5 秒既浪费判向预算（广播只有 10 秒），又可能把方向算错。
        //
        // 这里用**全局**的最后下发时刻，不是上一条同动作 —— 「点完解锁马上点开后备箱」
        // 正是最容易踩到的场景：后备箱自己的时间戳是几小时前的，按它算就不算「刚发过」。
        // 跳过判向之后，方向退回本地记忆（`QuickTiles.isOn`），而那正是上一轮下发的意图，
        // 比一条还没反映任何动作的旧报文更可信。
        val lastAnySend = WidgetCommandClock.lastSendAt(context)
        val justTapped = lastAnySend != 0L &&
            System.currentTimeMillis() - lastAnySend < QuickTiles.ACTION_GRACE_MS
        val status = when {
            action !in DIRECTION_ACTIONS -> runtime.statusFlow.value
            // 刚点过：本地记忆就是最新意图，车端还在执行，拉回来的还是旧报文 ——
            // 不为它多等 2.5 秒，也绝不用它把方向翻回去
            justTapped -> null
            else -> directionStatus(context, runtime, lastAction)
        }
        if (action in DIRECTION_ACTIONS && status != null) {
            // 校准规则与控制中心磁贴**同一份**（含 8 秒宽限期、信号 null 不动），别在这边另写
            runCatching { QuickTiles.calibrateFromStatus(context, status) }
            // 哨兵不是磁贴，QuickTiles.ALL 里没有它，本地态单独校一次（同样守 8 秒窗口）
            if (action == VehicleWidget.ACTION_SENTRY) {
                WidgetSentryState.calibrateFromStatus(context, status.sentryOn)
            }
        }

        var wantOn: Boolean? = null
        if (toggle != null) {
            wantOn = !QuickTiles.isOn(context, toggle)
        }

        // 车窗是 4 档循环（关 → 微开 → 半开 → 全开 → 关）。车况只回报「有没有窗开着」，
        // 分不出开度，档位只能本地记 —— 下一档命令与档位记忆都走 WindowControl，
        // 和控制中心磁贴同一套，不在这里另写一份状态。
        // `statusOpen` 取上面刚对过的那份车况：null（读不到）时 resolve 会退回本地档位。
        val windowCommand = if (action == VehicleWidget.ACTION_WINDOW) {
            val stage = WindowControl.resolve(
                saved = WindowControl.load(context, vin),
                savedAt = WindowControl.savedAt(context, vin),
                statusOpen = status?.windowOpen,
            )
            WindowControl.nextCommand(stage)
        } else {
            null
        }

        val command = when (action) {
            VehicleWidget.ACTION_UNLOCK -> "unlock"
            VehicleWidget.ACTION_LOCK -> "lock"
            VehicleWidget.ACTION_TOGGLE_AC -> if (wantOn == true) "acOn" else "acOff"
            VehicleWidget.ACTION_TOGGLE_TRUNK -> if (wantOn == true) "trunkOpen" else "trunkClose"
            VehicleWidget.ACTION_HORN -> "horn"
            VehicleWidget.ACTION_BATTERY_PREHEAT ->
                if (wantOn == true) "batteryPreheat" else "batteryPreheatOff"
            VehicleWidget.ACTION_SUNSHADE ->
                if (wantOn == true) "sunshadeOpen" else "sunshadeClose"
            VehicleWidget.ACTION_WINDOW -> windowCommand ?: return
            // 与零控首页的哨兵按钮**同一条判定**：sentryOn == true 就发 sentryOff，否则发 sentryOn。
            // 车况里压根读不到这个信号（部分车型恒 null）时才退回本地开合态，
            // 否则「本地已经开着、再点一下」会又发一条 sentryOn。
            VehicleWidget.ACTION_SENTRY ->
                if (status?.sentryOn != null) ControlActions.resolveCommand("sentry", status)
                else if (WidgetSentryState.isOn(context)) "sentryOff" else "sentryOn"
            else -> return
        }

        // 「再点一次确认」**只看设置**（「设置 → 快捷控制再点一次」，默认全关）。
        //
        // 这里曾经多挂了一条 `|| command == "trunkOpen"` —— 不管开关、开后备箱一律点两下。
        // 那是我自己加的「防误触」兜底，和 2026-11-04 定下的口径（默认点一次就下发，
        // 想防误触的人自己去勾）直接冲突：没开这项设置的人点一下必然发不出去，
        // 看见的就是「后备箱按钮按了没效果」。2026-11-11 撤掉，要保护就去设置里勾
        // 「打开后备箱」，与磁贴 / 首页完全同一条规则。
        //
        // [ConfirmGate] 仍是唯一记录处，别再拆出第二套（早先的 `confirmTrunkOpen`
        // 只弹 toast、不重画卡片、也不排复位，卡片会一直挂着「调节中」）。
        val needsConfirm = ConfirmActions.isEnabled(context, command)
        if (needsConfirm && !ConfirmGate.step(context, SCOPE, command)) {
            val label = ConfirmAction.of(command)?.title ?: "该操作"
            // 卡片右上角那行本来显示「12:30 更新」，临时借来说一句确认提示；
            // 只走增量，别为一个提示把整张卡（含壁纸）重画一遍。
            // 这一步同时把上面那句「后备箱调节中…」盖掉 —— 用户的第二下得有个看得见的依据。
            VehicleWidget.render(context, runtime, busy = ConfirmActions.HINT, partial = true)
            toast(context, "再点一次「$label」确认")
            resetHintLater(context, runtime)
            return
        }

        // ---- 跨按键串行：短时间连发两条指令时，让第二条**等一等**再发 ----
        //
        // 用户 2026-11-13 反馈：点完解锁，很快又点开后备箱，卡片写着「后备箱已开启」，
        // 车其实没动。根因不在判方向 —— 两条指令是**并发**下发的：
        // 第一条还在等网关回执，第二条已经冲出去了，车端（T-Box 刚被唤醒）把后一条丢掉，
        // 而网关照样回受理码 0，于是卡片把「受理」写成了「已开启」。
        //
        // 网关对同一台车的指令没有排队语义，只能由客户端把节奏拉开
        // （[WidgetCommandClock.MIN_GAP_MS] = 2.5 秒）。
        //
        // 位置在二次确认**之后**：确认那一下没真的下发任何东西，不该占掉一个节拍 ——
        // 否则「再点一次确认」的第二下会被硬等 1.8 秒才发出去。
        //
        // 上限 [SERIAL_WAIT_MAX_MS] 是为了守住广播的 10 秒预算：
        // 等太久会让这条广播来不及做回执轮询，反而丢掉这次点击。
        //
        // 时刻取**现在**而不是 `execute` 开头：判向（最多 2.5 秒）也算作两次下发之间的间隔，
        // 早先按开头时刻算会把这段重复计入，第二条白白多等 2.5 秒，正好踩广播超时线。
        val waitMs = WidgetCommandClock.noteSend(context, action, System.currentTimeMillis())
            .coerceAtMost(SERIAL_WAIT_MAX_MS)
        if (waitMs > 0) {
            // delay 是可中断挂起点：广播被系统收走时这条协程会被一起取消，不会悬着
            delay(waitMs)
        }

        VehicleWidget.render(context, runtime, busy = labelOf(command), partial = true)

        // 短轮询：在广播预算里真的等一段车端回执（默认 3.5 秒）。
        // 拿到结果码 0 / 200 只代表**网关受理**，返回「XX已下发」；
        // 车端到底动没动，由广播结束后的车况求证决定（见 [confirmOutcomeLater]）。
        // 不能退回 resultWaitMs = 0：那是发完立刻返回，一个字的结果都不等，
        // 只剩 3 秒回读去猜 —— 而车端执行要 5~15 秒，读回来还是上一条报文，
        // 用户看到的就是「点了之后状态不刷新」。
        val outcome = runCatching {
            withTimeoutOrNull(WATCHDOG_MS) { runtime.controlForWidget(command, RESULT_BUDGET_MS) }
        }
        val message = outcome.getOrNull()
        if (message == null) {
            // 没发出去就别改本地开合态，也别让卡片停在「正在上锁…」
            VehicleWidget.render(context, runtime)
            fail(context, outcome.exceptionOrNull())
            return
        }

        // 标记**不在这里撤**：一次控车成功不等于账号恢复了（登录失效时这条压根发不出去），
        // 撤了只会制造「卡片说失效、控车又能用」的自相矛盾。撤标记只有两处 ——
        // 登录成功，和 `AppRuntime.verifySessionVerdict()` 在线核实出会话其实还好。

        if (toggle != null) {
            // 指令确实发出去了才落状态：发失败时不能让按键「假装」已经开/关了
            QuickTiles.setOn(context, toggle, wantOn == true)
            // 顺手把控制中心那几块磁贴也标脏，两处显示不会对不上
            runCatching { QuickTiles.refreshAll(context) }
        }
        if (action == VehicleWidget.ACTION_WINDOW) {
            // 车窗同理：发出去之后才把下一档记下来。失败时上面已经 return，
            // 档位不会被改成车上根本没达到的那一档。
            WindowControl.remember(context, vin, command)
        }
        if (action == VehicleWidget.ACTION_SENTRY) {
            // 指令确实发出去了才落本地开合态：发失败时不能让按键「假装」已经开了哨兵。
            // 时间戳同时开启 8 秒意图窗口（窗口内不再下发、高亮跟本地意图）。
            WidgetSentryState.setOn(context, command == "sentryOn")
        }

        // 结果**立刻**上卡片，但**只说「已下发」**。
        //
        // 网关受理 ≠ 车端执行（见 [AppRuntime.controlForWidget]）：这条链路上我们拿到的
        // 只是结果码 0/200，车端还要 5~15 秒才动，短时间连发时后一条甚至会被车端丢掉。
        // 以前这里按 `message.endsWith("已执行")` 判成功，等于把「受理」写成「已开启」，
        // 还顺带给按键亮 8 秒 —— 用户 2026-11-13 反馈的「提示已开启、车其实没动」就是它。
        //
        // 现在：文案 = `doneLabelOf(command, false)`（「开后备箱已下发」），
        // 颜色仍用主色（这是进展，不是失败），hint 照给（按键跟本地意图走，
        // 否则从点下到车况回来之间按键是灭的，看着像没点上）。
        // 真正的「后备箱已开启」只能由车况求证来写，见 [confirmOutcomeLater]。
        WidgetFlash.write(
            context,
            doneLabelOf(command, confirmed = false),
            ok = true,
            hint = command,
        )
        VehicleWidget.render(context, runtime, partial = true)
        toast(context, message)
        // 回读车况从广播里挪出去（见 [scheduleFlashExpiry]）：判方向已经用掉一段网络时间，
        // 广播只有约 10 秒预算，再在广播里等回读会把尾巴拖到线上。
        scheduleFlashExpiry(context, runtime)
        // 再看一眼车端到底动没动：这条不占广播预算，跑在进程级协程里
        confirmOutcomeLater(context, runtime, command)
    }

    /**
     * 「上一次还在执行」的提示。
     *
     * 两条拦截路径（同一个开关键的 8 秒意图窗口 / 解锁上锁鸣笛的连点去重）共用一份，
     * 免得哪一处只弹了 toast、卡片上什么都没变 —— 桌面广播弹的 toast 在不少 ROM 上会被吞掉，
     * 那就等于「点了毫无反应」。
     */
    private fun showRunningHint(context: Context, runtime: AppRuntime) {
        WidgetFlash.write(context, RUNNING_HINT, ok = true)
        VehicleWidget.render(context, runtime, partial = true)
        toast(context, RUNNING_HINT)
        resetHintLater(context, runtime, WidgetFlash.TTL_MS + 300)
    }

    /**
     * 用完车况回读**求证**这次到底成没成，再把结论写回卡片。
     *
     * ## 为什么必须补这一步
     * 广播里只能说「已下发」（10 秒预算等不到车端 5~15 秒的执行）。可是用户要的答案是
     * 「到底开了没有」—— 只说「已下发」等于把判断推给用户，他仍然得跑去车边确认。
     * 所以这里在广播结束后分两次读车况：
     *
     * - 读到「已经是意图值」→ 写完成式（「后备箱已开启」），这是**车说的**，不是网关说的；
     * - 读得到、但到最后一次采样车端仍没动 → 写「后备箱还没开」（警示色，留 5 秒）；
     * - 读不到这个信号（部分车型恒 null）→ 一句都不说，绝不替车端宣布结论。
     *
     * ## 为什么不是马上查
     * 车端执行要 5~15 秒。第一次采样放在 [OUTCOME_PROBES_MS] 的 9 秒后，
     * 第二次再隔 6 秒 —— 中间那次（2.5 秒）由 [scheduleFlashExpiry] 负责把提示交回车况。
     *
     * ## 什么时候放弃
     * 每轮采样前都确认「卡片上还是我写的那条」：用户在这期间又点了别的键，
     * 或者手动点了刷新，就说明他已经在看新一轮的结果，旧的结论不能盖上去。
     *
     * 进程被系统回收时这条协程会一起消失 —— 那也只是少一次核对，不会写出错误结论。
     */
    private fun confirmOutcomeLater(context: Context, runtime: AppRuntime, command: String) {
        // 没有可回读信号的指令（鸣笛 / 电池预热 / 充电枪）本来就无从求证
        if (ControlFeedback.topicOf(command) == null) return
        val appContext = context.applicationContext
        val pendingText = doneLabelOf(command, confirmed = false)
        work.launch {
            OUTCOME_PROBES_MS.forEachIndexed { index, gapMs ->
                delay(gapMs)
                if (!WidgetFlash.stillCurrent(appContext, pendingText, command)) return@launch
                val fresh = runCatching {
                    withTimeoutOrNull(STATUS_REFRESH_MS) { runtime.refreshStatus() }
                }.getOrNull() ?: return@forEachIndexed
                runCatching { StatusSnapshot.put(fresh) }
                when (ControlFeedback.reached(command, fresh)) {
                    true -> {
                        // 车况自己说话了 —— 这才是「已开启」的唯一凭据
                        WidgetFlash.write(
                            appContext,
                            doneLabelOf(command, confirmed = true),
                            ok = true,
                            hint = command,
                        )
                        runCatching { VehicleWidget.render(appContext, runtime, partial = true) }
                        // 这句也只能活 2 秒：到期主动重画一帧，否则会一直挂在卡片上。
                        // 用 [resetHintLater] 而不是 [scheduleFlashExpiry] —— 车况刚拉过，
                        // 没必要为了挪走一行字再唤醒一次 T-Box。
                        resetHintLater(appContext, runtime, WidgetFlash.TTL_MS + 300)
                        return@launch
                    }
                    false -> if (index == OUTCOME_PROBES_MS.lastIndex) {
                        // 信号读得到、车端到最后还是没动：说清楚，别让「已下发」悬在那里。
                        // 顺序很重要 —— **先**按真实车况校准，再重画：
                        // 反过来的话按键会先按本地意图亮起来、下一帧才灭，闪一下。
                        runCatching { QuickTiles.calibrateFromStatus(appContext, fresh) }
                        runCatching { QuickTiles.refreshAll(appContext) }
                        WidgetFlash.write(
                            appContext,
                            notDoneLabelOf(command),
                            ok = false,
                            hint = null,
                            ttlMs = WidgetFlash.TTL_WARN_MS,
                        )
                        runCatching { VehicleWidget.render(appContext, runtime, partial = true) }
                        // 这条警示留 5 秒；到期同样要主动重画，否则会一直挂在那儿
                        resetHintLater(appContext, runtime, WidgetFlash.TTL_WARN_MS + 300)
                    }
                    // null = 这台车读不到这个信号，不替它说话
                    null -> return@launch
                }
            }
        }
    }

    /**
     * 结果提示到期后：拉一次真实车况，再把右上角改回「HH:mm 更新」。
     *
     * 这里是整条链路里唯一的定时器，也是「一直显示上一轮控车结果」的修复点：
     * [WidgetFlash.read] 到点就返回 null，但**桌面不会自己重画** ——
     * 不补这一帧，那行字会一直挂着，直到下次有事件（刷新、控车、改尺寸）。
     *
     * 顺手回读一次车况放在这里，而不是在广播里 `await`：
     * 车端执行要 5~15 秒，2 秒后的这次回读本来也只是「读一眼」，晚两三秒没有区别，
     * 却能把广播预算让给真正决定成败的那几步。
     *
     * 走进程级 [work]：广播那 10 秒预算早就用完了，这条协程仍然能把卡片恢复；
     * 进程如果被回收，这点小事也不值得去 wake up，下次任何重画都会自然回到更新时间。
     */
    private fun scheduleFlashExpiry(context: Context, runtime: AppRuntime) {
        val appContext = context.applicationContext
        work.launch {
            delay(WidgetFlash.TTL_MS + 500)
            val fresh = runCatching {
                withTimeoutOrNull(STATUS_REFRESH_MS) { runtime.refreshStatus() }
            }.getOrNull()
            // 刷新快照：下一次点击判方向时优先用它，省掉一次联网（[directionStatus]）
            if (fresh != null) runCatching { StatusSnapshot.put(fresh) }
            runCatching {
                withTimeoutOrNull(CAR_IMAGE_MS) { VehicleWidget.ensureCar(appContext, runtime) }
            }
            runCatching { VehicleWidget.render(appContext, runtime, partial = true) }
        }
    }

    /**
     * 把临时提示（「再点一次确认」/「正在执行」）改回车况文案。
     *
     * 单独起协程、**不阻塞当前这条广播**：广播 10 秒就超时，
     * 在 [execute] 里 `delay(3 秒)` 会把整条链路一起拖长（v1.1.0 之前踩过）。
     *
     * @param afterMs 多久之后复位。默认按「再点一次」的 3 秒窗口算；
     *   换成 [WidgetFlash.TTL_MS] 那类走结果提示的，由调用方显式传。
     */
    private fun resetHintLater(
        context: Context,
        runtime: AppRuntime,
        afterMs: Long = DoubleConfirmRules.WINDOW_MS + 300,
    ) {
        val appContext = context.applicationContext
        work.launch {
            delay(afterMs)
            runCatching { VehicleWidget.render(appContext, runtime, partial = true) }
        }
    }

    /**
     * 行驶禁控用的车况：**只读本地，不联网**。
     *
     * 与 [directionStatus] 的区别是刻意的：判方向错了会发反方向指令，值得等一次网络；
     * 而「车在不在开」只决定要不要拦一下 —— 读不到就当判不出来（放行），
     * 不该为一次点击拖两秒多、还多烧一份车电。
     *
     * 两个来源：进程内快照 [StatusSnapshot]（磁贴展开 / 首页刷新写过），
     * 以及本进程内存里够新的 `statusFlow`。窗口 [SAFETY_STATUS_MS] 与磁贴那边一致。
     */
    private fun safetyStatus(runtime: AppRuntime): CarStatus? {
        StatusSnapshot.get(maxAgeMs = SAFETY_STATUS_MS)?.let { return it }
        val cached = runtime.statusFlow.value ?: return null
        val at = runtime.lastUpdatedFlow.value
        if (at <= 0L) return null
        return cached.takeIf { System.currentTimeMillis() - at in 0..SAFETY_STATUS_MS }
    }

    /**
     * 判方向用的车况。
     *
     * 桌面广播常常落在**冷进程**里：`AppRuntime(appContext)` 刚建出来，内存里没有车况，
     * 磁盘缓存又可能是几小时前那条报文。所以按这个顺序取：
     *
     * 1. **进程内快照** [StatusSnapshot]（磁贴展开 / 首页刷新 / 之前几次点击写过）——
     *    [STATUS_REUSE_MS] 以内、且**比这次动作的宽限期更晚**的才认，零网络开销；
     * 2. 本进程内存里同样够新的车况（`statusFlow` + `lastUpdatedFlow`）；
     * 3. 都没有才**联网拉一次**，[DIRECTION_SYNC_MS] 封顶 —— 这一步是方向正确的唯一保证，
     *    不能省成「拿本地记忆猜」。
     *
     * 拿不到就返回 null，调用方退回本地记忆（老行为，至少不比现在更糟）。
     *
     * ## 为什么要 `since`
     * 点完按键 2.5 秒后（[scheduleFlashExpiry]）会顺手回读一次车况，但那时车端往往**还没执行**，
     * 存下来的还是旧状态。若下次点击直接采信它，方向又被翻回旧值 —— 于是「点一下没反应」
     * 复现。所以只接受**在最后一次用户动作 + 宽限期之后**拍下的报文：那时的车况才是这次动作的结果。
     * 代价是刚点过的下一次点按可能多一次联网查询 —— 比发一条反方向指令便宜得多。
     *
     * ⚠️ 别改成「无脑联网」：每次点击前都拉一遍车况等于让用户多点几下就多烧几份车电，
     * 项目纪律里「别频繁拉车况」也包括这条。
     */
    private suspend fun directionStatus(
        context: Context,
        runtime: AppRuntime,
        lastAction: Long,
    ): CarStatus? {
        // 最后一次用户动作 + 宽限期之前拍下的报文，都还没反映这次动作，不能拿来判向
        val since = if (lastAction == 0L) 0L else lastAction + QuickTiles.ACTION_GRACE_MS
        StatusSnapshot.get(maxAgeMs = STATUS_REUSE_MS, since = since)?.let { return it }
        val now = System.currentTimeMillis()
        val cached = runtime.statusFlow.value
        val cachedAt = runtime.lastUpdatedFlow.value
        if (cached != null && now - cachedAt in 0..STATUS_REUSE_MS && cachedAt > since) {
            StatusSnapshot.put(cached, cachedAt)
            return cached
        }
        val fresh = runCatching {
            withTimeoutOrNull(DIRECTION_SYNC_MS) { runtime.refreshStatus() }
        }.getOrNull()
        if (fresh != null) {
            runCatching { StatusSnapshot.put(fresh) }
            return fresh
        }
        return cached
    }

    /**
     * 判向相关动作里**这个动作**上一次被用户点按的时刻（0 = 从没点过）。
     *
     * 原来是「四个开关键 + 车窗 + 哨兵取最大值」，看起来更保守，实际是错的：
     * 用户在 2 秒内先点解锁、再点后备箱，取最大值会把后备箱的 `since` 顶到解锁那一刻，
     * 于是后备箱那条明明有可用的车况快照（里面后备箱就是关着，判向完全够用），
     * 却被判成「太旧」而白拉一次网络 —— 2.5 秒预算就这么没了，正好撞上广播的超时线。
     *
     * 判断「这条报文有没有反映这次动作」只跟**同一个信号**有关：
     * 解锁不会改变后备箱的开合，所以按动作各取各的时间戳才准。
     * 同一块磁贴上「刚点完就再点」的重复动作，仍会被这里的 `since` 挡住（8 秒宽限期）。
     */
    private fun lastDirectionActionAt(context: Context, vin: String, action: String): Long = when (action) {
        VehicleWidget.ACTION_WINDOW -> WindowControl.savedAt(context, vin)
        VehicleWidget.ACTION_SENTRY -> WidgetSentryState.lastActionAt(context)
        else -> TOGGLE_TILES[action]
            ?.let { id -> QuickTiles.byId(id)?.let { QuickTiles.lastActionAt(context, it) } }
            ?: 0L
    }

    /**
     * 控车没成：说清原因 + 给下一步。
     *
     * 登录失效 / 没设密码 / 没选车这三类，**必须**把用户送到能解决问题的那一页 ——
     * 在桌面上弹一句 toast 是不够的，很多 ROM 会直接吞掉后台 toast
     * （用户看到的就是「点了没反应」）。
     */
    private fun fail(context: Context, e: Throwable?) {
        val hint = ControlFailure.of(e)
        toast(context, hint.message)
        when (hint.action) {
            ControlFix.RELOGIN -> {
                ControlFailure.markSessionExpired(context)
                // 全量重画一次：卡片右上角要改成「登录已失效」、五颗按键改成「点一下打开 App」，
                // 这些点击区属于静态部分，增量更新改不了。丢到进程级协程里做，别占广播预算。
                work.launch { runCatching { VehicleWidget.render(context, partial = false) } }
                openApp(context, AppNavRequest.RELOGIN)
            }
            ControlFix.OPEN_PIN -> openApp(context, AppNavRequest.OP_PASSWORD)
            ControlFix.OPEN_APP -> openApp(context, null)
            ControlFix.NONE -> Unit
        }
    }

    /**
     * 判向之前那句中性的「正在…」。
     *
     * 开关型直接用磁贴那份 `doing` 文案（「遮阳帘调节中…」），不再写第二套 ——
     * 两处对不上时用户会以为桌面和控制中心不是同一个功能。
     *
     * 解锁 / 上锁 / 鸣笛没有对应磁贴（磁贴里叫「解锁」但走的是 TOGGLE 的另一套），
     * 自己补一句：这三条也要在「跨按键等待」那段时间里有反馈，否则用户点完盯着卡片，
     * 两秒没变化就会以为没点上（2026-11-13 连发反馈的同一个心理）。
     */
    private fun preparingLabelOf(action: String): String? = when (action) {
        // 哨兵没有对应磁贴，QuickTiles.byId("sentry") 是 null，得自己给一句
        VehicleWidget.ACTION_SENTRY -> "哨兵调节中…"
        VehicleWidget.ACTION_UNLOCK -> "正在解锁…"
        VehicleWidget.ACTION_LOCK -> "正在上锁…"
        VehicleWidget.ACTION_HORN -> "正在鸣笛寻车…"
        else -> DIRECTION_TILES[action]?.let { id -> QuickTiles.byId(id)?.doing }
    }

    private fun labelOf(command: String): String = when (command) {
        "lock" -> "正在上锁…"
        "unlock" -> "正在解锁…"
        "acOn" -> "正在开启空调…"
        "acOff" -> "正在关闭空调…"
        "trunkOpen" -> "正在打开后备箱…"
        "trunkClose" -> "正在关闭后备箱…"
        "horn" -> "正在鸣笛寻车…"
        "batteryPreheat" -> "正在开启电池预热…"
        "batteryPreheatOff" -> "正在关闭电池预热…"
        "sunshadeOpen" -> "正在打开遮阳帘…"
        "sunshadeClose" -> "正在关闭遮阳帘…"
        "sentryOn" -> "正在开启哨兵…"
        "sentryOff" -> "正在关闭哨兵…"
        // 车窗四档共用的「进行中」文案也走 WindowControl 那一份，别在这边写第二套
        "windowVent", "windowOpen", "windowFull", "windowClose" -> WindowControl.runningText(command)
        else -> "正在下发…"
    }

    /**
     * 控车完成后右上角那句话。
     *
     * 用**完成式**（「车辆已上锁」）而不是指令名（「上锁」）：用户要的是结果，不是动作。
     * 没等到车端结果码时只能说「已下发」—— 把「发出去了」画成「已经成了」是骗人，
     * 车主看到卡片变了就不会再去车边确认，反而耽误事。
     */
    private fun doneLabelOf(command: String, confirmed: Boolean): String = when (command) {
        "unlock" -> if (confirmed) "车辆已解锁" else "解锁已下发"
        "lock" -> if (confirmed) "车辆已上锁" else "上锁已下发"
        "acOn" -> if (confirmed) "空调已开启" else "开空调已下发"
        "acOff" -> if (confirmed) "空调已关闭" else "关空调已下发"
        "trunkOpen" -> if (confirmed) "后备箱已开启" else "开后备箱已下发"
        "trunkClose" -> if (confirmed) "后备箱已关闭" else "关后备箱已下发"
        "horn" -> if (confirmed) "已鸣笛寻车" else "鸣笛已下发"
        "batteryPreheat" -> if (confirmed) "电池预热已开启" else "开电池预热已下发"
        "batteryPreheatOff" -> if (confirmed) "电池预热已关闭" else "关电池预热已下发"
        "sunshadeOpen" -> if (confirmed) "遮阳帘已打开" else "开遮阳帘已下发"
        "sunshadeClose" -> if (confirmed) "遮阳帘已关闭" else "关遮阳帘已下发"
        "windowVent" -> if (confirmed) "车窗已微开" else "微开车窗已下发"
        "windowOpen" -> if (confirmed) "车窗已半开" else "半开车窗已下发"
        "windowFull" -> if (confirmed) "车窗已全开" else "全开车窗已下发"
        "windowClose" -> if (confirmed) "车窗已关闭" else "关车窗已下发"
        "sentryOn" -> if (confirmed) "哨兵已开启" else "开哨兵已下发"
        "sentryOff" -> if (confirmed) "哨兵已关闭" else "关哨兵已下发"
        else -> "指令已下发"
    }

    /**
     * 车端**明确没动**时那句话（求证到信号可读、但始终没变成意图值）。
     *
     * 措辞一律用「还没…」而不是「失败」：车端可能只是慢，或者被防夹 / 被挡住卡住了，
     * 说「失败」会让用户以为指令被拒、反而想再点一遍。
     * 7 字以内，`wg_time` 只有 118dp。
     */
    private fun notDoneLabelOf(command: String): String = when (command) {
        "unlock" -> "车门还没解锁"
        "lock" -> "车门还没上锁"
        "acOn" -> "空调还没开"
        "acOff" -> "空调还没关"
        "trunkOpen" -> "后备箱还没开"
        "trunkClose" -> "后备箱还没关"
        "batteryPreheat" -> "预热还没开"
        "batteryPreheatOff" -> "预热还没关"
        "sunshadeOpen" -> "遮阳帘还没开"
        "sunshadeClose" -> "遮阳帘还没关"
        "windowVent", "windowOpen", "windowFull" -> "车窗还没开"
        "windowClose" -> "车窗还没关"
        "sentryOn" -> "哨兵还没开"
        "sentryOff" -> "哨兵还没关"
        else -> "车端还没变化"
    }

    private suspend fun refresh(context: Context) {
        val runtime = runCatching { AppRuntime(context) }.getOrNull() ?: return
        val fresh = runCatching { withTimeoutOrNull(6_000) { runtime.refreshStatus() } }.getOrNull()
        // 顺便刷新进程内快照：用户接着点按键时，判方向就不用再联网了（见 [directionStatus]）
        if (fresh != null) runCatching { StatusSnapshot.put(fresh) }
        runCatching { withTimeoutOrNull(3_000) { VehicleWidget.ensureCar(context, runtime) } }
        runCatching { VehicleWidget.render(context, runtime) }
    }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 把 App 拉起来，可带一个直达目标（[AppNavRequest.OP_PASSWORD] / [AppNavRequest.RELOGIN]）。
     *
     * 广播接收器里 `startActivity` 在 Android 10+ 会被后台启动限制拦下，所以这只是兜底：
     * 卡片重画过之后，按键本身已经换成 Activity 型 PendingIntent（由桌面发起，不受这条限制），
     * 用户再点一下一定进得去。`nav == null` 表示只打开首页。
     */
    private fun openApp(context: Context, nav: String?) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        if (nav != null) launch.putExtra(MainActivity.EXTRA_NAV, nav)
        runCatching { context.startActivity(launch) }
    }

    private companion object {
        /** 小组件的后台任务作用域：进程在，任务就在；进程被回收时指令早已发出。 */
        val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 等车端回执的窗口：发送 1~2 秒 + 这段轮询，落在 10 秒广播预算内。 */
        const val RESULT_BUDGET_MS = 3_500L

        /**
         * 判「是否行驶中」时允许采信的车况年龄（与磁贴 `STATUS_SEED_MS` 一致）。
         *
         * 两分钟：再旧就该怀疑车已经停了。宁可漏拦一次，也不要拿一条过期的
         * 「挂 D 挡」把驻车状态下的控车锁死两分钟。
         */
        const val SAFETY_STATUS_MS = 120_000L

        /** 卡片右上角那行放不下 15 个字，短句只用来解释「为什么没动」 */
        const val DRIVING_HINT_SHORT = "行驶中，无法操作"

        /**
         * 判方向前最多等多久车况。
         *
         * 正常 1~2 秒。给到 2.5 秒是因为它决定「开还是关」——宁可慢一拍，也不要发反方向。
         * 加上发送（1~2 秒）与回执轮询（3.5 秒），最坏约 7.5 秒，仍在广播 10 秒预算内。
         */
        const val DIRECTION_SYNC_MS = 2_500L

        /**
         * 这个年龄内的车况足够判方向，不必为此再联网。
         *
         * 一分钟：用户点完小组件、切到 App 看一眼、再回来点，都落在这个窗口里。
         * 再旧就该重新读了 —— 判方向错一次的代价是「点了没反应」。
         */
        const val STATUS_REUSE_MS = 60_000L

        /**
         * 开关型动作 → 磁贴 id。
         *
         * 车窗**不在**这份里：它走 `WindowControl` 的档位循环，每点一下就是有意进一档，
         * 不能被「重复点击」拦下（拦了就没法连点两下从关直接到半开）。
         */
        val TOGGLE_TILES = linkedMapOf(
            VehicleWidget.ACTION_TOGGLE_AC to "ac",
            VehicleWidget.ACTION_TOGGLE_TRUNK to "trunk",
            VehicleWidget.ACTION_BATTERY_PREHEAT to "batteryPreheat",
            VehicleWidget.ACTION_SUNSHADE to "sunshade",
        )

        /**
         * 判方向要靠车况的那几个动作 → 对应的状态 id（其余是单次动作，车况帮不上忙，
         * 别为它们多打一次网络）：开关动作 + 车窗 + 哨兵。
         *
         * 车窗也在这份里：它靠车况才知道「现在到底有没有窗开着」，下一档才可能是关。
         * 哨兵同理：只有读到 `sentryOn` 才能像首页那样判定该发 sentryOn 还是 sentryOff；
         * "sentry" 不是磁贴 id，`QuickTiles.byId` 会返回 null，本地态单独走 [WidgetSentryState]。
         */
        val DIRECTION_TILES: Map<String, String> =
            TOGGLE_TILES +
                (VehicleWidget.ACTION_WINDOW to "window") +
                (VehicleWidget.ACTION_SENTRY to "sentry")

        /** [DIRECTION_TILES] 的动作集合（判向要用车况的那几个）。 */
        val DIRECTION_ACTIONS = DIRECTION_TILES.keys

        /** 连点两下会把**同一条指令发两遍**的动作：解锁 / 上锁 / 鸣笛。
         *
         * 它们没有「反向」可切，重发只会多唤醒一次车、多执行一遍电机动作，
         * 不会有任何一次点击表达出新的意图。开关型不在其中 ——
         * 「开了再关」本身就是合法意图，那由 8 秒窗口按方向处理。
         */
        val REPEAT_ACTIONS = setOf(
            VehicleWidget.ACTION_UNLOCK,
            VehicleWidget.ACTION_LOCK,
            VehicleWidget.ACTION_HORN,
        )

        /**
         * 跨按键排队时最多等多久。
         *
         * [WidgetCommandClock.MIN_GAP_MS] 是理想间隔（2.5 秒），但整条广播只有 10 秒预算：
         * 判向 ≤2.5s + 发送 ≤2s + 回执 ≤3.5s 已经吃掉大半，再等满 2.5 秒就来不及做回执轮询、
         * 这次点击反而白点。1.8 秒是「把两条指令拉开」和「别把广播拖超时」之间的取舍。
         */
        const val SERIAL_WAIT_MAX_MS = 1_800L

        /**
         * 事后求证的两轮采样间隔（相对上一次）。
         *
         * 车端 T-Box 唤醒 + 执行 5~15 秒，第一次 9 秒时多半已经能读到结果；
         * 第二次再等 6 秒兜住慢车。再晚用户已经走开了，写了也没人看见。
         */
        val OUTCOME_PROBES_MS = longArrayOf(9_000L, 6_000L)

        /**
         * 8 秒内重复点同一个开关键时的提示（见 [execute] 开头那道拦截）。
         *
         * 说的是「上一次还在路上」，不是「失败了」—— 不许写成「操作失败」，
         * 那会让用户以为指令被拒，反而更想去点。
         */
        const val RUNNING_HINT = "正在执行，请稍候"

        /** 兜底看门狗：controlForWidget 内部是 Thread.sleep 轮询，实际靠 [RESULT_BUDGET_MS] 收口。 */
        const val WATCHDOG_MS = 8_000L

        /** 回读车况的窗口：车端已执行，报文一般已经跟上，2.5 秒足够。 */
        const val STATUS_REFRESH_MS = 2_500L

        /** 手动刷新车况的窗口：正常 1~2 秒，给 6 秒兜住弱网。 */
        const val REFRESH_TIMEOUT_MS = 6_000L

        /** 车模图（有缓存时是 0 开销）。 */
        const val CAR_IMAGE_MS = 1_000L

        /**
         * 「待确认」状态在**桌面卡片**这个入口下的作用域（见 [ConfirmGate]）。
         *
         * 控制中心每块磁贴用自己的 `tile_<id>`，两边互不干扰：
         * 在磁贴上点过解锁，不该让桌面卡片把下一次开后备箱当成「确认」。
         */
        const val SCOPE = "widget"

        const val PREFS = "widget_action_state"

        /** 卡片出现时的刷新去重窗口（onUpdate 与改尺寸回调常前后脚一起到）。 */
        const val APPEAR_DEDUPE_MS = 5_000L
        const val KEY_APPEAR_REFRESH_AT = "appear_refresh_at"
    }
}
