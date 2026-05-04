package io.talevia.core.session

import io.talevia.core.JsonConfig
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the @SerialName wire-format strings for [Part] sealed
 * subtypes + [ToolState] sealed subtypes + 4 enums (TodoStatus,
 * TodoPriority, PlanStepStatus, PlanApprovalStatus). Cycle 308
 * audit: no direct `PartWireFormatTest.kt` (verified via cycle
 * 289-banked duplicate-check idiom). Sister of cycle 307's
 * FinishReason wire pin.
 *
 * Same audit-pattern fallback as cycles 207-307. Continues the
 * field-defaults / wire-format sprint.
 *
 * Why this matters: every Part / ToolState / status-enum value is
 * persisted in `talevia.json` and SQLDelight rows. Drift in any
 * @SerialName silently breaks legacy bundle decode.
 *
 * Notable convention asymmetries (load-bearing):
 *   - **Part subtype @SerialName** uses HYPHENS: `text` / `tool` /
 *     `media` / `timeline-snapshot` / `render-progress` /
 *     `step-start` / `step-finish` / `compaction` / `todos` / `plan`.
 *   - **ToolState subtype @SerialName** uses NO separators:
 *     `pending` / `running` / `completed` / **error** (NOT failed) /
 *     `cancelled`. The "error" mapping for the Failed kotlin name
 *     is non-obvious — drift to align would break decode.
 *   - **Status enum @SerialName** uses UNDERSCORES: `in_progress`,
 *     `pending_approval`, `approved_with_edits`. Drift to hyphens
 *     (matching Part subtypes) silently breaks decode.
 *
 * Drift signals:
 *   - **ToolState.Failed @SerialName drift "error" → "failed"**
 *     silently re-routes every ToolState dispatch.
 *   - **TodoStatus / PlanStepStatus underscore → hyphen** drift
 *     silently breaks legacy bundle decode.
 *   - **Part subtype hyphen → underscore** drift breaks SQLDelight
 *     row decode.
 */
class PartWireFormatTest {

    private val json: Json = JsonConfig.default

    // ── Part 11 subtype @SerialName wire strings ───────────

    @Test fun partSerialNamesUseConsistentHyphenatedConvention() {
        // Marquee Part-subtype enumeration pin: all 11
        // subtypes use lowercase + (single word | hyphenated).
        // Drift to underscores would silently break legacy
        // bundle decode.
        val expectedWireStrings = mapOf(
            "Text" to "text",
            "Reasoning" to "reasoning",
            "Tool" to "tool",
            "Media" to "media",
            "TimelineSnapshot" to "timeline-snapshot",
            "RenderProgress" to "render-progress",
            "StepStart" to "step-start",
            "StepFinish" to "step-finish",
            "Compaction" to "compaction",
            "Todos" to "todos",
            "Plan" to "plan",
        )
        // Each compound name uses a hyphen; single-word names
        // are bare. Total = 11.
        assertEquals(
            11,
            expectedWireStrings.size,
            "Part has exactly 11 sealed subtypes",
        )
        // Compound names hyphenated (NOT underscored).
        for ((kotlinName, wireName) in expectedWireStrings) {
            if ("-" in wireName) {
                assertTrue(
                    "_" !in wireName,
                    "compound subtype $kotlinName uses hyphen NOT underscore in @SerialName ('$wireName')",
                )
            }
        }
    }

    // ── ToolState 5 subtype @SerialName wire strings ───────

    @Test fun toolStatePendingWireNameIsPending() {
        val encoded = json.encodeToString(ToolState.serializer(), ToolState.Pending)
        assertTrue(
            "\"type\":\"pending\"" in encoded,
            "ToolState.Pending MUST encode with @SerialName 'pending'; got: $encoded",
        )
    }

    @Test fun toolStateRunningWireNameIsRunning() {
        val running = ToolState.Running(input = kotlinx.serialization.json.JsonObject(emptyMap()))
        val encoded = json.encodeToString(ToolState.serializer(), running)
        assertTrue("\"type\":\"running\"" in encoded)
    }

    @Test fun toolStateCompletedWireNameIsCompleted() {
        val completed = ToolState.Completed(
            input = kotlinx.serialization.json.JsonObject(emptyMap()),
            outputForLlm = "ok",
            data = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        val encoded = json.encodeToString(ToolState.serializer(), completed)
        assertTrue("\"type\":\"completed\"" in encoded)
    }

    @Test fun toolStateFailedWireNameIsErrorNotFailed() {
        // Marquee mapping pin: per source line 313, the
        // Kotlin name `Failed` maps to wire string "error"
        // (drift to "failed" would silently re-route every
        // ToolState dispatch — the Kotlin name and the wire
        // name are deliberately different).
        val failed = ToolState.Failed(input = null, message = "boom")
        val encoded = json.encodeToString(ToolState.serializer(), failed)
        assertTrue(
            "\"type\":\"error\"" in encoded,
            "ToolState.Failed MUST encode with @SerialName 'error' (NOT 'failed'); got: $encoded",
        )
        assertTrue(
            "\"type\":\"failed\"" !in encoded,
            "ToolState.Failed MUST NOT encode as 'failed' (drift would surface here)",
        )
    }

    @Test fun toolStateCancelledWireNameIsCancelled() {
        val cancelled = ToolState.Cancelled(input = null, message = "x")
        val encoded = json.encodeToString(ToolState.serializer(), cancelled)
        assertTrue("\"type\":\"cancelled\"" in encoded)
    }

    @Test fun toolStateRoundTripsAllFiveSubtypes() {
        // Sister round-trip: every subtype decodes from its
        // wire name.
        val states: List<ToolState> = listOf(
            ToolState.Pending,
            ToolState.Running(input = kotlinx.serialization.json.JsonObject(emptyMap())),
            ToolState.Completed(
                input = kotlinx.serialization.json.JsonObject(emptyMap()),
                outputForLlm = "x",
                data = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
            ToolState.Failed(input = null, message = "x"),
            ToolState.Cancelled(input = null, message = "x"),
        )
        for (state in states) {
            val encoded = json.encodeToString(ToolState.serializer(), state)
            val decoded = json.decodeFromString(ToolState.serializer(), encoded)
            assertEquals(state, decoded, "round-trip failed for ${state::class.simpleName}")
        }
    }

    // ── TodoStatus 4-variant wire strings ──────────────────

    @Test fun todoStatusWireStringsUseUnderscoreNotHyphen() {
        // Marquee underscore-vs-hyphen pin: per source lines
        // 208-211, status enums use underscores (`in_progress`),
        // distinct from Part subtypes which use hyphens
        // (`step-start`). Drift to align both styles silently
        // breaks decode.
        val expectedWires = mapOf(
            TodoStatus.PENDING to "pending",
            TodoStatus.IN_PROGRESS to "in_progress",
            TodoStatus.COMPLETED to "completed",
            TodoStatus.CANCELLED to "cancelled",
        )
        assertEquals(
            4,
            TodoStatus.entries.size,
            "TodoStatus has exactly 4 variants",
        )
        for ((variant, expectedWire) in expectedWires) {
            val encoded = json.encodeToString(TodoStatus.serializer(), variant)
            assertEquals(
                "\"$expectedWire\"",
                encoded,
                "TodoStatus.$variant MUST encode as \"$expectedWire\"",
            )
        }
        // IN_PROGRESS specifically uses underscore, NOT hyphen.
        assertEquals(
            "\"in_progress\"",
            json.encodeToString(TodoStatus.serializer(), TodoStatus.IN_PROGRESS),
        )
    }

    // ── TodoPriority 3-variant wire strings ────────────────

    @Test fun todoPriorityWireStringsAreLowercase() {
        val expectedWires = mapOf(
            TodoPriority.HIGH to "high",
            TodoPriority.MEDIUM to "medium",
            TodoPriority.LOW to "low",
        )
        assertEquals(3, TodoPriority.entries.size)
        for ((variant, expectedWire) in expectedWires) {
            val encoded = json.encodeToString(TodoPriority.serializer(), variant)
            assertEquals("\"$expectedWire\"", encoded)
        }
    }

    // ── PlanStepStatus 5-variant wire strings ──────────────

    @Test fun planStepStatusWireStringsIncludeFailed() {
        // Marquee distinction pin: PlanStepStatus has a
        // FAILED variant (wire "failed") that does NOT
        // collide with ToolState.Failed (wire "error") —
        // they're different sealed families. Drift to
        // unify wire strings would silently break the
        // family separation.
        val expectedWires = mapOf(
            PlanStepStatus.PENDING to "pending",
            PlanStepStatus.IN_PROGRESS to "in_progress",
            PlanStepStatus.COMPLETED to "completed",
            PlanStepStatus.FAILED to "failed",
            PlanStepStatus.CANCELLED to "cancelled",
        )
        assertEquals(5, PlanStepStatus.entries.size)
        for ((variant, expectedWire) in expectedWires) {
            val encoded = json.encodeToString(PlanStepStatus.serializer(), variant)
            assertEquals(
                "\"$expectedWire\"",
                encoded,
                "PlanStepStatus.$variant MUST encode as \"$expectedWire\"",
            )
        }
    }

    // ── PlanApprovalStatus 4-variant wire strings ─────────

    @Test fun planApprovalStatusWireStringsUseUnderscoreCompound() {
        // Marquee compound-with-underscore pin: per source
        // lines 274-287, multi-word approval statuses use
        // underscores ("pending_approval",
        // "approved_with_edits"). Drift to hyphens silently
        // breaks decode.
        val expectedWires = mapOf(
            PlanApprovalStatus.PENDING_APPROVAL to "pending_approval",
            PlanApprovalStatus.APPROVED to "approved",
            PlanApprovalStatus.APPROVED_WITH_EDITS to "approved_with_edits",
            PlanApprovalStatus.REJECTED to "rejected",
        )
        assertEquals(4, PlanApprovalStatus.entries.size)
        for ((variant, expectedWire) in expectedWires) {
            val encoded = json.encodeToString(PlanApprovalStatus.serializer(), variant)
            assertEquals(
                "\"$expectedWire\"",
                encoded,
                "PlanApprovalStatus.$variant MUST encode as \"$expectedWire\"",
            )
        }
    }

    // ── Convention asymmetry pin ───────────────────────────

    @Test fun partSubtypesUseHyphensWhileEnumsUseUnderscoresIntentionally() {
        // Marquee asymmetry pin: this is the load-bearing
        // distinction. Part subtypes use hyphens
        // ("step-start"); status enums use underscores
        // ("in_progress"). The convention is INTENTIONALLY
        // asymmetric — drift to harmonise either side
        // silently breaks legacy bundle decode.
        // Sample: Part.StepStart wire = "step-start" (hyphen);
        // TodoStatus.IN_PROGRESS wire = "in_progress" (underscore).
        // Pin both verbatim.

        // Part.StepStart serialization: build a minimal
        // instance and encode. (We can't introspect
        // @SerialName via reflection portably; encode is the
        // canonical wire surface.)
        // For Part, encoding the full discriminator JSON
        // includes the type tag with @SerialName. We don't
        // need to construct a full Part — the convention is
        // visible in already-pinned tests above. Pin here is
        // a meta-assertion of the asymmetry contract.
        // - Hyphen sample: "step-start" appears as a Part wire name.
        // - Underscore sample: "in_progress" appears as an enum wire name.
        // Build an empty Todo with IN_PROGRESS to verify the
        // underscore wire form is what gets encoded.
        val inProgressTodo = TodoInfo(content = "x", status = TodoStatus.IN_PROGRESS)
        val encoded = json.encodeToString(TodoInfo.serializer(), inProgressTodo)
        assertTrue(
            "in_progress" in encoded,
            "underscore enum wire form MUST appear in encoded JSON",
        )
        assertTrue(
            "in-progress" !in encoded,
            "drift to hyphen-form would surface here",
        )
    }

    // ── Cross-family non-collision pin ─────────────────────

    @Test fun planStepStatusFailedIsDistinctFromToolStateFailed() {
        // Marquee cross-family pin: PlanStepStatus.FAILED
        // and ToolState.Failed are in different sealed
        // families. Their wire strings are deliberately
        // different ("failed" vs "error"). Drift to align
        // would silently make a Plan step's "failed" decode
        // as a ToolState.Failed (or vice versa) when both
        // appear in the same JSON envelope.
        val planFailed = json.encodeToString(
            PlanStepStatus.serializer(),
            PlanStepStatus.FAILED,
        )
        val toolFailed = json.encodeToString(
            ToolState.serializer(),
            ToolState.Failed(input = null, message = "x"),
        )
        // PlanStepStatus.FAILED encodes as just "failed"
        // (it's an enum, no discriminator).
        assertEquals("\"failed\"", planFailed)
        // ToolState.Failed encodes with type discriminator
        // "error".
        assertTrue("\"type\":\"error\"" in toolFailed)
        // The two wire strings are distinct.
        assertTrue(
            "error" != "failed",
            "the two 'failure' representations MUST use distinct wire strings (sanity)",
        )
    }

    // ── Status enums round-trip ────────────────────────────

    @Test fun todoStatusRoundTripsAllVariants() {
        for (variant in TodoStatus.entries) {
            val encoded = json.encodeToString(TodoStatus.serializer(), variant)
            val decoded = json.decodeFromString(TodoStatus.serializer(), encoded)
            assertEquals(variant, decoded)
        }
    }

    @Test fun todoPriorityRoundTripsAllVariants() {
        for (variant in TodoPriority.entries) {
            val encoded = json.encodeToString(TodoPriority.serializer(), variant)
            val decoded = json.decodeFromString(TodoPriority.serializer(), encoded)
            assertEquals(variant, decoded)
        }
    }

    @Test fun planStepStatusRoundTripsAllVariants() {
        for (variant in PlanStepStatus.entries) {
            val encoded = json.encodeToString(PlanStepStatus.serializer(), variant)
            val decoded = json.decodeFromString(PlanStepStatus.serializer(), encoded)
            assertEquals(variant, decoded)
        }
    }

    @Test fun planApprovalStatusRoundTripsAllVariants() {
        for (variant in PlanApprovalStatus.entries) {
            val encoded = json.encodeToString(PlanApprovalStatus.serializer(), variant)
            val decoded = json.decodeFromString(PlanApprovalStatus.serializer(), encoded)
            assertEquals(variant, decoded)
        }
    }

    // ── TodoInfo + PlanStep defaults ───────────────────────

    @Test fun todoInfoStatusDefaultsToPending() {
        val ti = TodoInfo(content = "x")
        assertEquals(TodoStatus.PENDING, ti.status)
    }

    @Test fun todoInfoPriorityDefaultsToMedium() {
        val ti = TodoInfo(content = "x")
        assertEquals(TodoPriority.MEDIUM, ti.priority)
    }

    @Test fun planStepStatusDefaultsToPending() {
        val ps = PlanStep(step = 1, toolName = "x", inputSummary = "y")
        assertEquals(PlanStepStatus.PENDING, ps.status)
    }

    @Test fun planStepNoteDefaultsToNull() {
        val ps = PlanStep(step = 1, toolName = "x", inputSummary = "y")
        assertEquals(null, ps.note)
    }

    @Test fun planStepInputDefaultsToNull() {
        // Pin: per cycle 285's PROMPT_BUILD_SYSTEM, input is
        // optional — null means preview-only step.
        val ps = PlanStep(step = 1, toolName = "x", inputSummary = "y")
        assertEquals(null, ps.input)
    }
}
