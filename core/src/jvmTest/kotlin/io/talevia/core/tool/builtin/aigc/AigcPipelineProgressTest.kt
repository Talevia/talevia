package io.talevia.core.tool.builtin.aigc

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
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
        val ctx = ToolContext(
            sessionId = SessionId("s-progress"),
            messageId = MessageId("m-progress"),
            callId = CallId("c-progress"),
            askPermission = { PermissionDecision.Once },
            emitPart = { p -> parts += p },
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
}
