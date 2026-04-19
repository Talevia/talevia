package io.talevia.core.tool.builtin.aigc

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.TtsRequest
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
import kotlin.time.Duration

/**
 * Synthesize a voiceover via a [TtsEngine], persist the bytes through a
 * [MediaBlobWriter], register the result with [MediaStorage], and surface an
 * `AssetId` `add_clip` can drop onto an audio track. The first AIGC audio
 * tool — pairs with `transcribe_asset` (audio → text) to close the round-trip
 * for VISION §2's audio compiler lane.
 *
 * Lockfile cache (VISION §3.1): hash key is `(tool, model, voice, format,
 * speed, text)`. Same inputs → same asset id, no provider round-trip. There's
 * no seed — TTS providers don't expose one and identical inputs produce
 * identical (or perceptually identical) audio, so the hash is naturally stable.
 *
 * Consistency bindings: not folded for v1. The OpenAI endpoint takes a fixed
 * voice id with no notion of character-conditioned cloning. When a provider
 * with voice cloning lands (ElevenLabs, future OpenAI), `character_ref` will
 * grow a `voiceId` field and this tool will start consuming it the same way
 * `generate_image` consumes visual descriptions. The plumbing is the same;
 * the prompt-fold call is just unnecessary today.
 *
 * Permission: `"aigc.generate"` — same bucket as image gen because both incur
 * external cost + are seed-fragile in spirit (cache invariants, audit trail).
 */
class SynthesizeSpeechTool(
    private val engine: TtsEngine,
    private val storage: MediaStorage,
    private val blobWriter: MediaBlobWriter,
    private val projectStore: ProjectStore? = null,
) : Tool<SynthesizeSpeechTool.Input, SynthesizeSpeechTool.Output> {

    @Serializable
    data class Input(
        val text: String,
        val voice: String = "alloy",
        val model: String = "tts-1",
        val format: String = "mp3",
        val speed: Double = 1.0,
        val projectId: String? = null,
    )

    @Serializable
    data class Output(
        val assetId: String,
        val format: String,
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val voice: String,
        val parameters: JsonObject,
        /** True when this asset came from [io.talevia.core.domain.Project.lockfile] rather than a fresh engine call. */
        val cacheHit: Boolean = false,
    )

    override val id: String = "synthesize_speech"
    override val helpText: String =
        "Synthesize speech from text via a TTS provider (default: tts-1 / alloy / mp3) and " +
            "import it as a project asset. Pass projectId to enable lockfile caching — a second " +
            "call with identical (text, voice, model, format, speed) returns the same asset " +
            "without re-billing the provider. Use add_clip to drop the result onto an audio track."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Script to speak. OpenAI caps single calls at ~4096 characters.")
            }
            putJsonObject("voice") {
                put("type", "string")
                put("description", "Provider-scoped voice id (OpenAI: alloy, echo, fable, onyx, nova, shimmer). Default: alloy.")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped model id (OpenAI: tts-1 fast, tts-1-hd higher quality). Default: tts-1.")
            }
            putJsonObject("format") {
                put("type", "string")
                put("description", "Audio container: mp3 (default), opus, aac, flac, wav, pcm.")
            }
            putJsonObject("speed") {
                put("type", "number")
                put("description", "Playback speed multiplier (1.0 = normal). OpenAI accepts 0.25–4.0.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Required to consult the project lockfile. Without it every call hits the provider.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("text"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val inputHash = AigcPipeline.inputHash(
            listOf(
                "tool" to id,
                "model" to input.model,
                "voice" to input.voice,
                "format" to input.format,
                "speed" to input.speed.toString(),
                "text" to input.text,
            ),
        )

        val pid = input.projectId?.let(::ProjectId)
        val store = projectStore
        if (pid != null && store != null) {
            val cached = AigcPipeline.findCached(store, pid, inputHash)
            if (cached != null) {
                return hit(cached, input)
            }
        }

        val result = engine.synthesize(
            TtsRequest(
                text = input.text,
                modelId = input.model,
                voice = input.voice,
                format = input.format,
                speed = input.speed,
            ),
        )

        val source = blobWriter.writeBlob(result.audio.audioBytes, result.audio.format)
        val asset = storage.import(source) { _ ->
            // Duration / sample rate would need an audio probe to fill in honestly. The TTS
            // endpoint doesn't echo a duration and we have no portable audio probe in
            // commonMain — leaving it Duration.ZERO is the same compromise the image
            // engine makes for non-image dimensions.
            MediaMetadata(
                duration = Duration.ZERO,
                resolution = Resolution(0, 0),
                frameRate = null,
            )
        }

        if (pid != null && store != null) {
            AigcPipeline.record(
                store = store,
                projectId = pid,
                toolId = id,
                inputHash = inputHash,
                assetId = asset.id,
                provenance = result.provenance,
                sourceBinding = emptySet<SourceNodeId>(),
            )
        }

        val prov = result.provenance
        val out = Output(
            assetId = asset.id.value,
            format = result.audio.format,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            voice = input.voice,
            parameters = prov.parameters,
            cacheHit = false,
        )
        return ToolResult(
            title = "synthesize speech",
            outputForLlm = "Synthesized ${input.text.length}-char ${result.audio.format} audio (asset ${out.assetId}) " +
                "via ${prov.providerId}/${prov.modelId} voice=${input.voice}",
            data = out,
        )
    }

    private fun hit(entry: LockfileEntry, input: Input): ToolResult<Output> {
        val prov = entry.provenance
        val out = Output(
            assetId = entry.assetId.value,
            format = input.format,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            voice = input.voice,
            parameters = prov.parameters,
            cacheHit = true,
        )
        return ToolResult(
            title = "synthesize speech (cached)",
            outputForLlm = "Reused cached audio ${out.assetId} (lockfile hit; voice=${input.voice}, model=${prov.modelId})",
            data = out,
        )
    }
}
