package io.talevia.core.tool.builtin.aigc

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.consistency.FoldedPrompt
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.MusicGenEngine
import io.talevia.core.platform.MusicGenRequest
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.Duration.Companion.seconds

/**
 * Generate a music track via a [MusicGenEngine], persist the bytes through a
 * [MediaBlobWriter], register the result with [MediaStorage], and surface an
 * `AssetId` that `add_clip` can drop onto an audio track. Closes the AIGC
 * music lane from VISION §2 ("AIGC: 音乐生成") — the last of the four
 * generative modalities the VISION calls out (image / video / TTS / music).
 *
 * Seed + provenance handling (VISION §3.1): delegates to [AigcPipeline] so
 * this tool and every future AIGC tool share one implementation of "mint a
 * seed if missing, record the full generation parameters." Music providers
 * are often seed-lossy (the same seed doesn't guarantee an identical waveform)
 * but recording the caller's intent still lets the lockfile cache key stay
 * meaningful, and future providers that *do* honour seeds will see them.
 *
 * Lockfile cache (VISION §3.1 "产物可 pin"): hash over `(tool, model, seed,
 * duration, format, effective prompt, bindings)`. A style_bible or brand_palette
 * bound via `consistencyBindingIds` is folded into the prompt the same way as
 * image gen — so the same "冷色调、钢琴、慢节奏" style bible produces cache-
 * coherent music across calls. character_ref bindings with voiceIds do NOT
 * apply here (music gen doesn't use speaking voice selection) — the tool
 * silently ignores character_ref voice, matching the VISION §5.5 model where
 * style/brand is the cross-modal knob, voice is the speaking-only knob.
 *
 * Permission: `"aigc.generate"` — same bucket as image / video / TTS because
 * all four incur external cost + need lockfile coherence.
 */
class GenerateMusicTool(
    private val engine: MusicGenEngine,
    private val storage: MediaStorage,
    private val blobWriter: MediaBlobWriter,
    private val projectStore: ProjectStore? = null,
) : Tool<GenerateMusicTool.Input, GenerateMusicTool.Output> {

    @Serializable
    data class Input(
        val prompt: String,
        val model: String = "musicgen-melody",
        /** Target duration in seconds. Engines may clamp to provider-specific maxima. */
        val durationSeconds: Double = 15.0,
        val format: String = "mp3",
        val seed: Long? = null,
        val projectId: String? = null,
        val consistencyBindingIds: List<String> = emptyList(),
    )

    @Serializable
    data class Output(
        val assetId: String,
        val durationSeconds: Double,
        val format: String,
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val seed: Long,
        val parameters: JsonObject,
        val effectivePrompt: String,
        val appliedConsistencyBindingIds: List<String>,
        /** True when this asset came from [io.talevia.core.domain.Project.lockfile] rather than a fresh engine call. */
        val cacheHit: Boolean = false,
    )

    override val id: String = "generate_music"
    override val helpText: String =
        "Generate a music track from a text prompt via an AIGC provider and import it as a project asset. " +
            "Records seed + model in the project lockfile so a second call with identical inputs is a cache hit. " +
            "Pass consistencyBindingIds with style_bible / brand_palette node ids to keep music coherent with " +
            "visual style across shots. Drop the returned assetId onto an audio track via add_clip."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "Text description of the music (mood, genre, instruments, tempo).")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped model id (default: musicgen-melody).")
            }
            putJsonObject("durationSeconds") {
                put("type", "number")
                put("description", "Target duration in seconds. Default 15. Providers clamp to supported maxima (MusicGen ~30s, Suno ~240s).")
            }
            putJsonObject("format") {
                put("type", "string")
                put("description", "Audio container: mp3 (default), wav, ogg, flac.")
            }
            putJsonObject("seed") {
                put("type", "integer")
                put("description", "Optional seed for reproducibility. If omitted the tool picks one client-side so provenance is still complete. Explicit seeds make cache hits meaningful.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Required when consistencyBindingIds is non-empty or when the project lockfile cache should be consulted.")
            }
            putJsonObject("consistencyBindingIds") {
                put("type", "array")
                put("description", "Source node ids (kind core.consistency.*) to fold into the prompt — typically style_bibles or brand_palettes. character_ref voiceIds are ignored by music gen.")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val seed = AigcPipeline.ensureSeed(input.seed)
        val folded = resolveConsistency(input)

        val inputHash = AigcPipeline.inputHash(
            listOf(
                "tool" to id,
                "model" to input.model,
                "dur" to input.durationSeconds.toString(),
                "fmt" to input.format,
                "seed" to seed.toString(),
                "prompt" to folded.effectivePrompt,
                "bindings" to folded.appliedNodeIds.joinToString(","),
                "neg" to (folded.negativePrompt ?: ""),
            ),
        )

        val pid = input.projectId?.let(::ProjectId)
        val store = projectStore
        if (pid != null && store != null) {
            val cached = AigcPipeline.findCached(store, pid, inputHash)
            if (cached != null) {
                return hit(cached, folded, input)
            }
        }

        val result = engine.generate(
            MusicGenRequest(
                prompt = folded.effectivePrompt,
                modelId = input.model,
                seed = seed,
                durationSeconds = input.durationSeconds,
                format = input.format,
            ),
        )
        val music = result.music

        val source = blobWriter.writeBlob(music.audioBytes, music.format)
        val asset = storage.import(source) { _ ->
            MediaMetadata(
                duration = music.durationSeconds.seconds,
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
                sourceBinding = folded.appliedNodeIds.map { SourceNodeId(it) }.toSet(),
                baseInputs = JsonConfig.default.encodeToJsonElement(Input.serializer(), input).jsonObject,
            )
        }

        val prov = result.provenance
        val out = Output(
            assetId = asset.id.value,
            durationSeconds = music.durationSeconds,
            format = music.format,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            parameters = prov.parameters,
            effectivePrompt = folded.effectivePrompt,
            appliedConsistencyBindingIds = folded.appliedNodeIds,
            cacheHit = false,
        )
        val bindingTail = if (folded.appliedNodeIds.isEmpty()) ""
        else " [bindings: ${folded.appliedNodeIds.joinToString(", ")}]"
        return ToolResult(
            title = "generate music",
            outputForLlm = "Generated ${music.durationSeconds}s ${music.format} music " +
                "(asset ${out.assetId}) via ${prov.providerId}/${prov.modelId} seed=${prov.seed}$bindingTail",
            data = out,
        )
    }

    private fun hit(entry: LockfileEntry, folded: FoldedPrompt, input: Input): ToolResult<Output> {
        val prov = entry.provenance
        val out = Output(
            assetId = entry.assetId.value,
            durationSeconds = input.durationSeconds,
            format = input.format,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            parameters = prov.parameters,
            effectivePrompt = folded.effectivePrompt,
            appliedConsistencyBindingIds = folded.appliedNodeIds,
            cacheHit = true,
        )
        return ToolResult(
            title = "generate music (cached)",
            outputForLlm = "Reused cached music ${out.assetId} (lockfile hit; seed=${prov.seed}, model=${prov.modelId})",
            data = out,
        )
    }

    private suspend fun resolveConsistency(input: Input): FoldedPrompt {
        if (input.consistencyBindingIds.isEmpty()) {
            return io.talevia.core.domain.source.consistency
                .foldConsistencyIntoPrompt(input.prompt, emptyList())
        }
        val store = projectStore
            ?: error("consistencyBindingIds supplied but this GenerateMusicTool has no ProjectStore wired")
        val pid = input.projectId
            ?: error("consistencyBindingIds require projectId to locate the source graph")
        val project = store.get(ProjectId(pid))
            ?: error("Project $pid not found when resolving consistency bindings")
        return AigcPipeline.foldPrompt(
            project = project,
            basePrompt = input.prompt,
            bindingIds = input.consistencyBindingIds.map { SourceNodeId(it) },
        )
    }
}
