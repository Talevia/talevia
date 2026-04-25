package io.talevia.core.agent

/**
 * Map an environment-variable string into a concrete
 * [ProviderRoutingPolicy]. Containers (CLI / Desktop / Server)
 * call this with the value of `TALEVIA_PROVIDER_ROUTING` so the
 * choice between registry-order fallback and cost-aware fallback
 * is operator-controlled at process boot time, without redeploying.
 *
 * Accepted values (case-insensitive, leading/trailing space-trimmed):
 * - `null` / empty → [RegistryOrderPolicy] (preserves pre-policy
 *   behavior on a fresh deploy with no env-var set).
 * - `"default"` / `"registry-order"` / `"registry"` →
 *   [RegistryOrderPolicy].
 * - `"cheapest-first"` / `"cheapest"` / `"cost-aware"` →
 *   [CheapestFirstPolicy].
 *
 * Unknown values fail loud rather than silently degrading — the
 * routing policy is a per-deployment decision and "the operator
 * misspelled the flag" should surface immediately, not at the first
 * production fallback. Misconfiguration at boot is a far cheaper
 * failure than misconfiguration noticed three hours into a billing
 * incident.
 *
 * Lives in `core.agent` (not in a container) so all five
 * `AppContainer`s share one interpretation of the env-var grammar.
 * The env read itself stays platform-side — Android / iOS don't
 * have `System.getenv`, so they keep [RegistryOrderPolicy] until a
 * platform-appropriate config surface is introduced.
 */
fun resolveProviderRoutingPolicy(envValue: String?): ProviderRoutingPolicy =
    when (envValue?.trim()?.lowercase()) {
        null, "" -> RegistryOrderPolicy
        "default", "registry-order", "registry" -> RegistryOrderPolicy
        "cheapest-first", "cheapest", "cost-aware" -> CheapestFirstPolicy
        else -> error(
            "Unknown TALEVIA_PROVIDER_ROUTING value '$envValue' " +
                "(accepted: registry-order, cheapest-first; case-insensitive).",
        )
    }
