package io.talevia.core.tool.builtin.source

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [executeLiveImport] and [topoCollectForLiveImport] —
 * `core/tool/builtin/source/ImportSourceNodeLiveHandler.kt`. The
 * cross-project live-import handler that copies a [SourceNode] (and any
 * transitive parents) from one open project into another, mirroring
 * [executeEnvelopeImport] but reading from a live in-memory project
 * rather than a serialised envelope. Cycle 222 audit: 0 direct test
 * refs (sibling envelope handler covered by cycle 210; live handler has
 * been on its own since the cycle 136 dispatcher fold).
 *
 * Same audit-pattern fallback as cycles 207-221.
 *
 * Six correctness contracts pinned:
 *
 *  1. **Self-import guard.** `fromProjectId == toProjectId` MUST fail
 *     loud with a remediation hint pointing at
 *     `source_node_action(action=add)`. Drift to silent no-op /
 *     in-place rename would let the LLM accidentally duplicate or
 *     overwrite source nodes via the import path.
 *
 *  2. **Source-project-not-found.** A non-existent `fromProjectId`
 *     fails loud with the project id cited. Drift to a null-pointer
 *     would surface a stacktrace to the agent instead of a remediation
 *     hint.
 *
 *  3. **Dedup-by-contentHash.** A node whose contentHash matches an
 *     existing node in the target project is REUSED (not added again);
 *     `skippedDuplicate=true`; subsequent nodes that referenced the
 *     dedupped parent get the existing id remapped via the `remap`
 *     map. Marquee dedup contract — import-into-fresh-project of an
 *     already-imported chain is idempotent.
 *
 *  4. **Collision-on-id without contentHash match.** Same id +
 *     different contentHash on the target → fail with a remediation
 *     hint pointing at `newNodeId` / `source_node_action(action=remove)`.
 *     Drift to "auto-overwrite" would silently mutate target data.
 *
 *  5. **Parent-ref remap with effectiveHash recompute.** When a
 *     parent's id was remapped (either via dedup or via the leaf
 *     `newNodeId` rename), the child's `parents` list uses the new
 *     ids AND the child's contentHash is recomputed against those
 *     remapped parents — otherwise the dedup check on the child would
 *     compare an apples-to-oranges hash and miss legitimate duplicates.
 *
 *  6. **`newNodeId` renames the leaf only.** When `request.newNodeId`
 *     is non-blank, only the leaf node (the one named by `fromNodeId`)
 *     is renamed; transitive parents keep their source-side ids. Drift
 *     to "rename every node" would break unrelated downstream
 *     references.
 *
 * Plus `topoCollectForLiveImport` direct pins (parents-first ordering;
 * missing-parent → error with "referenced from import chain" phrasing)
 * and outcome shape pins (`fromProjectId == request.fromProjectId`;
 * `formatVersion == null`; `title = "import <kind> <leaf-id>"` — NO
 * "envelope" prefix; `outputForLlm` cites both project ids + leaf id +
 * parent count + dedup count + AIGC binding hint).
 */
class ImportSourceNodeLiveHandlerTest {

    private fun bodyJson(name: String): JsonObject = buildJsonObject {
        put("description", JsonPrimitive(name))
    }

    private fun req(
        fromProjectId: String,
        fromNodeId: String,
        toProjectId: String = "p-target",
        newNodeId: String? = null,
    ): SourceNodeImportRequest = SourceNodeImportRequest(
        toProjectId = toProjectId,
        fromProjectId = fromProjectId,
        fromNodeId = fromNodeId,
        envelope = null,
        newNodeId = newNodeId,
    )

    // ── 1. Self-import guard ────────────────────────────────

    @Test fun selfImportRejectedWithRemediationHint() = runTest {
        // Marquee guard pin: same id on both sides MUST fail. Drift to
        // silent no-op would let the LLM accidentally try to duplicate
        // a node via the import path.
        val store = ProjectStoreTestKit.create()
        val source = store.createAt(path = "/projects/self".toPath(), title = "Self")
        store.mutateSource(source.id) { it.addNode(makeNode("n1", "character_ref", bodyJson("A"))) }

        val ex = assertFailsWith<IllegalArgumentException> {
            executeLiveImport(
                store,
                req(fromProjectId = source.id.value, fromNodeId = "n1", toProjectId = source.id.value),
                source.id,
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "fromProjectId and toProjectId are the same" in msg,
            "expected self-import message; got: $msg",
        )
        assertTrue(
            "source_node_action(action=add)" in msg,
            "expected within-project remediation hint; got: $msg",
        )
        assertTrue(
            "fresh nodeId" in msg,
            "expected fresh-nodeId guidance; got: $msg",
        )
    }

    // ── 2. Source project not found ─────────────────────────

    @Test fun nonexistentFromProjectFailsLoud() = runTest {
        val store = ProjectStoreTestKit.create()
        val target = store.createAt(path = "/projects/target".toPath(), title = "Target")
        val ex = assertFailsWith<IllegalStateException> {
            executeLiveImport(
                store,
                req(fromProjectId = "ghost-project-id", fromNodeId = "n1", toProjectId = target.id.value),
                target.id,
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "Source project ghost-project-id not found" in msg,
            "expected not-found message citing the missing project id; got: $msg",
        )
    }

    // ── 3. Dedup-by-contentHash ─────────────────────────────

    @Test fun importDeduppedByContentHashMarksSkippedAndReusesExistingId() = runTest {
        // Marquee dedup pin: a content-hash match in the target project
        // skips the write and reuses the existing id.
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")

        // Source side has a single node "alice".
        store.mutateSource(src.id) {
            it.addNode(makeNode("alice", "character_ref", bodyJson("Alice")))
        }
        // Target side already has a content-hash-equivalent node under a
        // DIFFERENT id ("alice-existing").
        store.mutateSource(tgt.id) {
            it.addNode(makeNode("alice-existing", "character_ref", bodyJson("Alice")))
        }

        val outcome = executeLiveImport(
            store,
            req(fromProjectId = src.id.value, fromNodeId = "alice", toProjectId = tgt.id.value),
            tgt.id,
        )
        assertEquals(1, outcome.nodes.size)
        val row = outcome.nodes[0]
        assertEquals("alice", row.originalId, "outcome echoes source-side id")
        assertEquals("alice-existing", row.importedId, "import landed on the existing dedup target")
        assertTrue(row.skippedDuplicate, "skippedDuplicate=true on hash match")
        // Target source DAG was NOT mutated — still 1 node, still
        // "alice-existing".
        val tgtSource = store.get(tgt.id)!!.source
        assertEquals(1, tgtSource.nodes.size, "no fresh node added on dedup")
        assertEquals(SourceNodeId("alice-existing"), tgtSource.nodes[0].id)
    }

    // ── 4. Collision-on-id without contentHash match ────────

    @Test fun sameIdDifferentContentHashFailsWithRemediation() = runTest {
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")
        store.mutateSource(src.id) {
            it.addNode(makeNode("shared", "character_ref", bodyJson("FromSrc")))
        }
        store.mutateSource(tgt.id) {
            it.addNode(makeNode("shared", "character_ref", bodyJson("AlreadyOnTarget")))
        }

        val ex = assertFailsWith<IllegalStateException> {
            executeLiveImport(
                store,
                req(fromProjectId = src.id.value, fromNodeId = "shared", toProjectId = tgt.id.value),
                tgt.id,
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "already has a node shared with a different contentHash" in msg,
            "expected collision message; got: $msg",
        )
        assertTrue(
            "Pick a fresh newNodeId" in msg,
            "expected newNodeId remediation hint; got: $msg",
        )
        assertTrue(
            "source_node_action(action=remove)" in msg,
            "expected remove remediation hint; got: $msg",
        )
        assertTrue(
            "kind=character_ref" in msg,
            "expected target kind cited for context; got: $msg",
        )
    }

    // ── 5. Parent-ref remap with effectiveHash recompute ────

    @Test fun dedupRemapPropagatesToChildParentRefs() = runTest {
        // Pin: when a parent dedups, the child's parent ref is
        // rewritten via remap. Drift to "remap missed" would land the
        // child with a dangling SourceRef into the source-side
        // namespace.
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")
        // Source: parent "style-src" + child "alice" referencing it.
        store.mutateSource(src.id) {
            it
                .addNode(makeNode("style-src", "style_bible", bodyJson("Modern")))
                .addNode(
                    makeNode(
                        id = "alice",
                        kind = "character_ref",
                        body = bodyJson("Alice"),
                        parents = listOf("style-src"),
                    ),
                )
        }
        // Target: existing parent under DIFFERENT id, same content hash.
        store.mutateSource(tgt.id) {
            it.addNode(makeNode("style-existing", "style_bible", bodyJson("Modern")))
        }

        val outcome = executeLiveImport(
            store,
            req(fromProjectId = src.id.value, fromNodeId = "alice", toProjectId = tgt.id.value),
            tgt.id,
        )
        assertEquals(2, outcome.nodes.size)
        val parentRow = outcome.nodes[0]
        assertTrue(parentRow.skippedDuplicate, "parent dedupped against existing")
        assertEquals("style-existing", parentRow.importedId)
        val childRow = outcome.nodes[1]
        assertFalse(childRow.skippedDuplicate, "child is a fresh insert in the target")
        assertEquals("alice", childRow.importedId)

        // Critical: the child's parent ref in the target source DAG
        // points at "style-existing" (NOT "style-src" — that ref came
        // from the source side and would dangle on the target).
        val tgtSource = store.get(tgt.id)!!.source
        val childInTarget = tgtSource.nodes.first { it.id.value == "alice" }
        assertEquals(
            listOf(SourceRef(SourceNodeId("style-existing"))),
            childInTarget.parents,
            "child's parent ref must be remapped to the target's existing dedup id",
        )
    }

    @Test fun effectiveHashRecomputedOnRemappedParents() = runTest {
        // Marquee effectiveHash pin: when parents get remapped, the
        // child's contentHash MUST be recomputed against the remapped
        // parents. Otherwise the subsequent dedup-by-hash check would
        // compare the SOURCE-side hash (with original parent ids) and
        // miss a legitimate duplicate that already exists on the target.
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")

        // Source: parent "style-src" + child "alice" referencing it.
        store.mutateSource(src.id) {
            it
                .addNode(makeNode("style-src", "style_bible", bodyJson("Modern")))
                .addNode(
                    makeNode(
                        id = "alice",
                        kind = "character_ref",
                        body = bodyJson("Alice"),
                        parents = listOf("style-src"),
                    ),
                )
        }

        // Target: parent "style-existing" + child built locally with the
        // SAME body but parents = ["style-existing"]. The child's hash
        // on the target is computed against the target-side parent id,
        // so without effectiveHash recompute the import would not see
        // the dedup match.
        val tgtParent = makeNode("style-existing", "style_bible", bodyJson("Modern"))
        val tgtChild = makeNode(
            id = "alice-existing",
            kind = "character_ref",
            body = bodyJson("Alice"),
            parents = listOf("style-existing"),
        )
        store.mutateSource(tgt.id) { it.addNode(tgtParent).addNode(tgtChild) }

        val outcome = executeLiveImport(
            store,
            req(fromProjectId = src.id.value, fromNodeId = "alice", toProjectId = tgt.id.value),
            tgt.id,
        )
        assertEquals(2, outcome.nodes.size)
        val parentRow = outcome.nodes[0]
        val childRow = outcome.nodes[1]
        assertTrue(parentRow.skippedDuplicate, "parent dedupped via direct hash match")
        assertEquals("style-existing", parentRow.importedId)
        // The marquee assertion: child ALSO dedups, because the
        // effectiveHash recompute against remapped parents matches the
        // target-side hash. Drift would mark this skippedDuplicate=false
        // and add a duplicate "alice" → would also collide on the next
        // import attempt.
        assertTrue(
            childRow.skippedDuplicate,
            "child must dedup via effectiveHash recompute over remapped parents",
        )
        assertEquals("alice-existing", childRow.importedId)
        // Target unchanged.
        val tgtSource = store.get(tgt.id)!!.source
        assertEquals(2, tgtSource.nodes.size, "no fresh nodes on full dedup chain")
    }

    // ── 6. newNodeId rename of leaf only ────────────────────

    @Test fun newNodeIdRenamesLeafOnly() = runTest {
        // Marquee rename pin: `newNodeId` only takes effect for the
        // leaf (the node named by `fromNodeId`). Transitive parents
        // keep their source-side ids.
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")
        store.mutateSource(src.id) {
            it
                .addNode(makeNode("style-src", "style_bible", bodyJson("Modern")))
                .addNode(
                    makeNode(
                        id = "alice-src",
                        kind = "character_ref",
                        body = bodyJson("AliceUnique"),
                        parents = listOf("style-src"),
                    ),
                )
        }

        val outcome = executeLiveImport(
            store,
            req(
                fromProjectId = src.id.value,
                fromNodeId = "alice-src",
                toProjectId = tgt.id.value,
                newNodeId = "alice-renamed",
            ),
            tgt.id,
        )
        assertEquals(2, outcome.nodes.size)
        // Parent: NOT renamed.
        assertEquals("style-src", outcome.nodes[0].importedId, "parent keeps source id")
        // Leaf: renamed.
        assertEquals("alice-src", outcome.nodes[1].originalId, "outcome echoes source-side id")
        assertEquals("alice-renamed", outcome.nodes[1].importedId, "leaf renamed to newNodeId")

        // Target DAG: child's parent ref still points at the un-renamed
        // parent id ("style-src" — which now lives in the target).
        val tgtSource = store.get(tgt.id)!!.source
        val leafInTarget = tgtSource.nodes.first { it.id.value == "alice-renamed" }
        assertEquals(
            listOf(SourceRef(SourceNodeId("style-src"))),
            leafInTarget.parents,
            "renamed leaf's parent ref must point at the (un-renamed) parent in the target",
        )
    }

    @Test fun blankNewNodeIdIsTreatedAsAbsent() = runTest {
        // Pin: per impl `newNodeId?.takeIf { it.isNotBlank() }` —
        // empty / whitespace are treated as no-rename. Drift to
        // "blank → rename to blank" would land an empty-id node and
        // crash the next id resolution.
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        store.mutateSource(src.id) {
            it.addNode(makeNode("alice-src", "character_ref", bodyJson("A")))
        }
        // Tag-string disambiguation per cycle 213's blank-loop pattern.
        for ((tag, blank) in listOf("empty" to "", "space" to " ", "tab" to "\t")) {
            val tgt = store.createAt(path = "/projects/blank-$tag".toPath(), title = "T")
            val outcome = executeLiveImport(
                store,
                req(
                    fromProjectId = src.id.value,
                    fromNodeId = "alice-src",
                    toProjectId = tgt.id.value,
                    newNodeId = blank,
                ),
                tgt.id,
            )
            assertEquals(
                "alice-src",
                outcome.nodes[0].importedId,
                "blank newNodeId='$blank' must be treated as absent (kept original id)",
            )
        }
    }

    // ── topoCollectForLiveImport direct pins ────────────────

    @Test fun topoCollectOrdersParentsBeforeChild() = runTest {
        // Pin: traversal is parents-first (topological). Drift to
        // pre-order child-first would let the import write children
        // before their parents had been added → collision-on-parent or
        // dangling SourceRef on the intermediate state.
        val parent = makeNode("p", "style_bible", bodyJson("Style"))
        val child = makeNode(
            id = "c",
            kind = "character_ref",
            body = bodyJson("Child"),
            parents = listOf("p"),
        )
        val byId = mapOf(parent.id to parent, child.id to child)
        val ordered = topoCollectForLiveImport(byId, child.id)
        assertEquals(2, ordered.size)
        assertEquals(parent.id, ordered[0].id, "parent must come first in topological order")
        assertEquals(child.id, ordered[1].id, "child must come last")
    }

    @Test fun topoCollectDeduplicatesSharedAncestor() = runTest {
        // Pin: a diamond DAG (two parents share an ancestor) yields
        // each node exactly once, ancestor first. The `visited` set is
        // load-bearing — drift to "visit every ref" would emit the
        // ancestor twice and trip a duplicate-add downstream.
        val ancestor = makeNode("a", "style_bible", bodyJson("Ancestor"))
        val left = makeNode("l", "style_bible", bodyJson("Left"), parents = listOf("a"))
        val right = makeNode("r", "style_bible", bodyJson("Right"), parents = listOf("a"))
        val leaf = makeNode(
            id = "leaf",
            kind = "character_ref",
            body = bodyJson("Leaf"),
            parents = listOf("l", "r"),
        )
        val byId = listOf(ancestor, left, right, leaf).associateBy { it.id }
        val ordered = topoCollectForLiveImport(byId, leaf.id)
        assertEquals(4, ordered.size, "diamond should yield 4 distinct nodes (ancestor not duplicated)")
        // Ancestor must come before both branches (topological).
        val pos = ordered.withIndex().associate { (i, n) -> n.id to i }
        assertTrue(pos[ancestor.id]!! < pos[left.id]!!, "ancestor before left branch")
        assertTrue(pos[ancestor.id]!! < pos[right.id]!!, "ancestor before right branch")
        assertTrue(pos[left.id]!! < pos[leaf.id]!!, "left before leaf")
        assertTrue(pos[right.id]!! < pos[leaf.id]!!, "right before leaf")
    }

    @Test fun topoCollectErrorsOnMissingParent() = runTest {
        // Pin: a referenced parent that's not in `byId` MUST throw with
        // "referenced from import chain" phrasing. Drift to "skip the
        // missing parent" would let the import land a child with a
        // dangling SourceRef.
        val orphan = makeNode("c", "character_ref", bodyJson("Child"), parents = listOf("ghost"))
        val byId = mapOf(orphan.id to orphan)
        val ex = assertFailsWith<IllegalStateException> {
            topoCollectForLiveImport(byId, orphan.id)
        }
        val msg = ex.message ?: ""
        assertTrue(
            "Source node ghost not found" in msg,
            "expected ghost-parent message; got: $msg",
        )
        assertTrue(
            "referenced from import chain" in msg,
            "expected import-chain context; got: $msg",
        )
    }

    @Test fun topoCollectErrorsWhenLeafItselfMissing() = runTest {
        // Pin: a missing leaf id (the entry point) ALSO uses the same
        // missing-parent error path — `visit(leafId)` is the first call
        // and it goes through the same `byId[id] ?: error(...)` branch.
        val byId = emptyMap<SourceNodeId, SourceNode>()
        val ex = assertFailsWith<IllegalStateException> {
            topoCollectForLiveImport(byId, SourceNodeId("ghost-leaf"))
        }
        assertTrue(
            "Source node ghost-leaf not found" in (ex.message ?: ""),
            "expected ghost-leaf message; got: ${ex.message}",
        )
    }

    // ── Outcome shape pins ──────────────────────────────────

    @Test fun outcomeFieldsAndTitleFormatHaveNoEnvelopePrefix() = runTest {
        // Pin: live-import outcome differs from envelope-import in TWO
        // shape-load-bearing places — `fromProjectId` is populated (NOT
        // null), `formatVersion` is null (NO envelope), and `title`
        // omits the "envelope" prefix that the envelope handler adds.
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")
        store.mutateSource(src.id) {
            it.addNode(makeNode("alice", "character_ref", bodyJson("A")))
        }

        val outcome = executeLiveImport(
            store,
            req(fromProjectId = src.id.value, fromNodeId = "alice", toProjectId = tgt.id.value),
            tgt.id,
        )
        assertEquals(src.id.value, outcome.fromProjectId, "live import populates fromProjectId")
        assertEquals(tgt.id.value, outcome.toProjectId)
        assertEquals(null, outcome.formatVersion, "live import has no envelope formatVersion")
        assertEquals("import character_ref alice", outcome.title, "title has NO envelope prefix")
    }

    @Test fun outcomeOutputForLlmCitesBothProjectsLeafAndAigcHint() = runTest {
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")
        store.mutateSource(src.id) {
            it.addNode(makeNode("alice", "character_ref", bodyJson("A")))
        }

        val outcome = executeLiveImport(
            store,
            req(fromProjectId = src.id.value, fromNodeId = "alice", toProjectId = tgt.id.value),
            tgt.id,
        )
        val msg = outcome.outputForLlm
        assertTrue("Imported alice" in msg, "expected leaf id; got: $msg")
        assertTrue("from ${src.id.value}" in msg, "expected fromProjectId; got: $msg")
        assertTrue("into ${tgt.id.value}" in msg, "expected toProjectId; got: $msg")
        assertTrue(
            "Pass importedId=alice in consistencyBindingIds" in msg,
            "expected AIGC binding hint citing the imported id; got: $msg",
        )
    }

    @Test fun multiNodeOutputCitesParentCount() = runTest {
        // Pin: when imported.size > 1, summary appends "(with N parent
        // node(s))" — N being size-1 (everything before the leaf).
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")
        store.mutateSource(src.id) {
            it
                .addNode(makeNode("style-src", "style_bible", bodyJson("S")))
                .addNode(
                    makeNode(
                        id = "alice",
                        kind = "character_ref",
                        body = bodyJson("A"),
                        parents = listOf("style-src"),
                    ),
                )
        }
        val outcome = executeLiveImport(
            store,
            req(fromProjectId = src.id.value, fromNodeId = "alice", toProjectId = tgt.id.value),
            tgt.id,
        )
        assertTrue(
            "(with 1 parent node(s))" in outcome.outputForLlm,
            "expected '(with 1 parent node(s))'; got: ${outcome.outputForLlm}",
        )
    }

    @Test fun dedupCountAppendedToSummaryWhenAtLeastOneSkipped() = runTest {
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")
        store.mutateSource(src.id) {
            it
                .addNode(makeNode("style-src", "style_bible", bodyJson("S")))
                .addNode(
                    makeNode(
                        id = "alice",
                        kind = "character_ref",
                        body = bodyJson("A"),
                        parents = listOf("style-src"),
                    ),
                )
        }
        // Target already has a hash-equivalent parent under a different id.
        store.mutateSource(tgt.id) {
            it.addNode(makeNode("style-existing", "style_bible", bodyJson("S")))
        }

        val outcome = executeLiveImport(
            store,
            req(fromProjectId = src.id.value, fromNodeId = "alice", toProjectId = tgt.id.value),
            tgt.id,
        )
        assertTrue(
            "1 already-present node(s) reused" in outcome.outputForLlm,
            "expected dedup count appended; got: ${outcome.outputForLlm}",
        )
    }

    @Test fun zeroDedupOmitsNoteFromSummary() = runTest {
        // Pin: dedup count of 0 produces empty string. Mirrors the
        // envelope handler's "no-op chatter" minimization.
        val store = ProjectStoreTestKit.create()
        val src = store.createAt(path = "/projects/src".toPath(), title = "Src")
        val tgt = store.createAt(path = "/projects/tgt".toPath(), title = "Tgt")
        store.mutateSource(src.id) {
            it.addNode(makeNode("alice", "character_ref", bodyJson("Unique")))
        }

        val outcome = executeLiveImport(
            store,
            req(fromProjectId = src.id.value, fromNodeId = "alice", toProjectId = tgt.id.value),
            tgt.id,
        )
        assertFalse(
            "already-present node(s) reused" in outcome.outputForLlm,
            "zero-dedup must omit the dedup note; got: ${outcome.outputForLlm}",
        )
    }

    private fun makeNode(
        id: String,
        kind: String,
        body: JsonObject,
        parents: List<String> = emptyList(),
    ): SourceNode = SourceNode.create(
        id = SourceNodeId(id),
        kind = kind,
        body = body,
        parents = parents.map { SourceRef(SourceNodeId(it)) },
    )
}
