package io.talevia.core.provider.replicate

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.talevia.core.JsonConfig
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.UpscaleEngine
import io.talevia.core.platform.UpscaleRequest
import io.talevia.core.platform.UpscaleResult
import io.talevia.core.platform.UpscaledImage
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Replicate-hosted super-resolution backing for [UpscaleEngine] — turns
 * `upscale_asset` from contract-only into a working lane (VISION §2 "ML
 * 加工: 超分"). Sibling of `ReplicateMusicGenEngine`; same async-poll
 * shape, different model + output field.
 *
 * Upload strategy. Replicate model inputs that take binary media accept
 * either a public URL or a `data:` URI. The Tool layer hands us an
 * absolute local filesystem path via `UpscaleRequest.imagePath`; this
 * engine base64-encodes the file bytes and wraps them as
 * `data:application/octet-stream;base64,...`. Trade-off: the prediction
 * payload balloons with the image size (fine for stills up to a few MB;
 * for 4K+ inputs a pre-signed upload path is the escape hatch).
 *
 * Placed under `jvmMain` rather than `commonMain` because it reads the
 * source image via `java.io.File` — same rationale as
 * `OpenAiWhisperEngine` / `OpenAiVisionEngine`, which also need
 * filesystem access to upload binary media and therefore live alongside
 * JVM ktor-cio rather than in shared code.
 *
 * Model selection. Defaults to `nightmareai/real-esrgan` which accepts
 * `{image, scale, face_enhance}` and returns a single URL. Swap `modelSlug`
 * via the constructor for SUPIR / CodeFormer / etc. — any model whose
 * input takes `image` + returns a URL in `output` works without engine
 * changes.
 *
 * Per CLAUDE.md §5, Replicate-native types MUST NOT leak past [upscale].
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ReplicateUpscaleEngine(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val modelSlug: String = "nightmareai/real-esrgan",
    private val baseUrl: String = "https://api.replicate.com",
    private val json: Json = JsonConfig.default,
    private val clock: Clock = Clock.System,
    /** Poll interval — Real-ESRGAN jobs typically finish in 10-40 s. */
    private val pollIntervalMs: Long = 2_000L,
    private val maxWaitMs: Long = 10 * 60 * 1000L,
) : UpscaleEngine {

    override val providerId: String = "replicate"

    override suspend fun upscale(request: UpscaleRequest): UpscaleResult {
        val bytes = File(request.imagePath).readBytes()
        if (bytes.isEmpty()) error("upscale source ${request.imagePath} is empty or unreadable")
        val dataUri = "data:application/octet-stream;base64," + kotlin.io.encoding.Base64.encode(bytes)

        val wireInput = buildWireInput(request, dataUri)
        val createBody = buildJsonObject { put("input", wireInput) }
        val createResp: HttpResponse = httpClient.post("$baseUrl/v1/models/$modelSlug/predictions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), createBody))
        }
        if (createResp.status != HttpStatusCode.OK && createResp.status != HttpStatusCode.Created) {
            val errBody = runCatching { createResp.bodyAsText() }.getOrNull().orEmpty()
            error("Replicate $modelSlug predictions create failed: ${createResp.status} $errBody")
        }

        val createJson = json.parseToJsonElement(createResp.bodyAsText()).jsonObject
        val jobId = createJson["id"]?.jsonPrimitive?.content
            ?: error("Replicate create response missing `id`")
        val pollUrl = createJson["urls"]?.jsonObject?.get("get")?.jsonPrimitive?.content
            ?: "$baseUrl/v1/predictions/$jobId"

        val finalJob = pollUntilTerminal(pollUrl, jobId)
        val status = finalJob["status"]?.jsonPrimitive?.content
        if (status != "succeeded") {
            val err = finalJob["error"]?.takeUnless { it is JsonNull }?.toString().orEmpty()
            error("Replicate $modelSlug job $jobId ended status=$status $err")
        }

        val imageUrl = extractImageUrl(finalJob["output"])
            ?: error("Replicate $modelSlug job $jobId succeeded but output was missing an image URL")
        val imageResp: HttpResponse = httpClient.get(imageUrl)
        if (imageResp.status != HttpStatusCode.OK) {
            val errBody = runCatching { imageResp.bodyAsText() }.getOrNull().orEmpty()
            error("Replicate upscaled image download failed: ${imageResp.status} $errBody")
        }
        val outBytes = imageResp.readRawBytes()
        if (outBytes.isEmpty()) error("Replicate $modelSlug returned empty image body for job $jobId")

        // SR providers don't reliably echo output dimensions; UpscaledImage
        // carries (0, 0) here and the Tool layer's `storage.import` probes
        // the persisted bytes to populate MediaMetadata correctly.
        val image = UpscaledImage(
            imageBytes = outBytes,
            format = request.format,
            width = 0,
            height = 0,
        )
        val provenance = GenerationProvenance(
            providerId = providerId,
            modelId = modelSlug,
            modelVersion = finalJob["version"]?.jsonPrimitive?.content,
            seed = request.seed,
            parameters = provenanceParameters(request),
            createdAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        return UpscaleResult(image = image, provenance = provenance)
    }

    private suspend fun pollUntilTerminal(pollUrl: String, jobId: String): JsonObject {
        val deadline = clock.now().toEpochMilliseconds() + maxWaitMs
        while (true) {
            val resp: HttpResponse = httpClient.get(pollUrl) { bearerAuth(apiKey) }
            if (resp.status != HttpStatusCode.OK) {
                val errBody = runCatching { resp.bodyAsText() }.getOrNull().orEmpty()
                error("Replicate predictions poll failed: ${resp.status} $errBody")
            }
            val payload = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val status = payload["status"]?.jsonPrimitive?.content
            when (status) {
                "succeeded", "failed", "canceled" -> return payload
                null -> error("Replicate predictions poll returned payload without `status`")
                else -> Unit
            }
            if (clock.now().toEpochMilliseconds() > deadline) {
                error("Replicate $modelSlug job $jobId did not finish within ${maxWaitMs}ms (last status=$status)")
            }
            delay(pollIntervalMs)
        }
    }

    private fun extractImageUrl(output: JsonElement?): String? = when {
        output == null || output is JsonNull -> null
        output is JsonPrimitive -> output.content.takeIf { it.isNotBlank() }
        output is JsonArray -> output.firstOrNull()?.let { extractImageUrl(it) }
        output is JsonObject -> output["image"]?.let { extractImageUrl(it) }
            ?: output["url"]?.let { extractImageUrl(it) }
            ?: output["output"]?.let { extractImageUrl(it) }
        else -> null
    }

    private fun buildWireInput(request: UpscaleRequest, imageDataUri: String): JsonObject = buildJsonObject {
        put("image", JsonPrimitive(imageDataUri))
        put("scale", JsonPrimitive(request.scale))
        // Seed passed so the lockfile hash is meaningful; models that ignore
        // the key drop it silently.
        put("seed", JsonPrimitive(request.seed))
        for ((k, v) in request.parameters) put(k, JsonPrimitive(v))
    }

    /**
     * Audit payload. Deliberately *does not* echo the `image` data URI —
     * keeps the lockfile JSON from ballooning with the entire input image
     * (which would double storage cost per entry) while preserving the
     * replay-identifying parameters.
     */
    private fun provenanceParameters(request: UpscaleRequest): JsonObject = buildJsonObject {
        put("scale", JsonPrimitive(request.scale))
        put("seed", JsonPrimitive(request.seed))
        put("format", JsonPrimitive(request.format))
        put("_talevia_model_slug", JsonPrimitive(modelSlug))
        for ((k, v) in request.parameters) put(k, JsonPrimitive(v))
    }
}
