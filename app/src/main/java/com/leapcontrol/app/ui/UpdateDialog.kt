package com.leapcontrol.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leapcontrol.app.AppUpdateInfo
import com.leapcontrol.app.R

/**
 * 说明区在大屏上的高度软上限：再长就在框内自己滚。
 *
 * 真正的高度上限由弹窗可用高度决定 —— 说明区带 `weight(1f, fill = false)`，
 * 只能吃「弹窗高度 − 标题 − 底部按钮」剩下的那部分（见 [UpdateDialog] 里的 maxCardHeight），
 * 所以这个 360dp 只负责大屏上别把弹窗撑成一整页：约 20 行（11sp / 17sp 行高），
 * 常见 Release 正文基本一屏看完，再长的部分框内滚动。
 */
private val NOTES_MAX_HEIGHT = 360.dp

/**
 * 版本更新提醒弹窗：检测到 GitHub 有新版本时弹出。
 * - 「去下载」：App 内下载安装包，下载中可最小化到悬浮条，不影响继续用车；
 * - 「稍后」：关闭弹窗，下次启动仍会提醒（未忽略该版本）；
 * - 「不再提醒此版本」：记下该版本，之后不再自动弹窗（设置页红点仍保留）；
 * - 「更新说明」：**默认整段展开**，不再折叠、也不再有「展开 / 收起」开关
 *   （2026-11-10 用户要求「默认展开，不再折叠」）。
 *
 * ## 没有「去 GitHub 看看」入口
 * 仓库已转私有（令牌只够 App 内调 API 下载），浏览器打开 Release 页会 404，
 * 所以 2026-11-10 起下掉了 GitHub 按钮与所有「去 GitHub 下载」的文案，
 * 弹窗只留 App 内这一条下载通道。
 *
 * ## 展开但按钮不能被顶走
 * Release 正文动辄二三十行，整段摊开很容易把「去下载 / 稍后 / 不再提醒」挤出屏幕。
 * 所以这里不是「无脑摊开」，而是两层约束叠起来：
 * 1. 弹窗整体 `heightIn(max = 可用高度 × 0.92)`，永远留出上下边距、不顶到状态栏/导航栏；
 * 2. 说明区 `weight(1f, fill = false)` —— 先给标题、提示行、四个按钮量好高度，
 *    **剩下的**才分给说明区，超出的部分在说明区内部滚动。
 * 于是按钮永远在弹窗底部看得见，说明再长也只是自己滚，不会把按钮挤走。
 */
@Composable
fun UpdateDialog(
    info: AppUpdateInfo,
    currentVersion: String,
    onOpenDownload: () -> Unit,
    onDismiss: () -> Unit,
    onNeverAgain: () -> Unit,
    /**
     * 最小化到悬浮条：弹窗收起，下载继续。
     *
     * **不给默认值**：以前这里是 `{}`，设置页那份漏传后「—」点了毫无反应且编译不报错
     * （2026-11-10 用户反馈），现在漏传直接编译失败。
     */
    onMinimize: () -> Unit,
    /** 正在 App 内下载安装包：按钮锁住，显示进度。 */
    downloading: Boolean = false,
    /** 下载进度 0f..1f；null 表示总长未知。 */
    progress: Float? = null,
    /** 下载失败的提示文案。 */
    downloadError: String? = null,
) {
    val C = LocalAppColors.current
    val notesScroll = rememberScrollState()
    // 半透明遮罩 + 居中卡片；点遮罩 = 稍后
    // 用 BoxWithConstraints 是为了拿「可用高度」：说明区能吃多少要按它算，
    // 屏幕矮（横屏 / 小屏）时说明区自动少占，按钮才不会被顶出屏幕。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = DIALOG_SCRIM))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        // 弹窗最高占可用高度的 92%：剩下 8% 是上下留白，不会贴到状态栏 / 导航栏。
        // Column 有了这个上限，说明区的 weight 才能算出「剩多少给它」。
        // 父容器高度无界时（真出现这种情况）退回 560dp，否则 weight 分不到空间，按钮会被顶出去。
        val maxCardHeight = if (maxHeight.value.isInfinite()) 560.dp else maxHeight * 0.92f
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = C.dialogBg,
            shadowElevation = 20.dp,
            // 卡片自身消费点击，避免穿透到遮罩误关
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .heightIn(max = maxCardHeight)
                .clickable(onClick = { }),
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_system_update),
                        contentDescription = null,
                        tint = C.accent,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(22.dp),
                    )
                    Text(
                        text = "发现新版本 v${info.version}",
                        color = C.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    // 最小化：下载中可以边下边用，不占着整屏。
                    // 点击区 34dp 包着 28dp 的视觉块 —— 原来拿 28dp 既当视觉又当点击区，
                    // 手指常点空；现在视觉不变、可点范围大一圈。
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onMinimize),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(C.chipBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "—",
                                color = C.textSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "当前版本 v$currentVersion。已有新版本发布，点「去下载」即可在 App 内直接安装更新。",
                    color = C.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )

                // ---- 更新说明：默认整段展开，长了自己在框内滚 ----
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            // fill = false：内容短就按内容高，不硬撑到上限（弹窗会跟着变矮）；
                            // 内容长则最多吃「弹窗剩下的那部分」，再多就在里面滚 ——
                            // 这一条就是「按钮永远看得见」的保证。
                            .weight(1f, fill = false)
                            .heightIn(max = NOTES_MAX_HEIGHT)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(C.chipBg)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "更新说明",
                            color = C.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = plainNotes(info.notes),
                            color = C.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            // 不再设 maxLines / 不再给「展开」开关：默认就是全部内容。
                            // 装不下由外层限高 + 这里滚动解决，绝不靠截断文字。
                            modifier = Modifier.verticalScroll(notesScroll),
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                // ---- 下载通道说明 ----
                // 安装包还放在 GitHub Releases，但仓库是私有的，只能由 App 带令牌下载，
                // 所以这里只说明「慢不是 App 坏了」，不再提 GitHub、也不挂任何外部入口。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = C.textSecondary.copy(alpha = 0.85f),
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "安装包在 App 内直接下载，无需跳转浏览器。下载缓慢或失败通常是网络问题，切换网络后重试即可。",
                        color = C.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 下载进度 / 失败提示：只在走 App 内下载时出现
                if (downloading || downloadError != null) {
                    if (downloading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(C.chipBg),
                        ) {
                            val fraction = (progress ?: 0f).coerceIn(0f, 1f)
                            if (fraction > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(C.accent),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (progress != null) {
                                "正在下载安装包 ${(progress * 100).toInt()}%"
                            } else {
                                "正在下载安装包…"
                            },
                            color = C.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    if (downloadError != null) {
                        Text(
                            text = downloadError,
                            color = Color(0xFFD32F2F),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                // 主操作：去下载（App 内下载后直接唤起安装器）。
                // 原来右边还有一个「GitHub」按钮占半行，仓库转私有后点开只有 404，已撤掉，
                // 单按钮撑满整行。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (downloading) C.accent.copy(alpha = 0.55f) else C.accent)
                        .clickable(enabled = !downloading, onClick = onOpenDownload),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            downloading -> "下载中"
                            downloadError != null -> "重新下载"
                            else -> "去下载"
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 次要操作：稍后（下载中＝后台下载，转成悬浮条而不是彻底关掉）
                Row {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(C.chipBg)
                            .clickable(onClick = if (downloading) onMinimize else onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (downloading) "后台下载" else "稍后",
                            color = C.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onNeverAgain),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("不再提醒此版本", color = C.textSecondary, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * 最小化后的更新悬浮条：贴在底部导航上方，点了重新打开完整弹窗。
 *
 * 存在的意义只有一个 —— 安装包 25 MB 要下几十秒，不该让用户一直盯着一块挡住界面的弹窗。
 * 收起后仍能看到进度，下载在后台继续。
 */
@Composable
fun UpdateMiniBar(
    version: String,
    downloading: Boolean,
    progress: Float?,
    error: String?,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val C = LocalAppColors.current
    val shape = RoundedCornerShape(16.dp)
    // 实心面板 + 发丝描边：半透明 + elevation 会「四周发灰、中间发白」，压在渐变背景上尤其脏
    Row(
        modifier = modifier
            .clip(shape)
            .background(C.panelBg)
            .border(1.dp, C.border, shape)
            .clickable(onClick = onExpand)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 进度环：没有具体百分比时用一小段弧，表示「在转」
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2f
                drawArc(
                    color = C.chipBg,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
                val sweep = when {
                    error != null -> 0f
                    downloading -> (progress ?: 0.25f).coerceIn(0.02f, 1f) * 360f
                    else -> 360f
                }
                if (sweep > 0f) {
                    drawArc(
                        color = if (error != null) Color(0xFFD32F2F) else C.accent,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                when {
                    error != null -> "更新下载失败"
                    downloading -> "正在下载 v$version"
                    else -> "新版本 v$version 可更新"
                },
                color = C.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (downloading) {
                Text(
                    if (progress != null) "${(progress * 100).toInt()}%" else "下载中…",
                    color = C.textSecondary,
                    fontSize = 10.sp,
                )
            } else if (error != null) {
                Text("点开重试", color = C.textSecondary, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("展开", color = C.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 把 Release 正文里的 Markdown 记号去掉，只留可读的纯文本。
 *
 * 弹窗里不引 Markdown 渲染器（为一个更新说明引一整个库不值得），
 * 这里做最小清理：标题井号、加粗星号、行内代码反引号、列表符号。
 */
private fun plainNotes(raw: String): String = raw
    .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
    .replace(Regex("""\*\*([^*\n]+)\*\*"""), "$1")
    .replace(Regex("""`([^`\n]+)`"""), "$1")
    .replace(Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE), "· ")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()
