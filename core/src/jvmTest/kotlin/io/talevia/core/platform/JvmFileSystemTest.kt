package io.talevia.core.platform

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [JvmFileSystem] end-to-end against a real tempdir. Load-bearing
 * scenarios: UTF-8 round trip, size cap enforcement, malformed UTF-8 rejected,
 * relative paths rejected by PathGuard, truncated list / glob results, symlink
 * safety (Files.walk default does not follow symlinks).
 */
class JvmFileSystemTest {
    private lateinit var root: File
    private val fs = JvmFileSystem()

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("jvm-fs-test").toFile()
    }

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    private fun path(vararg parts: String): String = File(root, parts.joinToString("/")).absolutePath

    @Test fun `readText round-trips UTF-8 content`() = runBlocking {
        val p = path("hello.txt")
        File(p).writeText("你好，Talevia!")
        assertEquals("你好，Talevia!", fs.readText(p))
    }

    @Test fun `readText rejects files larger than cap`() = runBlocking {
        val p = path("big.txt")
        File(p).writeText("x".repeat(1_000))
        val ex = assertFailsWith<IllegalArgumentException> {
            fs.readText(p, maxBytes = 100)
        }
        assertTrue(ex.message!!.contains("file too large"), "got: ${ex.message}")
    }

    @Test fun `readText rejects non-UTF-8 bytes`() = runBlocking {
        val p = path("bin.dat")
        Files.write(File(p).toPath(), byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01))
        val ex = assertFailsWith<IllegalArgumentException> { fs.readText(p) }
        assertTrue(ex.message!!.contains("not valid UTF-8"), "got: ${ex.message}")
    }

    @Test fun `readText rejects directories`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { fs.readText(root.absolutePath) }
    }

    @Test fun `readText rejects relative paths`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { fs.readText("./foo.txt") }
    }

    @Test fun `readText rejects non-existent file`() = runBlocking {
        val ex = assertFailsWith<IllegalArgumentException> {
            fs.readText(path("missing.txt"))
        }
        assertTrue(ex.message!!.contains("no such file"), "got: ${ex.message}")
    }

    @Test fun `writeText creates file and returns bytes written`() = runBlocking {
        val p = path("out.txt")
        val n = fs.writeText(p, "abc\u00E9")
        // 'é' encodes as two bytes in UTF-8
        assertEquals(5L, n)
        assertEquals("abc\u00E9", File(p).readText(StandardCharsets.UTF_8))
    }

    @Test fun `writeText truncates existing file`() = runBlocking {
        val p = path("out.txt")
        File(p).writeText("longer content")
        fs.writeText(p, "short")
        assertEquals("short", File(p).readText(StandardCharsets.UTF_8))
    }

    @Test fun `writeText refuses missing parent without createDirs`() = runBlocking {
        val p = path("sub", "deep", "x.txt")
        val ex = assertFailsWith<IllegalArgumentException> { fs.writeText(p, "x") }
        assertTrue(ex.message!!.contains("parent directory does not exist"), "got: ${ex.message}")
    }

    @Test fun `writeText creates parents when asked`() = runBlocking {
        val p = path("sub", "deep", "x.txt")
        fs.writeText(p, "ok", createDirs = true)
        assertEquals("ok", File(p).readText())
    }

    @Test fun `list returns sorted entries`() = runBlocking {
        File(path("b.txt")).writeText("1")
        File(path("a.txt")).writeText("22")
        File(path("sub")).mkdirs()
        val res = fs.list(root.absolutePath)
        assertFalse(res.truncated)
        assertContentEquals(listOf("a.txt", "b.txt", "sub"), res.entries.map { it.name })
        assertTrue(res.entries.single { it.name == "sub" }.isDirectory)
        assertEquals(2L, res.entries.single { it.name == "a.txt" }.sizeBytes)
    }

    @Test fun `list truncates above cap`() = runBlocking {
        repeat(5) { i -> File(path("f$i.txt")).writeText("x") }
        val res = fs.list(root.absolutePath, maxEntries = 3)
        assertTrue(res.truncated)
        assertEquals(3, res.entries.size)
    }

    @Test fun `list rejects files`(): Unit = runBlocking {
        val p = path("plain.txt")
        File(p).writeText("x")
        assertFailsWith<IllegalArgumentException> { fs.list(p) }
    }

    @Test fun `glob matches files by extension`() = runBlocking {
        File(path("a.srt")).writeText("x")
        File(path("b.srt")).writeText("x")
        File(path("c.mp4")).writeText("x")
        val subdir = File(path("nested")).apply { mkdirs() }
        File(subdir, "d.srt").writeText("x")
        val res = fs.glob("${root.absolutePath}/*.srt")
        assertFalse(res.truncated)
        assertEquals(2, res.matches.size, "glob result=$res")
        assertTrue(res.matches.all { it.endsWith(".srt") })
    }

    @Test fun `glob with double-star walks recursively`() = runBlocking {
        File(path("a.srt")).writeText("x")
        File(path("nested")).mkdirs()
        File(path("nested", "b.srt")).writeText("x")
        val res = fs.glob("${root.absolutePath}/**.srt")
        assertEquals(2, res.matches.size, "glob result=$res")
    }

    @Test fun `glob rejects relative pattern`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { fs.glob("*.srt") }
    }

    @Test fun `glob truncates at cap`() = runBlocking {
        repeat(10) { i -> File(path("f$i.srt")).writeText("x") }
        val res = fs.glob("${root.absolutePath}/*.srt", maxMatches = 3)
        assertTrue(res.truncated)
        assertEquals(3, res.matches.size)
    }
}
