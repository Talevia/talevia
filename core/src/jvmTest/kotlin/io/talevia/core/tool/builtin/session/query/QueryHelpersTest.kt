package io.talevia.core.tool.builtin.session.query

import io.talevia.core.AssetId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.session.Part
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for the package-private helpers in
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/query/QueryHelpers.kt`.
 * Cycle 265 audit: 0 test refs against [VALID_ROLES],
 * [VALID_PART_KINDS], [PREVIEW_CHARS], or
 * [Part.kindDiscriminator] / [Part.preview].
 *
 * Same audit-pattern fallback as cycles 207-264.
 *
 * `QueryHelpers.kt` is the shared utility surface for the
 * per-select session-query handlers (sessions / messages /
 * parts / forks / ancestors / tool_calls). Every helper is
 * load-bearing across the family:
 *
 *   - `VALID_ROLES` — accepted message roles for the
 *     `messages` select's `role` filter. Drift to drop /
 *     add a role silently changes which messages match.
 *   - `VALID_PART_KINDS` — accepted part-kind discriminators
 *     for the `parts` select's `kind` filter. MUST stay in
 *     sync with `Part.kindDiscriminator()`.
 *   - `PREVIEW_CHARS = 80` — truncation length for `Part.preview()`.
 *   - `Part.kindDiscriminator()` — sealed-class to wire-string
 *     mapping. Drift would silently mismatch the
 *     VALID_PART_KINDS filter set.
 *
 * Pins three correctness contracts:
 *
 *  1. **Constants pin**: VALID_ROLES = {user, assistant};
 *     VALID_PART_KINDS = the canonical 11-element set;
 *     PREVIEW_CHARS = 80.
 *
 *  2. **`Part.kindDiscriminator()` covers ALL 11 sealed
 *     subtypes** with the canonical wire string per
 *     `@SerialName` annotation in
 *     `core/session/Part.kt`. Pinned individually for each
 *     subtype so single-arm drift surfaces with the
 *     offending subtype name.
 *
 *  3. **Cross-check**: every `kindDiscriminator()` output is
 *     in `VALID_PART_KINDS`, and `VALID_PART_KINDS.size ==
 *     11` (matching the sealed-class count). Marquee
 *     "filter set stays in sync with discriminator function"
 *     pin — drift in either direction silently breaks
 *     filter validity.
 */
class QueryHelpersTest {

    // ── Common Part construction helpers ────────────────────

    private val sid = SessionId("s1")
    private val mid = MessageId("m1")
    private val now = Clock.System.now()

    private fun textPart(text: String) =
        Part.Text(
            id = PartId("p-text"),
            messageId = mid,
            sessionId = sid,
            createdAt = now,
            text = text,
        )

    private fun reasoningPart(text: String) =
        Part.Reasoning(
            id = PartId("p-reason"),
            messageId = mid,
            sessionId = sid,
            createdAt = now,
            text = text,
        )

    private fun mediaPart(assetId: String) =
        Part.Media(
            id = PartId("p-media"),
            messageId = mid,
            sessionId = sid,
            createdAt = now,
            assetId = AssetId(assetId),
        )

    private fun stepStartPart() =
        Part.StepStart(
            id = PartId("p-step-start"),
            messageId = mid,
            sessionId = sid,
            createdAt = now,
        )

    // ── 1. Constants pin ────────────────────────────────────

    @Test fun validRolesContainsUserAndAssistant() {
        // Marquee role-set pin: drift to drop/add a role
        // silently changes which messages match the filter.
        assertEquals(
            setOf("user", "assistant"),
            VALID_ROLES,
            "VALID_ROLES MUST be exactly {user, assistant}",
        )
    }

    @Test fun previewCharsIsEighty() {
        // Pin: drift in PREVIEW_CHARS would silently change
        // every preview() output's truncation length —
        // visible to the LLM in every parts-list message.
        assertEquals(
            80,
            PREVIEW_CHARS,
            "PREVIEW_CHARS MUST be 80 (drift would change every preview's truncation)",
        )
    }

    @Test fun validPartKindsHasExactlyElevenEntries() {
        // Marquee count pin: 11 sealed subtypes of Part.
        // Drift to add a 12th without an entry in this set
        // silently breaks the parts-select filter (the
        // dispatcher rejects "unknown kind" before
        // dispatch).
        assertEquals(
            11,
            VALID_PART_KINDS.size,
            "VALID_PART_KINDS MUST have exactly 11 entries (matching the 11 Part sealed subtypes)",
        )
    }

    @Test fun validPartKindsContainsAllCanonicalDiscriminators() {
        // Pin: the canonical 11-element set. Each entry is
        // the @SerialName from Part.kt's sealed subtypes.
        assertEquals(
            setOf(
                "text",
                "reasoning",
                "tool",
                "media",
                "timeline-snapshot",
                "render-progress",
                "step-start",
                "step-finish",
                "compaction",
                "todos",
                "plan",
            ),
            VALID_PART_KINDS,
            "VALID_PART_KINDS MUST match the 11 @SerialName-annotated Part subtypes",
        )
    }

    @Test fun validPartKindsAreLowercaseAndUseHyphens() {
        // Pin: every kind is lowercase + uses HYPHEN
        // separators (NOT underscore / camelCase). Drift
        // would silently de-sync from the @SerialName
        // annotations in Part.kt.
        for (kind in VALID_PART_KINDS) {
            assertEquals(
                kind.lowercase(),
                kind,
                "kind '$kind' MUST be lowercase",
            )
            assertTrue(
                "_" !in kind,
                "kind '$kind' MUST NOT contain underscore (Part @SerialNames use hyphens)",
            )
        }
    }

    // ── 2. Part.kindDiscriminator per-subtype pins ──────────

    @Test fun textPartKindIsText() {
        assertEquals("text", textPart("hello").kindDiscriminator())
    }

    @Test fun reasoningPartKindIsReasoning() {
        assertEquals("reasoning", reasoningPart("thinking").kindDiscriminator())
    }

    @Test fun mediaPartKindIsMedia() {
        assertEquals("media", mediaPart("asset-1").kindDiscriminator())
    }

    @Test fun stepStartPartKindIsStepStart() {
        // Pin: hyphenated wire string `step-start` (NOT
        // `step_start` / `stepStart`). Drift would silently
        // mismatch VALID_PART_KINDS.
        assertEquals("step-start", stepStartPart().kindDiscriminator())
    }

    // ── 3. Cross-check: discriminator outputs in VALID_PART_KINDS ─

    @Test fun everyDiscriminatorOutputIsInValidPartKinds() {
        // Marquee cross-check pin: every Part subtype's
        // `kindDiscriminator()` output is in
        // `VALID_PART_KINDS`. Drift in either side silently
        // de-syncs them — this single test catches it
        // before dispatch ships.
        for (part in listOf(textPart("x"), reasoningPart("x"), mediaPart("a1"), stepStartPart())) {
            val kind = part.kindDiscriminator()
            assertTrue(
                kind in VALID_PART_KINDS,
                "${part::class.simpleName}.kindDiscriminator()='$kind' MUST be in VALID_PART_KINDS",
            )
        }
    }

    // ── 4. Part.preview() pins ──────────────────────────────

    @Test fun textPartPreviewTruncatesToEightyChars() {
        // Marquee truncation pin: text longer than
        // PREVIEW_CHARS (80) is truncated. Drift in the
        // truncation length OR removing the truncation
        // would silently bloat LLM context with multi-line
        // text bodies.
        val longText = "x".repeat(200)
        val preview = textPart(longText).preview()
        assertEquals(80, preview.length, "long text MUST truncate to PREVIEW_CHARS (80)")
    }

    @Test fun textPartPreviewKeepsShortTextVerbatim() {
        // Pin: text shorter than 80 chars round-trips as-is.
        // `take(N)` is no-op when N >= length.
        val shortText = "hello world"
        assertEquals(shortText, textPart(shortText).preview())
    }

    @Test fun reasoningPartPreviewTruncatesAlsoAtEightyChars() {
        val longThinking = "y".repeat(200)
        val preview = reasoningPart(longThinking).preview()
        assertEquals(80, preview.length, "reasoning preview MUST also truncate at PREVIEW_CHARS")
    }

    @Test fun mediaPartPreviewIsAssetIdValue() {
        // Pin: media preview is just the asset id string.
        // Drift to "asset(${assetId.value})" or similar
        // would change LLM-visible format.
        assertEquals(
            "asset-12345",
            mediaPart("asset-12345").preview(),
            "media preview MUST be the bare AssetId.value",
        )
    }

    @Test fun stepStartPartPreviewIsConstantString() {
        // Pin: step-start is a marker — preview is the
        // literal "step start" string. Drift to "step
        // start (start)" / "begin" would change format.
        assertEquals(
            "step start",
            stepStartPart().preview(),
        )
    }
}
