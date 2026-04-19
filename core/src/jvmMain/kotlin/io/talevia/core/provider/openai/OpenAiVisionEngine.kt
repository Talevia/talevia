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
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.VisionEngine
import io.talevia.core.platform.VisionRequest
import io.talevia.core.platform.VisionResult
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Base64

/**
 * OpenAI Chat Completions (`/v1/chat/completions`) backing for [VisionEngine].
 *
 * Sends a single multimodal user message of the form `[ {type: "text", text: ...},
 * {type: "image_url", image_url: { url: "data:image/<ext>;base64,..." }} ]`, then
 * extracts `choices[0].message.content` as the description text. Per CLAUDE.md §5
 * provider-native types do NOT leak past [describe].
 *
 * Lives in `jvmMain` because the request requires raw image bytes; we read the
 * file via `java.io.File` and base64-encode once per call. Native (iOS/Android)
 * impls will appear when those platforms get their own vision provider — the
 * [VisionEngine] interface is already in `commonMain` ready for them.
 *
 * Non-image assets fail loudly: Vision API only accepts images, and transparently
 * falling back (e.g. frame-grabbing a video) would require a VideoEngine sidecar
 * that this commonMain-independent engine doesn't own. When video-frame describe
 * becomes a workflow, add it as a separate tool that extracts a frame first.
 */
class OpenAiVisionEngine(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val json: Json = JsonConfig.default,
    private val clock: Clock = Clock.System,
) : VisionEngine {

    override val providerId: String = "openai"

    override suspend fun describe(request: VisionRequest): VisionResult {
        val file = File(request.imagePath)
        require(file.exists()) { "image path does not exist: ${request.imagePath}" }
        val ext = file.extension.lowercase()
        val mimeType = when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> error(
                "OpenAI Vision only accepts image files (png/jpg/jpeg/webp/gif); got '.$ext' at ${request.imagePath}",
            )
        }
        val bytes = file.readBytes()
        val dataUrl = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}"
        val instruction = request.prompt?.takeIf { it.isNotBlank() }
            ?: "Describe this image. Note the subject, setting, notable colors / lighting, and any text visible."

        val body = buildJsonObject {
            put("model", request.modelId)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", instruction)
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", dataUrl) }
                                },
                            )
                        }
                    },
                )
            }
            for ((k, v) in request.parameters) put(k, JsonPrimitive(v))
        }

        val response: HttpResponse = httpClient.post("$baseUrl/v1/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }
        if (response.status != HttpStatusCode.OK) {
            val errBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            error("OpenAI Vision request failed: ${response.status} $errBody")
        }

        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val choices = payload["choices"]?.jsonArray ?: JsonArray(emptyList())
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        val text = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty().trim()

        val provenanceParams = buildJsonObject {
            put("model", JsonPrimitive(request.modelId))
            request.prompt?.let { put("prompt", JsonPrimitive(it)) }
            for ((k, v) in request.parameters) put(k, JsonPrimitive(v))
        }

        return VisionResult(
            text = text,
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
}
