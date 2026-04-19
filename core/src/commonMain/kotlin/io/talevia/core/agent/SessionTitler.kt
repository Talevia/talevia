package io.talevia.core.agent

import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Clock

/**
 * Generates a short human-readable title for a brand-new session by asking a
 * cheap model to summarise the user's first message. Kept separate from the
 * Agent loop so it can be fired in the background without blocking the main
 * turn.
 *
 * Title update rule: only overwrite titles that look like placeholders
 * ("Untitled", "New session", blank). If the caller has already set a
 * deliberate title, this is a no-op.
 */
class SessionTitler(
    private val provider: LlmProvider,
    private val store: SessionStore,
    /** Model id to use for generation. Should be a cheap, fast variant. */
    private val modelId: String = defaultModelFor(provider.id),
    private val clock: Clock = Clock.System,
) {
    suspend fun generate(sessionId: SessionId, userText: String) {
        val session = store.getSession(sessionId) ?: return
        if (!isPlaceholderTitle(session.title)) return
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return

        val request = LlmRequest(
            model = ModelRef(provider.id, modelId),
            messages = listOf(fakeUserMessage(sessionId, trimmed)),
            systemPrompt = SYSTEM_PROMPT,
            maxTokens = 32,
            temperature = 0.2,
        )

        val collected = StringBuilder()
        runCatching {
            provider.stream(request).collect { evt ->
                when (evt) {
                    is LlmEvent.TextDelta -> collected.append(evt.text)
                    is LlmEvent.TextEnd -> { collected.clear(); collected.append(evt.finalText) }
                    is LlmEvent.Error -> return@collect
                    else -> {}
                }
            }
        }

        val title = cleanTitle(collected.toString())
        if (title.isEmpty()) return

        val latest = store.getSession(sessionId) ?: return
        if (!isPlaceholderTitle(latest.title)) return
        store.updateSession(latest.copy(title = title, updatedAt = clock.now()))
    }

    private fun fakeUserMessage(sessionId: SessionId, text: String): MessageWithParts {
        val now = clock.now()
        val msg = Message.User(
            id = MessageId("titler-msg"),
            sessionId = sessionId,
            createdAt = now,
            agent = "titler",
            model = ModelRef(provider.id, modelId),
        )
        val part = Part.Text(
            id = PartId("titler-part"),
            messageId = msg.id,
            sessionId = sessionId,
            createdAt = now,
            text = text,
        )
        return MessageWithParts(msg, listOf(part))
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "Write a 2-6 word title summarising the user's request. " +
                "Output only the title, no quotes, no trailing punctuation."

        /** Titles that [generate] will overwrite. */
        fun isPlaceholderTitle(title: String): Boolean {
            val t = title.trim().lowercase()
            return t.isEmpty() || t == "untitled" || t == "new session"
        }

        fun cleanTitle(raw: String): String {
            // Models like to wrap output in quotes or add trailing punctuation.
            // A single trim pass isn't enough because the two cleanups interleave
            // (e.g. "Short recap"! needs the ! stripped, then the "), so strip
            // both classes in one predicate.
            val stripped = raw.trim().trim {
                it == '"' || it == '\'' || it == '`' || it == '.' ||
                    it == '!' || it == '?' || it == ',' || it == ';' || it == ':'
            }
            return stripped.take(60)
        }

        fun defaultModelFor(providerId: String): String = when (providerId) {
            "anthropic" -> "claude-haiku-4-5-20251001"
            "openai" -> "gpt-4o-mini"
            else -> "default"
        }
    }
}
