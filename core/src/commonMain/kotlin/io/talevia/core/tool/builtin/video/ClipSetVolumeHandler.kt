package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `field="volume"` handler extracted from [ClipSetActionTool].
 *
 * The field axis the parent class splits along: each `field=*` branch
 * carries its own validation shape (volume is numeric + range-bounded,
 * transform is multi-field partial override, sourceBinding is a set
 * swap with source-DAG existence check) and its own result type. Per-
 * field files let each handler own its specific invariants without
 * crowding a single class past the R.5.4 500-LOC threshold.
 */

internal suspend fun executeClipSetVolume(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipSetActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipSetActionTool.Output> {
    val items = input.volumeItems ?: error("field=volume requires `volumeItems`")
    rejectForeignClipSetFields("volume", input)
    require(items.isNotEmpty()) { "volumeItems must not be empty" }
    items.forEachIndexed { idx, item ->
        require(item.volume.isFinite()) {
            "volumeItems[$idx]: volume must be finite (got ${item.volume})"
        }
        require(item.volume >= 0f) {
            "volumeItems[$idx]: volume must be >= 0 (got ${item.volume})"
        }
        require(item.volume <= MAX_VOLUME) {
            "volumeItems[$idx]: volume must be <= $MAX_VOLUME (got ${item.volume}); " +
                "clip-level gain beyond that belongs in mix-time staging."
        }
    }

    val results = mutableListOf<ClipSetActionTool.VolumeResult>()
    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val hit = tracks.firstNotNullOfOrNull { track ->
                track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
            } ?: error("volumeItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            val (track, clip) = hit
            val audio = clip as? Clip.Audio ?: error(
                "volumeItems[$idx]: set_clip_volumes only applies to audio clips; clip ${item.clipId} " +
                    "is a ${clip::class.simpleName}.",
            )
            val oldVolume = audio.volume
            val rebuilt = track.clips.map {
                if (it.id == audio.id) audio.copy(volume = item.volume) else it
            }
            tracks = tracks.map { if (it.id == track.id) withClips(track, rebuilt) else it }
            results += ClipSetActionTool.VolumeResult(
                clipId = item.clipId,
                trackId = track.id.value,
                oldVolume = oldVolume,
                newVolume = item.volume,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }
    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "set volume × ${results.size}",
        outputForLlm = "Set volume on ${results.size} audio clip(s). Snapshot: ${snapshotId.value}",
        data = ClipSetActionTool.Output(
            projectId = pid.value,
            field = "volume",
            snapshotId = snapshotId.value,
            volumeResults = results,
        ),
    )
}
