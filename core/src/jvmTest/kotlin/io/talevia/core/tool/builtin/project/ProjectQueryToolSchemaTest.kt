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
 * Direct tests for [PROJECT_QUERY_INPUT_SCHEMA] +
 * [PROJECT_QUERY_HELP_TEXT] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ProjectQueryToolSchema.kt`.
 * Cycle 267 audit: 0 test refs against either.
 *
 * Same audit-pattern fallback as cycles 207-266. Sister to
 * cycles 242 / 243 / 244 / 266 schema pins. The dispatcher
 * `ProjectQueryTool` is the largest query family member with
 * 22+ selects — this schema's helpText is the LLM's primary
 * way to discover which selects exist + which filters
 * apply per select.
 *
 * Drift signals:
 *   - helpText drops a select name → LLM no longer offers it.
 *   - helpText description drift → LLM mis-routes filters.
 *   - schema `sourceNodeIds` type drift to `string` →
 *     silently breaks batched incremental_plan input.
 *   - `additionalProperties=true` drift → LLM smuggles
 *     unknown filters that get silently ignored.
 *
 * Pins three correctness contracts:
 *
 *  1. **Top-level shape**: type=object,
 *     additionalProperties=false, required=["select"]. The
 *     single-required pin matches the dispatcher (every
 *     other field is select-conditional).
 *
 *  2. **Properties block has exactly 27 fields** + per-type
 *     buckets pinned (string / number / integer / boolean /
 *     array). The `sourceNodeIds: array<string>` pin is
 *     marquee — drift to plain `string` would silently
 *     break batched incremental_plan input.
 *
 *  3. **`PROJECT_QUERY_HELP_TEXT` enumerates all 22+
 *     selects** + sort-key listings per select. This is
 *     the agent's main discovery surface — drift in select
 *     names silently retires functionality. Pinned
 *     individually for each canonical select id.
 */
class ProjectQueryToolSchemaTest {

    private val schema: JsonObject = PROJECT_QUERY_INPUT_SCHEMA

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
            "additionalProperties MUST be false (drift would let LLM smuggle unknown filters that get silently ignored)",
        )
    }

    @Test fun requiredIsExactlySelect() {
        val required = schema["required"]
        assertNotNull(required)
        assertTrue(required is JsonArray)
        assertEquals(
            listOf("select"),
            required.jsonArray.map { it.jsonPrimitive.content },
            "required MUST be exactly ['select'] — every other field is select-conditional",
        )
    }

    // ── 2. Properties block: count + types ──────────────────

    @Test fun propertiesHasExactlyTwentyFiveFields() {
        // Pin: 27 properties total. Drift to add / drop
        // surfaces here.
        assertEquals(
            27,
            properties.size,
            "PROJECT_QUERY_INPUT_SCHEMA properties MUST have exactly 27 fields",
        )
    }

    @Test fun stringTypedPropertiesHaveStringType() {
        for (key in listOf(
            "projectId",
            "select",
            "trackKind",
            "trackId",
            "kind",
            "toolId",
            "assetId",
            "sourceNodeId",
            "clipId",
            "inputHash",
            "sortBy",
            "fromSnapshotId",
            "toSnapshotId",
            "engineId",
        )) {
            assertEquals(
                "string",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=string",
            )
        }
    }

    @Test fun numberTypedPropertiesHaveNumberType() {
        // Pin: `fromSeconds` / `toSeconds` are `number` (NOT
        // integer) — the agent emits fractional seconds for
        // window queries. Drift to integer would silently
        // round to whole seconds.
        for (key in listOf("fromSeconds", "toSeconds")) {
            assertEquals(
                "number",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=number (fractional seconds)",
            )
        }
    }

    @Test fun integerTypedPropertiesHaveIntegerType() {
        for (key in listOf("sinceEpochMs", "maxAgeDays", "limit", "offset")) {
            assertEquals(
                "integer",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=integer",
            )
        }
    }

    @Test fun booleanTypedPropertiesHaveBooleanType() {
        for (key in listOf(
            "onlyNonEmpty",
            "onlySourceBound",
            "onlyPinned",
            "onlyUnused",
            "onlyReferenced",
            "onlyOrphaned",
        )) {
            assertEquals(
                "boolean",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=boolean",
            )
        }
    }

    @Test fun sourceNodeIdsIsArrayOfStrings() {
        // Marquee batched-input pin: `sourceNodeIds` is the
        // ONLY array property — used by `incremental_plan` to
        // accept a batch of changed source-node ids. Drift to
        // plain `string` would silently break the batch input
        // path (the LLM would have to fall back to single-
        // node incremental queries that miss cross-node
        // changes).
        val prop = properties["sourceNodeIds"]?.jsonObject
        assertNotNull(prop, "sourceNodeIds MUST be present")
        assertEquals(
            "array",
            prop["type"]?.jsonPrimitive?.content,
            "sourceNodeIds MUST be type=array",
        )
        assertEquals(
            "string",
            prop["items"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
            "sourceNodeIds.items MUST be type=string (batched ids)",
        )
    }

    // ── 3. PROJECT_QUERY_HELP_TEXT enumerates all selects ──

    @Test fun helpTextEnumeratesAllCanonicalSelects() {
        // Marquee discovery-surface pin: drift to drop a
        // select name from helpText silently retires that
        // functionality from the LLM's view. Each select
        // name pinned individually to surface single-name
        // drift.
        val canonicalSelects = listOf(
            "tracks",
            "timeline_clips",
            "assets",
            "transitions",
            "lockfile_entries",
            "clips_for_asset",
            "clips_for_source",
            "consistency_propagation",
            "clip",
            "lockfile_entry",
            "project_metadata",
            "spend",
            "lockfile_cache_stats",
            "snapshots",
            "lockfile_orphans",
            "timeline_diff",
            "lockfile_diff",
            "source_binding_stats",
            "stale_clips",
            "render_stale",
            "incremental_plan",
            "validation",
        )
        for (select in canonicalSelects) {
            assertTrue(
                select in PROJECT_QUERY_HELP_TEXT,
                "helpText MUST cite select '$select'; drift removes LLM discovery",
            )
        }
        // Sanity: at least 22 selects are covered.
        assertTrue(
            canonicalSelects.size >= 22,
            "PROJECT_QUERY_HELP_TEXT canonical-select list MUST have ≥22 entries",
        )
    }

    @Test fun helpTextEnumeratesPerSelectSortModes() {
        // Pin: per-select sort modes are documented inline
        // in helpText after the `| sort:` separator. Drift
        // to drop a sort mode silently retires it from the
        // LLM's mental model.
        for ((select, sorts) in mapOf(
            "tracks" to listOf("index", "clipCount", "span", "recent"),
            "timeline_clips" to listOf("startSeconds", "durationSeconds", "recent"),
            "assets" to listOf("insertion", "duration", "duration-asc", "id", "recent"),
        )) {
            for (sortMode in sorts) {
                assertTrue(
                    sortMode in PROJECT_QUERY_HELP_TEXT,
                    "helpText MUST cite sort mode '$sortMode' for select '$select'; got: $PROJECT_QUERY_HELP_TEXT",
                )
            }
        }
    }

    @Test fun helpTextDocumentsKindEnumForAssets() {
        // Pin: assets-select `kind` filter values listed.
        // Drift to drop one (e.g. `image`) silently retires
        // it.
        for (kind in listOf("video", "audio", "image", "all")) {
            assertTrue(
                kind in PROJECT_QUERY_HELP_TEXT,
                "helpText MUST cite asset kind '$kind'; got: $PROJECT_QUERY_HELP_TEXT",
            )
        }
    }

    @Test fun helpTextDocumentsCommonLimitRange() {
        // Pin: drift in the documented limit range silently
        // changes what the LLM thinks is valid. The schema's
        // `limit` property has no enum/range, so helpText is
        // the only place this is documented.
        assertTrue(
            "limit 1..500" in PROJECT_QUERY_HELP_TEXT,
            "helpText MUST cite 'limit 1..500'; got: $PROJECT_QUERY_HELP_TEXT",
        )
        assertTrue(
            "default 100" in PROJECT_QUERY_HELP_TEXT,
            "helpText MUST cite default 100; got: $PROJECT_QUERY_HELP_TEXT",
        )
    }

    @Test fun helpTextDocumentsFilterOnWrongSelectFailLoud() {
        // Pin: helpText warns that mis-applied filters fail
        // loud (NOT silently ignored). Drift to drop the
        // warning would change LLM expectations.
        assertTrue(
            "fails loud" in PROJECT_QUERY_HELP_TEXT.lowercase(),
            "helpText MUST cite 'fails loud' filter-validation behavior; got: $PROJECT_QUERY_HELP_TEXT",
        )
    }

    // ── 4. Critical description pins ────────────────────────

    @Test fun trackKindDescriptionEnumeratesAllFourTrackVariants() {
        val desc = properties["trackKind"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("trackKind missing description")
        for (kind in listOf("video", "audio", "subtitle", "effect")) {
            assertTrue(
                kind in desc,
                "trackKind description MUST cite '$kind'; got: $desc",
            )
        }
    }

    @Test fun engineIdDescriptionMentionsDefaultFfmpegJvm() {
        // Pin: per source, `engineId`'s description cites
        // `default ffmpeg-jvm`. The LLM uses this default
        // when `render_stale` / `incremental_plan` don't
        // get an explicit engine id. Drift to drop the
        // default mention silently confuses the LLM.
        val desc = properties["engineId"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("engineId missing description")
        assertTrue(
            "ffmpeg-jvm" in desc,
            "engineId description MUST cite default ffmpeg-jvm; got: $desc",
        )
    }

    @Test fun assetIdDescriptionLinksToTwoDrillDowns() {
        // Pin: assetId's description points at BOTH
        // `clips_for_asset` (forward) and `lockfile_entry`
        // (reverse-lookup, xor with inputHash). Drift to
        // drop either silently retires the field's
        // dual-role discovery.
        val desc = properties["assetId"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("assetId missing description")
        assertTrue("clips_for_asset" in desc)
        assertTrue("lockfile_entry" in desc)
        assertTrue(
            "xor" in desc.lowercase() || "inputhash" in desc.lowercase(),
            "assetId description MUST cite the assetId/inputHash xor relation; got: $desc",
        )
    }

    @Test fun selectDescriptionFlagsCaseInsensitivity() {
        // Pin: agents commonly emit `Tracks` / `TIMELINE_CLIPS`
        // — the dispatcher lowercases input. Description
        // tells the LLM that case-insensitive is allowed
        // so the agent doesn't have to guess.
        val desc = properties["select"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("select missing description")
        assertTrue(
            "case-insensitive" in desc,
            "select description MUST cite case-insensitive matching; got: $desc",
        )
    }
}
