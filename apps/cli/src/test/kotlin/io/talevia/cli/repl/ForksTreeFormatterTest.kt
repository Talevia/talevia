package io.talevia.cli.repl

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Coverage for [formatForksTree] — the `/forks` slash command's
 * pure-function renderer. The dispatcher wires the inputs by walking
 * `parentId` and calling `listChildSessions`; tests substitute
 * deterministic node lists.
 */
class ForksTreeFormatterTest {

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\\u001B\\[[0-9;?]*[A-Za-z]"), "")

    private fun node(id: String, title: String = id, createdAtEpochMs: Long = 1_700_000_000_000L, archived: Boolean = false) =
        ForksTreeNode(id = id, title = title, createdAtEpochMs = createdAtEpochMs, archived = archived)

    @Test fun rootWithNoForksRendersFriendlyHint() {
        Styles.setEnabled(false)
        val out = stripAnsi(
            formatForksTree(
                current = node("solo-session"),
                ancestors = emptyList(),
                children = emptyList(),
            ),
        )
        assertTrue("is a root with no forks" in out, "expected root-with-no-forks hint; got: $out")
        assertTrue("/fork" in out, "hint should point operators at /fork")
    }

    @Test fun chainAndChildrenRenderInRootToBranchOrder() {
        Styles.setEnabled(false)
        val ancestorsRootFirst = listOf(
            node("root-id", "Root"),
            node("mid-id", "Middle"),
        )
        val children = listOf(node("child-a", "ChildA"), node("child-b", "ChildB"))
        val current = node("active-id", "Active")
        val out = stripAnsi(formatForksTree(current, ancestorsRootFirst, children))
        // Header should advertise the structure to the operator without
        // requiring them to count nodes manually.
        assertTrue(out.startsWith("forks"), "header missing; got first line: ${out.lineSequence().firstOrNull()}")
        assertTrue("(root → current → children)" in out, "header missing layout hint; got: $out")
        // Order: root → middle → active → children. Verify by index of
        // each node id in the rendered string.
        val idxRoot = out.indexOf("root-id")
        val idxMid = out.indexOf("mid-id")
        val idxActive = out.indexOf("active-id")
        val idxChildA = out.indexOf("child-a")
        val idxChildB = out.indexOf("child-b")
        assertTrue(idxRoot in 0..idxMid, "root must precede middle; out=$out")
        assertTrue(idxMid in 0..idxActive, "middle must precede active; out=$out")
        assertTrue(idxActive in 0..idxChildA, "active must precede children; out=$out")
        assertTrue(idxChildA in 0..idxChildB, "children must keep input order; out=$out")
    }

    @Test fun currentSessionGetsHighlightMarker() {
        // The current session must be visually distinguishable so the
        // operator knows where they are without reading every id.
        Styles.setEnabled(false)
        val out = stripAnsi(
            formatForksTree(
                current = node("active-id"),
                ancestors = listOf(node("root-id")),
                children = listOf(node("child-id")),
            ),
        )
        // The current row is the only one prefixed with the "►" marker.
        val markerCount = "►".toRegex().findAll(out).count()
        assertTrue(markerCount == 1, "expected exactly one current-marker; out=$out")
        // Marker must appear on the `active-id` line, not root/child.
        val activeLine = out.lineSequence().single { "active-id" in it }
        assertTrue("►" in activeLine, "current marker missing on active row; line=$activeLine")
    }

    @Test fun blankTitleRendersUntitled() {
        Styles.setEnabled(false)
        val out = stripAnsi(
            formatForksTree(
                current = node("c", title = ""),
                ancestors = emptyList(),
                children = emptyList(),
            ),
        )
        assertTrue("(untitled)" in out, "blank title should render as `(untitled)`; got: $out")
    }

    @Test fun archivedNodeRendersArchivedNote() {
        Styles.setEnabled(false)
        val out = stripAnsi(
            formatForksTree(
                current = node("active"),
                ancestors = listOf(node("dead-root", title = "Dead", archived = true)),
                children = emptyList(),
            ),
        )
        val rootLine = out.lineSequence().single { "dead-root" in it }
        assertTrue("(archived)" in rootLine, "archived ancestor should carry note; line=$rootLine")
    }

    @Test fun longTitleIsTruncatedToDisplayCap() {
        Styles.setEnabled(false)
        val long = "x".repeat(120)
        val out = stripAnsi(
            formatForksTree(
                current = node("active", title = long),
                ancestors = emptyList(),
                children = emptyList(),
            ),
        )
        // Display cap is 60 chars (mirrors ProjectsTableFormatter).
        assertTrue("x".repeat(60) in out, "first 60 chars should appear")
        assertTrue("x".repeat(61) !in out, "truncation cap should hold; got: $out")
    }

    @Test fun deepChainIndentationGrowsMonotonically() {
        // Ancestors render with progressively deeper indents so the
        // chain's hierarchy is visually obvious. The current node sits
        // one level deeper than the deepest ancestor; children sit
        // one level deeper than that.
        Styles.setEnabled(false)
        val out = stripAnsi(
            formatForksTree(
                current = node("active"),
                ancestors = listOf(node("root"), node("mid")),
                children = listOf(node("kid")),
            ),
        )
        // Each subsequent line's leading-spaces count should be ≥ the
        // previous on the chain → current → child path.
        val lines = out.lineSequence().drop(1).filter { it.isNotBlank() }.toList()
        val indents = lines.map { line -> line.takeWhile { it == ' ' }.length }
        // We expect 4 rows (root, mid, active, kid) in that order.
        assertTrue(indents.size == 4, "expected 4 body rows; lines=$lines")
        assertTrue(indents[0] <= indents[1], "root indent ≤ mid indent")
        assertTrue(indents[1] <= indents[2], "mid indent ≤ active indent")
        assertTrue(indents[2] < indents[3], "active indent < child indent (children one level deeper)")
    }
}
