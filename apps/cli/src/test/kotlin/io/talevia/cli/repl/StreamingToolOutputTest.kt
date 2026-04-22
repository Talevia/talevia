package io.talevia.cli.repl

import io.talevia.core.PartId
import kotlinx.coroutines.runBlocking
import org.jline.terminal.Terminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the in-place rewrite behaviours added for
 * `cli-streaming-tool-output-renderer`:
 *  - same-partId `toolRunning → toolCompleted/Failed` collapses onto one row
 *    via ANSI cursor-up + clear-line when `ansiEnabled = true`
 *  - consecutive `renderProgress` ticks on the same `jobId` repaint the last
 *    row in place; a different `jobId`, or any other write, breaks the slot
 *  - with `ansiEnabled = false` both paths fall back to the legacy
 *    "one line per update" shape (preserves the `MarkdownRepaintTest`
 *    invariant that every bullet after a tool block survives)
 */
class StreamingToolOutputTest {

    @Test
    fun `tool running to completed on same partId rewrites in place when ansiEnabled`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = true)

        val part = PartId("p-tool")
        renderer.toolRunning(part, "list_directory")
        renderer.toolCompleted(part, "list_directory", "listed 7 entries")
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        // The completion path should emit CursorUp(1) + CR + EraseLine before the ✓ line.
        assertTrue(
            "\u001B[1A" in captured,
            "expected cursor-up escape when repainting tool completion in place: <$captured>",
        )
        assertTrue(
            "\u001B[2K" in captured,
            "expected erase-line escape when repainting tool completion in place: <$captured>",
        )
        // Both the running marker and the completion marker should still be
        // present in the byte stream — the repaint writes ✓ over where ⟳
        // used to be, but a terminal replays both frames.
        assertTrue("⟳" in captured, "running marker missing")
        assertTrue("✓" in captured, "completed marker missing")
        assertTrue("listed 7 entries" in captured, "summary missing")
    }

    @Test
    fun `tool running to failed on same partId rewrites in place when ansiEnabled`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = true)

        val part = PartId("p-tool-fail")
        renderer.toolRunning(part, "bad_tool")
        renderer.toolFailed(part, "bad_tool", "boom")
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        assertTrue("\u001B[1A" in captured, "expected cursor-up for failure repaint")
        assertTrue("\u001B[2K" in captured, "expected erase-line for failure repaint")
        assertTrue("✗" in captured, "failure marker missing")
        assertTrue("boom" in captured, "failure message missing")
    }

    @Test
    fun `tool completion does not rewrite when ansiEnabled is false`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = false)

        val part = PartId("p-tool")
        renderer.toolRunning(part, "list_directory")
        renderer.toolCompleted(part, "list_directory", "listed 7")
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        assertFalse(
            "\u001B[1A" in captured,
            "did not expect cursor-up when ansiEnabled=false: <$captured>",
        )
        // Both lines should each end with a newline — no repaint, plain shell.
        assertTrue("⟳" in captured)
        assertTrue("✓" in captured)
    }

    @Test
    fun `tool completion without matching running line falls through to fresh line`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = true)

        val p1 = PartId("p-1")
        val p2 = PartId("p-2")
        renderer.toolRunning(p1, "tool-a")
        // A different partId starts running — invalidates the bottom slot for p1.
        renderer.toolRunning(p2, "tool-b")
        renderer.toolCompleted(p1, "tool-a", "done-a")
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        // The completion for p1 must NOT repaint p2's row. Count the cursor-up
        // escapes: zero expected on this path.
        val cursorUpCount = captured.split("\u001B[1A").size - 1
        assertEquals(
            0,
            cursorUpCount,
            "completion of a non-bottom tool should fresh-line, not repaint: <$captured>",
        )
    }

    @Test
    fun `renderProgress same jobId repaints in place`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = true)

        renderer.renderProgress("job-1", ratio = 0.1f, message = "started", thumbnailPath = null)
        renderer.renderProgress("job-1", ratio = 0.5f, message = "half", thumbnailPath = null)
        renderer.renderProgress("job-1", ratio = 0.8f, message = "almost", thumbnailPath = null)
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        // First tick is a fresh line; ticks 2 and 3 each repaint in place.
        val cursorUpCount = captured.split("\u001B[1A").size - 1
        assertEquals(
            2,
            cursorUpCount,
            "expected two in-place repaints across three ticks on the same jobId: <$captured>",
        )
        assertTrue("job-1" in captured)
        assertTrue("started" in captured || "half" in captured || "almost" in captured)
    }

    @Test
    fun `renderProgress different jobIds fresh-line each`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = true)

        renderer.renderProgress("job-a", ratio = 0.2f, message = "a-1", thumbnailPath = null)
        renderer.renderProgress("job-b", ratio = 0.3f, message = "b-1", thumbnailPath = null)
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        val cursorUpCount = captured.split("\u001B[1A").size - 1
        assertEquals(
            0,
            cursorUpCount,
            "two different jobIds must each fresh-line: <$captured>",
        )
    }

    @Test
    fun `renderProgress completion marker locks the slot so next tick fresh-lines`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = true)

        renderer.renderProgress("job-1", ratio = 0.5f, message = "half", thumbnailPath = null)
        renderer.renderProgress("job-1", ratio = 1.0f, message = "completed", thumbnailPath = null)
        // Spurious late tick on the same jobId (e.g. a re-emit): should fresh-line.
        renderer.renderProgress("job-1", ratio = 1.0f, message = "completed", thumbnailPath = null)
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        // Only the 0.5 → 1.0 tick counts as an in-place repaint.
        val cursorUpCount = captured.split("\u001B[1A").size - 1
        assertEquals(
            1,
            cursorUpCount,
            "completion must lock the slot so subsequent ticks don't repaint: <$captured>",
        )
        assertTrue("✓" in captured, "completed marker missing")
    }

    @Test
    fun `println between renderProgress ticks invalidates the slot`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = true)

        renderer.renderProgress("job-1", ratio = 0.2f, message = "a", thumbnailPath = null)
        renderer.println("· an interrupting line")
        renderer.renderProgress("job-1", ratio = 0.4f, message = "b", thumbnailPath = null)
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        val cursorUpCount = captured.split("\u001B[1A").size - 1
        assertEquals(
            0,
            cursorUpCount,
            "an intervening println must prevent the second tick from repainting: <$captured>",
        )
        assertTrue("interrupting line" in captured)
    }

    @Test
    fun `thumbnailPath is rendered in the progress line`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = false)

        renderer.renderProgress(
            "job-1",
            ratio = 0.5f,
            message = "preview",
            thumbnailPath = "/tmp/talevia/preview-001.jpg",
        )
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        assertTrue("preview=" in captured, "expected preview= marker in progress line")
        assertTrue("preview-001.jpg" in captured, "expected thumbnail basename in progress line")
    }

    private fun dumbTerminal(out: ByteArrayOutputStream, width: Int = 120, height: Int = 40): Terminal {
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
