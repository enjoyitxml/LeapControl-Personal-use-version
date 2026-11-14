package com.leapcontrol.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * 3D 车模包的**响应归一化**与**安全解压**。
 *
 * 这一段是 2026-11-12 从「总是下载不下来」这个线上反馈里拆出来的纯逻辑：
 * 原来的下载函数只认一种假设 —— `200` + 裸 ZIP 字节流 + 压缩包根目录就是
 * `index.html`。真实网关对同一条路径可能回四种东西里的任意一种：
 *
 *  1. 裸 ZIP（`PK\x03\x04`）—— 理想情况；
 *  2. gzip 包了一层（`\x1f\x8b`）—— OkHttp 只在服务端给了 `Content-Encoding: gzip`
 *     时才自动解，网关有时是「先 gzip 再当二进制塞进 body」，需要自己解；
 *  3. JSON：包体太大时改成下发一个 OSS 预签名地址（`data.url` / `packageUrl` …），
 *     必须**再发一次**请求去取；这类地址是公网直读，不能带我们自己的签名头；
 *  4. JSON 报错（token 过期 / 无权限 / 车端未开通）—— 和「下载失败」是两回事，
 *     要分开报，否则用户永远只看到一句「多半是网络问题」。
 *
 * 抽成 object 而不是塞回 `LeapmotorApi`：这里全是可单测的纯函数
 * （字节 → 形态、JSON → 提示、zip → 目录），跟网络、线程、Context 都无关。
 */
object CarModelPackage {

    /** 单个包体的上限。官方整包约 12MB，留 4 倍余量；超过基本是网关回了别的东西。 */
    const val MAX_BYTES = 64 * 1024 * 1024

    /** 解压后的临时目录名（相对模型目录）。中途被杀下次会先清掉。 */
    private const val STAGING = ".tmp_unzip"

    /** 包体内 Web 入口文件名。 */
    const val ENTRY_HTML = "index.html"

    // ---------------------------------------------------------------- 形态识别

    enum class Shape { ZIP, GZIP, JSON, UNKNOWN }

    fun shapeOf(bytes: ByteArray): Shape {
        if (bytes.size < 4) return Shape.UNKNOWN
        // PK：普通 zip（`PK\x03\x04`，空包是 `PK\x05\x06`，两种都认）
        if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return Shape.ZIP
        // \x1f\x8b：gzip
        if (bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) return Shape.GZIP
        // 跳过前导空白后看首个有效字符是不是 JSON 的 `{` / `[`
        for (i in 0 until minOf(bytes.size, 8)) {
            when (bytes[i].toInt() and 0xFF) {
                0x20, 0x0A, 0x0D, 0x09 -> continue
                0x7B, 0x5B -> return Shape.JSON
                else -> return Shape.UNKNOWN
            }
        }
        return Shape.UNKNOWN
    }

    /** JSON 里可能放着下载地址的字段，按可信度排序。 */
    private val LINK_FIELDS = listOf(
        "packageUrl", "pkgUrl", "downloadUrl", "fileUrl", "resourcePkg", "h5Url", "srcUrl", "url",
    )

    /** 内联 base64 包体的候选字段。 */
    private val INLINE_FIELDS = listOf("package", "base64", "content", "data", "file")

    /** 从 JSON 响应里能得出的结论。 */
    sealed interface Hint {
        /** 真正的包体在另一个地址上，需要再发一次请求。 */
        data class Link(val url: String) : Hint

        /** 包体直接以 base64 塞在响应里。 */
        data class Inline(val bytes: ByteArray) : Hint

        /** 服务端明确说不行（token / 权限），要按业务失败处理，不能当成网络问题。 */
        data class Failure(val reason: String) : Hint

        /** 认不出来。 */
        data object Unknown : Hint
    }

    /**
     * 解析 JSON 形态的包体响应。
     *
     * 注意字段顺序：先找「地址」，再找「内联字节」——网关的 `data` 字段两种都可能出现，
     * 但地址远比内联常见，先判地址可以少做一次可能几 MB 的 base64 解码。
     */
    fun readHint(text: String): Hint {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return Hint.Unknown
        val json = runCatching { org.json.JSONObject(trimmed) }.getOrNull() ?: return Hint.Unknown

        val raw = json.opt("data")
        // org.json 的 opt() 返回的是 JSONObject.NULL 而不是 null，先剥掉再当对象用
        val data = if (raw == null || raw == org.json.JSONObject.NULL) json else raw
        // 1. 地址
        linkIn(data)?.let { return Hint.Link(it) }
        linkIn(json)?.let { return Hint.Link(it) }
        // 2. 内联字节
        inlineIn(data)?.let { return Hint.Inline(it) }
        // 3. 报错
        val code = json.opt("code")?.toString().orEmpty()
        val result = json.opt("result")?.toString().orEmpty()
        val msg = json.optString("msg").ifEmpty { json.optString("message") }
        if (looksLikeTokenFailure(trimmed) || (code.isNotBlank() && code !in setOf("0", "200", "null"))) {
            return Hint.Failure(
                when {
                    msg.isNotBlank() -> msg
                    result.isNotBlank() && result != "null" -> "result=$result"
                    else -> "code=$code"
                }
            )
        }
        return Hint.Unknown
    }

    private fun linkIn(root: Any?): String? {
        if (root !is org.json.JSONObject) return null
        for (field in LINK_FIELDS) {
            val value = root.optString(field)
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        }
        // 地址也可能藏在一层子对象里（`data.package.url` 这种）
        val keys = root.keys()
        while (keys.hasNext()) {
            val child = root.optJSONObject(keys.next()) ?: continue
            linkIn(child)?.let { return it }
        }
        return null
    }

    private fun inlineIn(root: Any?): ByteArray? {
        val obj = root as? org.json.JSONObject ?: return null
        for (field in INLINE_FIELDS) {
            val value = obj.optString(field)
            if (value.length < 512) continue
            val decoded = runCatching { Base64.getMimeDecoder().decode(value) }.getOrNull() ?: continue
            if (shapeOf(decoded) == Shape.ZIP || shapeOf(decoded) == Shape.GZIP) return decoded
        }
        return null
    }

    /**
     * 「这是业务失败，不是网络失败」的判据。
     *
     * 旧代码写的是 `content.contains("39")` —— 任意 JSON 只要碰巧出现 `39` 就会被当成
     * token 失效，反过来真正的 `{"code":39}` 里如果没别的线索又会漏。这里按字段判。
     */
    fun looksLikeTokenFailure(text: String): Boolean {
        val lower = text.lowercase()
        if ("token" in lower) return true
        if ("信息校验失败" in text) return true
        if ("登录" in text && ("过期" in text || "失效" in text)) return true
        return runCatching {
            val json = org.json.JSONObject(text)
            val code = json.optString("code").ifEmpty { json.optString("result") }
            code in setOf("39", "401", "403", "1001")
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 解压

    /** 解压结果。[error] 为空表示成功。 */
    data class UnzipResult(val files: Int, val error: String? = null) {
        val ok: Boolean get() = error == null
    }

    /**
     * 把 ZIP 解到 [destDir]，**先解到暂存目录再合并**。
     *
     * 为什么要暂存：H5 包先落盘、资产包后落盘，两者共用同一个目录。资产包解到一半失败时，
     * 直接把半截文件写进目标目录会把已经下好的 H5 弄脏 —— 下次进来既不算就绪，也没法只补一次。
     *
     * 单层包装目录会被摊平：官方 H5 包有两种打包方式，一种 `index.html` 在根，一种外面套
     * 一层 `dist/`。不摊平的话前面全都成功，只是「找不到 index.html」，用户看到的就是
     * 「下不下来」。
     */
    fun unzip(bytes: ByteArray, destDir: File): UnzipResult {
        destDir.mkdirs()
        val staging = File(destDir, STAGING)
        // 上一轮被杀留下的残骸
        runCatching { staging.deleteRecursively() }
        if (!staging.mkdirs() && !staging.isDirectory) return UnzipResult(0, "无法创建解压目录")

        var count = 0
        try {
            val canonicalStaging = staging.canonicalFile
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (name.isBlank()) {
                        zis.closeEntry()
                        continue
                    }
                    val target = File(canonicalStaging, name).canonicalFile
                    // zip-slip：`../../x` 这类条目直接拒绝，不能让它写出去
                    if (target.path != canonicalStaging.path &&
                        !target.path.startsWith(canonicalStaging.path + File.separator)
                    ) {
                        throw IOException("压缩包条目越界：$name")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zis.copyTo(out) }
                        count++
                    }
                    zis.closeEntry()
                }
            }
            if (count == 0) {
                runCatching { staging.deleteRecursively() }
                return UnzipResult(0, "压缩包是空的")
            }
            flattenSingleWrapper(staging)
            mergeInto(staging, destDir)
            runCatching { staging.deleteRecursively() }
            return UnzipResult(count)
        } catch (e: Exception) {
            runCatching { staging.deleteRecursively() }
            return UnzipResult(count, e.message?.take(120) ?: "解压失败")
        }
    }

    /**
     * 根目录没有 [ENTRY_HTML]、而唯一子目录里有，就把那一层摊平上来。
     *
     * 只在「恰好一个子目录且里面有入口页」时动手：资产包也在同一个目录里解，
     * 乱摊平会把资产移到错误的位置。
     */
    fun flattenSingleWrapper(dir: File) {
        if (File(dir, ENTRY_HTML).isFile) return
        val children = dir.listFiles()?.filter { it.name != STAGING } ?: return
        val dirs = children.filter { it.isDirectory }
        if (dirs.size != 1) return
        val wrapper = dirs.first()
        if (!File(wrapper, ENTRY_HTML).isFile) return
        wrapper.listFiles()?.forEach { child ->
            val target = File(dir, child.name)
            if (target.exists()) runCatching { target.deleteRecursively() }
            if (!child.renameTo(target)) {
                // rename 失败（极少见，通常是跨挂载点）就退回复制
                runCatching { child.copyRecursively(target, overwrite = true) }
                runCatching { child.deleteRecursively() }
            }
        }
        runCatching { wrapper.deleteRecursively() }
    }

    private fun mergeInto(from: File, to: File) {
        to.mkdirs()
        from.listFiles()?.forEach { child ->
            val target = File(to, child.name)
            if (child.isDirectory) {
                mergeInto(child, target)
            } else {
                target.parentFile?.mkdirs()
                if (!child.renameTo(target)) {
                    runCatching { child.copyRecursively(target, overwrite = true) }
                    runCatching { child.delete() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 完整性

    /** 官方下发的 `h5Whole` / `srcWhole` 是 MD5 的 base64，用来校验包体有没有被截断。 */
    fun md5Base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(md5(bytes))

    fun md5Hex(bytes: ByteArray): String = md5(bytes).joinToString("") { "%02x".format(it) }

    private fun md5(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(bytes)

    /**
     * 校验包体。
     *
     * [expected] 为空（服务端没下发 / 字段是别的算法）时一律放行 —— 宁可少校验一次，
     * 也不能因为一个对不上的 hash 把本来能用的包拒了。
     */
    fun matchesExpected(bytes: ByteArray, expected: String?): Boolean {
        val want = expected?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        // 32 位 hex 与 16 字节 base64 都是 MD5，两种都认；其它形态无法判断，放行
        if (want.length == 32 && want.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return md5Hex(bytes).equals(want, ignoreCase = true)
        }
        if (!looksLikeMd5Base64(want)) return true
        return md5Base64(bytes) == want
    }

    fun looksLikeMd5Base64(value: String): Boolean =
        runCatching { Base64.getDecoder().decode(value).size == 16 }.getOrDefault(false)

    // ---------------------------------------------------------------- 解 gzip

    fun gunzip(bytes: ByteArray): ByteArray? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream(bytes.size * 4)
            input.copyTo(out, 8 * 1024)
            out.toByteArray()
        }
    }.getOrNull()

    // ---------------------------------------------------------------- 候选域名

    /**
     * 下载时要试的域名。
     *
     * 必须和元数据探测（`getVehiclePictureMeta`）保持同一套 —— 2026-11-12 那次反馈里
     * 元数据拿得到 `h5Key`、包却永远下不下来，差异就在这里：探测会退到
     * `GLOBAL_HOST`（旧网关，`API.md` 里 `/carpicture/key` 的兼容线路），
     * 而下载只试了 `appCenter / appRegion / 驾驶记录 / appuser` 四个，
     * 恰好没有那个真正能用的。
     */
    fun candidateHosts(vararg hosts: String?): List<String> =
        hosts.mapNotNull { it?.trim()?.takeIf { h -> h.isNotEmpty() }?.removeSuffix("/") }
            .distinct()
}
