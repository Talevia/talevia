package io.talevia.core.tool.builtin.source

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `select=dot` on [SourceQueryTool]. Semantic boundaries
 * (§3a rule 9):
 *  - Empty DAG → nodeCount=0, edgeCount=0, DOT wrapper still opens + closes.
 *  - Single node, no edges → one node line + zero edge lines.
 *  - Multi-parent (diamond) DAG → correct edge count + direction (parent→child).
 *  - ID / kind containing special chars (`"`, `\\`) gets DOT-escaped.
 *  - Unused orphan node → dashed style in the rendered text.
 *  - Incompatible filter (`kind` on select=dot) rejected — shares the
 *    existing `rejectIncompatibleFilters` guard.
 *  - `scope=all_projects` rejected — DOT is per-project, no meaningful
 *    cross-project view.
 */
class SourceQueryDotTest {

    private suspend fun fixture(
        nodes: List<SourceNode> = emptyList(),
        clips: List<Clip.Video> = emptyList(),
    ): Triple<FileProjectStore, ToolContext, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-dot")
        val timeline = if (clips.isEmpty()) {
            Timeline()
        } else {
            Timeline(
                tracks = listOf(Track.Video(id = TrackId("v"), clips = clips)),
                duration = clips.maxOf { it.timeRange.start + it.timeRange.duration },
            )
        }
        store.upsert(
            "demo",
            Project(id = pid, timeline = timeline, source = Source(nodes = nodes)),
        )
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

    private fun rows(out: SourceQueryTool.Output): SourceQueryTool.DotRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.DotRow.serializer()),
            out.rows,
        ).single()

    private suspend fun run(store: FileProjectStore, ctx: ToolContext, pid: ProjectId): SourceQueryTool.Output {
        val tool = SourceQueryTool(store)
        return tool.execute(
            SourceQueryTool.Input(select = "dot", projectId = pid.value),
            ctx,
        ).data
    }

    @Test fun emptyDagEmitsWrapperWithNoNodes() = runTest {
        val (store, ctx, pid) = fixture()
        val out = run(store, ctx, pid)
        val row = rows(out)
        assertEquals(0, row.nodeCount)
        assertEquals(0, row.edgeCount)
        assertTrue(row.dot.startsWith("digraph SourceDAG {\n"), "header line must open the digraph")
        assertTrue(row.dot.trimEnd().endsWith("}"), "closing brace must be present")
        // No node-line identifiers present.
        assertTrue("[label=" !in row.dot, "no node labels in an empty DAG; got: ${row.dot}")
    }

    @Test fun singleNodeNoEdges() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("lone", kind = "foo.bar")))
        val out = run(store, ctx, pid)
        val row = rows(out)
        assertEquals(1, row.nodeCount)
        assertEquals(0, row.edgeCount)
        assertTrue("\"lone\" [label=" in row.dot, "must declare the `lone` node; got: ${row.dot}")
        // Edge indicator `->` must be absent.
        assertTrue(" -> " !in row.dot, "zero edges expected; got: ${row.dot}")
    }

    @Test fun diamondDagEmitsFourEdgesInParentToChildDirection() = runTest {
        // Classic diamond:
        //           root
        //           /  \
        //        left   right
        //           \  /
        //          bottom
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("root"),
                node("left", parents = listOf("root")),
                node("right", parents = listOf("root")),
                node("bottom", parents = listOf("left", "right")),
            ),
        )
        val out = run(store, ctx, pid)
        val row = rows(out)
        assertEquals(4, row.nodeCount)
        assertEquals(4, row.edgeCount, "diamond has 4 edges (root→left, root→right, left→bottom, right→bottom)")
        // All 4 edges present, parent-to-child direction.
        assertTrue("\"root\" -> \"left\"" in row.dot)
        assertTrue("\"root\" -> \"right\"" in row.dot)
        assertTrue("\"left\" -> \"bottom\"" in row.dot)
        assertTrue("\"right\" -> \"bottom\"" in row.dot)
        // Child→parent direction is NOT emitted (easy mistake given that
        // SourceNode.parents is the back-reference).
        assertTrue("\"left\" -> \"root\"" !in row.dot)
    }

    @Test fun orphanNodeRendersDashed() = runTest {
        // `lone` has no downstream clip binding → orphan. `bound` is
        // referenced by a Clip.sourceBinding → not an orphan.
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("lone"),
                node("bound"),
            ),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c1"),
                    timeRange = TimeRange(0.seconds, 3.seconds),
                    sourceRange = TimeRange(0.seconds, 3.seconds),
                    assetId = AssetId("a1"),
                    sourceBinding = setOf(SourceNodeId("bound")),
                ),
            ),
        )
        val out = run(store, ctx, pid)
        val row = rows(out)
        // `lone`'s node line carries the dashed style; `bound`'s doesn't.
        val loneLine = row.dot.lineSequence().single { it.contains("\"lone\" [label=") }
        val boundLine = row.dot.lineSequence().single { it.contains("\"bound\" [label=") }
        assertTrue("style=dashed" in loneLine, "orphan node must be dashed; got: $loneLine")
        assertTrue("style=dashed" !in boundLine, "bound node must not be dashed; got: $boundLine")
    }

    @Test fun idWithQuoteGetsDotEscaped() = runTest {
        // ids carrying `"` / `\` must be escaped per DOT's quoted-string
        // grammar. Without escaping, a stray `"` would terminate the token
        // early and the graph would fail to parse in dot -Tsvg.
        val weirdId = "char.\"mei\\1\""
        val (store, ctx, pid) = fixture(nodes = listOf(node(weirdId, kind = "core.consistency.character_ref")))
        val out = run(store, ctx, pid)
        val row = rows(out)
        assertEquals(1, row.nodeCount)
        // The raw id must appear as `"char.\"mei\\1\""` — backslash +
        // quote escaping.
        assertTrue(
            "\"char.\\\"mei\\\\1\\\"\"" in row.dot,
            "id with embedded quote/backslash must be DOT-escaped; got: ${row.dot}",
        )
    }

    @Test fun rejectsFilterFieldsOnSelectDot() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("a")))
        val tool = SourceQueryTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SourceQueryTool.Input(select = "dot", projectId = pid.value, kind = "foo.bar"),
                ctx,
            )
        }
        assertTrue("kind" in ex.message.orEmpty(), "filter-rejection message must mention `kind`")
    }

    @Test fun rejectsAllProjectsScopeOnSelectDot() = runTest {
        val (store, ctx, _) = fixture(nodes = listOf(node("a")))
        val tool = SourceQueryTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                SourceQueryTool.Input(select = "dot", scope = "all_projects"),
                ctx,
            )
        }
        assertTrue(
            "all_projects" in ex.message.orEmpty(),
            "must explain cross-project DOT isn't meaningful; got: ${ex.message}",
        )
    }

    @Test fun outputEchoesSourceRevision() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("a")))
        val out = run(store, ctx, pid)
        // Source constructed with default revision=0; one upsert doesn't
        // bump it (the bumps happen inside source-mutation tools). The
        // field echoes whatever the store has — protects against a
        // sourceRevision regression where the DOT query forgets to
        // propagate the echo the other selects already carry.
        assertEquals(0L, out.sourceRevision)
    }
}
