package io.talevia.core.provider.volcano

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
import io.talevia.core.bus.BusEvent
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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * ByteDance Seedance backing for [VideoGenEngine] via Volcano Engine ARK
 * (`/api/v3/contents/generations/tasks`). Second non-stub `VideoGenEngine`
 * impl alongside [io.talevia.core.provider.openai.OpenAiSoraVideoGenEngine] —
 * unblocks M2 criterion 2 (provider 多元) and proves the abstraction is
 * provider-agnostic rather than openai-shaped.
 *
 * Async polling shape, parallel to Sora / Replicate MusicGen:
 *  1. `POST /api/v3/contents/generations/tasks` with
 *     `{model, content:[{type:"text", text}], resolution, ratio, duration, watermark}`.
 *  2. `GET /api/v3/contents/generations/tasks/{taskId}` on a fixed interval
 *     until `status` reaches `succeeded` / `failed` / `expired` / `cancelled`.
 *  3. On success, download `content.video_url` (a 24h-signed URL — no auth
 *     header needed for the GET).
 *
 * Per CLAUDE.md §5, ARK-native types MUST NOT leak past [generate]. Polling
 * payload shapes stay JsonObject-local to this file; we emit the shared
 * [VideoGenResult] / [GeneratedVideo] / [GenerationProvenance].
 *
 * **Resolution / ratio** — `VideoGenRequest` is `(width, height)` because the
 * other engines (Sora) take pixel dimensions. ARK takes a coarse
 * `resolution` enum (`480p|720p|1080p|2K`) plus a discrete `ratio` enum
 * (`16:9|9:16|4:3|3:4|21:9|1:1`). [buildWireBody] derives both from the
 * request's `width × height`. Callers can override either by pre-computing
 * and passing `parameters = mapOf("resolution" to "1080p", "ratio" to
 * "21:9")` — the merge step lets a caller's parameter win over the derived
 * value, mirroring Sora / Replicate-Music.
 *
 * **Seed** — Seedance's public ARK surface (as of 2026-04) does not document
 * a `seed` field; sending one risks a 400. We follow the same pattern as
 * Sora: keep [request.seed] in [GenerationProvenance.seed] for lockfile
 * cache-key discipline but never put it on the wire. Callers can opt in via
 * `parameters["seed"]` if a future model exposes it.
 *
 * **Default model** — `doubao-seedance-2-0-260128` (Seedance 2.0). Callers
 * point `request.modelId` at lite / fast variants
 * (`doubao-seedance-2-0-fast-260128`, `doubao-seedance-1-0-pro-250528`,
 * etc.) when they want a different price/quality knob.
 *
 * **Gating** — JVM containers wire a real instance only when `ARK_API_KEY`
 * is set; otherwise the `VideoGenEngine` slot falls back to OpenAI Sora (if
 * `OPENAI_API_KEY` is set) or stays null and `generate_video` stays
 * unregistered.
 */
class SeedanceVideoGenEngine(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://ark.cn-beijing.volces.com",
    private val json: Json = JsonConfig.default,
    private val clock: Clock = Clock.System,
    /** Interval between polls of `/api/v3/contents/generations/tasks/{id}`. */
    private val pollIntervalMs: Long = 3_000L,
    /** Hard deadline; Seedance jobs typically finish in 30–180 s. */
    private val maxWaitMs: Long = 10 * 60 * 1000L,
) : VideoGenEngine {

    override val providerId: String = "volcano-seedance"

    override suspend fun generate(request: VideoGenRequest): VideoGenResult =
        generate(request, onWarmup = { })

    override suspend fun generate(
        request: VideoGenRequest,
        onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit,
    ): VideoGenResult {
        val wireBody = buildWireBody(request)

        // Signal warmup before any network I/O so a UI subscriber sees the
        // "warming up Seedance…" line even if TLS / DNS stalls.
        onWarmup(BusEvent.ProviderWarmup.Phase.Starting)
        val createResp: HttpResponse = httpClient.post("$baseUrl/api/v3/contents/generations/tasks") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), wireBody))
        }
        if (createResp.status != HttpStatusCode.OK && createResp.status != HttpStatusCode.Created) {
            val errBody = runCatching { createResp.bodyAsText() }.getOrNull().orEmpty()
            error("Seedance tasks create failed: ${createResp.status} $errBody")
        }
        val createJson = json.parseToJsonElement(createResp.bodyAsText()).jsonObject
        val taskId = createJson["id"]?.jsonPrimitive?.content
            ?: error("Seedance create response missing `id`: $createJson")
        // Submit accepted → cold-start window past, queue wait begins.
        onWarmup(BusEvent.ProviderWarmup.Phase.Ready)

        val finalTask = pollUntilTerminal(taskId)
        val status = finalTask["status"]?.jsonPrimitive?.content
        if (status != "succeeded") {
            val err = finalTask["error"]?.toString().orEmpty()
            error("Seedance task $taskId ended with status=$status $err")
        }

        val videoUrl = finalTask["content"]?.jsonObject?.get("video_url")?.jsonPrimitive?.content
            ?: error("Seedance task $taskId succeeded but content.video_url is missing: $finalTask")

        // ARK returns a 24h-signed URL — no auth header needed (and adding
        // one would cause some CDN edges to 403). Plain GET.
        val videoResp: HttpResponse = httpClient.get(videoUrl)
        if (videoResp.status != HttpStatusCode.OK) {
            val errBody = runCatching { videoResp.bodyAsText() }.getOrNull().orEmpty()
            error("Seedance video download failed: ${videoResp.status} $errBody")
        }
        val mp4Bytes = videoResp.readRawBytes()
        if (mp4Bytes.isEmpty()) error("Seedance returned empty body for task $taskId")

        // ARK doesn't echo back the actual rendered duration in a stable
        // field; trust the request's target. Width / height aren't echoed
        // either (only `resolution`); same trust pattern.
        val video = GeneratedVideo(
            mp4Bytes = mp4Bytes,
            width = request.width,
            height = request.height,
            durationSeconds = request.durationSeconds,
        )
        val provenance = GenerationProvenance(
            providerId = providerId,
            // ARK's task response doesn't echo a separate model checkpoint
            // version; the modelId already includes the dated build slug
            // (e.g. `doubao-seedance-2-0-260128`).
            modelId = request.modelId,
            modelVersion = null,
            seed = request.seed,
            parameters = provenanceParameters(request, wireBody),
            createdAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        return VideoGenResult(videos = listOf(video), provenance = provenance)
    }

    private suspend fun pollUntilTerminal(taskId: String): JsonObject {
        val deadline = clock.now().toEpochMilliseconds() + maxWaitMs
        while (true) {
            val resp: HttpResponse = httpClient.get("$baseUrl/api/v3/contents/generations/tasks/$taskId") {
                bearerAuth(apiKey)
            }
            if (resp.status != HttpStatusCode.OK) {
                val errBody = runCatching { resp.bodyAsText() }.getOrNull().orEmpty()
                error("Seedance task poll failed: ${resp.status} $errBody")
            }
            val payload = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val status = payload["status"]?.jsonPrimitive?.content
            when (status) {
                "succeeded", "failed", "expired", "cancelled" -> return payload
                null -> error("Seedance task poll returned payload without `status`")
                else -> Unit // queued / running — keep polling
            }
            if (clock.now().toEpochMilliseconds() > deadline) {
                error("Seedance task $taskId did not finish within ${maxWaitMs}ms (last status=$status)")
            }
            delay(pollIntervalMs)
        }
    }

    internal fun buildWireBody(request: VideoGenRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(request.modelId))
        put(
            "content",
            buildJsonArray {
                addJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(request.prompt))
                }
            },
        )
        put("resolution", JsonPrimitive(deriveResolution(request.width, request.height)))
        put("ratio", JsonPrimitive(deriveRatio(request.width, request.height)))
        // ARK accepts integer seconds; round up so a 5.5s request doesn't
        // silently truncate to 5. Min 1s — the API errors below that.
        put(
            "duration",
            JsonPrimitive(kotlin.math.ceil(request.durationSeconds).toInt().coerceAtLeast(1)),
        )
        // Watermark off by default — agent-driven exports are typically
        // composited downstream and a watermark would persist into the final.
        // Caller can re-enable via parameters["watermark"]="true".
        put("watermark", JsonPrimitive(false))
        for ((k, v) in request.parameters) {
            // Filter `seed` defensively — ARK doesn't accept it on Seedance
            // (as of 2026-04). Same posture as Sora wireBody.
            if (k == "seed") continue
            put(k, JsonPrimitive(v))
        }
    }

    /**
     * Provenance is a superset of the wire body — captures what the caller
     * *asked for* even when ARK doesn't accept the field on the wire (seed,
     * negative prompt, reference assets, LoRA pins). Dropping these on the
     * floor would let the lockfile cache key collide across semantically
     * distinct generations.
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

    companion object {
        /**
         * Map pixel dims to ARK's `resolution` enum. Decision pivot is the
         * **short side** so portrait (e.g. 720x1280) and landscape (1280x720)
         * resolve to the same tier (720p), matching ARK's documented behaviour.
         */
        internal fun deriveResolution(width: Int, height: Int): String {
            val shortSide = minOf(width, height)
            return when {
                shortSide <= 480 -> "480p"
                shortSide <= 720 -> "720p"
                shortSide <= 1080 -> "1080p"
                else -> "2K"
            }
        }

        /**
         * Map pixel dims to ARK's `ratio` enum. Picks the closest of the
         * documented options by absolute ratio difference; ties favour
         * orientation (`9:16` over `4:3` for portrait inputs). When the input
         * is degenerate (zero height), default to `16:9`.
         */
        internal fun deriveRatio(width: Int, height: Int): String {
            if (height <= 0 || width <= 0) return "16:9"
            val r = width.toDouble() / height.toDouble()
            // Order matters: extremes (21:9 cinematic, 9:16 portrait) listed
            // before the central 16:9 / 4:3 / 1:1 / 3:4 so they win on ties.
            val candidates = listOf(
                "21:9" to 21.0 / 9.0,
                "16:9" to 16.0 / 9.0,
                "4:3" to 4.0 / 3.0,
                "1:1" to 1.0,
                "3:4" to 3.0 / 4.0,
                "9:16" to 9.0 / 16.0,
            )
            return candidates.minBy { abs(it.second - r) }.first
        }
    }
}
