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
 * Direct tests for [PROJECT_ACTION_INPUT_SCHEMA] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ProjectLifecycleActionToolSchema.kt`.
 * Cycle 266 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-265. Sister to
 * cycles 242 / 243 / 244's schema pins
 * (`IMPORT_MEDIA_INPUT_SCHEMA` / `FORK_PROJECT_INPUT_SCHEMA` /
 * `SOURCE_QUERY_INPUT_SCHEMA`). Same shape promise — the kdoc
 * mirrors `ClipActionToolSchema.kt` / `ProjectQueryToolSchema.kt`'s
 * extraction pattern.
 *
 * The schema is the LLM-visible contract for
 * `project_action(action=...)`. Drift in:
 *
 *  - the **`action` enum** silently changes which actions the
 *    LLM thinks are valid (adding / dropping a verb without
 *    dispatcher logic update would break the routed call).
 *  - the **`template` enum** silently changes which templates
 *    the LLM can pick for `create_from_template` (drift to
 *    drop one would silently never offer it).
 *  - per-property **descriptions** silently changes the
 *    routing-conditional guidance (e.g. `delete: required.
 *    create: optional hint.` for projectId).
 *
 * Pins three correctness contracts:
 *
 *  1. **Top-level shape**: `type=object`,
 *     `additionalProperties=false`, `required=["action"]`
 *     (only `action` is unconditionally required; every other
 *     parameter is verb-conditional).
 *
 *  2. **`action` enum has the canonical 7 verbs**:
 *     `create / create_from_template / open / delete / rename
 *     / set_output_profile / remove_asset`. Drift to add a
 *     verb without dispatcher update silently lets the agent
 *     emit a verb the dispatcher rejects with "unknown
 *     action".
 *
 *  3. **`template` enum has the canonical 6 values**:
 *     `narrative / vlog / ad / musicmv / tutorial / auto` —
 *     the 5 banked templates (cycles 250-254) plus the `auto`
 *     classifier. Drift to drop / add silently changes which
 *     templates `create_from_template` accepts.
 *
 * Plus structural pins:
 *   - 18 properties total (drift to add / drop a property
 *     silently changes the LLM-visible surface).
 *   - Type pins per property family (string / integer /
 *     boolean buckets).
 *   - Critical descriptions pinned (verb-conditional hints
 *     load-bearing for LLM routing).
 */
class ProjectLifecycleActionToolSchemaTest {

    private val schema: JsonObject = PROJECT_ACTION_INPUT_SCHEMA

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

    @Test fun requiredIsExactlyAction() {
        // Marquee single-required pin: `action` is the only
        // unconditionally required field. Drift to add
        // `projectId` to required would force the LLM to send
        // it even on `create` (which only treats it as a hint).
        val required = schema["required"]
        assertNotNull(required)
        assertTrue(required is JsonArray)
        assertEquals(
            listOf("action"),
            required.jsonArray.map { it.jsonPrimitive.content },
            "required MUST be exactly ['action']",
        )
    }

    // ── 2. `action` enum has canonical 7 verbs ──────────────

    @Test fun actionEnumHasExactlyTheCanonicalSevenVerbs() {
        // Marquee verb-set pin: drift would silently let the
        // LLM emit a verb the dispatcher rejects, OR stop
        // emitting a verb the dispatcher still routes.
        val actionEnum = properties["action"]?.jsonObject?.get("enum")
        assertNotNull(actionEnum, "action property MUST have an `enum`")
        assertTrue(actionEnum is JsonArray)
        val values = actionEnum.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf(
                "create",
                "create_from_template",
                "open",
                "delete",
                "rename",
                "set_output_profile",
                "remove_asset",
            ),
            values,
            "action.enum MUST be the canonical 7 verbs",
        )
    }

    // ── 3. `template` enum has canonical 6 values ───────────

    @Test fun templateEnumHasExactlyTheCanonicalSixTemplates() {
        // Marquee template-set pin: matches the 5 banked
        // templates (cycles 250-254 — Narrative / Ad /
        // MusicMv / Tutorial / Vlog) PLUS the `auto`
        // classifier. Drift to drop `auto` would silently
        // remove the keyword-classifier path.
        val templateEnum = properties["template"]?.jsonObject?.get("enum")
        assertNotNull(templateEnum, "template property MUST have an `enum`")
        assertTrue(templateEnum is JsonArray)
        val values = templateEnum.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("narrative", "vlog", "ad", "musicmv", "tutorial", "auto"),
            values,
            "template.enum MUST be the 5 banked templates + `auto`",
        )
    }

    // ── 4. Property count + per-type pins ───────────────────

    @Test fun propertiesHasExactlyEighteenFields() {
        // Pin: drift to add a 19th property (silently expand
        // surface) or drop a property (silently strip a
        // dispatcher input lane) surfaces here. Schema lives
        // in a separate file specifically to allow growth — but
        // every change should be visible in this test diff.
        assertEquals(
            18,
            properties.size,
            "PROJECT_ACTION_INPUT_SCHEMA properties MUST have exactly 18 fields",
        )
    }

    @Test fun stringTypedPropertiesHaveStringType() {
        // Pin: every string-typed property gets `type=string`.
        for (key in listOf(
            "action",
            "projectId",
            "title",
            "path",
            "resolutionPreset",
            "assetId",
            "videoCodec",
            "audioCodec",
            "container",
            "template",
            "intent",
        )) {
            assertEquals(
                "string",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=string",
            )
        }
    }

    @Test fun integerTypedPropertiesHaveIntegerType() {
        // Pin: every integer-typed property gets `type=integer`
        // (NOT `number` — fps / resolution / bitrate are all
        // integral).
        for (key in listOf(
            "fps",
            "resolutionWidth",
            "resolutionHeight",
            "videoBitrate",
            "audioBitrate",
        )) {
            assertEquals(
                "integer",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=integer",
            )
        }
    }

    @Test fun booleanTypedPropertiesHaveBooleanType() {
        for (key in listOf("deleteFiles", "force")) {
            assertEquals(
                "boolean",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=boolean",
            )
        }
    }

    // ── 5. Critical description pins ────────────────────────

    @Test fun projectIdDescriptionListsVerbConditionalRequirements() {
        // Pin: per source, projectId's description tells the
        // LLM the verb-conditional shape (delete / rename /
        // set_output_profile / remove_asset = required;
        // create = optional hint). Drift to drop the verb
        // list would silently leave LLM unable to know when
        // to emit projectId.
        val desc = properties["projectId"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("projectId missing description")
        for (verb in listOf("delete", "rename", "set_output_profile", "remove_asset")) {
            assertTrue(
                verb in desc,
                "projectId description MUST cite verb '$verb' as required; got: $desc",
            )
        }
        assertTrue(
            "create" in desc,
            "projectId description MUST cite `create` as optional-hint; got: $desc",
        )
    }

    @Test fun deleteFilesDescriptionWarnsAboutBundleAndDefaultFalse() {
        // Pin: deleteFiles description tells the LLM what
        // gets deleted (talevia.json / media/ / .talevia-cache/)
        // AND the default (false). Drift to drop either
        // silently changes user-visible behavior on `delete`.
        val desc = properties["deleteFiles"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("deleteFiles missing description")
        assertTrue("talevia.json" in desc, "deleteFiles MUST cite talevia.json; got: $desc")
        assertTrue("media/" in desc, "deleteFiles MUST cite media/; got: $desc")
        assertTrue("Default false" in desc, "deleteFiles MUST cite default; got: $desc")
    }

    @Test fun resolutionPresetDescriptionEnumeratesThreePresets() {
        // Pin: the 3 supported preset names (720p / 1080p /
        // 4k) appear in the description. Drift to drop a
        // preset silently tells the LLM only fewer are
        // valid.
        val desc = properties["resolutionPreset"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("resolutionPreset missing description")
        assertTrue("720p" in desc)
        assertTrue("1080p" in desc)
        assertTrue("4k" in desc)
        assertTrue(
            "default" in desc.lowercase(),
            "resolutionPreset MUST mark a default; got: $desc",
        )
    }

    @Test fun templateDescriptionMentionsAutoClassifier() {
        // Pin: `auto` is special — it triggers the
        // keyword-based classifier. The description tells the
        // LLM `auto needs intent`. Drift to drop the link
        // would silently confuse the LLM about how `auto`
        // gets routed.
        val desc = properties["template"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("template missing description")
        assertTrue("auto" in desc, "template description MUST cite auto; got: $desc")
        assertTrue(
            "intent" in desc,
            "template description MUST cite that auto needs intent; got: $desc",
        )
    }

    @Test fun forceDescriptionExplainsRemoveAssetEscape() {
        // Pin: `force` is the escape hatch for remove_asset
        // when references exist. Drift to drop the
        // explanation silently leaves the LLM to guess
        // whether to use `force=true`.
        val desc = properties["force"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("force missing description")
        assertTrue(
            "remove_asset" in desc,
            "force description MUST cite remove_asset role; got: $desc",
        )
        assertTrue(
            "default false" in desc,
            "force description MUST cite default false; got: $desc",
        )
    }

    @Test fun setOutputProfileDescriptionsListCanonicalCodecs() {
        // Pin: drift in the videoCodec / audioCodec /
        // container enums silently leaves LLM guessing
        // valid values.
        val videoCodec = properties["videoCodec"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("videoCodec missing description")
        for (codec in listOf("h264", "h265", "prores", "vp9")) {
            assertTrue(codec in videoCodec, "videoCodec MUST cite '$codec'; got: $videoCodec")
        }

        val audioCodec = properties["audioCodec"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("audioCodec missing description")
        for (codec in listOf("aac", "opus", "mp3")) {
            assertTrue(codec in audioCodec, "audioCodec MUST cite '$codec'; got: $audioCodec")
        }

        val container = properties["container"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("container missing description")
        for (c in listOf("mp4", "mov", "mkv", "webm")) {
            assertTrue(c in container, "container MUST cite '$c'; got: $container")
        }
    }
}
