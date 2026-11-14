package com.leapcontrol.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 断点续传的核心分支。
 *
 * 用 OkHttp 的拦截器伪造响应，不碰真实网络 —— 重点是「拼出来的文件对不对」，
 * 而不是「网络通不通」。
 */
class ApkUpdaterResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- Content-Range ----------------

    @Test
    fun `解析正常的 Content-Range`() {
        val r = ApkUpdater.parseContentRange("bytes 1000-1999/6202683")
        assertEquals(1000L, r?.start)
        assertEquals(1999L, r?.end)
        assertEquals(6202683L, r?.total)
    }

    @Test
    fun `总长未知时用星号`() {
        val r = ApkUpdater.parseContentRange("bytes 0-99/*")
        assertEquals(0L, r?.total)
    }

    @Test
    fun `非法 Content-Range 一律返回 null`() {
        assertNull(ApkUpdater.parseContentRange(null))
        assertNull(ApkUpdater.parseContentRange(""))
        assertNull(ApkUpdater.parseContentRange("bytes 100-99/200")) // end < start
        assertNull(ApkUpdater.parseContentRange("bytes 0-199/100")) // 总长比 end 还小
        assertNull(ApkUpdater.parseContentRange("1000-1999/6202683")) // 缺 bytes 前缀
        assertNull(ApkUpdater.parseContentRange("bytes abc-def/200"))
    }

    // ---------------- 续传起点 ----------------

    @Test
    fun `残包不存在或为空时从头下`() {
        val f = File(tmp.root, "a.apk.part")
        assertEquals(0L, ApkUpdater.resumableBytes(f, 200))
        f.writeBytes(ByteArray(0))
        assertEquals(0L, ApkUpdater.resumableBytes(f, 200))
    }

    @Test
    fun `残包比总长小时从残包末尾续传`() {
        val f = File(tmp.root, "a.apk.part").apply { writeBytes(ByteArray(100)) }
        assertEquals(100L, ApkUpdater.resumableBytes(f, 200))
    }

    @Test
    fun `残包不小于总长时不再续传`() {
        // 已经下完、或比远端还长的坏包，续传都没有意义
        val full = File(tmp.root, "full.apk.part").apply { writeBytes(ByteArray(200)) }
        assertEquals(0L, ApkUpdater.resumableBytes(full, 200))
        val over = File(tmp.root, "over.apk.part").apply { writeBytes(ByteArray(300)) }
        assertEquals(0L, ApkUpdater.resumableBytes(over, 200))
    }

    @Test
    fun `总长未知时按残包长度续传`() {
        val f = File(tmp.root, "a.apk.part").apply { writeBytes(ByteArray(100)) }
        assertEquals(100L, ApkUpdater.resumableBytes(f, 0))
    }

    // ---------------- 摘要 ----------------

    @Test
    fun `摘要一致才通过`() {
        val body = ByteArray(200) { it.toByte() }
        val f = File(tmp.root, "a.bin").apply { writeBytes(body) }
        assertTrue(ApkUpdater.matchesDigest(f, "sha256:${sha256(body)}"))
        assertTrue(ApkUpdater.matchesDigest(f, "sha256:${sha256(body).uppercase()}"))
    }

    @Test
    fun `摘要不一致必须失败`() {
        val f = File(tmp.root, "a.bin").apply { writeBytes(ByteArray(200)) }
        val wrong = "0".repeat(64)
        assertTrue(!ApkUpdater.matchesDigest(f, "sha256:$wrong"))
    }

    @Test
    fun `没有摘要时退化成只信长度`() {
        val f = File(tmp.root, "a.bin").apply { writeBytes(ByteArray(10)) }
        assertTrue(ApkUpdater.matchesDigest(f, null))
        assertTrue(ApkUpdater.matchesDigest(f, ""))
        assertTrue(ApkUpdater.matchesDigest(f, "sha256:")) // 格式不完整，等同没有
    }

    // ---------------- 写入分支 ----------------

    @Test
    fun `206 续传把新字节追加到残包后面`() {
        val part = File(tmp.root, "a.apk.part").apply { writeBytes(ByteArray(100) { 'A'.code.toByte() }) }
        val tail = ByteArray(100) { 'B'.code.toByte() }
        var progressStart = -1L
        val total = ApkUpdater.fetchInto(
            http = clientReturning { req ->
                mock(req, 206, tail, mapOf("Content-Range" to "bytes 100-199/200"))
            },
            request = requestOf(),
            partial = part,
            from = 100,
            declaredTotal = 200,
            onProgress = { p -> if (progressStart < 0) progressStart = p.downloaded },
        )
        assertEquals(200L, total)
        assertEquals(200L, part.length())
        assertEquals("A".repeat(100) + "B".repeat(100), part.readText())
        // 进度必须从残包位置起跳，否则界面会显示成「从 0 重新开始」
        assertEquals(100L, progressStart)
    }

    @Test
    fun `服务端忽略 Range 回 200 时从头覆盖`() {
        val part = File(tmp.root, "a.apk.part").apply { writeBytes(ByteArray(100) { 'A'.code.toByte() }) }
        val full = ByteArray(200) { 'C'.code.toByte() }
        val total = ApkUpdater.fetchInto(
            http = clientReturning { req -> mock(req, 200, full) },
            request = requestOf(),
            partial = part,
            from = 100,
            declaredTotal = 200,
            onProgress = {},
        )
        assertEquals(200L, total)
        // 旧残包的尾巴必须被截掉，不能变成 100 个 A + 200 个 C
        assertEquals(200L, part.length())
        assertEquals("C".repeat(200), part.readText())
    }

    @Test
    fun `416 视为断点失效`() {
        val part = File(tmp.root, "a.apk.part").apply { writeBytes(ByteArray(100)) }
        try {
            ApkUpdater.fetchInto(
                http = clientReturning { req -> mock(req, 416, ByteArray(0)) },
                request = requestOf(),
                partial = part,
                from = 100,
                declaredTotal = 200,
                onProgress = {},
            )
            throw AssertionError("416 应当抛 ResumeRejected")
        } catch (_: ApkUpdater.ResumeRejected) {
            // 预期
        }
    }

    @Test
    fun `206 起点对不上时拒绝拼接`() {
        val part = File(tmp.root, "a.apk.part").apply { writeBytes(ByteArray(100)) }
        try {
            ApkUpdater.fetchInto(
                http = clientReturning { req ->
                    mock(req, 206, ByteArray(150), mapOf("Content-Range" to "bytes 50-199/200"))
                },
                request = requestOf(),
                partial = part,
                from = 100,
                declaredTotal = 200,
                onProgress = {},
            )
            throw AssertionError("起点不一致应当抛 ResumeRejected")
        } catch (_: ApkUpdater.ResumeRejected) {
            // 预期
        }
    }

    @Test
    fun `续传请求带 Range 而全量请求不带`() {
        val resume = ApkUpdater.buildAssetRequest(1L, "t", 100)
        assertEquals("bytes=100-", resume.header("Range"))
        val fresh = ApkUpdater.buildAssetRequest(1L, "t", 0)
        assertNull(fresh.header("Range"))
    }

    // ---------------- 辅助 ----------------

    private fun requestOf(): Request =
        Request.Builder().url("https://example.test/asset").build()

    private fun clientReturning(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build()

    private fun mock(
        req: Request,
        code: Int,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val builder = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("MOCK")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
