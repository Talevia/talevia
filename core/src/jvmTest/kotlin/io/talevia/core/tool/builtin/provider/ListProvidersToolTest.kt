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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListProvidersToolTest {

    private class FakeProvider(override val id: String) : LlmProvider {
        override suspend fun listModels(): List<ModelInfo> = emptyList()
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

    private fun registry(vararg ids: String): ProviderRegistry {
        val builder = ProviderRegistry.Builder()
        for (id in ids) builder.add(FakeProvider(id))
        return builder.build()
    }

    @Test fun listsAllRegisteredProviders() = runTest {
        val reg = registry("anthropic", "openai", "gemini")

        val out = ListProvidersTool(reg).execute(ListProvidersTool.Input(), ctx()).data

        assertEquals(3, out.total)
        assertEquals("anthropic", out.defaultProviderId)
        val ids = out.providers.map { it.providerId }.toSet()
        assertEquals(setOf("anthropic", "openai", "gemini"), ids)
    }

    @Test fun firstRegisteredIsDefault() = runTest {
        val reg = registry("openai", "anthropic")
        val out = ListProvidersTool(reg).execute(ListProvidersTool.Input(), ctx()).data

        assertEquals("openai", out.defaultProviderId)
        val def = out.providers.single { it.isDefault }
        assertEquals("openai", def.providerId)
    }

    @Test fun emptyRegistryReturnsZero() = runTest {
        val reg = registry()
        val out = ListProvidersTool(reg).execute(ListProvidersTool.Input(), ctx()).data

        assertEquals(0, out.total)
        assertNull(out.defaultProviderId)
        assertTrue(out.providers.isEmpty())
    }

    @Test fun singleProviderReportsAsDefault() = runTest {
        val reg = registry("anthropic")
        val out = ListProvidersTool(reg).execute(ListProvidersTool.Input(), ctx()).data

        assertEquals(1, out.total)
        assertTrue(out.providers.single().isDefault)
    }

    @Test fun duplicateAddsAreDedupedByRegistry() = runTest {
        val builder = ProviderRegistry.Builder()
        builder.add(FakeProvider("anthropic"))
        builder.add(FakeProvider("anthropic")) // no-op per Builder.add contract
        val reg = builder.build()

        val out = ListProvidersTool(reg).execute(ListProvidersTool.Input(), ctx()).data
        assertEquals(1, out.total)
    }
}
