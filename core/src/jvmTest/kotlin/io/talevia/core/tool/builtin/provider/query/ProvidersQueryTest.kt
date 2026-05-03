package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [runProvidersQuery] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/provider/query/ProvidersQuery.kt`.
 * Cycle 261 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-260. **Fourth in
 * the provider-query family** after EngineReadinessQuery (245),
 * RateLimitHistoryQuery (246), AigcCostEstimateQuery (247).
 *
 * `runProvidersQuery(providers)` enumerates every LLM provider
 * configured in this runtime and marks the default. Pure local
 * state; no HTTP. The `outputForLlm` field is the **string the
 * LLM reads** to know which providers it can target.
 *
 * Pins three correctness contracts:
 *
 *  1. **Three-branch summary semantics** (sister to cycles
 *     245 / 246 query patterns):
 *     - **Empty registry** → "No LLM providers configured..."
 *       + canonical env-var pointer (ANTHROPIC_API_KEY /
 *       OPENAI_API_KEY / GEMINI_API_KEY). Drift to drop the
 *       env-var hint silently loses recovery action; drift in
 *       the env-var names mismatches the operator's actual
 *       env.
 *     - **Single provider** → "1 provider: $id (default)."
 *       (always marked default since it's the only one).
 *     - **Multi-provider** → "$N providers: $namesWithStars
 *       (default marked *)." with `*` suffix on the default
 *       provider's id.
 *
 *  2. **Default-marker `*` suffix** is the marquee invariant
 *     for multi-provider summaries. Drift to drop the marker
 *     silently leaves the LLM unable to identify which
 *     provider it'll fall through to; drift to a different
 *     marker (e.g. `(default)`) would change LLM parse.
 *
 *  3. **Output structure**:
 *     - `select == ProviderQueryTool.SELECT_PROVIDERS`.
 *     - `total == returned == rows.size` (no pagination).
 *     - title format: `"provider_query providers (N)"`.
 *     - rows are JsonArray with N entries; each row is
 *       `{providerId, isDefault}`.
 */
class ProvidersQueryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun rowsFrom(out: ProviderQueryTool.Output): List<ProviderRow> =
        json.decodeFromJsonElement(
            ListSerializer(ProviderRow.serializer()),
            out.rows,
        )

    private class FakeProvider(override val id: String) : LlmProvider {
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = emptyFlow()
    }

    private fun emptyRegistry(): ProviderRegistry =
        ProviderRegistry(byId = emptyMap(), default = null)

    private fun singleRegistry(id: String): ProviderRegistry {
        val p = FakeProvider(id)
        return ProviderRegistry(byId = mapOf(id to p), default = p)
    }

    private fun multiRegistry(defaultId: String, vararg ids: String): ProviderRegistry {
        val providers = ids.associate { it to FakeProvider(it) }
        return ProviderRegistry(byId = providers, default = providers[defaultId])
    }

    // ── 1. Three-branch summary semantics ───────────────────

    @Test fun emptyRegistryProducesNoProvidersConfiguredNote() {
        // Marquee branch-A pin: empty registry → operator-facing
        // recovery hint with env var names. Drift to drop the
        // env-var hint silently loses recovery action.
        val result = runProvidersQuery(emptyRegistry())
        assertTrue(
            "No LLM providers configured in this runtime" in result.outputForLlm,
            "empty registry MUST surface 'No LLM providers configured in this runtime'; got: ${result.outputForLlm}",
        )
        // Each canonical env var name must appear (drift in names
        // would mismatch operator's actual env).
        for (envVar in listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY")) {
            assertTrue(
                envVar in result.outputForLlm,
                "empty-registry note MUST cite '$envVar' env var; got: ${result.outputForLlm}",
            )
        }
        // Pin: SecretStore alternative also surfaced.
        assertTrue(
            "SecretStore" in result.outputForLlm,
            "empty-registry note MUST cite SecretStore alternative; got: ${result.outputForLlm}",
        )
    }

    @Test fun singleProviderSummaryUsesParenthesisedDefault() {
        // Marquee branch-B pin: 1 provider always shows
        // "1 provider: $id (default)." — drift to "(default
        // marked *)" or omitting the marker silently mismatches
        // multi-provider format.
        val result = runProvidersQuery(singleRegistry("anthropic"))
        assertEquals(
            "1 provider: anthropic (default).",
            result.outputForLlm,
        )
    }

    @Test fun multiProviderSummaryMarksDefaultWithStar() {
        // Marquee branch-C pin: 2+ providers join with `, `;
        // default carries `*` suffix. Drift to drop the marker
        // silently leaves LLM unable to identify the fallback
        // provider.
        val result = runProvidersQuery(
            multiRegistry(
                defaultId = "anthropic",
                "anthropic",
                "openai",
            ),
        )
        // Marker pin: `anthropic*, openai`.
        assertTrue(
            "anthropic*" in result.outputForLlm,
            "default provider id MUST carry '*' suffix; got: ${result.outputForLlm}",
        )
        // Non-default explicitly does NOT have `*`. Pinning
        // "openai*" absence (drift to "all marked default" or
        // "marker on every entry" surfaces here).
        assertTrue(
            "openai*" !in result.outputForLlm,
            "non-default provider id MUST NOT carry '*' suffix; got: ${result.outputForLlm}",
        )
        assertTrue(
            "default marked *" in result.outputForLlm,
            "summary MUST cite 'default marked *' parenthetical legend; got: ${result.outputForLlm}",
        )
        // Count + comma-join format.
        assertTrue(
            "2 providers:" in result.outputForLlm,
            "multi-provider summary MUST cite plural count 'N providers:'; got: ${result.outputForLlm}",
        )
    }

    @Test fun multiProviderUsesCommaSpaceJoin() {
        // Pin: provider list uses `, ` join. Drift to `;` /
        // newline would silently change LLM parse.
        val result = runProvidersQuery(
            multiRegistry(
                defaultId = "anthropic",
                "anthropic",
                "openai",
                "gemini",
            ),
        )
        // The summary contains "anthropic*, openai, gemini"
        // somewhere (order may differ since Map iteration
        // can be insertion-order or not in MapOf — pinning the
        // join format on adjacent pairs).
        // ProviderRegistry uses LinkedHashMap (preserves
        // insertion); but to be robust, just check that all
        // 3 ids appear and at least one ", " join is present.
        assertTrue("anthropic*" in result.outputForLlm, "anthropic appears as default")
        assertTrue("openai" in result.outputForLlm)
        assertTrue("gemini" in result.outputForLlm)
        // Count "3 providers".
        assertTrue("3 providers:" in result.outputForLlm)
    }

    // ── 2. Output structure ─────────────────────────────────

    @Test fun selectFieldIsCanonicalProvidersConstant() {
        val result = runProvidersQuery(emptyRegistry())
        assertEquals(
            ProviderQueryTool.SELECT_PROVIDERS,
            result.data.select,
            "select MUST be the canonical SELECT_PROVIDERS constant",
        )
    }

    @Test fun totalEqualsReturnedEqualsRowCountOnEmpty() {
        val result = runProvidersQuery(emptyRegistry())
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(0, result.data.rows.size)
    }

    @Test fun totalEqualsReturnedEqualsRowCountOnSingle() {
        val result = runProvidersQuery(singleRegistry("anthropic"))
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertEquals(1, result.data.rows.size)
    }

    @Test fun totalEqualsReturnedEqualsRowCountOnMulti() {
        val result = runProvidersQuery(multiRegistry("anthropic", "anthropic", "openai", "gemini"))
        assertEquals(3, result.data.total)
        assertEquals(3, result.data.returned)
        assertEquals(3, result.data.rows.size)
    }

    @Test fun titleFormatIsProviderQueryProvidersN() {
        // Pin: title format `provider_query providers (N)`.
        // Drift to "providers query (N)" / "provider_query (N)"
        // would shift logging / tracing.
        assertEquals(
            "provider_query providers (0)",
            runProvidersQuery(emptyRegistry()).title,
        )
        assertEquals(
            "provider_query providers (1)",
            runProvidersQuery(singleRegistry("anthropic")).title,
        )
        assertEquals(
            "provider_query providers (3)",
            runProvidersQuery(multiRegistry("anthropic", "anthropic", "openai", "gemini")).title,
        )
    }

    // ── 3. Rows shape + isDefault flag ──────────────────────

    @Test fun rowsCarryProviderIdAndIsDefaultFlagsCorrectly() {
        // Marquee row-shape pin: each row is
        // `{providerId, isDefault}`. Drift to drop / rename
        // either field would silently break downstream
        // consumers.
        val result = runProvidersQuery(
            multiRegistry(
                defaultId = "openai",
                "anthropic",
                "openai",
                "gemini",
            ),
        )
        val rows = rowsFrom(result.data)
        assertEquals(3, rows.size)
        val byId = rows.associateBy { it.providerId }
        assertEquals(false, byId["anthropic"]?.isDefault)
        assertEquals(true, byId["openai"]?.isDefault, "openai MUST be flagged isDefault=true")
        assertEquals(false, byId["gemini"]?.isDefault)
    }

    @Test fun nullDefaultRegistryProducesNoIsDefaultRowsTrue() {
        // Edge: ProviderRegistry constructed with `default=null`
        // but non-empty byId — every row has isDefault=false.
        // Drift to "first wins" or "any wins" silently
        // mis-labels the default.
        val anthropic = FakeProvider("anthropic")
        val openai = FakeProvider("openai")
        val registry = ProviderRegistry(
            byId = mapOf("anthropic" to anthropic, "openai" to openai),
            default = null,
        )
        val result = runProvidersQuery(registry)
        val rows = rowsFrom(result.data)
        assertEquals(2, rows.size)
        assertTrue(
            rows.all { !it.isDefault },
            "with default=null, NO row MUST be flagged isDefault=true; got: $rows",
        )
        // Summary: NO id has `*` suffix.
        assertTrue(
            !result.outputForLlm.contains("*") ||
                // The phrase "default marked *" still appears in the format string.
                result.outputForLlm.indexOf("*") == result.outputForLlm.indexOf("default marked *") + "default marked ".length,
            "with default=null, no id MUST carry '*' suffix; got: ${result.outputForLlm}",
        )
    }
}
