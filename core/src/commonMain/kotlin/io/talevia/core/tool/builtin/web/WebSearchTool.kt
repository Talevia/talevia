package io.talevia.core.tool.builtin.web

import io.talevia.core.JsonConfig
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.SearchEngine
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Search the web for a query and return a small list of `{title, url, snippet}`
 * hits (and an optional one-paragraph synthesised answer when the backing
 * provider supplies one). Mirrors Claude Code's WebSearch and OpenCode's
 * `tool/websearch.ts`.
 *
 * The agent reaches for this when it doesn't already know the URL — "find
 * recent posts about X", "what's the canonical doc for Y", "give me a few
 * references to feed into `web_fetch`". For known URLs use [WebFetchTool]
 * directly.
 *
 * Permission is `web.search` with the **lower-cased query** as the pattern,
 * so an "Always" rule scopes to the exact phrase the LLM searched for.
 * Defaults to ASK because every call is an external (potentially metered)
 * API hit; a user that wants frictionless search can flip the rule to ALLOW
 * with pattern `*` once. Server containers via `ServerPermissionService`
 * auto-reject ASK so headless deployments start deny-by-default.
 *
 * Backed by a [SearchEngine] (provider-agnostic). Wired only in containers
 * where a concrete provider is configured; otherwise the tool stays
 * unregistered, matching the AIGC-engine pattern.
 */
class WebSearchTool(private val engine: SearchEngine) :
    Tool<WebSearchTool.Input, WebSearchTool.Output> {
    @Serializable
    data class Input(
        val query: String,
        val maxResults: Int = DEFAULT_MAX_RESULTS,
    )

    @Serializable
    data class Hit(
        val title: String,
        val url: String,
        val snippet: String,
    )

    @Serializable
    data class Output(
        val query: String,
        val provider: String,
        val results: List<Hit>,
        /** Provider-synthesised one-paragraph answer when available; null otherwise. */
        val answer: String? = null,
    )

    override val id: String = "web_search"
    override val helpText: String =
        "Search the web for `query` and return a short list of {title, url, snippet} hits. " +
            "Use when you don't know a URL yet — for known URLs prefer `web_fetch`. " +
            "Returns up to maxResults (default $DEFAULT_MAX_RESULTS, max $HARD_MAX_RESULTS). " +
            "Some providers also return a one-paragraph synthesised answer. ASK permission " +
            "gated per-query."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "web.search",
        patternFrom = { raw -> extractQueryPattern(raw) },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Free-text search query.")
            }
            putJsonObject("maxResults") {
                put("type", "integer")
                put(
                    "description",
                    "Number of hits to return. Default $DEFAULT_MAX_RESULTS, max $HARD_MAX_RESULTS.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("query"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.query.isNotBlank()) { "query must not be blank" }
        val capped = input.maxResults.coerceIn(1, HARD_MAX_RESULTS)
        val raw = engine.search(input.query, capped)
        val hits = raw.results.map { Hit(title = it.title, url = it.url, snippet = it.snippet) }
        val output = Output(
            query = raw.query,
            provider = engine.providerId,
            results = hits,
            answer = raw.answer,
        )
        val text = buildString {
            append("web_search [").append(engine.providerId).append("] \"")
                .append(input.query).append("\" — ").append(hits.size).append(" hit(s)\n")
            raw.answer?.let { append("answer: ").append(it).append("\n\n") }
            hits.forEachIndexed { i, h ->
                append(i + 1).append(". ").append(h.title).append('\n')
                append("   ").append(h.url).append('\n')
                if (h.snippet.isNotBlank()) {
                    append("   ").append(h.snippet.lineSequence().joinToString(" ").trim()).append('\n')
                }
            }
            if (hits.isEmpty()) append("(no results)")
        }
        return ToolResult(
            title = "web_search ${input.query}",
            outputForLlm = text,
            data = output,
        )
    }

    internal companion object {
        const val DEFAULT_MAX_RESULTS: Int = 5
        const val HARD_MAX_RESULTS: Int = 20

        internal fun extractQueryPattern(
            inputJson: String,
            json: Json = JsonConfig.default,
        ): String = runCatching {
            json.parseToJsonElement(inputJson).jsonObject["query"]
                ?.jsonPrimitive?.content
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: "*"
    }
}
