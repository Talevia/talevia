package io.talevia.core.tool.builtin.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [DescribeSourceDagTool]. Uses a minimal genre-agnostic node kind so the
 * shape assertions don't depend on consistency-layer body schemas — this tool is
 * structural, not semantic.
 */
class DescribeSourceDagToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val ctx: ToolContext,
    )

    private suspend fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    private fun node(
        id: String,
        kind: String = "test.node",
        parents: List<String> = emptyList(),
        title: String = id,
    ): SourceNode = SourceNode.create(
        id = SourceNodeId(id),
        kind = kind,
        body = buildJsonObject { put("title", JsonPrimitive(title)) },
        parents = parents.map { SourceRef(SourceNodeId(it)) },
    )

    private fun sourceOf(vararg nodes: SourceNode): Source {
        var src = Source.EMPTY
        for (n in nodes) src = src.addNode(n)
        return src
    }

    private fun videoClip(
        id: String,
        binding: Set<String>,
        startS: Long = 0,
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(startS.seconds, 1.seconds),
        sourceRange = TimeRange(0.seconds, 1.seconds),
        assetId = AssetId("asset-$id"),
        sourceBinding = binding.map { SourceNodeId(it) }.toSet(),
    )

    private suspend fun seedProject(
        store: SqlDelightProjectStore,
        pid: String,
        source: Source,
        clips: List<Clip> = emptyList(),
    ) {
        val timeline = Timeline(
            tracks = if (clips.isEmpty()) emptyList() else listOf(
                Track.Video(id = TrackId("v0"), clips = clips.sortedBy { it.timeRange.start }),
            ),
        )
        store.upsert(pid, Project(id = ProjectId(pid), timeline = timeline, source = source))
    }

    @Test fun emptyDagReturnsAllZeroWithNonEmptySummary() = runTest {
        val rig = rig()
        seedProject(rig.store, "p", Source.EMPTY)
        val out = DescribeSourceDagTool(rig.store)
            .execute(DescribeSourceDagTool.Input(projectId = "p"), rig.ctx).data
        assertEquals(0, out.nodeCount)
        assertEquals(emptyMap(), out.nodesByKind)
        assertEquals(emptyList(), out.rootNodeIds)
        assertEquals(emptyList(), out.leafNodeIds)
        assertEquals(0, out.maxDepth)
        assertEquals(emptyList(), out.hotspots)
        assertEquals(emptyList(), out.orphanedNodeIds)
        assertTrue(out.summaryText.isNotBlank())
        assertTrue("0 nodes" in out.summaryText)
    }

    @Test fun singleNodeIsRootAndLeafDepthOne() = runTest {
        val rig = rig()
        seedProject(rig.store, "p", sourceOf(node("only")))
        val out = DescribeSourceDagTool(rig.store)
            .execute(DescribeSourceDagTool.Input(projectId = "p"), rig.ctx).data
        assertEquals(1, out.nodeCount)
        assertEquals(listOf("only"), out.rootNodeIds)
        assertEquals(listOf("only"), out.leafNodeIds)
        assertEquals(1, out.maxDepth)
        assertEquals(mapOf("test.node" to 1), out.nodesByKind)
    }

    @Test fun twoLevelChainHasDepthTwo() = runTest {
        val rig = rig()
        val src = sourceOf(
            node("parent"),
            node("child", parents = listOf("parent")),
        )
        seedProject(rig.store, "p", src)
        val out = DescribeSourceDagTool(rig.store)
            .execute(DescribeSourceDagTool.Input(projectId = "p"), rig.ctx).data
        assertEquals(2, out.nodeCount)
        assertEquals(listOf("parent"), out.rootNodeIds)
        assertEquals(listOf("child"), out.leafNodeIds)
        assertEquals(2, out.maxDepth)
    }

    @Test fun multiBranchDagPicksLongestChain() = runTest {
        val rig = rig()
        // Two roots: "rootA" feeds a 4-deep chain (rootA→b→c→leaf); "rootB" is a lone root.
        val src = sourceOf(
            node("rootA"),
            node("b", parents = listOf("rootA")),
            node("c", parents = listOf("b")),
            node("leaf", parents = listOf("c")),
            node("rootB"),
            node("short", parents = listOf("rootB")),
        )
        seedProject(rig.store, "p", src)
        val out = DescribeSourceDagTool(rig.store)
            .execute(DescribeSourceDagTool.Input(projectId = "p"), rig.ctx).data
        assertEquals(6, out.nodeCount)
        assertEquals(listOf("rootA", "rootB"), out.rootNodeIds)
        // Leaves: "leaf" (bottom of chain) + "short" (under rootB).
        assertEquals(listOf("leaf", "short"), out.leafNodeIds)
        // Longest chain is rootA → b → c → leaf = 4 hops of depth.
        assertEquals(4, out.maxDepth)
    }

    @Test fun hotspotsRankByTransitiveClipCountAndRespectLimit() = runTest {
        val rig = rig()
        // DAG: popular → leafA, leafB, leafC; mid → leafD; lonely (no children).
        val src = sourceOf(
            node("popular"),
            node("leafA", parents = listOf("popular")),
            node("leafB", parents = listOf("popular")),
            node("leafC", parents = listOf("popular")),
            node("mid"),
            node("leafD", parents = listOf("mid")),
            node("lonely"),
        )
        // Clip bindings:
        //  - c1 binds leafA directly → popular has transitive count 1.
        //  - c2 binds leafB directly → popular +1 (transitive 2).
        //  - c3 binds leafC directly → popular +1 (transitive 3).
        //  - c4 binds leafD directly → mid transitive 1.
        //  - c5 binds lonely directly → lonely transitive 1, direct 1.
        val clips = listOf(
            videoClip("c1", setOf("leafA"), startS = 0),
            videoClip("c2", setOf("leafB"), startS = 2),
            videoClip("c3", setOf("leafC"), startS = 4),
            videoClip("c4", setOf("leafD"), startS = 6),
            videoClip("c5", setOf("lonely"), startS = 8),
        )
        seedProject(rig.store, "p", src, clips)

        // hotspotLimit = 2 → top two by transitiveClipCount (popular=3, then
        // mid/leafA/leafB/leafC/leafD/lonely tie at 1 — tiebreak by nodeId ascending).
        val out = DescribeSourceDagTool(rig.store)
            .execute(DescribeSourceDagTool.Input(projectId = "p", hotspotLimit = 2), rig.ctx).data
        assertEquals(2, out.hotspots.size)
        assertEquals("popular", out.hotspots[0].nodeId)
        assertEquals(3, out.hotspots[0].transitiveClipCount)
        assertEquals(0, out.hotspots[0].directClipCount) // no clip binds "popular" directly
        // Second: among the transitiveClipCount=1 group, smallest nodeId is "leafA".
        assertEquals("leafA", out.hotspots[1].nodeId)
        assertEquals(1, out.hotspots[1].transitiveClipCount)
        assertEquals(1, out.hotspots[1].directClipCount)
    }

    @Test fun orphanedNodeShowsUp() = runTest {
        val rig = rig()
        // bound → child; free is unreferenced (and has no downstream clip reach).
        val src = sourceOf(
            node("bound"),
            node("child", parents = listOf("bound")),
            node("free"),
        )
        // Only one clip binds "child" → "bound" is transitively reached; "free" gets
        // nothing and should surface as orphan.
        val clips = listOf(videoClip("c1", setOf("child")))
        seedProject(rig.store, "p", src, clips)
        val out = DescribeSourceDagTool(rig.store)
            .execute(DescribeSourceDagTool.Input(projectId = "p"), rig.ctx).data
        assertEquals(listOf("free"), out.orphanedNodeIds)
        // "bound" should be a hotspot (transitively bound via child).
        assertTrue(out.hotspots.any { it.nodeId == "bound" && it.transitiveClipCount == 1 })
        assertTrue(out.hotspots.any { it.nodeId == "child" && it.directClipCount == 1 })
    }

    @Test fun missingProjectThrows() = runTest {
        val rig = rig()
        assertFailsWith<IllegalStateException> {
            DescribeSourceDagTool(rig.store)
                .execute(DescribeSourceDagTool.Input(projectId = "ghost"), rig.ctx)
        }
    }
}
