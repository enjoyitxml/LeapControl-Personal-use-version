package com.leapcontrol.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * 官方应用证书签名防篡改自校验：
 * 防止恶意第三方对 APK 进行反编译注入、二次打包重签名分发。
 */
object AppSignatureGuard {

    private const val TAG = "AppSignatureGuard"

    // 官方正式版签名证书 SHA-256 指纹 (无冒号全大写)
    const val EXPECTED_RELEASE_SHA256 =
        "4FD7A609F94AC11ABF9BB40BFD78F5448BB1BBDEBC83FBD827A0D9E85EF0A3F6"

    /**
     * 实际分发用的证书：Android 默认 debug keystore（`CN=Android Debug`）。
     *
     * 2026-11-10 起 release 变体也参与分发（只为 R8 + 资源压缩的体积收益，
     * 体积 26MB → 3.4MB），但它同样用 debug keystore 签名 —— 车友装的历史包全是
     * debug 签名，换签名会导致「应用未安装」，只能卸载重装，而卸载会带走零记与行程数据。
     *
     * 所以这里必须认这把证书：否则 release 包一进 `isSignatureValid` 就判 false，
     * `ObfuscatedSecrets.getGitHubToken` 会拒绝解密，更新检查与下载直接静默失效。
     * 安全口径与改动前一致 —— 线上包一直是 debug 签名，这条校验从来没有真正拦过重签名。
     */
    private const val DISTRIBUTION_DEBUG_SHA256 =
        "0FCEDB4AA779DDF5A6CE07D2FE2520875BBC39A3D5BC47A202275251B6D7E4A2"

    /** 允许的证书指纹：正式版证书 + 实际分发用的 debug 证书。 */
    private val ALLOWED_SHA256 = arrayOf(EXPECTED_RELEASE_SHA256, DISTRIBUTION_DEBUG_SHA256)

    @Volatile
    private var verifiedCache: Boolean? = null

    /**
     * 校验当前运行中的 APK 是否由「我们自己发出去的包」签名。
     *
     * debug 变体直接放行（历史行为不变）；release 变体比对 [ALLOWED_SHA256]。
     */
    fun isSignatureValid(context: Context?): Boolean {
        // 缓存判断，避免频繁反射与系统调用开销
        verifiedCache?.let { return it }

        if (context == null || BuildConfig.DEBUG) {
            return true
        }

        return try {
            val currentSha256 = getApkSignatureSha256(context)
            val isValid = ALLOWED_SHA256.any { it.equals(currentSha256, ignoreCase = true) }
            if (!isValid) {
                Log.e(TAG, "警告：检测到应用签名与官方证书不匹配，可能存在二次打包篡改！")
            }
            verifiedCache = isValid
            isValid
        } catch (e: Exception) {
            Log.w(TAG, "签名检查异常: ${e.message}")
            // 异常时默认放行以保证基础可用性，不强阻断异常设备
            true
        }
    }

    /**
     * 提取当前 APK 签名的 SHA-256 指纹
     */
    fun getApkSignatureSha256(context: Context): String? {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return null
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(certBytes)
            digest.joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "无法提取当前签名证书: ${e.message}")
            null
        }
    }
}
