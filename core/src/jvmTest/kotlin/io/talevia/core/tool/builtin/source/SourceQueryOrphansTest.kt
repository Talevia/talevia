package io.talevia.core.tool.builtin.source

import io.talevia.core.CallId
import io.talevia.core.ClipId
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
import io.talevia.core.tool.builtin.source.query.OrphanRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `select=orphans` on [SourceQueryTool]. A node is orphan iff
 * [io.talevia.core.domain.clipsBoundTo] is empty (no clip binds it directly
 * or transitively). Semantic boundaries:
 *  - Empty DAG → zero rows.
 *  - DAG with zero clips on timeline → every node is orphan.
 *  - DAG with a clip bound directly to one node → that node is non-orphan,
 *    its ancestors (transitively bound) are also non-orphan, everything else
 *    orphan.
 *  - parentCount / childCount carry DAG-topology hints (leaf stray vs. root
 *    of orphan subtree).
 *  - Incompatible filters rejected (inherits the no-filters contract, same
 *    as `dot` / `ascii_tree`).
 */
class SourceQueryOrphansTest {

    private suspend fun fixture(
        nodes: List<SourceNode>,
        timeline: Timeline = Timeline(),
    ): Triple<FileProjectStore, ToolContext, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-orph")
        store.upsert("demo", Project(id = pid, timeline = timeline, source = Source(nodes = nodes)))
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
        revision: Long = 0,
    ): SourceNode = SourceNode(
        id = SourceNodeId(id),
        kind = kind,
        body = JsonObject(emptyMap()),
        parents = parents.map { SourceRef(SourceNodeId(it)) },
        revision = revision,
    )

    private suspend fun runOrphans(
        store: FileProjectStore,
        ctx: ToolContext,
        pid: ProjectId,
    ): SourceQueryTool.Output = SourceQueryTool(store).execute(
        SourceQueryTool.Input(select = "orphans", projectId = pid.value),
        ctx,
    ).data

    private fun rows(out: SourceQueryTool.Output): List<OrphanRow> =
        out.rows.decodeRowsAs(OrphanRow.serializer())

    @Test fun emptyDagReturnsNoRows() = runTest {
        val (store, ctx, pid) = fixture(nodes = emptyList())
        val out = runOrphans(store, ctx, pid)
        assertEquals(0, out.total)
        assertEquals(0, out.returned)
        assertEquals(emptyList(), rows(out))
    }

    @Test fun allNodesOrphanWhenNoClipsBind() = runTest {
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("a", kind = "k.a"),
                node("b", kind = "k.b", parents = listOf("a"), revision = 3),
            ),
        )
        val out = runOrphans(store, ctx, pid)
        val got = rows(out)
        assertEquals(2, out.total)
        assertEquals(listOf("a", "b"), got.map { it.id })
        val a = got.first { it.id == "a" }
        val b = got.first { it.id == "b" }
        assertEquals("k.a", a.kind)
        assertEquals(0, a.parentCount)
        assertEquals(1, a.childCount, "a has one child (b)")
        assertEquals(0L, a.revision)
        assertEquals("k.b", b.kind)
        assertEquals(1, b.parentCount, "b has one parent (a)")
        assertEquals(0, b.childCount)
        assertEquals(3L, b.revision)
    }

    @Test fun directBindingExcludesNodeAndAncestors() = runTest {
        // DAG: root → mid → leaf; one clip binds `leaf`. Per clipsBoundTo's
        // transitive-downstream semantics, editing `root` propagates down to
        // `leaf`, so `leaf`'s binding is reachable from both `root` and
        // `mid` — all three are non-orphan. A fourth node `stray` is orphan.
        val trackId = TrackId("t")
        val clip = Clip.Text(
            id = ClipId("c-caption"),
            timeRange = TimeRange(start = 0.seconds, duration = 1.seconds),
            text = "caption",
            sourceBinding = setOf(SourceNodeId("leaf")),
        )
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("root"),
                node("mid", parents = listOf("root")),
                node("leaf", parents = listOf("mid")),
                node("stray"),
            ),
            timeline = Timeline(tracks = listOf(Track.Subtitle(id = trackId, clips = listOf(clip)))),
        )
        val got = rows(runOrphans(store, ctx, pid))
        assertEquals(listOf("stray"), got.map { it.id }, "only stray is orphan; root/mid/leaf are transitively bound")
        val stray = got.single()
        assertEquals(0, stray.parentCount)
        assertEquals(0, stray.childCount)
    }

    @Test fun parentAndChildCountsHelpCleanupChoice() = runTest {
        // Orphan subtree: top is root, two children, no clips anywhere.
        // A fully stray (standalone) sibling alongside to contrast.
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("top"),
                node("c1", parents = listOf("top")),
                node("c2", parents = listOf("top")),
                node("stray"),
            ),
        )
        val got = rows(runOrphans(store, ctx, pid))
        assertEquals(listOf("c1", "c2", "stray", "top"), got.map { it.id }, "sorted by id")
        val top = got.first { it.id == "top" }
        val c1 = got.first { it.id == "c1" }
        val stray = got.first { it.id == "stray" }
        assertEquals(2, top.childCount, "top roots an orphan subtree with two children")
        assertEquals(0, top.parentCount)
        assertEquals(1, c1.parentCount)
        assertEquals(0, c1.childCount)
        assertEquals(0, stray.parentCount)
        assertEquals(0, stray.childCount, "stray has zero of both — fully standalone")
    }

    @Test fun incompatibleFilterRejected() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("x")))
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(select = "orphans", projectId = pid.value, kind = "test.generic"),
                ctx,
            )
        }
        assertTrue("kind" in ex.message!!, "kind filter must be rejected on orphans: ${ex.message}")
    }

    @Test fun paginationRejected() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("x"), node("y")))
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(select = "orphans", projectId = pid.value, limit = 1),
                ctx,
            )
        }
        assertTrue("limit" in ex.message!!, "limit must be rejected on orphans (no pagination): ${ex.message}")
    }
}
