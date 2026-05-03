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

    // OpenAI image-gen accepts `n` as a top-level field (line 114 of the
    // wire body), so a single API call returns N distinct images.
    // `aigc-multi-variant-phase3-openai-native-n` (cycle 33): the
    // [AigcGenerateTool] dispatcher reads this flag to decide whether to
    // batch-dispatch via [GenerateImageTool.executeBatch] or fall back
    // to the sequential N×1 loop.
    override val supportsNativeBatch: Boolean = true

    override suspend fun generate(request: ImageGenRequest): ImageGenResult =
        generate(request, onWarmup = { })

    override suspend fun generate(
        request: ImageGenRequest,
        onWarmup: suspend (io.talevia.core.bus.BusEvent.ProviderWarmup.Phase) -> Unit,
    ): ImageGenResult {
        val wireBody = buildWireBody(request)
        onWarmup(io.talevia.core.bus.BusEvent.ProviderWarmup.Phase.Starting)
        val response: HttpResponse = httpClient.post("$baseUrl/v1/images/generations") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), wireBody))
        }
        if (response.status != HttpStatusCode.OK) {
            val errBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            error("OpenAI images request failed: ${response.status} $errBody")
        }
        // First successful response byte = provider accepted the request + is
        // returning payload; the cold-start window has ended.
        onWarmup(io.talevia.core.bus.BusEvent.ProviderWarmup.Phase.Ready)

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
            parameters = provenanceParameters(request, wireBody),
            createdAtEpochMs = createdSeconds * 1000L,
        )
        return ImageGenResult(images = images, provenance = provenance)
    }

    internal fun buildWireBody(request: ImageGenRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(request.modelId))
        put("prompt", JsonPrimitive(request.prompt))
        put("size", JsonPrimitive("${request.width}x${request.height}"))
        put("n", JsonPrimitive(request.n))
        put("response_format", JsonPrimitive("b64_json"))
        // NO `seed` — the current `/v1/images/generations` models (gpt-image-1,
        // dall-e-3) reject it with 400 `Unknown parameter: 'seed'`. Seed stays
        // on [GenerationProvenance.seed] for lockfile cache-key discipline but
        // is never put on the wire; callers that try to smuggle it via
        // `parameters` are filtered too.
        for ((k, v) in request.parameters) {
            if (k == "seed") continue
            put(k, JsonPrimitive(v))
        }
    }

    /**
     * Provenance is a superset of the wire body. OpenAI's
     * `/v1/images/generations` endpoint doesn't accept negative prompts,
     * reference images, or LoRA pins (those go through `/v1/images/edits`
     * via multipart, and LoRA isn't supported at all), but the audit log
     * must still capture what the caller *asked* for — dropping these on the
     * floor would make the lockfile hash look like a cache hit even when
     * the semantic input had changed.
     */
    private fun provenanceParameters(request: ImageGenRequest, wireBody: JsonObject): JsonObject =
        buildJsonObject {
            for ((k, v) in wireBody) put(k, v)
            request.negativePrompt?.takeIf { it.isNotBlank() }?.let {
                put("_talevia_negative_prompt", JsonPrimitive(it))
            }
            if (request.referenceAssetPaths.isNotEmpty()) {
                put(
                    "_talevia_reference_asset_paths",
                    JsonPrimitive(request.referenceAssetPaths.joinToString(",")),
                )
            }
            if (request.loraPins.isNotEmpty()) {
                put(
                    "_talevia_lora_pins",
                    JsonPrimitive(
                        request.loraPins.joinToString(",") { "${it.adapterId}@${it.weight}" },
                    ),
                )
            }
        }
}
