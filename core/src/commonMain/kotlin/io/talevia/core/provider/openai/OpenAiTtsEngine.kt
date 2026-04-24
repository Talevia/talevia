package io.talevia.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import io.talevia.core.platform.SynthesizedAudio
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.TtsRequest
import io.talevia.core.platform.TtsResult
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * OpenAI Speech (`/v1/audio/speech`) backing for [TtsEngine].
 *
 * Mirrors [OpenAiImageGenEngine]'s translation style: native HTTP shape stays
 * inside this class, common types come out. Per CLAUDE.md §5 provider-native
 * types do NOT leak past [synthesize].
 *
 * Lives in `commonMain` because the call is JSON-in / bytes-out — no file IO,
 * no platform-specific concerns. (Whisper sits in jvmMain instead because it
 * needs `java.io.File` to upload bytes via multipart.)
 *
 * Request shape:
 * ```
 * { model, input, voice, response_format, speed }
 * ```
 * Response: raw audio bytes in the requested container; OpenAI returns no
 * structured metadata, so [GenerationProvenance.modelVersion] is null and
 * [GenerationProvenance.createdAtEpochMs] is filled from [clock] at request
 * time.
 */
class OpenAiTtsEngine(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val json: Json = JsonConfig.default,
    private val clock: Clock = Clock.System,
) : TtsEngine {

    override val providerId: String = "openai"

    override suspend fun synthesize(request: TtsRequest): TtsResult =
        synthesize(request, onWarmup = { })

    override suspend fun synthesize(
        request: TtsRequest,
        onWarmup: suspend (io.talevia.core.bus.BusEvent.ProviderWarmup.Phase) -> Unit,
    ): TtsResult {
        val body = buildRequestBody(request)
        onWarmup(io.talevia.core.bus.BusEvent.ProviderWarmup.Phase.Starting)
        val response: HttpResponse = httpClient.post("$baseUrl/v1/audio/speech") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        if (response.status != HttpStatusCode.OK) {
            val errBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            error("OpenAI TTS request failed: ${response.status} $errBody")
        }
        onWarmup(io.talevia.core.bus.BusEvent.ProviderWarmup.Phase.Ready)
        val audioBytes = response.readRawBytes()
        if (audioBytes.isEmpty()) error("OpenAI TTS returned empty audio body")

        return TtsResult(
            audio = SynthesizedAudio(audioBytes = audioBytes, format = request.format),
            provenance = GenerationProvenance(
                providerId = providerId,
                modelId = request.modelId,
                modelVersion = null,
                seed = 0L,
                parameters = body,
                createdAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun buildRequestBody(request: TtsRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(request.modelId))
        put("input", JsonPrimitive(request.text))
        put("voice", JsonPrimitive(request.voice))
        put("response_format", JsonPrimitive(request.format))
        put("speed", JsonPrimitive(request.speed))
        for ((k, v) in request.parameters) put(k, JsonPrimitive(v))
    }
}
