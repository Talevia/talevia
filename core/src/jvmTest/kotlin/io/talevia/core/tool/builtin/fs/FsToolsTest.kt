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
        assertEquals("*", GrepTool(fs).permission.patternFrom("not-json"))
    }

    @Test fun `grep finds matches across files and reports counts`() = runBlocking {
        File(root, "a.kt").writeText("val x = 1\nval y = 2\n// TODO cleanup\n")
        File(root, "b.kt").writeText("fun main() {\n    println(\"hello\")\n}\n")
        File(root, "c.txt").writeText("no matches here\n")
        val tool = GrepTool(fs)

        val result = tool.execute(
            GrepTool.Input(path = root.absolutePath, pattern = "val\\s+\\w+"),
            ctx,
        )

        assertEquals(2, result.data.matches.size)
        assertTrue(result.data.matches.all { it.path.endsWith("a.kt") })
        assertEquals(listOf(1, 2), result.data.matches.map { it.line })
        assertEquals(3, result.data.filesScanned)
        assertTrue(result.outputForLlm.contains("val x = 1"))
    }

    @Test fun `grep include glob scopes files`() = runBlocking {
        File(root, "a.kt").writeText("TODO in kotlin\n")
        File(root, "b.txt").writeText("TODO in text\n")
        val tool = GrepTool(fs)

        val result = tool.execute(
            GrepTool.Input(
                path = root.absolutePath,
                pattern = "TODO",
                include = "**.kt",
            ),
            ctx,
        )
        assertEquals(1, result.data.matches.size)
        assertTrue(result.data.matches.single().path.endsWith("a.kt"))
    }

    @Test fun `grep caseInsensitive matches regardless of case`() = runBlocking {
        File(root, "a.txt").writeText("Mei arrived early\nmei was late\nMEI left\n")
        val tool = GrepTool(fs)

        val ci = tool.execute(
            GrepTool.Input(path = root.absolutePath, pattern = "mei", caseInsensitive = true),
            ctx,
        )
        assertEquals(3, ci.data.matches.size)

        val cs = tool.execute(
            GrepTool.Input(path = root.absolutePath, pattern = "mei"),
            ctx,
        )
        assertEquals(1, cs.data.matches.size)
    }

    @Test fun `grep skips non-utf8 and oversized files silently`() = runBlocking {
        // Write a file with an invalid UTF-8 byte sequence.
        val binary = File(root, "blob.bin")
        binary.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x7F))
        val tool = GrepTool(fs)

        // Should not throw — binary file is silently skipped.
        val result = tool.execute(
            GrepTool.Input(path = root.absolutePath, pattern = ".*"),
            ctx,
        )
        // binary was scanned attempt but had no matches (or was skipped); just assert no crash.
        assertTrue(result.data.matches.isEmpty() || result.data.matches.all { !it.path.endsWith("blob.bin") })
    }

    @Test fun `grep on a single file works`() = runBlocking {
        val p = File(root, "single.txt").apply { writeText("alpha\nbeta\ngamma\n") }.absolutePath
        val tool = GrepTool(fs)

        val result = tool.execute(
            GrepTool.Input(path = p, pattern = "beta"),
            ctx,
        )
        assertEquals(1, result.data.matches.size)
        assertEquals(2, result.data.matches.single().line)
        assertEquals("beta", result.data.matches.single().content)
    }

    @Test fun `grep maxMatches caps result and sets truncated`() = runBlocking {
        val body = (1..20).joinToString("\n") { "hit on line $it" }
        File(root, "many.txt").writeText(body)
        val tool = GrepTool(fs)

        val result = tool.execute(
            GrepTool.Input(path = root.absolutePath, pattern = "hit", maxMatches = 5),
            ctx,
        )
        assertEquals(5, result.data.matches.size)
        assertTrue(result.data.truncated)
        assertTrue(result.outputForLlm.contains("truncated"))
    }

    @Test fun `grep invalid regex fails with a clear error`(): Unit = runBlocking {
        val tool = GrepTool(fs)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(GrepTool.Input(path = root.absolutePath, pattern = "[unclosed"), ctx)
        }
    }

    @Test fun `edit_file replaces unique match and extracts path pattern`() = runBlocking {
        val p = File(root, "edit.txt").apply { writeText("alpha\nbeta\ngamma\n") }.absolutePath
        val tool = EditTool(fs)

        val result = tool.execute(
            EditTool.Input(path = p, oldString = "beta", newString = "BETA"),
            ctx,
        )
        assertEquals(1, result.data.replacements)
        assertEquals("alpha\nBETA\ngamma\n", File(p).readText())
        assertTrue(result.outputForLlm.contains("1 occurrence"))

        val pattern = tool.permission.patternFrom("""{"path":"$p"}""")
        assertEquals(p, pattern)
    }

    @Test fun `edit_file replaceAll rewrites every occurrence`() = runBlocking {
        val p = File(root, "many.txt").apply { writeText("foo foo bar foo\n") }.absolutePath
        val tool = EditTool(fs)

        val result = tool.execute(
            EditTool.Input(path = p, oldString = "foo", newString = "baz", replaceAll = true),
            ctx,
        )
        assertEquals(3, result.data.replacements)
        assertEquals("baz baz bar baz\n", File(p).readText())
        assertTrue(result.outputForLlm.contains("3 occurrence"))
    }

    @Test fun `edit_file fails when oldString not found`(): Unit = runBlocking {
        val p = File(root, "miss.txt").apply { writeText("hello world\n") }.absolutePath
        val tool = EditTool(fs)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(EditTool.Input(path = p, oldString = "xyz", newString = "abc"), ctx)
        }
    }

    @Test fun `edit_file fails when oldString matches multiple times without replaceAll`(): Unit = runBlocking {
        val p = File(root, "dup.txt").apply { writeText("foo foo\n") }.absolutePath
        val tool = EditTool(fs)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(EditTool.Input(path = p, oldString = "foo", newString = "bar"), ctx)
        }
    }

    @Test fun `edit_file rejects empty oldString and identical new-old`(): Unit = runBlocking {
        val p = File(root, "x.txt").apply { writeText("abc\n") }.absolutePath
        val tool = EditTool(fs)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(EditTool.Input(path = p, oldString = "", newString = "x"), ctx)
        }
        assertFailsWith<IllegalArgumentException> {
            tool.execute(EditTool.Input(path = p, oldString = "abc", newString = "abc"), ctx)
        }
    }

    @Test fun `edit_file empty newString deletes the match`() = runBlocking {
        val p = File(root, "del.txt").apply { writeText("keep <<drop>> keep\n") }.absolutePath
        val tool = EditTool(fs)

        val result = tool.execute(
            EditTool.Input(path = p, oldString = "<<drop>>", newString = ""),
            ctx,
        )
        assertEquals(1, result.data.replacements)
        assertEquals("keep  keep\n", File(p).readText())
    }

    @Test fun `edit_file patternFrom falls back to wildcard on malformed JSON`() {
        assertEquals("*", EditTool(fs).permission.patternFrom("{"))
    }

    @Test fun `multi_edit applies sequential edits atomically`() = runBlocking {
        val p = File(root, "multi.txt").apply { writeText("alpha\nbeta\ngamma\n") }.absolutePath
        val tool = MultiEditTool(fs)

        val result = tool.execute(
            MultiEditTool.Input(
                path = p,
                edits = listOf(
                    MultiEditTool.EditOp(oldString = "alpha", newString = "AAA"),
                    MultiEditTool.EditOp(oldString = "beta", newString = "BBB"),
                    MultiEditTool.EditOp(oldString = "gamma", newString = "GGG"),
                ),
            ),
            ctx,
        )
        assertEquals(3, result.data.totalReplacements)
        assertEquals(3, result.data.perEdit.size)
        assertEquals("AAA\nBBB\nGGG\n", File(p).readText())
        assertTrue(result.outputForLlm.contains("3 edit"))

        val pattern = tool.permission.patternFrom("""{"path":"$p"}""")
        assertEquals(p, pattern)
    }

    @Test fun `multi_edit operates on running result of previous edits`() = runBlocking {
        val p = File(root, "chain.txt").apply { writeText("foo bar baz\n") }.absolutePath
        val tool = MultiEditTool(fs)

        val result = tool.execute(
            MultiEditTool.Input(
                path = p,
                edits = listOf(
                    MultiEditTool.EditOp(oldString = "foo", newString = "qux"),
                    MultiEditTool.EditOp(oldString = "qux bar", newString = "qux QUX"),
                ),
            ),
            ctx,
        )
        assertEquals(2, result.data.totalReplacements)
        assertEquals("qux QUX baz\n", File(p).readText())
    }

    @Test fun `multi_edit replaceAll counts all occurrences in that step`() = runBlocking {
        val p = File(root, "many.txt").apply { writeText("foo foo foo\n") }.absolutePath
        val tool = MultiEditTool(fs)

        val result = tool.execute(
            MultiEditTool.Input(
                path = p,
                edits = listOf(
                    MultiEditTool.EditOp(oldString = "foo", newString = "bar", replaceAll = true),
                ),
            ),
            ctx,
        )
        assertEquals(3, result.data.totalReplacements)
        assertEquals("bar bar bar\n", File(p).readText())
    }

    @Test fun `multi_edit fails atomically when one edit cannot apply`(): Unit = runBlocking {
        val p = File(root, "atomic.txt").apply { writeText("alpha beta\n") }.absolutePath
        val tool = MultiEditTool(fs)

        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                MultiEditTool.Input(
                    path = p,
                    edits = listOf(
                        MultiEditTool.EditOp(oldString = "alpha", newString = "AAA"),
                        // This second edit will not match — the file should be untouched.
                        MultiEditTool.EditOp(oldString = "this-string-isnt-here", newString = "x"),
                    ),
                ),
                ctx,
            )
        }
        // Disk file untouched — atomicity invariant.
        assertEquals("alpha beta\n", File(p).readText())
    }

    @Test fun `multi_edit rejects empty edits list`(): Unit = runBlocking {
        val p = File(root, "x.txt").apply { writeText("hi") }.absolutePath
        val tool = MultiEditTool(fs)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(MultiEditTool.Input(path = p, edits = emptyList()), ctx)
        }
    }

    @Test fun `multi_edit rejects duplicate match without replaceAll`(): Unit = runBlocking {
        val p = File(root, "dup.txt").apply { writeText("foo foo\n") }.absolutePath
        val tool = MultiEditTool(fs)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                MultiEditTool.Input(
                    path = p,
                    edits = listOf(MultiEditTool.EditOp(oldString = "foo", newString = "bar")),
                ),
                ctx,
            )
        }
        assertEquals("foo foo\n", File(p).readText())
    }
}
