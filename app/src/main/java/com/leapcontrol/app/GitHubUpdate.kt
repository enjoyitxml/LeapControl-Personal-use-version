package com.leapcontrol.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub 版本更新检测。
 *
 * 仓库为**私有**仓库：匿名访问 `releases/latest` 会直接 404，请求必须带一个只读令牌。
 * 令牌以密文打进包（`BuildConfig.GITHUB_TOKEN_ENCRYPTED`），运行时由
 * [ObfuscatedSecrets.getGitHubToken] 还原，再由调用方传进来 —— 本 object 不持有 Context，
 * 也就不去自己解密；令牌为空时退化成匿名请求（私有仓库会走「检测失败」分支，不会崩）。
 *
 * 取数主路径是 REST API `releases/latest`，能拿到版本号、发布页与 APK 附件信息。
 * 另有一条 `github.com/{repo}/releases/latest` 的 302 兜底：它走的是另一条网络路径，
 * api.github.com 被限流 / 不可达时仍可能成功。**但私有仓库下这条路径匿名拿不到东西**，
 * 所以只在没有令牌（或令牌失效）时才值得一试。
 *
 * 仓库 [REPO] 与发版脚本（`scripts/gh-release.py`）保持一致：
 * tag 形如 `vX.Y.Z`，Release 附件为 `LeapControl-vX.Y.Z.apk`（2026-11-10 起发 release
 * 变体，名字里不再带 `-debug`；客户端按 `.apk` 后缀挑附件，不依赖这个名字）。
 */
object GitHubUpdate {
    /**
     * 从哪个仓库取版本：自用版是私有仓库，个人学习版是公开仓库（见 [UPDATE_REPO]）。
     *
     * 不能写死 —— 公开版如果还去查私有仓库，匿名请求一律 404，
     * 「检查更新」会永远显示失败；反过来把令牌塞进公开包更不行。
     */
    private val REPO: String = UPDATE_REPO
    private val API: String = "https://api.github.com/repos/$REPO/releases/latest"
    private val RELEASES_LATEST: String = "https://github.com/$REPO/releases/latest"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        // 一次检测最多耗 15 秒：主接口不通时要尽快落到兜底路径，别让用户干等
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 最新发布版信息；无网络 / 解析失败 / 无发布版时返回 null。
     *
     * @param token 私有仓库的只读令牌，为空则走匿名请求。
     */
    fun latestRelease(token: String = ""): AppUpdateInfo? =
        fetchViaApi(token) ?: fetchViaRedirect()

    /** 主路径：REST API，失败重试一次（应对偶发的网络抖动 / 5xx）。 */
    private fun fetchViaApi(token: String): AppUpdateInfo? {
        repeat(2) {
            val info = runCatching { requestApi(token) }.getOrNull()
            if (info != null) return info
        }
        return null
    }

    private fun requestApi(token: String): AppUpdateInfo? {
        val builder = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
        // 私有仓库必须带令牌；没有令牌时不加头，由服务端按匿名处理
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        val body = client.newCall(builder.get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
        if (body.isNullOrBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null

        val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val version = tag.removePrefix("v").trim()
        val htmlUrl = root.optString("html_url").takeIf { it.isNotBlank() }
            ?: "https://github.com/$REPO/releases/latest"

        // 优先挑 .apk 附件：记录 asset id，App 内下载要走 API 才能带上令牌
        // （browser_download_url 是网页地址，私有仓库用浏览器打开需要先登录）
        val assets = root.optJSONArray("assets")
        var downloadUrl: String? = null
        var assetId: Long? = null
        var assetName: String? = null
        var assetSize: Long = 0L
        var assetDigest: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                    assetId = a.optLong("id").takeIf { it > 0 }
                    assetName = name
                    assetSize = a.optLong("size")
                    // GitHub 2024 年起给附件下发 `sha256:<hex>`，用它兜住断点续传的
                    // 「拼错」风险：长度对但内容错的情况只有摘要能发现。
                    assetDigest = a.optString("digest")
                        .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                    break
                }
            }
        }

        return AppUpdateInfo(
            version = version,
            tagName = tag,
            htmlUrl = htmlUrl,
            downloadUrl = downloadUrl,
            assetId = assetId,
            assetName = assetName,
            assetSize = assetSize,
            assetDigest = assetDigest,
            // Release 正文就是「更新说明」，弹窗里折叠展示；截断防止个别超长说明把弹窗撑爆
            notes = root.optString("body").orEmpty().trim().take(4000),
        )
    }

    /**
     * 兜底路径：请求 `github.com/{repo}/releases/latest`，OkHttp 自动跟随 302，
     * 从最终 URL（`.../releases/tag/vX.Y.Z`）里解析版本号。
     *
     * ⚠️ 私有仓库下这个网页端点匿名访问会 404，带 PAT 也不会被当成有效登录，
     * 所以它**只对公开仓库有意义**。保留它是为了「仓库哪天再转公开」时还能降级取数；
     * 私有仓库里 API 失败时，这里一定会跟着失败，代价是多等一次 8 秒超时
     * （只有 main 路径彻底不通时才会走到，正常检查不会碰它）。
     *
     * 此路径拿不到附件列表，所以 [AppUpdateInfo.assetId] 为 null ——
     * App 内下载会直接报「这个版本没有可下载的安装包」，这也是私有仓库下
     * 不该走到这条分支的原因。
     */
    private fun fetchViaRedirect(): AppUpdateInfo? = try {
        val request = Request.Builder()
            .url(RELEASES_LATEST)
            .header("Accept", "text/html")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            // 跟随重定向后的最终地址，如 https://github.com/xxx/LeapControl/releases/tag/v0.2.4
            val finalUrl = resp.request.url.toString()
            val tag = Regex("/releases/tag/([^/?#]+)").find(finalUrl)?.groupValues?.get(1)
                ?: return null
            val version = tag.removePrefix("v").trim()
            if (version.isBlank()) return null
            AppUpdateInfo(
                version = version,
                tagName = tag,
                htmlUrl = if (finalUrl.contains("/releases/tag/")) finalUrl else RELEASES_LATEST,
                // 此路径拿不到附件列表，按固定命名规则拼直链；若附件改名会 404，
                // 弹窗里还有「GitHub」入口可退回发布页
                downloadUrl = "https://github.com/$REPO/releases/download/$tag/LeapControl-$tag.apk",
                assetId = null,
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 附件下载地址（API 路径）。
     *
     * `GET /releases/assets/{id}` + `Accept: application/octet-stream` 会 302 到
     * 一个短期签名的对象存储地址，OkHttp 跟随后即可下载。公开仓库匿名可用。
     */
    internal fun assetApiUrl(assetId: Long): String =
        "https://api.github.com/repos/$REPO/releases/assets/$assetId"

    /** [latest] 是否比 [current] 更新（语义化版本比较）。 */
    fun isNewer(latest: String, current: String): Boolean = compareVersion(latest, current) > 0

    /** 版本号比较：a>b 返回正数，相等返回 0，a<b 返回负数。逐段整数比较。 */
    private fun compareVersion(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}

/**
 * 一次更新检测的结果。
 *
 * 必须区分「已是最新」与「检测失败」：以前两者都返回 null，
 * 检测失败时界面照样显示「已是最新」，用户根本看不出功能是否正常。
 */
sealed interface UpdateCheckResult {
    /** 确有更新。 */
    data class Newer(val info: AppUpdateInfo) : UpdateCheckResult

    /** 已是最新版本。 */
    data object UpToDate : UpdateCheckResult

    /** 网络不可达 / 接口异常 / 解析失败。 */
    data object Failed : UpdateCheckResult
}

/** 一个可用更新。 */
data class AppUpdateInfo(
    /** 版本号（不含前缀 v），如 "0.2.4"。 */
    val version: String,
    /** 原始 tag，如 "v0.2.4"。 */
    val tagName: String,
    /** GitHub 发布页地址。 */
    val htmlUrl: String,
    /** APK 直链（Release 含 .apk 附件时），可能为 null。 */
    val downloadUrl: String?,
    /** 附件 id：私有仓库要在 App 内下载必须靠它走 API。 */
    val assetId: Long? = null,
    /** 附件文件名。 */
    val assetName: String? = null,
    /** 附件大小（字节），0 表示未知。 */
    val assetSize: Long = 0L,
    /** 附件内容的 sha256，形如 `sha256:<hex>`；GitHub 未下发时为 null。 */
    val assetDigest: String? = null,
    /** Release 正文（更新说明原文，可能为空 —— 兜底路径拿不到）。 */
    val notes: String = "",
)
