package io.talevia.core.compaction

/**
 * Two-mode toggle for how [Compactor.process] reduces a session's context:
 *
 * - [SUMMARIZE_AND_PRUNE] (default, current behaviour) — first prune oldest
 *   completed-tool outputs to fit the budget, then call the session's
 *   provider with the survivors and write the LLM-generated summary
 *   into a new `Part.Compaction` attached to the latest assistant
 *   message. Costs one extra LLM round-trip per compaction pass.
 *
 * - [PRUNE_ONLY] — prune oldest completed-tool outputs and stop. No
 *   provider call, no `Part.Compaction` written, no summary cost. The
 *   in-flight history simply loses its biggest old tool outputs.
 *
 * Why expose a knob: tool-heavy sessions (long autonomous runs that
 * mostly emit tool calls + outputs, with very little prose between
 * them) get marginal value from a prose summary — the surviving
 * recent turns already say what's happening. The summary call costs
 * tokens + wall time + a provider round-trip that the user is already
 * waiting on. Letting the agent pick `prune_only` for those sessions
 * cuts compaction cost from "another LLM turn" to "a couple of SQL
 * stamps" without giving up the budget recovery.
 *
 * Default stays [SUMMARIZE_AND_PRUNE] so behaviour is unchanged for
 * callers that don't pass the parameter — the auto-compaction path in
 * `CompactionGate` still summarises by default. The new mode is
 * opt-in via `compact_session(strategy="prune_only")` for now; a
 * per-session metadata switch can land later if real data shows
 * tool-heavy auto-compaction is the common case.
 */
enum class CompactionStrategy {
    SUMMARIZE_AND_PRUNE,
    PRUNE_ONLY,
    ;

    companion object {
        /**
         * Parse the agent-facing string form (case-insensitive,
         * underscore- or hyphen-separated). Null / blank / unknown values
         * default to [SUMMARIZE_AND_PRUNE] so a typo never silently skips
         * the summary — it just behaves like the unparametrised path.
         */
        fun parseOrDefault(raw: String?): CompactionStrategy {
            val key = raw?.trim()?.lowercase()?.replace('-', '_') ?: return SUMMARIZE_AND_PRUNE
            return when (key) {
                "", "summarize_and_prune", "summarise_and_prune", "default" -> SUMMARIZE_AND_PRUNE
                "prune_only", "prune", "no_summary" -> PRUNE_ONLY
                else -> SUMMARIZE_AND_PRUNE
            }
        }
    }
}
