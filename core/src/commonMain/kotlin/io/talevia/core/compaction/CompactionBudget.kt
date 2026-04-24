package io.talevia.core.compaction

import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.ModelRef

/**
 * Per-session knobs that drive the compaction prune loop in [Compactor].
 *
 * Two axes:
 *  - [protectUserTurns] — how many trailing user turns the pre-window
 *    protection phase pins intact. Small models (~50k context) and large
 *    models (~1M context) both benefit from 2: this is a UX decision
 *    ("don't lose the user's last ask and its reply") rather than a
 *    size-scaled budget, so the default doesn't move per-model.
 *  - [pruneKeepTokens] — the total-token ceiling the prune loop tries
 *    to land under. On a 200k-context model, 40k leaves 160k of head-
 *    room for the summary + protected window + fresh reasoning — the
 *    classic OpenCode behaviour. On a 64k-context model the same 40k
 *    leaves almost nothing; scaling to `contextWindow * DEFAULT_PRUNE_KEEP_RATIO`
 *    (0.30 by default) keeps effective headroom proportional.
 *
 * Constructed once per turn by the resolver threaded into [Compactor].
 * A single non-dynamic budget (passed as a constant lambda) is the
 * pre-cycle default — the in-tree callers that don't override still get
 * the old (`protectUserTurns=2, pruneKeepTokens=40_000`) numbers.
 */
data class CompactionBudget(
    val protectUserTurns: Int,
    val pruneKeepTokens: Int,
) {
    init {
        require(protectUserTurns >= 1) { "protectUserTurns must be ≥ 1, got $protectUserTurns" }
        require(pruneKeepTokens > 0) { "pruneKeepTokens must be > 0, got $pruneKeepTokens" }
    }

    companion object {
        /**
         * Fraction of a model's advertised `contextWindow` retained after a
         * compaction pass. Chosen to be noticeably lower than
         * [DEFAULT_COMPACTION_THRESHOLD_RATIO] (0.85) so that each
         * compaction actually relieves pressure instead of just trimming
         * to the trigger threshold and re-firing on the next turn. 0.30
         * ≈ 60k on a 200k-context Claude window — comfortable for the
         * summary + recent turns + room for the next reasoning burst.
         */
        const val DEFAULT_PRUNE_KEEP_RATIO: Double = 0.30

        /**
         * Pre-cycle behaviour (matches the hardcoded numbers from the
         * 40k / 2-turn era). Kept public so tests + in-memory container
         * setups that don't wire a registry still see stable numbers.
         */
        val DEFAULT: CompactionBudget = CompactionBudget(
            protectUserTurns = 2,
            pruneKeepTokens = 40_000,
        )
    }
}

/**
 * Helper for callers that don't need per-model scaling — returns a
 * resolver that always yields a `CompactionBudget` built from the two
 * knobs. Used as [Compactor]'s default so pre-cycle constructors
 * (`Compactor(provider, store, bus)` without explicit budget)
 * reproduce the legacy `CompactionBudget.DEFAULT` behaviour.
 */
internal fun constantBudgetResolver(
    protectUserTurns: Int,
    pruneKeepTokens: Int,
): (ModelRef) -> CompactionBudget {
    val constant = CompactionBudget(
        protectUserTurns = protectUserTurns,
        pruneKeepTokens = pruneKeepTokens,
    )
    return { _ -> constant }
}

/**
 * Per-model resolver mirroring [PerModelCompactionThreshold]. Built once
 * at composition-root time from the registered providers'
 * [io.talevia.core.provider.ModelInfo] tables and queried per turn via
 * the `(ModelRef) -> CompactionBudget` contract.
 *
 * Why a second resolver instead of folding the budget into
 * [PerModelCompactionThreshold]: the two knobs answer different questions
 * — the threshold decides **when** to compact (trigger at ~85 %), the
 * budget decides **how aggressively** to compact (keep ~30 % after the
 * pass). Coupling the two locks the ratios together forever; keeping
 * them separate means a future tune can adjust one without re-deriving
 * the other's unchanged math.
 *
 * Unknown models fall through to [CompactionBudget.DEFAULT] — the legacy
 * (40k / 2-turn) behaviour, so nothing regresses when a model is
 * unrecognised. Same fall-through discipline as
 * [PerModelCompactionThreshold.fallback].
 */
class PerModelCompactionBudget(
    private val contextWindowByRef: Map<Pair<String, String>, Int>,
    private val keepRatio: Double = CompactionBudget.DEFAULT_PRUNE_KEEP_RATIO,
    private val protectUserTurns: Int = CompactionBudget.DEFAULT.protectUserTurns,
    private val fallback: CompactionBudget = CompactionBudget.DEFAULT,
) : (ModelRef) -> CompactionBudget {

    override operator fun invoke(ref: ModelRef): CompactionBudget {
        val window = contextWindowByRef[ref.providerId to ref.modelId] ?: return fallback
        // Guard against pathologically small or mis-reported context
        // windows — fall back rather than synthesise a zero budget that
        // would fail [CompactionBudget.init].
        val scaled = (window * keepRatio).toInt()
        if (scaled <= 0) return fallback
        return CompactionBudget(protectUserTurns = protectUserTurns, pruneKeepTokens = scaled)
    }

    companion object {
        /**
         * Prefetches `listModels()` for every provider in [registry] and
         * builds a non-suspend resolver. Same assumption as
         * [PerModelCompactionThreshold.fromRegistry]: the three in-tree
         * providers return hardcoded `listOf(...)`, so this completes
         * synchronously at wire-up.
         */
        suspend fun fromRegistry(
            registry: ProviderRegistry,
            keepRatio: Double = CompactionBudget.DEFAULT_PRUNE_KEEP_RATIO,
            protectUserTurns: Int = CompactionBudget.DEFAULT.protectUserTurns,
            fallback: CompactionBudget = CompactionBudget.DEFAULT,
        ): PerModelCompactionBudget {
            val byRef = mutableMapOf<Pair<String, String>, Int>()
            for (provider in registry.all()) {
                for (info in provider.listModels()) {
                    byRef[provider.id to info.id] = info.contextWindow
                }
            }
            return PerModelCompactionBudget(byRef, keepRatio, protectUserTurns, fallback)
        }
    }
}
