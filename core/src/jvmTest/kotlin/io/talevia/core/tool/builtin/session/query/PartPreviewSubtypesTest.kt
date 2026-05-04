package io.talevia.core.tool.builtin.session.query

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Part
import io.talevia.core.session.PlanApprovalStatus
import io.talevia.core.session.PlanStep
import io.talevia.core.session.PlanStepStatus
import io.talevia.core.session.TodoInfo
import io.talevia.core.session.TodoStatus
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [Part.preview] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/query/QueryHelpers.kt:51`.
 * Cycle 293 audit: existing
 * `core/src/jvmTest/.../session/query/QueryHelpersTest.kt`
 * covers ONLY 4 of the 11 Part subtypes' preview format
 * (Text, Reasoning, Media, StepStart). The remaining 7
 * (Tool, TimelineSnapshot, RenderProgress, StepFinish,
 * Compaction, Todos, Plan) have NO direct preview-format
 * pins.
 *
 * Same audit-pattern fallback as cycles 207-292.
 *
 * `Part.preview()` is what `session_query(select=parts)`
 * renders to the LLM as the human-readable summary for each
 * part. Drift in the format silently changes how the LLM
 * sees session history → affects compactor decisions, agent
 * context-pressure estimates, and UI display.
 *
 * Drift signals:
 *   - **Tool preview drift** (e.g. swap `${state}` ordering
 *     to `$toolId[$state]` → `$state[$toolId]`) silently
 *     re-orders the LLM-readable summary.
 *   - **TimelineSnapshot drift** (e.g. drop the `baseline`
 *     vs `after <callId>` distinction) silently loses the
 *     "first vs subsequent" snapshot signal.
 *   - **RenderProgress format drift** (e.g. drift `0..100`
 *     percent to `0..1` ratio) silently fails the operator's
 *     "is this render progressing?" check.
 *   - **Todos / Plan count drift** silently misreports
 *     status across `pending` / `in_progress` / `completed`
 *     buckets.
 *
 * Pins via direct construction of each Part subtype + string
 * equality on the preview output.
 */
class PartPreviewSubtypesTest {

    private val sid = SessionId("s1")
    private val mid = MessageId("m1")
    private val pid = PartId("p1")
    private val cid = CallId("c1")
    private val now = Instant.fromEpochMilliseconds(0)

    private fun toolPart(toolId: String, state: ToolState): Part.Tool = Part.Tool(
        id = pid, messageId = mid, sessionId = sid, createdAt = now,
        callId = cid, toolId = toolId, state = state,
    )

    // ── Part.Tool preview: $toolId[$state] format ──────────

    @Test fun toolPendingPreviewFormat() {
        // Marquee state pin: ToolState.Pending → "pending".
        // Drift in the lowercase variant (e.g. "PENDING")
        // would silently change LLM-readable session log.
        assertEquals(
            "any_tool[pending]",
            toolPart("any_tool", ToolState.Pending).preview(),
            "ToolState.Pending MUST render as '\$toolId[pending]'",
        )
    }

    @Test fun toolRunningPreviewFormat() {
        assertEquals(
            "do_thing[running]",
            toolPart("do_thing", ToolState.Running(input = JsonObject(emptyMap()))).preview(),
        )
    }

    @Test fun toolCompletedPreviewFormat() {
        assertEquals(
            "lookup_thing[completed]",
            toolPart(
                "lookup_thing",
                ToolState.Completed(
                    input = JsonObject(emptyMap()),
                    outputForLlm = "result",
                    data = JsonObject(emptyMap()),
                ),
            ).preview(),
        )
    }

    @Test fun toolFailedPreviewIsErrorNotFailed() {
        // Marquee mapping pin: ToolState.Failed → "error"
        // (NOT "failed"). Drift to "failed" surfaces as a
        // session-log readability change.
        assertEquals(
            "do_thing[error]",
            toolPart(
                "do_thing",
                ToolState.Failed(input = null, message = "boom"),
            ).preview(),
            "ToolState.Failed MUST render as 'error' (NOT 'failed')",
        )
    }

    @Test fun toolCancelledPreviewFormat() {
        assertEquals(
            "do_thing[cancelled]",
            toolPart(
                "do_thing",
                ToolState.Cancelled(input = null, message = "user cancel"),
            ).preview(),
        )
    }

    @Test fun toolPreviewFormatPositionsToolIdBeforeBracket() {
        // Sister format pin: `$toolId[$state]` ordering.
        // Drift to `[$state] $toolId` would surface here.
        val p = toolPart("hello_world", ToolState.Pending).preview()
        assertTrue(p.startsWith("hello_world"), "toolId MUST come before bracket in preview")
        assertTrue(p.endsWith("]"), "preview MUST end with ']'")
        assertTrue("[" in p, "preview MUST contain '['")
    }

    // ── Part.TimelineSnapshot preview ───────────────────────

    @Test fun timelineSnapshotPreviewBaselineWithEmptyTimeline() {
        // Marquee baseline pin: producedByCallId=null →
        // " baseline" suffix (not " after <callId>"). Drift
        // to drop baseline marker silently merges first +
        // subsequent snapshots in agent's mental model.
        val snap = Part.TimelineSnapshot(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            timeline = Timeline(),
            producedByCallId = null,
        )
        assertEquals(
            "0 clip(s) baseline",
            snap.preview(),
            "null producedByCallId MUST render as ' baseline' suffix",
        )
    }

    @Test fun timelineSnapshotPreviewAfterToolCall() {
        // Pin: producedByCallId set → " after <callId>" suffix.
        val snap = Part.TimelineSnapshot(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            timeline = Timeline(),
            producedByCallId = CallId("call-add-1"),
        )
        assertEquals(
            "0 clip(s) after call-add-1",
            snap.preview(),
            "non-null producedByCallId MUST render as ' after <callId>' suffix",
        )
    }

    @Test fun timelineSnapshotPreviewClipCountAcrossTracks() {
        // Pin: clip count sums across all tracks. Drift to
        // count tracks-only or first-track-only would
        // silently misreport snapshot density.
        val snap = Part.TimelineSnapshot(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(id = TrackId("v1")),
                    Track.Audio(id = TrackId("a1")),
                ),
            ),
            producedByCallId = null,
        )
        // Both tracks empty here, so clips=0 still — pin
        // documents the sumOf composition.
        assertEquals(
            "0 clip(s) baseline",
            snap.preview(),
            "empty tracks contribute 0 to sum",
        )
    }

    // ── Part.RenderProgress preview ─────────────────────────

    @Test fun renderProgressPreviewPercentageFormat() {
        // Marquee percentage pin: ratio is a 0..1 Float;
        // preview shows it as integer percent. Drift to
        // raw-ratio (e.g. "ratio=0.5") would silently change
        // operator-readable progress.
        val rp = Part.RenderProgress(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            jobId = "job-42",
            ratio = 0.5f,
        )
        assertEquals(
            "job=job-42 ratio=50%",
            rp.preview(),
            "RenderProgress MUST render ratio as integer percent (0.5 → 50%)",
        )
    }

    @Test fun renderProgressFloorsPercent() {
        // Pin: per source `(ratio * 100).toInt()` truncates.
        // 0.999 → 99% (NOT 100). Drift to round-half-up
        // would silently bump 0.5 to 50 already (matches),
        // but 0.999 → 100 instead of 99 surfaces here.
        val rp = Part.RenderProgress(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            jobId = "j",
            ratio = 0.999f,
        )
        assertEquals(
            "job=j ratio=99%",
            rp.preview(),
            "0.999 MUST floor to 99% (toInt truncates, not rounds)",
        )
    }

    @Test fun renderProgressZeroPercentForFreshStart() {
        val rp = Part.RenderProgress(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            jobId = "j",
            ratio = 0.0f,
        )
        assertEquals("job=j ratio=0%", rp.preview())
    }

    // ── Part.StepFinish preview ─────────────────────────────

    @Test fun stepFinishPreviewWithFinishReasonAndTokens() {
        // Marquee format pin: "${finish.name.lowercase()}
        // input=N output=M". Drift in format (e.g. swap
        // input/output) would surface here.
        val sf = Part.StepFinish(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            tokens = TokenUsage(input = 1234, output = 567, reasoning = 0, cacheRead = 0, cacheWrite = 0),
            finish = FinishReason.END_TURN,
        )
        assertEquals(
            "end_turn input=1234 output=567",
            sf.preview(),
            "StepFinish MUST render as '<finish_lowercase> input=N output=M'",
        )
    }

    @Test fun stepFinishLowercasesFinishReason() {
        // Pin: FinishReason.MAX_TOKENS → "max_tokens" lowercase.
        // Drift to KEEP enum upper-case would silently
        // surface SHOUTING summaries.
        val sf = Part.StepFinish(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            tokens = TokenUsage(input = 0, output = 0, reasoning = 0, cacheRead = 0, cacheWrite = 0),
            finish = FinishReason.MAX_TOKENS,
        )
        assertTrue(
            sf.preview().startsWith("max_tokens"),
            "FinishReason MUST be lowercased in preview",
        )
    }

    // ── Part.Compaction preview ─────────────────────────────

    @Test fun compactionPreviewHasArrowFormat() {
        // Marquee arrow pin: "compacted <fromMid>→<toMid>".
        // Drift to a different separator would silently
        // break the preview's structured-handle format.
        val cp = Part.Compaction(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            replacedFromMessageId = MessageId("m-old"),
            replacedToMessageId = MessageId("m-new"),
            summary = "irrelevant — preview ignores summary text",
        )
        assertEquals(
            "compacted m-old→m-new",
            cp.preview(),
            "Compaction MUST render 'compacted <from>→<to>' with arrow separator",
        )
    }

    // ── Part.Todos preview ──────────────────────────────────

    @Test fun todosPreviewWithStatusCounts() {
        // Marquee status-count pin: 4 fields ("N todo(s)
        // pending=P in_progress=R done=D"). Drift in
        // ordering / labels would silently re-shape the
        // dashboard.
        val todos = Part.Todos(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            todos = listOf(
                TodoInfo(content = "a", status = TodoStatus.PENDING),
                TodoInfo(content = "b", status = TodoStatus.PENDING),
                TodoInfo(content = "c", status = TodoStatus.IN_PROGRESS),
                TodoInfo(content = "d", status = TodoStatus.COMPLETED),
                TodoInfo(content = "e", status = TodoStatus.CANCELLED),
            ),
        )
        // Note: cancelled todos contribute to the size (5)
        // but not to any of the 3 surface counts —
        // documents the actual counting behavior.
        assertEquals(
            "5 todo(s) pending=2 in_progress=1 done=1",
            todos.preview(),
            "Todos preview MUST render '<size> todo(s) pending=P in_progress=R done=D'",
        )
    }

    @Test fun todosPreviewEmptyList() {
        // Edge: empty todos list → "0 todo(s) pending=0
        // in_progress=0 done=0". Drift to a different empty
        // marker would surface here.
        val todos = Part.Todos(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            todos = emptyList(),
        )
        assertEquals(
            "0 todo(s) pending=0 in_progress=0 done=0",
            todos.preview(),
        )
    }

    // ── Part.Plan preview ───────────────────────────────────

    @Test fun planPreviewWithStatusCountsAndApproval() {
        // Marquee plan-format pin: 5-field shape "<size>
        // step(s) pending=P done=D failed=F [approval]".
        // Distinct from Todos by including failed + approval.
        val plan = Part.Plan(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            goalDescription = "deploy",
            steps = listOf(
                PlanStep(step = 1, toolName = "a", inputSummary = "x", status = PlanStepStatus.PENDING),
                PlanStep(step = 2, toolName = "b", inputSummary = "y", status = PlanStepStatus.PENDING),
                PlanStep(step = 3, toolName = "c", inputSummary = "z", status = PlanStepStatus.IN_PROGRESS),
                PlanStep(step = 4, toolName = "d", inputSummary = "q", status = PlanStepStatus.COMPLETED),
                PlanStep(step = 5, toolName = "e", inputSummary = "r", status = PlanStepStatus.FAILED),
            ),
            approvalStatus = PlanApprovalStatus.PENDING_APPROVAL,
        )
        assertEquals(
            "5 step(s) pending=2 done=1 failed=1 [pending_approval]",
            plan.preview(),
            "Plan preview MUST render '<size> step(s) pending=P done=D failed=F [<approval_lower>]'",
        )
    }

    @Test fun planPreviewLowercasesApprovalStatus() {
        // Pin: approvalStatus enum lowercased in preview
        // (matches FinishReason convention).
        val plan = Part.Plan(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            goalDescription = "x",
            steps = emptyList(),
            approvalStatus = PlanApprovalStatus.APPROVED,
        )
        assertEquals(
            "0 step(s) pending=0 done=0 failed=0 [approved]",
            plan.preview(),
            "approvalStatus MUST be lowercased in preview",
        )
    }

    @Test fun planPreviewApprovedWithEditsBracket() {
        // Sister enum pin: APPROVED_WITH_EDITS → "approved_
        // with_edits" (underscores preserved + lowercase).
        val plan = Part.Plan(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            goalDescription = "x",
            steps = emptyList(),
            approvalStatus = PlanApprovalStatus.APPROVED_WITH_EDITS,
        )
        assertTrue(
            "[approved_with_edits]" in plan.preview(),
            "APPROVED_WITH_EDITS MUST render as '[approved_with_edits]' (underscores preserved)",
        )
    }

    @Test fun planPreviewRejectedBracket() {
        val plan = Part.Plan(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            goalDescription = "x",
            steps = emptyList(),
            approvalStatus = PlanApprovalStatus.REJECTED,
        )
        assertTrue("[rejected]" in plan.preview())
    }

    // ── Cross-section invariants ────────────────────────────

    @Test fun previewAlwaysReturnsNonBlankString() {
        // Pin: every Part subtype's preview returns a
        // non-blank string. Drift to return empty for any
        // subtype would silently cause UI / LLM context
        // gaps.
        val parts: List<Part> = listOf(
            toolPart("t", ToolState.Pending),
            Part.TimelineSnapshot(pid, mid, sid, now, timeline = Timeline()),
            Part.RenderProgress(pid, mid, sid, now, jobId = "j", ratio = 0f),
            Part.StepFinish(
                pid, mid, sid, now,
                tokens = TokenUsage(0, 0, 0, 0, 0),
                finish = FinishReason.STOP,
            ),
            Part.Compaction(
                pid, mid, sid, now,
                replacedFromMessageId = mid, replacedToMessageId = mid, summary = "x",
            ),
            Part.Todos(pid, mid, sid, now, todos = emptyList()),
            Part.Plan(
                pid, mid, sid, now,
                goalDescription = "",
                steps = emptyList(),
                approvalStatus = PlanApprovalStatus.PENDING_APPROVAL,
            ),
        )
        for (p in parts) {
            assertTrue(
                p.preview().isNotBlank(),
                "preview of ${p::class.simpleName} MUST be non-blank; got: '${p.preview()}'",
            )
        }
    }

    @Test fun toolPreviewIncludesAssetIdValueForMediaPart() {
        // Sister of existing Media test in
        // session/query/QueryHelpersTest.mediaPartPreviewIs
        // AssetIdValue — pin documents that this preview
        // returns the raw AssetId.value with no prefix
        // (drift to wrap with "asset:" / "media:" surfaces
        // here).
        val mp = Part.Media(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            assetId = AssetId("asset-7-uuid-v4"),
        )
        assertEquals(
            "asset-7-uuid-v4",
            mp.preview(),
            "Media preview MUST be raw AssetId.value (no wrap)",
        )
    }
}
