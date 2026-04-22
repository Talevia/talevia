package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.cost.AigcPricing
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.UpscaleEngine
import io.talevia.core.platform.UpscaleRequest
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
import kotlin.time.Duration

/**
 * Upscale / super-resolve an imported image asset via an [UpscaleEngine],
 * persist the bytes through a [MediaBlobWriter], register the result with
 * [MediaStorage], and surface a new `AssetId` that downstream tools can use.
 * Closes the VISION §2 "ML 加工: … 超分 …" lane.
 *
 * Placed under `tool/builtin/aigc/` (not `ml/`) because its engine output is
 * bytes rather than text — it shares the seed / lockfile / provenance
 * disciplines with [GenerateImageTool] et al. (`ml/` is the "analysis →
 * derived text" lane: `describe_asset`, `transcribe_asset`). The folder split
 * is operational; the semantic line is "does it emit bytes?". If we add
 * denoise / inpaint / style-transfer later they belong here too.
 *
 * Seed + provenance (VISION §3.1). Delegates to [AigcPipeline] so this tool
 * uses the same "mint a seed if missing, record the full generation
 * parameters" policy as image / video / music gen. Diffusion-based SR models
 * are seed-sensitive; seed-ignoring ESRGAN-style models still get the seed
 * in provenance so the lockfile hash stays meaningful.
 *
 * Lockfile cache (VISION §3.1). Hash over
 * `(tool, sourceAssetId, model, scale, seed, format)`. A second call with
 * identical inputs returns the cached upscaled asset without re-invoking the
 * provider. Source binding is empty — this tool doesn't fold character /
 * style consistency (upscaling is a pixel-fidelity operation, not a creative
 * one), so stale-propagation operates on the *source asset* identity alone
 * via the assetId in the hash. If a user regenerates the source asset, the
 * assetId changes and the upscale cache busts naturally.
 *
 * Permission: `"aigc.generate"` — same bucket as image / video / TTS / music
 * because it incurs external cost + produces a new artifact.
 */
class UpscaleAssetTool(
    private val engine: UpscaleEngine,
    private val storage: MediaStorage,
    private val resolver: MediaPathResolver,
    private val blobWriter: MediaBlobWriter,
    private val projectStore: ProjectStore? = null,
) : Tool<UpscaleAssetTool.Input, UpscaleAssetTool.Output> {

    @Serializable data class Input(
        val assetId: String,
        val scale: Int = 2,
        val model: String = "real-esrgan-4x",
        val seed: Long? = null,
        val format: String = "png",
        val projectId: String? = null,
    )

    @Serializable data class Output(
        val sourceAssetId: String,
        val upscaledAssetId: String,
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val seed: Long,
        val scale: Int,
        val width: Int,
        val height: Int,
        val format: String,
        val parameters: JsonObject,
        /** True when this asset came from [io.talevia.core.domain.Project.lockfile] rather than a fresh engine call. */
        val cacheHit: Boolean = false,
    )

    override val id: String = "upscale_asset"
    override val helpText: String =
        "Upscale / super-resolve an imported image asset via an ML provider (default model: " +
            "real-esrgan-4x, default scale: 2). Returns a new assetId you can substitute via " +
            "replace_clip or drop onto the timeline via add_clip. Pass projectId to cache the " +
            "result in the project lockfile — a second call with identical (assetId, model, " +
            "scale, seed, format) returns the same upscaled asset without re-billing the provider."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("assetId") {
                put("type", "string")
                put("description", "Source asset id to upscale. Must resolve to an image the provider accepts.")
            }
            putJsonObject("scale") {
                put("type", "integer")
                put("description", "Upscale factor. 2, 3, or 4 in practice; engines clamp to what the chosen model supports.")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped model id (default: real-esrgan-4x).")
            }
            putJsonObject("seed") {
                put("type", "integer")
                put("description", "Optional seed for stochastic SR models. If omitted the tool picks one client-side so provenance is still complete. Explicit seeds make cache hits meaningful.")
            }
            putJsonObject("format") {
                put("type", "string")
                put("description", "Output container: png (default, lossless) or jpg.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Required when the project lockfile cache should be consulted.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("assetId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.scale in 2..8) { "scale must be between 2 and 8; got ${input.scale}" }
        val seed = AigcPipeline.ensureSeed(input.seed)
        val sourceAssetId = AssetId(input.assetId)
        val sourcePath = resolver.resolve(sourceAssetId)

        val inputHash = AigcPipeline.inputHash(
            listOf(
                "tool" to id,
                "asset" to input.assetId,
                "model" to input.model,
                "scale" to input.scale.toString(),
                "seed" to seed.toString(),
                "format" to input.format,
            ),
        )

        val pid = input.projectId?.let(::ProjectId)
        val store = projectStore
        if (pid != null && store != null && !ctx.isReplay) {
            val cached = AigcPipeline.findCached(store, pid, inputHash)
            if (cached != null) {
                return hit(cached, input, seed)
            }
        }

        val result = AigcPipeline.withProgress(
            ctx = ctx,
            jobId = "upscale-${inputHash.take(8)}",
            startMessage = "upscaling asset ${input.assetId} x${input.scale} with ${input.model}",
        ) {
            engine.upscale(
                UpscaleRequest(
                    imagePath = sourcePath,
                    modelId = input.model,
                    scale = input.scale,
                    seed = seed,
                    format = input.format,
                ),
            )
        }
        val image = result.image

        val source = blobWriter.writeBlob(image.imageBytes, image.format)
        val asset = storage.import(source) { _ ->
            MediaMetadata(
                duration = Duration.ZERO,
                resolution = Resolution(image.width, image.height),
                frameRate = null,
            )
        }

        val baseInputs = JsonConfig.default.encodeToJsonElement(Input.serializer(), input).jsonObject
        val costCents = AigcPricing.estimateCents(id, result.provenance, baseInputs)
        if (pid != null && store != null) {
            AigcPipeline.record(
                store = store,
                projectId = pid,
                toolId = id,
                inputHash = inputHash,
                assetId = asset.id,
                provenance = result.provenance,
                sourceBinding = emptySet(),
                baseInputs = baseInputs,
                costCents = costCents,
                sessionId = ctx.sessionId,
            )
            ctx.publishEvent(
                BusEvent.AigcCostRecorded(
                    sessionId = ctx.sessionId,
                    projectId = pid,
                    toolId = id,
                    assetId = asset.id.value,
                    costCents = costCents,
                ),
            )
        }

        val prov = result.provenance
        val out = Output(
            sourceAssetId = input.assetId,
            upscaledAssetId = asset.id.value,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            scale = input.scale,
            width = image.width,
            height = image.height,
            format = image.format,
            parameters = prov.parameters,
            cacheHit = false,
        )
        return ToolResult(
            title = "upscale asset x${input.scale}",
            outputForLlm = "Upscaled ${input.assetId} → ${out.upscaledAssetId} (${image.width}x${image.height} " +
                "${image.format}) via ${prov.providerId}/${prov.modelId} seed=${prov.seed}",
            data = out,
        )
    }

    private fun hit(entry: LockfileEntry, input: Input, seed: Long): ToolResult<Output> {
        val prov = entry.provenance
        val out = Output(
            sourceAssetId = input.assetId,
            upscaledAssetId = entry.assetId.value,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            scale = input.scale,
            width = 0,
            height = 0,
            format = input.format,
            parameters = prov.parameters,
            cacheHit = true,
        )
        return ToolResult(
            title = "upscale asset (cached) x${input.scale}",
            outputForLlm = "Reused cached upscaled asset ${out.upscaledAssetId} (lockfile hit; seed=$seed, model=${prov.modelId})",
            data = out,
        )
    }
}
