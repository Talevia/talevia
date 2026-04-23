package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.TimelineDiffRow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `select=timeline_diff` on [ProjectQueryTool]. Scenarios: clip
 * added between snapshot and current, clip removed + asset-swapped,
 * track added, snapshot-vs-snapshot, and the error paths (missing
 * snapshot id, both null inputs, filter applied outside select).
 */
class ProjectQueryTimelineDiffTest {

    private data class Rig(
        val store: FileProjectStore,
        val ctx: ToolContext,
    )

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

    private fun videoClip(id: String, assetIdValue: String, durationS: Int = 2): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, durationS.seconds),
        sourceRange = TimeRange(0.seconds, durationS.seconds),
        assetId = AssetId(assetIdValue),
    )

    private fun project(
        id: String,
        clips: List<Clip.Video> = emptyList(),
        extraTracks: List<Track> = emptyList(),
        snapshots: List<ProjectSnapshot> = emptyList(),
    ): Project = Project(
        id = ProjectId(id),
        timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), clips)) + extraTracks),
        assets = clips.map {
            MediaAsset(
                id = it.assetId,
                source = MediaSource.File("/tmp/${it.assetId.value}.mp4"),
                metadata = MediaMetadata(duration = 5.seconds),
            )
        },
        snapshots = snapshots,
    )

    private suspend fun runDiff(rig: Rig, projectId: String, fromSnap: String?, toSnap: String?): TimelineDiffRow {
        val out = ProjectQueryTool(rig.store).execute(
            ProjectQueryTool.Input(
                projectId = projectId,
                select = "timeline_diff",
                fromSnapshotId = fromSnap,
                toSnapshotId = toSnap,
            ),
            rig.ctx,
        ).data
        assertEquals(1, out.total)
        assertEquals(1, out.returned)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(TimelineDiffRow.serializer()),
            out.rows,
        )
        return rows.single()
    }

    @Test fun diffSnapshotVsCurrentDetectsAddedClip() = runTest {
        val rig = rig()
        val v1Clips = listOf(videoClip("c-1", "a-1"))
        val v2Clips = v1Clips + videoClip("c-2", "a-2")
        val snap = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v1"),
            label = "v1",
            capturedAtEpochMs = 1_000L,
            project = project("p", clips = v1Clips),
        )
        rig.store.upsert("demo", project("p", clips = v2Clips, snapshots = listOf(snap)))

        val diff = runDiff(rig, "p", fromSnap = "snap-v1", toSnap = null)
        assertFalse(diff.identical)
        assertEquals(1, diff.totalChanges)
        assertEquals(listOf("c-2"), diff.clipsAdded.map { it.clipId })
        assertTrue(diff.clipsRemoved.isEmpty())
        assertTrue(diff.clipsChanged.isEmpty())
        assertTrue(diff.fromLabel.contains("v1"))
        assertTrue(diff.toLabel.contains("current"))
    }

    @Test fun diffDetectsClipRemovedAndChanged() = runTest {
        val rig = rig()
        val v1Clips = listOf(
            videoClip("c-1", "a-1"),
            videoClip("c-2", "a-2"),
        )
        val v2Clips = listOf(videoClip("c-1", "a-1-swapped", durationS = 5))
        val snap = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v1"),
            label = "v1",
            capturedAtEpochMs = 1_000L,
            project = project("p", clips = v1Clips),
        )
        rig.store.upsert("demo", project("p", clips = v2Clips, snapshots = listOf(snap)))

        val diff = runDiff(rig, "p", fromSnap = "snap-v1", toSnap = null)
        assertEquals(listOf("c-2"), diff.clipsRemoved.map { it.clipId })
        val c1Change = diff.clipsChanged.single { it.clipId == "c-1" }
        assertTrue("assetId" in c1Change.changedFields, "assetId swap should surface")
        assertTrue(
            "timeRange" in c1Change.changedFields || "sourceRange" in c1Change.changedFields,
            "duration change should surface as timeRange/sourceRange delta",
        )
    }

    @Test fun diffDetectsTrackAdded() = runTest {
        val rig = rig()
        val snap = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v1"),
            label = "v1",
            capturedAtEpochMs = 1_000L,
            project = project("p"),
        )
        val withAudioTrack = project(
            "p",
            extraTracks = listOf(Track.Audio(TrackId("a1"))),
            snapshots = listOf(snap),
        )
        rig.store.upsert("demo", withAudioTrack)

        val diff = runDiff(rig, "p", fromSnap = "snap-v1", toSnap = null)
        assertEquals(listOf("a1"), diff.tracksAdded.map { it.trackId })
        assertEquals("audio", diff.tracksAdded.single().kind)
    }

    @Test fun diffSnapshotVsSnapshotBothNamed() = runTest {
        val rig = rig()
        val snap1 = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v1"),
            label = "v1",
            capturedAtEpochMs = 1_000L,
            project = project("p", clips = listOf(videoClip("c-1", "a-1"))),
        )
        val snap2 = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v2"),
            label = "v2",
            capturedAtEpochMs = 2_000L,
            project = project(
                "p",
                clips = listOf(videoClip("c-1", "a-1"), videoClip("c-2", "a-2")),
            ),
        )
        rig.store.upsert(
            "demo",
            project("p", snapshots = listOf(snap1, snap2)),
        )

        val diff = runDiff(rig, "p", fromSnap = "snap-v1", toSnap = "snap-v2")
        assertEquals(listOf("c-2"), diff.clipsAdded.map { it.clipId })
        assertTrue(diff.fromLabel.contains("v1") && diff.toLabel.contains("v2"))
    }

    @Test fun diffBothNullFailsLoud() = runTest {
        val rig = rig()
        rig.store.upsert("demo", project("p"))
        val ex = assertFailsWith<IllegalArgumentException> {
            runDiff(rig, "p", fromSnap = null, toSnap = null)
        }
        assertTrue(ex.message!!.contains("fromSnapshotId") || ex.message!!.contains("toSnapshotId"), ex.message)
    }

    @Test fun diffUnknownSnapshotFailsLoud() = runTest {
        val rig = rig()
        rig.store.upsert("demo", project("p"))
        val ex = assertFailsWith<IllegalStateException> {
            runDiff(rig, "p", fromSnap = "ghost", toSnap = null)
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("project_query(select=snapshots)"), "error should hint at the list route")
    }

    @Test fun fromSnapshotIdRejectedOnOtherSelects() = runTest {
        val rig = rig()
        rig.store.upsert("demo", project("p"))
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(rig.store).execute(
                ProjectQueryTool.Input(
                    projectId = "p",
                    select = "timeline_clips",
                    fromSnapshotId = "snap-v1",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("fromSnapshotId"), ex.message)
    }

    @Test fun diffIdenticalSnapshotVsSameSnapshotReportsNoChanges() = runTest {
        val rig = rig()
        val snap = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v1"),
            label = "v1",
            capturedAtEpochMs = 1_000L,
            project = project("p", clips = listOf(videoClip("c-1", "a-1"))),
        )
        rig.store.upsert("demo", project("p", snapshots = listOf(snap)))

        val diff = runDiff(rig, "p", fromSnap = "snap-v1", toSnap = "snap-v1")
        assertTrue(diff.identical)
        assertEquals(0, diff.totalChanges)
    }
}
