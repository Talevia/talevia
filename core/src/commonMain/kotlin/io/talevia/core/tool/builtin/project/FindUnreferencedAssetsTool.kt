package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
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
import kotlin.time.DurationUnit

/**
 * Read-only counterpart to `prune_lockfile`: scans `Project.assets` for ids that
 * are NOT referenced anywhere — no clip, no lockfile entry, no LUT filter. Lets
 * the agent answer the professional workflow question "I imported 20 clips, used
 * 3, what can I safely delete?" without dumping the entire project JSON.
 *
 * The counterpart reverses `prune_lockfile`'s direction: that tool sweeps
 * lockfile entries whose asset has been removed from the catalog (stale
 * provenance); this one sweeps catalog entries that no part of the project
 * references (orphan media). Together they keep the asset / lockfile tables
 * mutually consistent.
 *
 * Reference lanes scanned:
 *  - `Clip.Video.assetId` / `Clip.Audio.assetId` on any track.
 *  - `project.lockfile.entries[*].assetId` — an asset keeps its "we generated
 *    this" provenance even after every clip using it is deleted; preserve the
 *    audit trail rather than treating lockfile-only assets as garbage.
 *  - `Clip.Video.filters[*].assetId` — LUT `.cube` files are loaded by the
 *    engine through `MediaPathResolver` but never sit on a clip as a primary
 *    asset; without this lane every LUT would look unreferenced.
 *
 * Read-only (`project.read`). Consumers compose this with an explicit
 * per-id `remove_asset` call — this tool never mutates.
 */
class FindUnreferencedAssetsTool(
    private val projects: ProjectStore,
) : Tool<FindUnreferencedAssetsTool.Input, FindUnreferencedAssetsTool.Output> {

    @Serializable data class Input(val projectId: String)

    @Serializable data class Summary(
        val assetId: String,
        val durationSeconds: Double? = null,
        val widthPx: Int? = null,
        val heightPx: Int? = null,
        /** "video" | "audio" | "image" — coarse classification from codec metadata. */
        val kind: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val totalAssets: Int,
        val referencedCount: Int,
        val unreferencedCount: Int,
        val unreferenced: List<Summary>,
    )

    override val id: String = "find_unreferenced_assets"
    override val helpText: String =
        "Report assets in the project catalog that are not referenced by any clip, lockfile entry, " +
            "or LUT filter. Read-only counterpart to prune_lockfile for the catalog direction. " +
            "Use to surface orphan imports for deletion via remove_asset."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("project ${input.projectId} not found")

        val referencedByClip: Set<AssetId> = buildSet {
            project.timeline.tracks.forEach { track ->
                track.clips.forEach { clip ->
                    when (clip) {
                        is Clip.Video -> add(clip.assetId)
                        is Clip.Audio -> add(clip.assetId)
                        is Clip.Text -> Unit
                    }
                }
            }
        }
        val referencedByFilter: Set<AssetId> = buildSet {
            project.timeline.tracks.forEach { track ->
                track.clips.forEach { clip ->
                    if (clip is Clip.Video) {
                        clip.filters.forEach { f -> f.assetId?.let { add(it) } }
                    }
                }
            }
        }
        val referencedByLockfile: Set<AssetId> =
            project.lockfile.entries.map { it.assetId }.toSet()

        val referenced = referencedByClip + referencedByFilter + referencedByLockfile
        val unreferenced = project.assets.filter { it.id !in referenced }

        val out = Output(
            projectId = pid.value,
            totalAssets = project.assets.size,
            referencedCount = project.assets.size - unreferenced.size,
            unreferencedCount = unreferenced.size,
            unreferenced = unreferenced.map { asset -> summarize(asset) },
        )
        val summary = if (unreferenced.isEmpty()) {
            "All ${project.assets.size} asset(s) referenced (by clip / lockfile / filter). Nothing to prune."
        } else {
            "${unreferenced.size} of ${project.assets.size} asset(s) unreferenced: " +
                unreferenced.take(5).joinToString(", ") { it.id.value } +
                if (unreferenced.size > 5) ", …" else ""
        }
        return ToolResult(
            title = "find unreferenced assets",
            outputForLlm = summary,
            data = out,
        )
    }

    private fun summarize(asset: MediaAsset): Summary {
        val meta = asset.metadata
        val hasV = meta.videoCodec != null
        val hasA = meta.audioCodec != null
        val kind = when {
            hasV -> "video"
            hasA -> "audio"
            else -> "image"
        }
        return Summary(
            assetId = asset.id.value,
            durationSeconds = meta.duration.toDouble(DurationUnit.SECONDS),
            widthPx = meta.resolution?.width,
            heightPx = meta.resolution?.height,
            kind = kind,
        )
    }
}
