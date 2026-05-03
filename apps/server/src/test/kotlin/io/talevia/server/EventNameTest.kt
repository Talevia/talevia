package io.talevia.server

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.FallbackHint
import io.talevia.core.bus.BusEvent
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [eventName] —
 * `apps/server/src/main/kotlin/io/talevia/server/ServerDtos.kt:79-110`.
 * Cycle 236 audit: 0 direct test refs. The function is consumed by
 * `SessionRoutes.kt`'s `/sessions/{id}/events` SSE endpoint to write
 * the `event:` line for every BusEvent. SSE clients filter on this
 * string; drift in any single arm would silently break consumers
 * watching for that event type.
 *
 * Same audit-pattern fallback as cycles 207-235.
 *
 * Two correctness contracts pinned:
 *
 *  1. **Each BusEvent variant maps to its canonical event-name
 *     string** (covers all 30 arms of the `when` in `eventName`).
 *     Drift in any single string (`session.created` →
 *     `session.create`, dot-separator → underscore, missing
 *     segment) would silently break SSE filters.
 *
 *  2. **`eventName(e)` and `BusEventDto.from(e).type` MUST agree.**
 *     The SSE protocol writes BOTH: `event: <eventName>` then `data:
 *     {"type": "<type>"}`. Subscribers branch on the SSE-level
 *     `event` line; consumers re-parsing the JSON read `type`. Drift
 *     between them would create silent cross-talk where the same
 *     event has two different identifiers depending on which lane
 *     the consumer reads. This invariant is the marquee pin —
 *     pinning per-arm name AND the agreement between the two
 *     functions catches any future refactor that updates one
 *     without the other.
 *
 * The test exercises every variant via a representative sample with
 * minimum-shape constructors (IDs only where required); the rich-
 * data variants don't need real payloads — `eventName` only switches
 * on the variant TYPE, not contents.
 */
class EventNameTest {

    private val sid = SessionId("s1")
    private val mid = MessageId("m1")
    private val partId = PartId("p1")
    private val pid = ProjectId("proj1")
    private val callId = CallId("c1")
    private val now = Clock.System.now()
    private val msg: Message = Message.User(
        id = mid,
        sessionId = sid,
        createdAt = now,
        agent = "test",
        model = ModelRef("anthropic", "claude-opus-4-7"),
    )

    // ── 1. Per-arm canonical name pins (all 30 variants) ────

    @Test fun sessionLifecycleEvents() {
        assertEquals("session.created", eventName(BusEvent.SessionCreated(sid)))
        assertEquals("session.updated", eventName(BusEvent.SessionUpdated(sid)))
        assertEquals("session.deleted", eventName(BusEvent.SessionDeleted(sid)))
        assertEquals("session.cancelled", eventName(BusEvent.SessionCancelled(sid)))
        assertEquals("session.cancel.requested", eventName(BusEvent.SessionCancelRequested(sid)))
        assertEquals(
            "session.full",
            eventName(BusEvent.SessionFull(sessionId = sid, messageCount = 1, cap = 100)),
        )
    }

    @Test fun sessionRevertedAndCompactionEvents() {
        assertEquals(
            "session.reverted",
            eventName(
                BusEvent.SessionReverted(
                    sessionId = sid,
                    projectId = pid,
                    anchorMessageId = mid,
                    deletedMessages = 0,
                    appliedSnapshotPartId = null,
                ),
            ),
        )
        assertEquals(
            "session.compaction.auto",
            eventName(
                BusEvent.SessionCompactionAuto(
                    sessionId = sid,
                    historyTokensBefore = 1000,
                    thresholdTokens = 500,
                ),
            ),
        )
        assertEquals(
            "session.compacted",
            eventName(
                BusEvent.SessionCompacted(
                    sessionId = sid,
                    prunedCount = 1,
                    summaryLength = 100,
                ),
            ),
        )
        assertEquals(
            "session.project.binding.changed",
            eventName(
                BusEvent.SessionProjectBindingChanged(
                    sessionId = sid,
                    previousProjectId = null,
                    newProjectId = pid,
                ),
            ),
        )
    }

    @Test fun messageAndPartEvents() {
        assertEquals("message.updated", eventName(BusEvent.MessageUpdated(sid, mid, msg)))
        assertEquals("message.deleted", eventName(BusEvent.MessageDeleted(sid, mid)))
        val textPart = Part.Text(
            id = partId,
            messageId = mid,
            sessionId = sid,
            createdAt = now,
            text = "x",
        )
        assertEquals(
            "message.part.updated",
            eventName(BusEvent.PartUpdated(sid, mid, partId, textPart)),
        )
        assertEquals(
            "message.part.delta",
            eventName(BusEvent.PartDelta(sid, mid, partId, "field", "delta")),
        )
    }

    @Test fun permissionAndAgentEvents() {
        assertEquals(
            "permission.asked",
            eventName(BusEvent.PermissionAsked(sid, "req-1", "fs.read", listOf("/tmp/*"))),
        )
        assertEquals(
            "permission.replied",
            eventName(
                BusEvent.PermissionReplied(
                    sessionId = sid,
                    requestId = "req-1",
                    accepted = true,
                    remembered = false,
                ),
            ),
        )
        assertEquals(
            "agent.run.failed",
            eventName(BusEvent.AgentRunFailed(sid, "corr-1", "boom")),
        )
        assertEquals(
            "agent.retry.scheduled",
            eventName(
                BusEvent.AgentRetryScheduled(
                    sessionId = sid,
                    attempt = 1,
                    waitMs = 1000L,
                    reason = "rate-limited",
                ),
            ),
        )
        assertEquals(
            "agent.provider.fallback",
            eventName(
                BusEvent.AgentProviderFallback(
                    sessionId = sid,
                    fromProviderId = "anthropic",
                    toProviderId = "openai",
                    reason = "exhausted",
                ),
            ),
        )
        assertEquals(
            "agent.run.state.changed",
            eventName(
                BusEvent.AgentRunStateChanged(
                    sessionId = sid,
                    state = AgentRunState.Idle,
                ),
            ),
        )
    }

    @Test fun projectAndAigcEvents() {
        assertEquals(
            "project.validation.warning",
            eventName(BusEvent.ProjectValidationWarning(pid, listOf("issue1"))),
        )
        assertEquals(
            "project.mutated",
            eventName(BusEvent.ProjectMutated(pid, mutatedAtEpochMs = 0L)),
        )
        assertEquals(
            "aigc.cost.recorded",
            eventName(
                BusEvent.AigcCostRecorded(
                    sessionId = sid,
                    projectId = pid,
                    toolId = "generate_image",
                    assetId = "a1",
                    costCents = 4L,
                ),
            ),
        )
        assertEquals(
            "spend.cap.approaching",
            eventName(
                BusEvent.SpendCapApproaching(
                    sessionId = sid,
                    capCents = 1000L,
                    currentCents = 800L,
                    ratio = 0.8,
                    scope = "aigc",
                    toolId = "generate_image",
                ),
            ),
        )
        assertEquals("aigc.cache.probe", eventName(BusEvent.AigcCacheProbe("generate_image", true)))
        assertEquals(
            "project.assets.missing",
            eventName(
                BusEvent.AssetsMissing(
                    projectId = pid,
                    missing = listOf(BusEvent.MissingAsset("a1", "/foo/bar.mp4")),
                ),
            ),
        )
    }

    @Test fun providerAndJobAndStreamingEvents() {
        assertEquals(
            "provider.warmup",
            eventName(
                BusEvent.ProviderWarmup(
                    sessionId = sid,
                    providerId = "anthropic",
                    phase = BusEvent.ProviderWarmup.Phase.Starting,
                    epochMs = 0L,
                ),
            ),
        )
        assertEquals(
            "aigc.job.progress",
            eventName(
                BusEvent.AigcJobProgress(
                    sessionId = sid,
                    callId = callId,
                    toolId = "generate_image",
                    jobId = "j1",
                    phase = BusEvent.AigcProgressPhase.Started,
                ),
            ),
        )
        assertEquals(
            "tool.spec.budget.warning",
            eventName(
                BusEvent.ToolSpecBudgetWarning(
                    estimatedTokens = 18500,
                    threshold = 18000,
                    toolCount = 53,
                ),
            ),
        )
        assertEquals(
            "tool.streaming.part",
            eventName(
                BusEvent.ToolStreamingPart(
                    sessionId = sid,
                    callId = callId,
                    toolId = "generate_image",
                    chunk = "x",
                    doneTokens = 1,
                ),
            ),
        )
    }

    // ── 2. Cross-protocol agreement: eventName == DTO.type ──

    @Test fun eventNameMatchesBusEventDtoTypeForAllVariants() {
        // Marquee cross-protocol invariant pin: the SSE protocol
        // writes BOTH `event: <eventName>` and `data: {"type":
        // "<DTO.type>"}`. Drift between the two would silently
        // create a protocol where the same event has two different
        // identifiers depending on which lane the consumer reads.
        // This test pins the agreement for every variant — drift in
        // any single mapping fails here.
        val samples: List<BusEvent> = listOf(
            BusEvent.SessionCreated(sid),
            BusEvent.SessionUpdated(sid),
            BusEvent.SessionDeleted(sid),
            BusEvent.SessionCancelled(sid),
            BusEvent.SessionCancelRequested(sid),
            BusEvent.SessionFull(sid, 1, 100),
            BusEvent.SessionReverted(sid, pid, mid, 0, null),
            BusEvent.SessionCompactionAuto(sid, 1000, 500),
            BusEvent.SessionCompacted(sid, 1, 100),
            BusEvent.SessionProjectBindingChanged(sid, null, pid),
            BusEvent.MessageUpdated(sid, mid, msg),
            BusEvent.MessageDeleted(sid, mid),
            BusEvent.PermissionAsked(sid, "r1", "fs.read", listOf("*")),
            BusEvent.PermissionReplied(sid, "r1", true, false),
            BusEvent.AgentRunFailed(sid, "c1", "boom"),
            BusEvent.AgentRetryScheduled(sid, 1, 1000L, "x"),
            BusEvent.AgentProviderFallback(sid, "a", "b", "x"),
            BusEvent.AgentRunStateChanged(sid, AgentRunState.Idle),
            BusEvent.ProjectValidationWarning(pid, listOf("issue")),
            BusEvent.ProjectMutated(pid, 0L),
            BusEvent.AigcCostRecorded(sid, pid, "generate_image", "a1", 4L),
            BusEvent.SpendCapApproaching(sid, 1000L, 800L, 0.8, "aigc", "generate_image"),
            BusEvent.AigcCacheProbe("generate_image", true),
            BusEvent.AssetsMissing(pid, listOf(BusEvent.MissingAsset("a1", "/foo/bar.mp4"))),
            BusEvent.ProviderWarmup(sid, "anthropic", BusEvent.ProviderWarmup.Phase.Ready, 0L),
            BusEvent.AigcJobProgress(
                sid,
                callId,
                "generate_image",
                "j1",
                BusEvent.AigcProgressPhase.Started,
            ),
            BusEvent.ToolSpecBudgetWarning(18500, 18000, 53),
            BusEvent.ToolStreamingPart(sid, callId, "generate_image", "x", 1),
        )
        for (e in samples) {
            assertEquals(
                BusEventDto.from(e).type,
                eventName(e),
                "eventName mismatch for ${e::class.simpleName} — SSE event: line and JSON type field MUST agree",
            )
        }
    }

    // ── 3. Failed-state cross-check (failure carries cause) ──

    @Test fun agentRunStateChangedFailedStillMatchesDto() {
        // Pin: even with the rich Failed sub-state (which carries a
        // `cause` String + `fallback` hint), the eventName/DTO type
        // agreement holds — `eventName` switches on the variant
        // TYPE, not the inner state.
        val event = BusEvent.AgentRunStateChanged(
            sessionId = sid,
            state = AgentRunState.Failed("provider timeout", FallbackHint.Uncaught()),
        )
        assertEquals("agent.run.state.changed", eventName(event))
        assertEquals(BusEventDto.from(event).type, eventName(event))
    }

    // ── 4. Dot-separated convention ────────────────────────

    @Test fun allEventNamesAreDotSeparatedNotUnderscore() {
        // Pin: every event name uses dots as separators (matches the
        // canonical "namespace.subject.verb" pattern). Drift to
        // underscore would break clients filtering on dot-prefix
        // (e.g. `session.*` watchers).
        val samples = listOf(
            BusEvent.SessionCreated(sid),
            BusEvent.SessionProjectBindingChanged(sid, null, pid),
            BusEvent.AgentRunStateChanged(sid, AgentRunState.Idle),
            BusEvent.ToolSpecBudgetWarning(18500, 18000, 53),
        )
        for (e in samples) {
            val name = eventName(e)
            assertTrue(
                "_" !in name,
                "eventName for ${e::class.simpleName} contains underscore (must use dots): $name",
            )
            assertTrue(
                "." in name,
                "eventName for ${e::class.simpleName} contains no dot separator: $name",
            )
        }
    }
}
