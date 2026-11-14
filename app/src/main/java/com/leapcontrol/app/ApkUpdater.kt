package com.leapcontrol.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 更新包下载与安装。
 *
 * 仓库是**私有**仓库，下载必须带只读令牌：走 GitHub API 的
 * `releases/assets/{id}` + `Accept: application/octet-stream`，
 * 该端点会 302 到短期签名的对象存储地址（`release-assets.githubusercontent.com`），
 * 真正的字节流从 CDN 取，不反复消耗 API 限额。
 *
 * `browser_download_url` 不能用：它是网页直链，私有仓库下用浏览器打开需要先登录，
 * App 内直接 GET 也只会拿到一个登录跳转。asset id 是接口返回的稳定标识，沿用它。
 *
 * **断点续传**（2026-11-10）：CDN 支持 Range（实测 206 + `accept-ranges: bytes`），
 * 所以中断后能接着下。两个必须记住的点：
 * 1. 签名地址 1 小时就过期，续传**必须重新请求 asset API**拿新地址，不能缓存旧 URL；
 * 2. 续传得到的包必须能和新包逐字节一致，靠 sha256（GitHub 下发的 `digest`）+
 *    总长度双重校验，对不上就丢掉残包全量重下。
 */
object ApkUpdater {

    /** 下载进度。[total] 为 0 表示服务端没给长度，界面只显示「下载中」。 */
    data class Progress(val downloaded: Long, val total: Long) {
        /** 0f..1f；总长未知时返回 null。 */
        val fraction: Float?
            get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /**
     * 服务端拒绝了 Range：残包和远端对不上（416，或 206 的起点不是我们要的位置）。
     * 这时只有「删掉残包从头下」一条路，交给 [download] 重来一轮。
     */
    internal class ResumeRejected(message: String) : Exception(message)

    /** 206 响应里的区间信息。总长未知时 [total] 为 0。 */
    internal data class ContentRange(val start: Long, val end: Long, val total: Long)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // 安装包几 MB，弱网下要给足时间，但也不能无限挂
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * 下载 [info] 指向的 APK 到本地，返回文件。
     *
     * 支持断点续传：上次中断留下的 `.part` 会被接着写，而不是从 0 重下。
     * 只有「不属于本次目标」的旧文件会被清掉。
     *
     * @param token 私有仓库的只读令牌；为空时按匿名请求（私有仓库下会 404）。
     */
    fun download(
        context: Context,
        info: AppUpdateInfo,
        token: String = "",
        onProgress: (Progress) -> Unit = {},
    ): File {
        val assetId = info.assetId
            ?: error("这个版本没有可下载的安装包，请到发布页手动下载")

        // 放内部 files 而不是 cache：cache 在空间紧张时会被系统清掉，
        // 下载完点安装却提示文件不存在很难解释
        val dir = File(context.filesDir, "update").apply { mkdirs() }
        val target = File(dir, info.assetName ?: "LeapControl-${info.version}.apk")
        // 先写 .part 再改名：中途失败不会留下一个"看起来完整"的残包
        val partial = File(dir, "${target.name}.part")

        // 只清理「不属于本次目标」的残留（旧版本包、旧版本的 .part）。
        // 本次的 .part 必须留着 —— 它就是断点。改造前这里是无条件全删，
        // 于是每次重下都从 0 开始，断点续传根本无从谈起。
        dir.listFiles()?.forEach { f ->
            if (f.name != target.name && f.name != partial.name) f.delete()
        }

        // 上次已经下完、只是安装那步没走完（典型：用户还没给「安装未知应用」权限，
        // 授权回来再点一次）——直接复用，别再下一遍几 MB
        if (target.isFile && target.length() > 0 &&
            (info.assetSize <= 0 || target.length() == info.assetSize) &&
            matchesDigest(target, info.assetDigest)
        ) {
            return target
        }

        // 上次可能字节已经下完、只差改名就被杀进程，这种情况直接收尾，别再下一遍
        if (partial.length() > 0 &&
            finalize(partial, target, info.assetSize, info.assetDigest) != null
        ) {
            return target
        }

        // 两轮：第一轮带断点续传；校验不过就丢掉残包，第二轮从 0 全量。
        // 必须留这一轮 —— 残包可能是「上次下到一半、之后附件被重传过」拼出来的脏数据，
        // 直接报错会让用户点几次都失败。
        var attempt = 0
        while (attempt < 2) {
            attempt++
            val from = if (attempt == 1) resumableBytes(partial, info.assetSize) else 0L
            if (from > 0) {
                // 先把已有进度播一次：进度条从上次的位置起跳，用户才知道是在接着下
                onProgress(Progress(from, info.assetSize))
            }
            val total = try {
                fetchInto(
                    http = client,
                    request = buildAssetRequest(assetId, token, from),
                    partial = partial,
                    from = from,
                    declaredTotal = info.assetSize,
                    onProgress = onProgress,
                )
            } catch (e: ResumeRejected) {
                partial.delete()
                if (attempt >= 2) throw IllegalStateException("${e.message}，请重新下载")
                continue
            }
            val expected = total.takeIf { it > 0 } ?: info.assetSize
            if (finalize(partial, target, expected, info.assetDigest) != null) return target
            // 大小或摘要对不上：这份残包不可信，清掉重来
            partial.delete()
            if (attempt >= 2) error("安装包校验失败（大小或摘要不符），请重新下载")
        }
        error("安装包下载失败，请重试")
    }

    /** 拼下载请求。`from > 0` 时带 `Range`，只取剩下的字节。 */
    internal fun buildAssetRequest(assetId: Long, token: String, from: Long): Request {
        val builder = Request.Builder()
            .url(GitHubUpdate.assetApiUrl(assetId))
            .header("Accept", "application/octet-stream")
            // 关掉传输层压缩。APK 本身已是压缩包，gzip 省不了流量，但一旦服务端
            // 真做了压缩，Range 的偏移就是「压缩后」的，和我们要拼的原始字节对不上，
            // 续传会拼出一个长度对、内容错的文件。
            .header("Accept-Encoding", "identity")
        // 私有仓库：必须带令牌，否则 404（GitHub 对私有资源一律「看不见」，不区分无权限）
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        // 服务端若忽略 Range 会回 200 全量，写入侧会跟着从头覆盖，
        // 不会把新旧两段拼错（详见 [fetchInto] 的 resumed 分支）
        if (from > 0) builder.header("Range", "bytes=$from-")
        return builder.get().build()
    }

    /**
     * 把响应写进 [partial]，返回远端总长度（未知时为 0）。
     *
     * 关键分支：
     * - **206**：追加到 [from] 之后，`Content-Range` 的起点必须等于 [from]，
     *   否则说明残包不是这份附件的前缀，拼下去只会得到坏包 → [ResumeRejected]；
     * - **200**：服务端忽略了 Range（或残包已失效），从头覆盖写，不能追加；
     * - **416**：Range 越界，残包和远端长度对不上 → [ResumeRejected]。
     */
    internal fun fetchInto(
        http: OkHttpClient,
        request: Request,
        partial: File,
        from: Long,
        declaredTotal: Long,
        onProgress: (Progress) -> Unit,
    ): Long {
        http.newCall(request).execute().use { resp ->
            if (resp.code == 416) throw ResumeRejected("上次没下完的残包已失效")
            if (!resp.isSuccessful) error("下载失败（HTTP ${resp.code}）")
            val body = resp.body ?: error("安装包响应为空")

            // from = 0 时一律按全量写：残包可能是个长度够但内容坏的包，
            // 追加只会把坏数据留在前面
            val resumed = resp.code == 206 && from > 0
            val range = if (resumed) parseContentRange(resp.header("Content-Range")) else null
            if (resumed) {
                if (range == null) throw ResumeRejected("服务器没有按续传返回")
                // 起点对不上：残包不是这份附件的前缀（附件被重传过 / 文件被改过）
                if (range.start != from) throw ResumeRejected("上次没下完的残包已失效")
            }
            val total = when {
                range != null && range.total > 0 -> range.total
                resp.code == 200 && body.contentLength() > 0 -> body.contentLength()
                else -> declaredTotal
            }
            val offset = if (resumed) from else 0L

            // append = resumed：200 时截断重写，避免旧残包的尾巴留在新文件后面
            FileOutputStream(partial, resumed).use { output ->
                var downloaded = offset
                var lastReport = offset
                onProgress(Progress(downloaded, total))
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        // 每 256 KB 回报一次，太频繁会让 Compose 每帧都重组
                        if (downloaded - lastReport >= 256 * 1024) {
                            lastReport = downloaded
                            onProgress(Progress(downloaded, total))
                        }
                    }
                }
                output.flush()
                onProgress(Progress(downloaded, total))
            }
            return total
        }
    }

    /**
     * 解析 206 的 `Content-Range: bytes 1000-1999/6202683`。
     * 总长写成 `*` 表示服务端不知道，此时 [ContentRange.total] 为 0。
     */
    internal fun parseContentRange(header: String?): ContentRange? {
        if (header.isNullOrBlank()) return null
        val m = CONTENT_RANGE.find(header) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = m.groupValues[3].takeIf { it != "*" }?.toLongOrNull() ?: 0L
        // 总长比区间末尾还小，明显是坏头，别拿它去算进度
        if (total in 1..end) return null
        return ContentRange(start, end, total)
    }

    /**
     * 残包能从第几个字节接着下。返回 0 表示「从头下」。
     *
     * 以下情况一律返回 0：没有残包、空文件、长度已经不小于远端总长
     * （下完的，或比远端还长的坏包，续传都没有意义）。
     * 总长未知（0）时只能信残包长度，最终靠长度 + 摘要校验兜底。
     */
    internal fun resumableBytes(partial: File, total: Long): Long {
        if (!partial.isFile) return 0L
        val len = partial.length()
        if (len <= 0) return 0L
        if (total > 0 && len >= total) return 0L
        return len
    }

    /**
     * 摘要校验。GitHub 没下发 `digest`（老 release）时退化成「只信长度」，返回 true，
     * 由调用方的长度校验兜底。
     */
    internal fun matchesDigest(file: File, digest: String?): Boolean {
        val expected = digest?.substringAfter(':', "")?.trim()?.lowercase().orEmpty()
        if (expected.length != 64) return true
        return sha256Hex(file) == expected
    }

    /**
     * 校验通过就改名成正式文件并返回，否则返回 null。
     * 失败时**不删** [partial]，留给下一次续传。
     */
    private fun finalize(partial: File, target: File, expected: Long, digest: String?): File? {
        if (!partial.isFile || partial.length() <= 0) return null
        if (expected > 0 && partial.length() != expected) return null
        if (!matchesDigest(partial, digest)) return null
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return target
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private val CONTENT_RANGE =
        Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    /** 是否已允许「安装未知应用」；Android 8 以下默认允许。 */
    fun canInstall(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    } catch (_: Exception) {
        true
    }

    /** 跳到「安装未知应用」授权页。 */
    fun openInstallPermission(context: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 唤起系统安装器。
     *
     * 用 FileProvider 暴露内部目录里的文件（`file://` 在 Android 7+ 不允许直接共享），
     * 并授予一次性读权限。
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.exists() || apk.length() <= 0) return false
        if (!canInstall(context)) return openInstallPermission(context)
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
