package io.talevia.core.tool.builtin.aigc

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [AigcPipeline.withProgress] must emit a `RenderProgress` Part with `ratio=0`
 * before the wrapped block runs, then follow up with `ratio=1 "completed"` on
 * success or `ratio=0 "failed: ..."` on exception. Same `partId` across all
 * emits so the UI treats it as a single logical progress row.
 *
 * Coverage:
 *  - happy path: started → completed, same partId, result returned
 *  - failure path: started → failed, exception rethrown untouched
 *  - ordering: start emit happens BEFORE block starts executing (so UI flips
 *    to "generating…" before any provider latency)
 */
class AigcPipelineProgressTest {

    private class CapturedCtx {
        val parts = mutableListOf<Part>()
        val events = mutableListOf<BusEvent>()
        val ctx = ToolContext(
            sessionId = SessionId("s-progress"),
            messageId = MessageId("m-progress"),
            callId = CallId("c-progress"),
            askPermission = { PermissionDecision.Once },
            emitPart = { p -> parts += p },
            publishEvent = { e -> events += e },
            messages = emptyList(),
        )
    }

    @Test
    fun successEmitsStartedThenCompletedWithSamePartId() = runTest {
        val cap = CapturedCtx()
        val result = AigcPipeline.withProgress(
            ctx = cap.ctx,
            jobId = "job-ok",
            startMessage = "doing thing",
        ) {
            "payload"
        }

        assertEquals("payload", result)
        assertEquals(2, cap.parts.size, "expected started + completed, got: ${cap.parts}")

        val started = cap.parts[0] as Part.RenderProgress
        val completed = cap.parts[1] as Part.RenderProgress

        assertEquals("job-ok", started.jobId)
        assertEquals(0f, started.ratio)
        assertEquals("doing thing", started.message)

        assertEquals("job-ok", completed.jobId)
        assertEquals(1f, completed.ratio)
        assertEquals("completed", completed.message)

        assertEquals(started.id, completed.id, "partId must be reused so the UI collapses to a single progress row")
    }

    @Test
    fun failureEmitsStartedThenFailedAndRethrows() = runTest {
        val cap = CapturedCtx()
        val boom = IllegalStateException("provider exploded")

        val thrown = assertFailsWith<IllegalStateException> {
            AigcPipeline.withProgress(
                ctx = cap.ctx,
                jobId = "job-err",
                startMessage = "about to blow",
            ) {
                throw boom
            }
        }
        assertEquals("provider exploded", thrown.message)

        assertEquals(2, cap.parts.size)
        val started = cap.parts[0] as Part.RenderProgress
        val failed = cap.parts[1] as Part.RenderProgress

        assertEquals(0f, started.ratio)
        assertEquals(0f, failed.ratio)
        val failMsg = failed.message ?: error("failed emit must carry a non-null message")
        assertTrue(
            failMsg.startsWith("failed:"),
            "failure message must be prefixed with 'failed:' (got: $failMsg)",
        )
        assertTrue("provider exploded" in failMsg)
        assertEquals(started.id, failed.id)
    }

    @Test
    fun startedEmitsBeforeBlockRuns() = runTest {
        // Critical invariant: if the block suspends or blocks, UI must already
        // have seen "started" — otherwise users watch a silent pause.
        val cap = CapturedCtx()
        var blockStartedAfter = -1
        AigcPipeline.withProgress(
            ctx = cap.ctx,
            jobId = "job-order",
            startMessage = "…",
        ) {
            blockStartedAfter = cap.parts.size
            "ok"
        }
        assertEquals(1, blockStartedAfter, "block must see exactly 1 emitted part (the started marker) before running")
        assertNotNull(cap.parts.firstOrNull() as? Part.RenderProgress)
    }

    @Test
    fun successPublishesStartedThenCompletedBusEvents() = runTest {
        // The new BusEvent.AigcJobProgress channel runs alongside the
        // session-history Part.RenderProgress so non-Part subscribers
        // (metrics, desktop, server SSE) can see the same lifecycle without
        // parsing PartUpdated. Pair fires Started + Completed on success.
        val cap = CapturedCtx()
        AigcPipeline.withProgress(
            ctx = cap.ctx,
            jobId = "job-bus-ok",
            startMessage = "doing thing",
            toolId = "generate_image",
            providerId = "openai",
        ) {
            "payload"
        }

        val progress = cap.events.filterIsInstance<BusEvent.AigcJobProgress>()
        assertEquals(2, progress.size, "expected Started + Completed; got: $progress")
        assertEquals(BusEvent.AigcProgressPhase.Started, progress[0].phase)
        assertEquals(BusEvent.AigcProgressPhase.Completed, progress[1].phase)
        assertTrue(progress.all { it.jobId == "job-bus-ok" })
        assertTrue(progress.all { it.toolId == "generate_image" })
        assertTrue(progress.all { it.providerId == "openai" })
        assertTrue(progress.all { it.callId.value == "c-progress" })
        assertTrue(progress.all { it.sessionId.value == "s-progress" })
        assertEquals(0f, progress[0].ratio)
        assertEquals(1f, progress[1].ratio)
        assertEquals("doing thing", progress[0].message)
        assertEquals("completed", progress[1].message)

        // Part.RenderProgress + BusEvent.AigcJobProgress fire as a pair —
        // 2 of each.
        assertEquals(2, cap.parts.size, "Part.RenderProgress count unchanged")
    }

    @Test
    fun failurePublishesStartedThenFailedBusEvents() = runTest {
        // Failure path mirrors the Part shape: Started + Failed (no
        // Completed), with the failure message verbatim from the
        // RenderProgress part and the original exception rethrown.
        val cap = CapturedCtx()
        val boom = IllegalStateException("provider exploded")

        assertFailsWith<IllegalStateException> {
            AigcPipeline.withProgress(
                ctx = cap.ctx,
                jobId = "job-bus-err",
                startMessage = "about to blow",
                toolId = "generate_video",
            ) {
                throw boom
            }
        }

        val progress = cap.events.filterIsInstance<BusEvent.AigcJobProgress>()
        assertEquals(2, progress.size, "expected Started + Failed; got: $progress")
        assertEquals(BusEvent.AigcProgressPhase.Started, progress[0].phase)
        assertEquals(BusEvent.AigcProgressPhase.Failed, progress[1].phase)
        assertEquals(0f, progress[1].ratio)
        val failMsg = progress[1].message ?: error("Failed phase must carry a message")
        assertTrue(failMsg.startsWith("failed:"))
        assertTrue("provider exploded" in failMsg)
        // toolId echoes the explicit override; jobId uses the base name
        // not the substring fallback because we passed toolId.
        assertTrue(progress.all { it.toolId == "generate_video" })
    }

    @Test
    fun toolIdDefaultsToJobIdPrefixWhenOmitted() = runTest {
        // Backward-compat: existing AIGC tools call withProgress without a
        // toolId argument, so the dispatcher derives one from the jobId
        // prefix (e.g. `gen-image-abc123` → `gen`). Real tools should pass
        // an explicit toolId; this fallback exists so the metrics counter
        // doesn't go nameless when callers haven't migrated yet.
        val cap = CapturedCtx()
        AigcPipeline.withProgress(
            ctx = cap.ctx,
            jobId = "gen-image-abc123",
            startMessage = "x",
        ) {
            "ok"
        }
        val first = cap.events.filterIsInstance<BusEvent.AigcJobProgress>().first()
        assertEquals("gen", first.toolId, "fallback splits jobId on first '-'")
    }
}
