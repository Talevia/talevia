package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration.Companion.seconds

@Serializable data class ClipRow(
    val clipId: String,
    val trackId: String,
    val trackKind: String,
    val clipKind: String,
    val startSeconds: Double,
    val durationSeconds: Double,
    val endSeconds: Double,
    val assetId: String? = null,
    val sourceStartSeconds: Double? = null,
    val sourceDurationSeconds: Double? = null,
    val filterCount: Int = 0,
    val volume: Float? = null,
    val fadeInSeconds: Float? = null,
    val fadeOutSeconds: Float? = null,
    val textPreview: String? = null,
    val sourceBindingNodeIds: List<String> = emptyList(),
    /** Stamped by [io.talevia.core.domain.FileProjectStore]; null on pre-recency blobs. */
    val updatedAtEpochMs: Long? = null,
)

/**
 * `select=timeline_clips` — one row per clip on the timeline with track,
 * kind, time range, asset binding, audio envelope, source-binding ids.
 */
internal fun runTimelineClipsQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val kindFilter = input.trackKind?.trim()?.lowercase()
    if (kindFilter != null && kindFilter !in VALID_TRACK_KINDS) {
        error("trackKind must be one of ${VALID_TRACK_KINDS.joinToString(", ")} (got '${input.trackKind}')")
    }
    val sortBy = input.sortBy?.trim()?.lowercase()
    if (sortBy != null && sortBy !in CLIP_SORTS) {
        error(
            "sortBy for select=timeline_clips must be one of ${CLIP_SORTS.joinToString(", ")} " +
                "(got '${input.sortBy}')",
        )
    }
    val fromDuration = input.fromSeconds?.coerceAtLeast(0.0)?.seconds
    val toDuration = input.toSeconds?.coerceAtLeast(0.0)?.seconds

    val filtered = mutableListOf<ClipRow>()
    for (track in project.timeline.tracks) {
        val trackKind = trackKindOf(track)
        if (kindFilter != null && trackKind != kindFilter) continue
        if (input.trackId != null && track.id.value != input.trackId) continue
        for (clip in track.clips.sortedBy { it.timeRange.start }) {
            if (fromDuration != null && clip.timeRange.end < fromDuration) continue
            if (toDuration != null && clip.timeRange.start > toDuration) continue
            if (input.onlySourceBound == true && clip.sourceBinding.isEmpty()) continue
            if (input.onlyPinned != null && !matchesPinned(clip, project, input.onlyPinned)) continue
            filtered += buildClipRow(clip, track, trackKind)
        }
    }
    val sorted = when (sortBy) {
        null, "startseconds" -> filtered
        "durationseconds" -> filtered.sortedByDescending { it.durationSeconds }
        "recent" -> filtered.sortedWith(recentComparator({ it.updatedAtEpochMs }, { it.clipId }))
        else -> error("unreachable")
    }

    val page = sorted.drop(offset).take(limit)
    val rows = encodeRows(ListSerializer(ClipRow.serializer()), page)
    val body = if (page.isEmpty()) {
        "No clips match the given filters."
    } else {
        page.joinToString("\n") { c ->
            val extra = when (c.clipKind) {
                "video" -> c.assetId?.let { " asset=$it" }.orEmpty() +
                    if (c.filterCount > 0) " filters=${c.filterCount}" else ""
                "audio" -> c.assetId?.let { " asset=$it" }.orEmpty() +
                    (c.volume?.let { " vol=$it" }.orEmpty())
                "text" -> c.textPreview?.let {
                    " text=\"${it.take(40)}${if (it.length > 40) "…" else ""}\""
                }.orEmpty()
                else -> ""
            }
            val binding = if (c.sourceBindingNodeIds.isEmpty()) {
                ""
            } else {
                " bindings=${c.sourceBindingNodeIds.joinToString(",")}"
            }
            "- [${c.trackKind}/${c.trackId}] ${c.clipId} @ ${c.startSeconds}s +${c.durationSeconds}s$extra$binding"
        }
    }
    val hiddenByPage = filtered.size - page.size
    val tail = if (hiddenByPage > 0) "\n… ($hiddenByPage more not shown)" else ""
    return ToolResult(
        title = "project_query timeline_clips (${page.size}/${filtered.size})",
        outputForLlm = body + tail,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_TIMELINE_CLIPS,
            total = filtered.size,
            returned = page.size,
            rows = rows,
        ),
    )
}

/**
 * Tri-state pin match — covers the semantics [ProjectQueryTool.Input.onlyPinned]
 * documents plus the `find_pinned_clips` legacy behavior. A clip counts as
 * "pinned" when its backing lockfile entry exists AND is pinned. Text clips
 * and clips whose asset has no lockfile row (imported media) are NOT pinned.
 */
private fun matchesPinned(clip: Clip, project: Project, onlyPinned: Boolean): Boolean {
    val assetId = when (clip) {
        is Clip.Video -> clip.assetId
        is Clip.Audio -> clip.assetId
        is Clip.Text -> null
    }
    val isPinned = if (assetId == null) false else project.lockfile.findByAssetId(assetId)?.pinned == true
    return isPinned == onlyPinned
}

private fun buildClipRow(clip: Clip, track: Track, trackKind: String): ClipRow {
    val start = clip.timeRange.start.toSecondsDouble()
    val dur = clip.timeRange.duration.toSecondsDouble()
    return when (clip) {
        is Clip.Video -> ClipRow(
            clipId = clip.id.value,
            trackId = track.id.value,
            trackKind = trackKind,
            clipKind = "video",
            startSeconds = start,
            durationSeconds = dur,
            endSeconds = start + dur,
            assetId = clip.assetId.value,
            sourceStartSeconds = clip.sourceRange.start.toSecondsDouble(),
            sourceDurationSeconds = clip.sourceRange.duration.toSecondsDouble(),
            filterCount = clip.filters.size,
            sourceBindingNodeIds = clip.sourceBinding.map { it.value }.sorted(),
            updatedAtEpochMs = clip.updatedAtEpochMs,
        )
        is Clip.Audio -> ClipRow(
            clipId = clip.id.value,
            trackId = track.id.value,
            trackKind = trackKind,
            clipKind = "audio",
            startSeconds = start,
            durationSeconds = dur,
            endSeconds = start + dur,
            assetId = clip.assetId.value,
            sourceStartSeconds = clip.sourceRange.start.toSecondsDouble(),
            sourceDurationSeconds = clip.sourceRange.duration.toSecondsDouble(),
            volume = clip.volume,
            fadeInSeconds = clip.fadeInSeconds,
            fadeOutSeconds = clip.fadeOutSeconds,
            sourceBindingNodeIds = clip.sourceBinding.map { it.value }.sorted(),
            updatedAtEpochMs = clip.updatedAtEpochMs,
        )
        is Clip.Text -> ClipRow(
            clipId = clip.id.value,
            trackId = track.id.value,
            trackKind = trackKind,
            clipKind = "text",
            startSeconds = start,
            durationSeconds = dur,
            endSeconds = start + dur,
            textPreview = clip.text.take(80),
            sourceBindingNodeIds = clip.sourceBinding.map { it.value }.sorted(),
            updatedAtEpochMs = clip.updatedAtEpochMs,
        )
    }
}
