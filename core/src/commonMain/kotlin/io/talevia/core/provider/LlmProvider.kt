package io.talevia.core.provider

import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.tool.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * SDK-agnostic interface every LLM backend implements. Provider implementations
 * translate `LlmRequest` into provider-native HTTP+SSE calls and surface results
 * as a normalised [LlmEvent] flow.
 *
 * Inspired by OpenCode's provider abstraction (`packages/opencode/src/provider/provider.ts`)
 * but without the Vercel AI SDK indirection — KMP has no equivalent, so we own the
 * mapping in each provider.
 */
interface LlmProvider {
    val id: String

    suspend fun listModels(): List<ModelInfo>

    fun stream(request: LlmRequest): Flow<LlmEvent>
}

data class ModelInfo(
    val id: String,
    val name: String,
    val contextWindow: Int,
    val supportsTools: Boolean,
    val supportsThinking: Boolean = false,
    val supportsImages: Boolean = false,
)

data class LlmRequest(
    val model: ModelRef,
    val messages: List<MessageWithParts>,
    val tools: List<ToolSpec> = emptyList(),
    val systemPrompt: String? = null,
    val maxTokens: Int = 4096,
    val temperature: Double? = null,
    val options: ProviderOptions = ProviderOptions(),
)

/** Provider-specific knobs. Each provider reads only the keys it understands. */
data class ProviderOptions(
    val anthropicThinkingBudget: Int? = null,
    val openaiReasoningEffort: String? = null,
    val extra: JsonObject? = null,
)
