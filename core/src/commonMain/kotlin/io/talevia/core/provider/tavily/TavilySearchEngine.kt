package io.talevia.core.provider.tavily

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.talevia.core.JsonConfig
import io.talevia.core.platform.SearchEngine
import io.talevia.core.platform.SearchResult
import io.talevia.core.platform.SearchResults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tavily-backed [SearchEngine] — first concrete provider for the
 * `web_search` tool. Tavily exposes a single `POST /search` endpoint that
 * takes the query plus a few flags and returns a small, LLM-friendly JSON
 * shape (title / url / content / score per hit, plus an optional
 * synthesised one-paragraph answer). The free tier is generous enough for
 * an interactive agent.
 *
 * Per CLAUDE.md §5, Tavily-native types must NOT leak past [search] —
 * the request payload + response parsing live entirely inside this file
 * and we emit only [SearchResults] / [SearchResult].
 *
 * Gating. Containers wire a real instance only when `TAVILY_API_KEY` is
 * set in the environment; otherwise the `SearchEngine` slot stays null
 * and `web_search` stays unregistered, matching the pattern established by
 * the OpenAI / Replicate engines.
 */
class TavilySearchEngine(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.tavily.com",
    /**
     * `basic` is fast + cheap and sufficient for "give me 5 references" —
     * `advanced` doubles latency for marginally richer snippets. Keep
     * `basic` as the default; callers that need richer answers can
     * instantiate a second engine.
     */
    private val searchDepth: String = "basic",
    /** When true, Tavily returns a one-paragraph synthesised answer. */
    private val includeAnswer: Boolean = true,
    private val json: Json = JsonConfig.default,
) : SearchEngine {

    override val providerId: String = "tavily"

    override suspend fun search(query: String, maxResults: Int): SearchResults {
        require(query.isNotBlank()) { "query must not be blank" }
        require(maxResults in 1..20) { "maxResults must be in 1..20 (got $maxResults)" }

        val body = buildJsonObject {
            put("api_key", JsonPrimitive(apiKey))
            put("query", JsonPrimitive(query))
            put("search_depth", JsonPrimitive(searchDepth))
            put("include_answer", JsonPrimitive(includeAnswer))
            put("max_results", JsonPrimitive(maxResults))
        }
        val response: HttpResponse = httpClient.post("$baseUrl/search") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        if (response.status != HttpStatusCode.OK) {
            val errBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            error("Tavily search failed: ${response.status} $errBody")
        }
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject

        val answer = payload["answer"]?.jsonPrimitive?.contentOrNullSafe()
        val results = (payload["results"]?.jsonArray ?: emptyList())
            .mapNotNull { element ->
                val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                val url = obj["url"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                val snippet = obj["content"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                SearchResult(title = title, url = url, snippet = snippet)
            }

        return SearchResults(query = query, results = results, answer = answer)
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this.toString() == "null") null else content.takeIf { it.isNotBlank() }
}
