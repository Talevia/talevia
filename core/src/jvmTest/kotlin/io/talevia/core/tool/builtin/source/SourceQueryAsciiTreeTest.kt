package io.talevia.core.tool.builtin.source

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.source.query.AsciiTreeRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers `select=ascii_tree` on [SourceQueryTool]. Semantic boundaries
 * (§3a rule 9):
 *  - Empty DAG → one-line `(empty source DAG)` sentinel.
 *  - Single root + child → indented branch under the root.
 *  - Multi-parent (diamond) DAG → second-parent branch prints a `(dup)`
 *    marker so the output stays linear + tree-shaped.
 *  - Orphan node (no clip bound) → `[orphan]` marker on its line.
 *  - Incompatible filter rejected — inherits the existing
 *    rejectIncompatibleFilters guard (covers the no-filters contract for
 *    whole-DAG selects, same as `dot`).
 */
class SourceQueryAsciiTreeTest {

    private suspend fun fixture(nodes: List<SourceNode>): Triple<FileProjectStore, ToolContext, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-tree")
        store.upsert("demo", Project(id = pid, timeline = Timeline(), source = Source(nodes = nodes)))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Triple(store, ctx, pid)
    }

    private fun node(
        id: String,
        kind: String = "test.generic",
        parents: List<String> = emptyList(),
    ): SourceNode = SourceNode(
        id = SourceNodeId(id),
        kind = kind,
        body = JsonObject(emptyMap()),
        parents = parents.map { SourceRef(SourceNodeId(it)) },
    )

    private fun rows(out: SourceQueryTool.Output): AsciiTreeRow =
        out.rows.decodeRowsAs(AsciiTreeRow.serializer()).single()

    private suspend fun run(store: FileProjectStore, ctx: ToolContext, pid: ProjectId): SourceQueryTool.Output {
        val tool = SourceQueryTool(store)
        return tool.execute(
            SourceQueryTool.Input(select = "ascii_tree", projectId = pid.value),
            ctx,
        ).data
    }

    @Test fun emptyDagEmitsSentinel() = runTest {
        val (store, ctx, pid) = fixture(nodes = emptyList())
        val out = run(store, ctx, pid)
        val row = rows(out)
        assertEquals(0, row.nodeCount)
        assertEquals(0, row.edgeCount)
        assertEquals(0, row.rootCount)
        assertEquals("(empty source DAG)\n", row.tree)
    }

    @Test fun singleOrphanNodeCarriesMarker() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("lone", kind = "core.consistency.style_bible")))
        val out = run(store, ctx, pid)
        val row = rows(out)
        assertEquals(1, row.nodeCount)
        assertEquals(0, row.edgeCount)
        assertEquals(1, row.rootCount)
        assertTrue("lone" in row.tree, "node id must appear in tree: ${row.tree}")
        assertTrue("core.consistency.style_bible" in row.tree, "kind must appear: ${row.tree}")
        assertTrue("[orphan]" in row.tree, "orphan marker must fire on zero-clip-bound node: ${row.tree}")
    }

    @Test fun rootAndChildIndentedAsBranch() = runTest {
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("root"),
                node("child", parents = listOf("root")),
            ),
        )
        val row = rows(run(store, ctx, pid))
        assertEquals(2, row.nodeCount)
        assertEquals(1, row.edgeCount)
        assertEquals(1, row.rootCount)
        // Root at left margin, child under box-drawing branch.
        val lines = row.tree.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(2, lines.size, "expected two lines; got ${row.tree}")
        assertTrue(lines[0].startsWith("root "), "root at margin: ${lines[0]}")
        assertTrue("└─ child" in lines[1], "child under └─ branch: ${lines[1]}")
    }

    @Test fun diamondEmitsDupMarkerOnSecondParentBranch() = runTest {
        // a, b both root; c has two parents (a + b). First pass under a
        // prints the full c line; second pass under b gets `(dup)`.
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("a"),
                node("b"),
                node("c", parents = listOf("a", "b")),
            ),
        )
        val row = rows(run(store, ctx, pid))
        assertEquals(3, row.nodeCount)
        assertEquals(2, row.edgeCount, "diamond has 2 edges (a→c, b→c)")
        assertEquals(2, row.rootCount, "a + b are both roots")
        // First expansion: no (dup). Second (under b): (dup).
        val dupMentions = row.tree.lineSequence().count { "(dup)" in it }
        assertEquals(1, dupMentions, "exactly one (dup) marker on the second mention of c; got:\n${row.tree}")
    }

    @Test fun incompatibleFilterRejected() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("x")))
        val tool = SourceQueryTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SourceQueryTool.Input(select = "ascii_tree", projectId = pid.value, kind = "test.generic"),
                ctx,
            )
        }
        assertTrue("kind" in ex.message!!, "kind filter must be rejected on ascii_tree: ${ex.message}")
    }
}
