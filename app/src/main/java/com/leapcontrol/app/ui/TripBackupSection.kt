package com.leapcontrol.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.BuildConfig
import com.leapcontrol.app.trip.TripAutoBackup
import com.leapcontrol.app.trip.TripBackup
import com.leapcontrol.app.trip.TripTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 设置 →「行程轨迹」里的备份与迁移。
 *
 * 行程只存在本机（`filesDir/trip/trips.json`），卸载重装、换手机都会跟着没；
 * 以前退出登录还会整份删掉。这里给三个出口（与零记同一套逻辑）：
 *  - **每天自动备份**到 `Download/LeapControl/`：那个目录不属于 App，卸载也还在；
 *  - **导出**：走系统「保存到…」，位置自己挑；
 *  - **导入**：走系统「打开文件」，与本机**合并**，不覆盖已有记录。
 *
 * 全程不联网、不申请存储权限。⚠️ 备份文件里有完整行驶轨迹与停车位置，
 * 比零记的账本更敏感，文案里必须提醒别随手转发。
 */
@Composable
fun TripBackupRows(
    runtime: AppRuntime,
    onMessage: (String) -> Unit,
) {
    val C = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overview by runtime.tripFlow.collectAsState()
    var autoBackup by remember { mutableStateOf(TripAutoBackup.isEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    // 读完文件先不落盘，弹确认框让用户看清这份备份是什么
    var pending by remember { mutableStateOf<TripBackup.Payload?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var lastExport by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val text = TripTracker.exportBackup(context, BuildConfig.VERSION_NAME)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: throw IllegalStateException("写不进这个位置")
                }.exceptionOrNull()
            }
            busy = false
            if (failure == null) {
                val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))
                lastExport = stamp
                onMessage("行程数据已导出（$stamp）")
            } else {
                dialog = "导出失败：${UserMessage.of(failure, "无法写入所选位置")}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader().readText()
                    } ?: throw TripBackup.BackupException("打不开这个文件")
                    TripBackup.decode(text).getOrThrow()
                }
            }
            busy = false
            decoded
                .onSuccess { pending = it }
                .onFailure { dialog = UserMessage.of(it, "这个文件读不出来") }
        }
    }

    SwitchRow(
        label = "每天自动备份",
        desc = TripAutoBackup.summary(context),
        checked = autoBackup,
        onCheckedChange = { on ->
            autoBackup = on
            TripAutoBackup.setEnabled(context, on)
            if (on) {
                scope.launch {
                    val failure = withContext(Dispatchers.IO) {
                        TripTracker.backupToDownloadsNow(context).exceptionOrNull()
                    }
                    dialog = if (failure == null) {
                        "已写入 下载/LeapControl，之后每天自动更新一份。"
                    } else {
                        "立即备份失败：${UserMessage.of(failure, "无法写入下载目录")}"
                    }
                }
            }
        },
    )

    Spacer(Modifier.height(2.dp))

    ClickRow(
        title = "立即备份到下载目录",
        desc = if (busy) "处理中…" else "写一份完整行程到 下载/LeapControl，卸载重装也不丢",
        icon = Icons.Filled.FolderOpen,
    ) {
        if (busy) return@ClickRow
        busy = true
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                TripTracker.backupToDownloadsNow(context).exceptionOrNull()
            }
            busy = false
            dialog = if (failure == null) {
                "已写入 下载/LeapControl。这个目录不属于零控，卸载 App 不会删掉它。"
            } else {
                "备份失败：${UserMessage.of(failure, "无法写入下载目录")}"
            }
        }
    }

    Spacer(Modifier.height(2.dp))

    ClickRow(
        title = "导出行程",
        desc = when {
            busy -> "处理中…"
            lastExport != null -> "上次导出 $lastExport，再导一次会另存一个文件"
            else -> localSummary(overview)
        },
        icon = Icons.Filled.Save,
    ) {
        if (!busy) exportLauncher.launch(TripBackup.fileName())
    }

    Spacer(Modifier.height(2.dp))

    ClickRow(
        title = "导入行程",
        desc = if (busy) "处理中…" else "选一个备份文件，与本机行程合并，不覆盖已有记录",
        icon = Icons.Filled.Restore,
    ) {
        // 不同文件管理器给 .json 的 MIME 不一样，限定类型会让文件变灰选不中；
        // 一律放开，由 TripBackup.decode 的 kind 签名把关
        if (!busy) importLauncher.launch(arrayOf("*/*"))
    }

    Spacer(Modifier.height(10.dp))

    Text(
        "行程只存在这台手机上。换手机时：旧机「导出行程」→ 把文件带过去 → " +
            "新机装好零控后「导入行程」。\n" +
            "备份文件里有完整的行驶轨迹与停车位置，别随手发给别人。",
        color = C.textSecondary,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )

    // ---- 导入确认 ----
    pending?.let { payload ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            containerColor = C.panelBg,
            title = { Text("导入行程数据", color = C.textPrimary) },
            text = {
                Column {
                    Text(
                        TripBackup.describe(payload),
                        color = C.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "会合并进这台手机现有的行程，按每段的开始时刻去重，不会删掉已经记下的内容。",
                        color = C.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        pending = null
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching { TripTracker.importBackup(context, payload) }
                            }
                            busy = false
                            outcome
                                .onSuccess {
                                    val text = TripBackup.describe(it)
                                    dialog = text
                                    onMessage(text)
                                }
                                .onFailure { dialog = "导入失败：${UserMessage.of(it, "这个文件读不出来")}" }
                        }
                    },
                ) { Text(if (busy) "导入中…" else "导入", color = C.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text("取消", color = C.textSecondary)
                }
            },
        )
    }

    // ---- 结果 / 错误 ----
    dialog?.let { text ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = C.panelBg,
            title = { Text("行程数据", color = C.textPrimary) },
            text = {
                Text(text, color = C.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            },
            confirmButton = {
                TextButton(onClick = { dialog = null }) { Text("好", color = C.accent) }
            },
        )
    }
}

/** 导出按钮的副标题：当前这辆车现在有多少段行程可导。 */
private fun localSummary(overview: com.leapcontrol.app.trip.TripOverview?): String {
    val trips = overview?.trips.orEmpty()
    if (trips.isEmpty()) return "本机还没有已收尾的行程"
    val km = trips.sumOf { it.distanceKm }
    return "本车：" + trips.size + " 段 · " + String.format(Locale.CHINA, "%.1f", km) + " km"
}
