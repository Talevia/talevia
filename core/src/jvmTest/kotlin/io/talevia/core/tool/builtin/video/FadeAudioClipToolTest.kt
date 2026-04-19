package io.talevia.core.tool.builtin.video

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
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
import kotlin.time.Duration.Companion.seconds

/**
 * `fade_audio_clip` shapes the attack/release envelope on an audio clip.
 * Tests cover: setting fade-in only preserves existing fade-out, setting
 * fade-out only preserves existing fade-in, both at once, disabling via
 * `0.0`, the audio-only guard, the negative guard, the
 * fade-exceeds-duration guard, the missing-clip fail-loud, and the
 * post-mutation snapshot for `revert_timeline` parity.
 */
class FadeAudioClipToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val tool: FadeAudioClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private suspend fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val tool = FadeAudioClipTool(store)
        val parts = mutableListOf<Part>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { parts += it },
            messages = emptyList(),
        )
        store.upsert("test", project)
        return Rig(store, tool, ctx, project.id, parts)
    }

    private fun audioClip(
        id: String,
        fadeIn: Float = 0f,
        fadeOut: Float = 0f,
        durationSeconds: Int = 10,
    ): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, durationSeconds.seconds),
        sourceRange = TimeRange(0.seconds, durationSeconds.seconds),
        assetId = AssetId("a-$id"),
        fadeInSeconds = fadeIn,
        fadeOutSeconds = fadeOut,
    )

    @Test fun setsFadeInOnlyPreservesExistingFadeOut() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1", fadeOut = 1.5f)))),
                ),
            ),
        )
        val out = rig.tool.execute(
            FadeAudioClipTool.Input(rig.projectId.value, "c1", fadeInSeconds = 2.0f),
            rig.ctx,
        )
        assertEquals(0.0f, out.data.oldFadeInSeconds)
        assertEquals(2.0f, out.data.newFadeInSeconds)
        assertEquals(1.5f, out.data.oldFadeOutSeconds)
        assertEquals(1.5f, out.data.newFadeOutSeconds)

        val refreshed = rig.store.get(rig.projectId)!!
        val audio = refreshed.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(2.0f, audio.fadeInSeconds)
        assertEquals(1.5f, audio.fadeOutSeconds)
    }

    @Test fun setsFadeOutOnlyPreservesExistingFadeIn() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1", fadeIn = 0.5f)))),
                ),
            ),
        )
        rig.tool.execute(
            FadeAudioClipTool.Input(rig.projectId.value, "c1", fadeOutSeconds = 2.5f),
            rig.ctx,
        )
        val audio = rig.store.get(rig.projectId)!!.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(0.5f, audio.fadeInSeconds)
        assertEquals(2.5f, audio.fadeOutSeconds)
    }

    @Test fun setsBothSidesAtOnce() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        rig.tool.execute(
            FadeAudioClipTool.Input(rig.projectId.value, "c1", fadeInSeconds = 1.0f, fadeOutSeconds = 1.0f),
            rig.ctx,
        )
        val audio = rig.store.get(rig.projectId)!!.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(1.0f, audio.fadeInSeconds)
        assertEquals(1.0f, audio.fadeOutSeconds)
    }

    @Test fun zeroValueDisablesFade() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1", fadeIn = 2.0f, fadeOut = 2.0f)))),
                ),
            ),
        )
        rig.tool.execute(
            FadeAudioClipTool.Input(rig.projectId.value, "c1", fadeInSeconds = 0.0f),
            rig.ctx,
        )
        val audio = rig.store.get(rig.projectId)!!.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(0.0f, audio.fadeInSeconds)
        assertEquals(2.0f, audio.fadeOutSeconds)
    }

    @Test fun requiresAtLeastOneField() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(FadeAudioClipTool.Input(rig.projectId.value, "c1"), rig.ctx)
        }
    }

    @Test fun rejectsVideoClip() = runTest {
        val video = Clip.Video(
            id = ClipId("v1"),
            timeRange = TimeRange(0.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("av"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(video)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                FadeAudioClipTool.Input(rig.projectId.value, "v1", fadeInSeconds = 1.0f),
                rig.ctx,
            )
        }
        assertTrue("audio" in ex.message!!, ex.message)
    }

    @Test fun rejectsTextClip() = runTest {
        val text = Clip.Text(
            id = ClipId("t1"),
            timeRange = TimeRange(0.seconds, 5.seconds),
            text = "hi",
            style = TextStyle(),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("s1"), listOf(text)))),
            ),
        )
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                FadeAudioClipTool.Input(rig.projectId.value, "t1", fadeInSeconds = 1.0f),
                rig.ctx,
            )
        }
    }

    @Test fun rejectsNegativeFade() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                FadeAudioClipTool.Input(rig.projectId.value, "c1", fadeInSeconds = -1.0f),
                rig.ctx,
            )
        }
    }

    @Test fun rejectsOverlappingFades() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Audio(TrackId("a1"), listOf(audioClip("c1", durationSeconds = 5))),
                    ),
                ),
            ),
        )
        // 3 + 3 = 6 > 5s clip duration → must fail.
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                FadeAudioClipTool.Input(rig.projectId.value, "c1", fadeInSeconds = 3.0f, fadeOutSeconds = 3.0f),
                rig.ctx,
            )
        }
        assertTrue("overlap" in ex.message!! || "exceed" in ex.message!!, ex.message)
    }

    @Test fun missingClipFailsLoudly() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                FadeAudioClipTool.Input(rig.projectId.value, "ghost", fadeInSeconds = 1.0f),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun emitsTimelineSnapshotForRevert() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        rig.tool.execute(
            FadeAudioClipTool.Input(rig.projectId.value, "c1", fadeInSeconds = 1.2f, fadeOutSeconds = 0.8f),
            rig.ctx,
        )
        val snap = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>().single()
        val audio = snap.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(1.2f, audio.fadeInSeconds)
        assertEquals(0.8f, audio.fadeOutSeconds)
    }
}
