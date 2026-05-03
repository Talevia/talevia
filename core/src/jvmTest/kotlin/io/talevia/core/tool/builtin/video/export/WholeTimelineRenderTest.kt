package io.talevia.core.tool.builtin.video.export

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Direct tests for [runWholeTimelineRender] —
 * `core/tool/builtin/video/export/WholeTimelineRender.kt`. Whole-timeline
 * export path that translates [RenderProgress] events from a
 * [VideoEngine.render] flow into [Part.RenderProgress] parts emitted via
 * [ToolContext.emitPart], and throws after collect if any [RenderProgress.Failed]
 * was seen. Cycle 207 audit: 61 LOC, 0 direct test refs.
 *
 * Six correctness contracts pinned:
 *
 *  1. **Per-event mapping is exhaustive over the [RenderProgress] sealed
 *     family.** `Started` → ratio=0f / "started", `Frames` →
 *     ratio + message passthrough, `Preview` → "preview" + thumbnailPath,
 *     `Completed` → ratio=1f / "completed", `Failed` →
 *     ratio=0f / "failed: <msg>". Drift would silently drop a variant or
 *     mis-set the ratio (UI progress bars stop working).
 *
 *  2. **`Failed` is fatal — but only AFTER collect completes.** The Flow
 *     keeps pumping post-Failed (a Preview / Completed event still emits a
 *     part); `error("export failed: ...")` throws once the whole flow has
 *     been consumed. Drift to "throw on first Failed" would prevent
 *     downstream Completed cleanup events from reaching the part stream.
 *
 *  3. **Failure message is last-write-wins.** Two `Failed` events in the
 *     same flow → the error message cites the LAST one's message (per
 *     `failure = ev.message` overwrite). The first Failed's part still
 *     emitted; the SECOND's message is what the agent sees in the error.
 *
 *  4. **Empty flow + all-success flow are non-throwing.** No `Failed`
 *     event → no error. The success path is pure emit, even if zero
 *     events arrived.
 *
 *  5. **Per-part identity comes from `ctx`.** Every emitted part copies
 *     `ctx.messageId` / `ctx.sessionId` and stamps `clock.now()` for
 *     `createdAt`. Drift to "drop one of these" would break the part
 *     stream's join with the originating turn.
 *
 *  6. **Each part has a fresh PartId** (uuidv4 per event, NOT one shared
 *     id reused). Drift to a single shared id would collapse the parts
 *     into one row in any per-id index downstream.
 *
 * Plus passthrough pins: timeline / output / resolver flow into the engine
 * verbatim — the function is a pure adapter and adds nothing to those.
 */
class WholeTimelineRenderTest {

    /**
     * Capturing engine — record arguments, return the canned event flow. Each
     * test instance configures `events` upfront via the constructor.
     */
    private class CapturingEngine(
        private val events: Flow<RenderProgress>,
    ) : VideoEngine {
        var lastTimeline: Timeline? = null
            private set
        var lastOutput: OutputSpec? = null
            private set
        var lastResolver: MediaPathResolver? = null
            private set
        var renderCallCount: Int = 0
            private set

        override suspend fun probe(source: MediaSource): MediaMetadata =
            error("probe must not be called by runWholeTimelineRender")

        override fun render(
            timeline: Timeline,
            output: OutputSpec,
            resolver: MediaPathResolver?,
        ): Flow<RenderProgress> {
            lastTimeline = timeline
            lastOutput = output
            lastResolver = resolver
            renderCallCount++
            return events
        }

        override suspend fun thumbnail(
            asset: AssetId,
            source: MediaSource,
            time: Duration,
        ): ByteArray = error("thumbnail must not be called")
    }

    /**
     * Fixed-instant clock so we can pin part `createdAt` deterministically.
     */
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private val sessionId = SessionId("s")
    private val messageId = MessageId("m")
    private val callId = CallId("c")
    private val frozenInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val clock = FixedClock(frozenInstant)

    private fun ctxWith(emitted: MutableList<Part>): ToolContext = ToolContext(
        sessionId = sessionId,
        messageId = messageId,
        callId = callId,
        askPermission = { PermissionDecision.Once },
        emitPart = { emitted += it },
        messages = emptyList(),
    )

    private val timeline = Timeline()
    private val output = OutputSpec(
        targetPath = "/tmp/out.mp4",
        resolution = Resolution(1920, 1080),
    )

    private fun progressParts(parts: List<Part>): List<Part.RenderProgress> =
        parts.filterIsInstance<Part.RenderProgress>()

    // ── 1. Per-event mapping ─────────────────────────────────

    @Test fun startedEventMapsToRatioZeroWithStartedMessage() = runTest {
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(flowOf(RenderProgress.Started(jobId = "j1")))
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(1, emitted.size)
        assertEquals("j1", emitted[0].jobId)
        assertEquals(0f, emitted[0].ratio)
        assertEquals("started", emitted[0].message)
        assertNull(emitted[0].thumbnailPath)
    }

    @Test fun framesEventPassesThroughRatioAndMessage() = runTest {
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(RenderProgress.Frames(jobId = "j1", ratio = 0.42f, message = "encoding")),
        )
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(1, emitted.size)
        assertEquals(0.42f, emitted[0].ratio)
        assertEquals("encoding", emitted[0].message)
        assertNull(emitted[0].thumbnailPath)
    }

    @Test fun framesEventWithNullMessagePassesThroughAsNull() = runTest {
        // Pin: `Frames.message` is nullable; the part's message should
        // mirror that — NOT default to a placeholder string.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(RenderProgress.Frames(jobId = "j1", ratio = 0.5f, message = null)),
        )
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(1, emitted.size)
        assertNull(emitted[0].message, "null Frames.message should pass through as null")
    }

    @Test fun previewEventMapsToPreviewMessageAndCarriesThumbnailPath() = runTest {
        // Marquee preview pin: thumbnailPath MUST land on the part —
        // VISION §5.4 expert-path mid-render inspection depends on it.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(
                RenderProgress.Preview(
                    jobId = "j1",
                    ratio = 0.75f,
                    thumbnailPath = "/tmp/thumb.jpg",
                ),
            ),
        )
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(1, emitted.size)
        assertEquals(0.75f, emitted[0].ratio)
        assertEquals("preview", emitted[0].message)
        assertEquals(
            "/tmp/thumb.jpg",
            emitted[0].thumbnailPath,
            "thumbnailPath must propagate (mid-render expert inspection per VISION §5.4)",
        )
    }

    @Test fun completedEventMapsToRatioOneWithCompletedMessage() = runTest {
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(RenderProgress.Completed(jobId = "j1", outputPath = "/tmp/out.mp4")),
        )
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(1, emitted.size)
        assertEquals(1f, emitted[0].ratio)
        assertEquals("completed", emitted[0].message)
    }

    @Test fun failedEventMapsToRatioZeroWithFailedMessagePrefix() = runTest {
        // Pin: failed message format = "failed: <ev.message>" (NOT just
        // ev.message). Drift would lose the prefix that lets UI filter
        // failure events by message.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(RenderProgress.Failed(jobId = "j1", message = "ffmpeg crashed")),
        )
        assertFailsWith<IllegalStateException> {
            runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        }
        val emitted = progressParts(parts)
        assertEquals(1, emitted.size)
        assertEquals(0f, emitted[0].ratio)
        assertEquals("failed: ffmpeg crashed", emitted[0].message)
    }

    // ── 2. Failed throws AFTER collect (latched) ─────────────

    @Test fun failedEventStillProcessesSubsequentEventsBeforeThrowing() = runTest {
        // Marquee latched-failure pin: `failure = ev.message` is set, but
        // the loop keeps processing. Subsequent events still emit parts;
        // the error throws AFTER the whole flow is collected. Drift to
        // "throw on first Failed" would prevent downstream cleanup events
        // (Completed) from reaching consumers.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(
                RenderProgress.Started(jobId = "j1"),
                RenderProgress.Failed(jobId = "j1", message = "boom"),
                RenderProgress.Completed(jobId = "j1", outputPath = "/tmp/out.mp4"),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        }
        // All 3 events emitted parts before the throw.
        val emitted = progressParts(parts)
        assertEquals(3, emitted.size)
        assertEquals("started", emitted[0].message)
        assertEquals("failed: boom", emitted[1].message)
        assertEquals("completed", emitted[2].message)
        assertTrue("export failed: boom" in (ex.message ?: ""), "error cites the latched failure")
    }

    @Test fun failureThrowsExportFailedPrefix() = runTest {
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(flowOf(RenderProgress.Failed(jobId = "j1", message = "no-codec")))
        val ex = assertFailsWith<IllegalStateException> {
            runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        }
        assertEquals("export failed: no-codec", ex.message)
    }

    // ── 3. Last-write-wins on multi-Failed ───────────────────

    @Test fun multipleFailedEventsLastMessageWinsInError() = runTest {
        // Pin: per impl `failure = ev.message` overwrites on each Failed.
        // The final error cites the LAST Failed's message; the FIRST
        // Failed's part is still emitted (before the overwrite). This
        // pins the documented assignment-not-coalesce semantics.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(
                RenderProgress.Failed(jobId = "j1", message = "first-failure"),
                RenderProgress.Failed(jobId = "j1", message = "second-failure"),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        }
        // Both parts emitted — both with their own message.
        val emitted = progressParts(parts)
        assertEquals(2, emitted.size)
        assertEquals("failed: first-failure", emitted[0].message)
        assertEquals("failed: second-failure", emitted[1].message)
        // Error: last failure wins.
        assertEquals("export failed: second-failure", ex.message)
    }

    // ── 4. Non-throwing paths ────────────────────────────────

    @Test fun emptyFlowProducesZeroPartsAndDoesNotThrow() = runTest {
        // Pin: zero events → zero parts → no error. Engine that returns
        // an empty flow shouldn't break the whole-timeline path.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(flowOf())
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        assertEquals(0, progressParts(parts).size)
        assertEquals(1, engine.renderCallCount)
    }

    @Test fun allSuccessFlowDoesNotThrow() = runTest {
        // Pin: Started → Frames → Frames → Completed without any Failed
        // → no error. The success path is pure emit.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(
                RenderProgress.Started(jobId = "j1"),
                RenderProgress.Frames(jobId = "j1", ratio = 0.25f),
                RenderProgress.Frames(jobId = "j1", ratio = 0.75f),
                RenderProgress.Completed(jobId = "j1", outputPath = "/tmp/out.mp4"),
            ),
        )
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(4, emitted.size)
        assertEquals(listOf(0f, 0.25f, 0.75f, 1f), emitted.map { it.ratio })
    }

    // ── 5. Per-part identity ─────────────────────────────────

    @Test fun emittedPartsCarryCtxMessageIdAndSessionId() = runTest {
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(
                RenderProgress.Started(jobId = "j1"),
                RenderProgress.Completed(jobId = "j1", outputPath = "/tmp/out.mp4"),
            ),
        )
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(2, emitted.size)
        for (p in emitted) {
            assertEquals(messageId, p.messageId, "part.messageId == ctx.messageId")
            assertEquals(sessionId, p.sessionId, "part.sessionId == ctx.sessionId")
        }
    }

    @Test fun emittedPartsStampClockNowAsCreatedAt() = runTest {
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(flowOf(RenderProgress.Started(jobId = "j1")))
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(1, emitted.size)
        assertEquals(
            frozenInstant,
            emitted[0].createdAt,
            "createdAt is stamped from clock.now() (NOT recovered from the engine event)",
        )
    }

    // ── 6. Unique PartId per event ───────────────────────────

    @Test fun eachEventGetsAFreshPartId() = runTest {
        // Marquee unique-id pin: per impl `PartId(Uuid.random().toString())`
        // is created INSIDE the loop, so two events in the same flow
        // produce two distinct ids. Drift to "shared partId across events"
        // would collapse all events into one row in any per-id downstream
        // index.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(
                RenderProgress.Started(jobId = "j1"),
                RenderProgress.Frames(jobId = "j1", ratio = 0.5f),
                RenderProgress.Completed(jobId = "j1", outputPath = "/tmp/out.mp4"),
            ),
        )
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(3, emitted.size)
        val ids = emitted.map { it.id }.toSet()
        assertEquals(3, ids.size, "each event must get a fresh PartId; drift would collapse rows")
    }

    // ── Passthrough pins ────────────────────────────────────

    @Test fun timelineAndOutputAndResolverFlowToEngineVerbatim() = runTest {
        // Pin: function is a pure adapter. timeline / output / resolver
        // pass through to engine.render(...) without rewrite. Drift
        // (e.g. accidental `output.copy(metadata = emptyMap())`) would
        // silently drop the provenance hint on the way out.
        val customResolver = MediaPathResolver { _ -> "x" }
        val customOutput = output.copy(metadata = mapOf("comment" to "trace"))
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(flowOf())
        runWholeTimelineRender(engine, timeline, customOutput, ctxWith(parts), clock, customResolver)
        assertEquals(1, engine.renderCallCount)
        assertEquals(timeline, engine.lastTimeline)
        assertEquals(customOutput, engine.lastOutput)
        assertEquals(customResolver, engine.lastResolver)
    }

    @Test fun nullResolverIsAcceptableAndPassesThroughAsNull() = runTest {
        // Pin: the `resolver` parameter is optional with default null; the
        // function passes that null through unchanged so the engine can
        // fall back to its own carried resolver. Drift to "construct a
        // sentinel default resolver" would break test rigs that rely on
        // engine-internal resolution.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(flowOf())
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock /* resolver = null default */)
        assertEquals(1, engine.renderCallCount)
        assertNull(engine.lastResolver, "default null resolver passes through unchanged")
    }

    @Test fun engineRenderInvokedOncePerCall() = runTest {
        // Pin: function calls engine.render(...) exactly once. Drift to
        // "retry on first event" or "double-call to materialise the flow
        // twice" would either re-run an expensive ffmpeg child process
        // or duplicate the part stream.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(flowOf(RenderProgress.Started(jobId = "j1")))
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        assertEquals(1, engine.renderCallCount, "engine.render must be invoked exactly once")
    }

    @Test fun jobIdPropagatesAcrossAllVariants() = runTest {
        // Pin: every variant carries jobId; the part's jobId field is
        // populated regardless of which variant it was. Drift to "drop
        // jobId on Preview" would prevent the UI from grouping preview
        // ticks under their parent job.
        val parts = mutableListOf<Part>()
        val engine = CapturingEngine(
            flowOf(
                RenderProgress.Started(jobId = "job-A"),
                RenderProgress.Frames(jobId = "job-A", ratio = 0.5f),
                RenderProgress.Preview(jobId = "job-A", ratio = 0.6f, thumbnailPath = "/t.jpg"),
                RenderProgress.Completed(jobId = "job-A", outputPath = "/o.mp4"),
            ),
        )
        runWholeTimelineRender(engine, timeline, output, ctxWith(parts), clock)
        val emitted = progressParts(parts)
        assertEquals(4, emitted.size)
        assertTrue(emitted.all { it.jobId == "job-A" }, "every part's jobId == 'job-A'")
        // Sanity: Preview also got the path.
        assertNotNull(emitted[2].thumbnailPath)
    }
}
