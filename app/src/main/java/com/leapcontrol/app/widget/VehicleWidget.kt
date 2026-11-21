package com.leapcontrol.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.leapcontrol.app.ChargeStatus
import com.leapcontrol.app.MainActivity
import com.leapcontrol.app.DEFAULT_VEHICLE_TITLE
import com.leapcontrol.app.PERSONAL_EDITION
import com.leapcontrol.app.R
import com.leapcontrol.app.SentryLocalState
import com.leapcontrol.app.SentrySchemeRules
import com.leapcontrol.app.SentrySchemeStore
import com.leapcontrol.app.tiles.QuickTiles
import com.leapcontrol.app.tiles.statusOn
import com.leapcontrol.app.ui.AppNavRequest
import com.leapcontrol.app.ui.AppRuntime
import com.leapcontrol.app.ui.CarStatus
import com.leapcontrol.app.ui.ControlFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面小组件的渲染与点击派发。
 *
 * ## 为什么是标准 Android 小组件
 * 澎湃 / MIUI 的「原生小部件」是小米私有体系，要求上架小米应用商店并申请白名单，
 * 侧载包走不通，所以用系统 AppWidgetProvider，视觉自己做。
 *
 * ## 三套版式（默认全透明，见 [WidgetStyle.DEFAULT_STYLE]）
 * 用户在「个性化 → 桌面小组件」里选，见 [WidgetStyle]：
 * - **A 素纸**：浅色卡 + 发丝分隔线，字色跟系统深浅走；
 * - **D 沉浸**：一张图铺满，下半部分压暗，按键是白玻璃，字色恒为白；
 * - **T 全透明**：没有卡片底，字与按键直接浮在壁纸上（默认）。
 * （早先还有一版「色块」，用户否决后已删除。）
 *
 * ## 两种尺寸
 * 4×2（默认）与 5×2（[VehicleWidgetProviderWide]，2026-11-06 加）：
 * 5 列的桌面摆 4×2 会空着一列，所以多给一个宽版入口。
 * 「哪个 id 用哪份布局」由它属于哪个 component 决定，见 [PROVIDERS] 与 [layoutFor]。
 * **别用宽度阈值去猜**：有些桌面把 5 列压在 340dp 的屏上，判断会翻车。
 *
 * ## 续航：纯电与增程是两种排版
 * - 纯电：一个大数字（26sp）+ 电量条 + 一行「电量 68%」；
 * - 增程：两行 —— 电（22sp，主题数字）+ 电量条，油（16sp，前面一个小「油」字）+ 油量条，
 *   副信息变成「电 68% · 油 45%」。
 * 判据是 [CarStatus.isRangeExtender]（按 VIN 粘滞的车型能力），不是「有没有油量信号」——
 * 后者在部分车型上会缺失，用它判断会把增程车错画成纯电。
 *
 * ## 尺寸自适应
 * 桌面把卡片拉宽 / 压扁后，系统只给 min/max width/height 这一组 dp 值，RemoteViews 不会自动缩放字号，
 * 所以 [buildViews] 按实际尺寸重设一遍字号（[applyTextScale]）；图标交给 match_parent + fitCenter 自己缩。
 *
 * ## 全量 vs 增量
 * 见 [render] 的 `partial` 参数：**结构没变就别整块重绘**。沉浸样式的壁纸是一张用户自选的原图，
 * 每次全量都要让桌面进程重新解码一遍，刷新车况 / 点按键时会明显闪一下。
 *
 * ## 数据从哪来
 * 小组件与 App 同进程。车况先用磁盘缓存预填（冷启动也能立刻有内容），
 * 再由 [VehicleWidgetProvider] 在添加 / 点按键时后台刷新。
 *
 * 车模图同理，但多了一层 [WidgetCarImage]（磁盘）：桌面定时更新、拖动改尺寸都会在**新进程**里
 * 重画一次，那时 AppRuntime 的内存位图是空的 —— 只认内存就会把车模判成「没有」，让它在卡上消失。
 */
object VehicleWidget {

    /** 单次指令：解锁。 */
    const val ACTION_UNLOCK = "com.leapcontrol.app.widget.UNLOCK"

    /** 单次指令：上锁。 */
    const val ACTION_LOCK = "com.leapcontrol.app.widget.LOCK"

    /** 开关式：空调开 / 关。 */
    const val ACTION_TOGGLE_AC = "com.leapcontrol.app.widget.TOGGLE_AC"

    /** 开关式：后备箱开 / 关。 */
    const val ACTION_TOGGLE_TRUNK = "com.leapcontrol.app.widget.TOGGLE_TRUNK"

    /** 单次指令：鸣笛寻车。 */
    const val ACTION_HORN = "com.leapcontrol.app.widget.HORN"

    /** 档位循环：车窗四档（见 WindowControl），用户可把任意槽位换成它。 */
    const val ACTION_WINDOW = "com.leapcontrol.app.widget.WINDOW"

    /** 开关式：电池预热开 / 关。 */
    const val ACTION_BATTERY_PREHEAT = "com.leapcontrol.app.widget.BATTERY_PREHEAT"

    /** 开关式：遮阳帘开 / 关。 */
    const val ACTION_SUNSHADE = "com.leapcontrol.app.widget.SUNSHADE"

    /** 开关式：哨兵模式开 / 关，判向与首页同源（ControlActions.resolveCommand）。 */
    const val ACTION_SENTRY = "com.leapcontrol.app.widget.SENTRY"

    /** 只刷新车况，不下发任何指令（右上角那颗刷新键）。 */
    const val ACTION_REFRESH = "com.leapcontrol.app.widget.REFRESH"

    /**
     * 控车指令集合（9 个动作）。刷新键单独处理，不在这份里 —— 它不需要登录校验 / 二次确认。
     * `VehicleWidgetProvider.onReceive` 拿它过滤广播，新动作必须进这份，否则点了没反应。
     */
    val ACTIONS = setOf(
        ACTION_UNLOCK, ACTION_LOCK, ACTION_TOGGLE_AC, ACTION_TOGGLE_TRUNK, ACTION_HORN,
        ACTION_WINDOW, ACTION_BATTERY_PREHEAT, ACTION_SUNSHADE, ACTION_SENTRY,
    )

    /** 点「打开 App」用的 requestCode（与五个按键岔开）。 */
    private const val RC_OPEN_APP = 90
    private const val RC_UNLOCK = 91
    private const val RC_LOCK = 92
    private const val RC_AC = 93
    private const val RC_TRUNK = 94
    private const val RC_HORN = 95
    private const val RC_REFRESH = 97
    // 后续新增动作各占一个独立 requestCode，和上面那些（以及 96/97）都不冲突。
    private const val RC_WINDOW = 98
    private const val RC_BATTERY_PREHEAT = 99
    private const val RC_SUNSHADE = 100
    private const val RC_SENTRY = 101

    /** 「打开 App 并直达某个页面」用独立 requestCode，别和上面那颗普通打开混成同一个。 */
    private const val RC_OPEN_APP_NAV = 96

    /**
     * 5 个槽位的容器 / 图标 id：**按位置固定**，六份布局的 id 集合完全一致
     * （见 `WidgetSizesTest`）。换成哪个动作只改绑定内容，不改这些 id。
     */
    private val CELL_IDS = intArrayOf(
        R.id.wg_cell_unlock, R.id.wg_cell_lock, R.id.wg_cell_ac,
        R.id.wg_cell_trunk, R.id.wg_cell_horn,
    )
    private val ICON_IDS = intArrayOf(
        R.id.wg_icon_unlock, R.id.wg_icon_lock, R.id.wg_icon_ac,
        R.id.wg_icon_trunk, R.id.wg_icon_horn,
    )

    /**
     * 四条竖向分隔线。**六份布局必须都有这四个 id**（见 `WidgetSizesTest`）：
     * RemoteViews 只能按 id 改属性，A / D 两份布局原先没写 id，
     * 于是「关掉分隔线」只能对全透明样式生效 —— 用户要的是三套都能关。
     */
    private val DIVIDER_IDS = intArrayOf(
        R.id.wg_div1, R.id.wg_div2, R.id.wg_div3, R.id.wg_div4,
    )

    /**
     * 电量 / 油量进度条外面那层「槽」。
     *
     * **粗细只能加在槽上，不能加在条自己身上**：`ProgressBar` 把 drawable 收到内容区后
     * 是从内容区**顶部**开始画的（不是居中），给条自己加对称内边距，条的顶边不动、底边
     * 往上收 —— 用户看到的就是「条比文字高 2~3dp」（2026-11-09 截图反馈）。
     * 改加在定高 12dp 的槽上，条会跟着槽的内容区一起变矮，而槽本身仍由行的
     * `gravity="center_vertical"` 居中，于是不论粗细 / 字号怎么调都在同一条中线上。
     */
    private val BAR_BOX_IDS = intArrayOf(R.id.wg_ev_bar_box, R.id.wg_fuel_bar_box)

    /** 每个动作一个独立 requestCode：`FLAG_UPDATE_CURRENT` 不会把不同广播互相覆盖。 */
    private fun requestCodeFor(id: WidgetButtonId): Int = when (id) {
        WidgetButtonId.UNLOCK -> RC_UNLOCK
        WidgetButtonId.LOCK -> RC_LOCK
        WidgetButtonId.AC -> RC_AC
        WidgetButtonId.TRUNK -> RC_TRUNK
        WidgetButtonId.HORN -> RC_HORN
        WidgetButtonId.WINDOW -> RC_WINDOW
        WidgetButtonId.BATTERY_PREHEAT -> RC_BATTERY_PREHEAT
        WidgetButtonId.SUNSHADE -> RC_SUNSHADE
        WidgetButtonId.SENTRY -> RC_SENTRY
    }

    // 浅色卡上的字色
    private const val INK_LIGHT = 0xFF12141A.toInt()
    private const val SUB_LIGHT = 0xFF8A9099.toInt()
    private const val WARN_LIGHT = 0xFFD98324.toInt()

    // 深色卡上的字色（对比度按 WCAG 正文 4.5:1 取）
    private const val INK_DARK = 0xFFF2F5FA.toInt()
    private const val SUB_DARK = 0xFFA9B3C1.toInt()
    private const val WARN_DARK = 0xFFFFB454.toInt()

    /** 沉浸样式压在图上：字恒白，副信息靠透明度分层。 */
    private const val INK_GLASS = 0xFFFFFFFF.toInt()
    private const val SUB_GLASS = 0xCCFFFFFF.toInt()
    private const val WARN_GLASS = 0xFFFFD9A0.toInt()

    private const val ACCENT_LIGHT = 0xFF1FA45B.toInt()
    private const val ACCENT_DARK = 0xFF3DD68C.toInt()
    private const val ACCENT_GLASS = 0xFF8BEFB8.toInt()

    /** 续航两行的小图标：与首页续航区同色（电绿、油蓝），沉浸样式提亮一档。 */
    private const val EV_LIGHT = 0xFF2FCC71.toInt()
    private const val EV_DARK = 0xFF3DD68C.toInt()
    private const val EV_GLASS = 0xFF8BEFB8.toInt()
    private const val FUEL_LIGHT = 0xFF2F86F6.toInt()
    private const val FUEL_DARK = 0xFF5AA9FF.toInt()
    private const val FUEL_GLASS = 0xFF9CC8FF.toInt()

    /**
     * 全透明主题：字色与控件色都由用户定，这里只补「信号色」。
     *
     * 电绿油蓝如果直接用浅壁纸那套（#2FCC71 / #2F86F6），压在白墙纸或浅灰壁纸上会发灰，
     * 所以按用户字色的亮度分亮 / 暗两档；警示色同理 —— 深壁纸要暖橙才看得见，
     * 浅壁纸要用压深的砖红。
     */
    private const val EV_T_LIGHT = 0xFF3DD68C.toInt()
    private const val EV_T_DARK = 0xFF12A150.toInt()
    private const val FUEL_T_LIGHT = 0xFF5AB4FF.toInt()
    private const val FUEL_T_DARK = 0xFF1A73E8.toInt()
    private const val WARN_T_LIGHT = 0xFFFFB454.toInt()
    private const val WARN_T_DARK = 0xFFC2410C.toInt()

    /**
     * 五个按键图标的留白基数（dp）。
     *
     * 它们不是同一套比例：空调的压缩机符号笔画密、层次多，撑满格子会显得比邻居大一圈；
     * 鸣笛的喇叭是横长形，按基数缩完看着略小，要少留一点边（但不能像上一版那样放太多）。
     * 统一留白时并排一眼就能看出不齐。
     */
    private const val AC_ICON_SHRINK = 2.5f
    private const val HORN_ICON_GROW = 1.5f

    /**
     * 鸣笛图标的上移量（dp）：靠上下不对称的 padding 把它顶高一点。
     * 喇叭图形本身重心偏下，跟旁边三个居中图标并排时看着像「坐」在格子里。
     */
    private const val HORN_ICON_LIFT = 1.5f

    /** 按键格的通用留白基数（dp）：倍率 = 1.0 时的值，微调在它上面换算（见 WidgetMetrics.iconPaddingDp）。 */
    private const val BASE_ICON_PAD = 5.5f

    /** 版式基准：320×150dp（4 列 2 行在 400dp 级屏幕上的常见实际尺寸）。 */
    private const val BASE_WIDTH_DP = 320f
    private const val BASE_HEIGHT_DP = 150f

    /**
     * 数字（续航 / 百分比）的字号基准，与布局里的 `textSize` 一致。
     *
     * 上限不写在这里：卡片尺寸系数与用户调的「信息区大小」相乘后，
     * 由 [WidgetMetrics.NUM_MAX_SCALE] 先夹一道，再交给
     * [WidgetMetrics.numberSizeScale] 按系统字体实测宽度收 ——
     * 槽宽（56dp / 宽版 62dp）只是「应该装得下」，真正决定要不要缩的是实测结果。
     */
    internal const val NUM_SIZE_SP = 13f

    /** 车名与右上角更新时间的基准字号（与布局一致）。 */
    private const val TITLE_SIZE_SP = 13f
    private const val TIME_SIZE_SP = 10f

    /**
     * 量宽度用的样本文本。
     *
     * 用「888km」而不是真实里程：8 在绝大多数字体里是最宽的数字，取值稳定，
     * 不会因为「今天刚好 145、明天 99」就让字号来回跳；
     * 也不能用「999km」—— 9 通常比 8 窄，按它会算小、真到 888 又放不下。
     */
    private const val NUM_SAMPLE = "888km"

    /**
     * 车模图的内存缓存。`render()` 会被 UI 线程（改尺寸）、IO 线程（刷新车况）、
     * 广播线程（点按键）同时调用，所以两个字段都标 `@Volatile`。
     */
    @Volatile
    private var carCache: Bitmap? = null

    @Volatile
    private var carCacheVin: String? = null

    /** 补车模图的后台任务。进程在任务就在，进程没了说明图也不需要再画。 */
    private val carFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 找不到车模图时，两次「补图」之间的最小间隔（按 VIN 记）。 */
    private const val CAR_RETRY_MS = 15 * 60 * 1000L

    /** 补图节流记在哪个 prefs 里（与控车按键的 `widget_action_state` 分开）。 */
    private const val PREFS_CAR = "widget_car_state"

    /**
     * 上一次**全量**渲染时的深浅模式。
     *
     * 增量更新不动背景色，所以系统切深浅色时必须强制回全量 —— 否则会留下一张
     * 浅色底配浅色字的卡（背景是深色的旧值，文字是新算的白色）。
     * 同时它还兜住「进程刚起来、卡片还没全量画过」这一帧：null ≠ 当前值，自然走全量。
     */
    @Volatile
    private var lastFullNight: Boolean? = null

    /**
     * 上一次**全量**渲染时用的版式。
     *
     * 与 [lastFullNight] 同一个道理：增量更新只能打在**同一份布局**上，
     * 换了版式（素纸 / 沉浸 / 全透明是三份独立 XML）之后再来一帧增量，
     * 桌面按 id 找不到对应视图，整帧被丢掉 —— 卡片会卡在旧车况上，
     * 直到下一次全量更新。null（本进程还没全量画过）同样强制全量。
     */
    @Volatile
    private var lastFullStyle: String? = null

    // ---------------- 对外入口 ----------------

    /**
     * 桌面上有**两个**入口：4×2 与 5×2（见 [VehicleWidgetProviderWide]）。
     *
     * 两者共用全部渲染逻辑，唯一的差别是「用哪份布局」—— 记在这里，
     * 免得日后加尺寸时又在 [render] 里撒出一堆 if。
     */
    private val PROVIDERS = listOf(
        VehicleWidgetProvider::class.java to false,
        VehicleWidgetProviderWide::class.java to true,
    )

    /**
     * 用内存 / 磁盘里已有的数据立刻重画一次（不发网络请求）。
     *
     * @param partial true = 只把**会变的东西**推给桌面（状态文案、续航数字、进度条、
     *   按键高亮、车模），不动背景 / 壁纸 / 字号 / 内边距 / 点击区。
     *   高频路径（每次刷新车况、点按键时的「正在上锁…」）必须走这条：
     *   全量重绘在沉浸样式下会让桌面进程把用户那张壁纸重新解码一遍，卡片会明显闪一下。
     *   首次添加、改尺寸、换版式 / 换壁纸 / 换车模这些**结构变了**的场合仍走全量。
     *
     * ## 为什么要 `@Synchronized`
     * 桌面卡片的每一条广播各自起协程（点击下发、结果提示到期、刷新键、卡片出现时的刷新），
     * 它们会**同时**调到这里。桌面那条 `updateAppWidget` 是异步的 binder 调用，
     * 一旦先后顺序错了，一张**更旧**的帧会盖住刚画好的新帧 ——
     * 表现就是「点了后备箱，右上角先写调节中、闪一下又变回车辆已解锁」。
     * 串行化之后每一帧按调用顺序落地，最后画的永远是最新的那一次。
     *
     * 单帧耗时是毫秒级（车模图走内存 / 磁盘缓存，网络补图是另起的协程），
     * 所以这把锁不会把主线程或广播拖住。
     */
    @Synchronized
    fun render(
        context: Context,
        runtime: AppRuntime? = null,
        busy: String? = null,
        partial: Boolean = false,
    ) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        // 两个 component 各自查 id：桌面不会告诉我们「这个 id 是哪个尺寸的」，
        // 只能按 component 分开问，否则 5×2 那张卡永远轮不到重画。
        val byProvider = PROVIDERS.map { (provider, wide) ->
            wide to manager.getAppWidgetIds(ComponentName(context, provider)).toList()
        }
        if (byProvider.all { it.second.isEmpty() }) return
        // 传进来的 runtime 已经是刚刷完的那一份，别再 loadCachedStatus：
        // 磁盘缓存比内存里的新鲜值旧，重读一次会把刚拿到的车况又盖回去。
        val r = runtime ?: AppRuntime(context).also { it.loadCachedStatus() }
        val status = r.statusFlow.value
        // 右上角时间取「车况数据本身的时刻」，不是我们刚拉过请求的时刻：
        // 车机休眠时网关回的是旧报文，用拉取时刻会写成「刚刚」，
        // 用户会以为车况是新的（口径见 StatusFreshness）。
        val updatedAt = r.statusDataAtFlow.value
        val vin = r.session.selectedVin
        // 登录已失效（token 被服务端作废，比如账号在别的设备上被重新登录）时，
        // 车况缓存还在、VIN 还在，但任何指令都会失败。这里按「未登录」画：
        // 右上角明说、五颗按键改成「点一下打开 App 重新登录」——
        // 否则用户只能反复点、反复等 5 秒超时，还以为 App 坏了（2026-11-05 的反馈）。
        val sessionExpired = ControlFailure.isSessionExpired(context)
        val signedIn = vin.isNotBlank() && !sessionExpired
        val title = r.displayName()
        // 内存没有就翻磁盘那份（[WidgetCarImage]）—— 冷进程第一帧全靠它，
        // 只认内存的话车模会时有时无：开过 App 之后有、被系统回收之后就没了。
        val car = cachedCar(context, vin)
        // 增量更新不碰背景，所以三种情况必须退回全量：
        // ① 调用方要的就是全量；② 系统深浅色变了（背景色在静态部分）；
        // ③ 这个进程里还没全量画过（`lastFullNight == null`），桌面上可能是一张空卡。
        // 2026-11-16 再加一条：版式变了（含「默认值一次性迁移」把素纸换成全透明）。
        val night = WidgetStyle.isNight(context)
        val style = WidgetStyle.style(context)
        val usePartial = partial && lastFullNight == night && lastFullStyle == style
        byProvider.forEach { (wide, ids) ->
            ids.forEach { id ->
                val (widthDp, heightDp) = widgetSize(manager, id)
                runCatching {
                    if (usePartial) {
                        manager.partiallyUpdateAppWidget(
                            id,
                            buildPartialViews(
                                context = context,
                                status = status,
                                updatedAt = updatedAt,
                                busy = busy,
                                signedIn = signedIn,
                                title = title,
                                car = car,
                                heightDp = heightDp,
                                widthDp = widthDp,
                                // 增量更新必须用**同一份**布局，否则桌面按 id 找不到视图，
                                // 整帧被丢掉 —— 宽卡会一直显示旧车况。
                                wide = wide,
                                // 增量只改文案，但「未设控车密码」这行字必须在，
                                // 否则每次刷新都会把它盖回「12:30 更新」
                                pinReady = r.hasPin,
                                sessionExpired = sessionExpired,
                            ),
                        )
                    } else {
                        manager.updateAppWidget(
                            id,
                            buildViews(
                                context = context,
                                status = status,
                                updatedAt = updatedAt,
                                busy = busy,
                                signedIn = signedIn,
                                title = title,
                                car = car,
                                widthDp = widthDp,
                                heightDp = heightDp,
                                wide = wide,
                                // 没设控车密码 → 五颗按键改成「打开 App 去设置」，
                                // 否则用户点了之后只看到卡片闪一下，什么也没发生
                                pinReady = r.hasPin,
                                signInRequired = !signedIn,
                                sessionExpired = sessionExpired,
                            ),
                        )
                        lastFullNight = night
                        lastFullStyle = style
                    }
                }
            }
        }

        // 磁盘里也没有（第一次装、或者刚换登录账号）：后台补一张，补到就再画一遍。
        // 改卡片尺寸、桌面定时更新走的就是这条路径，以前它们只画不补，于是卡上一直缺车模。
        if (car == null && signedIn) fetchCarImageLater(context, r, vin)
    }

    /** 内存 → 磁盘。解码一张 440 宽的 PNG 是毫秒级的，而且一个进程最多只做一次。 */
    private fun cachedCar(context: Context, vin: String): Bitmap? {
        carCache?.let { if (!it.isRecycled && carCacheVin == vin) return it }
        if (vin.isBlank()) return null
        val fromDisk = WidgetCarImage.load(context, vin) ?: return null
        carCache = fromDisk
        carCacheVin = vin
        return fromDisk
    }

    /**
     * 车模图缺失时补一次。
     *
     * 按 VIN 限频：有些账号本来就没有官方定妆照（接口不给 shareBindUrl），
     * 每次重画都去问一遍没有意义，还会让每次桌面更新都多一次网络请求。
     */
    private fun fetchCarImageLater(context: Context, runtime: AppRuntime, vin: String) {
        if (vin.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_CAR, Context.MODE_PRIVATE)
        val key = "last_try_$vin"
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(key, 0L) < CAR_RETRY_MS) return
        prefs.edit().putLong(key, now).apply()
        carFetchScope.launch {
            val got = runCatching { ensureCar(context, runtime) }.getOrNull()
            // 没拿到就不用重画：卡片保持原样，等下一次（或用户点按键）再试
            if (got != null) runCatching { render(context, runtime) }
        }
    }

    /**
     * 取当前车辆的车模图（改过色优先），并缩到小组件够用的尺寸。
     * 官方接口返回 1000px 级别的大图，直接塞进 RemoteViews 会超 Binder 事务上限。
     *
     * 拿到之后**同时写进磁盘**（[WidgetCarImage]）：这张图是桌面卡片唯一的图像来源，
     * 进程一被回收内存就没了，不落盘的话下次唤醒又得从头取一遍。
     */
    suspend fun ensureCar(context: Context, runtime: AppRuntime): Bitmap? {
        val vin = runtime.session.selectedVin
        if (vin.isBlank()) return null
        carCache?.let { if (!it.isRecycled && carCacheVin == vin) return it }
        val raw = runtime.cachedDisplayCarBitmap()
            ?: runCatching { runtime.displayCarBitmap() }.getOrNull()
            ?: return null
        val scaled = scaleDown(raw, 440)
        carCache = scaled
        carCacheVin = vin
        WidgetCarImage.save(context, vin, scaled)
        return scaled
    }

    /** 换车 / 登出时清掉车模缓存（内存 + 磁盘），避免把上一辆车的图留在桌面上。 */
    fun clearCarCache(context: Context) {
        carCache = null
        carCacheVin = null
        WidgetCarImage.clearAll(context)
    }

    /**
     * 车模换了（刷新官方图 / 换色 / 相册导入 / 恢复官方）：丢掉旧图，重新取一张并让桌面重画。
     *
     * 只清内存是不够的 —— 磁盘那份缓存图会让桌面一直显示上一版车模，
     * 而且要等到进程被杀、重新取图才会变，看起来就像「换了没生效」。
     */
    fun refreshCarImage(context: Context, runtime: AppRuntime) {
        carCache = null
        carCacheVin = null
        WidgetCarImage.clear(context, runtime.session.selectedVin)
        carFetchScope.launch {
            runCatching { ensureCar(context, runtime) }
            runCatching { render(context, runtime) }
        }
    }

    // ---------------- RemoteViews 组装 ----------------

    /**
     * 一次渲染用的配色：浅色 / 深色 / 沉浸三套，全量与增量共用。
     *
     * 2026-11-20 起没有「状态提示高亮色」这个字段了：右上角那行文字和刷新键一律取
     * [ink]（字体颜色，默认白），用户要求跟字体颜色一起变。按键高亮仍单独走 [active]，
     * 改字色不会把五个按键一起染了。
     */
    private data class Palette(
        val ink: Int,
        val sub: Int,
        val warn: Int,
        val active: Int,
        val ev: Int,
        val fuel: Int,
    )

    private fun paletteFor(context: Context): Palette {
        val style = WidgetStyle.style(context)
        // 全透明：颜色全由用户定，跟系统深浅色没关系，先走这一支
        if (style == WidgetStyle.STYLE_T) return transparentPalette(context)

        val night = WidgetStyle.isNight(context)
        val glass = style == WidgetStyle.STYLE_D
        return Palette(
            ink = when {
                glass -> INK_GLASS
                night -> INK_DARK
                else -> INK_LIGHT
            },
            sub = when {
                glass -> SUB_GLASS
                night -> SUB_DARK
                else -> SUB_LIGHT
            },
            warn = when {
                glass -> WARN_GLASS
                night -> WARN_DARK
                else -> WARN_LIGHT
            },
            active = when {
                glass -> ACCENT_GLASS
                night -> ACCENT_DARK
                else -> ACCENT_LIGHT
            },
            // 续航那两行的小图标跟首页同色：电绿、油蓝。沉浸样式压在照片上，
            // 原色会被背景吃掉，所以单独提亮一档。
            ev = when {
                glass -> EV_GLASS
                night -> EV_DARK
                else -> EV_LIGHT
            },
            fuel = when {
                glass -> FUEL_GLASS
                night -> FUEL_DARK
                else -> FUEL_LIGHT
            },
        )
    }

    /**
     * 全透明主题的配色。
     *
     * 分工只有一条：**字色管文字与常态图标，控件色管按键高亮与分隔线**。
     * 两色都交给用户，是因为卡片本身没有底 —— 同一张卡放在深色照片和浅色壁纸上，
     * 想要「都看得清」只能让人自己挑。默认白字 + 零控绿高亮，和沉浸样式的层次一致。
     *
     * 副信息、警示色、电 / 油图标这些「派生色」不开放设置：
     * 一来说明里解释不清，二来真要摆在一起才好看，交给 [isLight] 按亮度挑亮暗两档即可。
     */
    private fun transparentPalette(context: Context): Palette {
        val ink = WidgetStyle.ink(context)
        val lightInk = WidgetStyle.isLight(ink)
        return Palette(
            ink = ink,
            sub = withAlpha(ink, 0.68f),
            warn = if (lightInk) WARN_T_LIGHT else WARN_T_DARK,
            active = WidgetStyle.ctrl(context),
            ev = if (lightInk) EV_T_LIGHT else EV_T_DARK,
            fuel = if (lightInk) FUEL_T_LIGHT else FUEL_T_DARK,
        )
    }

    /** 给颜色换一档透明度（保留 RGB）。 */
    private fun withAlpha(color: Int, factor: Float): Int {
        val alpha = (((color ushr 24) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    /** 五个按键的静态部分（格子底色、图标资源、留白、点击）在这三种样式下不一样。 */
    private fun cellBackgroundRes(context: Context): Int {
        // 用户在「个性化 → 桌面小组件 → 按键背景」里关掉圆角矩形时，退回只有水波的格子。
        // 直接借素纸那两份（`widget_cell_a` / `_dark` 本来就是无底色版）：
        // 它们只留 ripple + 10dp 圆角 mask，按下去仍有反馈，但不再画底和描边。
        if (!WidgetStyle.cellBgVisible(context)) return plainCellRes(context)
        return when (WidgetStyle.style(context)) {
            WidgetStyle.STYLE_D -> R.drawable.widget_cell_d
            // 透明主题：浅色字配深玻璃底，深色字配浅玻璃底，否则按键边界直接消失
            WidgetStyle.STYLE_T ->
                if (WidgetStyle.isLight(WidgetStyle.ink(context))) R.drawable.widget_cell_t
                else R.drawable.widget_cell_t_dark
            else -> if (WidgetStyle.isNight(context)) R.drawable.widget_cell_a_dark else R.drawable.widget_cell_a
        }
    }

    /**
     * 关掉按键底色后用的「空格子」：只有水波，没有底和描边。
     *
     * 水波颜色得跟着**格子实际压在什么底色上**走，不能只看系统深浅：
     * 沉浸样式永远压着压暗后的照片、透明样式看用户选的字色。
     * 深底配白水波、浅底配黑水波，否则点下去等于没反应。
     */
    private fun plainCellRes(context: Context): Int {
        val onDark = when (WidgetStyle.style(context)) {
            WidgetStyle.STYLE_D -> true
            WidgetStyle.STYLE_T -> WidgetStyle.isLight(WidgetStyle.ink(context))
            else -> WidgetStyle.isNight(context)
        }
        return if (onDark) R.drawable.widget_cell_a_dark else R.drawable.widget_cell_a
    }

    /**
     * 用哪份 RemoteViews 布局。
     *
     * 版式 × 尺寸 = 6 份（`widget_a/d/t` 与各自的 `_wide`）。不是偷懒才拆开：
     * RemoteViews 改不了 `layout_weight`（`setInt` 只认 @RemotableViewMethod），
     * 而 5×2 的宽出来那一格要分给车模与续航条，只能一个尺寸一份 XML。
     * 两份布局的 id 必须完全一致 —— 增量更新靠 id 定位。
     */
    internal fun layoutFor(style: String, wide: Boolean): Int = when (style) {
        WidgetStyle.STYLE_D -> if (wide) R.layout.widget_d_wide else R.layout.widget_d
        WidgetStyle.STYLE_T -> if (wide) R.layout.widget_t_wide else R.layout.widget_t
        else -> if (wide) R.layout.widget_a_wide else R.layout.widget_a
    }

    /**
     * **全量**：外壳 + 内边距 + 字号 + 所有内容。
     *
     * 只在结构真的变了的时候用：首次添加、改卡片尺寸、换版式、换壁纸、换车模。
     */
    internal fun buildViews(
        context: Context,
        status: CarStatus?,
        updatedAt: Long,
        busy: String?,
        signedIn: Boolean,
        title: String = DEFAULT_VEHICLE_TITLE,
        car: Bitmap? = null,
        widthDp: Int = 320,
        heightDp: Int = 150,
        /** true = 5×2 宽版（用 `widget_*_wide` 那份布局）。 */
        wide: Boolean = false,
        /** 控车密码是否已设置；没设置时五个按键改成「打开 App 去设置密码」。 */
        pinReady: Boolean = true,
        /** 登录已失效（token 被服务端作废）：五颗按键一律改成「打开 App 重新登录」。 */
        signInRequired: Boolean = false,
        /** 登录**失效**（而不是从没登录过）：只影响右上角那行字。 */
        sessionExpired: Boolean = false,
    ): RemoteViews {
        val style = WidgetStyle.style(context)
        val night = WidgetStyle.isNight(context)
        val glass = style == WidgetStyle.STYLE_D
        val clear = style == WidgetStyle.STYLE_T
        val p = paletteFor(context)

        val views = RemoteViews(context.packageName, layoutFor(style, wide))
        val density = context.resources.displayMetrics.density

        // ---- 外壳 ----
        // 沉浸样式的底必须是深色：万一图片没画上（桌面不认 URI），白字还得有底可压。
        // 全透明样式反过来 —— 布局里已经写成 transparent，这里再设一次卡片色就白做了。
        if (!clear) {
            views.setInt(
                R.id.wg_root,
                "setBackgroundResource",
                if (glass || night) R.drawable.widget_card_dark else R.drawable.widget_card_light,
            )
        }

        // ---- 沉浸样式的底图 ----
        if (glass) {
            val wallpaper = if (WidgetWallpaper.exists(context)) WidgetWallpaper.uri(context) else null
            // 走 URI：桌面进程自己按原图分辨率解码，绕开 setImageViewBitmap 的 1MB 上限
            if (wallpaper != null) applyImage(views, R.id.wg_bg, wallpaper) else
                views.setImageViewResource(R.id.wg_bg, R.drawable.widget_d_fallback)
        }

        // ---- 车标 ----
        // 车名前固定放一个车标（用户明确要一直显示，不再按「高级设置」是否解锁切方向盘）。
        // 它是纯色图形，浅色卡上必须染色，否则白字白底看不见。
        // 公开版换成不带品牌标识的方向盘（[PERSONAL_EDITION]）：直接用 App 里现成的
        // `login_logo_steering.png`（登录页同款），只裁掉四周留白、等比缩到 224×224，
        // 线条不重画也不加粗 —— 这样与登录页看到的是同一枚图形。
        //
        // **必须是位图（drawable-nodpi 的 PNG），不能换成矢量图**：上下两行是「先设图、再染色」，
        // 矢量 Drawable 跨进程交给桌面进程后，`setColorFilter` 在部分桌面上不生效，
        // 图形就保持自身的黑色 —— 深色版式 / 玻璃版式的卡片上等于没画（2026-11-15 用户反馈
        // 「公开版小组件不显示 logo」）。PNG 与 [R.drawable.ic_leap_logo] 走同一条已知可用的染色链路。
        //
        // 2026-11-16：用户在个性化里可以把这个车标整个关掉。关掉用 `GONE`（不占位），
        // 车名顺势往左挪 18dp + 1dp 贴住原来的左缘 —— 用 `INVISIBLE` 会留下一条空档。
        // 关掉时连图与染色都不用设：`GONE` 的视图不参与绘制，重新打开走的是全量重画。
        val logoVisible = WidgetStyle.logoVisible(context)
        views.setViewVisibility(R.id.wg_logo, if (logoVisible) View.VISIBLE else View.GONE)
        if (logoVisible) {
            views.setImageViewResource(
                R.id.wg_logo,
                if (PERSONAL_EDITION) R.drawable.ic_widget_logo_steering else R.drawable.ic_leap_logo,
            )
            views.setInt(R.id.wg_logo, "setColorFilter", p.ink)
        }

        // ---- 五个按键：静态部分 ----
        // 槽位 id / 图标 id 按位置固定（六份布局一致），图标、广播 action、中文标签按当前配置取。
        // 图标留白按**动作**给，不按槽位给：空调那个压缩机符号笔画密要收小、鸣笛喇叭横长形要
        // 少留边并顶高一点 —— 换了槽位也该保持各自那一套，否则并排一眼能看出不齐。
        val cellBg = cellBackgroundRes(context)
        // 没设控车密码时，这五颗按键点下去注定什么都不发生（指令层会直接抛 NeedPin）。
        // 与其弹一句很可能被 ROM 吞掉的 toast，不如让按键本身变成入口：
        // 点任意一颗都打开 App 并直接弹密码录入框 —— Activity 型的 PendingIntent
        // 由桌面发起，不受「后台启动 Activity」限制，比广播里 startActivity 可靠得多。
        // 登录失效同理（而且优先级更高）：先去重新登录，密码设了也没用。
        val navIntent = when {
            signInRequired -> openAppIntent(context, AppNavRequest.RELOGIN)
            !pinReady -> openAppIntent(context, AppNavRequest.OP_PASSWORD)
            else -> null
        }
        val buttons = WidgetButtonLayout.load(context)
        // 细节微调只改图标的 padding，格子（38 / 42dp）、weight 均分、分隔线和点击区全不动。
        // 宽版按键行高 42dp，缩放公式要用它自己的容器高度，否则同一倍率下两版大小不一致。
        val iconScale = WidgetStyle.iconScale(context)
        val cellDp = if (wide) WidgetMetrics.CELL_HEIGHT_WIDE_DP else WidgetMetrics.CELL_HEIGHT_DP
        buttons.forEachIndexed { index, id ->
            val cellId = CELL_IDS.getOrNull(index) ?: return@forEachIndexed
            val iconId = ICON_IDS.getOrNull(index) ?: return@forEachIndexed
            val basePad = when (id) {
                WidgetButtonId.AC -> BASE_ICON_PAD + AC_ICON_SHRINK
                WidgetButtonId.HORN -> (BASE_ICON_PAD - HORN_ICON_GROW).coerceAtLeast(0f)
                else -> BASE_ICON_PAD
            }
            val paddingDp = WidgetMetrics.iconPaddingDp(basePad, cellDp, iconScale)
            val liftDp = if (id == WidgetButtonId.HORN) {
                WidgetMetrics.liftDp(HORN_ICON_LIFT, iconScale, paddingDp)
            } else {
                0f
            }
            bindCellShell(
                views, cellId, iconId, id.iconRes,
                navIntent ?: broadcast(context, id.action, requestCodeFor(id)),
                cellBg, paddingDp, density, liftDp,
            )
            // XML 里写死的是默认五个的中文名，换了动作必须一起换掉（无障碍朗读）。
            views.setContentDescription(cellId, id.title)
            views.setContentDescription(iconId, id.title)
        }

        // 四条分隔线：先按开关定可见性（三套样式都能关），再给透明样式补线色。
        // 关掉用 GONE 而不是 INVISIBLE —— GONE 不占位，五个按键重新均分整行，
        // 否则每两条之间会留一道 1dp 的空缝，看着像线没擦干净。
        val dividers = WidgetStyle.dividersVisible(context)
        for (id in DIVIDER_IDS) {
            views.setViewVisibility(id, if (dividers) View.VISIBLE else View.GONE)
        }
        // 全透明样式的线色：A / D 的线色写在 XML 里，透明版得跟着「控件颜色」走。
        // 用 35% 透明度而不是实色 —— 实线在壁纸上会像裂缝，淡一点才像「分组」。
        if (clear && dividers) {
            val divider = withAlpha(p.active, 0.35f)
            for (id in DIVIDER_IDS) {
                runCatching { views.setInt(id, "setBackgroundColor", divider) }
            }
        }

        // 点文字区 / 车模都打开 App（车模不再兼做「换背景」按钮，那是设置页的事）
        // 登录失效时带上 RELOGIN：进去直接问「要不要重新登录」，而不是让人对着满屏报错自己找
        openAppIntent(context, if (signInRequired) AppNavRequest.RELOGIN else null)?.let {
            views.setOnClickPendingIntent(R.id.wg_info, it)
            views.setOnClickPendingIntent(R.id.wg_car_box, it)
        }

        // 右上角刷新键：只拉一次车况重画，不打开 App。
        // 点击区属于静态部分 —— 增量更新里不重设它，桌面上原有的点击才会一直有效。
        views.setOnClickPendingIntent(
            R.id.wg_refresh,
            broadcast(context, ACTION_REFRESH, RC_REFRESH),
        )

        // 字号先算出来：续航数字的「三格」宽度要按这一档字号现量，
        // 所以 bindLive 也得知道它（增量更新只跑 bindLive，字宽得靠这里带进去）
        val scale = textScale(widthDp, heightDp)
        val numDp = numberSizeDp(context, scale, wide)

        // ---- 所有会变的内容 ----
        bindLive(views, context, p, status, updatedAt, busy, signedIn, title, car, heightDp,
            numDp, pinReady, sessionExpired)

        // 字号跟尺寸走，是结构属性：只在全量里设
        applyTextScale(
            context = context,
            views = views,
            scale = scale,
            numDp = numDp,
        )
        // 进度条粗细同理：改的是槽的内边距，增量更新不碰它
        applyBarThickness(context = context, views = views)
        // 刷新键同理：格子恒 20dp，只收放图形四周的留白
        applyRefreshPadding(context = context, views = views)
        // 左上角车标同理：格子恒 18dp（布局里写死），只收放留白，1.0 时图形仍 14dp
        applyLogoPadding(context = context, views = views)
        return views
    }

    /**
     * **增量**：只含会变的那几项。
     *
     * 走 [AppWidgetManager.partiallyUpdateAppWidget]，桌面只应用这些动作：
     * 背景、壁纸、字号、留白、点击区一律不碰 —— 这正是免掉「刷新时闪一下」的关键。
     */
    internal fun buildPartialViews(
        context: Context,
        status: CarStatus?,
        updatedAt: Long,
        busy: String?,
        signedIn: Boolean,
        title: String = DEFAULT_VEHICLE_TITLE,
        car: Bitmap? = null,
        heightDp: Int = 150,
        /** 卡片当前宽度（dp）：续航数字的格宽要按它推出的字号现量。 */
        widthDp: Int = 320,
        /** true = 5×2 宽版。必须与上一次全量更新用的是同一份布局。 */
        wide: Boolean = false,
        pinReady: Boolean = true,
        /** 登录已失效：右上角改写「登录已失效」，比「点此登录」说得更清楚。 */
        sessionExpired: Boolean = false,
    ): RemoteViews {
        val style = WidgetStyle.style(context)
        val views = RemoteViews(context.packageName, layoutFor(style, wide))
        // 增量也要算字号：续航的「三格」宽度按它现量，字号不对两条一长一短
        val numDp = numberSizeDp(context, textScale(widthDp, heightDp), wide)
        bindLive(
            views, context, paletteFor(context), status, updatedAt, busy, signedIn,
            title, car, heightDp,
            numDp = numDp, pinReady = pinReady, sessionExpired = sessionExpired,
        )
        return views
    }

    /**
     * 右上角状态行前面那颗 13dp 小图标。
     *
     * 三件事一起做：给不给图（[WidgetStatusText.HintIcon.NONE] 就 GONE）、换哪张、染什么色。
     * **顺序不能反**：先 `setImageViewResource` 再 `setColorFilter` —— 反了某些桌面会拿
     * 上一次的滤镜去染新图，出现「已解锁却还配着满电电池的绿」这种串色。
     */
    private fun bindHintIcon(
        views: RemoteViews,
        line: WidgetStatusText.Line,
        color: Int,
    ) {
        val res = hintIconRes(line.icon)
        if (res == 0) {
            views.setViewVisibility(R.id.wg_hint_icon, View.GONE)
            return
        }
        views.setViewVisibility(R.id.wg_hint_icon, View.VISIBLE)
        views.setImageViewResource(R.id.wg_hint_icon, res)
        views.setInt(R.id.wg_hint_icon, "setColorFilter", color)
    }

    /**
     * 状态图标 → 资源。
     *
     * 载具类那 8 颗（解锁 / 上锁 / 后备箱 / 车窗 / 空调 / 预热 / 遮阳帘 / 哨兵）
     * **直接复用按键那套 `ic_qc_*`**：同一份官方线稿，描边 1.31，和旁边 10sp 的字一样细。
     * 2026-11-20 第一版曾把它们各复制一份、描边加粗到 2.3（怕 10dp 下糊），
     * 用户看过真机后要求「不要加粗」，那 8 个副本就删了 —— 别再复制回来。
     * 只有工程里原本没有的 5 颗（闪电 / 满格电池 / 对勾 / 感叹 / 人像）是 `ic_hint_*`。
     *
     * [WidgetStatusText.HintIcon.NONE] 返回 0，调用方据此把图标藏掉（更新时间那类提示）。
     */
    private fun hintIconRes(icon: WidgetStatusText.HintIcon): Int = when (icon) {
        WidgetStatusText.HintIcon.NONE -> 0
        WidgetStatusText.HintIcon.UNLOCK -> R.drawable.ic_qc_unlock
        WidgetStatusText.HintIcon.LOCK -> R.drawable.ic_qc_lock
        WidgetStatusText.HintIcon.TRUNK -> R.drawable.ic_qc_trunk
        WidgetStatusText.HintIcon.WINDOW -> R.drawable.ic_qc_window
        WidgetStatusText.HintIcon.AC -> R.drawable.ic_qc_ac
        WidgetStatusText.HintIcon.PREHEAT -> R.drawable.ic_qc_preheat
        WidgetStatusText.HintIcon.SUNSHADE -> R.drawable.ic_qc_sunshade
        WidgetStatusText.HintIcon.SENTRY -> R.drawable.ic_qc_sentry
        WidgetStatusText.HintIcon.CHARGE -> R.drawable.ic_hint_charge
        WidgetStatusText.HintIcon.CHARGED -> R.drawable.ic_hint_charged
        WidgetStatusText.HintIcon.OK -> R.drawable.ic_hint_ok
        WidgetStatusText.HintIcon.WARN -> R.drawable.ic_hint_warn
        WidgetStatusText.HintIcon.USER -> R.drawable.ic_hint_user
    }

    /**
     * 副厂方案下显示用的哨兵状态（取本地记录）；原厂返回 null = 照旧只看车况。
     *
     * 后加装的哨兵不会把「已开启」写进车端信号，车况里那一项要么读不到、要么恒为 false，
     * 所以副厂方案下这一行只能信本地（[SentryLocalState]），与按键点击判向同源。
     */
    private fun sentryOnOverride(context: Context): Boolean? =
        if (SentrySchemeStore.isAftermarket(context)) SentryLocalState.isOn(context) else null

    /**
     * 全量与增量共用的「会变的内容」：状态文案、续航数字与进度条、车模、按键高亮。
     *
     * 往这里加东西是安全的（两条路径都会生效）；**别把背景 / 字号 / 内边距 / 点击写进来**，
     * 它们属于 [buildViews] 的静态部分，增量更新里重复设置没有意义。
     */
    private fun bindLive(
        views: RemoteViews,
        context: Context,
        p: Palette,
        status: CarStatus?,
        updatedAt: Long,
        busy: String?,
        signedIn: Boolean,
        title: String,
        car: Bitmap?,
        heightDp: Int,
        /** 续航数字这一档的最终字号（dp），由 [numberSizeDp] 统一算好，见 [applyTextScale]。 */
        numDp: Float = NUM_SIZE_SP,
        pinReady: Boolean = true,
        sessionExpired: Boolean = false,
    ) {
        // ---- 车标 + 车名 ----
        views.setTextViewText(R.id.wg_title, title.ifBlank { DEFAULT_VEHICLE_TITLE })
        views.setTextColor(R.id.wg_title, p.ink)

        // ---- 状态 / 更新时间（卡片右上角）----
        val clock = if (updatedAt > 0L) {
            SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(updatedAt))
        } else {
            null
        }
        val flash = WidgetFlash.read(context)
        // 文案与色调都交给纯逻辑层：解锁 / 空调 / 后备箱 / 车窗 / 电池预热 / 遮阳帘 / 哨兵
        // 开着时要说清楚，充电中还要带剩余时长；118dp 装不下就按「第一条 · 等N项」折。
        // 高亮色只作用于这一行文字，按键图标仍用 p.active（见 Palette）。
        val line = WidgetStatusText.resolve(
            WidgetStatusText.Input(
                busy = busy,
                signedIn = signedIn,
                sessionExpired = sessionExpired,
                pinReady = pinReady,
                flashText = flash?.text,
                flashOk = flash?.ok ?: true,
                // 副厂哨兵：状态记在本地，车况里那个信号永远是空的（或恒 false）。
                // null = 原厂方案，照旧只看车况。
                state = when {
                    status != null -> WidgetStatusText.State(
                        locked = status.locked,
                        acOn = status.acOn,
                        trunkOpen = status.trunkOpen,
                        windowOpen = status.windowOpen,
                        batteryPreheatOn = status.batteryPreheatOn,
                        sunshadeOpen = status.sunshadeOpen,
                        sentryOn = sentryOnOverride(context) ?: status.sentryOn,
                        chargeState = status.chargeState,
                        charging = status.charging,
                        chargeRemainMinutes = status.chargeRemainMinutes,
                        chargeTimeLeft = status.chargeTimeLeft,
                    )
                    // 副厂方案刚开完哨兵、车况还没拉回来：不能因为整块车况是空的
                    // 就把「哨兵已开启」一起丢掉（那会退回「正在同步…」）
                    sentryOnOverride(context) == true -> WidgetStatusText.State(sentryOn = true)
                    else -> null
                },
                clock = clock,
                fullConfirmed = ChargeStatus.fullConfirmed(status?.chargeState, status?.soc),
            ),
        )
        views.setTextViewText(R.id.wg_time, line.text)
        // 文字与刷新键都取「字体颜色」（2026-11-20 用户要求，默认白）。
        // 只有异常类提示保留警示色 —— 跟「12:30 更新」一样白的话，出事了反而看不见。
        val statusColor = if (line.tone == WidgetStatusText.Tone.WARN) p.warn else p.ink
        views.setTextColor(R.id.wg_time, statusColor)
        bindHintIcon(views, line, statusColor)
        views.setInt(R.id.wg_refresh, "setColorFilter", p.ink)

        // ---- 续航：与首页续航区同一套口径（图标 · 里程 · 进度条 · 百分比）----
        // 纯电车型只有电一行、读的是「总续航」；增程车型电那一行读「纯电续航」，
        // 再补一行油 —— 跟首页 MileageRow 的取值规则一模一样，两边数字对得上。
        val reev = status?.isRangeExtender == true
        val soc = status?.soc?.coerceIn(0, 100)
        val fuelSoc = status?.fuelSoc?.coerceIn(0, 100)
        views.setInt(R.id.wg_ev_icon, "setColorFilter", p.ev)
        applyRangeText(
            views,
            id = R.id.wg_ev_value,
            raw = if (reev) status?.electricRange else status?.range,
            ink = p.ink,
        )
        views.setTextViewText(R.id.wg_ev_pct, soc?.let { "$it%" } ?: "--")
        views.setTextColor(R.id.wg_ev_pct, p.ink)
        // 条刻度 = 电量 / 油量水位：拿不到「满电续航」标称值（不同电池版本不一样），不编假刻度。
        // 电绿油蓝这两套颜色写在布局引用的 progressDrawable 里（widget_bar_ev / widget_bar_fuel），
        // 运行时**不去换 drawable** —— ProgressBar 没有 setProgressDrawable(int) 这个签名，
        // setInt 反射调用会直接失败。
        // 透明主题例外：它要按字色亮度挑亮条 / 暗条，所以在布局里放了两条、这里显示其中一条。
        val clear = WidgetStyle.style(context) == WidgetStyle.STYLE_T
        if (clear) {
            val lightBar = WidgetStyle.isLight(p.ink)
            views.setProgressBar(R.id.wg_ev_bar, 100, soc ?: 0, false)
            views.setProgressBar(R.id.wg_ev_bar_dark, 100, soc ?: 0, false)
            views.setViewVisibility(R.id.wg_ev_bar, if (lightBar) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.wg_ev_bar_dark, if (lightBar) View.GONE else View.VISIBLE)
        } else {
            views.setProgressBar(R.id.wg_ev_bar, 100, soc ?: 0, false)
        }

        // 两行里程加起来比一张 4×1 高得多，高度不够时只留电那一行。
        // 纯电车型本来就只有一行，降级之后结构不变，不会突然换一套版式。
        if (reev && heightDp >= 120) {
            views.setViewVisibility(R.id.wg_fuel_row, View.VISIBLE)
            views.setInt(R.id.wg_fuel_icon, "setColorFilter", p.fuel)
            applyRangeText(
                views,
                id = R.id.wg_fuel_value,
                raw = status?.fuelRange,
                ink = p.ink,
            )
            views.setTextViewText(R.id.wg_fuel_pct, fuelSoc?.let { "$it%" } ?: "--")
            views.setTextColor(R.id.wg_fuel_pct, p.ink)
            if (clear) {
                val lightBar = WidgetStyle.isLight(p.ink)
                views.setProgressBar(R.id.wg_fuel_bar, 100, fuelSoc ?: 0, false)
                views.setProgressBar(R.id.wg_fuel_bar_dark, 100, fuelSoc ?: 0, false)
                views.setViewVisibility(R.id.wg_fuel_bar, if (lightBar) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.wg_fuel_bar_dark, if (lightBar) View.GONE else View.VISIBLE)
            } else {
                views.setProgressBar(R.id.wg_fuel_bar, 100, fuelSoc ?: 0, false)
            }
        } else {
            views.setViewVisibility(R.id.wg_fuel_row, View.GONE)
        }

        // ---- 车模 ----
        if (car != null) {
            views.setViewVisibility(R.id.wg_car, View.VISIBLE)
            views.setImageViewBitmap(R.id.wg_car, car)
        } else {
            views.setViewVisibility(R.id.wg_car, View.GONE)
        }

        // ---- 五个按键的高亮 ----
        // 槽位现在是动态的：图标 id 按位置固定，该不该亮按**当前槽位放的是哪个动作**算。
        // 刚控过车（[WidgetFlash]）且车端已确认时，先按这次的结果（hint = 指令名）上色，
        // 右上角写着「空调已开启」的同一帧里空调键就该是亮的；
        // 新加的车窗 / 电池预热 / 遮阳帘也走同一条路，点完立刻有答复。
        val hint = flash?.takeIf { it.ok }?.hint
        val buttons = WidgetButtonLayout.load(context)
        val locked = status?.locked

        // 这次确认的指令让哪颗键亮 / 灭；null = 与该键无关，退回常态。
        fun hinted(id: WidgetButtonId): Boolean? = when (id) {
            WidgetButtonId.UNLOCK -> when (hint) {
                WidgetFlash.HINT_UNLOCK -> true
                WidgetFlash.HINT_LOCK -> false
                else -> null
            }
            WidgetButtonId.AC -> when (hint) {
                WidgetFlash.HINT_AC_ON -> true
                WidgetFlash.HINT_AC_OFF -> false
                else -> null
            }
            WidgetButtonId.TRUNK -> when (hint) {
                WidgetFlash.HINT_TRUNK_OPEN -> true
                WidgetFlash.HINT_TRUNK_CLOSE -> false
                else -> null
            }
            WidgetButtonId.WINDOW -> when (hint) {
                WidgetFlash.HINT_WINDOW_VENT, WidgetFlash.HINT_WINDOW_HALF,
                WidgetFlash.HINT_WINDOW_FULL -> true
                WidgetFlash.HINT_WINDOW_CLOSE -> false
                else -> null
            }
            WidgetButtonId.BATTERY_PREHEAT -> when (hint) {
                WidgetFlash.HINT_BATTERY_PREHEAT_ON -> true
                WidgetFlash.HINT_BATTERY_PREHEAT_OFF -> false
                else -> null
            }
            WidgetButtonId.SUNSHADE -> when (hint) {
                WidgetFlash.HINT_SUNSHADE_OPEN -> true
                WidgetFlash.HINT_SUNSHADE_CLOSE -> false
                else -> null
            }
            WidgetButtonId.SENTRY -> when (hint) {
                WidgetFlash.HINT_SENTRY_ON -> true
                WidgetFlash.HINT_SENTRY_OFF -> false
                else -> null
            }
            // 上锁键不亮（车多数时间锁着，常亮没有信息量）；鸣笛是瞬时动作，也不常亮。
            WidgetButtonId.LOCK, WidgetButtonId.HORN -> null
        }

        // 常态：车况读不到这个信号（部分车型恒为 null）时退回本地记住的开合态，
        // 判据和控制中心磁贴、按键点击方向是同一份（QuickTiles.state_<id>）。
        fun tileIdOf(id: WidgetButtonId): String? = when (id) {
            WidgetButtonId.AC -> "ac"
            WidgetButtonId.TRUNK -> "trunk"
            WidgetButtonId.WINDOW -> "window"
            WidgetButtonId.BATTERY_PREHEAT -> "batteryPreheat"
            WidgetButtonId.SUNSHADE -> "sunshade"
            // 不是磁贴，但 statusOn 里补了这份映射；本地态走 SentryLocalState
            WidgetButtonId.SENTRY -> "sentry"
            // 上锁键不亮（车多数时间锁着）、鸣笛是瞬时动作，都没有常驻开合态
            WidgetButtonId.UNLOCK, WidgetButtonId.LOCK, WidgetButtonId.HORN -> null
        }

        fun normallyOn(id: WidgetButtonId): Boolean {
            if (id == WidgetButtonId.UNLOCK) return locked == false
            // 哨兵没有对应磁贴：意图窗口内跟本地意图走，其余时候车况优先、本地兜底。
            // 判据与开关型磁贴完全一致，只是状态存在 SentryLocalState 里。
            if (id == WidgetButtonId.SENTRY) {
                val now = System.currentTimeMillis()
                if (WidgetTapRules.withinIntentWindow(SentryLocalState.lastActionAt(context), now)) {
                    return SentryLocalState.isOn(context)
                }
                // 副厂方案（后加装）车端报不出哨兵状态，只能以本地记录为准
                if (SentrySchemeStore.isAftermarket(context)) return SentryLocalState.isOn(context)
                return statusOn("sentry", status) ?: SentryLocalState.isOn(context)
            }
            val key = tileIdOf(id) ?: return false
            val spec = QuickTiles.byId(key)
            // 刚点过 8 秒内（[QuickTiles.ACTION_GRACE_MS]）：车况还没反映这次执行
            // （车端要 5~15 秒），按车况上色会把用户刚点亮的键又打回「灭」——
            // 用户以为没生效再点一下，正好落进 `execute` 开头那道「重复点击」拦截。
            // 所以这个窗口内高亮跟**本地意图**走；下一次成功的车况回读
            // （`QuickTiles.calibrateFromStatus`）会把本地态校回真实值。
            //
            // 车窗不参与：它的档位记在 `WindowControl` 里，`state_window` 不是它的真身，
            // 拿它上色反而会错，仍按车况走。
            if (key != "window" &&
                spec != null &&
                WidgetTapRules.withinIntentWindow(
                    QuickTiles.lastActionAt(context, spec),
                    System.currentTimeMillis(),
                )
            ) {
                return QuickTiles.isOn(context, spec)
            }
            // 车况优先、本地兜底：与磁贴的 statusOn 同一份映射，别在这里再写一套
            return statusOn(key, status) ?: spec?.let { QuickTiles.isOn(context, it) } ?: false
        }

        buttons.forEachIndexed { index, id ->
            val iconId = ICON_IDS.getOrNull(index) ?: return@forEachIndexed
            val on = hinted(id) ?: normallyOn(id)
            tintIcon(views, iconId, if (on) p.active else p.ink)
        }
    }

    /** 按键格的静态部分：底色、图标资源、留白、点击。 */
    private fun bindCellShell(
        views: RemoteViews,
        container: Int,
        icon: Int,
        iconRes: Int,
        pendingIntent: PendingIntent,
        cellBg: Int,
        paddingDp: Float,
        density: Float,
        liftDp: Float = 0f,
    ) {
        views.setInt(container, "setBackgroundResource", cellBg)
        views.setImageViewResource(icon, iconRes)
        // setViewPadding 收的是 px，不是 dp。下边多留 = 图形整体上移。
        val p = (paddingDp * density).toInt()
        val lift = (liftDp * density).toInt()
        views.setViewPadding(icon, p, (p - lift).coerceAtLeast(0), p, p + lift)
        views.setOnClickPendingIntent(container, pendingIntent)
    }

    /** 按键图标的高亮色（会随车况变，所以归在 [bindLive] 里）。 */
    private fun tintIcon(views: RemoteViews, icon: Int, tint: Int) {
        // setColorFilter(int) 是 ImageView 的公开方法：SRC_ATOP 只换颜色、保留抗锯齿透明度，
        // 线稿图标才不会发糊（setImageViewTintList 要 API 31+，这里得兼容到 26）。
        views.setInt(icon, "setColorFilter", tint)
    }

    /**
     * 按卡片实际尺寸放大 / 缩小字号。
     *
     * 宽度和高度各占一份权重：桌面只拉宽时字号长一点，只改高度时才主要影响字号 ——
     * 用长宽混合比单看一边更接近实际观感。上下限压住极端值：太小读不清，太大撑破卡片。
     */
    private fun textScale(widthDp: Int, heightDp: Int): Float {
        val byWidth = widthDp / BASE_WIDTH_DP
        val byHeight = heightDp / BASE_HEIGHT_DP
        return (byWidth * 0.55f + byHeight * 0.45f).coerceIn(0.82f, 1.28f)
    }

    /**
     * 把 [textScale] 算出的字号落到每个文本上。
     *
     * ## 为什么一律用 dp 落字号（用户 2026-11-08 反馈「续航和百分比不一样大」）
     * 续航和百分比本来是同一个 `13sp`。`sp` 会跟着系统字体再放大一次：
     * 系统字体一调到「大 / 特大」，这一行整体变宽，进度条被挤到几乎没有 ——
     * 桌面只会把放不下的那部分缩掉，「一样大」就变成「续航小一号」。
     * （截图实测续航只有百分比的 0.72 倍。）
     *
     * 用 `dp` 后字号只跟卡片尺寸走：两个数字永远同一档，宽度需求也是恒定的。
     * 小组件的尺寸由桌面网格决定，字号跟着系统字体走必然撑破版式 ——
     * 标题、时间同理，所以这里全部用 dp，卡片内部比例在任何字体设置下都不变。
     *
     * ## 用户可调的整体大小（设置页「信息区大小」）
     * 乘在**这三块**上：车名、右上角更新时间、续航行（里程 / 百分比）。
     * 进度条粗细不再单开开关，同样吃这个倍率（见 [applyBarThickness]）。
     * 图标里的位图确实改不了 LayoutParams（`setViewLayoutWidth / Height` 要 API 31），
     * 但**五个按键图标**是 `match_parent + fitCenter`，可以靠收放四边内边距调大小
     * （[WidgetMetrics.iconPaddingDp]）—— 容器不动，所以不会把行高顶出去。
     * 右上角刷新键同理，见 [applyRefreshPadding]；左上角车标见 [applyLogoPadding]；
     * 续航小图标仍固定。
     *
     * ## 数字还要过一道「实测宽度」
     * 里程是**一个** TextView（「195km」连写、贴左起排，与首页 [MileageRow] 同款），
     * 列宽 `wrap_content + minWidth`：两行的数字左缘对齐，条 `weight=1` 吃剩余，
     * 百分比 `minWidth` 且右对齐 —— 于是条等长、百分号也成一条竖线。
     *
     * 整列的预算宽度是 [WidgetMetrics.VALUE_COLUMN_WIDTH_DP]（宽版
     * [WidgetMetrics.VALUE_COLUMN_WIDTH_WIDE_DP]）：超过就把字号等比收回来（见 [numberSizeDp]），
     * 让进度条永远还有地方 —— 默认倍率不会被误伤，换字体也不会把条挤没。
     *
     * @param numDp 由 [numberSizeDp] 算好的数字字号，六处文字共用（任何一边单独调都会一大一小）
     */
    private fun applyTextScale(
        context: Context,
        views: RemoteViews,
        scale: Float,
        numDp: Float,
    ) {
        // 卡片尺寸系数 × 用户系数，再夹进可用范围：数字另有实测宽度兜底
        val merged = WidgetMetrics.textScale(scale, WidgetStyle.infoScale(context))
        val textScale = merged.coerceAtMost(WidgetMetrics.TEXT_MAX_SCALE)
        fun size(id: Int, sizeDp: Float) {
            runCatching { views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, sizeDp) }
        }
        size(R.id.wg_title, TITLE_SIZE_SP * textScale)
        // 右上角时间：仍在「信息区大小」之上，再叠一层它自己的微调倍率。
        // 状态行有 maxWidth=118dp + ellipsize=end + maxLines=1，字再大也只会省略号，
        // 不会把右端那颗刷新键挤出卡片（这正是「不影响排版」的兜底）。
        size(
            R.id.wg_time,
            WidgetMetrics.timeSizeDp(TIME_SIZE_SP, textScale, WidgetStyle.headerScale(context)),
        )
        // 续航和百分比必须共用同一组基准值：任何一边单独调，都会重现「两个数字不一样大」。
        // 里程是一个 TextView（「195km」连写，与首页 MileageRow 同款），单位也吃同一档字号 ——
        // km 比数字小一号会显得碎，两行的高度也会跟着不一致。
        size(R.id.wg_ev_value, numDp)
        size(R.id.wg_ev_pct, numDp)
        size(R.id.wg_fuel_value, numDp)
        size(R.id.wg_fuel_pct, numDp)
    }

    /**
     * 续航数字最终落地的字号（dp）。
     *
     * 卡片系数 × 用户系数，再按系统字体**实测宽度**收 —— 保证「888km」塞得进
     * 里程列的预算宽度（[WidgetMetrics.VALUE_COLUMN_WIDTH_DP]），这样进度条永远还有地方。
     * 数字、km、百分比都用这一档，三者才永远一样大。
     */
    private fun numberSizeDp(context: Context, scale: Float, wide: Boolean): Float =
        NUM_SIZE_SP * WidgetMetrics.numberSizeScale(
            merged = WidgetMetrics.textScale(scale, WidgetStyle.infoScale(context)),
            baseDp = NUM_SIZE_SP,
            limitDp = if (wide) WidgetMetrics.VALUE_COLUMN_WIDTH_WIDE_DP
            else WidgetMetrics.VALUE_COLUMN_WIDTH_DP,
            measureDp = { sizeDp -> measureTextDp(context, NUM_SAMPLE, sizeDp) },
        )

    /**
     * 用系统默认字体量一段文字有多宽（dp）。
     *
     * 必须与 `setTextViewTextSize(..., COMPLEX_UNIT_DIP, x)` 同一口径：
     * 那里 px = x × density（**不是** scaledDensity —— 我们故意绕开系统字体倍率，
     * 否则定宽槽在任何全局字号下都会重新崩），所以这里也按 density 换算。
     */
    private fun measureTextDp(context: Context, text: String, sizeDp: Float): Float {
        val density = context.resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = sizeDp * density
        return paint.measureText(text) / density
    }

    /**
     * 电量 / 油量进度条的粗细 = 9dp × 「信息区大小」倍率。
     *
     * 布局里条的槽位是 [WidgetMetrics.BAR_SLOT_DP]（12dp），这里给**槽**（不是条）加
     * 上下内边距，条作为 `match_parent` 的子视图跟着变矮，槽本身仍被行的
     * `gravity="center_vertical"` 居中 —— 于是粗细怎么调，条都在文字的中线上。
     *
     * 为什么不能直接给条加内边距（第一版就是这么写的，被用户截图打回）：
     * `ProgressBar` 把 drawable 收成 `高度 - paddingTop - paddingBottom` 之后，
     * 是从内容区**顶部**开始画的；对称内边距只让条往上缩，顶边不动，
     * 观感就是「条比文字高一截」，字号越大越明显。
     *
     * 之所以不用 `setViewLayoutHeight`：那是 API 31 才有的方法，本项目 minSdk 26，
     * 在 Android 8~11 上会直接无效（甚至抛 NoSuchMethod）。`setViewPadding` 各版本一致。
     *
     * 全透明版有亮 / 暗两条叠在同一个槽里（按字色亮度切显示），
     * 内边距加在槽上，两条自然同步，不会切一次配色粗细就变一次。
     */
    private fun applyBarThickness(context: Context, views: RemoteViews) {
        val pad = WidgetMetrics.barPaddingPx(
            thicknessDp = WidgetMetrics.barThicknessDp(WidgetStyle.infoScale(context)),
            density = context.resources.displayMetrics.density,
        )
        for (id in BAR_BOX_IDS) {
            runCatching { views.setViewPadding(id, 0, pad, 0, pad) }
        }
    }

    /** setImageViewUri 在部分桌面进程里会抛，兜住之后退回默认渐变，别整张卡变空白。 */
    private fun applyImage(views: RemoteViews, viewId: Int, uri: Uri) {
        val ok = runCatching { views.setImageViewUri(viewId, uri) }.isSuccess
        if (!ok) views.setImageViewResource(viewId, R.drawable.widget_d_fallback)
    }

    /**
     * 右上角刷新键的大小微调。
     *
     * **格子恒为 20dp**（六份布局写死），点击区也就恒为 20dp —— 只改 `ImageView`
     * 的四边内边距，让 `fitCenter` 画出 12~19dp 的图形。这样状态行的高度、
     * 文字与刷新键的间距、右上角整块的位置一个都不动，用户怎么拖都不会挤到车模。
     *
     * 与五个按键图标一样属于「结构属性」，只在全量渲染里设；增量更新只重设
     * 刷新键的颜色（`bindLive`），不会把这里算好的留白盖掉。
     */
    private fun applyRefreshPadding(context: Context, views: RemoteViews) {
        val pad = (
            WidgetMetrics.refreshPaddingDp(WidgetStyle.headerScale(context)) *
                context.resources.displayMetrics.density
            ).toInt()
        runCatching { views.setViewPadding(R.id.wg_refresh, pad, pad, pad, pad) }
    }

    /**
     * 左上角车标的大小微调（公开版是方向盘，完整版是车标本身）。
     *
     * 与按键图标、刷新键同一套办法：**格子固定 18dp**（六份布局写死），只收放四边留白，
     * 让 `fitCenter` 画出 11.2~17.5dp 的图形。1.0 时留白 0/2/4/2，图形正好 14dp、
     * 左缘贴住原来的位置 —— 升级后卡片与旧版逐像素一致。
     *
     * 左边为什么恒为 0：车标是标题行的起点，用户调大调小时它必须贴住原来的左基线长，
     * 否则车名会跟着左右晃（[WidgetMetrics.logoPad] 里写了这条）。
     *
     * 同样是「结构属性」，只在全量渲染里设；增量更新（[buildPartialViews]）不碰它，
     * 所以刷新车况不会把用户调好的大小打回去。
     */
    private fun applyLogoPadding(context: Context, views: RemoteViews) {
        val pad = WidgetMetrics.logoPad(WidgetStyle.logoScale(context))
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = (dp * density).toInt().coerceAtLeast(0)
        runCatching {
            views.setViewPadding(
                R.id.wg_logo,
                px(pad.leftDp), px(pad.topDp), px(pad.rightDp), px(pad.bottomDp),
            )
        }
    }

    /** 从 "316km" / "316" / "316 km" 里取出数字部分；取不到返回 null。 */
    private fun numberText(raw: String?): String? =
        raw?.let { Regex("\\d+").find(it)?.value }

    /**
     * 续航单元格里的那一行「316km」：数字自己拼单位。
     *
     * 直接拼接接口原文不行 —— 它有时带空格、有时已经带了 km，拼出来会变成「316km km」。
     * 取不到里程时只写「--」，不写「--km」（首页 [com.leapcontrol.app.ui.MileageRow]
     * 是拿原文拼的，缺值会显示「--km」；磁贴 / 小组件这一格更窄，去掉单位更干净）。
     */
    private fun kmText(raw: String?): String = numberText(raw)?.let { "${it}km" } ?: "--"

    /**
     * 续航里程写成一个 TextView：「195km」，数字与单位连写、贴列左缘起排。
     *
     * 这里绕了一圈才回到「一个 TextView」：2026-11-10 凌晨为了「92 和 195 两端对齐」，
     * 拆成「数字格 + km 格」再给两位数加字距，结果两位数中间空出一个数字宽，用户看图
     * 直接判「效果太差」，并要求「按照零控首页的效果来，续航靠左对齐」。
     *
     * 首页 [com.leapcontrol.app.ui.MileageRow] 的做法才是参照：**一个** 文字块写「195km」，
     * 左侧起排，后面接定宽进度条。这里照抄 —— 里程列 `minWidth`（46 / 52dp）让两行的
     * 起点对齐，条 `weight=1` 吃剩余、百分比右对齐，于是两条等长、百分号也成一条竖线，
     * 而数字自己保持自然间距，不拉字距、不拆格。
     *
     * `setLetterSpacing` 的复位不能省：桌面会复用同一个 RemoteViews 反复重画，
     * 上一版留下的字距要是没清掉，「195km」会一直是被拉开的模样。
     */
    private fun applyRangeText(views: RemoteViews, id: Int, raw: String?, ink: Int) {
        views.setTextViewText(id, kmText(raw))
        views.setTextColor(id, ink)
        runCatching { views.setFloat(id, "setLetterSpacing", 0f) }
    }

    /** 取卡片在桌面上的实际 dp 尺寸（取 min/max 中值，启动器两侧常不一致）。 */
    private fun widgetSize(manager: AppWidgetManager, id: Int): Pair<Int, Int> {
        val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            ?: return 320 to 150
        fun pick(minKey: String, maxKey: String, fallback: Int): Int {
            val min = options.getInt(minKey)
            val max = options.getInt(maxKey)
            return when {
                min > 0 && max > 0 -> (min + max) / 2
                min > 0 -> min
                max > 0 -> max
                else -> fallback
            }
        }
        val width = pick(
            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
            320,
        )
        val height = pick(
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            150,
        )
        return width to height
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, VehicleWidgetProvider::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context.applicationContext, requestCode, intent, flags)
    }

    private fun openAppIntent(context: Context, nav: String? = null): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return null
        if (nav != null) {
            // 复用已经在跑的那个实例，而不是再叠一层 Activity
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            launch.putExtra(MainActivity.EXTRA_NAV, nav)
        }
        return PendingIntent.getActivity(
            context.applicationContext,
            // 带目标的版本必须换一个 requestCode：FLAG_UPDATE_CURRENT 会把
            // 「打开 App」和「打开 App 并跳到密码页」当成同一个 PendingIntent 互相覆盖，
            // 结果就是点按键只打开了首页
            if (nav != null) RC_OPEN_APP_NAV else RC_OPEN_APP,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 等比缩到最长边 [maxWidth] 像素（Binder 事务 1MB 上限，留足余量）。 */
    private fun scaleDown(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width
        return Bitmap.createScaledBitmap(
            src,
            maxWidth,
            (src.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
