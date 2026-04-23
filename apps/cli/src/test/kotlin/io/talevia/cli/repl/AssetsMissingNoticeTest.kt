package io.talevia.cli.repl

import kotlinx.coroutines.runBlocking
import org.jline.terminal.Terminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [Renderer.assetsMissingNotice] — the CLI-side surfacing of
 * `BusEvent.AssetsMissing`, wired by
 * [io.talevia.cli.event.EventRouter]. The notice only shape-checks here;
 * the EventRouter wiring is covered indirectly because it calls through
 * to this same method with the event's `missing.map { it.originalPath }`.
 */
class AssetsMissingNoticeTest {

    @Test
    fun `notice prints header plus every path when under preview cap`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = false)

        renderer.assetsMissingNotice(
            listOf("/nas/clips/a.mp4", "/nas/clips/b.mp4", "/nas/clips/c.mp4"),
        )
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        assertTrue("3 assets don't resolve" in captured, "header missing: <$captured>")
        assertTrue("relink_asset" in captured, "relink hint missing: <$captured>")
        assertTrue("/nas/clips/a.mp4" in captured, "a.mp4 missing: <$captured>")
        assertTrue("/nas/clips/b.mp4" in captured, "b.mp4 missing: <$captured>")
        assertTrue("/nas/clips/c.mp4" in captured, "c.mp4 missing: <$captured>")
        assertTrue("(+" !in captured, "overflow tail should be absent: <$captured>")
    }

    @Test
    fun `notice singular header when only one asset missing`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = false)

        renderer.assetsMissingNotice(listOf("/nas/solo.mov"))
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        assertTrue("1 asset don't resolve" in captured, "singular header missing: <$captured>")
        // Style-free run so the message body should contain the path.
        assertTrue("/nas/solo.mov" in captured, "path missing: <$captured>")
    }

    @Test
    fun `notice summarises overflow beyond five paths`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = false)

        val paths = (1..12).map { "/nas/clips/clip-$it.mp4" }
        renderer.assetsMissingNotice(paths)
        terminal.flush()

        val captured = out.toString(StandardCharsets.UTF_8)
        assertTrue("12 assets" in captured, "count missing: <$captured>")
        // Preview only lists the first 5 paths; the rest land in the summary.
        for (i in 1..5) assertTrue("/nas/clips/clip-$i.mp4" in captured, "clip-$i missing")
        assertTrue("+7 more" in captured, "overflow tail missing: <$captured>")
        assertTrue("/nas/clips/clip-6.mp4" !in captured, "6th clip should not be printed")
    }

    @Test
    fun `empty missing list emits nothing`() = runBlocking {
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val renderer = Renderer(terminal, markdownEnabled = false, ansiEnabled = false)

        renderer.assetsMissingNotice(emptyList())
        terminal.flush()

        assertEquals("", out.toString(StandardCharsets.UTF_8))
    }

    private fun dumbTerminal(out: ByteArrayOutputStream): Terminal {
        val t = org.jline.terminal.impl.DumbTerminal(
            "test",
            "dumb",
            ByteArrayInputStream(ByteArray(0)),
            out,
            StandardCharsets.UTF_8,
        )
        t.setSize(org.jline.terminal.Size(120, 40))
        return t
    }
}
