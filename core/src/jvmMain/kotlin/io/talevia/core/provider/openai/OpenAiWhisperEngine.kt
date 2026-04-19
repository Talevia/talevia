package io.talevia.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.talevia.core.JsonConfig
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.AsrRequest
import io.talevia.core.platform.AsrResult
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.TranscriptSegment
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * OpenAI Whisper (`/v1/audio/transcriptions`) backing for [AsrEngine].
 *
 * Mirrors [OpenAiImageGenEngine]'s translation style — multipart upload at the
 * boundary, native JSON parsed into common [TranscriptSegment] / [AsrResult]
 * before returning. Per CLAUDE.md §5 provider-native types do NOT leak past
 * [transcribe].
 *
 * Lives in `jvmMain` because Whisper requires raw audio bytes; we read the file
 * via `java.io.File` once per call. Native (iOS/Android) impls will appear when
 * those platforms get their own ASR provider — the [AsrEngine] interface is
 * already in `commonMain` ready for them.
 *
 * `response_format=verbose_json` so we get per-segment timestamps that line up
 * with `Clip.timeRange` units. `timestamp_granularities[]=segment` keeps the
 * payload focused on the granularity downstream subtitle tools want.
 */
class OpenAiWhisperEngine(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val json: Json = JsonConfig.default,
    private val clock: Clock = Clock.System,
) : AsrEngine {

    override val providerId: String = "openai"

    override suspend fun transcribe(request: AsrRequest): AsrResult {
        val file = File(request.audioPath)
        require(file.exists()) { "audio path does not exist: ${request.audioPath}" }
        val bytes = file.readBytes()
        val fileName = file.name.ifBlank { "audio.bin" }

        val parts = formData {
            append(
                "file",
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, contentTypeFor(fileName).toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                },
            )
            append("model", request.modelId)
            append("response_format", "verbose_json")
            append("timestamp_granularities[]", "segment")
            request.languageHint?.takeIf { it.isNotBlank() }?.let { append("language", it) }
            for ((k, v) in request.parameters) append(k, v)
        }

        val response: HttpResponse = httpClient.post("$baseUrl/v1/audio/transcriptions") {
            bearerAuth(apiKey)
            setBody(MultiPartFormDataContent(parts))
        }
        if (response.status != HttpStatusCode.OK) {
            val errBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            error("OpenAI Whisper request failed: ${response.status} $errBody")
        }

        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val text = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val language = payload["language"]?.jsonPrimitive?.contentOrNull
        val segments = payload["segments"]?.jsonArray.orEmpty().map { el ->
            val obj = el.jsonObject
            val startSec = obj["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val endSec = obj["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            TranscriptSegment(
                startMs = (startSec * 1000.0).toLong(),
                endMs = (endSec * 1000.0).toLong(),
                text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            )
        }

        val provenanceParams = buildJsonObject {
            put("model", JsonPrimitive(request.modelId))
            put("response_format", JsonPrimitive("verbose_json"))
            request.languageHint?.let { put("language", JsonPrimitive(it)) }
            for ((k, v) in request.parameters) put(k, JsonPrimitive(v))
        }

        return AsrResult(
            text = text,
            language = language,
            segments = segments,
            provenance = GenerationProvenance(
                providerId = providerId,
                modelId = request.modelId,
                modelVersion = null,
                seed = 0L,
                parameters = provenanceParams,
                createdAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun contentTypeFor(fileName: String): ContentType {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "mp3" -> ContentType.parse("audio/mpeg")
            "wav" -> ContentType.parse("audio/wav")
            "m4a", "aac" -> ContentType.parse("audio/mp4")
            "flac" -> ContentType.parse("audio/flac")
            "ogg", "oga" -> ContentType.parse("audio/ogg")
            "webm" -> ContentType.parse("audio/webm")
            "mp4", "m4v" -> ContentType.parse("video/mp4")
            "mov" -> ContentType.parse("video/quicktime")
            "mpeg", "mpg" -> ContentType.parse("video/mpeg")
            else -> ContentType.Application.OctetStream
        }
    }
}
