package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStoreTestKit
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
 * Direct tests for [executeClipFade] —
 * `core/tool/builtin/video/ClipAudioMutateHandlers.kt`.
 * The `clip_action(action="fade")` audio-only handler.
 * Cycle 203 audit: 104 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **At least one of `fadeInSeconds` / `fadeOutSeconds`
 *    required per item.** Drift to "both null silently
 *    skipped" would no-op fade requests the agent
 *    explicitly tried to issue.
 *
 * 2. **Audio-only with type discrimination.** Per impl
 *    `clip as? Clip.Audio ?: error(...)`. Drift to
 *    "video clips also accepted" would silently no-op or
 *    crash on attempting to set fade on Clip.Video which
 *    has no fade fields.
 *
 * 3. **Fade overlap rejection: `newIn + newOut <=
 *    clipDuration + 1e-3` tolerance.** Drift in tolerance
 *    would either surface as a "fades sum to exactly
 *    duration but rejected" UX issue (epsilon too tight)
 *    OR over-permissive overlap (epsilon too wide).
 *    Marquee invariant: fades must not overlap.
 */
class ClipAudioMutateHandlersTest {

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

    private fun audioClip(
        id: String,
        durationSec: Long = 10,
        fadeIn: Float = 0f,
        fadeOut: Float = 0f,
    ): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = anyRange(durationSec),
        sourceRange = anyRange(durationSec),
        assetId = AssetId("asset-$id"),
        fadeInSeconds = fadeIn,
        fadeOutSeconds = fadeOut,
    )

    private fun videoClip(id: String): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId("v-$id"),
    )

    private suspend fun newProjectWithClips(
        store: io.talevia.core.domain.FileProjectStore,
        clips: List<Clip>,
    ): io.talevia.core.domain.Project {
        val created = store.createAt(
            path = "/projects/p1".toPath(),
            title = "Test",
        )
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

    private fun input(items: List<ClipActionTool.FadeItem>?): ClipActionTool.Input =
        ClipActionTool.Input(action = "fade", fadeItems = items)

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingFadeItemsThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(store, emptyList())
        val ex = assertFailsWith<IllegalStateException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = null),
                ctx = ctx,
            )
        }
        assertTrue("requires `fadeItems`" in (ex.message ?: ""))
    }

    @Test fun emptyFadeItemsThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(store, emptyList())
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = emptyList()),
                ctx = ctx,
            )
        }
        assertTrue("must not be empty" in (ex.message ?: ""))
    }

    @Test fun bothNullFadeFieldsRejected() = runTest {
        // Marquee at-least-one pin: per impl `require(item.fadeInSeconds != null || item.fadeOutSeconds != null)`.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(store, listOf(audioClip("c1")))
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(
                    items = listOf(
                        ClipActionTool.FadeItem(
                            clipId = "c1",
                            fadeInSeconds = null,
                            fadeOutSeconds = null,
                        ),
                    ),
                ),
                ctx = ctx,
            )
        }
        assertTrue(
            "at least one of fadeInSeconds / fadeOutSeconds required" in (ex.message ?: ""),
            "expected at-least-one phrase; got: ${ex.message}",
        )
    }

    // ── Numeric validation ──────────────────────────────────

    @Test fun negativeFadeInRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(store, listOf(audioClip("c1")))
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = listOf(
                    ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = -1f),
                )),
                ctx = ctx,
            )
        }
        assertTrue("must be finite and >= 0" in (ex.message ?: ""))
    }

    @Test fun negativeFadeOutRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(store, listOf(audioClip("c1")))
        assertFailsWith<IllegalArgumentException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = listOf(
                    ClipActionTool.FadeItem(clipId = "c1", fadeOutSeconds = -0.5f),
                )),
                ctx = ctx,
            )
        }
    }

    @Test fun nonFiniteFadeInRejected() = runTest {
        // Pin: NaN and Infinity rejected via `isFinite()`.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(store, listOf(audioClip("c1")))
        assertFailsWith<IllegalArgumentException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = listOf(
                    ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = Float.NaN),
                )),
                ctx = ctx,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = listOf(
                    ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = Float.POSITIVE_INFINITY),
                )),
                ctx = ctx,
            )
        }
    }

    // ── Audio-only ──────────────────────────────────────────

    @Test fun videoClipRejectedAsNonAudio() = runTest {
        // Marquee audio-only pin: video clips can't carry
        // fade fields. Drift would silently mis-route or
        // crash.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "Test")
        val project = store.mutate(created.id) { p ->
            p.copy(
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
                    ),
                ),
            )
        }

        val ex = assertFailsWith<IllegalStateException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = listOf(
                    ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 1f),
                )),
                ctx = ctx,
            )
        }
        assertTrue(
            "only applies to audio clips" in (ex.message ?: ""),
            "audio-only phrase; got: ${ex.message}",
        )
        assertTrue(
            "Video" in (ex.message ?: ""),
            "actual clip type cited; got: ${ex.message}",
        )
    }

    @Test fun missingClipThrowsWithItemIndex() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(store, listOf(audioClip("c1")))
        val ex = assertFailsWith<IllegalStateException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = listOf(
                    ClipActionTool.FadeItem(clipId = "ghost", fadeInSeconds = 1f),
                )),
                ctx = ctx,
            )
        }
        assertTrue("ghost not found" in (ex.message ?: ""))
        assertTrue("[0]" in (ex.message ?: ""))
    }

    // ── Fade overlap rejection ──────────────────────────────

    @Test fun fadesSumExceedingDurationRejected() = runTest {
        // Marquee overlap-rejection pin: 5s clip + 3s
        // fadeIn + 3s fadeOut → sum 6 > 5 → reject.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(
            store,
            listOf(audioClip("c1", durationSec = 5)),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = listOf(
                    ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 3f, fadeOutSeconds = 3f),
                )),
                ctx = ctx,
            )
        }
        assertTrue(
            "would exceed clip duration" in (ex.message ?: ""),
            "expected overlap phrase; got: ${ex.message}",
        )
        assertTrue(
            "fades would overlap" in (ex.message ?: ""),
            "documented hint cited; got: ${ex.message}",
        )
    }

    @Test fun fadesSumExactlyDurationAcceptedWithinTolerance() = runTest {
        // Pin: per impl `newIn + newOut <= clipDuration +
        // 1e-3f`. Sum exactly == duration must NOT reject
        // (within float tolerance).
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(
            store,
            listOf(audioClip("c1", durationSec = 10)),
        )
        // Should not throw.
        executeClipFade(
            store = store,
            pid = project.id,
            input = input(items = listOf(
                ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 5f, fadeOutSeconds = 5f),
            )),
            ctx = ctx,
        )
        // Fades persisted.
        val updated = store.get(project.id)!!
        val clip = updated.timeline.tracks.flatMap { it.clips }.first { it.id.value == "c1" } as Clip.Audio
        assertEquals(5f, clip.fadeInSeconds)
        assertEquals(5f, clip.fadeOutSeconds)
    }

    @Test fun overlapCheckUsesNewValuesNotOld() = runTest {
        // Pin: when partial-update keeps an old value, the
        // overlap check uses the (newIn, newOut) pair
        // including the kept old. e.g. clip with old
        // fadeOut=4, request fadeIn=4 (keeping fadeOut=4)
        // → 4+4=8 ok on 10s clip.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(
            store,
            listOf(audioClip("c1", durationSec = 10, fadeOut = 4f)),
        )
        // Setting fadeIn=4 (fadeOut stays at 4) → 8 < 10 → ok.
        executeClipFade(
            store = store,
            pid = project.id,
            input = input(items = listOf(
                ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 4f),
            )),
            ctx = ctx,
        )
        val updated = store.get(project.id)!!
        val clip = updated.timeline.tracks.flatMap { it.clips }.first { it.id.value == "c1" } as Clip.Audio
        assertEquals(4f, clip.fadeInSeconds, "new fadeIn applied")
        assertEquals(4f, clip.fadeOutSeconds, "old fadeOut preserved")

        // Now setting fadeIn=7 (fadeOut still 4) → 11 > 10 → rejected.
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = listOf(
                    ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 7f),
                )),
                ctx = ctx,
            )
        }
        assertTrue("would exceed clip duration" in (ex.message ?: ""))
    }

    // ── Partial update semantics ────────────────────────────

    @Test fun fadeInOnlyKeepsOldFadeOut() = runTest {
        // Pin: per `newIn = item.fadeInSeconds ?: oldIn`
        // and `newOut = item.fadeOutSeconds ?: oldOut`.
        // Setting fadeInSeconds with fadeOutSeconds=null
        // keeps old fadeOut.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(
            store,
            listOf(audioClip("c1", fadeIn = 1f, fadeOut = 2f)),
        )

        val result = executeClipFade(
            store = store,
            pid = project.id,
            input = input(items = listOf(
                ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 3f),
            )),
            ctx = ctx,
        )

        val updated = store.get(project.id)!!
        val clip = updated.timeline.tracks.flatMap { it.clips }.first { it.id.value == "c1" } as Clip.Audio
        assertEquals(3f, clip.fadeInSeconds)
        assertEquals(2f, clip.fadeOutSeconds, "old fadeOut preserved")
        // Result reports both old AND new for both fields.
        val faded = result.data.faded.single()
        assertEquals(1f, faded.oldFadeInSeconds)
        assertEquals(3f, faded.newFadeInSeconds)
        assertEquals(2f, faded.oldFadeOutSeconds)
        assertEquals(2f, faded.newFadeOutSeconds, "newFadeOut == oldFadeOut on partial update")
    }

    @Test fun fadeOutOnlyKeepsOldFadeIn() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(
            store,
            listOf(audioClip("c1", fadeIn = 1f, fadeOut = 2f)),
        )

        executeClipFade(
            store = store,
            pid = project.id,
            input = input(items = listOf(
                ClipActionTool.FadeItem(clipId = "c1", fadeOutSeconds = 4f),
            )),
            ctx = ctx,
        )

        val updated = store.get(project.id)!!
        val clip = updated.timeline.tracks.flatMap { it.clips }.first { it.id.value == "c1" } as Clip.Audio
        assertEquals(1f, clip.fadeInSeconds, "old fadeIn preserved")
        assertEquals(4f, clip.fadeOutSeconds)
    }

    // ── Multi-item batch ────────────────────────────────────

    @Test fun multipleItemsAllProcessedAtomically() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(
            store,
            listOf(audioClip("c1"), audioClip("c2")),
        )

        val result = executeClipFade(
            store = store,
            pid = project.id,
            input = input(items = listOf(
                ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 1f),
                ClipActionTool.FadeItem(clipId = "c2", fadeOutSeconds = 2f),
            )),
            ctx = ctx,
        )
        assertEquals(2, result.data.faded.size)
        assertTrue("2 audio clip(s)" in result.outputForLlm)
    }

    @Test fun midBatchFailureAtomicRollback() = runTest {
        // Pin: store.mutate{} lambda failure rolls back
        // the whole batch. Drift to "partial commit" would
        // leak partial state.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(
            store,
            listOf(
                audioClip("c1", fadeIn = 0f),
                audioClip("c2", durationSec = 5),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            executeClipFade(
                store = store,
                pid = project.id,
                input = input(items = listOf(
                    // c1 succeeds (fadeIn=2 on 10s clip).
                    ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 2f),
                    // c2 fails (fadeIn=4 + fadeOut=4 on 5s clip → reject).
                    ClipActionTool.FadeItem(clipId = "c2", fadeInSeconds = 4f, fadeOutSeconds = 4f),
                )),
                ctx = ctx,
            )
        }

        // After failure, c1 NOT mutated.
        val updated = store.get(project.id)!!
        val c1 = updated.timeline.tracks.flatMap { it.clips }.first { it.id.value == "c1" } as Clip.Audio
        assertEquals(0f, c1.fadeInSeconds, "c1 NOT mutated after batch failure (atomic rollback)")
    }

    // ── Output shape ─────────────────────────────────────────

    @Test fun outputCarriesProjectIdActionAndSnapshotId() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(store, listOf(audioClip("c1")))

        val result = executeClipFade(
            store = store,
            pid = project.id,
            input = input(items = listOf(
                ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 1f),
            )),
            ctx = ctx,
        )
        assertEquals(project.id.value, result.data.projectId)
        assertEquals("fade", result.data.action)
        assertTrue(result.data.snapshotId.isNotBlank())
    }

    @Test fun toolResultTitleCitesFadeCount() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(
            store,
            listOf(audioClip("c1"), audioClip("c2")),
        )

        val result = executeClipFade(
            store = store,
            pid = project.id,
            input = input(items = listOf(
                ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 1f),
                ClipActionTool.FadeItem(clipId = "c2", fadeInSeconds = 1f),
            )),
            ctx = ctx,
        )
        assertTrue("fade × 2" in result.title!!)
    }

    @Test fun fadeResultExposesAllFields() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithClips(
            store,
            listOf(audioClip("c1", fadeIn = 1f, fadeOut = 2f)),
        )

        val result = executeClipFade(
            store = store,
            pid = project.id,
            input = input(items = listOf(
                ClipActionTool.FadeItem(clipId = "c1", fadeInSeconds = 3f, fadeOutSeconds = 4f),
            )),
            ctx = ctx,
        )

        val faded = result.data.faded.single()
        assertEquals("c1", faded.clipId)
        assertEquals("a", faded.trackId)
        assertEquals(1f, faded.oldFadeInSeconds)
        assertEquals(3f, faded.newFadeInSeconds)
        assertEquals(2f, faded.oldFadeOutSeconds)
        assertEquals(4f, faded.newFadeOutSeconds)
    }
}
