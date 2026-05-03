package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
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
 * Direct tests for [executeEnvelopeImport] —
 * `core/tool/builtin/source/ImportSourceNodeEnvelopeHandler.kt`. The
 * portable JSON envelope import handler that ingests a
 * [SourceNodeEnvelope] (produced by [ExportSourceNodeTool]) into a
 * target project. Cycle 210 audit: 115 LOC, 0 direct test refs.
 *
 * Same audit-pattern fallback as cycles 207-209.
 *
 * Six correctness contracts pinned:
 *
 *  1. **Envelope JSON validation.** Malformed JSON, wrong-shape JSON,
 *     and JSON with unknown top-level fields should fail at decode
 *     with a friendly "not valid JSON for the source-export schema"
 *     message — drift to a raw `SerializationException` would surface
 *     a stacktrace to the agent instead of a remediation hint.
 *
 *  2. **`formatVersion` exact-match gate.** Per kdoc on
 *     `ExportSourceNodeTool.FORMAT_VERSION` ("breaking changes bump
 *     the constant — additive changes only"), an envelope with the
 *     wrong version is REFUSED, NOT silently best-effort decoded.
 *     Drift to "any version OK" risks corrupting the target project
 *     when field semantics evolve.
 *
 *  3. **Empty + corrupt-root checks.** `nodes.isEmpty()` and
 *     `rootNodeId not in nodes` both fail loud — degenerate envelopes
 *     would otherwise import nothing or land an orphan reference.
 *
 *  4. **Dedup-by-contentHash.** A node whose contentHash matches an
 *     existing node in the target project is REUSED (not added
 *     again). The outcome row reports `skippedDuplicate=true` and the
 *     existing id; subsequent nodes that referenced the duplicated
 *     parent get the existing id remapped via the `remap` map. This
 *     is the marquee dedup contract: import-into-self is idempotent.
 *
 *  5. **Collision-on-id without contentHash match.** A node whose id
 *     already exists in the target but has a DIFFERENT contentHash
 *     fails fast with a remediation hint pointing at `newNodeId` /
 *     `source_node_action(action=remove)`. Drift to "auto-overwrite"
 *     would silently mutate target data.
 *
 *  6. **`newNodeId` renames the root only.** When `request.newNodeId`
 *     is non-blank, only the rootNodeId is renamed — non-root nodes
 *     keep their exported ids, parent refs to the renamed root use
 *     the new id via `remap`. Drift to "rename every node" would
 *     break unrelated downstream references; drift to "rename
 *     ignored" defeats the user's collision-resolution intent.
 *
 * Plus shape pins: `outputForLlm` cites root + version + leaf id +
 * parent count + dedup count + AIGC binding hint; `title = "import
 * envelope <kind> <leaf-id>"`; `outcome.toProjectId == toPid.value`;
 * `outcome.fromProjectId == null` (envelope path is target-only).
 */
class ImportSourceNodeEnvelopeHandlerTest {

    private val json = JsonConfig.default

    private fun makeEnvelope(
        rootNodeId: String = "root-1",
        nodes: List<ExportedNode>,
        formatVersion: String = ExportSourceNodeTool.FORMAT_VERSION,
    ): String = json.encodeToString(
        SourceNodeEnvelope.serializer(),
        SourceNodeEnvelope(
            formatVersion = formatVersion,
            rootNodeId = rootNodeId,
            nodes = nodes,
        ),
    )

    private fun bodyJson(name: String): JsonObject = buildJsonObject {
        put("description", JsonPrimitive(name))
    }

    private fun req(
        envelope: String,
        newNodeId: String? = null,
        toProjectId: String = "p-target",
    ): SourceNodeImportRequest = SourceNodeImportRequest(
        toProjectId = toProjectId,
        fromProjectId = null,
        fromNodeId = null,
        envelope = envelope,
        newNodeId = newNodeId,
    )

    // ── 1. JSON validation ──────────────────────────────────

    @Test fun malformedJsonFailsLoudWithRemediationHint() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val ex = assertFailsWith<IllegalStateException> {
            executeEnvelopeImport(store, req(envelope = "{not valid json"), created.id)
        }
        val msg = ex.message ?: ""
        assertTrue(
            "not valid JSON for the source-export schema" in msg,
            "expected friendly schema-validation error; got: $msg",
        )
    }

    @Test fun jsonWithWrongShapeFailsLoud() = runTest {
        // Pin: a JSON value with the wrong shape (e.g. an array
        // instead of the envelope object, or missing required fields)
        // is caught by SerializationException → re-thrown with the
        // schema-validation prefix.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val ex = assertFailsWith<IllegalStateException> {
            executeEnvelopeImport(store, req(envelope = """{"unknownField": 1}"""), created.id)
        }
        assertTrue("not valid JSON for the source-export schema" in (ex.message ?: ""))
    }

    // ── 2. formatVersion gate ───────────────────────────────

    @Test fun wrongFormatVersionRejected() = runTest {
        // Marquee version-gate pin: per kdoc "best-effort cross-
        // version tolerance risks corrupting the target project when
        // field semantics evolve". Envelope from a future / past
        // build with a different FORMAT_VERSION is refused.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val envelope = makeEnvelope(
            rootNodeId = "n1",
            nodes = listOf(ExportedNode(id = "n1", kind = "character_ref", body = bodyJson("Alice"))),
            formatVersion = "talevia-source-export-v999",
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            executeEnvelopeImport(store, req(envelope = envelope), created.id)
        }
        val msg = ex.message ?: ""
        assertTrue(
            "Envelope formatVersion='talevia-source-export-v999' is not understood" in msg,
            "expected version mismatch message; got: $msg",
        )
        assertTrue(
            "expected ${ExportSourceNodeTool.FORMAT_VERSION}" in msg,
            "expected the actual current version cited; got: $msg",
        )
        assertTrue(
            "Re-export from a compatible Talevia build" in msg,
            "expected remediation hint; got: $msg",
        )
    }

    // ── 3. Empty + corrupt-root ─────────────────────────────

    @Test fun emptyNodesListRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val envelope = makeEnvelope(rootNodeId = "n1", nodes = emptyList())
        val ex = assertFailsWith<IllegalArgumentException> {
            executeEnvelopeImport(store, req(envelope = envelope), created.id)
        }
        assertTrue(
            "Envelope contains no nodes" in (ex.message ?: ""),
            "expected empty-nodes message; got: ${ex.message}",
        )
    }

    @Test fun rootNotInNodesListRejectedAsCorrupt() = runTest {
        // Pin: rootNodeId must reference a node that's actually in
        // the envelope's nodes list. Drift would silently land an
        // orphan rootRef in the target source DAG.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val envelope = makeEnvelope(
            rootNodeId = "ghost",
            nodes = listOf(ExportedNode(id = "n1", kind = "character_ref", body = bodyJson("A"))),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            executeEnvelopeImport(store, req(envelope = envelope), created.id)
        }
        val msg = ex.message ?: ""
        assertTrue(
            "Envelope rootNodeId='ghost' not present in its own nodes list" in msg,
            "expected corrupt-envelope message; got: $msg",
        )
        assertTrue("envelope corrupt" in msg, "expected explicit corruption phrasing; got: $msg")
    }

    // ── 4. Dedup-by-contentHash ─────────────────────────────

    @Test fun importIntoSelfIsIdempotentDeduppedByContentHash() = runTest {
        // Marquee dedup pin: importing an envelope whose nodes
        // already exist (by contentHash) in the target project
        // skips the writes and reuses existing ids. Idempotent
        // round-trip.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        // Seed the target with a node that has the same shape as
        // what the envelope describes.
        val seedNode = SourceNode.create(
            id = SourceNodeId("existing-1"),
            kind = "character_ref",
            body = bodyJson("Alice"),
            parents = emptyList(),
        )
        store.mutateSource(created.id) { source -> source.addNode(seedNode) }

        val envelope = makeEnvelope(
            rootNodeId = "envelope-1",
            nodes = listOf(
                ExportedNode(id = "envelope-1", kind = "character_ref", body = bodyJson("Alice")),
            ),
        )
        val outcome = executeEnvelopeImport(store, req(envelope = envelope), created.id)
        assertEquals(1, outcome.nodes.size)
        val row = outcome.nodes[0]
        assertEquals("envelope-1", row.originalId, "outcome echoes envelope-side id")
        assertEquals("existing-1", row.importedId, "import landed on the existing dedup target")
        assertTrue(row.skippedDuplicate, "skippedDuplicate=true on hash match")
    }

    @Test fun dedupRemapPropagatesToChildParentRefs() = runTest {
        // Pin: when a parent gets dedupped, the child's parent
        // ref uses the existing id via the `remap` map. Drift to
        // "remap missed" would land the child with a dangling parent
        // ref into the original-id namespace.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val parent = SourceNode.create(
            id = SourceNodeId("style-existing"),
            kind = "style_bible",
            body = bodyJson("ModernStyle"),
            parents = emptyList(),
        )
        store.mutateSource(created.id) { source -> source.addNode(parent) }

        // Envelope has a parent (same content as the seeded node)
        // and a fresh child referencing it.
        val envelope = makeEnvelope(
            rootNodeId = "child",
            nodes = listOf(
                ExportedNode(
                    id = "style-from-export",
                    kind = "style_bible",
                    body = bodyJson("ModernStyle"),
                    parents = emptyList(),
                ),
                ExportedNode(
                    id = "child",
                    kind = "character_ref",
                    body = bodyJson("Alice"),
                    parents = listOf("style-from-export"),
                ),
            ),
        )
        val outcome = executeEnvelopeImport(store, req(envelope = envelope), created.id)
        assertEquals(2, outcome.nodes.size)
        // Parent dedupped to existing.
        val parentRow = outcome.nodes[0]
        assertTrue(parentRow.skippedDuplicate, "parent dedupped")
        assertEquals("style-existing", parentRow.importedId)
        // Child inserted fresh.
        val childRow = outcome.nodes[1]
        assertFalse(childRow.skippedDuplicate, "child is a fresh insert")
        assertEquals("child", childRow.importedId)

        // Verify the child's parent ref WAS remapped to the
        // existing id (NOT the export-side "style-from-export").
        val source = store.get(created.id)!!.source
        val childInTarget = source.nodes.first { it.id.value == "child" }
        assertEquals(
            listOf(SourceRef(SourceNodeId("style-existing"))),
            childInTarget.parents,
            "child's parent ref must be remapped to the existing dedup id (NOT 'style-from-export')",
        )
    }

    // ── 5. Collision-on-id without contentHash match ────────

    @Test fun sameIdDifferentContentHashFailsWithRemediation() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        // Seed the target with a node at id "shared" with one body.
        val existing = SourceNode.create(
            id = SourceNodeId("shared"),
            kind = "character_ref",
            body = bodyJson("ExistingAlice"),
            parents = emptyList(),
        )
        store.mutateSource(created.id) { source -> source.addNode(existing) }

        // Envelope has the same id but a different body → different contentHash.
        val envelope = makeEnvelope(
            rootNodeId = "shared",
            nodes = listOf(
                ExportedNode(id = "shared", kind = "character_ref", body = bodyJson("DifferentAlice")),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            executeEnvelopeImport(store, req(envelope = envelope), created.id)
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
    }

    // ── 6. newNodeId rename of root ─────────────────────────

    @Test fun newNodeIdRenamesRootOnly() = runTest {
        // Marquee rename pin: `newNodeId` only takes effect for the
        // root. Non-root nodes preserve their exported ids; child
        // parent refs to the renamed root use the new id.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val envelope = makeEnvelope(
            rootNodeId = "originalRoot",
            nodes = listOf(
                ExportedNode(
                    id = "originalRoot",
                    kind = "style_bible",
                    body = bodyJson("Style1"),
                    parents = emptyList(),
                ),
                ExportedNode(
                    id = "child",
                    kind = "character_ref",
                    body = bodyJson("AliceUnique"),
                    parents = listOf("originalRoot"),
                ),
            ),
        )
        val outcome = executeEnvelopeImport(
            store,
            req(envelope = envelope, newNodeId = "renamedRoot"),
            created.id,
        )
        assertEquals(2, outcome.nodes.size)
        // Root row: imported under the new id.
        val rootRow = outcome.nodes[0]
        assertEquals("originalRoot", rootRow.originalId, "outcome echoes envelope-side id")
        assertEquals("renamedRoot", rootRow.importedId, "newNodeId applied to root")
        // Child row: NOT renamed.
        val childRow = outcome.nodes[1]
        assertEquals("child", childRow.importedId, "child id NOT renamed (only root)")

        // Verify the child's parent ref uses the renamed root id.
        val source = store.get(created.id)!!.source
        val childInTarget = source.nodes.first { it.id.value == "child" }
        assertEquals(
            listOf(SourceRef(SourceNodeId("renamedRoot"))),
            childInTarget.parents,
            "child's parent ref must follow the rename",
        )
    }

    @Test fun blankNewNodeIdIsTreatedAsAbsent() = runTest {
        // Pin: per impl `newNodeId?.takeIf { it.isNotBlank() }` —
        // empty string and whitespace-only are treated as no-rename.
        // Drift to "blank → rename to blank" would crash on the
        // next id resolution.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val envelope = makeEnvelope(
            rootNodeId = "n1",
            nodes = listOf(ExportedNode(id = "n1", kind = "character_ref", body = bodyJson("A"))),
        )
        for (blank in listOf("", " ", "\t")) {
            // Need a fresh project per iteration since import lands
            // the node and a re-run would dedup.
            val freshProject = store.createAt(
                path = "/projects/blank-$blank-${blank.length}".toPath(),
                title = "T",
            )
            val outcome = executeEnvelopeImport(
                store,
                req(envelope = envelope, newNodeId = blank),
                freshProject.id,
            )
            assertEquals(
                "n1",
                outcome.nodes[0].importedId,
                "blank newNodeId='$blank' must be treated as absent (kept original id)",
            )
        }
    }

    // ── Outcome shape pins ──────────────────────────────────

    @Test fun outcomeOutputForLlmCitesRootVersionLeafAndAigcHint() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val envelope = makeEnvelope(
            rootNodeId = "root-1",
            nodes = listOf(
                ExportedNode(
                    id = "root-1",
                    kind = "character_ref",
                    body = bodyJson("Alice"),
                    parents = emptyList(),
                ),
            ),
        )
        val outcome = executeEnvelopeImport(store, req(envelope = envelope), created.id)
        val msg = outcome.outputForLlm
        assertTrue("Ingested root-1" in msg, "expected root id; got: $msg")
        assertTrue(ExportSourceNodeTool.FORMAT_VERSION in msg, "expected formatVersion; got: $msg")
        assertTrue(created.id.value in msg, "expected target projectId; got: $msg")
        assertTrue(
            "Pass importedId=" in msg,
            "expected AIGC binding hint; got: $msg",
        )
        assertTrue(
            "consistencyBindingIds" in msg,
            "expected consistencyBindingIds hint; got: $msg",
        )
    }

    @Test fun outcomeFieldsAndTitleFormat() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val envelope = makeEnvelope(
            rootNodeId = "n1",
            nodes = listOf(
                ExportedNode(id = "n1", kind = "character_ref", body = bodyJson("A")),
            ),
        )
        val outcome = executeEnvelopeImport(store, req(envelope = envelope), created.id)
        assertEquals(null, outcome.fromProjectId, "envelope import has no source projectId")
        assertEquals(created.id.value, outcome.toProjectId)
        assertEquals(ExportSourceNodeTool.FORMAT_VERSION, outcome.formatVersion)
        assertEquals("import envelope character_ref n1", outcome.title)
    }

    @Test fun multiNodeOutputCitesParentCount() = runTest {
        // Pin: when imported.size > 1, summary appends "(with N
        // parent node(s))" — N being size-1 because the leaf is the
        // last entry and everything before it is treated as a parent
        // of the leaf in summary terms.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val envelope = makeEnvelope(
            rootNodeId = "n2",
            nodes = listOf(
                ExportedNode(id = "n1", kind = "style_bible", body = bodyJson("Style")),
                ExportedNode(id = "n2", kind = "character_ref", body = bodyJson("Alice"), parents = listOf("n1")),
            ),
        )
        val outcome = executeEnvelopeImport(store, req(envelope = envelope), created.id)
        assertTrue(
            "(with 1 parent node(s))" in outcome.outputForLlm,
            "expected '(with 1 parent node(s))'; got: ${outcome.outputForLlm}",
        )
    }

    @Test fun dedupCountAppendedToSummaryWhenAtLeastOneSkipped() = runTest {
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        // Seed a parent identical in shape to the envelope's parent.
        val seed = SourceNode.create(
            id = SourceNodeId("style-existing"),
            kind = "style_bible",
            body = bodyJson("Style"),
            parents = emptyList(),
        )
        store.mutateSource(created.id) { it.addNode(seed) }

        val envelope = makeEnvelope(
            rootNodeId = "child",
            nodes = listOf(
                ExportedNode(id = "style-export", kind = "style_bible", body = bodyJson("Style")),
                ExportedNode(
                    id = "child",
                    kind = "character_ref",
                    body = bodyJson("Alice"),
                    parents = listOf("style-export"),
                ),
            ),
        )
        val outcome = executeEnvelopeImport(store, req(envelope = envelope), created.id)
        assertTrue(
            "1 already-present node(s) reused" in outcome.outputForLlm,
            "expected dedup count appended; got: ${outcome.outputForLlm}",
        )
    }

    @Test fun zeroDedupOmitsNoteFromSummary() = runTest {
        // Pin: dedup count of 0 produces empty string (NOT " — 0
        // already-present node(s) reused"). The kdoc-implied "no-op
        // chatter" minimization.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Target")
        val envelope = makeEnvelope(
            rootNodeId = "n1",
            nodes = listOf(ExportedNode(id = "n1", kind = "character_ref", body = bodyJson("Unique"))),
        )
        val outcome = executeEnvelopeImport(store, req(envelope = envelope), created.id)
        assertFalse(
            "already-present node(s) reused" in outcome.outputForLlm,
            "zero-dedup must omit the dedup note; got: ${outcome.outputForLlm}",
        )
    }
}
