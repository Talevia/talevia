package io.talevia.server

import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.bus.BusEvent
import io.talevia.core.agent.FallbackHint
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Direct tests for [BusEventDto.from] — the BusEvent → SSE-DTO mapping
 * that every server-mode SSE consumer reads. Cycle 85 audit found this
 * 60-arm `when` function had no direct test (zero references in any
 * server test).
 *
 * The arms aren't exhaustively tested here (60 is too many for a unit
 * test). This file covers ~10 high-frequency arms representing the
 * major event categories: session lifecycle, message/part streaming,
 * permission flow, agent run state, compaction, project-scoped vs
 * session-scoped events. A regression in any of these would silently
 * corrupt SSE output for that event type — UI consumers would receive
 * malformed events with wrong field names or types.
 */
class BusEventDtoTest {

    private val sid = SessionId("sess-1")
    private val mid = MessageId("msg-1")
    private val pid = PartId("part-1")
    private val projId = ProjectId("proj-1")

    @Test fun sessionCreatedMapsToSessionCreatedTypeWithSessionId() {
        val dto = BusEventDto.from(BusEvent.SessionCreated(sid))
        assertEquals("session.created", dto.type)
        assertEquals("sess-1", dto.sessionId)
    }

    @Test fun sessionCancelRequestedMapsToCorrectType() {
        // Pin the discriminator string the server's SSE clients filter on.
        val dto = BusEventDto.from(BusEvent.SessionCancelRequested(sid))
        assertEquals("session.cancel.requested", dto.type)
        assertEquals("sess-1", dto.sessionId)
    }

    @Test fun partDeltaPopulatesMessageIdPartIdFieldDelta() {
        // Streaming hot path — every token's worth of LLM output flows
        // through this DTO arm. Pin all 4 streaming-relevant fields.
        val dto = BusEventDto.from(
            BusEvent.PartDelta(
                sessionId = sid,
                messageId = mid,
                partId = pid,
                field = "text",
                delta = "Hello, ",
            ),
        )
        assertEquals("message.part.delta", dto.type)
        assertEquals("sess-1", dto.sessionId)
        assertEquals("msg-1", dto.messageId)
        assertEquals("part-1", dto.partId)
        assertEquals("text", dto.field)
        assertEquals("Hello, ", dto.delta)
    }

    @Test fun permissionAskedPopulatesRequestIdPermissionPatterns() {
        // Security-UX surface — UIs render the prompt from this DTO.
        val dto = BusEventDto.from(
            BusEvent.PermissionAsked(
                sessionId = sid,
                requestId = "req-42",
                permission = "fs.write",
                patterns = listOf("/tmp/x", "/var/log"),
            ),
        )
        assertEquals("permission.asked", dto.type)
        assertEquals("sess-1", dto.sessionId)
        assertEquals("req-42", dto.requestId)
        assertEquals("fs.write", dto.permission)
        assertEquals(listOf("/tmp/x", "/var/log"), dto.patterns)
    }

    @Test fun permissionRepliedPopulatesAcceptedRemembered() {
        // The reply that pairs with PermissionAsked. SSE consumers showing
        // asking → answered transitions need both fields.
        val dto = BusEventDto.from(
            BusEvent.PermissionReplied(
                sessionId = sid,
                requestId = "req-42",
                accepted = true,
                remembered = false,
            ),
        )
        assertEquals("permission.replied", dto.type)
        assertEquals("req-42", dto.requestId)
        assertEquals(true, dto.accepted)
        assertEquals(false, dto.remembered)
    }

    @Test fun agentRunStateChangedMapsEachStateToCorrectStringTag() {
        // Pin the `runState` string mapping — the UI state machine
        // discriminates on these literal strings.
        val states = mapOf(
            AgentRunState.Idle to "idle",
            AgentRunState.Generating to "generating",
            AgentRunState.AwaitingTool to "awaiting_tool",
            AgentRunState.Compacting to "compacting",
            AgentRunState.Cancelled to "cancelled",
        )
        for ((state, expected) in states) {
            val dto = BusEventDto.from(
                BusEvent.AgentRunStateChanged(
                    sessionId = sid,
                    state = state,
                    retryAttempt = null,
                ),
            )
            assertEquals("agent.run.state.changed", dto.type)
            assertEquals(expected, dto.runState, "state $state must map to '$expected'")
            assertNull(dto.runStateCause, "non-failed states have null cause")
        }
    }

    @Test fun agentRunStateFailedSurfacesCauseInRunStateCause() {
        // Failed is the only state with a `cause` payload. Pin the
        // mapping so a refactor adding a new failed sub-shape doesn't
        // silently drop the message.
        val dto = BusEventDto.from(
            BusEvent.AgentRunStateChanged(
                sessionId = sid,
                state = AgentRunState.Failed("boom", FallbackHint.Uncaught()),
                retryAttempt = 2,
            ),
        )
        assertEquals("failed", dto.runState)
        assertEquals("boom", dto.runStateCause)
        assertEquals(2, dto.runStateRetryAttempt)
    }

    @Test fun sessionCompactedPopulatesPrunedCountAndSummaryLength() {
        val dto = BusEventDto.from(
            BusEvent.SessionCompacted(
                sessionId = sid,
                prunedCount = 5,
                summaryLength = 1234,
            ),
        )
        assertEquals("session.compacted", dto.type)
        assertEquals(5, dto.prunedCount)
        assertEquals(1234, dto.summaryLength)
    }

    @Test fun projectScopedEventOmitsSessionId() {
        // ProjectValidationWarning has no session affinity. The DTO's
        // `sessionId` MUST be null so SSE consumers know to handle it
        // as a project-wide event, not stuff it into a session log.
        val dto = BusEventDto.from(
            BusEvent.ProjectValidationWarning(
                projectId = projId,
                issues = listOf("dangling parent: ghost"),
            ),
        )
        assertEquals("project.validation.warning", dto.type)
        assertNull(dto.sessionId, "project-scoped events must have null sessionId")
        assertEquals("proj-1", dto.projectId)
        assertEquals(listOf("dangling parent: ghost"), dto.validationIssues)
    }

    @Test fun aigcCacheProbeIsProjectScopedNoSessionId() {
        // Another non-SessionEvent. Pin so a future refactor adding
        // sessionId to AigcCacheProbe's DTO mapping requires explicit
        // intent (and a new test).
        val dto = BusEventDto.from(BusEvent.AigcCacheProbe(toolId = "generate_image", hit = false))
        assertEquals("aigc.cache.probe", dto.type)
        assertNull(dto.sessionId)
        assertEquals("generate_image", dto.toolId)
    }

    @Test fun providerWarmupSurfacesPhaseAndProviderId() {
        // Warmup events tell SSE consumers when a provider is starting
        // up. Both `warmupPhase` (string) and `providerId` are needed
        // to render the right banner.
        val now = Clock.System.now().toEpochMilliseconds()
        val dto = BusEventDto.from(
            BusEvent.ProviderWarmup(
                sessionId = sid,
                providerId = "anthropic",
                phase = BusEvent.ProviderWarmup.Phase.Starting,
                epochMs = now,
            ),
        )
        assertEquals("provider.warmup", dto.type)
        assertEquals("sess-1", dto.sessionId)
        assertEquals("anthropic", dto.providerId)
        assertNotNull(dto.warmupPhase, "phase must be propagated")
        assertEquals(now, dto.epochMs)
    }
}
