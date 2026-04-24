package io.talevia.core.compaction

import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.ModelRef

/**
 * Safe-side default auto-compaction trigger for models whose context
 * window is not known to the [ProviderRegistry]. 120k is ~60% of a
 * 200k-context Claude window — conservative compared to the 85%
 * context-window heuristic most providers tolerate, but a sensible
 * floor when we don't know the window at all.
 *
 * Historically this was the hard-coded `Agent.compactionTokenThreshold`
 * default before cycle-56's per-model threading.
 */
const val DEFAULT_COMPACTION_TOKEN_THRESHOLD: Int = 120_000

/**
 * Fraction of a model's advertised `contextWindow` at which auto-
 * compaction triggers. OpenCode's `session/compaction.ts` uses 0.85
 * (~170k on a 200k model, ~108k on a 128k model). Staying aligned keeps
 * the two implementations converging on similar compaction cadence for
 * the same model.
 */
const val DEFAULT_COMPACTION_THRESHOLD_RATIO: Double = 0.85

/**
 * Per-model resolver for [io.talevia.core.agent.Agent]'s auto-compaction
 * trigger. Built once at composition-root time from the registered
 * providers' [io.talevia.core.provider.ModelInfo] tables; queried per
 * turn via the `(ref) -> Int` function contract.
 *
 * Rationale: a single fixed threshold penalises models at the two ends
 * of the context-window spectrum — 120k fires ~40% too early on a 200k
 * Haiku session, and fires ~too late on a 64k model. Scaling to
 * `contextWindow × 0.85` keeps effective headroom constant across
 * models.
 *
 * Unknown models (new model id the provider hasn't advertised yet, or
 * a provider without its models pre-cached) fall through to
 * [DEFAULT_COMPACTION_TOKEN_THRESHOLD] — the legacy behavior, so nothing
 * regresses when a model is unrecognised.
 */
class PerModelCompactionThreshold(
    private val contextWindowByRef: Map<Pair<String, String>, Int>,
    private val ratio: Double = DEFAULT_COMPACTION_THRESHOLD_RATIO,
    private val fallback: Int = DEFAULT_COMPACTION_TOKEN_THRESHOLD,
) : (ModelRef) -> Int {

    override operator fun invoke(ref: ModelRef): Int =
        contextWindowByRef[ref.providerId to ref.modelId]
            ?.let { (it * ratio).toInt() }
            ?: fallback

    companion object {
        /**
         * Prefetches `listModels()` for every provider in [registry] and
         * builds a non-suspend resolver. `listModels()` is suspend — the
         * three in-tree providers (Anthropic / OpenAI / Gemini) return
         * hardcoded `listOf(...)` so this completes synchronously at
         * wire-up; a future network-sourced provider would pay one
         * round-trip per provider at container init, then amortise.
         *
         * Call from the composition root (typically via `runBlocking`
         * because `newAgent()` factories are non-suspend).
         */
        suspend fun fromRegistry(
            registry: ProviderRegistry,
            ratio: Double = DEFAULT_COMPACTION_THRESHOLD_RATIO,
            fallback: Int = DEFAULT_COMPACTION_TOKEN_THRESHOLD,
        ): PerModelCompactionThreshold {
            val byRef = mutableMapOf<Pair<String, String>, Int>()
            for (provider in registry.all()) {
                for (info in provider.listModels()) {
                    byRef[provider.id to info.id] = info.contextWindow
                }
            }
            return PerModelCompactionThreshold(byRef, ratio, fallback)
        }
    }
}
