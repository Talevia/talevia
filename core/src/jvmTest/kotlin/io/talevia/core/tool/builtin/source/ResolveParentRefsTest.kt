package io.talevia.core.tool.builtin.source

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [resolveParentRefs] —
 * `core/tool/builtin/source/ResolveParentRefs.kt`. The
 * cross-ref validator at the `define_*` tool boundary that
 * keeps the Source DAG cycle-free + ghost-free. Cycle 167
 * audit: 47 LOC, 0 direct test refs (exercised indirectly
 * through full-tool tests like `SourceToolsTest`, but the
 * boundary contracts — empty fast-path, blank-skip, self-
 * reject, unknown-reject, dedup-with-order — were never
 * pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Self-reference + unknown-id throw `IllegalArgumentException`
 *    via `require`.** Per kdoc: "fail loudly at the tool
 *    boundary instead." Drift to "skip silently" would
 *    introduce ghost parents into `SourceNode.parents`,
 *    cascading into corrupt stale-propagation reports
 *    out of `source_query(select=nodes)`. Pinned via
 *    `assertFailsWith<IllegalArgumentException>` with the
 *    documented error-message phrases.
 *
 * 2. **Blank / whitespace-only ids skipped (NOT rejected,
 *    NOT counted).** Per kdoc: "Blank / empty ids are
 *    skipped (LLMs occasionally emit `''`)." A list
 *    `["a", "", "b"]` resolves to refs for "a" + "b"
 *    without throwing. Drift to either "throw on empty"
 *    (would surface false rejections from sloppy LLM
 *    output) or "treat empty as id" (would crash on
 *    unknown-id check) breaks tool ergonomics.
 *
 * 3. **Dedup preserves caller order.** Per kdoc: "repeats
 *    in the parent list carry no extra meaning." Input
 *    `["b", "a", "b"]` produces `[SourceRef(b),
 *    SourceRef(a)]` — first occurrence wins, NOT last,
 *    NOT reordered. Drift to "last wins" or "sort
 *    alphabetically" would shuffle `SourceNode.parents`
 *    in ways the DAG-walking code might rely on.
 */
class ResolveParentRefsTest {

    private fun makeSource(vararg ids: String): Source {
        val nodes = ids.map { id ->
            SourceNode(id = SourceNodeId(id), kind = "test")
        }
        return Source(nodes = nodes)
    }

    // ── empty input fast-path ──────────────────────────────────

    @Test fun emptyInputReturnsEmptyList() {
        // Pin: explicit `if (parentIds.isEmpty()) return
        // emptyList()` short-circuit. Drift would still
        // produce empty but allocate the index map +
        // mutable collections needlessly.
        val source = makeSource("a", "b")
        val out = resolveParentRefs(
            parentIds = emptyList(),
            source = source,
            self = SourceNodeId("self"),
        )
        assertEquals(emptyList(), out)
    }

    // ── happy path ─────────────────────────────────────────────

    @Test fun singleValidIdResolvesToSingleRef() {
        val source = makeSource("parent-a", "self")
        val out = resolveParentRefs(
            parentIds = listOf("parent-a"),
            source = source,
            self = SourceNodeId("self"),
        )
        assertEquals(listOf(SourceRef(SourceNodeId("parent-a"))), out)
    }

    @Test fun multipleValidIdsResolveInOrder() {
        // Pin: caller-order preserved, NOT alphabetical.
        // Drift to sort would break documented "list order
        // carries semantic information for the LLM."
        val source = makeSource("zebra", "apple", "middle", "self")
        val out = resolveParentRefs(
            parentIds = listOf("zebra", "apple", "middle"),
            source = source,
            self = SourceNodeId("self"),
        )
        assertContentEquals(
            listOf(
                SourceRef(SourceNodeId("zebra")),
                SourceRef(SourceNodeId("apple")),
                SourceRef(SourceNodeId("middle")),
            ),
            out,
        )
    }

    // ── blank / whitespace skipping ────────────────────────────

    @Test fun emptyStringIdsAreSkipped() {
        // Marquee blank-skip pin: "" between two valid ids.
        // Drift to throw would reject sloppy LLM output;
        // drift to "treat as id" would unknown-id-reject
        // because "" is not a valid SourceNodeId.
        val source = makeSource("a", "b", "self")
        val out = resolveParentRefs(
            parentIds = listOf("a", "", "b"),
            source = source,
            self = SourceNodeId("self"),
        )
        assertContentEquals(
            listOf(
                SourceRef(SourceNodeId("a")),
                SourceRef(SourceNodeId("b")),
            ),
            out,
        )
    }

    @Test fun whitespaceOnlyIdsAreSkippedAfterTrim() {
        // Pin: `trimmed.isEmpty()` after `trim()` — pure
        // whitespace ("   ", "\t\n") collapses to empty
        // → skipped. Drift to "no-trim" would treat whitespace
        // as id and unknown-id-reject every whitespace blob.
        val source = makeSource("a", "self")
        val out = resolveParentRefs(
            parentIds = listOf("a", "   ", "\t\n  "),
            source = source,
            self = SourceNodeId("self"),
        )
        assertContentEquals(listOf(SourceRef(SourceNodeId("a"))), out)
    }

    @Test fun allBlankInputProducesEmptyList() {
        // Pin: input that's all-blank after trim → empty.
        // Distinct from the empty-input fast-path (this
        // path goes through the loop).
        val source = makeSource("a", "self")
        val out = resolveParentRefs(
            parentIds = listOf("", "  ", "\t"),
            source = source,
            self = SourceNodeId("self"),
        )
        assertEquals(emptyList(), out)
    }

    @Test fun leadingAndTrailingWhitespaceIsTrimmedFromValidIds() {
        // Pin: `"  parent-a  "` is trimmed → resolves the
        // same as "parent-a". Drift to "no-trim" would
        // unknown-id-reject every padded id.
        val source = makeSource("parent-a", "self")
        val out = resolveParentRefs(
            parentIds = listOf("  parent-a  "),
            source = source,
            self = SourceNodeId("self"),
        )
        assertEquals(listOf(SourceRef(SourceNodeId("parent-a"))), out)
    }

    // ── self-reference rejection ───────────────────────────────

    @Test fun selfReferenceThrowsIllegalArgumentException() {
        // Marquee cycle-rejection pin. Drift to "skip" would
        // let cycles into the DAG; drift to "throw silently"
        // (no message) would break LLM debugging.
        val source = makeSource("self", "other")
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveParentRefs(
                parentIds = listOf("self"),
                source = source,
                self = SourceNodeId("self"),
            )
        }
        // Documented error-message phrases.
        assertTrue(
            "must not reference the node being defined" in (ex.message ?: ""),
            "expected cycle-rejection phrase; got: ${ex.message}",
        )
        assertTrue(
            "self" in (ex.message ?: ""),
            "expected self id in message; got: ${ex.message}",
        )
    }

    @Test fun selfReferenceWithSurroundingValidIdsStillThrows() {
        // Pin: even with valid neighbors, a self-ref
        // anywhere in the list throws. Drift to "skip the
        // self-ref but accept the rest" would silently
        // drop user intent.
        val source = makeSource("a", "b", "self")
        assertFailsWith<IllegalArgumentException> {
            resolveParentRefs(
                parentIds = listOf("a", "self", "b"),
                source = source,
                self = SourceNodeId("self"),
            )
        }
    }

    @Test fun selfReferenceCheckRunsAfterTrim() {
        // Pin: "  self  " trims to "self" before the self-
        // check. Drift to "check before trim" would let
        // padded self-references through.
        val source = makeSource("self", "a")
        assertFailsWith<IllegalArgumentException> {
            resolveParentRefs(
                parentIds = listOf("  self  "),
                source = source,
                self = SourceNodeId("self"),
            )
        }
    }

    // ── unknown-id rejection ──────────────────────────────────

    @Test fun unknownIdThrowsIllegalArgumentException() {
        // Marquee unknown-id-reject pin. Drift would let
        // ghost refs into SourceNode.parents.
        val source = makeSource("real-parent", "self")
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveParentRefs(
                parentIds = listOf("ghost-parent"),
                source = source,
                self = SourceNodeId("self"),
            )
        }
        assertTrue(
            "not found in project" in (ex.message ?: ""),
            "expected unknown-id phrase; got: ${ex.message}",
        )
        assertTrue(
            "ghost-parent" in (ex.message ?: ""),
            "expected ghost id in message; got: ${ex.message}",
        )
    }

    @Test fun unknownIdMessageMentionsImportFallback() {
        // Pin: error message includes the documented
        // recovery hint ("source_node_action(action=import)").
        // The LLM relies on this phrasing to know the
        // alternative. Drift to a terser message would break
        // recovery.
        val source = makeSource("real-parent", "self")
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveParentRefs(
                parentIds = listOf("ghost-parent"),
                source = source,
                self = SourceNodeId("self"),
            )
        }
        assertTrue(
            "source_node_action" in (ex.message ?: "") ||
                "import" in (ex.message ?: ""),
            "expected import-fallback hint; got: ${ex.message}",
        )
    }

    @Test fun firstUnknownIdRejectsBeforeProcessingTail() {
        // Pin: the require runs per-id in order — first
        // unknown surfaces immediately (no batch validation).
        // Drift to "validate all then throw" would change
        // the error message to mention the wrong id.
        val source = makeSource("a", "self")
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveParentRefs(
                parentIds = listOf("a", "first-ghost", "second-ghost"),
                source = source,
                self = SourceNodeId("self"),
            )
        }
        assertTrue(
            "first-ghost" in (ex.message ?: ""),
            "first unknown reported, NOT second; got: ${ex.message}",
        )
    }

    // ── dedup with order preservation ─────────────────────────

    @Test fun duplicateIdsDeduplicatedFirstWins() {
        // The marquee dedup-order pin. Input "b a b" →
        // output [b, a]. Drift to "last wins" would produce
        // [a, b]; drift to "no dedup" would produce [b, a, b].
        val source = makeSource("a", "b", "self")
        val out = resolveParentRefs(
            parentIds = listOf("b", "a", "b"),
            source = source,
            self = SourceNodeId("self"),
        )
        assertContentEquals(
            listOf(
                SourceRef(SourceNodeId("b")),
                SourceRef(SourceNodeId("a")),
            ),
            out,
        )
    }

    @Test fun manyDuplicatesCollapseToFirstOccurrencesOnly() {
        // Pin: deeper dedup case. "a a b a c b" → [a, b, c].
        val source = makeSource("a", "b", "c", "self")
        val out = resolveParentRefs(
            parentIds = listOf("a", "a", "b", "a", "c", "b"),
            source = source,
            self = SourceNodeId("self"),
        )
        assertContentEquals(
            listOf(
                SourceRef(SourceNodeId("a")),
                SourceRef(SourceNodeId("b")),
                SourceRef(SourceNodeId("c")),
            ),
            out,
        )
    }

    @Test fun duplicateAfterTrimAlsoCollapses() {
        // Pin: "a", "  a  ", "a   " all resolve to the same
        // SourceNodeId after trim → dedup catches them.
        // Drift to "dedup-before-trim" would let trimmed
        // duplicates through.
        val source = makeSource("a", "self")
        val out = resolveParentRefs(
            parentIds = listOf("a", "  a  ", "a   "),
            source = source,
            self = SourceNodeId("self"),
        )
        assertEquals(listOf(SourceRef(SourceNodeId("a"))), out)
    }

    // ── interaction: blank skip + dedup + order ────────────────

    @Test fun blankSkippingAndDedupCombineCorrectly() {
        // Pin: blanks don't count toward dedup tracking.
        // "a", "", "b", "  ", "a", "" → [a, b]. Drift to
        // "blanks consume a slot in seen-set" would still
        // work but is fragile to refactor.
        val source = makeSource("a", "b", "self")
        val out = resolveParentRefs(
            parentIds = listOf("a", "", "b", "  ", "a", ""),
            source = source,
            self = SourceNodeId("self"),
        )
        assertContentEquals(
            listOf(
                SourceRef(SourceNodeId("a")),
                SourceRef(SourceNodeId("b")),
            ),
            out,
        )
    }

    // ── edge: unknown-check runs even after trim ──────────────

    @Test fun unknownIdAfterTrimStillRejected() {
        // Pin: trim runs before the in-index check. "  ghost  "
        // becomes "ghost" before lookup → rejected.
        val source = makeSource("real-parent", "self")
        assertFailsWith<IllegalArgumentException> {
            resolveParentRefs(
                parentIds = listOf("  ghost  "),
                source = source,
                self = SourceNodeId("self"),
            )
        }
    }
}
