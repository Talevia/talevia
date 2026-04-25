package io.talevia.core.compaction

import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    protectUserTurns: Int = CompactionBudget.DEFAULT.protectUserTurns,
    pruneProtectTokens: Int = CompactionBudget.DEFAULT.pruneKeepTokens,
    /**
     * Per-model budget resolver. Defaults to a constant budget built from
     * the two Int params above, so pre-cycle callers (tests, iOS bridge)
     * see unchanged numbers. Production containers inject
     * [PerModelCompactionBudget] so a session on a 64k-window model
     * doesn't try to keep a 40k budget (which leaves ~zero room for the
     * next turn).
     */
    private val budgetResolver: (ModelRef) -> CompactionBudget = constantBudgetResolver(
        protectUserTurns = protectUserTurns,
        pruneKeepTokens = pruneProtectTokens,
    ),
) {
    /**
     * Backstop budget used by the no-arg [prune] overload — mirrors the
     * pre-cycle `(protectUserTurns, pruneProtectTokens)` fields so tests
     * that exercise `prune(history)` with explicit ctor knobs continue
     * to see the configured numbers. Production [process] uses
     * [budgetResolver] directly with the live session's model.
     */
    private val defaultBudget: CompactionBudget = CompactionBudget(
        protectUserTurns = protectUserTurns,
        pruneKeepTokens = pruneProtectTokens,
    )

    /**
     * Serialises concurrent [process] calls for the same session. The
     * prune → summary → upsertPart sequence isn't atomic at the
     * [SessionStore] layer (each write is mutex-guarded on its own but the
     * triple is not), so two racing passes could double-drop the same
     * candidate set + spawn two `Part.Compaction` entries summarising
     * overlapping ranges — wastes a provider call and leaves an ambiguous
     * "which compaction described the range?" trace for later readers.
     *
     * Single-mutex + [inflightSessions] is preferred over a per-session
     * `MutableMap<SessionId, Mutex>` because: (a) compaction is infrequent
     * (auto-trigger at ~85 % context + rare manual `/compact`), so
     * contention on one mutex for map lookups is nil, and (b) per-session
     * mutexes would accumulate forever without a GC pass, one per session
     * ever compacted.
     *
     * Second concurrent call returns [Result.Skipped] immediately rather
     * than queueing — a user-pressed `/compact` landing during an already-
     * running auto-compaction shouldn't spawn a redundant summary pass on
     * history that's about to change under it.
     */
    private val inflightMutex = Mutex()
    private val inflightSessions = mutableSetOf<SessionId>()

    /**
     * @param strategy controls whether to follow up the prune pass with an
     * LLM-generated summary. Defaults to [CompactionStrategy.SUMMARIZE_AND_PRUNE]
     * so existing callers keep historical behaviour;
     * [CompactionStrategy.PRUNE_ONLY] short-circuits before the provider
     * call and returns [Result.Pruned] (no summary, no `Part.Compaction`
     * written).
     *
     * @return [Result.Compacted] when summarisation produced a summary,
     * [Result.Pruned] when [strategy] is [CompactionStrategy.PRUNE_ONLY]
     * and at least one part was dropped, or [Result.Skipped] when there's
     * nothing to drop / another pass for the same session is already in
     * flight / the provider returned no summary.
     */
    suspend fun process(
        sessionId: SessionId,
        history: List<MessageWithParts>,
        model: ModelRef,
        strategy: CompactionStrategy = CompactionStrategy.SUMMARIZE_AND_PRUNE,
    ): Result {
        val acquired = inflightMutex.withLock {
            if (sessionId in inflightSessions) {
                false
            } else {
                inflightSessions.add(sessionId)
                true
            }
        }
        if (!acquired) {
            return Result.Skipped("compaction already in progress for session ${sessionId.value}")
        }
        try {
            val budget = budgetResolver(model)
            val prunedIds = prune(history, budget)
            val now = clock.now()
            prunedIds.forEach { store.markPartCompacted(it, now) }

            val survivors = history.map { mwp ->
                mwp.copy(parts = mwp.parts.filter { it.id !in prunedIds })
            }
            if (prunedIds.isEmpty() && survivors.tokens() < budget.pruneKeepTokens) {
                return Result.Skipped("nothing to compact")
            }

            // PRUNE_ONLY short-circuits before the provider call. Skipping
            // pure-zero-prune passes here too — there's no value in
            // publishing SessionCompacted(prunedCount=0) since the prune
            // step did nothing visible.
            if (strategy == CompactionStrategy.PRUNE_ONLY) {
                if (prunedIds.isEmpty()) {
                    return Result.Skipped("nothing to prune (prune-only strategy)")
                }
                bus.publish(
                    BusEvent.SessionCompacted(
                        sessionId = sessionId,
                        prunedCount = prunedIds.size,
                        summaryLength = 0,
                    ),
                )
                return Result.Pruned(prunedIds.size)
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

            bus.publish(
                BusEvent.SessionCompacted(
                    sessionId = sessionId,
                    prunedCount = prunedIds.size,
                    summaryLength = summary.length,
                ),
            )

            return Result.Compacted(prunedIds.size, summary, compactionPart.id)
        } finally {
            inflightMutex.withLock { inflightSessions.remove(sessionId) }
        }
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
    internal fun prune(
        history: List<MessageWithParts>,
        budget: CompactionBudget = defaultBudget,
    ): Set<PartId> {
        val userTurnIndices = history.mapIndexedNotNull { i, m ->
            if (m.message is Message.User) i else null
        }
        if (userTurnIndices.size <= budget.protectUserTurns) return emptySet()
        val protectFromIndex = userTurnIndices[userTurnIndices.size - budget.protectUserTurns]

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
            if (fixedTokens + kept <= budget.pruneKeepTokens) break
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

        /**
         * Output of [CompactionStrategy.PRUNE_ONLY] success path — at least
         * one [Part.Tool] was marked compacted, no summary was generated,
         * no [Part.Compaction] was written. Distinct from [Compacted] so
         * callers can branch on whether a summary part exists; CLI / UI
         * surfaces should say "pruned N tool outputs" instead of pretending
         * a summary is available.
         */
        data class Pruned(val prunedCount: Int) : Result()

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
