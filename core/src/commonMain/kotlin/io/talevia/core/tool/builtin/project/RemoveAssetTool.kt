package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
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

/**
 * Safe companion to [ListAssetsTool] — drops a [io.talevia.core.domain.MediaAsset]
 * from [io.talevia.core.domain.Project.assets]. The intended flow is:
 *
 *   list_assets(onlyUnused=true) → remove_asset(assetId)
 *
 * By default the tool refuses when the asset is still referenced by any clip
 * — the error message lists the dependent clipIds so the agent can either
 * prune the clips first or pass `force=true`. With `force=true` the asset is
 * removed anyway, leaving dangling clips that [ValidateProjectTool] will
 * surface as errors on next validation pass (Unix `rm -f` semantics).
 *
 * **Does NOT touch MediaStorage bytes.** The same AssetId can be shared across
 * projects, snapshots, and lockfile entries; byte-level GC is a separate
 * concern (future `compact_media_storage` job). This tool only mutates the
 * project-scoped asset catalog.
 *
 * **Does NOT auto-remove dependent clips.** Cascade-delete is out of scope:
 * keeping the surface small means the agent composes `remove_clip` +
 * `remove_asset` explicitly when that's the intent, making the destructive
 * step auditable rather than hidden inside a cascade.
 */
class RemoveAssetTool(
    private val projects: ProjectStore,
) : Tool<RemoveAssetTool.Input, RemoveAssetTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val assetId: String,
        /** When true, remove even if clips still reference the asset. */
        val force: Boolean = false,
    )

    @Serializable data class Output(
        val projectId: String,
        val assetId: String,
        val removed: Boolean,
        /** Clip ids that still reference the asset (non-empty only when `force=true` left dangling clips, or in the error path). */
        val dependentClips: List<String>,
    )

    override val id: String = "remove_asset"
    override val helpText: String =
        "Remove a media asset from a project's asset catalog. Refuses by default if any clip " +
            "references the asset; pass force=true to remove anyway (leaves dangling clips that " +
            "validate_project will flag). Does not delete asset bytes from shared MediaStorage. " +
            "Use list_assets(onlyUnused=true) to find safe candidates first."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("assetId") { put("type", "string") }
            putJsonObject("force") {
                put("type", "boolean")
                put("description", "Remove even if clips still reference the asset. Default false.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("assetId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val targetId = AssetId(input.assetId)

        val project = projects.get(pid) ?: error("project ${input.projectId} not found")
        if (project.assets.none { it.id == targetId }) {
            error("asset ${input.assetId} not found in project ${input.projectId}")
        }

        val dependentClips = project.timeline.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .mapNotNull { clip ->
                val clipAsset = when (clip) {
                    is Clip.Video -> clip.assetId
                    is Clip.Audio -> clip.assetId
                    is Clip.Text -> null
                }
                if (clipAsset == targetId) clip.id.value else null
            }
            .toList()

        if (dependentClips.isNotEmpty() && !input.force) {
            error(
                "asset ${input.assetId} is in use by ${dependentClips.size} clip(s): " +
                    "${dependentClips.joinToString(", ")}. " +
                    "Remove those clips first, or pass force=true to remove anyway (will leave dangling clips).",
            )
        }

        projects.mutate(pid) { p ->
            p.copy(assets = p.assets.filter { it.id != targetId })
        }

        val out = Output(
            projectId = pid.value,
            assetId = targetId.value,
            removed = true,
            dependentClips = dependentClips,
        )
        val summary = if (dependentClips.isEmpty()) {
            "Removed asset ${targetId.value} from project ${pid.value}. No clips referenced it."
        } else {
            "Removed asset ${targetId.value} from project ${pid.value}. " +
                "${dependentClips.size} clip(s) now dangle: ${dependentClips.joinToString(", ")}. " +
                "Run validate_project to see the fallout."
        }
        return ToolResult(
            title = "remove asset ${targetId.value}",
            outputForLlm = summary,
            data = out,
        )
    }
}
