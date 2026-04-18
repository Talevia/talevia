package io.talevia.core.agent

import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Test double for [LlmProvider]. Each invocation of [stream] consumes one scripted turn
 * from [turns]; if exhausted, the provider throws — Agent loop tests should script
 * exactly the number of turns they expect.
 *
 * Captures every [LlmRequest] in [requests] so tests can assert what the Agent sent
 * (e.g. that tool_results were threaded back into the second turn).
 */
class FakeProvider(turns: List<List<LlmEvent>>) : LlmProvider {
    override val id: String = "fake"
    private val turnQueue = ArrayDeque(turns)
    val requests = mutableListOf<LlmRequest>()

    override suspend fun listModels(): List<ModelInfo> = emptyList()

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        requests += request
        val events = turnQueue.removeFirstOrNull()
            ?: error("FakeProvider exhausted (got more turns than scripted)")
        for (e in events) emit(e)
    }
}
