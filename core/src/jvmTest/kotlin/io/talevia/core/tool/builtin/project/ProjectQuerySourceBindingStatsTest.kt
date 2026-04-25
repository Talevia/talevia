package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
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
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.SourceBindingStatsRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for `project_query(select=source_binding_stats)` — per-kind
 * coverage picture (direct / transitive / orphan partition).
 */
class ProjectQuerySourceBindingStatsTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun seedNode(
        store: FileProjectStore,
        pid: ProjectId,
        id: String,
        kind: String,
        parents: List<String> = emptyList(),
    ) {
        store.mutateSource(pid) { source ->
            source.addNode(
                SourceNode.create(
                    id = SourceNodeId(id),
                    kind = kind,
                    body = JsonObject(emptyMap()),
                    parents = parents.map { SourceRef(SourceNodeId(it)) },
                ),
            )
        }
    }

    private suspend fun seedClip(
        store: FileProjectStore,
        pid: ProjectId,
        clipId: String,
        binding: Set<String>,
    ) {
        store.mutate(pid) { project ->
            val track = (project.timeline.tracks.firstOrNull { it.id.value == "t1" } as? Track.Video)
                ?: Track.Video(id = TrackId("t1"))
            val clip = Clip.Video(
                id = ClipId(clipId),
                timeRange = TimeRange(start = 0.seconds, duration = 1.seconds),
                sourceRange = TimeRange(start = 0.seconds, duration = 1.seconds),
                assetId = AssetId("asset-$clipId"),
                sourceBinding = binding.map { SourceNodeId(it) }.toSet(),
            )
            val withClip = track.copy(clips = track.clips + clip)
            val tracks = project.timeline.tracks.filter { it.id.value != "t1" } + withClip
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }
    }

    private suspend fun freshProject(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        store.upsert("demo", Project(id = pid, timeline = Timeline()))
        return store to pid
    }

    @Test fun partitionsByDirectTransitiveOrphan() = runTest {
        val (store, pid) = freshProject()
        // character_ref kind: 3 nodes — c1 directly bound, c2 has child c2child bound (transitive), c3 orphan.
        seedNode(store, pid, "c1", "character_ref")
        seedNode(store, pid, "c2", "character_ref")
        seedNode(store, pid, "c3", "character_ref")
        seedNode(store, pid, "c2child", "narrative.shot", parents = listOf("c2"))
        // style_bible kind: 2 nodes — both orphan.
        seedNode(store, pid, "s1", "style_bible")
        seedNode(store, pid, "s2", "style_bible")

        // One clip binds c1 directly + c2child (which makes c2 transitively bound).
        seedClip(store, pid, "clip1", binding = setOf("c1", "c2child"))

        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "source_binding_stats",
            ),
            ctx(),
        ).data

        assertEquals("source_binding_stats", out.select)
        assertEquals(3, out.total) // 3 distinct kinds
        val rows = out.rows.decodeRowsAs(SourceBindingStatsRow.serializer())
        val byKind = rows.associateBy { it.kind }

        val character = byKind["character_ref"]!!
        assertEquals(3, character.totalNodes)
        assertEquals(1, character.boundDirectly)
        assertEquals(1, character.boundTransitively)
        assertEquals(1, character.orphans)
        assertEquals(listOf("c3"), character.orphanNodeIds)
        // 2/3 covered = 0.666...
        assertTrue(character.coverageRatio in 0.66..0.67)

        val style = byKind["style_bible"]!!
        assertEquals(2, style.totalNodes)
        assertEquals(0, style.boundDirectly)
        assertEquals(0, style.boundTransitively)
        assertEquals(2, style.orphans)
        assertEquals(0.0, style.coverageRatio)
    }

    @Test fun emptySourceReturnsEmptyRows() = runTest {
        val (store, pid) = freshProject()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "source_binding_stats",
            ),
            ctx(),
        ).data
        assertEquals(0, out.total)
        assertTrue(out.rows.toString() == "[]")
    }

    @Test fun summaryHighlightsLowestCoverage() = runTest {
        val (store, pid) = freshProject()
        seedNode(store, pid, "c1", "character_ref")
        seedNode(store, pid, "c2", "character_ref")
        seedNode(store, pid, "s1", "style_bible")
        seedClip(store, pid, "clip1", binding = setOf("c1"))

        val result = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "source_binding_stats",
            ),
            ctx(),
        )
        // style_bible is 0/1, lowest. Summary must call it out.
        assertNotNull(result.outputForLlm)
        assertTrue(
            "summary must highlight lowest-coverage kind: ${result.outputForLlm}",
        ) {
            result.outputForLlm.contains("Lowest coverage: style_bible")
        }
    }
}
