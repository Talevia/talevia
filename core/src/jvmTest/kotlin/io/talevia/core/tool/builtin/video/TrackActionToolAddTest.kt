package io.talevia.core.tool.builtin.video

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackActionToolAddTest {

    private val emittedSnapshots = mutableListOf<PartId>()

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { if (it is Part.TimelineSnapshot) emittedSnapshots += it.id },
        messages = emptyList(),
    )

    private suspend fun fixture(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-1")
        store.upsert(
            "title",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(Track.Video(id = TrackId("existing-video")))),
            ),
        )
        return store to pid
    }

    private fun addInput(
        pid: ProjectId,
        trackKind: String,
        trackId: String? = null,
    ): TrackActionTool.Input =
        TrackActionTool.Input(
            projectId = pid.value,
            action = "add",
            trackKind = trackKind,
            trackId = trackId,
        )

    @Test
    fun adds_video_track_with_generated_id() = runTest {
        val (store, pid) = fixture()
        val tool = TrackActionTool(store)

        val result = tool.execute(addInput(pid, "video"), ctx())

        assertEquals("add", result.data.action)
        assertEquals("video", result.data.trackKind)
        assertEquals(2, result.data.totalTrackCount)
        val after = store.get(pid)!!
        assertEquals(2, after.timeline.tracks.size)
        assertTrue(after.timeline.tracks.any { it is Track.Video && it.id.value == result.data.trackId })
    }

    @Test
    fun adds_audio_track_with_explicit_id() = runTest {
        val (store, pid) = fixture()
        val tool = TrackActionTool(store)

        val result = tool.execute(addInput(pid, "audio", trackId = "dialogue"), ctx())

        assertEquals("dialogue", result.data.trackId)
        val after = store.get(pid)!!
        assertTrue(after.timeline.tracks.any { it is Track.Audio && it.id.value == "dialogue" })
    }

    @Test
    fun adds_subtitle_track() = runTest {
        val (store, pid) = fixture()
        val result = TrackActionTool(store).execute(addInput(pid, "subtitle"), ctx())
        assertEquals("subtitle", result.data.trackKind)
        val after = store.get(pid)!!
        assertTrue(after.timeline.tracks.any { it is Track.Subtitle })
    }

    @Test
    fun adds_effect_track() = runTest {
        val (store, pid) = fixture()
        val result = TrackActionTool(store).execute(addInput(pid, "effect"), ctx())
        assertEquals("effect", result.data.trackKind)
        val after = store.get(pid)!!
        assertTrue(after.timeline.tracks.any { it is Track.Effect })
    }

    @Test
    fun trackKind_is_case_insensitive() = runTest {
        val (store, pid) = fixture()
        val result = TrackActionTool(store).execute(addInput(pid, " VIDEO "), ctx())
        assertEquals("video", result.data.trackKind)
    }

    @Test
    fun rejects_duplicate_track_id() = runTest {
        val (store, pid) = fixture()
        val tool = TrackActionTool(store)

        assertFailsWith<IllegalStateException> {
            tool.execute(addInput(pid, "video", trackId = "existing-video"), ctx())
        }
    }

    @Test
    fun rejects_unknown_kind() = runTest {
        val (store, pid) = fixture()
        val tool = TrackActionTool(store)

        assertFailsWith<IllegalArgumentException> {
            tool.execute(addInput(pid, "music"), ctx())
        }
    }

    @Test
    fun rejects_missing_project() = runTest {
        val store = ProjectStoreTestKit.create()
        val tool = TrackActionTool(store)

        assertFailsWith<IllegalStateException> {
            tool.execute(
                TrackActionTool.Input(
                    projectId = "no-such",
                    action = "add",
                    trackKind = "video",
                ),
                ctx(),
            )
        }
    }

    @Test
    fun rejects_add_without_trackKind() = runTest {
        // action=add with missing trackKind must fail loud — no silent default.
        // Covers §3a #9 counter-intuitive edge: the consolidated Input has
        // `trackKind: String? = null` (remove doesn't need it), so the
        // per-action validation in executeAdd carries the not-null guard.
        val (store, pid) = fixture()
        val tool = TrackActionTool(store)
        assertFailsWith<IllegalStateException> {
            tool.execute(
                TrackActionTool.Input(projectId = pid.value, action = "add"),
                ctx(),
            )
        }
    }

    @Test
    fun emits_timeline_snapshot() = runTest {
        val (store, pid) = fixture()

        TrackActionTool(store).execute(addInput(pid, "audio"), ctx())

        assertEquals(1, emittedSnapshots.size)
    }

    @Test
    fun multiple_audio_tracks_coexist() = runTest {
        val (store, pid) = fixture()
        val tool = TrackActionTool(store)

        tool.execute(addInput(pid, "audio", "dialogue"), ctx())
        tool.execute(addInput(pid, "audio", "music"), ctx())
        tool.execute(addInput(pid, "audio", "ambient"), ctx())

        val after = store.get(pid)!!
        val audioIds = after.timeline.tracks.filterIsInstance<Track.Audio>().map { it.id.value }.toSet()
        assertEquals(setOf("dialogue", "music", "ambient"), audioIds)
    }

    @Test
    fun rejects_unknown_action() = runTest {
        // Shared across add/remove consolidation: unknown action dispatch
        // must fail loud (catches typos like "added" / "insert") rather
        // than silently falling through to a default branch.
        val (store, pid) = fixture()
        val tool = TrackActionTool(store)
        assertFailsWith<IllegalStateException> {
            tool.execute(
                TrackActionTool.Input(projectId = pid.value, action = "insert", trackKind = "video"),
                ctx(),
            )
        }
    }
}
