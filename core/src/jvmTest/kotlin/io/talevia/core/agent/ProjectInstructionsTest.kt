package io.talevia.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [formatProjectInstructionsSuffix] —
 * `core/agent/ProjectInstructions.kt`. The host-injected
 * project / user instruction renderer (AGENTS.md, CLAUDE.md
 * etc.) that produces the system-prompt suffix. Cycle 162
 * audit: 42 LOC, 0 transitive direct test refs (the function
 * is exercised indirectly through SystemPromptComposer's
 * full-prompt assembly tests, but its own contracts —
 * empty-collapsing, blank-filtering, header literals,
 * delimiter shape, trailing-whitespace trim, caller-
 * preserved ordering — are not pinned).
 *
 * Four correctness contracts pinned:
 *
 * 1. **Empty / all-blank input → empty string, unconditionally.**
 *    Per kdoc: "Returns the empty string when [instructions]
 *    is empty or all contents are blank, so callers can pass
 *    it through `extraSuffix` unconditionally." Drift to
 *    "always emit header" would inject a bare `# Project
 *    context` section into every prompt without instructions.
 *
 * 2. **Header is literally `# Project context` + the
 *    documented preamble paragraph.** Drift in either
 *    string would change the model's behavior on every
 *    multi-instruction project — these are load-bearing
 *    cues for "treat as authoritative" + "later wins" tie-
 *    breaker. The kdoc documents both; pin the literals.
 *
 * 3. **Ordering preserved verbatim.** The kdoc says ordering
 *    is the caller's responsibility (outermost-first / inner-
 *    most-last). Drift to "sort alphabetically" would shuffle
 *    the convention and break the documented "later wins"
 *    rule downstream LLM behavior depends on.
 *
 * 4. **Trailing whitespace in content is trimmed; per-
 *    instruction blocks separated by exactly one blank
 *    line.** The kdoc shows `trimEnd()` plus a between-block
 *    blank-line. Drift to leading double-blank or stripped
 *    final newline would flake LLM line-counting.
 */
class ProjectInstructionsTest {

    // ── empty / all-blank → empty string ────────────────────────

    @Test fun emptyListReturnsEmptyString() {
        // Pin: empty input collapses unconditionally so
        // callers don't need to guard with `if (list.isEmpty())`.
        assertEquals("", formatProjectInstructionsSuffix(emptyList()))
    }

    @Test fun allBlankInstructionsReturnEmptyString() {
        // Pin: filter is `isNotBlank`, so contents that are
        // pure whitespace (spaces / tabs / newlines) collapse
        // out. Drift to `isNotEmpty` would emit a header
        // section with whitespace-only files — useless noise
        // in the prompt that an LLM might still try to
        // interpret.
        val instructions = listOf(
            ProjectInstruction(path = "/proj/AGENTS.md", content = ""),
            ProjectInstruction(path = "/proj/CLAUDE.md", content = "   "),
            ProjectInstruction(path = "/proj/README.md", content = "\n\t\n  "),
        )
        assertEquals("", formatProjectInstructionsSuffix(instructions))
    }

    @Test fun mixedBlankAndContentDropsOnlyTheBlanks() {
        // Pin: blank filter is per-entry, not all-or-nothing.
        // Drift to "skip the whole list if any are blank"
        // would silently drop all guidance the moment one
        // file is empty.
        val instructions = listOf(
            ProjectInstruction(path = "/proj/empty.md", content = ""),
            ProjectInstruction(path = "/proj/CLAUDE.md", content = "use kotlin"),
            ProjectInstruction(path = "/proj/blank.md", content = "  \n  "),
        )
        val out = formatProjectInstructionsSuffix(instructions)
        assertTrue("# Project context" in out, "header surfaces despite blanks")
        assertTrue("/proj/CLAUDE.md" in out, "content file's path surfaces")
        assertTrue("use kotlin" in out, "content surfaces")
        assertFalse("/proj/empty.md" in out, "blank file's path stays out: $out")
        assertFalse("/proj/blank.md" in out, "whitespace-only file's path stays out: $out")
    }

    // ── header + preamble literals ──────────────────────────────

    @Test fun headerLiteralIsProjectContext() {
        // Marquee preamble pin: the "# Project context" h1 is
        // load-bearing for the LLM's interpretation. Drift to
        // "## Project context" or "# Project instructions"
        // would change the model's section-segmentation.
        val out = formatProjectInstructionsSuffix(
            listOf(ProjectInstruction(path = "/x", content = "y")),
        )
        assertTrue(out.startsWith("# Project context\n\n"), "header is exact; got: $out")
    }

    @Test fun preambleParagraphMentionsAuthoritativeAndLaterWins() {
        // Pin: the preamble paragraph's two key phrases —
        // "authoritative" and "prefer the later one" — drive
        // LLM tie-breaking on conflicting instructions. Drift
        // to a softer phrasing or removal would silently
        // change conflict resolution behavior across every
        // multi-AGENTS.md project.
        val out = formatProjectInstructionsSuffix(
            listOf(ProjectInstruction(path = "/x", content = "y")),
        )
        assertTrue("authoritative" in out, "preamble retains authority cue")
        assertTrue(
            "prefer the later one" in out,
            "preamble retains tie-breaker rule",
        )
        assertTrue(
            "outermost-first / innermost-last" in out,
            "preamble retains ordering convention so the model knows which file is 'later'",
        )
    }

    // ── ordering preserved ──────────────────────────────────────

    @Test fun multipleInstructionsAppearInCallerProvidedOrder() {
        // The marquee ordering pin: kdoc says "Ordering is
        // the caller's responsibility." Drift to any sort
        // (alphabetical / by-length / reversed) would break
        // the documented outermost→innermost convention,
        // making "later wins" point to the wrong file.
        val instructions = listOf(
            ProjectInstruction(path = "/zebra/AGENTS.md", content = "outer rule"),
            ProjectInstruction(path = "/apple/AGENTS.md", content = "middle rule"),
            ProjectInstruction(path = "/middle/AGENTS.md", content = "inner rule"),
        )
        val out = formatProjectInstructionsSuffix(instructions)
        val zebraIdx = out.indexOf("/zebra/AGENTS.md")
        val appleIdx = out.indexOf("/apple/AGENTS.md")
        val middleIdx = out.indexOf("/middle/AGENTS.md")
        assertTrue(zebraIdx > 0, "zebra path present")
        assertTrue(appleIdx > 0, "apple path present")
        assertTrue(middleIdx > 0, "middle path present")
        assertTrue(
            zebraIdx < appleIdx,
            "zebra (input[0]) precedes apple (input[1]) — caller order preserved, NOT alphabetical",
        )
        assertTrue(appleIdx < middleIdx, "apple (input[1]) precedes middle (input[2])")
    }

    // ── per-instruction block shape ─────────────────────────────

    @Test fun eachInstructionBlockIsLevelTwoHeaderWithPath() {
        // Pin: each instruction is `## <path>` (level-2
        // header). Drift to level-1 / level-3 / a non-header
        // delimiter would change how the LLM parses the
        // section structure.
        val out = formatProjectInstructionsSuffix(
            listOf(
                ProjectInstruction(path = "/proj/CLAUDE.md", content = "rule one"),
                ProjectInstruction(path = "/proj/AGENTS.md", content = "rule two"),
            ),
        )
        assertTrue("## /proj/CLAUDE.md\n" in out, "first path is level-2 header; got: $out")
        assertTrue("## /proj/AGENTS.md\n" in out, "second path is level-2 header; got: $out")
        // NOT level-3 (`### path`) and NOT level-1 (`# /path`).
        assertFalse("### /proj/CLAUDE.md" in out, "not level-3")
        assertFalse("\n# /proj/CLAUDE.md" in out, "not level-1")
    }

    @Test fun trailingWhitespaceInContentIsTrimmed() {
        // Pin: kdoc shows `instruction.content.trimEnd()`.
        // Drift to encoding the raw content would leak
        // trailing newlines / spaces, leaving extra blank
        // lines in the prompt.
        val out = formatProjectInstructionsSuffix(
            listOf(
                ProjectInstruction(
                    path = "/proj/AGENTS.md",
                    content = "rule with trailing\n\n   \n",
                ),
            ),
        )
        // After "rule with trailing", the formatter writes
        // exactly ONE \n (closing the content), then the
        // suffix ends. NOT three blank lines from the raw
        // trailing whitespace.
        assertTrue(
            out.endsWith("rule with trailing\n"),
            "trailing whitespace trimmed; got tail: '${out.takeLast(40)}'",
        )
    }

    @Test fun leadingWhitespaceInContentIsPreserved() {
        // Pin: only `trimEnd` is called — leading whitespace
        // (e.g. indented content for a code block) is
        // preserved.
        val out = formatProjectInstructionsSuffix(
            listOf(
                ProjectInstruction(
                    path = "/proj/x.md",
                    content = "    indented line",
                ),
            ),
        )
        assertTrue("    indented line" in out, "leading spaces survive; got: $out")
    }

    @Test fun multipleInstructionsAreSeparatedByDoubleBlankLine() {
        // Pin: between two instruction blocks, the gap is
        // three '\n's (two blank lines): closing `\n` after
        // content + the "not-last" extra `\n` + the next
        // iteration's leading `\n`. Drift to single-blank
        // would change the LLM's section-boundary detection;
        // drift to four-blank would inflate the prompt.
        val out = formatProjectInstructionsSuffix(
            listOf(
                ProjectInstruction(path = "/a", content = "first"),
                ProjectInstruction(path = "/b", content = "second"),
            ),
        )
        // The pattern between the two blocks: "first\n\n\n##
        // /b" — three `\n`s = two blank lines.
        assertTrue(
            "first\n\n\n## /b" in out,
            "exactly three newlines between blocks; got: $out",
        )
        // Negative pin: NOT a single blank.
        assertFalse(
            "first\n\n## /b" in out && "first\n\n\n## /b" !in out,
            "must be three newlines, not two",
        )
    }

    @Test fun lastInstructionBlockHasNoTrailingBlankLine() {
        // Pin: the trailing-blank-line is conditional on
        // `index < nonBlank.size - 1`. The output ends with
        // a single `\n` after the last content's `trimEnd`.
        // Drift to "always append blank line" would cause
        // SystemPromptComposer's final assembly to have
        // double trailing blank when this suffix is appended.
        val out = formatProjectInstructionsSuffix(
            listOf(
                ProjectInstruction(path = "/a", content = "first"),
                ProjectInstruction(path = "/b", content = "second"),
            ),
        )
        assertTrue(out.endsWith("second\n"), "ends with single newline; got tail: '${out.takeLast(20)}'")
        assertFalse(out.endsWith("\n\n"), "no double trailing blank")
    }

    // ── single-instruction smoke ───────────────────────────────

    @Test fun singleInstructionRendersFullStructure() {
        // Smoke test pinning the exact bytes of a one-
        // instruction render. If something subtly shifts
        // (extra newline, missing header), this golden
        // catches it.
        val out = formatProjectInstructionsSuffix(
            listOf(
                ProjectInstruction(
                    path = "/proj/AGENTS.md",
                    content = "you are an expert kotlin engineer",
                ),
            ),
        )
        // Header present.
        assertTrue(out.startsWith("# Project context\n\n"))
        // Path block header.
        assertTrue("## /proj/AGENTS.md\n\n" in out)
        // Content body.
        assertTrue("you are an expert kotlin engineer" in out)
        // Tail is the content + a single \n (no trailing
        // blank).
        assertTrue(out.endsWith("you are an expert kotlin engineer\n"))
    }

    // ── data class sanity ───────────────────────────────────────

    @Test fun projectInstructionDataClassEqualityAndCopy() {
        // Pin: ProjectInstruction is a data class with
        // value-based equality. Caller code sometimes builds
        // a Set<ProjectInstruction> for dedup; drift to a
        // non-data class would break Set semantics silently.
        val a = ProjectInstruction(path = "/p", content = "c")
        val b = ProjectInstruction(path = "/p", content = "c")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = a.copy(content = "different")
        assertTrue(a != c)
    }
}
