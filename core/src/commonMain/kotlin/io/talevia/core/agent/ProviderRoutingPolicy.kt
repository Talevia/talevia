package io.talevia.core.agent

import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.pricing.LlmPricing

/**
 * Decides the order in which [Agent] walks its fallback chain. M2 exit
 * summary §3.1 follow-up #3 — the raw `fallbackProviders` list is built at
 * composition time and preserves registry order (= env probe order in the
 * composition root). Under tight budgets a user would rather hit the
 * cheapest priced provider first than the first-registered one.
 *
 * The policy only reorders **fallback** providers — the primary
 * ([Agent.provider]) is never displaced. This preserves a predictable
 * "the session's configured model goes first" contract and keeps the
 * policy's scope narrow (fallback is the arbitrage surface, not first-
 * call selection, which depends on session-specific preferences that a
 * global policy can't know).
 *
 * Policies are pure functions of the provider list; they see no session
 * state and do no IO. That keeps ordering a cacheable build-time step
 * ([Agent] invokes policies once per construction, not per turn) and
 * makes unit tests trivial.
 *
 * Default wiring keeps the pre-policy semantics: composition roots that
 * pass no policy get [RegistryOrderPolicy], so rolling this in is
 * non-breaking. The cost-aware variant is opt-in via
 * [CheapestFirstPolicy]; adding a per-session knob (e.g.
 * `Session.preferredRoutingPolicy`) is a follow-up once operator
 * feedback confirms which heuristic they actually want.
 */
sealed interface ProviderRoutingPolicy {

    /**
     * Return the fallback list reordered for this policy. The primary
     * provider is passed separately so policies know what to *exclude*
     * from reordering (the primary always goes first in the Agent's
     * turn-executor list; fallbacks are what this function arranges).
     *
     * Implementations must be deterministic — equal inputs produce equal
     * outputs — so tests and cross-run reproducibility work without
     * further coordination.
     *
     * @param primary The session's primary provider (Agent's `provider`).
     *   Policies must not return it inside the list; the Agent already
     *   prepends it.
     * @param fallbacks Raw fallback pool, typically
     *   `registry.all() - primary`. May contain providers in any order.
     * @return Fallback providers in the order Agent should walk when the
     *   primary exhausts its retry budget.
     */
    fun orderFallbacks(primary: LlmProvider, fallbacks: List<LlmProvider>): List<LlmProvider>

    companion object {
        /** The default — preserves pre-policy behavior. */
        val Default: ProviderRoutingPolicy = RegistryOrderPolicy
    }
}

/**
 * Pre-policy default — returns [fallbacks] untouched (modulo filtering
 * out the primary if it was accidentally included). Equivalent to the
 * behavior [Agent] shipped before [ProviderRoutingPolicy] landed.
 */
data object RegistryOrderPolicy : ProviderRoutingPolicy {
    override fun orderFallbacks(
        primary: LlmProvider,
        fallbacks: List<LlmProvider>,
    ): List<LlmProvider> =
        fallbacks.filter { it.id != primary.id }
}

/**
 * Cost-aware ordering — sort providers by the cheapest known model in
 * [LlmPricing.all] for their provider id. Providers with no pricing
 * entries sort to the end (stable-ordered by original list index so the
 * "unknown cost" tail is deterministic).
 *
 * **"Cheapest model" metric:** `centsPer1kInputTokens +
 * centsPer1kOutputTokens` on each priced model — a simple per-1k-token
 * sum that weighs input and output equally. The policy is not trying to
 * pick the best model per request (that needs runtime token counts and
 * per-request tradeoffs); it's an a-priori ranking "which provider's
 * cheapest model is cheaper than the other provider's cheapest model?"
 * — exactly the arbitrage question the M2 exit summary raises.
 *
 * **Tie-break:** alphabetic by provider id, so ordering is stable across
 * runs even when two providers share the same cheapest-model cost (e.g.
 * a future provider at identical list price).
 *
 * Unknown providers (no pricing rule) sort *after* priced providers —
 * they may still be cheaper or much more expensive, we just have no
 * signal. Putting them last preserves "try the knowably cheap option
 * first" without erasing them from the fallback chain.
 */
data object CheapestFirstPolicy : ProviderRoutingPolicy {
    override fun orderFallbacks(
        primary: LlmProvider,
        fallbacks: List<LlmProvider>,
    ): List<LlmProvider> {
        val filtered = fallbacks.filter { it.id != primary.id }
        val cheapestByProvider: Map<String, Double> = LlmPricing.all()
            .groupBy { it.providerId }
            .mapValues { (_, entries) ->
                entries.minOf { it.centsPer1kInputTokens + it.centsPer1kOutputTokens }
            }
        return filtered
            .mapIndexed { index, provider ->
                Triple(provider, cheapestByProvider[provider.id], index)
            }
            .sortedWith(
                compareBy(
                    // null (unknown) sorts last
                    { it.second == null },
                    // priced: ascending cost
                    { it.second ?: Double.MAX_VALUE },
                    // tie-break: provider id
                    { it.first.id },
                    // final tie-break: original index (total order; never hit in practice
                    // once ids are unique, but keeps the compareBy exhaustive)
                    { it.third },
                ),
            )
            .map { it.first }
    }
}
