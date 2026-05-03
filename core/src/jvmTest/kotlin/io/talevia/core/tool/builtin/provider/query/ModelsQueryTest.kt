package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [runModelsQuery] —
 * `core/tool/builtin/provider/query/ModelsQuery.kt`. The
 * SessionQueryTool's `select=models` handler that fetches
 * one provider's model catalog. Cycle 190 audit: 78 LOC,
 * 0 direct test refs (used through full-tool integration
 * but the provider-side-failure-via-Output.error
 * surfacing — vs throwing — was never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Network / auth / rate-limit failures surface via
 *    `Output.error` with empty rows, NOT exceptions.** The
 *    marquee robustness pin: drift to "throw on failure"
 *    would crash the agent's "let me see what models you
 *    have" reconnaissance flow on every transient
 *    provider issue. Tested via a fake LlmProvider whose
 *    `listModels` throws.
 *
 * 2. **`Output.error == null` on success; non-null on
 *    failure with the throwable's message.** Drift to
 *    "always populate error" or "echo throwable type only"
 *    would change consumer-side branching.
 *
 * 3. **Unknown providerId throws with documented hint.**
 *    Per impl: `error("Provider 'X' is not registered.
 *    Call provider_query(select=providers)…")`. The
 *    discoverability hint must surface so the agent can
 *    self-correct typos.
 */
class ModelsQueryTest {

    private fun fakeProvider(
        id: String,
        models: List<ModelInfo> = emptyList(),
        thrown: Throwable? = null,
    ): LlmProvider = object : LlmProvider {
        override val id: String = id
        override suspend fun listModels(): List<ModelInfo> =
            thrown?.let { throw it } ?: models
        override fun stream(request: LlmRequest): Flow<LlmEvent> = emptyFlow()
    }

    private fun registry(vararg providers: LlmProvider): ProviderRegistry =
        ProviderRegistry.Builder().apply {
            providers.forEach { add(it) }
        }.build()

    private fun rowFields(
        rows: kotlinx.serialization.json.JsonArray,
        key: String,
    ): List<String> = rows.map { row ->
        row.toString().substringAfter("\"$key\":\"").substringBefore("\"")
    }

    // ── Unknown providerId → throws with hint ────────────────

    @Test fun unknownProviderIdThrowsWithDiscoverabilityHint() = runTest {
        val registry = registry()
        val ex = assertFailsWith<IllegalStateException> {
            runModelsQuery(registry, providerId = "ghost")
        }
        assertTrue(
            "not registered" in (ex.message ?: ""),
            "expected not-registered phrase; got: ${ex.message}",
        )
        assertTrue(
            "ghost" in (ex.message ?: ""),
            "expected providerId in message; got: ${ex.message}",
        )
        assertTrue(
            "provider_query(select=providers)" in (ex.message ?: ""),
            "expected discoverability hint; got: ${ex.message}",
        )
    }

    // ── Empty model list ─────────────────────────────────────

    @Test fun emptyModelListReturnsEmptyResultWithFriendlyBody() = runTest {
        val provider = fakeProvider("anthropic", models = emptyList())
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "anthropic")

        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(0, result.data.rows.size)
        assertNull(result.data.error, "no error on empty success")
        // Body cites "no models" with implementation hint.
        assertTrue(
            "returned no models" in result.outputForLlm,
            "outputForLlm cites no-models; got: ${result.outputForLlm}",
        )
        assertTrue(
            "listModels may not be implemented yet" in result.outputForLlm,
            "outputForLlm cites impl hint; got: ${result.outputForLlm}",
        )
    }

    // ── Non-empty model list ────────────────────────────────

    @Test fun providerWithModelsReturnsAllRowsWithCount() = runTest {
        val provider = fakeProvider(
            id = "anthropic",
            models = listOf(
                ModelInfo(
                    id = "claude-3-opus",
                    name = "Claude 3 Opus",
                    contextWindow = 200_000,
                    supportsTools = true,
                    supportsThinking = true,
                    supportsImages = true,
                ),
                ModelInfo(
                    id = "claude-3-haiku",
                    name = "Claude 3 Haiku",
                    contextWindow = 100_000,
                    supportsTools = true,
                ),
            ),
        )
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "anthropic")
        assertEquals(2, result.data.total)
        assertEquals(2, result.data.returned)
        assertEquals(2, result.data.rows.size)
        assertNull(result.data.error)
        // Body cites count + lists model ids.
        assertTrue("2 model(s) on anthropic" in result.outputForLlm)
        assertTrue("claude-3-opus" in result.outputForLlm)
        assertTrue("claude-3-haiku" in result.outputForLlm)
    }

    // ── ModelRow shape ───────────────────────────────────────

    @Test fun modelRowExposesAllDocumentedFields() = runTest {
        val provider = fakeProvider(
            id = "openai",
            models = listOf(
                ModelInfo(
                    id = "gpt-5",
                    name = "GPT-5",
                    contextWindow = 1_000_000,
                    supportsTools = true,
                    supportsThinking = true,
                    supportsImages = true,
                ),
            ),
        )
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "openai")
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"providerId\":\"openai\"" in rowJson, "providerId; got: $rowJson")
        assertTrue("\"modelId\":\"gpt-5\"" in rowJson, "modelId")
        assertTrue("\"name\":\"GPT-5\"" in rowJson, "name")
        assertTrue("\"contextWindow\":1000000" in rowJson, "contextWindow")
        assertTrue("\"supportsTools\":true" in rowJson, "supportsTools")
        assertTrue("\"supportsThinking\":true" in rowJson, "supportsThinking")
        assertTrue("\"supportsImages\":true" in rowJson, "supportsImages")
    }

    @Test fun modelRowProviderIdIsTheProviderNotTheCallerInput() = runTest {
        // Pin: per impl `providerId = provider.id` — the
        // row's providerId comes from the provider object,
        // NOT from the caller's input. Drift to "echo
        // input" would let the row carry a typo'd id even
        // when the registry resolved the correct provider.
        val provider = fakeProvider(
            "anthropic",
            models = listOf(ModelInfo("claude", "Claude", 100, true)),
        )
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "anthropic")
        assertEquals("anthropic", rowFields(result.data.rows, "providerId").single())
    }

    @Test fun supportsImagesAndThinkingDefaultToFalseInModelInfo() = runTest {
        // Pin: ModelInfo.supportsThinking and
        // .supportsImages default to false. ModelRow
        // surfaces them with the same defaults.
        val provider = fakeProvider(
            id = "test",
            models = listOf(
                ModelInfo(id = "x", name = "X", contextWindow = 1, supportsTools = false),
            ),
        )
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "test")
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"supportsThinking\":false" in rowJson)
        assertTrue("\"supportsImages\":false" in rowJson)
    }

    // ── Provider-side failure surfaces via Output.error ─────

    @Test fun providerThrowingExceptionSurfaceEmptyRowsAndErrorMessage() = runTest {
        // Marquee robustness pin: drift to "throw on
        // failure" would crash the agent's "let me see
        // what models you have" reconnaissance flow on
        // every transient provider issue. The handler must
        // catch.
        val provider = fakeProvider(
            "anthropic",
            thrown = RuntimeException("HTTP 503 service unavailable"),
        )
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "anthropic")
        assertEquals(0, result.data.total, "rows empty on failure")
        assertEquals(0, result.data.returned)
        assertEquals(0, result.data.rows.size)
        assertNotNull(result.data.error, "error populated")
        assertTrue(
            "503 service unavailable" in result.data.error!!,
            "error preserves throwable message; got: ${result.data.error}",
        )
        // Body cites "Failed to list models" + error.
        assertTrue(
            "Failed to list models" in result.outputForLlm,
            "outputForLlm cites failure; got: ${result.outputForLlm}",
        )
        assertTrue(
            "anthropic" in result.outputForLlm,
            "outputForLlm cites providerId on failure",
        )
    }

    @Test fun providerThrowingNullMessageSurfacesClassNameAsErrorFallback() = runTest {
        // Pin: per impl `it.message ?: it::class.simpleName
        // ?: "unknown error"` — null message falls back to
        // the throwable's class name. Drift to "blank
        // string" or "throw NPE" would silently lose
        // diagnostic info on every Throwable subclass with
        // no message.
        class MyCustomException : RuntimeException()
        val provider = fakeProvider("anthropic", thrown = MyCustomException())
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "anthropic")
        assertNotNull(result.data.error)
        assertTrue(
            "MyCustomException" in result.data.error!!,
            "error falls back to class name; got: ${result.data.error}",
        )
    }

    // ── Truncation in summary ────────────────────────────────

    @Test fun summaryTruncatesAfterFiveModelIdsWithEllipsis() = runTest {
        val models = (1..7).map { i ->
            ModelInfo(
                id = "model-$i",
                name = "Model $i",
                contextWindow = 100,
                supportsTools = true,
            )
        }
        val provider = fakeProvider("anthropic", models = models)
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "anthropic")
        assertEquals(7, result.data.total)
        assertTrue(
            result.outputForLlm.endsWith(", …"),
            "truncation suffix appears; got: ${result.outputForLlm}",
        )
        assertTrue("model-1" in result.outputForLlm)
        assertTrue("model-5" in result.outputForLlm)
        // 6th and 7th NOT in summary.
        assertTrue(
            "model-6" !in result.outputForLlm,
            "6th model excluded from summary preview",
        )
    }

    @Test fun summaryNoEllipsisAtExactlyFiveModels() = runTest {
        val models = (1..5).map { i ->
            ModelInfo(
                id = "model-$i",
                name = "Model $i",
                contextWindow = 100,
                supportsTools = true,
            )
        }
        val provider = fakeProvider("anthropic", models = models)
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "anthropic")
        assertTrue(
            !result.outputForLlm.endsWith(", …"),
            "no ellipsis at exactly 5; got: ${result.outputForLlm}",
        )
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsModels() = runTest {
        val provider = fakeProvider("anthropic")
        val registry = registry(provider)
        val result = runModelsQuery(registry, providerId = "anthropic")
        assertEquals(ProviderQueryTool.SELECT_MODELS, result.data.select)
    }

    @Test fun toolResultTitleCitesProviderIdAndCount() = runTest {
        val provider = fakeProvider(
            "anthropic",
            models = listOf(ModelInfo("a", "A", 100, true), ModelInfo("b", "B", 100, true)),
        )
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "anthropic")
        assertTrue("anthropic" in result.title!!)
        assertTrue("(2)" in result.title!!, "title cites count; got: ${result.title}")
    }

    // ── total == returned (no pagination on this select) ─────

    @Test fun totalAndReturnedAreEqualNoPaginationForModels() = runTest {
        // Per kdoc: "Count of rows in rows; equals total
        // — no offset / limit on this select yet (both
        // selects are small enough that paging isn't
        // needed)." Drift to applying pagination would
        // change consumer-side decode.
        val models = (1..10).map { i ->
            ModelInfo(id = "m-$i", name = "M$i", contextWindow = 100, supportsTools = true)
        }
        val provider = fakeProvider("anthropic", models = models)
        val registry = registry(provider)

        val result = runModelsQuery(registry, providerId = "anthropic")
        assertEquals(10, result.data.total)
        assertEquals(10, result.data.returned, "returned == total (no paging)")
        assertEquals(10, result.data.rows.size)
    }
}
