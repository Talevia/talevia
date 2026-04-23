package io.talevia.core.tool.builtin.provider

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.provider.query.ModelRow
import io.talevia.core.tool.builtin.provider.query.ProviderRow
import io.talevia.core.tool.query.decodeRowsAs
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
 * Covers the unified `provider_query` tool — replaces
 * `list_providers` + `list_provider_models` (debt-consolidate-provider-queries).
 */
class ProviderQueryToolTest {

    private class FakeProvider(
        override val id: String,
        private val models: List<ModelInfo> = emptyList(),
        private val listModelsThrows: Throwable? = null,
    ) : LlmProvider {
        override suspend fun listModels(): List<ModelInfo> {
            listModelsThrows?.let { throw it }
            return models
        }
        override fun stream(request: LlmRequest): Flow<LlmEvent> = emptyFlow()
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun registry(vararg providers: LlmProvider): ProviderRegistry {
        val byId = providers.associateBy { it.id }
        return ProviderRegistry(byId, default = providers.firstOrNull())
    }

    @Test fun providersSelectEnumeratesAllWithDefaultMarker() = runTest {
        val reg = registry(FakeProvider("anthropic"), FakeProvider("openai"))
        val out = ProviderQueryTool(reg).execute(
            ProviderQueryTool.Input(select = "providers"),
            ctx(),
        ).data
        assertEquals("providers", out.select)
        assertEquals(2, out.total)
        assertNull(out.error)
        val rows = out.rows.decodeRowsAs(ProviderRow.serializer())
        assertEquals(setOf("anthropic", "openai"), rows.map { it.providerId }.toSet())
        assertEquals("anthropic", rows.single { it.isDefault }.providerId)
    }

    @Test fun providersSelectReturnsEmptyWhenNoneRegistered() = runTest {
        val out = ProviderQueryTool(registry()).execute(
            ProviderQueryTool.Input(select = "providers"),
            ctx(),
        ).data
        assertEquals(0, out.total)
        assertTrue(
            out.rows.toString() == "[]",
            "empty rows array expected, got ${out.rows}",
        )
    }

    @Test fun modelsSelectReturnsCatalogForKnownProvider() = runTest {
        val reg = registry(
            FakeProvider(
                "anthropic",
                models = listOf(
                    ModelInfo("opus-4", "Opus 4", 200_000, supportsTools = true, supportsThinking = true),
                    ModelInfo("haiku-4", "Haiku 4", 200_000, supportsTools = true),
                ),
            ),
        )
        val out = ProviderQueryTool(reg).execute(
            ProviderQueryTool.Input(select = "models", providerId = "anthropic"),
            ctx(),
        ).data
        assertEquals("models", out.select)
        assertEquals(2, out.total)
        assertNull(out.error)
        val rows = out.rows.decodeRowsAs(ModelRow.serializer())
        assertEquals(listOf("opus-4", "haiku-4"), rows.map { it.modelId })
        assertTrue(rows.all { it.providerId == "anthropic" })
        assertEquals(true, rows.first { it.modelId == "opus-4" }.supportsThinking)
    }

    @Test fun modelsSelectSurfacesProviderErrorViaErrorField() = runTest {
        val reg = registry(
            FakeProvider("openai", listModelsThrows = RuntimeException("HTTP 401 invalid_api_key")),
        )
        val out = ProviderQueryTool(reg).execute(
            ProviderQueryTool.Input(select = "models", providerId = "openai"),
            ctx(),
        ).data
        val err = assertNotNull(out.error)
        assertTrue(err.contains("401"), "error surface includes raw message: $err")
        assertEquals(0, out.total)
    }

    @Test fun modelsSelectFailsLoudOnUnknownProvider() = runTest {
        val reg = registry(FakeProvider("anthropic"))
        val e = assertFailsWith<IllegalStateException> {
            ProviderQueryTool(reg).execute(
                ProviderQueryTool.Input(select = "models", providerId = "nonexistent"),
                ctx(),
            )
        }
        assertTrue(
            e.message?.contains("not registered") == true,
            "expected \"not registered\" hint; got: ${e.message}",
        )
    }

    @Test fun modelsSelectRequiresProviderId() = runTest {
        val reg = registry(FakeProvider("anthropic"))
        val e = assertFailsWith<IllegalStateException> {
            ProviderQueryTool(reg).execute(
                ProviderQueryTool.Input(select = "models"),
                ctx(),
            )
        }
        assertTrue(
            e.message?.contains("requires providerId") == true,
            "expected \"requires providerId\" hint; got: ${e.message}",
        )
    }

    @Test fun providersSelectRejectsProviderIdFilter() = runTest {
        val reg = registry(FakeProvider("anthropic"))
        val e = assertFailsWith<IllegalStateException> {
            ProviderQueryTool(reg).execute(
                ProviderQueryTool.Input(select = "providers", providerId = "anthropic"),
                ctx(),
            )
        }
        assertTrue(
            e.message?.contains("providerId") == true,
            "expected providerId-rejection message; got: ${e.message}",
        )
    }

    @Test fun unknownSelectFailsLoud() = runTest {
        val reg = registry(FakeProvider("anthropic"))
        assertFailsWith<IllegalStateException> {
            ProviderQueryTool(reg).execute(
                ProviderQueryTool.Input(select = "garbage"),
                ctx(),
            )
        }
    }

    @Test fun caseInsensitiveSelect() = runTest {
        val reg = registry(FakeProvider("anthropic"))
        val out = ProviderQueryTool(reg).execute(
            ProviderQueryTool.Input(select = "PROVIDERS"),
            ctx(),
        ).data
        assertEquals("providers", out.select)
    }
}
