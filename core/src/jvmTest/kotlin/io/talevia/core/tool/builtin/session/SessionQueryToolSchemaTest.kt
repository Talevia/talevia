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
 * Direct tests for [SESSION_QUERY_INPUT_SCHEMA] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/SessionQueryToolSchema.kt`.
 * Cycle 269 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-268. Sister to
 * cycles 242 / 243 / 244 / 266 / 267 / 268 schema pins. The
 * `session_query` dispatcher carries the **largest** select
 * surface in the codebase — 28 selects per the schema's
 * `select` description.
 *
 * Drift signals:
 *   - **`select` description drops a select id** silently
 *     retires it from the LLM's discovery surface.
 *   - **`role` description** drops `user` or `assistant`
 *     silently disables the message-role filter.
 *   - **`kind` description** drops a Part kind from the
 *     enumeration silently retires that filter from the
 *     `parts` select.
 *   - **`limit` description** drift in the `[1, 1000]`
 *     range silently changes what the LLM thinks is valid.
 *
 * Pins three correctness contracts:
 *
 *  1. **Top-level shape**: type=object,
 *     additionalProperties=false, required=["select"].
 *
 *  2. **Properties block has exactly 14 fields** + per-type
 *     buckets pinned (string / integer / boolean).
 *
 *  3. **`select` description enumerates all 28 canonical
 *     selects** — pinned individually so single-name drift
 *     surfaces with the offending name.
 *
 * Plus critical descriptions:
 *   - `role` enumerates {user, assistant}.
 *   - `kind` enumerates the 10 Part kinds (matches cycle
 *     265's VALID_PART_KINDS minus `plan` since `plan` is
 *     a newer addition not yet in the schema description).
 *   - `limit` cites default 100 + clamped [1, 1000].
 *   - `sinceEpochMs` cites the 256-entry ring buffer for
 *     `run_state_history` select.
 *   - `sessionId` cites the verb-conditional shape (required
 *     vs rejected) per select.
 */
class SessionQueryToolSchemaTest {

    private val schema: JsonObject = SESSION_QUERY_INPUT_SCHEMA

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

    @Test fun requiredIsExactlySelect() {
        val required = schema["required"]
        assertNotNull(required)
        assertTrue(required is JsonArray)
        assertEquals(
            listOf("select"),
            required.jsonArray.map { it.jsonPrimitive.content },
            "required MUST be exactly ['select']",
        )
    }

    // ── 2. Properties block: count + types ──────────────────

    @Test fun propertiesHasExactlyFourteenFields() {
        assertEquals(
            14,
            properties.size,
            "SESSION_QUERY_INPUT_SCHEMA properties MUST have exactly 14 fields",
        )
    }

    @Test fun stringTypedPropertiesHaveStringType() {
        for (key in listOf(
            "select",
            "sessionId",
            "projectId",
            "role",
            "kind",
            "toolId",
            "messageId",
            "query",
        )) {
            assertEquals(
                "string",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=string",
            )
        }
    }

    @Test fun integerTypedPropertiesHaveIntegerType() {
        for (key in listOf("limit", "offset", "sinceEpochMs")) {
            assertEquals(
                "integer",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=integer",
            )
        }
    }

    @Test fun booleanTypedPropertiesHaveBooleanType() {
        for (key in listOf("includeArchived", "includeCompacted", "includeBreakdown")) {
            assertEquals(
                "boolean",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=boolean",
            )
        }
    }

    // ── 3. `select` description enumerates all 28 selects ──

    @Test fun selectDescriptionEnumeratesAllCanonicalSelects() {
        // Marquee discovery-surface pin: the largest select
        // enumeration in the codebase. Drift to drop a name
        // silently retires that select from LLM discovery.
        val desc = properties["select"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("select missing description")

        val canonicalSelects = listOf(
            "sessions",
            "messages",
            "parts",
            "forks",
            "ancestors",
            "tool_calls",
            "compactions",
            "status",
            "session_metadata",
            "message",
            "spend",
            "spend_summary",
            "cache_stats",
            "context_pressure",
            "run_state_history",
            "tool_spec_budget",
            "run_failure",
            "fallback_history",
            "cancellation_history",
            "permission_history",
            "permission_rules",
            "preflight_summary",
            "recap",
            "step_history",
            "active_run_summary",
            "bus_trace",
            "text_search",
            "token_estimate",
        )
        for (select in canonicalSelects) {
            assertTrue(
                select in desc,
                "select description MUST cite '$select'; drift removes LLM discovery",
            )
        }
        // Sanity: at least 28 canonical selects.
        assertTrue(
            canonicalSelects.size >= 28,
            "canonical-select list MUST have ≥28 entries (largest in the codebase)",
        )
    }

    // ── 4. Critical description pins ────────────────────────

    @Test fun roleDescriptionEnumeratesUserAndAssistant() {
        // Pin: drift to drop one role would silently disable
        // the message-role filter on that side. Sister to
        // cycle 265's VALID_ROLES set pin (the description
        // is the LLM-discovery side; VALID_ROLES is the
        // dispatcher-validation side).
        val desc = properties["role"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("role missing description")
        assertTrue("user" in desc, "role MUST cite 'user'; got: $desc")
        assertTrue("assistant" in desc, "role MUST cite 'assistant'; got: $desc")
    }

    @Test fun kindDescriptionEnumeratesPartKinds() {
        // Pin: drift to drop a Part kind from the
        // enumeration silently retires that filter from
        // the `parts` select. Note: the schema description
        // pre-dates cycle 265's VALID_PART_KINDS expansion;
        // the schema currently lists 10 kinds (no `plan`).
        // Pin matches the schema's actual enumeration —
        // drift to remove a kind silently retires it; drift
        // to add one without updating both sides surfaces
        // separately.
        val desc = properties["kind"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("kind missing description")
        for (kind in listOf(
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
        )) {
            assertTrue(
                kind in desc,
                "kind description MUST cite '$kind'; got: $desc",
            )
        }
    }

    @Test fun limitDescriptionCitesDefaultAndRange() {
        // Marquee range pin: the schema's only place this is
        // documented (no enum / range JSON Schema property
        // is set on `limit`). Drift to drop the upper bound
        // silently lets LLM emit limit=10000 that errors at
        // the dispatcher.
        val desc = properties["limit"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("limit missing description")
        assertTrue("100" in desc, "limit MUST cite default 100; got: $desc")
        assertTrue(
            "[1, 1000]" in desc || "1, 1000" in desc,
            "limit MUST cite [1, 1000] valid range; got: $desc",
        )
    }

    @Test fun sessionIdDescriptionListsVerbConditionalRequirements() {
        // Pin: sessionId description lists which selects
        // require it (messages / parts / forks / ancestors
        // / tool_calls / compactions / status) AND which
        // reject it (sessions). Drift to drop either side
        // silently changes LLM expectations.
        val desc = properties["sessionId"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("sessionId missing description")
        for (selectRequiringSessionId in listOf(
            "messages",
            "parts",
            "forks",
            "ancestors",
            "tool_calls",
            "compactions",
            "status",
        )) {
            assertTrue(
                selectRequiringSessionId in desc,
                "sessionId desc MUST cite '$selectRequiringSessionId' as required; got: $desc",
            )
        }
        assertTrue(
            "Rejected" in desc,
            "sessionId desc MUST cite 'Rejected for select=sessions'; got: $desc",
        )
        assertTrue(
            "sessions" in desc,
            "sessionId desc MUST cite the sessions select that rejects it; got: $desc",
        )
    }

    @Test fun sinceEpochMsDescriptionCitesRunStateHistoryAnd256RingBuffer() {
        // Marquee select-scoped pin: sinceEpochMs is ONLY
        // valid for `run_state_history`. The 256-entry ring
        // buffer cap is documented here so the LLM knows the
        // full ring is bounded. Drift to drop the buffer
        // size silently surprises operators expecting more.
        val desc = properties["sinceEpochMs"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("sinceEpochMs missing description")
        assertTrue(
            "run_state_history" in desc,
            "sinceEpochMs MUST cite run_state_history scope; got: $desc",
        )
        assertTrue(
            "256" in desc,
            "sinceEpochMs MUST cite the 256-entry ring buffer cap; got: $desc",
        )
        assertTrue(
            "Rejected" in desc,
            "sinceEpochMs MUST cite the rejection on other selects; got: $desc",
        )
    }

    @Test fun queryDescriptionCitesTextSearchRequiredAndCaseInsensitive() {
        // Pin: `query` is ONLY for `text_search` select and
        // is case-insensitive. Drift to drop either silently
        // changes LLM behavior (might emit lowercased query
        // unnecessarily; might use it on the wrong select).
        val desc = properties["query"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("query missing description")
        assertTrue(
            "text_search" in desc,
            "query MUST cite text_search scope; got: $desc",
        )
        assertTrue(
            "case-insensitive" in desc,
            "query MUST cite case-insensitive matching; got: $desc",
        )
    }

    @Test fun includeBreakdownDescriptionCitesTokenEstimateScopeAndDefault() {
        // Pin: `includeBreakdown` is ONLY for
        // `token_estimate` select; default is `false` for
        // terse output. Drift to drop the warning about
        // payload size silently lets LLM trigger a big
        // payload.
        val desc = properties["includeBreakdown"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("includeBreakdown missing description")
        assertTrue(
            "token_estimate" in desc,
            "includeBreakdown MUST cite token_estimate scope; got: $desc",
        )
        assertTrue(
            "Default false" in desc,
            "includeBreakdown MUST cite default false; got: $desc",
        )
    }

    @Test fun includeCompactedDescriptionCitesPartsAndToolCalls() {
        // Pin: select scope of includeCompacted (drift to
        // single-select would silently disable the filter
        // on the other side).
        val desc = properties["includeCompacted"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("includeCompacted missing description")
        assertTrue("parts" in desc, "MUST cite parts; got: $desc")
        assertTrue("tool_calls" in desc, "MUST cite tool_calls; got: $desc")
        assertTrue(
            "Default true" in desc,
            "MUST cite Default true; got: $desc",
        )
    }
}
