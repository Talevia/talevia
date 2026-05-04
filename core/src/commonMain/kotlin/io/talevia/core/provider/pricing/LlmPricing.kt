package io.talevia.core.provider.pricing

/**
 * Static, best-effort pricing snapshot for LLM inference — the provider-lane
 * analogue of [io.talevia.core.cost.AigcPricing]. Surfaced to the agent via
 * `provider_query(select=cost_compare)` so it can answer
 * "which model is cheapest for this 10k-token request?" without calling a
 * provider (list prices are stable enough that a local table is fine for
 * the planning-time "cheaper or pricier?" question, and a pricing tool that
 * needed HTTP would pay its own per-turn round-trip).
 *
 * **Deliberately imprecise.** Provider list prices drift (Anthropic
 * repriced Sonnet twice in 2025, OpenAI dropped gpt-4o mid-year). This
 * table is a 2026-04 snapshot; callers that need invoice-accurate numbers
 * should cross-check with the provider console. Same three-state contract
 * as [io.talevia.core.cost.AigcPricing]: unknown (providerId, modelId) →
 * return empty list for that entry (caller treats as "don't roll up"),
 * distinct from 0¢ (explicitly free, e.g. cached / quota-absorbed calls —
 * which LLMs don't have today but the contract is consistent).
 *
 * **No global pricing config.** Rates live as private constants in this
 * file so a reprice PR is a one-file diff with a clear blast radius.
 *
 * Units: **cents per 1_000 tokens**, stored as [Double] for sub-cent
 * precision — e.g. Haiku at $1/MTok input rounds to 0.1 ¢ / 1k tokens.
 * `estimateCostCents(inputTokens, outputTokens)` rolls up to integer
 * cents (rounded half-up, at-least-one-cent-if-nonzero-work) matching
 * the existing `LockfileEntry.costCents` convention.
 */
object LlmPricing {

    /** One row per (providerId, modelId) published list-price pair. */
    data class Entry(
        val providerId: String,
        val modelId: String,
        val centsPer1kInputTokens: Double,
        val centsPer1kOutputTokens: Double,
    ) {
        /**
         * Integer-cent estimate for a request of
         * [inputTokens] prompt tokens + [outputTokens] generated tokens.
         * Half-up rounding; a non-zero request returns at least 1¢ so
         * "submit a 10-token test prompt" doesn't read as free.
         */
        fun estimateCostCents(inputTokens: Int, outputTokens: Int): Long {
            require(inputTokens >= 0) { "inputTokens must be >= 0 (was $inputTokens)" }
            require(outputTokens >= 0) { "outputTokens must be >= 0 (was $outputTokens)" }
            val fractional = (inputTokens / 1000.0) * centsPer1kInputTokens +
                (outputTokens / 1000.0) * centsPer1kOutputTokens
            if (fractional == 0.0) return 0L
            // Non-zero work at non-zero rates → floor at 1¢ so sub-cent work
            // doesn't silently read as free. Half-up rounding otherwise so
            // larger requests track their real list-price cents accurately.
            val rounded = (fractional + 0.5).toLong()
            return rounded.coerceAtLeast(1L)
        }
    }

    /** Snapshot of entries. Order is stable so cost-sort ties are
     *  resolved by (providerId, modelId) alphabetic order. */
    private val ENTRIES: List<Entry> = listOf(
        // Anthropic Claude 4.x family (2026-04 snapshot).
        Entry(PROVIDER_ANTHROPIC, "claude-opus-4-7", 1.5, 7.5),
        Entry(PROVIDER_ANTHROPIC, "claude-sonnet-4-6", 0.3, 1.5),
        Entry(PROVIDER_ANTHROPIC, "claude-haiku-4-5", 0.1, 0.5),
        // OpenAI (2026-04 snapshot).
        Entry(PROVIDER_OPENAI, "gpt-5.4", 0.25, 1.0),
        Entry(PROVIDER_OPENAI, "gpt-5.4-mini", 0.015, 0.06),
        Entry(PROVIDER_OPENAI, "gpt-4o", 0.25, 1.0),
        Entry(PROVIDER_OPENAI, "gpt-4o-mini", 0.015, 0.06),
        // Google Gemini (2026-04 snapshot).
        Entry(PROVIDER_GEMINI, "gemini-2.5-pro", 0.125, 0.5),
        Entry(PROVIDER_GEMINI, "gemini-2.5-flash", 0.0075, 0.03),
    )

    /** All priced (provider, model) pairs, in published-order. */
    fun all(): List<Entry> = ENTRIES

    /**
     * Lookup a single pair. Null when either id is absent from the table —
     * callers (query tool, future pre-flight cost estimator) use null as
     * the "no pricing rule" signal, never guess a number.
     */
    fun find(providerId: String, modelId: String): Entry? =
        ENTRIES.firstOrNull { it.providerId == providerId && it.modelId == modelId }

    internal const val PROVIDER_ANTHROPIC = "anthropic"
    internal const val PROVIDER_OPENAI = "openai"
    internal const val PROVIDER_GEMINI = "gemini"
}
