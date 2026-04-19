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
import io.talevia.core.platform.GeneratedMusic
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.MusicGenEngine
import io.talevia.core.platform.MusicGenRequest
import io.talevia.core.platform.MusicGenResult
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

/**
 * Replicate-hosted MusicGen backing for [MusicGenEngine] — turns the
 * `generate_music` tool from "contract ships, no provider" into a usable
 * end-to-end lane (VISION §2 music pillar).
 *
 * Async polling shape, parallel to [OpenAiSoraVideoGenEngine]:
 *  1. `POST /v1/models/{slug}/predictions` with the tool's
 *     `{prompt, duration, seed, output_format}` on the Replicate `input` object.
 *  2. Poll `GET /v1/predictions/{id}` (or the `urls.get` returned by create)
 *     on a fixed interval until status terminates (`succeeded` / `failed` /
 *     `canceled`).
 *  3. Download the first URL in `output` (MusicGen returns either a single
 *     URL string or a one-element list depending on version).
 *
 * Per CLAUDE.md §5, Replicate-native types MUST NOT leak past [generate] —
 * the polling payloads stay local to this file and we emit only
 * [MusicGenResult] / [GeneratedMusic] / [GenerationProvenance].
 *
 * Gating. The desktop / server containers wire a real instance only when
 * `REPLICATE_API_TOKEN` is set in the environment; otherwise the
 * `MusicGenEngine` slot stays null and `generate_music` stays unregistered,
 * matching the pattern established by the OpenAI engines.
 *
 * Model selection. We scope to a single model slug at the constructor
 * (`meta/musicgen` by default) rather than trying to route the tool's
 * `modelId` to arbitrary Replicate models — Replicate's model surface is
 * wide, versions change, and a provider that silently accepted any slug
 * would leak "which model did we actually call" into `modelVersion=null`
 * on provenance. To use a different Replicate model, instantiate a second
 * engine with a different `modelSlug`.
 */
class ReplicateMusicGenEngine(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val modelSlug: String = "meta/musicgen",
    private val baseUrl: String = "https://api.replicate.com",
    private val json: Json = JsonConfig.default,
    private val clock: Clock = Clock.System,
    /** Interval between polls of the prediction status. */
    private val pollIntervalMs: Long = 3_000L,
    /** Hard deadline; MusicGen jobs typically finish in 30–120 s. */
    private val maxWaitMs: Long = 10 * 60 * 1000L,
) : MusicGenEngine {

    override val providerId: String = "replicate"

    override suspend fun generate(request: MusicGenRequest): MusicGenResult {
        val wireInput = buildWireInput(request)
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

        val audioUrl = extractAudioUrl(finalJob["output"])
            ?: error("Replicate $modelSlug job $jobId succeeded but output was missing an audio URL")

        val audioResp: HttpResponse = httpClient.get(audioUrl)
        if (audioResp.status != HttpStatusCode.OK) {
            val errBody = runCatching { audioResp.bodyAsText() }.getOrNull().orEmpty()
            error("Replicate audio download failed: ${audioResp.status} $errBody")
        }
        val bytes = audioResp.readRawBytes()
        if (bytes.isEmpty()) error("Replicate $modelSlug returned an empty audio body for job $jobId")

        // Replicate doesn't echo the actual duration back; trust the request's
        // target — the model clamps server-side and the real length is within
        // a bar or two. Callers that need exact duration can re-probe the
        // asset post-import.
        val music = GeneratedMusic(
            audioBytes = bytes,
            format = request.format,
            durationSeconds = request.durationSeconds,
        )
        val provenance = GenerationProvenance(
            providerId = providerId,
            modelId = modelSlug,
            modelVersion = finalJob["version"]?.jsonPrimitive?.content,
            seed = request.seed,
            parameters = provenanceParameters(request, wireInput),
            createdAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        return MusicGenResult(music = music, provenance = provenance)
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
                else -> Unit // starting / processing — keep polling
            }
            if (clock.now().toEpochMilliseconds() > deadline) {
                error("Replicate $modelSlug job $jobId did not finish within ${maxWaitMs}ms (last status=$status)")
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * Normalise the `output` shape. MusicGen returns either a single string
     * URL or a one-element list depending on model version; older versions
     * sometimes nest under `{audio: "..."}`. Handle all three defensively
     * and return null if nothing resolves to a URL — the caller errors with
     * a useful message.
     */
    private fun extractAudioUrl(output: JsonElement?): String? = when {
        output == null || output is JsonNull -> null
        output is JsonPrimitive -> output.content.takeIf { it.isNotBlank() }
        output is JsonArray -> output.firstOrNull()?.let { extractAudioUrl(it) }
        output is JsonObject -> output["audio"]?.let { extractAudioUrl(it) }
            ?: output["url"]?.let { extractAudioUrl(it) }
        else -> null
    }

    private fun buildWireInput(request: MusicGenRequest): JsonObject = buildJsonObject {
        put("prompt", JsonPrimitive(request.prompt))
        // MusicGen on Replicate accepts integer-second durations; round up
        // to the nearest second so a 15.5s request doesn't silently truncate
        // to 15. Longer requests are clamped server-side to the model's max.
        put("duration", JsonPrimitive(kotlin.math.ceil(request.durationSeconds).toInt().coerceAtLeast(1)))
        put("output_format", JsonPrimitive(request.format))
        // Seed is honored by some MusicGen versions, ignored by others.
        // Include unconditionally so when we switch to a seed-aware version
        // the determinism story works without an engine change.
        put("seed", JsonPrimitive(request.seed))
        for ((k, v) in request.parameters) put(k, JsonPrimitive(v))
    }

    /**
     * Provenance = wire input + anything the tool-layer surfaced that
     * Replicate doesn't accept. The lockfile hash already accounts for
     * prompt/seed/duration/format on the tool side, so provenance is purely
     * for audit / future replay.
     */
    private fun provenanceParameters(request: MusicGenRequest, wireInput: JsonObject): JsonObject =
        buildJsonObject {
            put("input", wireInput)
            put("_talevia_model_slug", JsonPrimitive(modelSlug))
            put("_talevia_requested_duration", JsonPrimitive(request.durationSeconds))
        }
}
