package com.leapcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 3D 车模包体的归一化 / 解压 / 失败文案。
 *
 * 这一组全部是 2026-11-12「3D 车模总是下载不下来」那次反馈的产物：老实现隐含假设
 * 「200 + 裸 ZIP + 根目录有 index.html」，三个假设任意一个不成立就静默失败。
 * 下面每条用例对应一个曾被假设掉的真实形态。
 */
class CarModelPackageTest {

    // ---------------------------------------------------------------- 形态识别

    @Test
    fun `裸 zip gzip json 三种形态都认得出来`() {
        assertEquals(CarModelPackage.Shape.ZIP, CarModelPackage.shapeOf(zip("index.html" to "<html/>")))
        assertEquals(CarModelPackage.Shape.GZIP, CarModelPackage.shapeOf(gzip(zip("a.txt" to "x"))))
        assertEquals(CarModelPackage.Shape.JSON, CarModelPackage.shapeOf("""{"code":0}""".toByteArray()))
        // 前面有空白 / 换行也算 JSON，网关偶尔会在前面塞 BOM 之外的空白
        assertEquals(CarModelPackage.Shape.JSON, CarModelPackage.shapeOf("\n  [1,2]".toByteArray()))
        assertEquals(CarModelPackage.Shape.UNKNOWN, CarModelPackage.shapeOf("<html>404</html>".toByteArray()))
        assertEquals(CarModelPackage.Shape.UNKNOWN, CarModelPackage.shapeOf(ByteArray(0)))
    }

    // ---------------------------------------------------------------- JSON 响应

    @Test
    fun `JSON 里的下载地址能被认出来`() {
        val hint = CarModelPackage.readHint(
            """{"code":0,"data":{"packageUrl":"https://cdn.example.com/a.zip?sig=1"}}"""
        )
        assertTrue(hint is CarModelPackage.Hint.Link)
        assertEquals("https://cdn.example.com/a.zip?sig=1", (hint as CarModelPackage.Hint.Link).url)
    }

    @Test
    fun `地址藏在更深一层也能找到`() {
        val hint = CarModelPackage.readHint(
            """{"code":0,"data":{"file":{"url":"https://cdn.example.com/b.zip"}}}"""
        )
        assertTrue(hint is CarModelPackage.Hint.Link)
    }

    @Test
    fun `JSON 里内联的 base64 包体也能解出来`() {
        // 用不可压缩的随机字节：重复内容压完只剩几十字节，会低于 base64 的判定阈值
        val raw = zipBytes("index.html" to ByteArray(4096).also { java.util.Random(7).nextBytes(it) })
        val encoded = Base64.getEncoder().encodeToString(raw)
        val hint = CarModelPackage.readHint("""{"code":0,"data":{"package":"$encoded"}}""")
        assertTrue(hint is CarModelPackage.Hint.Inline)
        assertEquals(CarModelPackage.Shape.ZIP, CarModelPackage.shapeOf((hint as CarModelPackage.Hint.Inline).bytes))
    }

    @Test
    fun `服务端明确拒绝时报的是 Failure 而不是 Unknown`() {
        val hint = CarModelPackage.readHint("""{"code":39,"msg":"信息校验失败"}""")
        assertTrue(hint is CarModelPackage.Hint.Failure)
        assertEquals("信息校验失败", (hint as CarModelPackage.Hint.Failure).reason)
    }

    @Test
    fun `既没地址也没字节的 JSON 归到 Unknown`() {
        assertTrue(CarModelPackage.readHint("""{"code":0,"data":null}""") is CarModelPackage.Hint.Unknown)
        assertTrue(CarModelPackage.readHint("not json at all") is CarModelPackage.Hint.Unknown)
    }

    @Test
    fun `token 失效的判据按字段判而不是碰运气看有没有 39`() {
        assertTrue(CarModelPackage.looksLikeTokenFailure("""{"code":39}"""))
        assertTrue(CarModelPackage.looksLikeTokenFailure("""{"msg":"token 已过期"}"""))
        assertTrue(CarModelPackage.looksLikeTokenFailure("""{"result":401}"""))
        // 反例：老实现 `contains("39")` 会把这个判成 token 失效
        assertFalse(CarModelPackage.looksLikeTokenFailure("""{"code":0,"data":{"size":39}}"""))
        assertFalse(CarModelPackage.looksLikeTokenFailure("""{"code":0,"data":"ok"}"""))
    }

    // ---------------------------------------------------------------- 解压

    @Test
    fun `普通包按原样摊到目标目录`() {
        val dir = Files.createTempDirectory("pkg").toFile()
        try {
            val result = CarModelPackage.unzip(
                zip("index.html" to "<html/>", "js/app.js" to "1"),
                dir,
            )
            assertTrue(result.error, result.ok)
            assertEquals(2, result.files)
            assertTrue(File(dir, "index.html").isFile)
            assertTrue(File(dir, "js/app.js").isFile)
            // 暂存目录不能留在成品里
            assertFalse(File(dir, ".tmp_unzip").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `单层包装目录会被摊平，否则永远找不到 index_html`() {
        val dir = Files.createTempDirectory("pkg").toFile()
        try {
            val result = CarModelPackage.unzip(
                zip("dist/index.html" to "<html/>", "dist/assets/car.glb" to "glb"),
                dir,
            )
            assertTrue(result.error, result.ok)
            assertTrue("入口页必须提到根目录", File(dir, "index.html").isFile)
            assertTrue(File(dir, "assets/car.glb").isFile)
            assertFalse("包装目录要删掉", File(dir, "dist").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `多个子目录时不乱摊平`() {
        val dir = Files.createTempDirectory("pkg").toFile()
        try {
            val result = CarModelPackage.unzip(
                zip("a/index.html" to "<html/>", "b/index.html" to "<html/>"),
                dir,
            )
            assertTrue(result.ok)
            // 两个候选目录说明我们猜不准，原样保留比猜错强
            assertFalse(File(dir, "index.html").isFile)
            assertTrue(File(dir, "a/index.html").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `zip 里带路径穿越条目时整体失败且不落任何文件`() {
        val dir = Files.createTempDirectory("pkg").toFile()
        try {
            val result = CarModelPackage.unzip(zip("../evil.txt" to "pwn"), dir)
            assertFalse(result.ok)
            assertNotNull(result.error)
            assertFalse(File(dir.parentFile, "evil.txt").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `空压缩包算失败而不是就绪`() {
        val dir = Files.createTempDirectory("pkg").toFile()
        try {
            val empty = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
            val result = CarModelPackage.unzip(empty, dir)
            assertFalse(result.ok)
            assertEquals("压缩包是空的", result.error)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- 完整性

    @Test
    fun `MD5 校验能吃 base64 也能吃 hex，字段缺失时放行`() {
        val bytes = "hello 3d".toByteArray()
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes)
        val b64 = Base64.getEncoder().encodeToString(md5)
        val hex = md5.joinToString("") { "%02x".format(it) }

        assertTrue(CarModelPackage.matchesExpected(bytes, b64))
        assertTrue(CarModelPackage.matchesExpected(bytes, hex))
        // 没有下发校验值 / 不是 MD5：一律放行，不能因为对不上就不让人看 3D
        assertTrue(CarModelPackage.matchesExpected(bytes, null))
        assertTrue(CarModelPackage.matchesExpected(bytes, ""))
        assertTrue(CarModelPackage.matchesExpected(bytes, "not-a-md5"))
        // 真对不上才拒
        assertFalse(CarModelPackage.matchesExpected(bytes, Base64.getEncoder().encodeToString(ByteArray(16))))
    }

    @Test
    fun `gzip 包能解开`() {
        val original = zip("index.html" to "<html/>")
        val packed = gzip(original)
        val unpacked = CarModelPackage.gunzip(packed)
        assertNotNull(unpacked)
        assertEquals(CarModelPackage.Shape.ZIP, CarModelPackage.shapeOf(unpacked!!))
    }

    // ---------------------------------------------------------------- 域名

    @Test
    fun `候选域名去重去空去尾斜杠，且保持顺序`() {
        val hosts = CarModelPackage.candidateHosts(
            null,
            " ",
            "https://a.example.com/",
            "https://b.example.com",
            "https://a.example.com",
        )
        assertEquals(listOf("https://a.example.com", "https://b.example.com"), hosts)
    }

    @Test
    fun `下载域名必须和元数据探测一样把 GLOBAL_HOST 算进去`() {
        // 回归用例：元数据会退到 app-gw-global-master，下载当年没试它，
        // 于是「h5Key 拿得到、包永远下不下来」。这条守着那个漏掉的域名。
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app").isDirectory }
        val source = File(root, "app/src/main/java/com/leapcontrol/app/LeapmotorApi.kt").readText()
        // 去掉注释行，否则 KDoc 里提到的关键字会让断言永远成立
        val body = source.substringAfter("fun downloadCarModelPackage")
            .substringBefore("fun listThemeList")
            .lines()
            .filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
            .joinToString("\n")
        assertTrue("下载要试 GLOBAL_HOST", "GLOBAL_HOST" in body)
        assertTrue("下载要试 appRegion", "appRegion" in body)
        assertTrue("包体要过 CarModelPackage 归一化", "CarModelPackage.unzip" in body)
        assertTrue("失败原因要留给界面", "lastPackageError" in body)
        // 三种请求形态都要在，别退回「只发一种 GET，失败就拉倒」
        assertTrue("旧签名 GET", "旧签名 GET" in body)
        assertTrue("旧签名 POST", "旧签名 POST" in body)
        assertTrue("新网关 POST", "新网关 POST" in body)
        assertTrue("JSON 里的地址要跟链", "plainFetch" in body)
    }

    // ---------------------------------------------------------------- 失败文案

    @Test
    fun `失败原因翻成人话`() {
        assertEquals(
            "登录态可能过期了，重新登录后再试",
            CarModel3DManager.failureHint("[旧签名 GET] 服务端拒绝：信息校验失败"),
        )
        assertEquals(
            "包没下全（可能网络中断），重试一次",
            CarModel3DManager.failureHint("[旧签名 POST] 包体校验不过（可能被截断）"),
        )
        assertEquals(
            "服务器暂时连不上，稍后再试",
            CarModel3DManager.failureHint("HTTP 502（GET appgateway.leapmotor.com）"),
        )
        assertEquals("下载失败，重试一次", CarModel3DManager.failureHint(null))
        assertEquals("下载失败，重试一次", CarModel3DManager.failureHint("   "))
    }

    // ---------------------------------------------------------------- 工具

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
