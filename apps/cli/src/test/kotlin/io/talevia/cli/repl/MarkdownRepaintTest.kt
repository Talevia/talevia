package io.talevia.cli.repl

import io.talevia.core.PartId
import kotlinx.coroutines.runBlocking
import org.jline.terminal.Terminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the "streamed list flashes on screen then vanishes" report.
 *
 * The Mordant markdown repaint in [Renderer.finalizeAssistantText] issues an
 * ANSI cursor-up + clear-to-end-of-screen and then re-renders through Mordant.
 * That cursor math is brittle in real terminals — wide CJK characters wrap
 * differently than the string-length heuristic assumes, and prior output from
 * the JLine permission prompt moves the cursor in ways the Renderer never
 * observes. The repaint is now OFF by default; this test pins the streamed
 * text as the primary visible surface so future changes don't regress back.
 */
class MarkdownRepaintTest {

    @Test
    fun `no repaint means streamed bullet list survives after a tool turn`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out, width = 120, height = 40)
        val renderer = Renderer(terminal, markdownEnabled = false)

        val toolPart = PartId("p-tool")
        val textPart = PartId("p-list")

        renderer.toolRunning(toolPart, "list_directory")
        renderer.toolCompleted(toolPart, "list_directory", "listed 7 entries")

        val body = buildString {
            append("在 `/Users/xueniluo/Desktop/test_assets` 下共有 7 个条目：\n")
            append("\n")
            append("- `alpha.mov`\n")
            append("- `beta.mov`\n")
            append("- `gamma.mov`\n")
            append("- `delta.jpg`\n")
            append("- `epsilon.png`\n")
            append("- `zeta.txt`\n")
            append("- `eta/`\n")
            append("\n")
            append("需要我帮你看 `eta/` 里面有什么吗？\n")
        }
        for (chunk in body.chunked(8)) {
            renderer.streamAssistantDelta(textPart, chunk)
        }
        renderer.finalizeAssistantText(textPart, body)
        renderer.println("· tokens in=100 out=114")
        renderer.endTurn()
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        // Every bullet, the closing question, and the token line must all be
        // present — no clear/repaint may erase them.
        for (item in listOf("alpha.mov", "beta.mov", "gamma.mov", "delta.jpg", "epsilon.png", "zeta.txt", "eta/")) {
            assertTrue(item in captured, "'$item' missing from captured output")
        }
        assertTrue("里面有什么吗" in captured, "trailing question missing from captured output")
        assertTrue("tokens in=100 out=114" in captured, "token summary missing from captured output")
    }

    private fun dumbTerminal(out: ByteArrayOutputStream, width: Int, height: Int): Terminal {
        // TerminalBuilder.builder() silently promotes to a PTY-backed terminal
        // even with .streams(...), which drops our captured output on the floor.
        // Construct DumbTerminal directly so writes land in `out`.
        val t = org.jline.terminal.impl.DumbTerminal(
            "test",
            "dumb",
            ByteArrayInputStream(ByteArray(0)),
            out,
            StandardCharsets.UTF_8,
        )
        t.setSize(org.jline.terminal.Size(width, height))
        return t
    }
}
