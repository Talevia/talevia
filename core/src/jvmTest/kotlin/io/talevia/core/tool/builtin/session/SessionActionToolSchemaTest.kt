package io.talevia.core.tool.builtin.session

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
 * Direct tests for [SESSION_ACTION_INPUT_SCHEMA] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/SessionActionToolSchema.kt`.
 * Cycle 268 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-267. Sister to
 * cycles 242 / 243 / 244 / 266 / 267 schema pins.
 *
 * **Phantom-test caveat**: the kdoc on
 * `SESSION_ACTION_INPUT_SCHEMA` says
 * "SessionActionToolSchemaTest round-trips the new top-level
 * constant against the dispatcher's `inputSchema` to lock that
 * in" — but no test file by that name existed before this
 * commit. The kdoc is a documentation-bug pointer to a phantom
 * test (mirror inverse of cycle 242's TaleviaSystemPromptTest
 * situation, where the test existed in commonTest but kdoc had
 * the wrong path). This commit closes the loop.
 *
 * Drift signals:
 *   - **action enum drops a verb** silently retires it;
 *     adds a verb without dispatcher logic update silently
 *     lets agent emit unroutable verbs.
 *   - **format enum drift** breaks `export_bus_trace` /
 *     `export` format negotiation (cycle 256 already pinned
 *     parseExportFormat for the JSON / markdown side, but
 *     the schema enum is the LLM-discovery side).
 *   - **capCents type drift** to plain `integer` silently
 *     breaks the "null clears the cap" semantic (the
 *     `["integer", "null"]` two-element type array allows
 *     both; drift to scalar would reject null).
 *
 * Pins three correctness contracts:
 *
 *  1. **Top-level shape**: type=object,
 *     additionalProperties=false, required=["action"]. The
 *     single-required pin matches the dispatcher (every
 *     other field is verb-conditional).
 *
 *  2. **`action` enum has the canonical 14 verbs**
 *     (archive / unarchive / rename / delete /
 *     remove_permission_rule / import / set_system_prompt /
 *     export_bus_trace / set_tool_enabled / set_spend_cap /
 *     fork / export / revert / compact). Drift surfaces
 *     here.
 *
 *  3. **`format` enum has the canonical 4 values**
 *     (jsonl / json / markdown / md) — the `md` alias
 *     pinned individually (drift to drop would break
 *     agent's natural shorthand from cycle 256's
 *     parseExportFormat pin).
 *
 * Plus structural pins:
 *   - 16 properties total.
 *   - Per-type buckets (string / integer / boolean /
 *     nullable-integer).
 *   - **`capCents: ["integer", "null"]`** marquee
 *     nullable-type pin — drift to plain `integer` silently
 *     breaks the null-clears-cap semantic.
 *   - Critical descriptions: anchorMessageId dual-role
 *     (fork optional / revert required), capCents semantics
 *     (null clears / 0 blocks / positive sets cents), strategy
 *     compact options.
 */
class SessionActionToolSchemaTest {

    private val schema: JsonObject = SESSION_ACTION_INPUT_SCHEMA

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
            "additionalProperties MUST be false",
        )
    }

    @Test fun requiredIsExactlyAction() {
        val required = schema["required"]
        assertNotNull(required)
        assertTrue(required is JsonArray)
        assertEquals(
            listOf("action"),
            required.jsonArray.map { it.jsonPrimitive.content },
            "required MUST be exactly ['action']",
        )
    }

    // ── 2. `action` enum — canonical 14 verbs ───────────────

    @Test fun actionEnumHasCanonicalFourteenVerbs() {
        val actionEnum = properties["action"]?.jsonObject?.get("enum")
        assertNotNull(actionEnum, "action property MUST have enum")
        assertTrue(actionEnum is JsonArray)
        val values = actionEnum.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf(
                "archive",
                "unarchive",
                "rename",
                "delete",
                "remove_permission_rule",
                "import",
                "set_system_prompt",
                "export_bus_trace",
                "set_tool_enabled",
                "set_spend_cap",
                "fork",
                "export",
                "revert",
                "compact",
            ),
            values,
            "action.enum MUST be the canonical 14 session-action verbs",
        )
    }

    @Test fun actionEnumHasExactlyFourteenEntries() {
        // Pin: count check — drift to add/drop without
        // updating the canonical-set test surfaces here as
        // a 1-test-failure rather than a multi-test failure.
        val actionEnum = properties["action"]?.jsonObject?.get("enum") as JsonArray
        assertEquals(
            14,
            actionEnum.size,
            "action.enum MUST have exactly 14 verbs",
        )
    }

    // ── 3. `format` enum — canonical 4 values ───────────────

    @Test fun formatEnumHasCanonicalFourValues() {
        val formatEnum = properties["format"]?.jsonObject?.get("enum")
        assertNotNull(formatEnum, "format property MUST have enum")
        assertTrue(formatEnum is JsonArray)
        val values = formatEnum.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("jsonl", "json", "markdown", "md"),
            values,
            "format.enum MUST be {jsonl, json, markdown, md}",
        )
    }

    @Test fun formatEnumIncludesMdAliasForMarkdown() {
        // Marquee `md` alias pin — sister to cycle 256's
        // parseExportFormat pin. Drift to drop the alias
        // would silently break the agent's natural
        // shorthand for markdown export.
        val formatEnum = properties["format"]?.jsonObject?.get("enum") as JsonArray
        val values = formatEnum.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            "md" in values,
            "format.enum MUST include 'md' alias (sister of cycle 256's parser pin); got: $values",
        )
        assertTrue(
            "markdown" in values,
            "format.enum MUST also include canonical 'markdown'; got: $values",
        )
    }

    // ── 4. Properties block: count + types ──────────────────

    @Test fun propertiesHasExactlySixteenFields() {
        // Pin: drift to add / drop a property silently
        // changes the LLM-visible surface.
        assertEquals(
            16,
            properties.size,
            "SESSION_ACTION_INPUT_SCHEMA properties MUST have exactly 16 fields",
        )
    }

    @Test fun stringTypedPropertiesHaveStringType() {
        for (key in listOf(
            "sessionId",
            "action",
            "newTitle",
            "permission",
            "pattern",
            "envelope",
            "systemPromptOverride",
            "format",
            "toolId",
            "anchorMessageId",
            "projectId",
            "strategy",
        )) {
            assertEquals(
                "string",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=string",
            )
        }
    }

    @Test fun integerTypedPropertyHasIntegerType() {
        // `limit` is plain integer (NOT nullable like capCents).
        assertEquals(
            "integer",
            properties["limit"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test fun booleanTypedPropertiesHaveBooleanType() {
        for (key in listOf("enabled", "prettyPrint")) {
            assertEquals(
                "boolean",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=boolean",
            )
        }
    }

    // ── 5. capCents nullable-type pin ───────────────────────

    @Test fun capCentsHasNullableIntegerTypeArray() {
        // Marquee unusual-schema pin: per source line 121-124,
        // `capCents.type` is the JSON Schema array form
        // `["integer", "null"]` — allowing BOTH integer and
        // null. Drift to plain "integer" would silently
        // break the "null clears the cap" semantic the
        // dispatcher relies on.
        val capCents = properties["capCents"]?.jsonObject
        assertNotNull(capCents, "capCents MUST be present")
        val type = capCents["type"]
        assertNotNull(type, "capCents MUST declare type")
        assertTrue(
            type is JsonArray,
            "capCents.type MUST be a JsonArray (the nullable-type form); got: $type",
        )
        val typeValues = type.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("integer", "null"),
            typeValues,
            "capCents.type MUST be exactly ['integer', 'null']",
        )
    }

    @Test fun capCentsDescriptionExplainsThreeStateSemantic() {
        // Pin: capCents description tells the LLM the 3
        // states (null clears / 0 blocks / positive sets).
        // Drift to drop the explanation silently leaves
        // LLM unable to use the field correctly.
        val desc = properties["capCents"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("capCents missing description")
        assertTrue("null clears" in desc, "capCents desc MUST cite 'null clears'; got: $desc")
        assertTrue(
            "0 blocks" in desc,
            "capCents desc MUST cite '0 blocks paid AIGC'; got: $desc",
        )
        assertTrue(
            "positive" in desc,
            "capCents desc MUST cite 'positive sets'; got: $desc",
        )
    }

    // ── 6. Critical description pins ────────────────────────

    @Test fun anchorMessageIdDescriptionForksAndReverts() {
        // Pin: anchorMessageId is dual-role — `fork`
        // (optional partial-copy anchor) vs `revert`
        // (REQUIRED rewind target). Drift to drop either
        // role silently changes LLM expectations of which
        // verbs need this field.
        val desc = properties["anchorMessageId"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("anchorMessageId missing description")
        assertTrue("fork" in desc, "anchorMessageId desc MUST cite fork role; got: $desc")
        assertTrue("revert" in desc, "anchorMessageId desc MUST cite revert role; got: $desc")
        assertTrue(
            "REQUIRED" in desc,
            "anchorMessageId desc MUST cite revert-REQUIRED; got: $desc",
        )
    }

    @Test fun strategyDescriptionEnumeratesCompactStrategies() {
        // Pin: strategy description tells the LLM the 2
        // compact strategies + the prune_only aliases
        // (`prune` / `no_summary`). Drift to drop an alias
        // would silently break agent's natural form.
        val desc = properties["strategy"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("strategy missing description")
        assertTrue(
            "summarize_and_prune" in desc,
            "strategy desc MUST cite summarize_and_prune; got: $desc",
        )
        assertTrue(
            "prune_only" in desc,
            "strategy desc MUST cite prune_only; got: $desc",
        )
        for (alias in listOf("prune", "no_summary")) {
            assertTrue(
                alias in desc,
                "strategy desc MUST cite alias '$alias'; got: $desc",
            )
        }
        assertTrue(
            "default" in desc.lowercase(),
            "strategy desc MUST mark a default; got: $desc",
        )
    }

    @Test fun envelopeDescriptionDocumentsImportContract() {
        // Pin: envelope description tells the LLM the import
        // round-trip contract (formatVersion checked,
        // projectId must exist, sessionId collision fails).
        // Drift to drop any of the three would silently
        // change LLM's understanding of import safety.
        val desc = properties["envelope"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("envelope missing description")
        assertTrue(
            "formatVersion" in desc,
            "envelope desc MUST cite formatVersion check; got: $desc",
        )
        assertTrue(
            "projectId" in desc,
            "envelope desc MUST cite projectId requirement; got: $desc",
        )
        assertTrue(
            "collision" in desc.lowercase() || "fails" in desc,
            "envelope desc MUST cite collision/fails; got: $desc",
        )
    }
}
