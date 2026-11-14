package com.leapcontrol.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 私有仓库的只读令牌是不是真的「进了包、能用、没明文」（2026-11-09 需求）。
 *
 * 背景：仓库从公开转回私有之后，`releases/latest` 匿名访问一律 404，
 * 检测更新与下载安装包都必须带令牌。令牌以密文编译进
 * `BuildConfig.GITHUB_TOKEN_ENCRYPTED`，运行时由 [ObfuscatedSecrets] 还原。
 *
 * 这里钉四件事：
 * 1. 密文能解出合法令牌（算法与 app/build.gradle.kts 里的编码必须严格对称 ——
 *    两边一旦不一致，解出来是乱码，而乱码只会表现为「检测失败」，极难定位）；
 * 2. 顺带防泄漏：源码与构建脚本里**不能出现明文令牌**（完整前缀见下面的拼接串）。
 *    只要有人图省事把明文粘回来，这条会立刻红；
 * 3. 检测与下载两条请求都必须带 `Authorization`，否则私有仓库下各自 404；
 * 4. 调用方必须把令牌真的传下去（有类型签名的默认参数，漏传不会编译报错，只会静默失败）。
 */
class GitHubTokenTest {

    private val root: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { File(it, "app").isDirectory }

    private fun src(path: String) = File(root, path).readText()

    @Test
    fun `内置密文能解出合法令牌`() {
        val token = ObfuscatedSecrets.getGitHubToken(null)
        assertTrue("内置令牌解出来是空的 —— 密文与 deobfuscate 的盐算法对不上", token.isNotBlank())
        assertTrue(
            "解出来的不是 GitHub 令牌：${token.take(12)}…",
            token.startsWith("github" + "_pat_"),
        )
        // 细粒度 PAT 一般在 90 字符以上；太短基本就是解错了
        assertTrue("令牌长度异常：${token.length}", token.length >= 60)
        // 必须是纯 ASCII，出现替换字符说明按 UTF-8 解坏了
        assertTrue("令牌含非 ASCII 字符，解码路径有问题", token.all { it.code in 0x21..0x7E })
    }

    @Test
    fun `源码与构建脚本里没有明文令牌`() {
        // 故意拆成两段拼接：本文件里也不能出现完整的明文前缀，否则这条会自己扫到自己
        val plainPrefix = "github" + "_pat_"
        val files = buildList {
            add(File(root, "app/build.gradle.kts"))
            File(root, "app/src").walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".xml")) }
                .forEach { add(it) }
        }
        files.forEach { f ->
            val text = f.readText()
            assertFalse(
                "${f.path} 里出现了明文令牌（必须是密文）",
                text.contains(plainPrefix),
            )
        }
    }

    @Test
    fun `检测与下载都带上了鉴权头`() {
        val updateSrc = src("app/src/main/java/com/leapcontrol/app/GitHubUpdate.kt")
        assertTrue(
            "检测更新没带 Authorization，私有仓库下会 404",
            updateSrc.contains("header(\"Authorization\", \"Bearer \$token\")"),
        )
        val apkSrc = src("app/src/main/java/com/leapcontrol/app/ApkUpdater.kt")
        assertTrue(
            "下载安装包没带 Authorization，私有仓库下会 404",
            apkSrc.contains("header(\"Authorization\", \"Bearer \$token\")"),
        )
    }

    @Test
    fun `调用方把令牌真的传下去了`() {
        val runtimeSrc = src("app/src/main/java/com/leapcontrol/app/ui/AppRuntime.kt")
        // 默认参数是空串，漏传不会编译报错 —— 只能靠这里守
        assertTrue(
            "checkForUpdate 没把令牌传给 latestRelease",
            runtimeSrc.contains("GitHubUpdate.latestRelease(token)"),
        )
        assertTrue(
            "downloadUpdate 没把令牌传给 ApkUpdater",
            runtimeSrc.contains("ApkUpdater.download(appContext, info, token, onProgress)"),
        )
        assertTrue(
            "两处都应从 ObfuscatedSecrets 取令牌",
            runtimeSrc.contains("ObfuscatedSecrets.getGitHubToken(appContext)"),
        )
    }

    @Test
    fun `签名不符时拒绝解密`() {
        // 这一层只对 release 变体生效（debug 直接放行），但代码路径必须在：
        // 重打包的包即便拿到 APK 也读不出令牌
        val secrets = src("app/src/main/java/com/leapcontrol/app/ObfuscatedSecrets.kt")
        val body = secrets.substring(
            secrets.indexOf("fun getGitHubToken("),
            secrets.indexOf("fun deobfuscate("),
        )
        assertTrue("缺少签名校验", body.contains("AppSignatureGuard.isSignatureValid"))
        assertTrue("解密必须走 deobfuscate", body.contains("deobfuscate(BuildConfig.GITHUB_TOKEN_ENCRYPTED)"))
    }
}
