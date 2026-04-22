package io.talevia.core.tool.builtin.project.query

import io.talevia.core.ClipId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=clip` — single-row drill-down replacing the deleted
 * `describe_clip` tool. Returns the rich Clip descriptor the old
 * tool produced (timeRange + sourceRange + transforms + per-kind
 * fields + a derived lockfile ref showing pin state + staleness).
 *
 * Consolidated under `project_query` per the
 * `debt-consolidate-project-describe-queries` backlog bullet, mirroring
 * the pattern `2026-04-21-debt-consolidate-session-describe-queries.md`
 * established for session_query.
 */
internal fun runClipDetailQuery(
    project: Project,
    input: ProjectQueryTool.Input,
): ToolResult<ProjectQueryTool.Output> {
    val clipIdValue = input.clipId
        ?: error(
            "select='${ProjectQueryTool.SELECT_CLIP}' requires clipId. Call " +
                "project_query(select=timeline_clips) to discover valid clip ids.",
        )
    val cid = ClipId(clipIdValue)
    val (track, clip) = findClip(project.timeline.tracks, cid)
        ?: error(
            "Clip ${cid.value} not found in project ${project.id.value}. Call " +
                "project_query(select=timeline_clips) to discover valid clip ids.",
        )

    val tr = ProjectQueryTool.ClipDetailTimeRange(
        startMs = clip.timeRange.start.inWholeMilliseconds,
        durationMs = clip.timeRange.duration.inWholeMilliseconds,
        endMs = clip.timeRange.end.inWholeMilliseconds,
    )
    val sr = clip.sourceRange?.let {
        ProjectQueryTool.ClipDetailTimeRange(
            startMs = it.start.inWholeMilliseconds,
            durationMs = it.duration.inWholeMilliseconds,
            endMs = it.end.inWholeMilliseconds,
        )
    }

    val assetIdValue = when (clip) {
        is Clip.Video -> clip.assetId
        is Clip.Audio -> clip.assetId
        is Clip.Text -> null
    }
    val lockfileRef = assetIdValue?.let { aid ->
        val entry = project.lockfile.findByAssetId(aid) ?: return@let null
        val currentHashesById = project.source.nodes.associate { it.id.value to it.contentHash }
        val drifted = entry.sourceContentHashes.filter { (nodeId, snap) ->
            val current = currentHashesById[nodeId.value]
            current == null || current != snap
        }.map { it.key.value }.sorted()
        ProjectQueryTool.ClipDetailLockfileRef(
            inputHash = entry.inputHash,
            toolId = entry.toolId,
            pinned = entry.pinned,
            currentlyStale = drifted.isNotEmpty(),
            driftedSourceNodeIds = drifted,
        )
    }

    val kind = when (clip) {
        is Clip.Video -> "video"
        is Clip.Audio -> "audio"
        is Clip.Text -> "text"
    }
    val row = ProjectQueryTool.ClipDetailRow(
        clipId = cid.value,
        trackId = track.id.value,
        clipType = kind,
        timeRange = tr,
        sourceRange = sr,
        sourceBindingIds = clip.sourceBinding.map { it.value }.sorted(),
        transforms = clip.transforms,
        assetId = assetIdValue?.value,
        filters = (clip as? Clip.Video)?.filters,
        volume = (clip as? Clip.Audio)?.volume,
        fadeInSeconds = (clip as? Clip.Audio)?.fadeInSeconds,
        fadeOutSeconds = (clip as? Clip.Audio)?.fadeOutSeconds,
        text = (clip as? Clip.Text)?.text,
        textStyle = (clip as? Clip.Text)?.style,
        lockfile = lockfileRef,
    )
    val rows = encodeRows(
        ListSerializer(ProjectQueryTool.ClipDetailRow.serializer()),
        listOf(row),
    )

    val staleNote = when {
        lockfileRef == null -> ""
        lockfileRef.currentlyStale -> " — stale"
        else -> " — fresh"
    }
    val pinNote = if (lockfileRef?.pinned == true) " — pinned" else ""
    val summary = "$kind clip ${cid.value} on track ${track.id.value} " +
        "(${tr.durationMs / 1000.0}s)$staleNote$pinNote."
    return ToolResult(
        title = "project_query clip ${cid.value}",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_CLIP,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}

private fun findClip(tracks: List<Track>, clipId: ClipId): Pair<Track, Clip>? {
    for (track in tracks) {
        val clip = track.clips.firstOrNull { it.id == clipId } ?: continue
        return track to clip
    }
    return null
}
