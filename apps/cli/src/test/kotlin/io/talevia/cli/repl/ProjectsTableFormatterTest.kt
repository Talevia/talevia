package io.talevia.cli.repl

import io.talevia.core.domain.ProjectSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [formatProjectsTable] — the `/projects` slash command's
 * pure-function renderer over `ProjectStore.listSummaries()` rows. The
 * dispatcher wires `pathLookup` to `projects.pathOf(...)`; tests
 * substitute a deterministic map.
 */
class ProjectsTableFormatterTest {

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\\u001B\\[[0-9;?]*[A-Za-z]"), "")

    @Test fun emptyListRendersFreshHint() {
        Styles.setEnabled(false)
        val out = stripAnsi(formatProjectsTable(emptyList()) { null })
        assertTrue(
            "no projects in the recents registry yet" in out,
            "expected the empty-state hint; got: $out",
        )
        assertTrue(
            "create_project" in out,
            "hint should point operators at create_project / talevia new",
        )
    }

    @Test fun rendersIdTitleAndPathColumns() {
        Styles.setEnabled(false)
        val a = ProjectSummary(
            id = "proj-alpha",
            title = "Alpha",
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_700_000_000_000L,
        )
        val b = ProjectSummary(
            id = "proj-beta",
            title = "Beta the Sequel",
            createdAtEpochMs = 2_000L,
            updatedAtEpochMs = 1_700_000_001_000L,
        )
        val paths = mapOf("proj-alpha" to "/tmp/alpha", "proj-beta" to "/tmp/beta")
        val out = stripAnsi(formatProjectsTable(listOf(a, b)) { paths[it] })
        // Header reflects the count + the sort orientation.
        assertTrue(out.startsWith("projects 2 project(s)"), "header missing count; got: ${out.lineSequence().firstOrNull()}")
        assertTrue("most-recently-updated first" in out, "sort orientation missing")
        // Both ids + titles + paths show up.
        assertTrue("proj-alpha" in out)
        assertTrue("proj-beta" in out)
        assertTrue("Alpha" in out)
        assertTrue("Beta the Sequel" in out)
        assertTrue("/tmp/alpha" in out)
        assertTrue("/tmp/beta" in out)
    }

    @Test fun mostRecentlyUpdatedFirst() {
        // Sort regression guard — agent's `list_projects(sortBy=updated-desc)`
        // and CLI's `/projects` must agree on ordering so the user can
        // cross-reference between the two views.
        Styles.setEnabled(false)
        val older = ProjectSummary(
            id = "proj-older",
            title = "Older",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1_000L,
        )
        val newer = ProjectSummary(
            id = "proj-newer",
            title = "Newer",
            createdAtEpochMs = 2L,
            updatedAtEpochMs = 2_000L,
        )
        // Pass them out of order — the formatter should resort.
        val out = stripAnsi(formatProjectsTable(listOf(older, newer)) { null })
        val newerIdx = out.indexOf("proj-newer")
        val olderIdx = out.indexOf("proj-older")
        assertTrue(newerIdx in 0..olderIdx, "newer should appear before older; out=$out")
    }

    @Test fun missingPathRendersDash() {
        // Path lookup may return null when the bundle is registered in
        // the recents but its on-disk path is no longer reachable
        // (manually moved, machine boundary). Render `—` rather than
        // crashing or printing "null".
        Styles.setEnabled(false)
        val s = ProjectSummary(
            id = "proj-orphaned",
            title = "Orphaned",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1_700_000_000_000L,
        )
        val out = stripAnsi(formatProjectsTable(listOf(s)) { null })
        assertTrue("proj-orphaned" in out)
        assertTrue("—" in out, "dash placeholder missing for null path; out=$out")
    }

    @Test fun blankTitleRendersUntitledMarker() {
        Styles.setEnabled(false)
        val s = ProjectSummary(
            id = "proj-blank",
            title = "",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1_700_000_000_000L,
        )
        val out = stripAnsi(formatProjectsTable(listOf(s)) { "/tmp/x" })
        assertTrue("(untitled)" in out, "blank title should render as `(untitled)`; out=$out")
    }

    @Test fun longTitlesAreTruncated() {
        Styles.setEnabled(false)
        val long = "x".repeat(120)
        val s = ProjectSummary(
            id = "proj-long",
            title = long,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val out = stripAnsi(formatProjectsTable(listOf(s)) { "/p" })
        // Display cap is 60 chars.
        assertTrue("x".repeat(60) in out, "first 60 chars should appear")
        assertEquals(false, "x".repeat(61) in out, "truncation cap should hold")
    }
}
