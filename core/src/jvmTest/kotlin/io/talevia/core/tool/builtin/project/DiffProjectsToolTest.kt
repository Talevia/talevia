package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.addNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DiffProjectsToolTest {

    private data class Rig(val store: FileProjectStore, val ctx: ToolContext)

    private fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
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

    private fun video(id: String, asset: String, start: Int = 0, end: Int = 2): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, end.seconds),
        sourceRange = TimeRange(0.seconds, (end - start).seconds),
        assetId = AssetId(asset),
    )

    private fun project(
        id: String = "p",
        clips: List<Clip.Video> = emptyList(),
        source: Source = Source.EMPTY,
        lockfile: Lockfile = Lockfile.EMPTY,
        snapshots: List<ProjectSnapshot> = emptyList(),
    ): Project = Project(
        id = ProjectId(id),
        timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), clips))),
        source = source,
        lockfile = lockfile,
        snapshots = snapshots,
    )

    private fun lockEntry(hash: String, toolId: String = "generate_image", asset: String = "a-$hash"): LockfileEntry =
        LockfileEntry(
            inputHash = hash,
            toolId = toolId,
            assetId = AssetId(asset),
            provenance = GenerationProvenance(
                providerId = "fake",
                modelId = "m",
                modelVersion = null,
                seed = 1L,
                parameters = JsonObject(emptyMap()),
                createdAtEpochMs = 1L,
            ),
        )

    private fun snap(id: String, label: String, payload: Project): ProjectSnapshot = ProjectSnapshot(
        id = ProjectSnapshotId(id),
        label = label,
        capturedAtEpochMs = 1L,
        project = payload,
    )

    @Test fun identicalProjectsAreIdentical() = runTest {
        val rig = rig()
        rig.store.upsert("test", project(clips = listOf(video("c1", "a1"))))
        val out = DiffProjectsTool(rig.store).execute(
            DiffProjectsTool.Input(fromProjectId = "p"),
            rig.ctx,
        )
        assertTrue(out.data.summary.identical, out.outputForLlm)
        assertEquals(0, out.data.summary.totalTimelineChanges)
        assertEquals(0, out.data.summary.totalSourceChanges)
        assertEquals(0, out.data.summary.totalLockfileChanges)
    }

    @Test fun snapshotVsCurrentReportsAddedClip() = runTest {
        val rig = rig()
        // Snapshot taken with one clip; current state has two.
        val v1Payload = project(clips = listOf(video("c1", "a1")))
        rig.store.upsert(
            "test",
            project(
                clips = listOf(video("c1", "a1"), video("c2", "a2", start = 2, end = 4)),
                snapshots = listOf(snap("s1", "v1", v1Payload)),
            ),
        )
        val out = DiffProjectsTool(rig.store).execute(
            DiffProjectsTool.Input(fromProjectId = "p", fromSnapshotId = "s1"),
            rig.ctx,
        )
        assertFalse(out.data.summary.identical)
        assertEquals(1, out.data.timeline.clipsAdded.size)
        assertEquals("c2", out.data.timeline.clipsAdded.single().clipId)
        assertEquals(0, out.data.timeline.clipsRemoved.size)
        assertEquals(0, out.data.timeline.clipsChanged.size)
        assertTrue("v1" in out.data.fromLabel)
        assertTrue("@current" in out.data.toLabel)
    }

    @Test fun changedClipReportsExactFields() = runTest {
        val rig = rig()
        val v1Payload = project(clips = listOf(video("c1", "a1", start = 0, end = 2)))
        // Same clip id, different asset + different timeRange.
        rig.store.upsert(
            "test",
            project(
                clips = listOf(video("c1", "a2", start = 1, end = 3)),
                snapshots = listOf(snap("s1", "v1", v1Payload)),
            ),
        )
        val out = DiffProjectsTool(rig.store).execute(
            DiffProjectsTool.Input(fromProjectId = "p", fromSnapshotId = "s1"),
            rig.ctx,
        )
        assertEquals(1, out.data.timeline.clipsChanged.size)
        val change = out.data.timeline.clipsChanged.single()
        assertEquals("c1", change.clipId)
        assertTrue("assetId" in change.changedFields, change.changedFields.toString())
        assertTrue("timeRange" in change.changedFields, change.changedFields.toString())
    }

    @Test fun sourceDiffDetectsAddRemoveChange() = runTest {
        val rig = rig()
        val srcV1 = Source.EMPTY
            .addNode(SourceNode.create(SourceNodeId("a"), "narrative.character", buildJsonObject { put("name", JsonPrimitive("Mei")) }))
            .addNode(SourceNode.create(SourceNodeId("b"), "narrative.character", buildJsonObject { put("name", JsonPrimitive("Bo")) }))
        val srcV2 = Source.EMPTY
            // a's body changed → contentHash changes → "changed"
            .addNode(SourceNode.create(SourceNodeId("a"), "narrative.character", buildJsonObject { put("name", JsonPrimitive("Mei v2")) }))
            // b removed
            // c added
            .addNode(SourceNode.create(SourceNodeId("c"), "narrative.character", buildJsonObject { put("name", JsonPrimitive("Lin")) }))

        val v1Payload = project(source = srcV1)
        rig.store.upsert("test", project(source = srcV2, snapshots = listOf(snap("s1", "v1", v1Payload))))
        val out = DiffProjectsTool(rig.store).execute(
            DiffProjectsTool.Input(fromProjectId = "p", fromSnapshotId = "s1"),
            rig.ctx,
        )
        assertEquals(setOf("c"), out.data.source.nodesAdded.map { it.nodeId }.toSet())
        assertEquals(setOf("b"), out.data.source.nodesRemoved.map { it.nodeId }.toSet())
        assertEquals(setOf("a"), out.data.source.nodesChanged.map { it.nodeId }.toSet())
    }

    @Test fun lockfileDeltaCountsByTool() = runTest {
        val rig = rig()
        val v1 = Lockfile.EMPTY.append(lockEntry("h1"))
        val v2 = v1
            .append(lockEntry("h2", toolId = "generate_image"))
            .append(lockEntry("h3", toolId = "generate_image"))
            .append(lockEntry("h4", toolId = "synthesize_speech"))

        val v1Payload = project(lockfile = v1)
        rig.store.upsert("test", project(lockfile = v2, snapshots = listOf(snap("s1", "v1", v1Payload))))
        val out = DiffProjectsTool(rig.store).execute(
            DiffProjectsTool.Input(fromProjectId = "p", fromSnapshotId = "s1"),
            rig.ctx,
        )
        assertEquals(3, out.data.lockfile.entriesAdded)
        assertEquals(0, out.data.lockfile.entriesRemoved)
        assertEquals(mapOf("generate_image" to 2, "synthesize_speech" to 1), out.data.lockfile.addedToolIds)
    }

    @Test fun crossProjectDiffWorksLikeForkVsParent() = runTest {
        val rig = rig()
        rig.store.upsert("parent", project(id = "parent", clips = listOf(video("c1", "a1"))))
        rig.store.upsert(
            "fork",
            project(id = "fork", clips = listOf(video("c1", "a1"), video("c2", "a2", start = 2, end = 4))),
        )
        val out = DiffProjectsTool(rig.store).execute(
            DiffProjectsTool.Input(fromProjectId = "parent", toProjectId = "fork"),
            rig.ctx,
        )
        assertFalse(out.data.summary.identical)
        assertEquals(1, out.data.timeline.clipsAdded.size)
        assertEquals("c2", out.data.timeline.clipsAdded.single().clipId)
        assertTrue("parent" in out.data.fromLabel)
        assertTrue("fork" in out.data.toLabel)
    }

    @Test fun missingProjectFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            DiffProjectsTool(rig.store).execute(
                DiffProjectsTool.Input(fromProjectId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun missingSnapshotFailsLoudly() = runTest {
        val rig = rig()
        rig.store.upsert("test", project())
        val ex = assertFailsWith<IllegalStateException> {
            DiffProjectsTool(rig.store).execute(
                DiffProjectsTool.Input(fromProjectId = "p", fromSnapshotId = "ghost-snap"),
                rig.ctx,
            )
        }
        assertTrue("ghost-snap" in ex.message!!, ex.message)
    }
}
