package io.talevia.core.session

import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Direct tests for [Message] subtypes + [ModelRef] + [TokenUsage] +
 * [Cost] + [FinishReason] field defaults. Cycle 307 audit: no direct
 * `MessageFieldDefaults*Test.kt` / `TokenUsage*Test.kt` /
 * `CostTest.kt` / `ModelRefTest.kt` (verified via cycle 289-banked
 * duplicate-check idiom). Continues the field-defaults sprint
 * (cycles 302-306).
 *
 * Same audit-pattern fallback as cycles 207-306.
 *
 * Why this matters: these data classes are the persisted message
 * shape across every session. Drift in defaults silently shifts:
 *   - Cost / TokenUsage zero-baseline (every assistant message
 *     starts uncosted / zero-tokened).
 *   - FinishReason @SerialName wire strings (drift breaks bundle
 *     decode).
 *   - schemaVersion forward-compat discriminator.
 *
 * Drift surface protected:
 *   - **TokenUsage / Cost ZERO singletons** — drift to non-zero
 *     defaults silently fabricates numbers fleet-wide.
 *   - **FinishReason wire strings** ("end-turn" hyphen, "tool-calls"
 *     hyphen) — drift to underscore silently breaks legacy bundle
 *     decode.
 *   - **ModelRef.variant default null** — drift to "" silently
 *     disables variant routing.
 *   - **schemaVersion = MessageSchema.CURRENT default** — drift to
 *     a different default silently mis-tags every newly-written
 *     blob.
 */
class MessageFieldDefaultsTest {

    private val sid = SessionId("s1")
    private val mid = MessageId("m1")
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val anyModel = ModelRef("anthropic", "claude-opus-4-7")
    private val json: Json = JsonConfig.default

    // ── TokenUsage defaults + ZERO singleton ────────────────

    @Test fun tokenUsageDefaultConstructorIsAllZeros() {
        val tu = TokenUsage()
        assertEquals(0L, tu.input)
        assertEquals(0L, tu.output)
        assertEquals(0L, tu.reasoning)
        assertEquals(0L, tu.cacheRead)
        assertEquals(0L, tu.cacheWrite)
    }

    @Test fun tokenUsageZeroSingletonEqualsDefaultConstructor() {
        // Marquee singleton pin: TokenUsage.ZERO == TokenUsage().
        // Drift to a different ZERO would silently de-equate
        // the singleton from default-constructed instances.
        assertEquals(
            TokenUsage(),
            TokenUsage.ZERO,
            "TokenUsage.ZERO MUST equal TokenUsage() (default-construct singleton)",
        )
    }

    @Test fun tokenUsageRoundTripPreservesZeros() {
        val original = TokenUsage.ZERO
        val encoded = json.encodeToString(TokenUsage.serializer(), original)
        val decoded = json.decodeFromString(TokenUsage.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test fun tokenUsageWithExplicitValuesDistinctFromZero() {
        // Pin: any non-zero field produces a distinct
        // TokenUsage. Drift in equality semantics would
        // silently merge measured + zero entries.
        val nonzero = TokenUsage(input = 100L)
        assertNotEquals(TokenUsage.ZERO, nonzero)
    }

    // ── Cost defaults + ZERO singleton ──────────────────────

    @Test fun costDefaultConstructorIsZeroUsd() {
        assertEquals(0.0, Cost().usd)
    }

    @Test fun costZeroSingletonEqualsDefaultConstructor() {
        assertEquals(
            Cost(),
            Cost.ZERO,
            "Cost.ZERO MUST equal Cost() (default-construct singleton)",
        )
    }

    @Test fun costRoundTripPreservesZero() {
        val original = Cost.ZERO
        val encoded = json.encodeToString(Cost.serializer(), original)
        val decoded = json.decodeFromString(Cost.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // ── ModelRef defaults ──────────────────────────────────

    @Test fun modelRefVariantDefaultsToNull() {
        // Marquee variant pin: per source line 46, variant
        // is optional. Drift to "" silently disables variant
        // routing (most providers treat empty string as
        // "unspecified" while null is "use the model id
        // verbatim").
        val mr = ModelRef(providerId = "anthropic", modelId = "claude-opus")
        assertNull(
            mr.variant,
            "ModelRef.variant MUST default to null (NOT empty string)",
        )
    }

    @Test fun modelRefRoundTripPreservesDefaults() {
        val original = ModelRef("openai", "gpt-5.4-mini")
        val encoded = json.encodeToString(ModelRef.serializer(), original)
        val decoded = json.decodeFromString(ModelRef.serializer(), encoded)
        assertEquals(original, decoded)
        assertNull(decoded.variant)
    }

    // ── Message.Assistant defaults ──────────────────────────

    private fun assistantMsg(): Message.Assistant = Message.Assistant(
        id = mid,
        sessionId = sid,
        createdAt = now,
        parentId = MessageId("parent"),
        model = anyModel,
    )

    @Test fun assistantCostDefaultsToZero() {
        // Marquee uncosted-default pin: drift to non-zero
        // Cost would silently flag every fresh assistant
        // message as costed.
        assertEquals(
            Cost.ZERO,
            assistantMsg().cost,
            "Assistant.cost MUST default to Cost.ZERO (uncosted)",
        )
    }

    @Test fun assistantTokensDefaultsToZero() {
        assertEquals(
            TokenUsage.ZERO,
            assistantMsg().tokens,
            "Assistant.tokens MUST default to TokenUsage.ZERO",
        )
    }

    @Test fun assistantFinishDefaultsToNull() {
        // Pin: null = "not yet finished" (in-flight or
        // cancelled before stamp). Drift to STOP would
        // silently mark every fresh assistant message as
        // already-finished.
        assertNull(
            assistantMsg().finish,
            "Assistant.finish MUST default to null (in-flight)",
        )
    }

    @Test fun assistantErrorDefaultsToNull() {
        assertNull(assistantMsg().error)
    }

    @Test fun assistantSchemaVersionDefaultsToCurrent() {
        // Pin: per cycle 269's MessageSchema.CURRENT pin,
        // CURRENT = 1 (drift to 2+ silently re-tags every
        // newly-written blob).
        assertEquals(
            MessageSchema.CURRENT,
            assistantMsg().schemaVersion,
            "Assistant.schemaVersion MUST default to MessageSchema.CURRENT",
        )
    }

    // ── Message.User defaults ──────────────────────────────

    private fun userMsg(): Message.User = Message.User(
        id = mid,
        sessionId = sid,
        createdAt = now,
        agent = "primary",
        model = anyModel,
    )

    @Test fun userSchemaVersionDefaultsToCurrent() {
        assertEquals(MessageSchema.CURRENT, userMsg().schemaVersion)
    }

    // ── FinishReason wire-format strings ───────────────────

    @Test fun finishReasonHasExactlySevenVariants() {
        // Marquee enumeration pin: drift in count would
        // silently break the FinishReason sealed-when
        // exhaustive checks across UI / Compactor / preview
        // paths.
        assertEquals(
            7,
            FinishReason.entries.size,
            "FinishReason MUST have exactly 7 variants",
        )
    }

    @Test fun finishReasonSerialNamesUseHyphenNotUnderscore() {
        // Marquee wire-format pin: per source lines 67-73,
        // the @SerialName values use hyphens
        // ("end-turn", "max-tokens", "content-filter",
        // "tool-calls"). Drift to underscores silently
        // breaks legacy bundle decode.
        // Verify by round-trip: encode each variant, check
        // hyphen presence.
        val expectedWireStrings = mapOf(
            FinishReason.STOP to "stop",
            FinishReason.END_TURN to "end-turn",
            FinishReason.MAX_TOKENS to "max-tokens",
            FinishReason.CONTENT_FILTER to "content-filter",
            FinishReason.TOOL_CALLS to "tool-calls",
            FinishReason.ERROR to "error",
            FinishReason.CANCELLED to "cancelled",
        )
        for ((variant, expectedWire) in expectedWireStrings) {
            val encoded = json.encodeToString(FinishReason.serializer(), variant)
            // encoded is the JSON-quoted form e.g. "\"end-turn\""
            assertEquals(
                "\"$expectedWire\"",
                encoded,
                "FinishReason.$variant MUST encode as \"$expectedWire\" (drift breaks legacy bundle decode)",
            )
        }
    }

    @Test fun finishReasonRoundTripsViaSerialNames() {
        // Sister round-trip pin: every variant decodes from
        // its @SerialName.
        for (variant in FinishReason.entries) {
            val encoded = json.encodeToString(FinishReason.serializer(), variant)
            val decoded = json.decodeFromString(FinishReason.serializer(), encoded)
            assertEquals(variant, decoded)
        }
    }

    // ── Cost / TokenUsage encodeDefaults=false ─────────────

    @Test fun assistantMessageEncodeOmitsZeroCostAndTokens() {
        // Marquee back-compat pin: per JsonConfig.default,
        // encodeDefaults=false. Default Cost.ZERO + ZERO
        // tokens MUST be omitted from encoded JSON. Drift to
        // encode defaults silently bloats every assistant-
        // message row.
        val msg: Message = assistantMsg()
        val encoded = json.encodeToString(Message.serializer(), msg)
        // The default-construct ZERO Cost / TokenUsage
        // produce empty JSON objects; encodeDefaults=false
        // omits the field name entirely.
        assertTrue(
            "cost" !in encoded,
            "default Cost.ZERO MUST be omitted from encoded JSON",
        )
        assertTrue(
            "tokens" !in encoded,
            "default TokenUsage.ZERO MUST be omitted from encoded JSON",
        )
        assertTrue(
            "finish" !in encoded,
            "default null finish MUST be omitted from encoded JSON",
        )
        assertTrue(
            "error" !in encoded,
            "default null error MUST be omitted from encoded JSON",
        )
    }

    // ── Round-trip pins for Message subtypes ───────────────

    @Test fun userMessageRoundTripPreservesAllFields() {
        val original = userMsg()
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded) as Message.User
        assertEquals(original, decoded)
    }

    @Test fun assistantMessageRoundTripPreservesDefaults() {
        val original = assistantMsg()
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded) as Message.Assistant
        assertEquals(original, decoded)
        assertEquals(Cost.ZERO, decoded.cost)
        assertEquals(TokenUsage.ZERO, decoded.tokens)
        assertNull(decoded.finish)
    }

    @Test fun assistantMessageWithExplicitFieldsRoundTrips() {
        val original = Message.Assistant(
            id = mid,
            sessionId = sid,
            createdAt = now,
            parentId = MessageId("p"),
            model = anyModel,
            cost = Cost(usd = 0.05),
            tokens = TokenUsage(input = 100L, output = 200L, reasoning = 50L),
            finish = FinishReason.END_TURN,
            error = null,
        )
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded) as Message.Assistant
        assertEquals(original, decoded)
    }

    // ── Equality / null distinctions ───────────────────────

    @Test fun finishNullVsStopAreSemanticallyDistinct() {
        // Pin: null finish (in-flight) MUST be distinct from
        // STOP finish (terminated). Drift to coalesce would
        // silently merge in-flight and finished states.
        val inflight = assistantMsg()
        val stopped = assistantMsg().copy(finish = FinishReason.STOP)
        assertNull(inflight.finish)
        assertEquals(FinishReason.STOP, stopped.finish)
        assertNotEquals(inflight, stopped)
    }

    @Test fun tokenUsageZeroIsTheSameSingletonEveryTime() {
        // Pin: TokenUsage.ZERO is a stable singleton (val
        // companion). Drift to a fresh-instance-per-call
        // would silently break referential identity in code
        // that compares via `===`.
        assertSame(TokenUsage.ZERO, TokenUsage.ZERO)
        assertSame(Cost.ZERO, Cost.ZERO)
    }
}
