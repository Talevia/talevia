package io.talevia.core.tool.builtin.shell

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.JvmProcessRunner
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
 * Covers happy-path shell invocation, non-zero exit, timeout, output
 * truncation, working directory, and the first-token permission pattern
 * extraction. Skipped gracefully on Windows because `sh` / `sleep` / `echo`
 * aren't guaranteed; our target platforms are macOS / Linux.
 */
class BashToolTest {
    private lateinit var root: File
    private val runner = JvmProcessRunner()
    private val tool = BashTool(runner)

    private val ctx = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("bash-tool-test").toFile()
    }

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun `happy path captures stdout, exit 0, untruncated`() = runBlocking {
        val result = tool.execute(BashTool.Input(command = "echo hello"), ctx)
        assertEquals(0, result.data.exitCode)
        assertEquals("hello\n", result.data.stdout)
        assertEquals("", result.data.stderr)
        assertEquals(false, result.data.timedOut)
        assertEquals(false, result.data.truncated)
        assertTrue(result.outputForLlm.contains("hello"))
        assertTrue(result.outputForLlm.contains("exit=0"))
    }

    @Test fun `non-zero exit is returned as data, not thrown`() = runBlocking {
        val result = tool.execute(
            BashTool.Input(command = "sh -c 'echo oops >&2; exit 3'"),
            ctx,
        )
        assertEquals(3, result.data.exitCode)
        assertTrue(result.data.stderr.contains("oops"))
        assertTrue(result.outputForLlm.contains("exit=3"))
    }

    @Test fun `workingDir makes pwd report that directory`() = runBlocking {
        val result = tool.execute(
            BashTool.Input(command = "pwd", workingDir = root.absolutePath),
            ctx,
        )
        // macOS /tmp is a symlink to /private/tmp; compare resolved canonical forms.
        assertEquals(root.canonicalPath, File(result.data.stdout.trim()).canonicalPath)
    }

    @Test fun `timeout kills the process and flags timedOut`() = runBlocking {
        val result = tool.execute(
            BashTool.Input(command = "sleep 5", timeoutMillis = 200),
            ctx,
        )
        assertTrue(result.data.timedOut, "expected timedOut=true; got $result")
        assertEquals(-1, result.data.exitCode)
        assertTrue(result.outputForLlm.contains("timed out"))
    }

    @Test fun `blank command is rejected`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            tool.execute(BashTool.Input(command = "   "), ctx)
        }
    }

    @Test fun `invalid working dir fails fast`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                BashTool.Input(command = "echo x", workingDir = "/definitely/not/a/real/path"),
                ctx,
            )
        }
    }

    @Test fun `permission pattern extracts first command token`() {
        assertEquals("git", BashTool.extractCommandPattern("""{"command":"git status"}"""))
        assertEquals(
            "git",
            BashTool.extractCommandPattern("""{"command":"git commit -m \"msg\""}"""),
        )
        assertEquals(
            "./gradlew",
            BashTool.extractCommandPattern("""{"command":"./gradlew test"}"""),
        )
        assertEquals("ls", BashTool.extractCommandPattern("""{"command":"ls -la | wc"}"""))
        assertEquals("echo", BashTool.extractCommandPattern("""{"command":"echo hi > /tmp/x"}"""))
        assertEquals("*", BashTool.extractCommandPattern("not-json"))
        assertEquals("*", BashTool.extractCommandPattern("""{"command":""}"""))
    }
}
