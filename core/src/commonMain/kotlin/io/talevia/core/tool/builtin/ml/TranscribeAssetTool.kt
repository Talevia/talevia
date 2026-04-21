package io.talevia.core.tool.builtin.ml

import io.talevia.core.AssetId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.AsrRequest
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.TranscriptSegment
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Transcribe an imported audio / video asset via an [AsrEngine] (VISION §5.2 ML
 * lane). Returns the full text plus time-aligned segments the agent can hand to
 * `add_subtitles` for auto-captioning, or quote back when planning cuts ("the
 * speaker mentions the team at 00:14, that's a natural moment to insert B-roll").
 *
 * Permission: `"ml.transcribe"` — defaults to ASK because the audio is uploaded
 * to a third-party provider; the user should consent to that exfiltration the
 * same way they consent to AIGC calls. The headless server resolves ASK to deny
 * by design.
 *
 * Caching: v1 does not consult the project lockfile — `LockfileEntry` requires
 * an asset id and ASR's "output" is structured text, not bytes. If repeat-ASR
 * on the same asset becomes a real workflow, materialize the transcript as a
 * JSON asset and key a lockfile entry off it.
 */
class TranscribeAssetTool(
    private val engine: AsrEngine,
    private val resolver: MediaPathResolver,
) : Tool<TranscribeAssetTool.Input, TranscribeAssetTool.Output> {

    @Serializable data class Input(
        val assetId: String,
        val model: String = "whisper-1",
        val language: String? = null,
    )

    @Serializable data class Output(
        val assetId: String,
        val providerId: String,
        val modelId: String,
        val detectedLanguage: String?,
        val text: String,
        val segments: List<TranscriptSegment>,
    )

    override val id: String = "transcribe_asset"
    override val helpText: String =
        "Transcribe an imported audio or video asset via an ASR provider (default model: whisper-1). " +
            "Returns the full text plus time-aligned segments (start/end in ms). " +
            "Pass language as ISO-639-1 ('en', 'zh') to skip auto-detection. " +
            "Audio is uploaded to the provider — the user is asked to confirm before each call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("ml.transcribe")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("assetId") {
                put("type", "string")
                put("description", "Asset id returned by import_media or generate_image / similar.")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped model id (default: whisper-1).")
            }
            putJsonObject("language") {
                put("type", "string")
                put("description", "Optional ISO-639-1 language hint (e.g. 'en'). Omit to auto-detect.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("assetId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val assetId = AssetId(input.assetId)
        val path = resolver.resolve(assetId)
        val result = engine.transcribe(
            AsrRequest(
                audioPath = path,
                modelId = input.model,
                languageHint = input.language?.takeIf { it.isNotBlank() },
            ),
        )
        val out = Output(
            assetId = input.assetId,
            providerId = result.provenance.providerId,
            modelId = result.provenance.modelId,
            detectedLanguage = result.language,
            text = result.text,
            segments = result.segments,
        )
        val preview = result.text.take(120).replace('\n', ' ')
        val tail = if (preview.length < result.text.length) "…" else ""
        val langTail = result.language?.let { " language=$it" }.orEmpty()
        return ToolResult(
            title = "transcribe asset",
            outputForLlm = "Transcribed asset ${input.assetId} via ${result.provenance.providerId}/${result.provenance.modelId}$langTail. " +
                "${result.segments.size} segment(s); preview: \"$preview$tail\"",
            data = out,
        )
    }
}
