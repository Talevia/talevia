package io.talevia.server

import io.talevia.core.bus.BusEvent
import io.talevia.core.permission.PermissionRule
import io.talevia.core.session.Session
import kotlinx.serialization.Serializable

// ─── Request / response DTOs (thin wire shapes over the Core domain) ───

@Serializable data class CreateProjectRequest(val title: String)
@Serializable data class CreateProjectResponse(val projectId: String)
@Serializable data class ProjectSummaryDto(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable data class CreateSessionRequest(
    val projectId: String,
    val title: String? = null,
    val permissionRules: List<PermissionRule>? = null,
)

@Serializable data class SessionSummary(
    val id: String,
    val projectId: String,
    val title: String,
    val parentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        fun from(s: Session): SessionSummary = SessionSummary(
            id = s.id.value,
            projectId = s.projectId.value,
            title = s.title,
            parentId = s.parentId?.value,
            createdAt = s.createdAt.toEpochMilliseconds(),
            updatedAt = s.updatedAt.toEpochMilliseconds(),
        )
    }
}

@Serializable data class CreateSessionResponse(val sessionId: String)
@Serializable data class AppendTextRequest(
    val text: String,
    val agent: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
)
@Serializable data class AppendTextResponse(val messageId: String)

@Serializable data class SubmitMessageRequest(
    val text: String,
    val providerId: String? = null,
    val modelId: String? = null,
)
@Serializable data class SubmitMessageResponse(
    val correlationId: String,
    val providerId: String,
    val modelId: String,
)

internal fun defaultModelFor(providerId: String): String = when (providerId) {
    "anthropic" -> "claude-opus-4-7"
    "openai" -> "gpt-5.4-mini"
    else -> "default"
}

// ─── SSE event serialisation ───

/** Human-readable event name for SSE `event:` lines. */
internal fun eventName(e: BusEvent): String = when (e) {
    is BusEvent.SessionCreated -> "session.created"
    is BusEvent.SessionUpdated -> "session.updated"
    is BusEvent.SessionDeleted -> "session.deleted"
    is BusEvent.SessionCancelled -> "session.cancelled"
    is BusEvent.SessionCancelRequested -> "session.cancel.requested"
    is BusEvent.MessageUpdated -> "message.updated"
    is BusEvent.MessageDeleted -> "message.deleted"
    is BusEvent.SessionReverted -> "session.reverted"
    is BusEvent.PartUpdated -> "message.part.updated"
    is BusEvent.PartDelta -> "message.part.delta"
    is BusEvent.PermissionAsked -> "permission.asked"
    is BusEvent.PermissionReplied -> "permission.replied"
    is BusEvent.AgentRunFailed -> "agent.run.failed"
    is BusEvent.AgentRetryScheduled -> "agent.retry.scheduled"
    is BusEvent.AgentProviderFallback -> "agent.provider.fallback"
    is BusEvent.SessionCompactionAuto -> "session.compaction.auto"
    is BusEvent.AgentRunStateChanged -> "agent.run.state.changed"
    is BusEvent.SessionProjectBindingChanged -> "session.project.binding.changed"
    is BusEvent.ProjectValidationWarning -> "project.validation.warning"
    is BusEvent.AigcCostRecorded -> "aigc.cost.recorded"
    is BusEvent.AigcCacheProbe -> "aigc.cache.probe"
    is BusEvent.AssetsMissing -> "project.assets.missing"
}

/**
 * Wire-format BusEvent. Mirrors the Kotlin sealed hierarchy but uses
 * plain strings for IDs so existing JSON consumers don't need branded
 * value-class handling.
 */
@Serializable
data class BusEventDto(
    val type: String,
    /**
     * Non-null for all session-scoped events; null for project-scoped
     * events like `project.validation.warning` that are emitted outside
     * any active session (e.g. on project load).
     */
    val sessionId: String? = null,
    val messageId: String? = null,
    val partId: String? = null,
    val field: String? = null,
    val delta: String? = null,
    val requestId: String? = null,
    val permission: String? = null,
    val patterns: List<String>? = null,
    val accepted: Boolean? = null,
    val remembered: Boolean? = null,
    val correlationId: String? = null,
    val message: String? = null,
    val projectId: String? = null,
    /** Set for `session.project.binding.changed` — null when previously unbound. */
    val previousProjectId: String? = null,
    val anchorMessageId: String? = null,
    val deletedMessages: Int? = null,
    val appliedSnapshotPartId: String? = null,
    val attempt: Int? = null,
    val waitMs: Long? = null,
    val reason: String? = null,
    val historyTokensBefore: Int? = null,
    val thresholdTokens: Int? = null,
    /** `idle | generating | awaiting_tool | compacting | cancelled | failed` for `agent.run.state.changed`. */
    val runState: String? = null,
    /** Message-ish cause; set when `runState == "failed"`. */
    val runStateCause: String? = null,
    /** Human-readable DAG issues. Set for `project.validation.warning`. */
    val validationIssues: List<String>? = null,
    /** Set for `agent.provider.fallback` — provider the chain is leaving. */
    val fromProviderId: String? = null,
    /** Set for `agent.provider.fallback` — provider the chain is advancing to. */
    val toProviderId: String? = null,
    /** Set for `aigc.cost.recorded` — which tool produced the asset (e.g. `generate_image`). */
    val toolId: String? = null,
    /** Set for `aigc.cost.recorded` — the produced asset id. */
    val assetId: String? = null,
    /** Set for `aigc.cost.recorded` — USD cents (null = no pricing rule). */
    val costCents: Long? = null,
) {
    companion object {
        fun from(e: BusEvent): BusEventDto = when (e) {
            is BusEvent.SessionCreated -> BusEventDto("session.created", e.sessionId.value)
            is BusEvent.SessionUpdated -> BusEventDto("session.updated", e.sessionId.value)
            is BusEvent.SessionDeleted -> BusEventDto("session.deleted", e.sessionId.value)
            is BusEvent.SessionCancelled -> BusEventDto("session.cancelled", e.sessionId.value)
            is BusEvent.SessionCancelRequested -> BusEventDto("session.cancel.requested", e.sessionId.value)
            is BusEvent.MessageUpdated -> BusEventDto("message.updated", e.sessionId.value, messageId = e.messageId.value)
            is BusEvent.MessageDeleted -> BusEventDto("message.deleted", e.sessionId.value, messageId = e.messageId.value)
            is BusEvent.SessionReverted -> BusEventDto(
                "session.reverted", e.sessionId.value,
                projectId = e.projectId.value,
                anchorMessageId = e.anchorMessageId.value,
                deletedMessages = e.deletedMessages,
                appliedSnapshotPartId = e.appliedSnapshotPartId?.value,
            )
            is BusEvent.PartUpdated -> BusEventDto(
                "message.part.updated", e.sessionId.value,
                messageId = e.messageId.value, partId = e.partId.value,
            )
            is BusEvent.PartDelta -> BusEventDto(
                "message.part.delta", e.sessionId.value,
                messageId = e.messageId.value, partId = e.partId.value, field = e.field, delta = e.delta,
            )
            is BusEvent.PermissionAsked -> BusEventDto(
                "permission.asked", e.sessionId.value,
                requestId = e.requestId, permission = e.permission, patterns = e.patterns,
            )
            is BusEvent.PermissionReplied -> BusEventDto(
                "permission.replied", e.sessionId.value,
                requestId = e.requestId, accepted = e.accepted, remembered = e.remembered,
            )
            is BusEvent.AgentRunFailed -> BusEventDto(
                "agent.run.failed", e.sessionId.value,
                correlationId = e.correlationId, message = e.message,
            )
            is BusEvent.AgentRetryScheduled -> BusEventDto(
                "agent.retry.scheduled", e.sessionId.value,
                attempt = e.attempt, waitMs = e.waitMs, reason = e.reason,
            )
            is BusEvent.AgentProviderFallback -> BusEventDto(
                "agent.provider.fallback", e.sessionId.value,
                fromProviderId = e.fromProviderId,
                toProviderId = e.toProviderId,
                reason = e.reason,
            )
            is BusEvent.SessionCompactionAuto -> BusEventDto(
                "session.compaction.auto", e.sessionId.value,
                historyTokensBefore = e.historyTokensBefore, thresholdTokens = e.thresholdTokens,
            )
            is BusEvent.AgentRunStateChanged -> {
                val tag = when (val s = e.state) {
                    is io.talevia.core.agent.AgentRunState.Idle -> "idle" to null
                    is io.talevia.core.agent.AgentRunState.Generating -> "generating" to null
                    is io.talevia.core.agent.AgentRunState.AwaitingTool -> "awaiting_tool" to null
                    is io.talevia.core.agent.AgentRunState.Compacting -> "compacting" to null
                    is io.talevia.core.agent.AgentRunState.Cancelled -> "cancelled" to null
                    is io.talevia.core.agent.AgentRunState.Failed -> "failed" to s.cause
                }
                BusEventDto(
                    "agent.run.state.changed", e.sessionId.value,
                    runState = tag.first, runStateCause = tag.second,
                )
            }
            is BusEvent.SessionProjectBindingChanged -> BusEventDto(
                "session.project.binding.changed", e.sessionId.value,
                projectId = e.newProjectId.value,
                previousProjectId = e.previousProjectId?.value,
            )
            is BusEvent.ProjectValidationWarning -> BusEventDto(
                "project.validation.warning",
                sessionId = null,
                projectId = e.projectId.value,
                validationIssues = e.issues,
            )
            is BusEvent.AigcCostRecorded -> BusEventDto(
                "aigc.cost.recorded",
                e.sessionId.value,
                projectId = e.projectId.value,
                toolId = e.toolId,
                assetId = e.assetId,
                costCents = e.costCents,
            )
            is BusEvent.AigcCacheProbe -> BusEventDto(
                "aigc.cache.probe",
                sessionId = null,
                toolId = e.toolId,
            )
            is BusEvent.AssetsMissing -> BusEventDto(
                "project.assets.missing",
                sessionId = null,
                projectId = e.projectId.value,
            )
        }
    }
}
