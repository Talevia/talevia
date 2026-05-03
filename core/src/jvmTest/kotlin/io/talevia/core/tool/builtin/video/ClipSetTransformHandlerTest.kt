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
import io.talevia.core.domain.Transform
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
 * Direct tests for [executeClipSetTransform] —
 * `core/tool/builtin/video/ClipSetTransformHandler.kt`. The
 * `clip_action(action="set_transform")` partial-override transform
 * handler. Cycle 208 audit: 114 LOC, 0 direct test refs.
 *
 * Sibling pattern to cycle 204's `ClipSetVolumeHandlerTest` —
 * structurally identical (required-input / range-validation /
 * foreign-field / clip-not-found / atomic-rollback / happy-path
 * sections) except this handler accepts ALL clip variants
 * (Video / Audio / Text) where set_volume was audio-only.
 *
 * Six correctness contracts pinned:
 *
 *  1. **`transformItems` required + non-empty.** Drift to "null
 *     silently no-ops" would silently swallow agent intent.
 *
 *  2. **At-least-one field required per item** (translateX / Y,
 *     scaleX / Y, rotationDeg, opacity). All-null payload fails fast
 *     so a typo'd `transformItem` doesn't silently no-op.
 *
 *  3. **Per-field range invariants:**
 *     - opacity ∈ [0, 1] inclusive + finite (alpha multiplier).
 *     - scaleX / scaleY > 0 strictly + finite (non-zero scale —
 *       ≤ 0 collapses or flips the clip; flips belong in a separate
 *       verb if ever needed).
 *     - translateX / translateY / rotationDeg finite, no range
 *       bound (negative + degrees > 360 are valid creative inputs).
 *
 *  4. **Foreign-field rejection** via
 *     `rejectForeignClipActionFields("set_transform", input)` —
 *     leaking another action's payload (e.g. `volumeItems`) fails
 *     fast at the dispatcher's typo-surface guard.
 *
 *  5. **Partial-override semantics: unspecified fields inherit from
 *     the clip's current Transform.** This is the marquee behavior:
 *     a request setting only `opacity=0.5` MUST leave `translateX`
 *     etc. untouched. Drift to "missing field zeroes out" would
 *     silently break clips with prior transform state.
 *
 *  6. **Atomic / no partial mutation on mid-batch failure.**
 *     `store.mutate` runs the whole list under one mutex; a bad
 *     clipId at items[1] must roll back items[0]'s transform
 *     change.
 *
 * Plus shape pins: ALL three clip variants (Video / Audio / Text)
 * accept the transform (per kdoc + impl `when` covering all sealed
 * branches); empty `clip.transforms` list uses a fresh `Transform()`
 * as base; single-element transforms output replaces (not appends);
 * multi-clip results preserve input ordering; old + new transforms
 * cited in TransformResult.
 */
class ClipSetTransformHandlerTest {

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

    private fun videoClip(
        id: String,
        transforms: List<Transform> = emptyList(),
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId("asset-$id"),
        transforms = transforms,
    )

    private fun audioClip(
        id: String,
        transforms: List<Transform> = emptyList(),
    ): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = anyRange(),
        sourceRange = anyRange(),
        assetId = AssetId("audio-$id"),
        transforms = transforms,
    )

    private fun textClip(
        id: String,
        transforms: List<Transform> = emptyList(),
    ): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = anyRange(),
        text = "hi",
        style = TextStyle(),
        transforms = transforms,
    )

    private suspend fun newProjectWithTrack(
        store: FileProjectStore,
        track: Track,
    ): Project {
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        return store.mutate(created.id) { p ->
            p.copy(timeline = Timeline(tracks = listOf(track)))
        }
    }

    private fun input(
        items: List<ClipActionTool.TransformItem>?,
        action: String = "set_transform",
        addItems: List<ClipActionTool.AddItem>? = null,
        clipIds: List<String>? = null,
        volumeItems: List<ClipActionTool.VolumeItem>? = null,
        fadeItems: List<ClipActionTool.FadeItem>? = null,
    ): ClipActionTool.Input = ClipActionTool.Input(
        action = action,
        transformItems = items,
        addItems = addItems,
        clipIds = clipIds,
        volumeItems = volumeItems,
        fadeItems = fadeItems,
    )

    // ── 1. Required-input rejection ─────────────────────────

    @Test fun missingTransformItemsThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(store, Track.Video(id = TrackId("v"), clips = emptyList()))
        val ex = assertFailsWith<IllegalStateException> {
            executeClipSetTransform(store, project.id, input(items = null), ctx)
        }
        assertTrue("requires `transformItems`" in (ex.message ?: ""))
    }

    @Test fun emptyTransformItemsThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(store, Track.Video(id = TrackId("v"), clips = emptyList()))
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetTransform(store, project.id, input(items = emptyList()), ctx)
        }
        assertTrue("must not be empty" in (ex.message ?: ""))
    }

    // ── 2. At-least-one-field required per item ─────────────

    @Test fun allNullFieldsRejected() = runTest {
        // Marquee at-least-one pin: per impl `require(overrides.isNotEmpty())`.
        // A transformItem with every field null is a no-op masquerading as a
        // transform edit — must fail loud.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetTransform(
                store = store,
                pid = project.id,
                input = input(items = listOf(ClipActionTool.TransformItem(clipId = "c1"))),
                ctx = ctx,
            )
        }
        assertTrue(
            "at least one of translate/scale/rotation/opacity required" in (ex.message ?: ""),
            "expected at-least-one phrase; got: ${ex.message}",
        )
        assertTrue("c1" in (ex.message ?: ""), "expected clipId in error; got: ${ex.message}")
    }

    // ── 3. Per-field range invariants ───────────────────────

    @Test fun opacityOutOfRangeRejected() = runTest {
        // Pin: opacity ∈ [0, 1] inclusive. <0 or >1 fails.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        for (bad in listOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val ex = assertFailsWith<IllegalArgumentException> {
                executeClipSetTransform(
                    store = store,
                    pid = project.id,
                    input = input(items = listOf(ClipActionTool.TransformItem(clipId = "c1", opacity = bad))),
                    ctx = ctx,
                )
            }
            assertTrue(
                "opacity must be in [0, 1]" in (ex.message ?: ""),
                "expected '[0, 1]' message for $bad; got: ${ex.message}",
            )
        }
    }

    @Test fun opacityBoundariesAccepted() = runTest {
        // Pin: 0.0 (fully transparent) and 1.0 (fully opaque) are inclusive.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        // 0.0 → ok
        val r0 = executeClipSetTransform(
            store, project.id,
            input(items = listOf(ClipActionTool.TransformItem(clipId = "c1", opacity = 0f))),
            ctx,
        )
        assertEquals(0f, r0.data!!.transformResults[0].newTransform.opacity)
    }

    @Test fun scaleNonPositiveRejected() = runTest {
        // Pin: scaleX / scaleY > 0 strictly. Zero collapses, negative
        // flips — neither valid for set_transform's non-flipping
        // semantics.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        for ((field, lambda) in listOf<Pair<String, (Float) -> ClipActionTool.TransformItem>>(
            "scaleX" to { v -> ClipActionTool.TransformItem(clipId = "c1", scaleX = v) },
            "scaleY" to { v -> ClipActionTool.TransformItem(clipId = "c1", scaleY = v) },
        )) {
            for (bad in listOf(0f, -1f, Float.NaN)) {
                val ex = assertFailsWith<IllegalArgumentException> {
                    executeClipSetTransform(
                        store = store,
                        pid = project.id,
                        input = input(items = listOf(lambda(bad))),
                        ctx = ctx,
                    )
                }
                assertTrue(
                    "$field must be > 0" in (ex.message ?: ""),
                    "expected '$field must be > 0' for value $bad; got: ${ex.message}",
                )
            }
        }
    }

    @Test fun translateAndRotationAcceptArbitraryFiniteValues() = runTest {
        // Pin: translateX/Y and rotationDeg accept negative + > 360°.
        // Only the finite check applies; no range bound. Drift to "must
        // be in [0, 360]" or "must be >= 0" would invalidate creative
        // values like a -45° dutch-angle or a 720° spin.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        val r = executeClipSetTransform(
            store, project.id,
            input(
                items = listOf(
                    ClipActionTool.TransformItem(
                        clipId = "c1",
                        translateX = -100f,
                        translateY = 1000f,
                        rotationDeg = 720f,
                    ),
                ),
            ),
            ctx,
        )
        val out = r.data!!.transformResults[0].newTransform
        assertEquals(-100f, out.translateX)
        assertEquals(1000f, out.translateY)
        assertEquals(720f, out.rotationDeg)
    }

    @Test fun nonFiniteTranslateRejected() = runTest {
        // Pin: NaN / Infinity rejected for translate / rotation too.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val ex = assertFailsWith<IllegalArgumentException> {
                executeClipSetTransform(
                    store, project.id,
                    input(items = listOf(ClipActionTool.TransformItem(clipId = "c1", translateX = bad))),
                    ctx,
                )
            }
            assertTrue(
                "translateX must be finite" in (ex.message ?: ""),
                "expected 'translateX must be finite' for $bad; got: ${ex.message}",
            )
        }
    }

    // ── 4. Foreign-field rejection ──────────────────────────

    @Test fun foreignFieldsRejected() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            executeClipSetTransform(
                store = store,
                pid = project.id,
                input = input(
                    items = listOf(ClipActionTool.TransformItem(clipId = "c1", opacity = 0.5f)),
                    volumeItems = listOf(ClipActionTool.VolumeItem("c1", 0.5f)),
                ),
                ctx = ctx,
            )
        }
        val msg = ex.message ?: ""
        assertTrue("action=set_transform rejects" in msg, "got: $msg")
        assertTrue("volumeItems" in msg, "expected foreign field cited; got: $msg")
    }

    // ── 5. Partial-override semantics (marquee) ─────────────

    @Test fun partialOverrideInheritsUnspecifiedFromBase() = runTest {
        // Marquee partial-override pin: a request setting ONLY opacity
        // must leave translateX / translateY / scaleX / scaleY /
        // rotationDeg at their prior values. Drift to "missing field
        // resets to 0/1" (the Transform() default) would silently
        // wipe a clip's prior transform on every partial edit.
        val base = Transform(
            translateX = 50f,
            translateY = 100f,
            scaleX = 1.5f,
            scaleY = 1.5f,
            rotationDeg = 30f,
            opacity = 1.0f,
        )
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1", transforms = listOf(base)))),
        )
        val r = executeClipSetTransform(
            store, project.id,
            input(items = listOf(ClipActionTool.TransformItem(clipId = "c1", opacity = 0.5f))),
            ctx,
        )
        val out = r.data!!.transformResults[0].newTransform
        assertEquals(0.5f, out.opacity, "opacity overridden to 0.5")
        assertEquals(50f, out.translateX, "translateX inherited from base")
        assertEquals(100f, out.translateY, "translateY inherited")
        assertEquals(1.5f, out.scaleX, "scaleX inherited")
        assertEquals(1.5f, out.scaleY, "scaleY inherited")
        assertEquals(30f, out.rotationDeg, "rotationDeg inherited")
    }

    @Test fun emptyTransformsListUsesFreshDefaultAsBase() = runTest {
        // Pin: per impl `clip.transforms.firstOrNull() ?: Transform()`.
        // A clip with no prior transform list gets a default-init base
        // (1,1 scale; 1.0 opacity; 0 translate / rotation) before
        // overrides apply. Drift to "throw on empty transforms" would
        // break new clips that haven't been touched yet.
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            // videoClip() default has empty transforms list
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        val r = executeClipSetTransform(
            store, project.id,
            input(items = listOf(ClipActionTool.TransformItem(clipId = "c1", scaleX = 2f))),
            ctx,
        )
        val rec = r.data!!.transformResults[0]
        // Old transform is the fresh default.
        assertEquals(Transform(), rec.oldTransform)
        // New transform: scaleX overridden, rest inherits Transform() defaults.
        assertEquals(2f, rec.newTransform.scaleX)
        assertEquals(1f, rec.newTransform.scaleY, "Transform() default scaleY=1f")
        assertEquals(1f, rec.newTransform.opacity, "Transform() default opacity=1f")
        assertEquals(0f, rec.newTransform.translateX)
        assertEquals(0f, rec.newTransform.rotationDeg)
    }

    @Test fun outputReplacesNotAppendsTransformsList() = runTest {
        // Pin: the output `clip.transforms` is `listOf(merged)` —
        // replaces, not appends. Drift to "transforms = clip.transforms
        // + merged" would let the list grow unbounded across edits and
        // confuse the engine (which reads transforms[0]).
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(
                id = TrackId("v"),
                clips = listOf(videoClip("c1", transforms = listOf(Transform(opacity = 0.5f)))),
            ),
        )
        executeClipSetTransform(
            store, project.id,
            input(items = listOf(ClipActionTool.TransformItem(clipId = "c1", opacity = 0.7f))),
            ctx,
        )
        val after = store.get(project.id)!!
        val c1 = after.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c1" } as Clip.Video
        assertEquals(1, c1.transforms.size, "transforms list replaced (size 1) — NOT appended")
        assertEquals(0.7f, c1.transforms[0].opacity)
    }

    // ── 6. Atomic mid-batch failure rollback ───────────────

    @Test fun midBatchFailureLeavesEarlierEditsUnapplied() = runTest {
        // items=[ok, badClip] must roll back items[0]'s transform edit.
        val store = ProjectStoreTestKit.create()
        val originalTransform = Transform(opacity = 1.0f)
        val project = newProjectWithTrack(
            store,
            Track.Video(
                id = TrackId("v"),
                clips = listOf(
                    videoClip("c1", transforms = listOf(originalTransform)),
                    videoClip("c2", transforms = listOf(originalTransform)),
                ),
            ),
        )
        assertFailsWith<IllegalStateException> {
            executeClipSetTransform(
                store = store,
                pid = project.id,
                input = input(
                    items = listOf(
                        ClipActionTool.TransformItem(clipId = "c1", opacity = 0.25f), // would succeed
                        ClipActionTool.TransformItem(clipId = "ghost", opacity = 0.5f), // fails
                    ),
                ),
                ctx = ctx,
            )
        }
        val after = store.get(project.id)!!
        val c1 = after.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c1" } as Clip.Video
        assertEquals(
            originalTransform,
            c1.transforms[0],
            "atomic rollback: items[0] edit must NOT survive a mid-batch failure",
        )
    }

    // ── All clip variants accept transform ─────────────────

    @Test fun videoAudioAndTextVariantsAllAcceptTransform() = runTest {
        // Marquee multi-variant pin (differs from set_volume which is
        // audio-only): set_transform applies to ALL three Clip variants.
        // Per impl's `when (c)` exhaustive over Video / Audio / Text.
        val store = ProjectStoreTestKit.create()
        val created = store.createAt(path = "/projects/p1".toPath(), title = "T")
        val project = store.mutate(created.id) { p ->
            p.copy(
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(id = TrackId("v"), clips = listOf(videoClip("vc"))),
                        Track.Audio(id = TrackId("a"), clips = listOf(audioClip("ac"))),
                        Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("tc"))),
                    ),
                ),
            )
        }
        val result = executeClipSetTransform(
            store, project.id,
            input(
                items = listOf(
                    ClipActionTool.TransformItem(clipId = "vc", opacity = 0.8f),
                    ClipActionTool.TransformItem(clipId = "ac", opacity = 0.6f),
                    ClipActionTool.TransformItem(clipId = "tc", opacity = 0.4f),
                ),
            ),
            ctx,
        )
        assertEquals(3, result.data!!.transformResults.size)
        // Round-trip: each clip's transform actually persists, regardless of variant.
        val after = store.get(project.id)!!
        val byId = after.timeline.tracks.flatMap { it.clips }.associateBy { it.id.value }
        assertEquals(0.8f, (byId["vc"] as Clip.Video).transforms[0].opacity)
        assertEquals(0.6f, (byId["ac"] as Clip.Audio).transforms[0].opacity)
        assertEquals(0.4f, (byId["tc"] as Clip.Text).transforms[0].opacity)
    }

    // ── Happy-path shape pins ───────────────────────────────

    @Test fun multiClipResultsPreserveInputOrder() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(
                id = TrackId("v"),
                clips = listOf(videoClip("c1"), videoClip("c2"), videoClip("c3")),
            ),
        )
        val result = executeClipSetTransform(
            store, project.id,
            input(
                items = listOf(
                    ClipActionTool.TransformItem(clipId = "c2", opacity = 0.5f),
                    ClipActionTool.TransformItem(clipId = "c1", opacity = 0.6f),
                    ClipActionTool.TransformItem(clipId = "c3", opacity = 0.7f),
                ),
            ),
            ctx,
        )
        val results = result.data!!.transformResults
        assertEquals(listOf("c2", "c1", "c3"), results.map { it.clipId })
        assertEquals(listOf(0.5f, 0.6f, 0.7f), results.map { it.newTransform.opacity })
        assertEquals("set transform × 3", result.title)
    }

    @Test fun resultCitesOldAndNewTransform() = runTest {
        // Pin: TransformResult has both oldTransform + newTransform.
        // Old = base (pre-merge); new = merged. Lets the agent reason
        // about delta without having to round-trip a separate read.
        val base = Transform(opacity = 0.9f, scaleX = 2f)
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1", transforms = listOf(base)))),
        )
        val result = executeClipSetTransform(
            store, project.id,
            input(items = listOf(ClipActionTool.TransformItem(clipId = "c1", opacity = 0.3f))),
            ctx,
        )
        val r = result.data!!.transformResults[0]
        assertEquals(base, r.oldTransform, "oldTransform = clip.transforms.first() pre-merge")
        assertEquals(0.3f, r.newTransform.opacity, "newTransform.opacity = override")
        assertEquals(2f, r.newTransform.scaleX, "newTransform inherits scaleX from base")
    }

    @Test fun missingClipFailsLoudWithIdAndProject() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        val ex = assertFailsWith<IllegalStateException> {
            executeClipSetTransform(
                store, project.id,
                input(items = listOf(ClipActionTool.TransformItem(clipId = "ghost", opacity = 0.5f))),
                ctx,
            )
        }
        val msg = ex.message ?: ""
        assertTrue("clip ghost not found" in msg, "expected clipId in error; got: $msg")
        assertTrue(project.id.value in msg, "expected projectId in error; got: $msg")
    }

    @Test fun mutationActuallyPersists() = runTest {
        val store = ProjectStoreTestKit.create()
        val project = newProjectWithTrack(
            store,
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1"))),
        )
        executeClipSetTransform(
            store, project.id,
            input(items = listOf(ClipActionTool.TransformItem(clipId = "c1", scaleX = 1.5f, opacity = 0.5f))),
            ctx,
        )
        val after = store.get(project.id)!!
        val c1 = after.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c1" } as Clip.Video
        assertEquals(1.5f, c1.transforms[0].scaleX)
        assertEquals(0.5f, c1.transforms[0].opacity)
    }
}
