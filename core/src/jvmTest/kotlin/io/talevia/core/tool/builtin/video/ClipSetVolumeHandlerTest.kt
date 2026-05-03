package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [executeClipSetVolume] —
 * `core/tool/builtin/video/ClipSetVolumeHandler.kt`.
 * The `clip_action(action="set_volume")` audio-only volume
 * multiplier handler. Cycle 204 audit: 82 LOC, 0 direct
 * test refs (cycle 203 covered fade only).
 *
 * Five correctness contracts pinned:
 *
 *  1. **`volumeItems` required + non-empty.** Drift to "null
 *     silently no-ops" would silently swallow agent intent.
 *
 *  2. **Range bound `[0, MAX_VOLUME]` with finite check.**
 *     MAX_VOLUME = 4.0 per the parent tool. Negative,
 *     NaN, +Infinity, or > 4 must fail loud — clip-level
 *     gain beyond 4× is "mix-time staging" (per the impl's
 *     own error message) so callers can't smuggle through.
 *
 *  3. **Audio-only with type discrimination.** Per impl
 *     `clip as? Clip.Audio ?: error(...)`. A Video or Text
 *     clip with the same id must trigger fail-loud, never a
 *     silent no-op.
 *
 *  4. **Foreign-field rejection.** Per
 *     `rejectForeignClipActionFields("set_volume", input)`,
 *     leaking another action's payload (e.g. `fadeItems`)
 *     fails fast — this is the typo-surface guard for the
 *     unified `clip_action` dispatcher.
 *
 *  5. **Atomic / no partial mutation on mid-batch failure.**
 *     `store.mutate` runs the whole list under one mutex;
 *     a bad clipId at items[1] must roll back items[0]'s
 *     change. Drift would leak partial volume edits.
 *
 * Plus shape pins: snapshot emitted, output title format
 * `"set volume × N"`, `volumeResults` populated with old
 * + new values, multi-item ordering preserved.
 */
class ClipSetVolumeHandlerTest {

    private val ctx: ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun anyRange(durationSec: Long = 10) = TimeRange(
        start = 0.seconds,
        duration = durationSec.seconds,
    )

    private fun audioClip(id: String, volume: Float = 1.0f): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId("asset-$id"),
        volume = volume,
    )

    private fun videoClip(id: String): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId("v-$id"),
    )

    private fun textClip(id: String): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = anyRange(),
        text = "hi",
        style = TextStyle(),
    )

    private suspend fun newProjectWithAudioTrack(
        store: FileProjectStore,
        clips: List<Clip>,
    ): Project {
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        return store.mutate(created.id) { p ->
            p.copy(
                timeline = Timeline(
                    tracks = if (clips.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(Track.Audio(id = TrackId("a"), clips = clips))
                    },
                ),
            )
        }
    }

    private fun input(
        items: List<ClipActionTool.VolumeItem>?,
        action: String = "set_volume",
        addItems: List<ClipActionTool.AddItem>? = null,
        clipIds: List<String>? = null,
        fadeItems: List<ClipActionTool.FadeItem>? = null,
        transformItems: List<ClipActionTool.TransformItem>? = null,
    ): ClipActionTool.Input = ClipActionTool.Input(
        action = action,
        volumeItems = items,
        addItems = addItems,
        clipIds = clipIds,
        fadeItems = fadeItems,
        transformItems = transformItems,
    )

    // ── 1. Required-input rejection ─────────────────────────

    @Test fun missingVolumeItemsThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, emptyList())
        val ex = assertFailsWith<IllegalStateException> {
            executeClipSetVolume(store, project.id, input(items = null), ctx)
        }
        assertTrue(
            "requires `volumeItems`" in (ex.message ?: ""),
            "expected 'requires volumeItems'; got: ${ex.message}",
        )
    }

    @Test fun emptyVolumeItemsThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, emptyList())
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetVolume(store, project.id, input(items = emptyList()), ctx)
        }
        assertTrue("must not be empty" in (ex.message ?: ""))
    }

    // ── 2. Range validation ─────────────────────────────────

    @Test fun nonFiniteVolumeRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, listOf(audioClip("c1")))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val ex = assertFailsWith<IllegalArgumentException> {
                executeClipSetVolume(
                    store = store,
                    pid = project.id,
                    input = input(items = listOf(ClipActionTool.VolumeItem("c1", bad))),
                    ctx = ctx,
                )
            }
            assertTrue(
                "must be finite" in (ex.message ?: ""),
                "expected 'must be finite' for $bad; got: ${ex.message}",
            )
        }
    }

    @Test fun negativeVolumeRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, listOf(audioClip("c1")))
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetVolume(
                store = store,
                pid = project.id,
                input = input(items = listOf(ClipActionTool.VolumeItem("c1", -0.5f))),
                ctx = ctx,
            )
        }
        assertTrue(
            "must be >= 0" in (ex.message ?: ""),
            "expected '>= 0' message; got: ${ex.message}",
        )
    }

    @Test fun volumeAboveMaxRejectedWithMixTimeStagingHint() = runTest {
        // Marquee MAX_VOLUME pin: above 4.0 must fail with the
        // "mix-time staging" remediation hint so the agent
        // doesn't silently inject a 10x gain.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, listOf(audioClip("c1")))
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetVolume(
                store = store,
                pid = project.id,
                input = input(items = listOf(ClipActionTool.VolumeItem("c1", 4.001f))),
                ctx = ctx,
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "must be <= ${ClipActionTool.MAX_VOLUME}" in msg,
            "expected MAX_VOLUME ceiling cited; got: $msg",
        )
        assertTrue(
            "mix-time staging" in msg,
            "expected mix-time staging hint; got: $msg",
        )
    }

    @Test fun volumeAtMaxBoundaryAccepted() = runTest {
        // Pin: <= 4.0 is the bound (inclusive).
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, listOf(audioClip("c1", volume = 1.0f)))
        val result = executeClipSetVolume(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(ClipActionTool.VolumeItem("c1", ClipActionTool.MAX_VOLUME)),
            ),
            ctx = ctx,
        )
        assertEquals(1, result.data!!.volumeResults.size)
        assertEquals(ClipActionTool.MAX_VOLUME, result.data!!.volumeResults[0].newVolume)
    }

    @Test fun volumeAtZeroBoundaryAcceptedAsMute() = runTest {
        // Pin: 0.0 (mute) is the lower bound, inclusive.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, listOf(audioClip("c1", volume = 1.0f)))
        val result = executeClipSetVolume(
            store = store,
            pid = project.id,
            input = input(items = listOf(ClipActionTool.VolumeItem("c1", 0f))),
            ctx = ctx,
        )
        assertEquals(0f, result.data!!.volumeResults[0].newVolume)
    }

    // ── 3. Audio-only with type discrimination ──────────────

    @Test fun videoClipRejectedAsNonAudio() = runTest {
        // Marquee audio-only pin: a Video clip with the same id
        // must trigger fail-loud, not silent no-op.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val project = store.mutate(created.id) { p ->
            p.copy(
                timeline = Timeline(
                    tracks = listOf(Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1")))),
                ),
            )
        }
        val ex = assertFailsWith<IllegalStateException> {
            executeClipSetVolume(
                store = store,
                pid = project.id,
                input = input(items = listOf(ClipActionTool.VolumeItem("c1", 0.5f))),
                ctx = ctx,
            )
        }
        assertTrue(
            "set_volume only applies to audio clips" in (ex.message ?: ""),
            "expected audio-only error; got: ${ex.message}",
        )
        assertTrue(
            "Video" in (ex.message ?: ""),
            "expected variant name in error; got: ${ex.message}",
        )
    }

    @Test fun textClipRejectedAsNonAudio() = runTest {
        // Pin: Text variant also rejected (covers all non-audio
        // sealed-class branches).
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val project = store.mutate(created.id) { p ->
            p.copy(
                timeline = Timeline(
                    tracks = listOf(
                        Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("c1"))),
                    ),
                ),
            )
        }
        val ex = assertFailsWith<IllegalStateException> {
            executeClipSetVolume(
                store = store,
                pid = project.id,
                input = input(items = listOf(ClipActionTool.VolumeItem("c1", 0.5f))),
                ctx = ctx,
            )
        }
        assertTrue("only applies to audio clips" in (ex.message ?: ""))
        assertTrue("Text" in (ex.message ?: ""))
    }

    // ── 4. Foreign-field rejection ──────────────────────────

    @Test fun foreignFieldsRejected() = runTest {
        // Marquee typo-surface pin: leaking `fadeItems`
        // alongside `set_volume` payload must fail loud, not
        // silently ignore.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, listOf(audioClip("c1")))
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetVolume(
                store = store,
                pid = project.id,
                input = input(
                    items = listOf(ClipActionTool.VolumeItem("c1", 0.5f)),
                    fadeItems = listOf(ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 1f)),
                ),
                ctx = ctx,
            )
        }
        val msg = ex.message ?: ""
        assertTrue("action=set_volume rejects" in msg, "expected rejection prefix; got: $msg")
        assertTrue("fadeItems" in msg, "expected foreign field cited; got: $msg")
    }

    // ── 5. Clip not found ───────────────────────────────────

    @Test fun missingClipFailsLoudWithIdAndProject() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, listOf(audioClip("c1")))
        val ex = assertFailsWith<IllegalStateException> {
            executeClipSetVolume(
                store = store,
                pid = project.id,
                input = input(items = listOf(ClipActionTool.VolumeItem("ghost", 0.5f))),
                ctx = ctx,
            )
        }
        val msg = ex.message ?: ""
        assertTrue("clip ghost not found" in msg, "expected clipId in error; got: $msg")
        assertTrue(project.id.value in msg, "expected projectId in error; got: $msg")
    }

    // ── Atomic mid-batch failure rollback ───────────────────

    @Test fun midBatchFailureLeavesEarlierEditsUnapplied() = runTest {
        // Marquee atomicity pin: items=[ok, badClip] must roll
        // back items[0]'s volume change. `store.mutate`
        // runs the whole closure under one mutex; an `error`
        // mid-closure rolls back the in-flight Project.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(
            store,
            listOf(audioClip("c1", volume = 1.0f), audioClip("c2", volume = 1.0f)),
        )
        assertFailsWith<IllegalStateException> {
            executeClipSetVolume(
                store = store,
                pid = project.id,
                input = input(
                    items = listOf(
                        ClipActionTool.VolumeItem("c1", 0.25f), // would succeed
                        ClipActionTool.VolumeItem("ghost", 0.5f), // fails
                    ),
                ),
                ctx = ctx,
            )
        }
        // Verify project is untouched: c1 still at 1.0.
        val after = store.get(project.id)!!
        val c1After = after.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c1" } as Clip.Audio
        assertEquals(
            1.0f,
            c1After.volume,
            "atomic rollback: items[0] edit must NOT survive a mid-batch failure",
        )
    }

    // ── Happy-path shape pins ───────────────────────────────

    @Test fun singleClipHappyPathReportsOldAndNew() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, listOf(audioClip("c1", volume = 1.0f)))
        val result = executeClipSetVolume(
            store = store,
            pid = project.id,
            input = input(items = listOf(ClipActionTool.VolumeItem("c1", 0.7f))),
            ctx = ctx,
        )
        val data = result.data!!
        assertEquals("set_volume", data.action)
        assertEquals(project.id.value, data.projectId)
        assertEquals(1, data.volumeResults.size)
        val r = data.volumeResults[0]
        assertEquals("c1", r.clipId)
        assertEquals("a", r.trackId)
        assertEquals(1.0f, r.oldVolume)
        assertEquals(0.7f, r.newVolume)
        assertTrue(data.snapshotId.isNotBlank(), "snapshot must be emitted")
        assertEquals("set volume × 1", result.title)
        assertTrue("Snapshot:" in result.outputForLlm, "outputForLlm cites snapshot")
    }

    @Test fun multiClipResultsPreserveInputOrder() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(
            store,
            listOf(
                audioClip("c1", volume = 1.0f),
                audioClip("c2", volume = 1.0f),
                audioClip("c3", volume = 1.0f),
            ),
        )
        val result = executeClipSetVolume(
            store = store,
            pid = project.id,
            input = input(
                items = listOf(
                    ClipActionTool.VolumeItem("c2", 0.5f),
                    ClipActionTool.VolumeItem("c1", 2.0f),
                    ClipActionTool.VolumeItem("c3", 0.0f),
                ),
            ),
            ctx = ctx,
        )
        val results = result.data!!.volumeResults
        assertEquals(3, results.size)
        // Ordering = input ordering, NOT clip id sort.
        assertEquals(listOf("c2", "c1", "c3"), results.map { it.clipId })
        assertEquals(listOf(0.5f, 2.0f, 0.0f), results.map { it.newVolume })
        assertEquals("set volume × 3", result.title)
    }

    @Test fun mutationActuallyPersists() = runTest {
        // Sanity round-trip: post-call store reflects the
        // edit. Pairs with `midBatchFailureLeavesEarlierEditsUnapplied`
        // (the rollback test) — that one proves "no partial",
        // this one proves "yes-final on success".
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(store, listOf(audioClip("c1", volume = 1.0f)))
        executeClipSetVolume(
            store = store,
            pid = project.id,
            input = input(items = listOf(ClipActionTool.VolumeItem("c1", 0.3f))),
            ctx = ctx,
        )
        val after = store.get(project.id)!!
        val c1After = after.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c1" } as Clip.Audio
        assertEquals(0.3f, c1After.volume)
    }

    @Test fun otherClipsOnTrackUntouched() = runTest {
        // Pin: only items-named clips change. Sibling clips on
        // the same track keep their original volume.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithAudioTrack(
            store,
            listOf(audioClip("c1", volume = 1.0f), audioClip("c2", volume = 1.0f)),
        )
        executeClipSetVolume(
            store = store,
            pid = project.id,
            input = input(items = listOf(ClipActionTool.VolumeItem("c2", 0.25f))),
            ctx = ctx,
        )
        val after = store.get(project.id)!!
        val byId = after.timeline.tracks.flatMap { it.clips }.associateBy { it.id.value }
        assertEquals(1.0f, (byId["c1"] as Clip.Audio).volume, "untouched clip preserved")
        assertEquals(0.25f, (byId["c2"] as Clip.Audio).volume, "named clip updated")
    }
}
