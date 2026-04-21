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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListProviderModelsToolTest {

    private class StubProvider(
        override val id: String,
        private val models: List<ModelInfo> = emptyList(),
        private val throwOnList: Throwable? = null,
    ) : LlmProvider {
        override suspend fun listModels(): List<ModelInfo> {
            throwOnList?.let { throw it }
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
        val builder = ProviderRegistry.Builder()
        for (p in providers) builder.add(p)
        return builder.build()
    }

    @Test fun returnsModelsWithCapabilities() = runTest {
        val provider = StubProvider(
            id = "anthropic",
            models = listOf(
                ModelInfo(
                    id = "claude-opus-4-7",
                    name = "Claude Opus 4.7",
                    contextWindow = 200_000,
                    supportsTools = true,
                    supportsThinking = true,
                    supportsImages = true,
                ),
                ModelInfo(
                    id = "claude-haiku-4-5",
                    name = "Claude Haiku 4.5",
                    contextWindow = 200_000,
                    supportsTools = true,
                ),
            ),
        )

        val out = ListProviderModelsTool(registry(provider)).execute(
            ListProviderModelsTool.Input(providerId = "anthropic"),
            ctx(),
        ).data

        assertEquals(2, out.modelCount)
        assertTrue(out.isDefault)
        assertNull(out.error)
        val opus = out.models.single { it.id == "claude-opus-4-7" }
        assertEquals(200_000, opus.contextWindow)
        assertTrue(opus.supportsTools)
        assertTrue(opus.supportsThinking)
        assertTrue(opus.supportsImages)
        val haiku = out.models.single { it.id == "claude-haiku-4-5" }
        assertTrue(!haiku.supportsThinking)
        assertTrue(!haiku.supportsImages)
    }

    @Test fun unknownProviderFailsLoud() = runTest {
        val reg = registry(StubProvider("anthropic"))
        val ex = assertFailsWith<IllegalStateException> {
            ListProviderModelsTool(reg).execute(
                ListProviderModelsTool.Input(providerId = "ghost"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("list_providers"), ex.message)
    }

    @Test fun httpFailureSurfacedAsErrorFieldNotThrown() = runTest {
        val provider = StubProvider(
            id = "openai",
            throwOnList = RuntimeException("401 Unauthorized"),
        )
        val out = ListProviderModelsTool(registry(provider)).execute(
            ListProviderModelsTool.Input(providerId = "openai"),
            ctx(),
        ).data

        assertEquals(0, out.modelCount)
        assertTrue(out.models.isEmpty())
        assertTrue(out.error!!.contains("401"), out.error)
    }

    @Test fun emptyModelListSucceedsWithoutError() = runTest {
        val out = ListProviderModelsTool(registry(StubProvider("gemini"))).execute(
            ListProviderModelsTool.Input(providerId = "gemini"),
            ctx(),
        ).data
        assertEquals(0, out.modelCount)
        assertNull(out.error)
    }

    @Test fun nonDefaultProviderReportsIsDefaultFalse() = runTest {
        val reg = registry(
            StubProvider("anthropic"),
            StubProvider("openai"),
        )
        val out = ListProviderModelsTool(reg).execute(
            ListProviderModelsTool.Input(providerId = "openai"),
            ctx(),
        ).data
        assertTrue(!out.isDefault)
    }
}
