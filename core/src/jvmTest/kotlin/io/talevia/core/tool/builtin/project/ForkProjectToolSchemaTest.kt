package io.talevia.core.tool.builtin.project

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [FORK_PROJECT_INPUT_SCHEMA] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ForkProjectToolSchema.kt`.
 * Cycle 243 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-242. Sister of cycle
 * 242's `IMPORT_MEDIA_INPUT_SCHEMA` pin — both schemas live in
 * separate files (per the long-file debt threshold) with kdocs
 * promising "byte-identical to the previous inline definition;
 * every field description is preserved verbatim".
 *
 * The schema is the LLM-visible contract for `fork_project`: when
 * the user asks "make a 9:16 vertical version of this project for
 * Reels", the LLM consults this schema to decide what JSON to
 * emit. Drift in a description (e.g. losing the supported-aspect
 * enumeration) silently lets the LLM guess at "is `4:5` valid?
 * is `vertical` valid?" — surfaces only as user-visible "fork
 * failed: unsupported aspect ratio" errors days later.
 *
 * Pins three correctness contracts:
 *
 *  1. **Top-level shape.** `type=object`,
 *     `additionalProperties=false`,
 *     `required=["sourceProjectId", "newTitle"]`. Drift to
 *     "required = ['sourceProjectId']" alone would let the LLM
 *     fork without a title (silent slug fallback creates ugly
 *     IDs); drift to add `path` to required would force the LLM
 *     to specify a path even when default home is fine.
 *
 *  2. **Properties block has exactly the 6 documented fields**:
 *     sourceProjectId, newTitle, newProjectId, snapshotId, path,
 *     variantSpec.
 *
 *  3. **variantSpec sub-schema integrity**:
 *     - Nested type=object with its own `additionalProperties=false`
 *       (drift to true would let LLM smuggle reshape options that
 *       silently no-op).
 *     - Sub-properties: aspectRatio (string), durationSecondsMax
 *       (number), language (string).
 *     - aspectRatio description enumerates ALL 5 supported
 *       presets (16:9 / 9:16 / 1:1 / 4:5 / 21:9). Drift to drop
 *       any single preset from the description leaves the LLM
 *       guessing whether it's still valid.
 *     - durationSecondsMax description mentions the `> 0`
 *       constraint (drift would silently let the LLM send 0 /
 *       negative values that fail at execute()).
 *     - language description cites ISO-639-1 + the
 *       `clip_action(action="replace")` downstream-wiring
 *       pointer (drift loses the "how to actually swap audio"
 *       hint that LLM uses to chain tool calls).
 */
class ForkProjectToolSchemaTest {

    private val schema: JsonObject = FORK_PROJECT_INPUT_SCHEMA

    private val properties: JsonObject by lazy {
        schema["properties"]?.jsonObject ?: error("schema missing 'properties'")
    }

    // ── 1. Top-level shape ──────────────────────────────────

    @Test fun typeIsObject() {
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }

    @Test fun additionalPropertiesIsFalse() {
        val ap = schema["additionalProperties"]
        assertNotNull(ap)
        assertTrue(
            ap is JsonPrimitive && !ap.boolean,
            "additionalProperties MUST be the boolean `false`",
        )
    }

    @Test fun requiredIsExactlySourceProjectIdAndNewTitle() {
        // Marquee required-set pin: the user MUST supply both
        // sourceProjectId AND newTitle. Drift to drop newTitle
        // would let the LLM fork with a default slug-of-nothing
        // ID; drift to add `path` to required would force the
        // LLM to supply a path even when default home works.
        val required = schema["required"]
        assertNotNull(required)
        assertTrue(required is JsonArray)
        val names = required.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("sourceProjectId", "newTitle"),
            names,
            "required MUST be exactly {sourceProjectId, newTitle}",
        )
    }

    // ── 2. Properties block — exactly 6 fields ──────────────

    @Test fun propertiesContainsExactly6DocumentedFields() {
        assertEquals(
            setOf(
                "sourceProjectId",
                "newTitle",
                "newProjectId",
                "snapshotId",
                "path",
                "variantSpec",
            ),
            properties.keys,
            "properties MUST contain exactly the 6 documented fields",
        )
    }

    // ── 3. Top-level property types ─────────────────────────

    @Test fun stringPropertiesHaveStringType() {
        // Pin: every top-level non-variantSpec property is `string`.
        // Drift to a different type would silently break the
        // dispatcher's deserialiser (LLM sends string by default).
        for (key in listOf("sourceProjectId", "newTitle", "newProjectId", "snapshotId", "path")) {
            assertEquals(
                "string",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=string",
            )
        }
    }

    @Test fun variantSpecIsObject() {
        assertEquals(
            "object",
            properties["variantSpec"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
            "variantSpec MUST be type=object",
        )
    }

    // ── 4. variantSpec sub-schema integrity ─────────────────

    private val variantSpec: JsonObject by lazy {
        properties["variantSpec"]?.jsonObject ?: error("variantSpec missing")
    }

    private val variantSpecProperties: JsonObject by lazy {
        variantSpec["properties"]?.jsonObject ?: error("variantSpec missing properties")
    }

    @Test fun variantSpecAdditionalPropertiesIsFalse() {
        // Marquee strict-validation pin for the nested sub-schema:
        // drift to true would let LLM smuggle reshape options
        // (e.g. `subtitle: "force"`) that the executor ignores —
        // silently no-op'd reshapes are confusing for the user.
        val ap = variantSpec["additionalProperties"]
        assertNotNull(ap)
        assertTrue(
            ap is JsonPrimitive && !ap.boolean,
            "variantSpec.additionalProperties MUST be false",
        )
    }

    @Test fun variantSpecPropertiesAreExactly3() {
        // Pin: exactly the 3 reshape verbs.
        assertEquals(
            setOf("aspectRatio", "durationSecondsMax", "language"),
            variantSpecProperties.keys,
            "variantSpec.properties MUST be {aspectRatio, durationSecondsMax, language}",
        )
    }

    @Test fun variantSpecAspectRatioIsString() {
        assertEquals(
            "string",
            variantSpecProperties["aspectRatio"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test fun variantSpecDurationSecondsMaxIsNumber() {
        // Marquee number-vs-integer pin: drift to "integer" would
        // silently round the LLM's "fork at 30.5s" input. Schema
        // type "number" allows fractional seconds.
        assertEquals(
            "number",
            variantSpecProperties["durationSecondsMax"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test fun variantSpecLanguageIsString() {
        assertEquals(
            "string",
            variantSpecProperties["language"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
    }

    // ── 5. Description-quality pins ─────────────────────────

    @Test fun aspectRatioDescriptionEnumeratesAllFiveSupportedPresets() {
        // Marquee LLM-mental-model pin: per the file kdoc, the
        // description "16:9, 9:16, 1:1, 4:5, or 21:9
        // (case-insensitive)" is what tells the LLM which
        // ratios are valid. Drift to drop any single preset
        // would silently leave the LLM guessing whether it's
        // valid; drift to ADD an unsupported preset would mis-
        // route to a failed reshape.
        val desc = variantSpecProperties["aspectRatio"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("aspectRatio missing description")
        for (preset in listOf("16:9", "9:16", "1:1", "4:5", "21:9")) {
            assertTrue(
                preset in desc,
                "aspectRatio description MUST cite preset '$preset'; got: $desc",
            )
        }
        assertTrue(
            "case-insensitive" in desc,
            "aspectRatio description MUST mention case-insensitivity (the LLM may emit '16:9' or '16x9' etc); got: $desc",
        )
    }

    @Test fun durationSecondsMaxDescriptionMentionsPositiveConstraint() {
        // Pin: description tells the LLM "must be > 0". Drift
        // to drop the constraint silently lets the LLM emit 0
        // / negative values that fail at execute() — surfaces
        // as a confusing user error.
        val desc = variantSpecProperties["durationSecondsMax"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("durationSecondsMax missing description")
        assertTrue(
            "> 0" in desc,
            "durationSecondsMax description MUST cite the '> 0' constraint; got: $desc",
        )
    }

    @Test fun languageDescriptionMentionsIsoCodeAndDownstreamChain() {
        // Marquee tool-chain pin: the description tells the LLM
        // that language regen is HALF the workflow — `clip_action
        // (action="replace")` chains the audio swap. Drift to
        // drop that pointer leaves the LLM thinking the regen
        // alone fully swaps audio (silently broken: timeline
        // still references old audio asset ids).
        val desc = variantSpecProperties["language"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("language missing description")
        assertTrue(
            "ISO-639-1" in desc,
            "language description MUST cite ISO-639-1; got: $desc",
        )
        assertTrue(
            "en" in desc && "es" in desc,
            "language description MUST give example codes (en/es/zh); got: $desc",
        )
        assertTrue(
            "clip_action" in desc && "replace" in desc,
            "language description MUST point at clip_action(action=\"replace\") for the audio-swap chain; got: $desc",
        )
    }

    @Test fun pathDescriptionMentionsAbsoluteAndBundleConstraint() {
        // Pin: the path description tells the LLM both "absolute"
        // (not relative) AND "the directory must not already
        // contain a talevia.json" (so the LLM doesn't fork onto
        // an existing project, silently corrupting it).
        val desc = properties["path"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("path missing description")
        assertTrue(
            "absolute" in desc,
            "path description MUST cite 'absolute' filesystem path; got: $desc",
        )
        assertTrue(
            "talevia.json" in desc,
            "path description MUST cite the empty-directory constraint (no existing talevia.json); got: $desc",
        )
    }

    @Test fun newTitleDescriptionIsPresentAndDescribesIdFallback() {
        // Pin: newTitle description includes the "also drives the
        // default newProjectId" hint so the LLM knows it doesn't
        // need to specify newProjectId for typical forks.
        val desc = properties["newTitle"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("newTitle missing description")
        assertTrue(
            "newProjectId" in desc,
            "newTitle description MUST mention newProjectId fallback; got: $desc",
        )
    }
}
