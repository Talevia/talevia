package io.talevia.core.platform

/**
 * Pluggable web-search backing for the `web_search` tool — keeps the tool
 * layer SDK-agnostic the same way [ImageGenEngine] / [MusicGenEngine] /
 * [VideoGenEngine] do for their AIGC counterparts. Concrete providers
 * (Tavily, Exa, Brave, Bing, etc.) live under `core.provider.<vendor>` and
 * translate their native response shapes into [SearchResults].
 *
 * The agent uses search for the "look up a reference / find an inspiration /
 * skim the latest on X" class of requests where it doesn't already know a URL
 * to feed `web_fetch`. We deliberately surface a small, well-typed result —
 * title + URL + snippet — rather than a raw provider blob so the LLM sees
 * uniform shape regardless of which backend served the call.
 */
interface SearchEngine {
    /** Stable provider id surfaced for telemetry and audit (e.g. "tavily"). */
    val providerId: String

    /**
     * Run [query] against the backing search service. Implementations must
     * cap to [maxResults] best matches; an optional one-paragraph [SearchResults.answer]
     * may be returned when the provider synthesises one (Tavily, Perplexity-style),
     * otherwise null.
     */
    suspend fun search(query: String, maxResults: Int): SearchResults
}

data class SearchResults(
    val query: String,
    val results: List<SearchResult>,
    /** Provider-synthesised one-paragraph answer when available; null otherwise. */
    val answer: String? = null,
)

data class SearchResult(
    val title: String,
    val url: String,
    /**
     * Short text excerpt the provider considered most relevant to the query.
     * Length and HTML-cleanliness vary by provider; consumers should treat
     * this as plain text suitable for direct inclusion in an LLM prompt.
     */
    val snippet: String,
)
