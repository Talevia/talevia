package io.talevia.server

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drift-catcher tests for the **rich-data** `BusEvent` variants in
 * `BusEventDto.from(...)` —
 * `apps/server/src/main/kotlin/io/talevia/server/ServerDtos.kt:211-371`.
 * Cycle 235 audit: the existing `BusEventDtoTest` (cycle 85) covers
 * 10 of 31 variants, focusing on session lifecycle + the
 * permission-flow staples. The 8 rich-data variants pinned here each
 * carry 3-5 fields — drift in any field's mapping (typo, wrong field
 * name, dropped field) would silently corrupt SSE events for that
 * event type without any test failure.
 *
 * Same audit-pattern fallback as cycles 207-234.
 *
 * Eight rich-data variants pinned (one contract test per variant +
 * cross-variant shape pin):
 *
 *  1. **AgentProviderFallback** — fromProviderId, toProviderId,
 *     reason. Drift: swap from↔to would mis-attribute the fallback
 *     chain in audit dashboards.
 *  2. **AgentRetryScheduled** — attempt, waitMs, reason. Drift: lose
 *     attempt would break "did retry #N succeed?" subscribers.
 *  3. **AgentRunFailed** — correlationId, message. Drift: lose
 *     correlationId would orphan the SSE-202 client recovery flow.
 *  4. **AigcCostRecorded** — projectId, toolId, assetId, costCents.
 *     Drift: any field swap would corrupt AIGC dashboard / billing
 *     attribution.
 *  5. **AigcJobProgress** — callId, toolId, jobId, phase
 *     (4-variant enum→string), ratio, etaSec, message, providerId.
 *     Marquee enum-mapping pin: phase mapping covers all 4 enum
 *     values (Started/Progress/Completed/Failed → started/progress/
 *     completed/failed lowercase).
 *  6. **SessionProjectBindingChanged** — previousProjectId
 *     (nullable), newProjectId. Marquee null-handling pin:
 *     `previousProjectId == null` (first-time bind) maps to a null
 *     DTO field; non-null maps to the value.
 *  7. **SessionReverted** — projectId, anchorMessageId,
 *     deletedMessages, appliedSnapshotPartId (nullable). Drift:
 *     similar null-handling for appliedSnapshotPartId.
 *  8. **SpendCapApproaching** — capCents, currentCents (mapped to
 *     `costCents`!), ratio, scope, toolId. Marquee field-rename pin:
 *     `currentCents` → DTO's `costCents` field is unintuitive
 *     drift-prone (drift to "preserve currentCents naming" would
 *     break subscribers reading `costCents`).
 *  9. **ToolSpecBudgetWarning** — estimatedTokens, threshold (mapped
 *     to `thresholdTokensBudget`!), toolCount. Marquee field-rename
 *     pin: `threshold` → `thresholdTokensBudget`. Project-scoped
 *     event (sessionId is null in DTO).
 *
 * Total: 9 rich variants × ~3-5 fields each = ~30 field-mapping
 * arrows pinned by this file. Full BusEventDto coverage (31 variants
 * × all fields) is the limit; this round bands the
 * highest-data-density rich variants — thin variants
 * (SessionCancelled, SessionDeleted, MessageUpdated, etc.) are
 * structurally identical to the SessionCreated coverage in
 * `BusEventDtoTest`.
 */
class BusEventDtoRichVariantsTest {

    private val sid = SessionId("sess-1")
    private val pid = ProjectId("proj-1")
    private val mid = MessageId("msg-1")
    private val partId = PartId("part-1")
    private val callId = CallId("call-1")

    @Test fun agentProviderFallbackMapsAllFields() {
        val dto = BusEventDto.from(
            BusEvent.AgentProviderFallback(
                sessionId = sid,
                fromProviderId = "anthropic",
                toProviderId = "openai",
                reason = "anthropic exhausted retry budget",
            ),
        )
        assertEquals("agent.provider.fallback", dto.type)
        assertEquals("sess-1", dto.sessionId)
        assertEquals("anthropic", dto.fromProviderId, "from-provider preserved (NOT swapped)")
        assertEquals("openai", dto.toProviderId, "to-provider preserved (NOT swapped)")
        assertEquals("anthropic exhausted retry budget", dto.reason)
    }

    @Test fun agentRetryScheduledMapsAttemptWaitMsReason() {
        val dto = BusEventDto.from(
            BusEvent.AgentRetryScheduled(
                sessionId = sid,
                attempt = 3,
                waitMs = 5000L,
                reason = "rate-limited",
                providerId = "anthropic",
            ),
        )
        assertEquals("agent.retry.scheduled", dto.type)
        assertEquals(3, dto.attempt, "attempt count preserved (drift would break retry tracking)")
        assertEquals(5000L, dto.waitMs, "waitMs preserved (drift would mis-display retry delay)")
        assertEquals("rate-limited", dto.reason)
    }

    @Test fun agentRunFailedMapsCorrelationIdAndMessage() {
        val dto = BusEventDto.from(
            BusEvent.AgentRunFailed(
                sessionId = sid,
                correlationId = "corr-abc-123",
                message = "provider timeout after 60s",
            ),
        )
        assertEquals("agent.run.failed", dto.type)
        assertEquals(
            "corr-abc-123",
            dto.correlationId,
            "correlationId preserved — load-bearing for SSE-202 client recovery",
        )
        assertEquals("provider timeout after 60s", dto.message)
    }

    @Test fun aigcCostRecordedMapsAllFinancialFields() {
        // Marquee billing-attribution pin: any field-swap here would
        // corrupt the per-asset cost ledger.
        val dto = BusEventDto.from(
            BusEvent.AigcCostRecorded(
                sessionId = sid,
                projectId = pid,
                toolId = "generate_image",
                assetId = "asset-uuid-1",
                costCents = 4L,
            ),
        )
        assertEquals("aigc.cost.recorded", dto.type)
        assertEquals("sess-1", dto.sessionId)
        assertEquals("proj-1", dto.projectId)
        assertEquals("generate_image", dto.toolId)
        assertEquals("asset-uuid-1", dto.assetId)
        assertEquals(4L, dto.costCents)
    }

    @Test fun aigcCostRecordedNullCostMapsToNull() {
        // Pin per kdoc: `costCents` null = "no pricing rule for
        // provider+model" — distinct from 0¢ (free). Subscribers
        // surface "unknown" separately.
        val dto = BusEventDto.from(
            BusEvent.AigcCostRecorded(
                sessionId = sid,
                projectId = pid,
                toolId = "generate_video",
                assetId = "asset-2",
                costCents = null,
            ),
        )
        assertNull(dto.costCents, "null costCents preserved (NOT defaulted to 0)")
    }

    // ── 5. AigcJobProgress: marquee enum-phase mapping ─────

    @Test fun aigcJobProgressPhaseStartedMapsToLowercase() {
        val dto = makeJobProgress(BusEvent.AigcProgressPhase.Started)
        assertEquals("started", dto.aigcJobPhase)
    }

    @Test fun aigcJobProgressPhaseProgressMapsToLowercase() {
        val dto = makeJobProgress(BusEvent.AigcProgressPhase.Progress)
        assertEquals("progress", dto.aigcJobPhase)
    }

    @Test fun aigcJobProgressPhaseCompletedMapsToLowercase() {
        val dto = makeJobProgress(BusEvent.AigcProgressPhase.Completed)
        assertEquals("completed", dto.aigcJobPhase)
    }

    @Test fun aigcJobProgressPhaseFailedMapsToLowercase() {
        val dto = makeJobProgress(BusEvent.AigcProgressPhase.Failed)
        assertEquals("failed", dto.aigcJobPhase)
    }

    @Test fun aigcJobProgressMapsAllRichFields() {
        val dto = BusEventDto.from(
            BusEvent.AigcJobProgress(
                sessionId = sid,
                callId = callId,
                toolId = "generate_image",
                jobId = "job-xyz",
                phase = BusEvent.AigcProgressPhase.Progress,
                ratio = 0.42f,
                etaSec = 30,
                message = "rendering frame 24/100",
                providerId = "openai",
            ),
        )
        assertEquals("aigc.job.progress", dto.type)
        assertEquals("call-1", dto.callId)
        assertEquals("generate_image", dto.toolId)
        assertEquals("job-xyz", dto.jobId)
        assertEquals("progress", dto.aigcJobPhase)
        assertEquals(0.42f, dto.ratio)
        assertEquals(30, dto.etaSec)
        assertEquals("rendering frame 24/100", dto.message)
        assertEquals("openai", dto.providerId)
    }

    // ── 6. SessionProjectBindingChanged: null-handling ─────

    @Test fun sessionProjectBindingChangedFirstTimeBindHasNullPrevious() {
        // Marquee null-handling pin: `previousProjectId == null`
        // (first-time bind) MUST map to null DTO field, NOT to ""
        // or some sentinel. Drift to non-null default would let
        // subscribers think the session was previously bound.
        val dto = BusEventDto.from(
            BusEvent.SessionProjectBindingChanged(
                sessionId = sid,
                previousProjectId = null,
                newProjectId = ProjectId("new-proj"),
            ),
        )
        assertEquals("session.project.binding.changed", dto.type)
        assertEquals("new-proj", dto.projectId, "newProjectId maps to projectId field")
        assertNull(dto.previousProjectId, "first-time bind → previousProjectId null")
    }

    @Test fun sessionProjectBindingChangedRebindHasBothIds() {
        val dto = BusEventDto.from(
            BusEvent.SessionProjectBindingChanged(
                sessionId = sid,
                previousProjectId = ProjectId("old-proj"),
                newProjectId = ProjectId("new-proj"),
            ),
        )
        assertEquals("old-proj", dto.previousProjectId)
        assertEquals("new-proj", dto.projectId)
    }

    // ── 7. SessionReverted: null-handling on snapshot id ───

    @Test fun sessionRevertedMapsAllFieldsIncludingNullSnapshot() {
        // Pin: appliedSnapshotPartId nullable — null when the revert
        // didn't restore from a snapshot. Drift to default would
        // confuse the UI's "snapshot N restored" badge.
        val dto = BusEventDto.from(
            BusEvent.SessionReverted(
                sessionId = sid,
                projectId = pid,
                anchorMessageId = mid,
                deletedMessages = 5,
                appliedSnapshotPartId = null,
            ),
        )
        assertEquals("session.reverted", dto.type)
        assertEquals("proj-1", dto.projectId)
        assertEquals("msg-1", dto.anchorMessageId)
        assertEquals(5, dto.deletedMessages)
        assertNull(dto.appliedSnapshotPartId, "null snapshot id preserved")
    }

    @Test fun sessionRevertedWithSnapshotMapsPartId() {
        val dto = BusEventDto.from(
            BusEvent.SessionReverted(
                sessionId = sid,
                projectId = pid,
                anchorMessageId = mid,
                deletedMessages = 3,
                appliedSnapshotPartId = partId,
            ),
        )
        assertEquals("part-1", dto.appliedSnapshotPartId)
    }

    // ── 8. SpendCapApproaching: marquee field-rename pin ──

    @Test fun spendCapApproachingRenamesCurrentCentsToCostCents() {
        // Marquee field-rename pin: `currentCents` (BusEvent) →
        // `costCents` (DTO). Drift to "rename to currentCents on the
        // DTO too" would break every subscriber reading `costCents`.
        val dto = BusEventDto.from(
            BusEvent.SpendCapApproaching(
                sessionId = sid,
                capCents = 1000L,
                currentCents = 850L,
                ratio = 0.85,
                scope = "aigc",
                toolId = "generate_image",
            ),
        )
        assertEquals("spend.cap.approaching", dto.type)
        assertEquals("sess-1", dto.sessionId)
        assertEquals(
            850L,
            dto.costCents,
            "currentCents (BusEvent) → costCents (DTO) — load-bearing rename",
        )
        assertEquals(1000L, dto.spendCapCents, "capCents → spendCapCents")
        assertEquals("aigc", dto.spendCapScope, "scope → spendCapScope (rename pin)")
        assertEquals("generate_image", dto.toolId)
    }

    @Test fun spendCapApproachingExportScopePreserved() {
        // Pin: `scope` accepts "aigc" or "export" per kdoc. Drift to
        // case-mangling would break subscriber filters.
        val dto = BusEventDto.from(
            BusEvent.SpendCapApproaching(
                sessionId = sid,
                capCents = 5000L,
                currentCents = 4200L,
                ratio = 0.84,
                scope = "export",
                toolId = "export",
            ),
        )
        assertEquals("export", dto.spendCapScope, "export scope preserved verbatim")
    }

    // ── 9. ToolSpecBudgetWarning: project-scoped (no sessionId) ─

    @Test fun toolSpecBudgetWarningHasNoSessionIdAndRenamesThreshold() {
        // Marquee project-scoped pin: registry is process-wide, not
        // session-scoped, so `sessionId` MUST be null. Plus the
        // `threshold` (BusEvent) → `thresholdTokensBudget` (DTO)
        // rename — drift to "match field names" would break
        // subscribers reading `thresholdTokensBudget`.
        val dto = BusEventDto.from(
            BusEvent.ToolSpecBudgetWarning(
                estimatedTokens = 18500,
                threshold = 18000,
                toolCount = 53,
            ),
        )
        assertEquals("tool.spec.budget.warning", dto.type)
        assertNull(dto.sessionId, "process-wide event has NO sessionId")
        assertEquals(18500, dto.estimatedTokens)
        assertEquals(
            18000,
            dto.thresholdTokensBudget,
            "BusEvent.threshold → DTO.thresholdTokensBudget (rename pin)",
        )
        assertEquals(53, dto.toolCount)
    }

    // ── helper ────────────────────────────────────────────

    private fun makeJobProgress(phase: BusEvent.AigcProgressPhase): BusEventDto =
        BusEventDto.from(
            BusEvent.AigcJobProgress(
                sessionId = sid,
                callId = callId,
                toolId = "generate_image",
                jobId = "job-xyz",
                phase = phase,
            ),
        )
}
