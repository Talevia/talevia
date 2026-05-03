package io.talevia.core.tool.builtin.video

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [CLIP_ACTION_INPUT_SCHEMA] — the JSON schema for
 * `clip_action`, the largest action dispatcher in the codebase
 * (12 verbs across video / audio / text clip mutations). Cycle 105
 * audit: 190 LOC, **zero** transitive test refs; the schema feeds
 * directly into the LLM's tool spec, so any silent regression
 * corrupts every clip-edit dispatch.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Action enum is the complete verb list.** The LLM's
 *    discriminator. A regression dropping one verb would render it
 *    silently un-callable; adding a verb to the dispatcher without
 *    the enum would let it slip past schema validation but fail
 *    cryptically at dispatch.
 *
 * 2. **Every per-verb itemArray has correct `required` fields.**
 *    Each verb's *Items array has different mandatory fields; a
 *    regression flipping these (e.g., dropping `assetId` from
 *    addItems) would let the LLM submit incomplete clips that
 *    fail late with confusing errors. Pinned per-verb explicitly.
 *
 * 3. **Top-level `additionalProperties: false` + `required: [action]`.**
 *    Closes the input shape so the LLM can't slip arbitrary keys
 *    past validation.
 */
class ClipActionToolSchemaTest {

    private val schema: JsonObject = CLIP_ACTION_INPUT_SCHEMA
    private val props: JsonObject by lazy { schema["properties"]!!.jsonObject }

    private fun JsonObject.requiredKeys(): Set<String> =
        (this["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()

    private fun itemsRequired(propertyName: String): Set<String> =
        (props[propertyName]!!.jsonObject["items"]!!.jsonObject["required"] as JsonArray)
            .map { it.jsonPrimitive.content }
            .toSet()

    private fun itemsKeys(propertyName: String): Set<String> =
        (props[propertyName]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject).keys

    // ── top-level shape ───────────────────────────────────────────

    @Test fun topLevelTypeIsObject() {
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
    }

    @Test fun topLevelRequiredIsActionOnly() {
        // Pin: only `action` is universally required. Per-verb
        // requirements live on each verb's itemArray (those vary
        // per-verb so they can't all be top-level required).
        assertEquals(setOf("action"), schema.requiredKeys())
    }

    @Test fun topLevelHasAdditionalPropertiesFalse() {
        // Pin: closes the input shape so the LLM can't slip arbitrary
        // keys past schema validation.
        val ap = schema["additionalProperties"]
        assertNotNull(ap)
        assertEquals(false, ap.jsonPrimitive.boolean)
    }

    // ── action enum ───────────────────────────────────────────────

    @Test fun actionEnumExposesAll12Verbs() {
        // Pin the complete verb set. A new verb landing in the
        // dispatcher without an enum entry is silently un-callable
        // by the LLM; a verb dropped from enum but still in
        // dispatcher slips past schema validation but fails at
        // dispatch with a confusing error. Both directions need
        // the enum aligned with the dispatch-side switch.
        val actionSchema = props["action"]!!.jsonObject
        assertEquals("string", actionSchema["type"]!!.jsonPrimitive.content)
        val verbs = (actionSchema["enum"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf(
                "add", "remove", "duplicate", "move", "split", "trim", "replace",
                "fade", "edit_text", "set_volume", "set_transform", "set_sourceBinding",
            ),
            verbs,
            "action enum drift; got: $verbs",
        )
    }

    // ── itemArray required-field pins (per verb) ──────────────────

    @Test fun addItemsRequiresAssetId() {
        // Pin: a clip can't be added without an assetId. A regression
        // dropping this would let the LLM submit nameless clips that
        // fail at dispatch.
        assertEquals(setOf("assetId"), itemsRequired("addItems"))
    }

    @Test fun duplicateItemsRequiresClipIdAndTimelineStart() {
        // Duplicate without a target start position would be ambiguous
        // (where on the timeline?). Pin both required fields.
        assertEquals(setOf("clipId", "timelineStartSeconds"), itemsRequired("duplicateItems"))
    }

    @Test fun moveItemsRequiresOnlyClipId() {
        // Move only requires clipId — timelineStart and toTrackId are
        // both optional (one or both must be set in practice but the
        // schema can't easily express XOR; that validation lives in
        // the dispatch layer). Pin: schema allows just clipId.
        assertEquals(setOf("clipId"), itemsRequired("moveItems"))
    }

    @Test fun splitItemsRequiresClipIdAndAtTimelineSeconds() {
        // Split needs both: which clip + where in the timeline.
        assertEquals(setOf("clipId", "atTimelineSeconds"), itemsRequired("splitItems"))
    }

    @Test fun trimItemsRequiresOnlyClipId() {
        // Trim args (newSourceStart / newDuration) are both optional —
        // omitting either keeps that field unchanged. Pin: only
        // clipId is required.
        assertEquals(setOf("clipId"), itemsRequired("trimItems"))
    }

    @Test fun replaceItemsRequiresClipIdAndNewAssetId() {
        // Asset swap needs both: which clip + which new asset.
        assertEquals(setOf("clipId", "newAssetId"), itemsRequired("replaceItems"))
    }

    @Test fun fadeItemsRequiresOnlyClipId() {
        // Fade-in/out are both optional (omit = keep current values).
        assertEquals(setOf("clipId"), itemsRequired("fadeItems"))
    }

    @Test fun editTextItemsRequiresOnlyClipId() {
        // Per kdoc: "Text-clip body / style patch (≥ 1 field/item)".
        // The "≥ 1 field" rule is dispatch-layer validation; schema
        // only enforces clipId.
        assertEquals(setOf("clipId"), itemsRequired("editTextItems"))
    }

    @Test fun volumeItemsRequiresClipIdAndVolume() {
        // set_volume must specify both — without volume, the action
        // is a no-op (schema-level check; range [0,4] is dispatch-
        // layer).
        assertEquals(setOf("clipId", "volume"), itemsRequired("volumeItems"))
    }

    @Test fun transformItemsRequiresOnlyClipId() {
        // Per kdoc: "set_transform: visual-transform partial
        // overrides (≥ 1 field/item)". All transform fields are
        // optional (partial-overlay semantics — omit = keep).
        assertEquals(setOf("clipId"), itemsRequired("transformItems"))
    }

    @Test fun sourceBindingItemsRequiresClipIdAndSourceBinding() {
        // set_sourceBinding is FULL replacement (per kdoc), so the
        // sourceBinding field must be supplied even if to clear (use
        // empty list). Pin: both fields required.
        assertEquals(setOf("clipId", "sourceBinding"), itemsRequired("sourceBindingItems"))
    }

    // ── itemArray structure invariants ────────────────────────────

    @Test fun everyItemArrayDeclaresAdditionalPropertiesFalse() {
        // Every itemArray's `items.additionalProperties` must be
        // false so the LLM can't slip arbitrary per-item keys past
        // validation. The DSL helper `itemArray()` always sets it,
        // but a new verb authored without using the helper would
        // silently miss the closure.
        val itemArrayProps = listOf(
            "addItems", "duplicateItems", "moveItems", "splitItems", "trimItems",
            "replaceItems", "fadeItems", "editTextItems", "volumeItems",
            "transformItems", "sourceBindingItems",
        )
        for (prop in itemArrayProps) {
            val items = props[prop]!!.jsonObject["items"]!!.jsonObject
            val ap = items["additionalProperties"]
            assertNotNull(ap, "$prop items must declare additionalProperties")
            assertEquals(
                false,
                ap.jsonPrimitive.boolean,
                "$prop items.additionalProperties must be false",
            )
        }
    }

    @Test fun everyItemArrayHasArrayType() {
        val itemArrayProps = listOf(
            "addItems", "duplicateItems", "moveItems", "splitItems", "trimItems",
            "replaceItems", "fadeItems", "editTextItems", "volumeItems",
            "transformItems", "sourceBindingItems",
        )
        for (prop in itemArrayProps) {
            assertEquals("array", props[prop]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    // ── per-verb specific fields ──────────────────────────────────

    @Test fun addItemsExposesAllExpectedFields() {
        // Pin the five fields a clip-add can specify. Missing any
        // would render those args un-LLM-settable.
        assertEquals(
            setOf("assetId", "timelineStartSeconds", "sourceStartSeconds", "durationSeconds", "trackId"),
            itemsKeys("addItems"),
        )
    }

    @Test fun transformItemsExposesAllSixVisualKnobs() {
        // Pin all 6 visual-transform fields (translate × 2, scale ×
        // 2, rotation, opacity). A regression dropping any would
        // silently lose user control over that knob.
        assertEquals(
            setOf("clipId", "translateX", "translateY", "scaleX", "scaleY", "rotationDeg", "opacity"),
            itemsKeys("transformItems"),
        )
    }

    @Test fun editTextItemsExposesAllTextStyleKnobs() {
        // Pin: text edits expose all 8 knobs. Dropping bold/italic
        // (the boolean ones) would silently lose those edits.
        assertEquals(
            setOf("clipId", "newText", "fontFamily", "fontSize", "color", "backgroundColor", "bold", "italic"),
            itemsKeys("editTextItems"),
        )
    }

    @Test fun sourceBindingItemsSourceBindingFieldIsArrayOfStrings() {
        // sourceBinding is a `Set<SourceNodeId>` on the domain side,
        // but JSON Schema doesn't have a Set type — it's an array of
        // strings. Pin items.type=string for round-trip correctness.
        val sb = props["sourceBindingItems"]!!.jsonObject["items"]!!.jsonObject["properties"]!!
            .jsonObject["sourceBinding"]!!.jsonObject
        assertEquals("array", sb["type"]!!.jsonPrimitive.content)
        assertEquals("string", sb["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    // ── top-level scalars ─────────────────────────────────────────

    @Test fun rippleIsBoolean() {
        val ripple = props["ripple"]!!.jsonObject
        assertEquals("boolean", ripple["type"]!!.jsonPrimitive.content)
    }

    @Test fun clipIdsIsArrayOfStrings() {
        // Used by `remove`. Pin shape so a regression typing it as
        // a single string (forcing one-clip-at-a-time removes)
        // gets caught.
        val clipIds = props["clipIds"]!!.jsonObject
        assertEquals("array", clipIds["type"]!!.jsonPrimitive.content)
        assertEquals("string", clipIds["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun projectIdIsOptionalString() {
        // projectId is NOT in top-level required (defaults to
        // session's current project). Pin both shape and absence
        // from required.
        val projectId = props["projectId"]!!.jsonObject
        assertEquals("string", projectId["type"]!!.jsonPrimitive.content)
        assertTrue(
            "projectId" !in schema.requiredKeys(),
            "projectId must be optional (defaults to session project); got required=${schema.requiredKeys()}",
        )
    }
}
