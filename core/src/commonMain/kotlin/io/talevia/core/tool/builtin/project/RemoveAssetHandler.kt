package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `project_lifecycle_action(action="remove_asset")` handler — drop a
 * [io.talevia.core.domain.MediaAsset] from
 * [io.talevia.core.domain.Project.assets]. Behaviour preserved from
 * the legacy `RemoveAssetTool`:
 *
 * - Refuses by default when any clip still references the asset; the
 *   error lists dependent clip ids so the agent can prune them first.
 * - With `force=true`, removes anyway leaving dangling clips for
 *   `project_query(select=validation)` to surface (Unix `rm -f` semantics).
 * - **Does NOT touch asset bytes** (cross-project sharing is real;
 *   byte-level GC is a separate concern).
 * - **Does NOT cascade to dependent clips** — keeping the surface
 *   small means the agent composes `clip_action(action=remove)` +
 *   this verb explicitly.
 */
internal suspend fun executeRemoveAsset(
    projects: ProjectStore,
    input: ProjectLifecycleActionTool.Input,
    @Suppress("UNUSED_PARAMETER") ctx: ToolContext,
): ToolResult<ProjectLifecycleActionTool.Output> {
    val rawProjectId = input.projectId
        ?: error("action=remove_asset requires `projectId`")
    val rawAssetId = input.assetId
        ?: error("action=remove_asset requires `assetId`")
    val pid = ProjectId(rawProjectId)
    val targetId = AssetId(rawAssetId)

    val project = projects.get(pid) ?: error("project $rawProjectId not found")
    if (project.assets.none { it.id == targetId }) {
        error("asset $rawAssetId not found in project $rawProjectId")
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
            "asset $rawAssetId is in use by ${dependentClips.size} clip(s): " +
                "${dependentClips.joinToString(", ")}. " +
                "Remove those clips first, or pass force=true to remove anyway " +
                "(will leave dangling clips).",
        )
    }

    projects.mutate(pid) { p ->
        p.copy(assets = p.assets.filter { it.id != targetId })
    }

    val data = ProjectLifecycleActionTool.Output(
        projectId = pid.value,
        action = "remove_asset",
        removeAssetResult = ProjectLifecycleActionTool.RemoveAssetResult(
            assetId = targetId.value,
            removed = true,
            dependentClips = dependentClips,
        ),
    )
    val summary = if (dependentClips.isEmpty()) {
        "Removed asset ${targetId.value} from project ${pid.value}. No clips referenced it."
    } else {
        "Removed asset ${targetId.value} from project ${pid.value}. " +
            "${dependentClips.size} clip(s) now dangle: ${dependentClips.joinToString(", ")}. " +
            "Run project_query(select=validation) to see the fallout."
    }
    return ToolResult(
        title = "remove asset ${targetId.value}",
        outputForLlm = summary,
        data = data,
    )
}
