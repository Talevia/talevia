package io.talevia.core.compaction

import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Two-phase context compaction modelled on OpenCode's `compaction.ts`:
 *
 *  1. **Prune** — walk message parts from newest to oldest. Protect the last
 *     [protectUserTurns] user turns intact and any [Part.TimelineSnapshot] /
 *     [Part.Compaction] parts. Once the protected envelope exceeds [pruneProtectTokens],
 *     mark older completed-tool outputs as compacted (their `time_compacted` column is
 *     stamped, so callers can skip them on next read).
 *
 *  2. **Summarize** — call [provider] with the surviving history plus a fixed
 *     summary prompt; capture the text response into a new [Part.Compaction]
 *     attached to the latest assistant message.
 *
 * Triggering is the caller's responsibility — typically the Agent loop checks
 * [TokenEstimator.forHistory] against the model's context window and invokes
 * [process] when the ratio crosses [contextOverflowRatio] (default 0.85).
 */
@OptIn(ExperimentalUuidApi::class)
class Compactor(
    private val provider: LlmProvider,
    private val store: SessionStore,
    private val bus: EventBus,
    private val clock: Clock = Clock.System,
    private val protectUserTurns: Int = 2,
    private val pruneProtectTokens: Int = 40_000,
) {

    /**
     * @return [Result.Compacted] with the new compaction summary, or [Result.Skipped]
     * when there's nothing to drop.
     */
    suspend fun process(
        sessionId: SessionId,
        history: List<MessageWithParts>,
        model: ModelRef,
    ): Result {
        val prunedIds = prune(history)
        val now = clock.now()
        prunedIds.forEach { store.markPartCompacted(it, now) }

        val survivors = history.map { mwp ->
            mwp.copy(parts = mwp.parts.filter { it.id !in prunedIds })
        }
        if (prunedIds.isEmpty() && survivors.tokens() < pruneProtectTokens) {
            return Result.Skipped("nothing to compact")
        }

        val summary = summarise(provider, survivors, model)
            ?: return Result.Skipped("provider returned no summary")

        val targetMessageId = survivors.lastOrNull { it.message is Message.Assistant }?.message?.id
            ?: survivors.last().message.id
        val firstId = history.first().message.id
        val lastId = history.last().message.id
        val compactionPart = Part.Compaction(
            id = PartId(Uuid.random().toString()),
            messageId = targetMessageId,
            sessionId = sessionId,
            createdAt = clock.now(),
            replacedFromMessageId = firstId,
            replacedToMessageId = lastId,
            summary = summary,
        )
        store.upsertPart(compactionPart)

        return Result.Compacted(prunedIds.size, summary, compactionPart.id)
    }

    /**
     * Compute which parts should be marked compacted.
     *
     * Two-tier algorithm, modelled on OpenCode's `compaction.ts`:
     *  1. **Protect window** — the last [protectUserTurns] user turns (and the
     *     assistant turns after the earliest protected user turn) are kept verbatim.
     *  2. **Budget envelope** outside the window — gather every completed-tool
     *     output in the pre-window zone as a drop candidate. Everything else
     *     (text, reasoning, running/pending/failed tools, timeline snapshots)
     *     is counted into the fixed envelope that must stay. If fixed + all
     *     candidates ≤ [pruneProtectTokens], drop nothing. Otherwise sort
     *     candidates by size descending and drop the biggest first until the
     *     kept total is under budget — one large drop is preferable to several
     *     small ones because it preserves more reasoning history for the same
     *     token budget ([ToolResult.estimatedTokens] lets big tools
     *     self-identify).
     */
    internal fun prune(history: List<MessageWithParts>): Set<PartId> {
        val userTurnIndices = history.mapIndexedNotNull { i, m ->
            if (m.message is Message.User) i else null
        }
        if (userTurnIndices.size <= protectUserTurns) return emptySet()
        val protectFromIndex = userTurnIndices[userTurnIndices.size - protectUserTurns]

        // Protected-window token cost — always kept.
        var fixedTokens = 0
        for (i in protectFromIndex until history.size) {
            for (part in history[i].parts) fixedTokens += TokenEstimator.forPart(part)
        }

        // Pre-window: split parts into drop-candidates (completed tool outputs)
        // and fixed (everything else). Non-candidate parts always count toward
        // the envelope.
        data class Candidate(val id: PartId, val cost: Int)
        val candidates = mutableListOf<Candidate>()
        for (i in 0 until protectFromIndex) {
            for (part in history[i].parts) {
                val cost = TokenEstimator.forPart(part)
                if (part is Part.Tool && part.state is ToolState.Completed) {
                    candidates += Candidate(part.id, cost)
                } else {
                    fixedTokens += cost
                }
            }
        }

        // Drop biggest candidates first until kept fits the budget.
        val drop = mutableSetOf<PartId>()
        var kept = candidates.sumOf { it.cost }
        for (c in candidates.sortedByDescending { it.cost }) {
            if (fixedTokens + kept <= pruneProtectTokens) break
            drop += c.id
            kept -= c.cost
        }
        return drop
    }

    private suspend fun summarise(
        provider: LlmProvider,
        history: List<MessageWithParts>,
        model: ModelRef,
    ): String? {
        val req = LlmRequest(
            model = model,
            messages = history,
            systemPrompt = SUMMARY_PROMPT,
            maxTokens = 4_096,
        )
        val text = StringBuilder()
        var lastFinish: FinishReason? = null
        provider.stream(req).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> text.append(event.text)
                is LlmEvent.TextEnd -> if (text.isEmpty()) text.append(event.finalText)
                is LlmEvent.StepFinish -> lastFinish = event.finish
                is LlmEvent.Error -> error("compaction provider error: ${event.message}")
                else -> {}
            }
        }
        return text.toString().takeIf { it.isNotBlank() && lastFinish != FinishReason.ERROR }
    }

    sealed class Result {
        data class Compacted(val prunedCount: Int, val summary: String, val partId: PartId) : Result()
        data class Skipped(val reason: String) : Result()
    }

    companion object {
        const val SUMMARY_PROMPT: String = """You are summarising an in-progress AI video editing session so we can drop older turns and continue without losing context.

Produce a structured summary using exactly these sections, each on its own line, max 200 words total:

Goal: <what the user is trying to accomplish>
Discoveries: <key facts about the media / project state we've learned>
Accomplished: <tools that have already been called and their effective output>
Current Timeline State: <what's currently on the timeline (clips, tracks, duration)>
Open Questions: <anything the user has asked or implied that we have not yet resolved>

Be terse. Do not include any prose outside the five sections."""
    }
}

private fun List<MessageWithParts>.tokens(): Int = TokenEstimator.forHistory(this)
