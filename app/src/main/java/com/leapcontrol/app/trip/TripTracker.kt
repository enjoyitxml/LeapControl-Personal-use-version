package com.leapcontrol.app.trip

import android.content.Context
import com.leapcontrol.app.AmapWebApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 行程采集的进程级入口。
 *
 * **开段只有一个入口**：[start]（用户点「开始记录」），自动开段方案已于 2026-11-12
 * 整个删除。采点由两条路径喂进来，口径完全一致（都走 `AppRuntime.refreshStatus`）：
 *  1. 用户在前台刷新车况 / 小组件刷车况（搭顺风车，不额外发请求）；
 *  2. 记录期间的后台定时采样（[TripSampling] / [TripRecordService]，用户设的间隔）。
 *
 * StateFlow 是进程级的，跨页返回不会丢数据。
 *
 * 起终点中文地址是唯一会额外联网的地方：一段行程收尾时，用用户自填的高德 Web Key
 * 对起终点各反查一次并缓存；没填 Key 或失败就保持「起点 / 终点」占位，
 * 绝不在行驶途中跟着坐标反复请求（那会吃额度、也没意义）。
 */
object TripTracker {
    private val _flow = MutableStateFlow<TripOverview?>(null)
    val flow: StateFlow<TripOverview?> = _flow.asStateFlow()

    /** 进程级 scope：地址补全不能挂在会被取消的页面协程上。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * 落盘专用 scope：**单线程**排队，保证写入顺序，同时把 JSON 序列化与文件读写挪出主线程。
     *
     * 之前每次开始 / 结束 / 删除 / 合并都在主线程 `TripStore.save()`：它要先把整份
     * trips.json 读回来、合并这一辆车、再整份写出去。90 天的采样点攒到几 MB 时，
     * 点「结束行程」要卡住几百毫秒到一两秒 —— 用户看到的就是「点了没反应」。
     * 内存里的 [_flow] 仍然是同步更新的，界面立刻切换；落盘慢一点不影响正确性。
     */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lock = Any()
    private var loadedVin: String? = null

    /** 老地址补全的互斥标记：App 启动和行程页可能同时触发，只跑一份。 */
    private val repairing = AtomicBoolean(false)

    /** 「要不要自动备份」这个判断本身的节流：每 30 秒最多看一次，避免每次落盘都读 prefs。 */
    @Volatile
    private var lastAutoBackupCheck = 0L

    private const val AUTO_BACKUP_CHECK_MS = 30_000L

    /** 老数据地址补全的节流：补不全的（Key 失效 / 没网）不该被后台采样反复触发。 */
    private const val REPAIR_PREFS = "trip_label_repair"
    private const val REPAIR_AT = "last_at"
    private const val REPAIR_INTERVAL_MS = 6 * 60 * 60_000L

    /** 异步落盘：只写 [_flow] 当前这份快照，调用方保证快照是最新的。 */
    private fun persist(context: Context, state: TripOverview) {
        storeScope.launch {
            runCatching { TripStore.save(context, state) }
            // 每天一次自动备份到下载目录。放在落盘之后、同一个单线程队列里：
            // 备份读到的必然是刚写下去的这一版，也不会和数据写入抢同一份文件。
            autoBackupIfDue(context)
        }
    }

    /**
     * 退出登录：只从内存里放下这辆车，**盘上的数据留着**。
     *
     * 用户 2026-11-09 反馈「每次退出登录行程就没了」—— 原来的 [clear] 会把
     * `trips.json` 整份删掉，登出再登录同一辆车就只剩空白。现在清的是内存，
     * 下次 [load] / [observe] 发现 `loadedVin` 变了就会重新读盘，数据自然回来。
     *
     * 行程仍然按 VIN 分车存：换账号登录另一辆车不会看到上一辆车的行踪。
     */
    fun detach() {
        synchronized(lock) {
            loadedVin = null
            _flow.value = TripOverview()
        }
    }


    fun load(context: Context, vin: String, batteryKwh: Double? = null) {
        synchronized(lock) {
            val current = _flow.value
            // 同一辆车、内存里已经有数据：只对齐容量，**不重读盘**。
            // 后台采样服务每轮都会构造一次 AppRuntime（其 init 又会调到这里），
            // 而落盘是异步队列 —— 重复读盘等于拿旧版覆盖内存，刚采到的点会消失。
            if (loadedVin == vin && current != null && current.vin == vin) {
                if (current.batteryKwh != batteryKwh) {
                    val next = TripRules.reprice(current.copy(batteryKwh = batteryKwh))
                    _flow.value = next
                    persist(context, next)
                }
                return
            }
            loadedVin = vin
            val stored = runCatching { TripStore.load(context, vin) }
                .getOrElse { TripOverview(vin) }
            // 容量存在数据文件里，是为了让「收尾那一刻用的是哪个容量」可追溯；
            // 用户改了设置就以设置为准，历史行程里的数字按新容量重算（容量是车的常量，
            // 改正它是纠错，不是篡改数据）。
            val merged = if (stored.batteryKwh == batteryKwh) stored
            else stored.copy(batteryKwh = batteryKwh)
            _flow.value = TripRules.reprice(merged)
        }
    }

    /**
     * 用户改了电池容量：立刻作用到进程内状态并落盘。
     *
     * 只改容量、没有新采样点时也要写盘 —— 否则下次读盘又把旧容量读回来，
     * 新填的度数永远算不出来。
     */
    fun setBatteryKwh(context: Context, kwh: Double?) {
        synchronized(lock) {
            val state = _flow.value ?: return
            if (state.batteryKwh == kwh) return
            // 填了容量就把历史里「有电量差但没度数」的段一起补算：用户填的是车的常量，
            // 那些段本来就该有度数，不该逼他重跑一趟。
            val next = TripRules.reprice(state.copy(batteryKwh = kwh))
            _flow.value = next
            persist(context, next)
        }
    }

    /**
     * **彻底删除**本机行程（含磁盘）。
     *
     * ⚠️ 退出登录不再走这里，走 [detach] —— 否则重新登录同一辆车，历史行程就没了。
     * 这个入口只留给「用户明确要求清空行程」的场景。
     */
    fun clear(context: Context) {
        synchronized(lock) {
            runCatching { TripStore.clear(context) }
            loadedVin = null
            _flow.value = TripOverview()
        }
    }

    // ---------------- 备份 / 迁移 ----------------
    //
    // 行程只存在本机（`filesDir/trip/trips.json`），卸载重装、换手机都会跟着没；
    // 退出登录以前还会整份删掉。这里给三个出口（与零记同一套逻辑）：
    //   1. 每天自动备份一份到公共下载目录（卸载也不丢）；
    //   2. 手动导出成一个 JSON，位置由用户自己挑；
    //   3. 导入时与本机**合并**，不覆盖已有记录。

    /**
     * 把本机**所有车辆**的行程打成一段 JSON。
     *
     * 内存里这份优先于磁盘：落盘是异步的，刚收尾一段就点导出时磁盘上可能还是上一版。
     */
    fun exportBackup(context: Context, appVersion: String, now: Long = System.currentTimeMillis()): String {
        val all = LinkedHashMap<String, TripOverview>()
        runCatching { TripStore.loadAll(context) }.getOrDefault(emptyMap()).forEach { (vin, state) ->
            all[vin] = state
        }
        _flow.value?.takeIf { it.vin.isNotBlank() }?.let { all[it.vin] = it }
        val vehicles = all.toSortedMap()
            .filterValues { it.trips.isNotEmpty() || it.open.isNotEmpty() }
            .map { (vin, state) -> TripBackup.Vehicle(vin, state) }
        return TripBackup.encode(
            TripBackup.Payload(exportedAt = now, appVersion = appVersion, vehicles = vehicles),
        )
    }

    /**
     * 导入一份备份，与**每辆车**本机已有的数据合并（规则见 [TripBackup.merge]）。
     *
     * 直接写盘、不走 [persist] 的异步队列：导入是用户等着结果的一次性操作，
     * 而且这里要一次处理多辆车。
     */
    fun importBackup(context: Context, payload: TripBackup.Payload): TripBackup.Summary {
        var newTrips = 0
        val writes = LinkedHashMap<String, TripOverview>()
        payload.vehicles.forEach { vehicle ->
            val isCurrent = vehicle.vin == loadedVin
            val local = if (isCurrent) {
                _flow.value?.takeIf { it.vin == vehicle.vin } ?: TripStore.load(context, vehicle.vin)
            } else {
                TripStore.load(context, vehicle.vin)
            }
            val merged = TripBackup.merge(local, vehicle.data)
            if (merged.trips.size == local.trips.size && merged.open == local.open) return@forEach
            newTrips += merged.trips.size - local.trips.size
            writes[vehicle.vin] = merged
            if (isCurrent) _flow.value = merged
        }
        writes.forEach { (vin, state) -> runCatching { TripStore.save(context, state) } }
        return TripBackup.Summary(vehicles = payload.vehicles.size, newTrips = newTrips)
    }

    private fun autoBackupIfDue(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoBackupCheck < AUTO_BACKUP_CHECK_MS) return
        lastAutoBackupCheck = now
        runCatching {
            if (!TripAutoBackup.isEnabled(context)) return
            if (!TripAutoBackup.isDue(context, now)) return
            // 没有任何行程时不写：一个空备份只会让「上次备份」看起来正常
            if ((_flow.value?.trips?.isEmpty() ?: true) && TripStore.loadAll(context).isEmpty()) return
            val text = exportBackup(context, com.leapcontrol.app.BuildConfig.VERSION_NAME, now)
            TripAutoBackup.write(context, text, now)
        }
    }

    /** 手动触发一次备份（设置页的「立即备份到下载目录」）。 */
    fun backupToDownloadsNow(context: Context): Result<Unit> = runCatching {
        val text = exportBackup(context, com.leapcontrol.app.BuildConfig.VERSION_NAME)
        TripAutoBackup.write(context, text, System.currentTimeMillis())
    }

    /**
     * 吃进一帧车况采样。**不会开段**：开段只有 [start]（用户点「开始记录」）。
     *
     * @param cloudKwh 当前云端累计能耗（kWh）：开段时存基准、收尾时做差。拿不到传 null。
     */
    fun observe(
        context: Context,
        vin: String,
        amapKey: String,
        batteryKwh: Double?,
        sample: TripSample,
        cloudKwh: Double? = null,
    ) {
        if (vin.isBlank()) return
        var justClosed: TripRecord? = null
        synchronized(lock) {
            if (loadedVin != vin) {
                loadedVin = vin
                _flow.value = runCatching { TripStore.load(context, vin) }
                    .getOrElse { TripOverview(vin) }
            }
            val stored = _flow.value ?: TripOverview(vin)
            // 设置里改了电池容量要能立刻生效：只改容量、没有新采样点时也得落盘，
            // 否则下次读盘又把旧容量读回来，kWh 永远按旧值算。
            val before = if (stored.batteryKwh == batteryKwh) stored
            else TripRules.reprice(stored.copy(batteryKwh = batteryKwh))
            val capacityChanged = before !== stored
            val known = before.trips.mapTo(HashSet()) { it.id }
            val next = TripRules.prune(
                TripRules.ingest(before, sample, cloudKwh),
                sample.at,
            )
            if (next == before && !capacityChanged) return
            _flow.value = next
            persist(context, next)
            justClosed = next.trips.lastOrNull()?.takeIf { it.id !in known }
        }
        val closed = justClosed ?: return
        // 后台这一帧刚好把行程收尾了（锁车 / 驻车满 5 分钟）：采样的服务与闹钟都该停，
        // 否则会继续按间隔唤醒车机，白耗小电瓶。
        TripSampling.stop(context)
        if (amapKey.isBlank()) return
        scope.launch { attachLabels(context, vin, amapKey, closed) }
    }

    /**
     * 用户手动点「开始行程」。
     *
     * 行程只由用户开始（自动判据会被车端的挡位噪声骗到，一打开 App 就「正在记录」）；
     * 结束仍是手动 / 锁车 / 停车宽限三路兜底。开段立刻落盘：用户点完就把 App 切走，
     * 回来还得是「正在记录」。
     */
    fun start(
        context: Context,
        vin: String,
        sample: TripSample,
        cloudKwh: Double? = null,
    ): TripStartResult {
        if (vin.isBlank()) return TripStartResult.NO_STATUS
        var result = TripStartResult.NO_STATUS
        synchronized(lock) {
            if (loadedVin != vin) {
                loadedVin = vin
                _flow.value = runCatching { TripStore.load(context, vin) }
                    .getOrElse { TripOverview(vin) }
            }
            val state = _flow.value ?: TripOverview(vin)
            val (next, r) = TripRules.start(state, sample, cloudKwh)
            result = r
            if (r == TripStartResult.STARTED) {
                _flow.value = next
                persist(context, next)
            }
        }
        // 开段成功就把后台采样接上：这是「点完就能把 App 收起来」的关键一步。
        if (result == TripStartResult.STARTED) TripSampling.begin(context, vin)
        return result
    }

    /**
     * 用户手动点「结束行程」。
     *
     * 只收尾、不联网拉车况：需要的点已经在 `open` 里了，再拉一次反而可能把停车帧
     * 追加进去。同步写盘并刷新 StateFlow，首页卡和二级页会同时切到「已结束」视图。
     * 失败（点太少 / 太短 / 算不出里程）时**保留 open**，由 UI 给具体原因。
     */
    fun finishCurrent(
        context: Context,
        vin: String,
        amapKey: String,
        now: Long = System.currentTimeMillis(),
        cloudKwh: Double? = null,
    ): TripCloseResult {
        if (vin.isBlank()) return TripCloseResult.NO_OPEN
        var result = TripCloseResult.NO_OPEN
        var closed: TripRecord? = null
        synchronized(lock) {
            if (loadedVin != vin) {
                loadedVin = vin
                _flow.value = runCatching { TripStore.load(context, vin) }
                    .getOrElse { TripOverview(vin) }
            }
            val state = _flow.value ?: TripOverview(vin)
            val (next, r) = TripRules.closeOpen(state, now, cloudKwh)
            result = r
            if (r == TripCloseResult.SAVED) {
                _flow.value = next
                persist(context, next)
                closed = next.trips.lastOrNull()
            }
        }
        val record = closed
        if (record != null) {
            // 这一趟结束了：后台采样立刻停（含闹钟），别再唤醒车机
            TripSampling.stop(context)
            if (amapKey.isNotBlank()) scope.launch { attachLabels(context, vin, amapKey, record) }
        }
        return result
    }

    /**
     * 手动暂停当前行程。
     *
     * 暂停会把已采到的点结清成一段：此后一帧都不采，暂停期间车再跑也不计入。
     * 返回值必须有明确反馈（[TripPauseResult]），不能点了没反应。
     */
    fun pause(context: Context, vin: String): TripPauseResult {
        if (vin.isBlank()) return TripPauseResult.NO_TRIP
        var result = TripPauseResult.NO_TRIP
        synchronized(lock) {
            if (loadedVin != vin) {
                loadedVin = vin
                _flow.value = runCatching { TripStore.load(context, vin) }
                    .getOrElse { TripOverview(vin) }
            }
            val state = _flow.value ?: return TripPauseResult.NO_TRIP
            val (next, r) = TripRules.pause(state, System.currentTimeMillis())
            result = r
            if (r == TripPauseResult.PAUSED) {
                _flow.value = next
                persist(context, next)
            }
        }
        // 暂停期间一帧都不采，后台采样也没必要醒着
        if (result == TripPauseResult.PAUSED) TripSampling.stop(context)
        return result
    }

    /**
     * 手动恢复被暂停的行程。
     *
     * @param sample 当前这一帧车况，作为新段的起点；null 时退回 [TripOverview.lastSample]
     */
    fun resume(context: Context, vin: String, sample: TripSample? = null): TripResumeResult {
        if (vin.isBlank()) return TripResumeResult.NO_STATUS
        var result = TripResumeResult.NOT_PAUSED
        synchronized(lock) {
            if (loadedVin != vin) {
                loadedVin = vin
                _flow.value = runCatching { TripStore.load(context, vin) }
                    .getOrElse { TripOverview(vin) }
            }
            val state = _flow.value ?: return TripResumeResult.NOT_PAUSED
            val (next, r) = TripRules.resume(state, sample)
            result = r
            if (r == TripResumeResult.RESUMED) {
                _flow.value = next
                persist(context, next)
            }
        }
        // 恢复记录 = 重新开始后台采样
        if (result == TripResumeResult.RESUMED) TripSampling.begin(context, vin)
        return result
    }

    /**
     * 用户确认「这趟不要了」：清掉进行中的一段，不写历史。
     *
     * 只服务「不到 30 秒 / 车机没读到位移」这种本来就建不了档的段 —— 用户点了结束
     * 就必须真的结束，不能因为「不够建档门槛」而一直卡在记录中。
     * 返回 false = 当前没有进行中的行程（可能已经被锁车自动收尾了）。
     */
    fun discardCurrent(context: Context, vin: String): Boolean {
        if (vin.isBlank()) return false
        var dropped = false
        synchronized(lock) {
            if (loadedVin != vin) {
                loadedVin = vin
                _flow.value = runCatching { TripStore.load(context, vin) }
                    .getOrElse { TripOverview(vin) }
            }
            val state = _flow.value ?: TripOverview(vin)
            if (state.open.isEmpty()) return false
            val next = TripRules.discardOpen(state)
            _flow.value = next
            persist(context, next)
            dropped = true
        }
        if (dropped) TripSampling.stop(context)
        return dropped
    }

    /**
     * 删除一段历史行程。**只删已收尾的段**，进行中的那段不在清单里，删不到。
     * 返回 false = 没找到（已被 90 天裁剪 / 换了车），UI 不该说「已删除」。
     */
    fun delete(context: Context, vin: String, id: Long): Boolean {
        if (vin.isBlank()) return false
        synchronized(lock) {
            val state = _flow.value ?: return false
            if (state.vin != vin) return false
            val next = TripRules.delete(state, id)
            if (next === state) return false
            _flow.value = next
            persist(context, next)
        }
        return true
    }

    /**
     * 保存用户手工涂抹修正的显示路线；传 null = 「恢复原状」，退回自动规划路线。
     *
     * 只有这一层覆盖被改写，`points` / 里程 / 时长 / 能耗一律不动。
     * 走的还是 `_flow` + 单线程落盘那条链路，跟删除 / 合并一样，界面立刻见效。
     */
    fun setRouteFix(
        context: Context,
        vin: String,
        id: Long,
        points: List<TripFixPoint>?,
    ): Boolean {
        if (vin.isBlank()) return false
        val fix = TripRouteFix.normalize(points)
        if (points != null && fix == null) return false // 画出来的点不合法，一个字都不写
        synchronized(lock) {
            val state = _flow.value ?: return false
            if (state.vin != vin || state.trips.none { it.id == id }) return false
            val next = state.copy(
                trips = state.trips.map { trip ->
                    if (trip.id == id) trip.copy(routeFix = fix) else trip
                },
            )
            _flow.value = next
            persist(context, next)
        }
        return true
    }

    /**
     * 把用户勾选的几段合并成一段。
     *
     * 合并后起点沿用最早那段，所以起终点地址大概率已经在；缺的话顺手补一次
     * （终点换成了最后一段的终点，本来就可能是新地址）。
     */
    fun merge(
        context: Context,
        vin: String,
        amapKey: String,
        ids: Collection<Long>,
    ): TripMergeResult {
        if (vin.isBlank()) return TripMergeResult.NOT_FOUND
        var result = TripMergeResult.NOT_FOUND
        var merged: TripRecord? = null
        synchronized(lock) {
            val state = _flow.value ?: return TripMergeResult.NOT_FOUND
            if (state.vin != vin) return TripMergeResult.NOT_FOUND
            // 合并后的 id 是最早那段的 id，先记下来，等下从这个位置取回结果去补地址
            val targetId = state.trips.filter { it.id in ids }
                .minByOrNull { it.startAt }?.id
            val (next, r) = TripRules.merge(state, ids)
            result = r
            if (r == TripMergeResult.MERGED) {
                _flow.value = next
                persist(context, next)
                merged = next.trips.firstOrNull { it.id == targetId }
            }
        }
        val record = merged
        if (record != null && amapKey.isNotBlank()) {
            scope.launch { attachLabels(context, vin, amapKey, record) }
        }
        return result
    }

    private fun attachLabels(context: Context, vin: String, key: String, record: TripRecord) {
        if (!needsLabel(record.startLabel) && !needsLabel(record.endLabel)) return
        val start = reverseLabel(key, record.startLat, record.startLng)
        val end = reverseLabel(key, record.endLat, record.endLng)
        if (start == null && end == null) return
        synchronized(lock) {
            val state = _flow.value ?: return@synchronized
            if (state.vin != vin) return@synchronized
            val updated = state.copy(
                trips = state.trips.map { trip ->
                    if (trip.id != record.id) trip
                    else trip.copy(
                        startLabel = trip.startLabel.takeUnless { needsLabel(it) } ?: start,
                        endLabel = trip.endLabel.takeUnless { needsLabel(it) } ?: end,
                    )
                },
            )
            _flow.value = updated
            persist(context, updated)
        }
    }

    /**
     * 老数据补全：2026-11-08 之前的版本把地址存成「14 个字 + …」，地名正好被切掉。
     *
     * 坐标还在，重新反查一次就能补全，不用用户重跑一趟。只在打开 App / 行程页时跑，
     * 每次最多 [limit] 段（每段最多 2 次请求）；补完就不会再命中，等于一次性迁移。
     */
    fun repairTruncatedLabels(
        context: Context,
        vin: String,
        amapKey: String,
        limit: Int = 6,
    ) {
        if (vin.isBlank() || amapKey.isBlank()) return
        // 多个入口（App 启动 / 行程页 / 后台采样服务）会同时触发，只让第一个真正干活
        if (!repairing.compareAndSet(false, true)) return
        // 节流：后台采样每 45 秒会构造一次 AppRuntime，其 init 又会走到这里。
        // 补不全的老数据（Key 失效 / 没网）会一直「缺」，不节流就是每 45 秒打 12 次高德请求。
        val prefs = context.getSharedPreferences(REPAIR_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(REPAIR_AT, 0L)
        if (System.currentTimeMillis() - last < REPAIR_INTERVAL_MS) {
            repairing.set(false)
            return
        }
        prefs.edit().putLong(REPAIR_AT, System.currentTimeMillis()).apply()
        scope.launch {
            try {
                val targets = _flow.value?.takeIf { it.vin == vin }?.trips
                    ?.filter { needsLabel(it.startLabel) || needsLabel(it.endLabel) }
                    ?.take(limit)
                    .orEmpty()
                if (targets.isEmpty()) return@launch
                // 先把地址取回来（联网），再一次性改内存 + 落盘，不让界面看到中间态
                val fresh = targets.associate { trip ->
                    trip.id to (
                        reverseLabel(amapKey, trip.startLat, trip.startLng) to
                            reverseLabel(amapKey, trip.endLat, trip.endLng)
                        )
                }
                if (fresh.values.all { it.first == null && it.second == null }) return@launch
                synchronized(lock) {
                    val state = _flow.value ?: return@synchronized
                    if (state.vin != vin) return@synchronized
                    val updated = state.copy(
                        trips = state.trips.map { trip ->
                            val pair = fresh[trip.id] ?: return@map trip
                            trip.copy(
                                startLabel = pair.first ?: trip.startLabel,
                                endLabel = pair.second ?: trip.endLabel,
                            )
                        },
                    )
                    _flow.value = updated
                    persist(context, updated)
                }
            } finally {
                repairing.set(false)
            }
        }
    }

    /** 没有地址、或者是被旧版本硬切成「…」结尾的，都算缺。 */
    private fun needsLabel(label: String?): Boolean =
        label == null || label.endsWith("…")

    private fun reverseLabel(key: String, lat: Double?, lng: Double?): String? =
        usable(lat, lng)?.let { (la, ln) ->
            runCatching { AmapWebApi.reverseGeocode(key, la, ln)?.formatted }.getOrNull()
        }?.let { TripFormat.placeText(it) }.takeUnless { it.isNullOrBlank() }

    private fun usable(lat: Double?, lng: Double?): Pair<Double, Double>? {
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
        if (lat == 0.0 && lng == 0.0) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }
}
