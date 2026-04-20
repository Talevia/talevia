package io.talevia.core.tool.builtin.fs

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.JvmFileSystem
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the four fs tools' happy paths plus the load-bearing bits of their
 * [io.talevia.core.permission.PermissionSpec.patternFrom] — selecting "Always"
 * in the CLI prompt creates a rule keyed on the exact path extracted here.
 */
class FsToolsTest {
    private lateinit var root: File
    private val fs = JvmFileSystem()

    private val ctx = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("fs-tools-test").toFile()
    }

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun `read_file returns content and extracts path pattern`() = runBlocking {
        val p = File(root, "hello.txt").apply { writeText("hi") }.absolutePath
        val tool = ReadFileTool(fs)

        val result = tool.execute(ReadFileTool.Input(path = p), ctx)
        assertEquals("hi", result.data.content)
        assertEquals(2L, result.data.bytes)
        assertTrue(result.outputForLlm.contains("hi"))

        val pattern = tool.permission.patternFrom("""{"path":"$p"}""")
        assertEquals(p, pattern)
    }

    @Test fun `read_file inlines small files and summarises large ones`() = runBlocking {
        val small = File(root, "small.txt").apply { writeText("short") }.absolutePath
        val bigBody = "x".repeat(5_000)
        val big = File(root, "big.txt").apply { writeText(bigBody) }.absolutePath
        val tool = ReadFileTool(fs)

        assertEquals("short", tool.execute(ReadFileTool.Input(small), ctx).outputForLlm)

        val bigResult = tool.execute(ReadFileTool.Input(big), ctx)
        assertTrue(
            bigResult.outputForLlm.contains("too large to inline"),
            "large-file output should summarise; got ${bigResult.outputForLlm.take(80)}",
        )
        assertEquals(bigBody, bigResult.data.content)
    }

    @Test fun `write_file creates file and reports bytes`() = runBlocking {
        val p = File(root, "out.txt").absolutePath
        val tool = WriteFileTool(fs)

        val result = tool.execute(WriteFileTool.Input(path = p, content = "abc"), ctx)
        assertEquals(3L, result.data.bytesWritten)
        assertEquals("abc", File(p).readText())
    }

    @Test fun `write_file createDirs true creates parent`() = runBlocking {
        val p = File(root, "sub/deep/out.txt").absolutePath
        val tool = WriteFileTool(fs)

        tool.execute(WriteFileTool.Input(path = p, content = "ok", createDirs = true), ctx)
        assertEquals("ok", File(p).readText())
    }

    @Test fun `write_file rejects relative path via PathGuard`(): Unit = runBlocking {
        val tool = WriteFileTool(fs)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(WriteFileTool.Input(path = "./rel.txt", content = "x"), ctx)
        }
    }

    @Test fun `list_directory returns entries`() = runBlocking {
        File(root, "a.txt").writeText("1")
        File(root, "b.txt").writeText("22")
        val tool = ListDirectoryTool(fs)

        val result = tool.execute(ListDirectoryTool.Input(path = root.absolutePath), ctx)
        assertEquals(listOf("a.txt", "b.txt"), result.data.entries.map { it.name })
        assertTrue(result.outputForLlm.contains("a.txt"))
        assertTrue(result.outputForLlm.contains("b.txt"))
    }

    @Test fun `glob finds matches and extracts pattern field`() = runBlocking {
        File(root, "a.srt").writeText("x")
        File(root, "b.srt").writeText("x")
        File(root, "c.mp4").writeText("x")
        val pattern = "${root.absolutePath}/*.srt"
        val tool = GlobTool(fs)

        val result = tool.execute(GlobTool.Input(pattern = pattern), ctx)
        assertEquals(2, result.data.matches.size)
        assertTrue(result.data.matches.all { it.endsWith(".srt") })

        val extracted = tool.permission.patternFrom("""{"pattern":"$pattern"}""")
        assertEquals(pattern, extracted)
    }

    @Test fun `fs tools fall back to wildcard pattern on malformed JSON`() {
        assertEquals("*", ReadFileTool(fs).permission.patternFrom("{"))
        assertEquals("*", GlobTool(fs).permission.patternFrom("not-json"))
    }
}
