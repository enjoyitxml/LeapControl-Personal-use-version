package com.leapcontrol.app

import android.content.Context
import android.util.Log

/**
 * 敏感 API Key 原生动态掩码混淆与保护模块：
 * 1. 杜绝在 DEX 常量池中保留明文字符串；
 * 2. 运行时动态执行变长盐值反向解混淆；
 * 3. 联动官方应用签名防篡改校验，阻断重打包劫持。
 */
object ObfuscatedSecrets {

    private const val TAG = "ObfuscatedSecrets"

    @Volatile
    private var cachedAmapWebKey: String? = null

    @Volatile
    private var cachedAmapWebKeys: List<String>? = null

    @Volatile
    private var cachedPgyerApiKey: String? = null

    @Volatile
    private var cachedGithubToken: String? = null

    /**
     * 获取高德 Web API Key 完整列表（支持逗号分隔多 Key，内存动态解混淆）
     */
    fun getAmapWebKeys(context: Context? = null): List<String> {
        cachedAmapWebKeys?.let { return it }

        if (context != null && !AppSignatureGuard.isSignatureValid(context)) {
            Log.e(TAG, "安全告警：签名校验失败，拒绝解密高德 API Key")
            return emptyList()
        }

        val decrypted = deobfuscate(BuildConfig.AMAP_WEB_KEY_ENCRYPTED)
        val keys = decrypted.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = if (keys.isNotEmpty()) keys else listOf(decrypted.trim()).filter { it.isNotBlank() }
        cachedAmapWebKeys = result
        if (result.isNotEmpty()) cachedAmapWebKey = result.first()
        return result
    }

    /**
     * 获取默认高德 Web API Key（内存动态解混淆）
     */
    fun getAmapWebKey(context: Context? = null): String {
        cachedAmapWebKey?.let { return it }
        return getAmapWebKeys(context).firstOrNull().orEmpty()
    }

    /**
     * 获取蒲公英 API Key（内存动态解混淆）
     */
    fun getPgyerApiKey(context: Context? = null): String {
        cachedPgyerApiKey?.let { return it }

        if (context != null && !AppSignatureGuard.isSignatureValid(context)) {
            Log.e(TAG, "安全告警：签名校验失败，拒绝解密蒲公英 API Key")
            return ""
        }

        val decrypted = deobfuscate(BuildConfig.PGYER_API_KEY_ENCRYPTED)
        cachedPgyerApiKey = decrypted
        return decrypted
    }

    /**
     * 获取 GitHub 只读令牌（内存动态解混淆）。
     *
     * 仓库是私有仓库，检测更新与下载安装包都要带它。构建时以密文写入
     * `BuildConfig.GITHUB_TOKEN_ENCRYPTED`（见 app/build.gradle.kts），
     * 这里在运行时还原 —— 源码、git 历史与 DEX 常量池里都不会出现明文。
     *
     * 返回空串表示没有令牌：调用方此时应退化成匿名请求（私有仓库下会检测失败，
     * 但不会崩），而不是抛异常。
     *
     * ## 这不是「安全存储」，只是「不明文」
     * 解混淆算法就在同一个 APK 里，任何人拿到包都能离线还原出令牌。
     * 因此这颗令牌必须是**最小权限 + 可随时吊销**的：只给仓库的
     * Contents / Releases 读权限，不要给它写权限，也不要复用到别处。
     */
    fun getGitHubToken(context: Context? = null): String {
        cachedGithubToken?.let { return it }

        if (context != null && !AppSignatureGuard.isSignatureValid(context)) {
            Log.e(TAG, "安全告警：签名校验失败，拒绝解密 GitHub 令牌")
            return ""
        }

        val decrypted = deobfuscate(BuildConfig.GITHUB_TOKEN_ENCRYPTED)
        cachedGithubToken = decrypted
        return decrypted
    }

    /**
     * 变长盐值反混淆解码算法（与 build.gradle.kts 中的混淆算法严格对称）
     */
    fun deobfuscate(hex: String): String {
        if (hex.isBlank() || hex.length % 2 != 0) return ""
        return try {
            val len = hex.length / 2
            val bytes = ByteArray(len)
            for (i in 0 until len) {
                val encoded = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return ""
                val salt = (0x7B + (i * 37) + (i ushr 2)) and 0xFF
                bytes[i] = (encoded xor salt).toByte()
            }
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
