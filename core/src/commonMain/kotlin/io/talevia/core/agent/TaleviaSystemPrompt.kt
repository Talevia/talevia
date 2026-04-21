package io.talevia.core.agent

import io.talevia.core.agent.prompt.PROMPT_AIGC_LANE
import io.talevia.core.agent.prompt.PROMPT_BUILD_SYSTEM
import io.talevia.core.agent.prompt.PROMPT_EDITING_AND_EXTERNAL
import io.talevia.core.agent.prompt.PROMPT_PROJECT

/**
 * The canonical Talevia system prompt.
 *
 * Teaches the model the small set of facts it cannot derive from the tool schemas
 * alone — in particular, the **build-system mental model** (Source → Compiler →
 * Artifact), the **consistency-binding protocol**, the **seed / lockfile cache
 * discipline**, and the **small-white / professional dual user** distinction from
 * VISION §4.
 *
 * Kept terse on purpose. Every byte is in every turn, and over-explanation dilutes
 * the signals we most want the model to follow. If a rule here is ever violated in
 * a trace, the fix is usually tightening the rule wording, not writing a longer one.
 *
 * **Composition.** The body is sharded across topical section files under
 * [io.talevia.core.agent.prompt] — the monolithic 743-line file this used to be
 * tripped §R.5.3 long-file debt. Sections are joined with a blank-line separator
 * (`\n\n`) so the on-wire prompt is byte-identical to the pre-refactor string —
 * [TaleviaSystemPromptTest] enforces that invariant.
 *
 * Order matters for LLM priming: mental model first, consistency + mechanics,
 * then dual-user mindset + AIGC lanes, then project lifecycle, then the
 * timeline-editing toolset and external tools. Changing the order risks
 * diluting the mental-model signal the earlier sections establish.
 */
internal val TALEVIA_SYSTEM_PROMPT_BASE: String = listOf(
    PROMPT_BUILD_SYSTEM,
    PROMPT_AIGC_LANE,
    PROMPT_PROJECT,
    PROMPT_EDITING_AND_EXTERNAL,
).joinToString(separator = "\n\n")

/**
 * Build the system prompt. App-specific prefixes / suffixes (e.g. "this is the
 * headless server — permissions default to deny") compose on top via [extraSuffix].
 *
 * The returned string is passed verbatim to the LLM through `Agent(systemPrompt = ...)`.
 */
fun taleviaSystemPrompt(extraSuffix: String? = null): String {
    val tail = extraSuffix?.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
    return TALEVIA_SYSTEM_PROMPT_BASE + tail
}
