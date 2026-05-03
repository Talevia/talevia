package io.talevia.core.tool.builtin.source

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
 * Direct tests for [SOURCE_QUERY_INPUT_SCHEMA] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/source/SourceQueryToolSchema.kt`.
 * Cycle 244 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-243. Sister to cycles
 * 242 / 243's [IMPORT_MEDIA_INPUT_SCHEMA] / [FORK_PROJECT_INPUT_SCHEMA]
 * pins — the file kdoc carries the same "byte-identical to the
 * previous inline definition; every field description is preserved
 * verbatim" promise.
 *
 * The schema is the LLM-visible contract for `source_query`: the
 * agent reads the descriptions to decide which `select` to use
 * and which filters apply per-select. Drift in the `select`
 * enumeration leaves the LLM guessing whether a select id is
 * still valid; drift in the per-filter "X only" annotations would
 * silently cause the LLM to send a filter against the wrong select
 * (which the executor rejects as a less-useful "incompatible
 * filter" error).
 *
 * Pins three correctness contracts:
 *
 *  1. **Top-level shape**: `type=object`,
 *     `additionalProperties=false`, `required=["select"]`. The
 *     single-key required pin matches the dispatcher (every other
 *     parameter is optional or select-conditional).
 *
 *  2. **Properties block has exactly the 16 documented fields**
 *     (select, projectId, scope, kind, kindPrefix,
 *     contentSubstring, caseSensitive, id, includeBody, sortBy,
 *     hasParent, hotspotLimit, root, depth, limit, offset).
 *
 *  3. **Select enumeration completeness.** The `select` field's
 *     description enumerates ALL 10 supported select ids:
 *     `nodes`, `dag_summary`, `dot`, `ascii_tree`, `orphans`,
 *     `leaves`, `descendants`, `ancestors`, `history`,
 *     `node_detail`. Drift to drop any single id from the
 *     description leaves the LLM guessing whether it's still
 *     valid; drift to ADD an unsupported id mis-routes to a
 *     failed dispatch. Plus the "case-insensitive" hint pin so
 *     the LLM knows `Nodes` / `NODES` work too.
 *
 * Plus per-property pins:
 *
 *   - Type sanity: boolean fields (`caseSensitive`, `includeBody`,
 *     `hasParent`) are `boolean`; integer fields (`hotspotLimit`,
 *     `depth`, `limit`, `offset`) are `integer` (NOT `number`,
 *     since fractional offsets / limits are nonsensical); string
 *     fields are `string`.
 *
 *   - Critical descriptions:
 *     - `scope`: enumerates `project (default)` and `all_projects`
 *       — drift to drop one would silently leave LLM guessing
 *       whether cross-project enumeration is supported.
 *     - `sortBy`: enumerates `id (default) | kind | revision-desc`.
 *     - `limit`: cites the `[1..500]` range (drift to looser
 *       "any positive int" lets LLM send 10000 that errors at
 *       executor).
 *     - `depth`: documents the 4-state contract (0 / positive /
 *       null / negative) — load-bearing for the LLM to reason
 *       about traversal scope.
 *     - `id`: dual-role description — `nodes` (optional filter)
 *       vs `node_detail` (required drilldown).
 *     - `root`: surfaces the "Required for
 *       descendants/ancestors/history" select-conditional
 *       requirement; drift to drop it leaves the LLM thinking
 *       traversal selects are unconditional.
 */
class SourceQueryToolSchemaTest {

    private val schema: JsonObject = SOURCE_QUERY_INPUT_SCHEMA

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

    @Test fun requiredIsExactlySelect() {
        val required = schema["required"]
        assertNotNull(required)
        assertTrue(required is JsonArray)
        val names = required.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("select"),
            names,
            "required MUST be exactly ['select'] — every other field is select-conditional",
        )
    }

    // ── 2. Properties — exactly 16 fields ───────────────────

    @Test fun propertiesContainsExactly16DocumentedFields() {
        assertEquals(
            setOf(
                "select",
                "projectId",
                "scope",
                "kind",
                "kindPrefix",
                "contentSubstring",
                "caseSensitive",
                "id",
                "includeBody",
                "sortBy",
                "hasParent",
                "hotspotLimit",
                "root",
                "depth",
                "limit",
                "offset",
            ),
            properties.keys,
            "properties MUST contain exactly the 16 documented fields",
        )
    }

    // ── 3. Per-property type pins ───────────────────────────

    @Test fun stringTypedPropertiesAreString() {
        for (key in listOf(
            "select",
            "projectId",
            "scope",
            "kind",
            "kindPrefix",
            "contentSubstring",
            "id",
            "sortBy",
            "root",
        )) {
            assertEquals(
                "string",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=string",
            )
        }
    }

    @Test fun booleanTypedPropertiesAreBoolean() {
        for (key in listOf("caseSensitive", "includeBody", "hasParent")) {
            assertEquals(
                "boolean",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=boolean",
            )
        }
    }

    @Test fun integerTypedPropertiesAreInteger() {
        // Marquee integer-vs-number pin: drift to "number" would
        // let the LLM send 100.5 as `limit` — fractional limits
        // are nonsensical for pagination. The schema enforces
        // integral types where the executor rounds anyway, so
        // the LLM doesn't waste tokens on float-form ints.
        for (key in listOf("hotspotLimit", "depth", "limit", "offset")) {
            assertEquals(
                "integer",
                properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content,
                "$key MUST be type=integer (NOT number — pagination is integral)",
            )
        }
    }

    // ── 4. Select enumeration completeness ──────────────────

    @Test fun selectDescriptionEnumeratesAllTenSupportedIds() {
        // Marquee LLM-mental-model pin: per the kdoc, select
        // accepts 10 ids. Drift to drop any id leaves the LLM
        // guessing whether the missing one is still valid; drift
        // to ADD an unsupported id silently mis-routes.
        val desc = properties["select"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("select missing description")
        for (id in listOf(
            "nodes",
            "dag_summary",
            "dot",
            "ascii_tree",
            "orphans",
            "leaves",
            "descendants",
            "ancestors",
            "history",
            "node_detail",
        )) {
            assertTrue(
                id in desc,
                "select description MUST cite '$id'; got: $desc",
            )
        }
        assertTrue(
            "case-insensitive" in desc,
            "select description MUST mention case-insensitivity (LLM may emit 'Nodes' / 'NODES'); got: $desc",
        )
    }

    // ── 5. Critical per-field descriptions ──────────────────

    @Test fun scopeDescriptionEnumeratesBothScopes() {
        // Pin: the scope field's description tells the LLM
        // whether cross-project enumeration is supported. Drift
        // to drop `all_projects` would silently let the LLM
        // assume per-project is the only option.
        val desc = properties["scope"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("scope missing description")
        assertTrue("project (default)" in desc, "scope description MUST cite 'project (default)'; got: $desc")
        assertTrue("all_projects" in desc, "scope description MUST cite 'all_projects'; got: $desc")
        assertTrue(
            "projectId" in desc,
            "scope description MUST mention that all_projects rows carry projectId; got: $desc",
        )
    }

    @Test fun sortByDescriptionEnumeratesAllThreeKeys() {
        val desc = properties["sortBy"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("sortBy missing description")
        assertTrue("id (default)" in desc, "sortBy MUST cite 'id (default)'; got: $desc")
        assertTrue("kind" in desc, "sortBy MUST cite 'kind'; got: $desc")
        assertTrue("revision-desc" in desc, "sortBy MUST cite 'revision-desc'; got: $desc")
    }

    @Test fun limitDescriptionCitesRange() {
        // Marquee range pin: the LLM uses this to avoid
        // requesting unreasonable counts. Drift to drop the
        // upper bound silently lets LLM emit `limit: 10000`
        // that errors at the executor.
        val desc = properties["limit"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("limit missing description")
        assertTrue("100" in desc, "limit MUST cite default 100; got: $desc")
        assertTrue(
            "[1..500]" in desc || "1..500" in desc,
            "limit MUST cite the [1..500] valid range; got: $desc",
        )
    }

    @Test fun depthDescriptionDocumentsFourStateContract() {
        // Marquee 4-state pin: depth's semantics are tricky —
        // 0 / positive / null / negative all behave differently.
        // Drift to drop any of the 4 cases would silently leave
        // LLM unable to reason about traversal scope.
        val desc = properties["depth"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("depth missing description")
        assertTrue("0=root only" in desc, "depth MUST cite '0=root only'; got: $desc")
        assertTrue("positive" in desc, "depth MUST mention positive=bounded; got: $desc")
        assertTrue(
            "unbounded" in desc,
            "depth MUST mention null/negative=unbounded; got: $desc",
        )
    }

    @Test fun idDescriptionCallsOutDualRole() {
        // Pin: id is optional for `nodes` (filter to ≤1 row)
        // but REQUIRED for `node_detail`. Drift to drop the
        // node_detail-required note silently lets LLM omit id
        // and get a confusing "node_detail: missing required
        // field" error rather than the schema-level signal.
        val desc = properties["id"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("id missing description")
        assertTrue("nodes" in desc, "id description MUST cite nodes role; got: $desc")
        assertTrue(
            "node_detail" in desc,
            "id description MUST cite node_detail role; got: $desc",
        )
        assertTrue(
            "required" in desc,
            "id description MUST surface the node_detail-required constraint; got: $desc",
        )
    }

    @Test fun rootDescriptionCitesSelectConditionalRequirement() {
        // Pin: root is conditionally required when select is
        // descendants / ancestors / history. Drift to drop the
        // select-conditional language leaves LLM thinking
        // traversal selects are unconditional.
        val desc = properties["root"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("root missing description")
        assertTrue(
            "Required for" in desc,
            "root description MUST surface select-conditional requirement; got: $desc",
        )
        for (sel in listOf("descendants", "ancestors", "history")) {
            assertTrue(
                sel in desc,
                "root description MUST cite select '$sel' as requiring root; got: $desc",
            )
        }
    }

    @Test fun projectIdDescriptionCitesAllProjectsConflict() {
        // Pin: per the description, projectId is REQUIRED unless
        // scope='all_projects', and the schema rejects projectId
        // when scope='all_projects'. Drift to drop this
        // mutual-conflict note would let LLM send both, getting
        // a less obvious dispatcher error.
        val desc = properties["projectId"]
            ?.jsonObject
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?: error("projectId missing description")
        assertTrue(
            "all_projects" in desc,
            "projectId description MUST cite all_projects scope conflict; got: $desc",
        )
        assertTrue(
            "Required" in desc || "required" in desc,
            "projectId description MUST cite the conditional-required role; got: $desc",
        )
    }

    @Test fun nodesOnlyFiltersAreDocumentedAsSuch() {
        // Pin: filters that ONLY apply to `select=nodes` carry
        // the `nodes only.` annotation in their description.
        // Drift to drop the annotation would silently let the
        // LLM send (e.g.) `kind` filter with `select=dag_summary`,
        // which the dispatcher rejects.
        for (key in listOf(
            "kind",
            "kindPrefix",
            "contentSubstring",
            "caseSensitive",
            "includeBody",
            "sortBy",
            "hasParent",
            "limit",
            "offset",
        )) {
            val desc = properties[key]
                ?.jsonObject
                ?.get("description")
                ?.jsonPrimitive
                ?.content
                ?: error("$key missing description")
            assertTrue(
                "nodes only" in desc,
                "$key description MUST carry the 'nodes only.' annotation; got: $desc",
            )
        }
    }
}
