package io.talevia.core.provider.openai

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
import io.talevia.core.platform.GeneratedVideo
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VideoGenRequest
import io.talevia.core.platform.VideoGenResult
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI Sora (`/v1/videos`) backing for [VideoGenEngine].
 *
 * The endpoint is asynchronous: POST creates a job, GET polls status, and once
 * the job reports `completed` the engine downloads the rendered bytes from
 * `/v1/videos/{id}/content`. Internally it polls on a fixed interval with a
 * hard deadline; callers see a single suspending call that blocks until the
 * video is ready (or the deadline is hit).
 *
 * Mirrors [OpenAiImageGenEngine] / [OpenAiTtsEngine]'s translation style —
 * provider-native response shapes stay local to this file, common types come
 * out. Per CLAUDE.md §5, provider-native types MUST NOT leak past [generate].
 *
 * Create request shape:
 * ```
 * { model, prompt, size: "<w>x<h>", seconds: <n> }
 * ```
 *
 * Note on `seed`: the OpenAI `/v1/videos` endpoint does **not** accept a seed
 * parameter (verified against a live 400 `Unknown parameter: 'seed'` response
 * on 2026-04). We still surface [request.seed] into provenance for the
 * lockfile's cache-key discipline (so the agent can ask for the same seed and
 * get the same lockfile slot even if Sora itself is non-deterministic) but we
 * no longer send it on the wire.
 * Create response shape:
 * ```
 * { id, status, created_at, ... }
 * ```
 * Poll response adds `status: queued|in_progress|completed|failed|cancelled`
 * and (when completed) may include `duration` so the engine can echo the
 * *actual* rendered duration rather than the requested one.
 *
 * OpenAI does not echo a checkpoint version back, so
 * [GenerationProvenance.modelVersion] is always `null`. See
 * [OpenAiImageGenEngine] for the same rationale.
 */
class OpenAiSoraVideoGenEngine(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val json: Json = JsonConfig.default,
    private val clock: Clock = Clock.System,
    /** Interval between polls of `/v1/videos/{id}`. */
    private val pollIntervalMs: Long = 5_000L,
    /** Hard deadline before we give up waiting on the job. Sora 2 jobs typically finish in well under this. */
    private val maxWaitMs: Long = 10 * 60 * 1000L,
) : VideoGenEngine {

    override val providerId: String = "openai"

    override suspend fun generate(request: VideoGenRequest): VideoGenResult {
        val wireBody = buildWireBody(request)
        val createResp: HttpResponse = httpClient.post("$baseUrl/v1/videos") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), wireBody))
        }
        if (createResp.status != HttpStatusCode.OK && createResp.status != HttpStatusCode.Created) {
            val errBody = runCatching { createResp.bodyAsText() }.getOrNull().orEmpty()
            error("OpenAI videos create failed: ${createResp.status} $errBody")
        }

        val createJson = json.parseToJsonElement(createResp.bodyAsText()).jsonObject
        val jobId = createJson["id"]?.jsonPrimitive?.content
            ?: error("OpenAI videos response missing `id`")

        val finalJob = pollUntilTerminal(jobId)
        val finalStatus = finalJob["status"]?.jsonPrimitive?.content
        if (finalStatus != "completed") {
            val err = finalJob["error"]?.toString().orEmpty()
            error("OpenAI video job $jobId ended with status=$finalStatus $err")
        }

        val contentResp: HttpResponse = httpClient.get("$baseUrl/v1/videos/$jobId/content") {
            bearerAuth(apiKey)
        }
        if (contentResp.status != HttpStatusCode.OK) {
            val errBody = runCatching { contentResp.bodyAsText() }.getOrNull().orEmpty()
            error("OpenAI videos download failed: ${contentResp.status} $errBody")
        }
        val mp4Bytes = contentResp.readRawBytes()
        if (mp4Bytes.isEmpty()) error("OpenAI videos returned empty body for job $jobId")

        // Prefer the duration the provider reports; fall back to the requested
        // duration if the response is sparse so the asset metadata is at least
        // the caller's declared intent.
        val actualDuration: Double =
            finalJob["duration"]?.jsonPrimitive?.doubleOrNull
                ?: finalJob["seconds"]?.jsonPrimitive?.doubleOrNull
                ?: request.durationSeconds

        val video = GeneratedVideo(
            mp4Bytes = mp4Bytes,
            width = request.width,
            height = request.height,
            durationSeconds = actualDuration,
        )

        val provenance = GenerationProvenance(
            providerId = providerId,
            modelId = request.modelId,
            modelVersion = null,
            seed = request.seed,
            parameters = provenanceParameters(request, wireBody),
            createdAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        return VideoGenResult(videos = listOf(video), provenance = provenance)
    }

    private suspend fun pollUntilTerminal(jobId: String): JsonObject {
        val deadline = clock.now().toEpochMilliseconds() + maxWaitMs
        while (true) {
            val resp: HttpResponse = httpClient.get("$baseUrl/v1/videos/$jobId") {
                bearerAuth(apiKey)
            }
            if (resp.status != HttpStatusCode.OK) {
                val errBody = runCatching { resp.bodyAsText() }.getOrNull().orEmpty()
                error("OpenAI videos poll failed: ${resp.status} $errBody")
            }
            val payload = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val status = payload["status"]?.jsonPrimitive?.content
            when (status) {
                "completed", "failed", "cancelled" -> return payload
                null -> error("OpenAI videos poll returned payload without `status`")
                else -> Unit // queued / in_progress / anything else → keep polling
            }
            if (clock.now().toEpochMilliseconds() > deadline) {
                error("OpenAI video job $jobId did not finish within ${maxWaitMs}ms (last status=$status)")
            }
            delay(pollIntervalMs)
        }
    }

    internal fun buildWireBody(request: VideoGenRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(request.modelId))
        put("prompt", JsonPrimitive(request.prompt))
        put("size", JsonPrimitive("${request.width}x${request.height}"))
        put("seconds", JsonPrimitive(request.durationSeconds))
        // NO `seed` — Sora rejects it with 400 Unknown parameter. Seed is kept in
        // [GenerationProvenance] for lockfile-cache discipline; callers can't
        // smuggle it back in via `parameters` either (filtered below).
        for ((k, v) in request.parameters) {
            if (k == "seed") continue
            put(k, JsonPrimitive(v))
        }
    }

    /**
     * Provenance is a superset of the wire body. Sora's synchronous endpoint
     * doesn't accept negative prompts, reference clips, or LoRA pins (yet), but
     * the audit log must still capture what the caller *asked* for — dropping
     * these on the floor would make the lockfile hash look like a cache hit
     * when the semantic input had changed.
     */
    private fun provenanceParameters(request: VideoGenRequest, wireBody: JsonObject): JsonObject =
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
