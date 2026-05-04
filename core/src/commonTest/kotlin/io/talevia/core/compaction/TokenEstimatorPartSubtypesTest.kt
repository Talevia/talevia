package io.talevia.core.compaction

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Part
import io.talevia.core.session.PlanApprovalStatus
import io.talevia.core.session.PlanStep
import io.talevia.core.session.TodoInfo
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Direct per-Part-subtype tests for [TokenEstimator] —
 * `core/src/commonMain/kotlin/io/talevia/core/compaction/TokenEstimator.kt:22`.
 * Cycle 287 audit: existing
 * `commonTest/kotlin/io/talevia/core/compaction/TokenEstimatorTest.kt`
 * pins ONLY 3 cases (all via Part.Media → forMedia clamp);
 * per-Part-subtype constants and the full formula surface are
 * unpinned.
 *
 * Same audit-pattern fallback as cycles 207-286. Pivoted from the
 * 6-cycle prompt-content family that retired cycle 286.
 *
 * `TokenEstimator` is the heuristic backing every "should we
 * compact?" decision (`CompactionGate.maybeCompact` /
 * `Agent.runLoop` per-turn budget check) and every visible
 * session-token UI surface. Drift in any of the per-Part
 * constants silently shifts compaction trigger timing across
 * the entire fleet. Drift in the char-per-4 round-up formula
 * shifts every text-bearing part. Drift in the
 * ToolState.Completed `estimatedTokens` preference drops
 * tool-author estimates.
 *
 * Drift signals:
 *   - **Constant drift (16→8 / 24→32 / 4→8)** silently shifts
 *     compaction cadence; every fleet-level token estimate
 *     moves in lockstep with no visible signal.
 *   - **Drop the `estimatedTokens ?: byte-sum` preference on
 *     ToolState.Completed** → all stamped tool results regress
 *     to byte-length heuristic (project_query rows × 50 etc.
 *     get re-estimated as raw output text length).
 *   - **Drift in the char/4 round-up integer math** (e.g.
 *     `length / 4` vs `(length + 3) / 4`) silently rounds
 *     down instead of up, under-estimating every text part.
 *
 * Pins formulas + per-subtype constants. Uses Part-construction
 * fixtures (helper `tool(...)` / `media(...)` / etc.) so the
 * test file stays compact.
 */
class TokenEstimatorPartSubtypesTest {

    private val sid = SessionId("s1")
    private val mid = MessageId("m1")
    private val cid = CallId("c1")
    private val pid = PartId("p1")
    private val now = Instant.fromEpochMilliseconds(0)

    private fun tool(state: ToolState): Part.Tool = Part.Tool(
        id = pid, messageId = mid, sessionId = sid, createdAt = now,
        callId = cid, toolId = "any_tool", state = state,
    )

    private fun reasoning(text: String): Part.Reasoning = Part.Reasoning(
        id = pid, messageId = mid, sessionId = sid, createdAt = now,
        text = text,
    )

    private fun renderProgress(): Part.RenderProgress = Part.RenderProgress(
        id = pid, messageId = mid, sessionId = sid, createdAt = now,
        jobId = "j1", ratio = 0.5f,
    )

    private fun stepStart(): Part.StepStart = Part.StepStart(
        id = pid, messageId = mid, sessionId = sid, createdAt = now,
    )

    private fun stepFinish(): Part.StepFinish = Part.StepFinish(
        id = pid, messageId = mid, sessionId = sid, createdAt = now,
        tokens = TokenUsage(input = 0, output = 0, reasoning = 0, cacheRead = 0, cacheWrite = 0),
        finish = FinishReason.END_TURN,
    )

    private fun compaction(summary: String): Part.Compaction = Part.Compaction(
        id = pid, messageId = mid, sessionId = sid, createdAt = now,
        replacedFromMessageId = mid, replacedToMessageId = mid,
        summary = summary,
    )

    private fun todos(items: List<TodoInfo>): Part.Todos = Part.Todos(
        id = pid, messageId = mid, sessionId = sid, createdAt = now,
        todos = items,
    )

    private fun plan(goal: String, steps: List<PlanStep>): Part.Plan = Part.Plan(
        id = pid, messageId = mid, sessionId = sid, createdAt = now,
        goalDescription = goal, steps = steps,
        approvalStatus = PlanApprovalStatus.PENDING_APPROVAL,
    )

    // ── forText: (length + 3) / 4 round-up ──────────────────

    @Test fun forTextEmptyStringIsZero() {
        // Pin: 0/4 round-up = 0. Drift to floor-only would
        // surface here.
        assertEquals(0, TokenEstimator.forText(""))
    }

    @Test fun forTextRoundsUpNotDown() {
        // Marquee round-up pin: drift to integer-divide
        // floor (length / 4) would surface as 1-char → 0
        // tokens, 5-char → 1 token instead of 2.
        assertEquals(1, TokenEstimator.forText("a"), "1 char MUST round up to 1 token")
        assertEquals(1, TokenEstimator.forText("abc"), "3 chars MUST round up to 1 token")
        assertEquals(1, TokenEstimator.forText("abcd"), "4 chars MUST be exactly 1 token")
        assertEquals(2, TokenEstimator.forText("abcde"), "5 chars MUST round up to 2 tokens")
        assertEquals(2, TokenEstimator.forText("12345678"), "8 chars MUST be exactly 2 tokens")
        assertEquals(3, TokenEstimator.forText("123456789"), "9 chars MUST round up to 3 tokens")
    }

    @Test fun forTextScalesLinearly() {
        // Pin: 100 chars = 25 tokens (100/4); 4000 = 1000.
        // Drift to a different ratio would surface here.
        assertEquals(25, TokenEstimator.forText("x".repeat(100)))
        assertEquals(250, TokenEstimator.forText("x".repeat(1000)))
        assertEquals(1_000, TokenEstimator.forText("x".repeat(4_000)))
    }

    // ── forJson: forText(element.toString()) pipeline ──────

    @Test fun forJsonDelegatesToForTextOnSerialised() {
        // Pin: forJson serialises via toString() then runs
        // forText. Drift to a different serialiser (e.g.
        // Json.encodeToString) would surface as different
        // counts on the same input.
        val json = buildJsonObject { put("k", "v") } // {"k":"v"} = 9 chars
        // 9 chars → (9+3)/4 = 3 tokens. JsonObject toString
        // matches the canonical compact form.
        assertEquals(3, TokenEstimator.forJson(json))
        assertEquals(TokenEstimator.forText(json.toString()), TokenEstimator.forJson(json))
    }

    @Test fun forJsonHandlesPrimitivesAndNull() {
        // JsonNull.toString() = "null" (4 chars) → 1 token.
        assertEquals(1, TokenEstimator.forJson(JsonNull))
        // JsonPrimitive(42).toString() = "42" (2 chars) →
        // 1 token.
        assertEquals(1, TokenEstimator.forJson(JsonPrimitive(42)))
    }

    // ── ToolState constants ─────────────────────────────────

    @Test fun toolPendingIsExactlySixteen() {
        // Marquee constant pin: drift to any other value
        // shifts every Pending tool part's token cost.
        assertEquals(16, TokenEstimator.forPart(tool(ToolState.Pending)))
    }

    @Test fun toolRunningIsTwentyFourPlusInputJsonTokens() {
        // Marquee formula pin: 24 + forJson(input).
        val input = buildJsonObject { put("k", "v") } // 3 tokens (above)
        val part = tool(ToolState.Running(input = input))
        assertEquals(
            24 + TokenEstimator.forJson(input),
            TokenEstimator.forPart(part),
            "Running MUST be 24 + forJson(input)",
        )
    }

    @Test fun toolCompletedPrefersStampedEstimateWhenNonNull() {
        // Marquee preference pin: ToolState.Completed reads
        // `estimatedTokens` first; falls back to byte-sum
        // formula only when null.
        // Drift to drop the preference would silently
        // re-estimate every project_query row × 50 stamped
        // tool result as raw text length.
        val input = buildJsonObject { put("query", "lots") }
        val output = "x".repeat(400) // 100 raw text tokens
        val data = buildJsonObject { put("k", "v") }

        // With stamped estimate=99, the helper MUST return
        // 99 verbatim — NOT recompute the byte-sum.
        val stamped = tool(
            ToolState.Completed(input, output, data, estimatedTokens = 99),
        )
        assertEquals(99, TokenEstimator.forPart(stamped))

        // Same shape but null estimate → falls back to
        // byte-sum 24 + forJson(input) + forText(output) +
        // forJson(data).
        val unstamped = tool(
            ToolState.Completed(input, output, data, estimatedTokens = null),
        )
        val expected = 24 +
            TokenEstimator.forJson(input) +
            TokenEstimator.forText(output) +
            TokenEstimator.forJson(data)
        assertEquals(expected, TokenEstimator.forPart(unstamped))
    }

    @Test fun toolFailedFormulaWithAndWithoutInput() {
        // Pin: 24 + forJson(input?) + forText(message).
        // input? null contributes 0.
        val msg = "boom"
        val withInput = tool(
            ToolState.Failed(input = buildJsonObject { put("k", "v") }, message = msg),
        )
        assertEquals(
            24 + TokenEstimator.forJson(buildJsonObject { put("k", "v") }) +
                TokenEstimator.forText(msg),
            TokenEstimator.forPart(withInput),
        )

        val withoutInput = tool(ToolState.Failed(input = null, message = msg))
        assertEquals(
            24 + TokenEstimator.forText(msg),
            TokenEstimator.forPart(withoutInput),
            "Failed with input=null MUST contribute 0 from the input slot",
        )
    }

    @Test fun toolCancelledFormulaWithAndWithoutInput() {
        // Sister formula pin to Failed (same shape, distinct
        // ToolState branch).
        val msg = "user cancel"
        val withInput = tool(
            ToolState.Cancelled(input = buildJsonObject { put("k", "v") }, message = msg),
        )
        assertEquals(
            24 + TokenEstimator.forJson(buildJsonObject { put("k", "v") }) +
                TokenEstimator.forText(msg),
            TokenEstimator.forPart(withInput),
        )

        val withoutInput = tool(ToolState.Cancelled(input = null, message = msg))
        assertEquals(
            24 + TokenEstimator.forText(msg),
            TokenEstimator.forPart(withoutInput),
        )
    }

    // ── Per-non-tool-Part-subtype constants ─────────────────

    @Test fun reasoningDelegatesToForText() {
        // Pin: Reasoning matches forText(text).
        assertEquals(
            TokenEstimator.forText("hello world"),
            TokenEstimator.forPart(reasoning("hello world")),
        )
    }

    @Test fun renderProgressIsExactlyEight() {
        // Marquee constant pin: drift would shift every
        // streamed render-progress part.
        assertEquals(8, TokenEstimator.forPart(renderProgress()))
    }

    @Test fun stepStartIsExactlyFour() {
        // Marquee constant pin: smallest part type — drift
        // away from 4 surfaces here.
        assertEquals(4, TokenEstimator.forPart(stepStart()))
    }

    @Test fun stepFinishIsExactlyEight() {
        // Marquee constant pin: drift to a different value
        // shifts every turn-boundary's token cost.
        assertEquals(8, TokenEstimator.forPart(stepFinish()))
    }

    @Test fun compactionDelegatesToForTextOnSummary() {
        // Pin: Compaction matches forText(summary). Drift
        // would silently inflate / deflate the surviving
        // summary's token cost in the post-compact history.
        val summary = "x".repeat(80) // 20 tokens
        assertEquals(20, TokenEstimator.forPart(compaction(summary)))
    }

    // ── Todos formula: 16 + sum(8 + forText(content)) ──────

    @Test fun todosFormulaWithEmptyAndNonEmpty() {
        // Marquee formula pin: 16 + per-todo overhead 8 +
        // forText(content). Drift in either constant shifts
        // todowrite cost.
        // Empty list → 16 base only.
        assertEquals(16, TokenEstimator.forPart(todos(emptyList())))

        // Two todos: 16 + 2*(8 + forText(content)).
        val items = listOf(
            TodoInfo(content = "a"),         // 8 + 1
            TodoInfo(content = "x".repeat(20)), // 8 + 5
        )
        val expected = 16 + (8 + 1) + (8 + 5)
        assertEquals(expected, TokenEstimator.forPart(todos(items)))
    }

    // ── Plan formula: 16 + forText(goal) + sum(12 + ...) ────

    @Test fun planFormulaWithEmptyAndNonEmptySteps() {
        // Marquee formula pin: 16 + forText(goal) + per-step
        // (12 + forText(toolName) + forText(inputSummary)).
        // Empty steps:
        val emptyPlan = plan(goal = "x".repeat(40), steps = emptyList())
        // 16 + 10 + 0 = 26 (40/4 = 10).
        assertEquals(16 + 10, TokenEstimator.forPart(emptyPlan))

        // Two steps with sentinel content:
        val steps = listOf(
            PlanStep(step = 1, toolName = "a", inputSummary = "b"),  // 12 + 1 + 1 = 14
            PlanStep(step = 2, toolName = "ab", inputSummary = "cd"), // 12 + 1 + 1 = 14
        )
        val nonEmpty = plan(goal = "g", steps = steps) // 16 + 1 = 17 base
        val expected = 16 + 1 + 14 + 14
        assertEquals(expected, TokenEstimator.forPart(nonEmpty))
    }

    // ── Media clamp via forPart(Part.Media) ─────────────────
    //   (existing TokenEstimatorTest covers default+1080p+8K
    //    via forPart; this pins the lower clamp + a mid-range
    //    point that existing tests miss.)

    @Test fun tinyImageClampsUpToDefaultImageBudget() {
        // Pin: 100x100 = 13 tokens raw; clamped UP to
        // DEFAULT_IMAGE_TOKENS=1568. Existing tests cover the
        // resolution=null fallback but not the small-image
        // clamp-up path explicitly.
        val tiny = MediaAsset(
            id = AssetId("a1"),
            source = MediaSource.File("/tmp/tiny.png"),
            metadata = MediaMetadata(duration = 1.seconds, resolution = Resolution(100, 100)),
        )
        val part = Part.Media(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            assetId = tiny.id,
        )
        assertEquals(
            1568,
            TokenEstimator.forPart(part) { tiny },
            "100x100 (13 raw) MUST clamp UP to DEFAULT_IMAGE_TOKENS=1568",
        )
    }

    @Test fun midRangeImageMatchesAnthropicFormulaExactly() {
        // Pin: 1280x720 = 1228 raw → clamp range
        // [1568, 6144] applies → 1568. The 1080p case in the
        // existing test (1920x1080 = 2764) sits within the
        // range; this pin captures the boundary clamp from
        // the LOW side.
        val asset = MediaAsset(
            id = AssetId("a1"),
            source = MediaSource.File("/tmp/720p.png"),
            metadata = MediaMetadata(duration = 1.seconds, resolution = Resolution(1280, 720)),
        )
        val part = Part.Media(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            assetId = asset.id,
        )
        // 1280 * 720 / 750 = 1228 → clamped UP to 1568.
        assertEquals(
            1568,
            TokenEstimator.forPart(part) { asset },
            "1280x720 (1228 raw) MUST clamp UP to 1568 (below DEFAULT)",
        )
    }

    @Test fun largeImageMatchesFormulaWithinBand() {
        // Pin: 2560x1440 = 4915 raw → in-band, returned
        // verbatim. Closes the gap between the existing
        // 1080p in-band case and 8K cap case.
        val asset = MediaAsset(
            id = AssetId("a1"),
            source = MediaSource.File("/tmp/1440p.png"),
            metadata = MediaMetadata(duration = 1.seconds, resolution = Resolution(2560, 1440)),
        )
        val part = Part.Media(
            id = pid, messageId = mid, sessionId = sid, createdAt = now,
            assetId = asset.id,
        )
        // 2560 * 1440 / 750 = 4915 → in-band [1568, 6144].
        assertEquals(4_915, TokenEstimator.forPart(part) { asset })
    }

    // ── forHistory: per-message sum across parts ────────────

    @Test fun forHistorySumsPartsAcrossMessages() {
        // Pin: forHistory delegates to per-part sum across
        // every message's parts. Drift to a different
        // composition would silently break compaction-budget
        // calculation.
        val anyModel = io.talevia.core.session.ModelRef(providerId = "anthropic", modelId = "claude-opus-4-7")
        val mwp1 = io.talevia.core.session.MessageWithParts(
            message = io.talevia.core.session.Message.User(
                id = mid,
                sessionId = sid,
                createdAt = now,
                agent = "primary",
                model = anyModel,
            ),
            parts = listOf(stepStart(), stepFinish()), // 4 + 8 = 12
        )
        val mwp2 = io.talevia.core.session.MessageWithParts(
            message = io.talevia.core.session.Message.User(
                id = MessageId("m2"),
                sessionId = sid,
                createdAt = now,
                agent = "primary",
                model = anyModel,
            ),
            parts = listOf(reasoning("abcd")), // forText("abcd") = 1
        )
        // Total = 4 + 8 + 1 = 13.
        assertEquals(
            13,
            TokenEstimator.forHistory(listOf(mwp1, mwp2)),
        )
    }

    @Test fun forHistoryEmptyIsZero() {
        // Edge: empty history is 0 tokens — drift to
        // anything else surfaces the no-base-cost invariant.
        assertEquals(0, TokenEstimator.forHistory(emptyList()))
    }
}
