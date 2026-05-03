package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RemoveAssetToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = {},
        messages = emptyList(),
    )

    private fun asset(id: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(duration = 5.seconds, videoCodec = "h264"),
    )

    private suspend fun fixture(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")

        val track = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-1"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("v-used"),
                ),
                Clip.Video(
                    id = ClipId("c-2"),
                    timeRange = TimeRange(5.seconds, 3.seconds),
                    sourceRange = TimeRange(0.seconds, 3.seconds),
                    assetId = AssetId("v-used"),
                ),
            ),
        )

        val project = Project(
            id = pid,
            timeline = Timeline(tracks = listOf(track), duration = 8.seconds),
            assets = listOf(asset("v-used"), asset("v-unused")),
        )
        store.upsert("demo", project)
        return store to pid
    }

    private fun input(projectId: String, assetId: String, force: Boolean = false) =
        ProjectLifecycleActionTool.Input(
            action = "remove_asset",
            projectId = projectId,
            assetId = assetId,
            force = force,
        )

    @Test fun removesUnusedAsset() = runTest {
        val (store, pid) = fixture()
        val tool = ProjectLifecycleActionTool(store)
        val out = tool.execute(input(pid.value, "v-unused"), ctx()).data
        val remove = assertNotNull(out.removeAssetResult)
        assertEquals(true, remove.removed)
        assertTrue(remove.dependentClips.isEmpty())
        val remaining = store.get(pid)!!.assets.map { it.id.value }
        assertEquals(listOf("v-used"), remaining)
    }

    @Test fun refusesWhenAssetInUse() = runTest {
        val (store, pid) = fixture()
        val tool = ProjectLifecycleActionTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(input(pid.value, "v-used"), ctx())
        }
        assertTrue(ex.message!!.contains("in use"))
        assertTrue(ex.message!!.contains("c-1"))
        assertTrue(ex.message!!.contains("c-2"))
        val remaining = store.get(pid)!!.assets.map { it.id.value }
        assertEquals(setOf("v-used", "v-unused"), remaining.toSet())
    }

    @Test fun forceRemovesInUseAssetAndReportsDependents() = runTest {
        val (store, pid) = fixture()
        val tool = ProjectLifecycleActionTool(store)
        val out = tool.execute(input(pid.value, "v-used", force = true), ctx()).data
        val remove = assertNotNull(out.removeAssetResult)
        assertEquals(true, remove.removed)
        assertEquals(setOf("c-1", "c-2"), remove.dependentClips.toSet())
        val remaining = store.get(pid)!!.assets.map { it.id.value }
        assertEquals(listOf("v-unused"), remaining)
        // Dangling clips are intentionally left in place.
        val clipsStillThere = store.get(pid)!!.timeline.tracks
            .flatMap { it.clips }
            .map { it.id.value }
        assertEquals(setOf("c-1", "c-2"), clipsStillThere.toSet())
    }

    @Test fun rejectsMissingAsset() = runTest {
        val (store, pid) = fixture()
        val tool = ProjectLifecycleActionTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(input(pid.value, "nope"), ctx())
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsMissingProject() = runTest {
        val (store, _) = fixture()
        val tool = ProjectLifecycleActionTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(input("nope", "x"), ctx())
        }
        assertTrue(ex.message!!.contains("project"))
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun removalIsPersisted() = runTest {
        val (store, pid) = fixture()
        val tool = ProjectLifecycleActionTool(store)
        tool.execute(input(pid.value, "v-unused"), ctx())
        // Fetch a second time: asset should be gone.
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(input(pid.value, "v-unused"), ctx())
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun missingAssetIdFailsLoud() = runTest {
        val (store, pid) = fixture()
        val tool = ProjectLifecycleActionTool(store)
        assertFailsWith<IllegalStateException> {
            tool.execute(
                ProjectLifecycleActionTool.Input(action = "remove_asset", projectId = pid.value),
                ctx(),
            )
        }
    }
}
