package io.talevia.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.talevia.core.JsonConfig
import io.talevia.core.platform.GeneratedImage
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.platform.ImageGenResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * OpenAI Images (`/v1/images/generations`) backing for [ImageGenEngine].
 *
 * Mirrors the translation style of [OpenAiProvider]: native response JSON is
 * parsed at the boundary and converted into common types — provider-native
 * types do NOT leak past [generate]. See CLAUDE.md §5.
 *
 * Request shape:
 * ```
 * { model, prompt, size: "<w>x<h>", n, seed, response_format: "b64_json" }
 * ```
 * Response shape:
 * ```
 * { created: <epoch-seconds>, data: [{ b64_json: "..." }, ...] }
 * ```
 *
 * OpenAI does not echo a checkpoint version back, so
 * [GenerationProvenance.modelVersion] is always `null` for this provider.
 * That is an accepted gap — `modelId + createdAtEpochMs` is the best lock
 * OpenAI gives us today, and the lockfile layer (P1-3) will either accept
 * that or layer a pinning strategy on top.
 */
@OptIn(ExperimentalEncodingApi::class)
class OpenAiImageGenEngine(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val json: Json = JsonConfig.default,
) : ImageGenEngine {

    override val providerId: String = "openai"

    override suspend fun generate(request: ImageGenRequest): ImageGenResult {
        val body = buildRequestBody(request)
        val response: HttpResponse = httpClient.post("$baseUrl/v1/images/generations") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        if (response.status != HttpStatusCode.OK) {
            val errBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            error("OpenAI images request failed: ${response.status} $errBody")
        }

        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = payload["data"]?.jsonArray ?: error("OpenAI images response missing `data`")
        val createdSeconds = payload["created"]?.jsonPrimitive?.longOrNull ?: 0L

        val images = data.map { el ->
            val b64 = el.jsonObject["b64_json"]?.jsonPrimitive?.content
                ?: error("OpenAI images response entry missing `b64_json`")
            val bytes = Base64.decode(b64)
            GeneratedImage(
                pngBytes = bytes,
                width = request.width,
                height = request.height,
            )
        }

        val provenance = GenerationProvenance(
            providerId = providerId,
            modelId = request.modelId,
            modelVersion = null,
            seed = request.seed,
            parameters = body,
            createdAtEpochMs = createdSeconds * 1000L,
        )
        return ImageGenResult(images = images, provenance = provenance)
    }

    private fun buildRequestBody(request: ImageGenRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(request.modelId))
        put("prompt", JsonPrimitive(request.prompt))
        put("size", JsonPrimitive("${request.width}x${request.height}"))
        put("n", JsonPrimitive(request.n))
        put("seed", JsonPrimitive(request.seed))
        put("response_format", JsonPrimitive("b64_json"))
        // Merge any provider-specific extras verbatim. These go straight into
        // the request and into provenance so later replays are byte-identical
        // to what the user asked for.
        for ((k, v) in request.parameters) put(k, JsonPrimitive(v))
    }
}
