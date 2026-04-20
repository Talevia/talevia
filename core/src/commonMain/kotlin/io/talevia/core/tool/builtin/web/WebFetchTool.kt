package io.talevia.core.tool.builtin.web

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.talevia.core.JsonConfig
import io.talevia.core.permission.PermissionSpec
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
 * Fetch the contents of an HTTP(S) URL and return them as text. Mirrors
 * Claude Code's WebFetch and OpenCode's `tool/webfetch.ts`. The agent uses
 * this for the "read this doc / blog / gist and tell me what it says" class
 * of requests — we don't run a full JS browser, and we don't follow
 * pagination; this is one GET, one body, best-effort text extraction.
 *
 * Permission is `web.fetch` with the URL **host** as the pattern (so
 * approving `github.com` once covers all its paths — rules keyed on full
 * URLs would never hit). Defaults to ASK — network fetches are a real-world
 * side effect (cost + traceable user-agent) and should never be silent.
 *
 * HTML is stripped to rough plain text (tags removed, scripts / styles
 * dropped, whitespace collapsed). We don't run a DOM parser — we're not
 * trying to render pages, just give the LLM readable signal. Plain
 * `text/plain` / `application/json` / `application/xml` content types pass
 * through unchanged. Binary responses fail loudly with a content-type
 * error; the agent should pick the right tool (e.g. `import_media`) for
 * media URLs.
 */
class WebFetchTool(private val httpClient: HttpClient) :
    Tool<WebFetchTool.Input, WebFetchTool.Output> {
    @Serializable
    data class Input(
        val url: String,
        val maxBytes: Long? = null,
    )

    @Serializable
    data class Output(
        val url: String,
        val status: Int,
        val contentType: String?,
        val content: String,
        val bytes: Long,
        val truncated: Boolean,
    )

    override val id: String = "web_fetch"
    override val helpText: String =
        "HTTP GET a URL and return the body as text. HTML is tag-stripped to rough plain text. " +
            "Use for reading a doc / blog post / gist the user mentioned. NOT for downloading " +
            "media (use import_media), NOT for paginated / scripted SPAs (we don't run JS). " +
            "ASK permission gated per-host."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "web.fetch",
        patternFrom = { raw -> extractHostPattern(raw) },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "Absolute http(s):// URL to fetch.")
            }
            putJsonObject("maxBytes") {
                put("type", "integer")
                put(
                    "description",
                    "Cap on response bytes returned. Default $DEFAULT_MAX_BYTES; responses larger " +
                        "than this are truncated and `truncated=true` is set.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("url"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.url.isNotBlank()) { "url must not be blank" }
        val parsed = runCatching { Url(input.url) }.getOrElse {
            throw IllegalArgumentException("invalid URL: ${input.url}")
        }
        require(parsed.protocol.name == "http" || parsed.protocol.name == "https") {
            "only http / https URLs are supported (got ${parsed.protocol.name})"
        }
        val cap = (input.maxBytes ?: DEFAULT_MAX_BYTES).coerceAtMost(HARD_MAX_BYTES)
        require(cap > 0) { "maxBytes must be positive (got $cap)" }

        val response: HttpResponse = httpClient.get(input.url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "text/html,text/plain,application/json")
        }
        val contentType = response.contentType()
        val contentTypeString = contentType?.toString()
        if (contentType != null && !isTextLike(contentType)) {
            throw IllegalArgumentException(
                "unsupported content-type $contentTypeString at ${input.url} — " +
                    "web_fetch only handles text. For media URLs use import_media.",
            )
        }

        val rawBody = response.bodyAsText()
        val rawBytes = rawBody.encodeToByteArray()
        val (bodyText, truncated) = if (rawBytes.size > cap) {
            rawBytes.decodeToString(0, cap.toInt()) to true
        } else {
            rawBody to false
        }

        val extracted = if (contentType != null && contentType.match("text/html")) {
            stripHtml(bodyText)
        } else {
            bodyText
        }

        if (!response.status.isSuccess()) {
            throw IllegalArgumentException(
                "HTTP ${response.status.value} from ${input.url}: ${extracted.take(500)}",
            )
        }

        return ToolResult(
            title = "web_fetch ${input.url}",
            outputForLlm = buildString {
                append("GET ").append(input.url).append(" [").append(response.status.value).append("]")
                if (contentTypeString != null) append(" ").append(contentTypeString)
                if (truncated) append(" (truncated to $cap bytes)")
                append('\n')
                append(extracted)
            },
            data = Output(
                url = input.url,
                status = response.status.value,
                contentType = contentTypeString,
                content = extracted,
                bytes = rawBytes.size.toLong(),
                truncated = truncated,
            ),
        )
    }

    internal companion object {
        /** 1 MB default; enough for docs / articles / READMEs, refuses to slurp SPAs. */
        const val DEFAULT_MAX_BYTES: Long = 1_048_576L

        /** Hard cap the tool accepts from the LLM — 5 MB. */
        const val HARD_MAX_BYTES: Long = 5L * 1_048_576L

        private const val USER_AGENT = "Talevia-Agent/1.0"

        private fun isTextLike(ct: ContentType): Boolean =
            ct.contentType == "text" ||
                ct.match(ContentType.Application.Json) ||
                ct.match("application/xml") ||
                ct.match("application/xhtml+xml") ||
                ct.contentSubtype.endsWith("+json") ||
                ct.contentSubtype.endsWith("+xml")

        internal fun extractHostPattern(
            inputJson: String,
            json: Json = JsonConfig.default,
        ): String = runCatching {
            val raw = json.parseToJsonElement(inputJson).jsonObject["url"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            // Ktor's Url("") returns host="localhost" by default; only trust the parse
            // when the raw URL explicitly names a host via a scheme + authority.
            val parsed = Url(raw)
            if (parsed.host.isBlank()) null else parsed.host
        }.getOrNull() ?: "*"

        /**
         * Roughly convert HTML to plain text. Drops `<script>` / `<style>` /
         * `<noscript>` bodies, strips tags, decodes the handful of entities
         * users actually write (`&amp; &lt; &gt; &quot; &nbsp; &#39;`), and
         * collapses whitespace. This is not a DOM parser; we're not trying
         * to render pages, just give the LLM readable signal.
         */
        internal fun stripHtml(html: String): String {
            var text = html
            // Drop script / style / noscript blocks entirely.
            val blockRx = Regex(
                "<(script|style|noscript)\\b[^>]*>[\\s\\S]*?</\\1>",
                RegexOption.IGNORE_CASE,
            )
            text = blockRx.replace(text, "")
            // Strip comments.
            text = Regex("<!--[\\s\\S]*?-->").replace(text, "")
            // Convert <br> / <p> / block boundaries to newlines before stripping tags.
            text = Regex("<(br|/p|/div|/li|/h[1-6]|/tr)\\b[^>]*>", RegexOption.IGNORE_CASE)
                .replace(text) { "\n" }
            // Strip remaining tags.
            text = Regex("<[^>]+>").replace(text, "")
            // Decode common entities.
            text = text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
            // Collapse runs of whitespace within lines; collapse 3+ blank lines to 2.
            text = Regex("[\\t ]+").replace(text, " ")
            text = Regex("\\n[\\t ]+").replace(text, "\n")
            text = Regex("\\n{3,}").replace(text, "\n\n")
            return text.trim()
        }
    }
}
