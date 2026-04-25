package io.talevia.core.tool.builtin.project.template

/**
 * Keyword-bag classifier that maps a free-form user intent to one of the
 * five genre template ids. Used by `project_action(action="create_from_template")` when
 * `template = "auto"` — the agent surfaces it for novice VISION §5.4
 * flows where the user doesn't know which template matches.
 *
 * Intentionally deterministic + on-device: no LLM round-trip, no
 * provider dependency. The classifier is genre-aware but genre-label
 * output only — it does NOT introduce genre-specific types (§3a #5).
 * Keyword lists are curated from the top-level node kinds each genre
 * carries in `core/domain/source/genre/<genre>/` — extend by editing
 * the bags here, not by adding new classifier stages.
 *
 * Scoring: lowercase token match against each bag, highest count wins.
 * Ties resolved in the [templateOrder] sequence (most general first).
 * Zero matches → `"narrative"` with a "no signal" reason so novice
 * intents without obvious keywords still get a reasonable starting
 * scaffold they can refine via explicit `template` on a redo.
 */
internal object IntentClassifier {

    /**
     * Deterministic tie-break + zero-match fallback order. Narrative
     * first because it covers the widest range of creative outputs and
     * its scaffold (character + style + scene + shot) is the closest to
     * a "neutral" creative skeleton any of the other genres can then
     * grow out of.
     */
    private val templateOrder = listOf("narrative", "vlog", "ad", "musicmv", "tutorial")

    /**
     * Lowercased substring patterns — matched against the intent text
     * directly (not word-boundary tokenised) so "music video" and
     * "how-to" survive hyphenation / punctuation quirks. Keep short +
     * disjoint between bags: a keyword that matches multiple genres
     * dilutes the signal. Reviewed 2026-04-21; revise when adding a
     * genre template in `core/tool/builtin/project/template/`.
     */
    private val keywordBags: Map<String, List<String>> = mapOf(
        "narrative" to listOf(
            "story", "short film", "screenplay", "scene", "plot", "character-driven",
            "drama", "narrative", "fiction", "cinematic",
        ),
        "vlog" to listOf(
            "vlog", "daily life", "day in the life", "diary", "travel", "lifestyle",
            "personal", "on my", "my trip", "my day",
        ),
        "ad" to listOf(
            "commercial", "advert", "product", "brand", "campaign", "promo",
            "marketing", "launch video", "pitch",
        ),
        "musicmv" to listOf(
            "music video", "music-video", " mv", "song", "lyric", "band", "artist",
            "track", "cover of", "beat drop",
        ),
        "tutorial" to listOf(
            "tutorial", "how to", "how-to", "guide", "walkthrough", "step by step",
            "step-by-step", "teach", "learn", "explain",
        ),
    )

    data class Classification(val template: String, val reason: String, val score: Int)

    fun classify(intent: String): Classification {
        val lower = intent.lowercase()
        val scores: Map<String, Int> = keywordBags.mapValues { (_, bag) ->
            bag.count { kw -> kw in lower }
        }
        val max = scores.values.max()
        if (max == 0) {
            return Classification(
                template = "narrative",
                reason = "no genre keywords in intent; defaulting to narrative (override by setting template explicitly)",
                score = 0,
            )
        }
        val winner = templateOrder.first { scores[it] == max }
        val matched = keywordBags.getValue(winner).filter { it in lower }
        return Classification(
            template = winner,
            reason = "matched ${matched.size} '$winner' keyword(s): ${matched.joinToString(", ")}",
            score = max,
        )
    }
}
