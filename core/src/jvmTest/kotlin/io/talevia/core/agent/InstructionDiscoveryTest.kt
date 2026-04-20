package io.talevia.core.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the [InstructionDiscovery.discover] walk so the AGENTS.md /
 * CLAUDE.md loader can't silently regress on someone running the CLI in a
 * deeply nested project dir.
 */
class InstructionDiscoveryTest {

    @Test
    fun findsAgentsMdInStartDir() {
        val root = newTempDir()
        File(root, "AGENTS.md").writeText("project rules")

        val found = InstructionDiscovery.discover(
            startDir = root,
            includeGlobal = false,
        )
        assertEquals(1, found.size)
        assertEquals("project rules", found.single().content)
        assertTrue(found.single().path.endsWith("AGENTS.md"))
    }

    @Test
    fun walksUpThroughParentDirsOuterFirst() {
        val parent = newTempDir()
        val child = File(parent, "child").apply { mkdirs() }
        File(parent, "AGENTS.md").writeText("outer")
        File(child, "AGENTS.md").writeText("inner")

        val found = InstructionDiscovery.discover(
            startDir = child,
            includeGlobal = false,
        )
        assertEquals(listOf("outer", "inner"), found.map { it.content })
    }

    @Test
    fun picksUpBothAgentsAndClaudeInSameDir() {
        val root = newTempDir()
        File(root, "AGENTS.md").writeText("agents")
        File(root, "CLAUDE.md").writeText("claude")

        val found = InstructionDiscovery.discover(
            startDir = root,
            includeGlobal = false,
        )
        assertEquals(setOf("agents", "claude"), found.map { it.content }.toSet())
        // AGENTS.md precedes CLAUDE.md at the same directory level (fileNames order).
        assertEquals("agents", found.first().content)
    }

    @Test
    fun dedupesWhenStartDirIsSameCanonicalAsParent() {
        val root = newTempDir()
        File(root, "AGENTS.md").writeText("once")

        val found = InstructionDiscovery.discover(
            startDir = root,
            includeGlobal = false,
        )
        assertEquals(1, found.size)
    }

    @Test
    fun enforcesMaxBytesPerFile() {
        val root = newTempDir()
        File(root, "AGENTS.md").writeText("x".repeat(200))

        val found = InstructionDiscovery.discover(
            startDir = root,
            includeGlobal = false,
            maxBytesPerFile = 50,
        )
        assertTrue(found.isEmpty(), "oversized files must be skipped silently")
    }

    @Test
    fun enforcesMaxTotalBytes() {
        val parent = newTempDir()
        val child = File(parent, "child").apply { mkdirs() }
        // 100 bytes each → total 200 → only the first (outer) file fits under a
        // 120-byte cap.
        File(parent, "AGENTS.md").writeText("a".repeat(100))
        File(child, "AGENTS.md").writeText("b".repeat(100))

        val found = InstructionDiscovery.discover(
            startDir = child,
            includeGlobal = false,
            maxBytesPerFile = 1_000,
            maxTotalBytes = 120,
        )
        assertEquals(1, found.size, "second file must be skipped once cumulative cap is crossed")
        assertEquals("a".repeat(100), found.single().content)
    }

    @Test
    fun skipsBlankFilesSoTheyDoNotWasteContextSlot() {
        val root = newTempDir()
        File(root, "AGENTS.md").writeText("   \n\t\n")

        val found = InstructionDiscovery.discover(
            startDir = root,
            includeGlobal = false,
        )
        assertTrue(found.isEmpty(), "blank file should not count as an instruction")
    }

    @Test
    fun globalsLandBeforeProjectSoProjectWinsOnConflict() {
        val fakeHome = newTempDir()
        val projectRoot = newTempDir()

        File(fakeHome, ".claude").apply { mkdirs() }
        File(fakeHome, ".claude/CLAUDE.md").writeText("global")
        File(projectRoot, "AGENTS.md").writeText("project")

        val found = InstructionDiscovery.discover(
            startDir = projectRoot,
            includeGlobal = true,
            home = fakeHome,
        )
        assertEquals(listOf("global", "project"), found.map { it.content })
    }

    @Test
    fun formatterEmitsEmptyStringOnEmptyInput() {
        assertEquals("", formatProjectInstructionsSuffix(emptyList()))
        assertEquals(
            "",
            formatProjectInstructionsSuffix(listOf(ProjectInstruction("/p", "   "))),
        )
    }

    @Test
    fun formatterIncludesPathHeaderAndContent() {
        val out = formatProjectInstructionsSuffix(
            listOf(
                ProjectInstruction("/a/AGENTS.md", "rule-outer"),
                ProjectInstruction("/a/b/AGENTS.md", "rule-inner"),
            ),
        )
        assertTrue(out.startsWith("# Project context"))
        assertTrue("## /a/AGENTS.md" in out)
        assertTrue("## /a/b/AGENTS.md" in out)
        assertTrue("rule-outer" in out)
        assertTrue("rule-inner" in out)
        // Inner appears later than outer → tail weight wins on conflict.
        assertTrue(out.indexOf("rule-inner") > out.indexOf("rule-outer"))
    }

    @Test
    fun missingStartDirYieldsEmptyList() {
        val missing = File("/this/path/definitely/does/not/exist/${System.nanoTime()}")
        val found = InstructionDiscovery.discover(startDir = missing, includeGlobal = false)
        assertFalse(found.isEmpty() && missing.exists(), "sanity: path should be missing")
        assertTrue(found.isEmpty())
    }

    private fun newTempDir(): File =
        Files.createTempDirectory("talevia-instruction-discovery-").toFile().also { it.deleteOnExit() }
}
