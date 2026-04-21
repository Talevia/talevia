package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=clips_for_asset` — every clip on the timeline that references
 * a given asset id. Replaces the pre-consolidation
 * `list_clips_bound_to_asset` tool. Requires
 * [ProjectQueryTool.Input.assetId] — unknown asset id throws so typos
 * surface instead of silently matching nothing.
 */
internal fun runClipsForAssetQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val assetIdString = input.assetId
        ?: error("select='clips_for_asset' requires the 'assetId' filter field.")
    val aid = AssetId(assetIdString)
    if (project.assets.none { it.id == aid }) {
        throw IllegalArgumentException(
            "Asset $assetIdString not found in project ${project.id.value} — " +
                "call project_query(select=assets) to discover valid ids.",
        )
    }
    val matches = mutableListOf<ProjectQueryTool.ClipForAssetRow>()
    for (track in project.timeline.tracks) {
        for (clip in track.clips.sortedBy { it.timeRange.start }) {
            val (clipAsset, kind) = when (clip) {
                is Clip.Video -> clip.assetId to "video"
                is Clip.Audio -> clip.assetId to "audio"
                is Clip.Text -> continue
            }
            if (clipAsset != aid) continue
            matches += ProjectQueryTool.ClipForAssetRow(
                clipId = clip.id.value,
                trackId = track.id.value,
                kind = kind,
                startSeconds = clip.timeRange.start.toSecondsDouble(),
                durationSeconds = clip.timeRange.duration.toSecondsDouble(),
            )
        }
    }
    val page = matches.drop(offset).take(limit)
    val jsonRows = encodeRows(ListSerializer(ProjectQueryTool.ClipForAssetRow.serializer()), page)

    val body = if (matches.isEmpty()) {
        "No clips reference asset $assetIdString (unreferenced — safe to delete)."
    } else {
        val head = page.take(5).joinToString("; ") {
            "${it.clipId} (${it.kind}/${it.trackId} @ ${it.startSeconds}s)"
        }
        val tail = if (page.size > 5) "; …" else ""
        "${matches.size} clip(s) reference asset $assetIdString: $head$tail"
    }
    return ToolResult(
        title = "project_query clips_for_asset (${page.size}/${matches.size})",
        outputForLlm = body,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_CLIPS_FOR_ASSET,
            total = matches.size,
            returned = page.size,
            rows = jsonRows,
        ),
    )
}
