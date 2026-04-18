package io.talevia.core.session

import io.talevia.core.MessageId
import io.talevia.core.SessionId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Message {
    abstract val id: MessageId
    abstract val sessionId: SessionId
    abstract val createdAt: Instant

    @Serializable @SerialName("user")
    data class User(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        val agent: String,
        val model: ModelRef,
    ) : Message()

    @Serializable @SerialName("assistant")
    data class Assistant(
        override val id: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        val parentId: MessageId,
        val model: ModelRef,
        val cost: Cost = Cost.ZERO,
        val tokens: TokenUsage = TokenUsage.ZERO,
        val finish: FinishReason? = null,
        val error: String? = null,
    ) : Message()
}

@Serializable
data class ModelRef(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
)

@Serializable
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    companion object { val ZERO = TokenUsage() }
}

@Serializable
data class Cost(val usd: Double = 0.0) {
    companion object { val ZERO = Cost() }
}

@Serializable
enum class FinishReason {
    @SerialName("stop") STOP,
    @SerialName("end-turn") END_TURN,
    @SerialName("max-tokens") MAX_TOKENS,
    @SerialName("content-filter") CONTENT_FILTER,
    @SerialName("tool-calls") TOOL_CALLS,
    @SerialName("error") ERROR,
    @SerialName("cancelled") CANCELLED,
}

data class MessageWithParts(
    val message: Message,
    val parts: List<Part>,
)
