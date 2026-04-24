package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Transform
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `field="transform"` handler extracted from [ClipSetActionTool].
 *
 * Partial-override semantics: `translate*` / `scale*` / `rotationDeg`
 * / `opacity` are all optional; unspecified fields inherit from the
 * clip's current `Transform` (or a fresh default). Merge happens here
 * so the sealed `Clip` `when` only lands the final merged transform
 * back.
 */

internal suspend fun executeClipSetTransform(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipSetActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipSetActionTool.Output> {
    val items = input.transformItems ?: error("field=transform requires `transformItems`")
    rejectForeignClipSetFields("transform", input)
    require(items.isNotEmpty()) { "transformItems must not be empty" }
    items.forEachIndexed { idx, item ->
        val overrides = listOfNotNull(
            item.translateX, item.translateY,
            item.scaleX, item.scaleY,
            item.rotationDeg, item.opacity,
        )
        require(overrides.isNotEmpty()) {
            "transformItems[$idx] (${item.clipId}): at least one of translate/scale/rotation/opacity required"
        }
        item.opacity?.let {
            require(it.isFinite() && it in 0f..1f) {
                "transformItems[$idx] (${item.clipId}): opacity must be in [0, 1] (got $it)"
            }
        }
        item.scaleX?.let {
            require(it.isFinite() && it > 0f) {
                "transformItems[$idx] (${item.clipId}): scaleX must be > 0 (got $it)"
            }
        }
        item.scaleY?.let {
            require(it.isFinite() && it > 0f) {
                "transformItems[$idx] (${item.clipId}): scaleY must be > 0 (got $it)"
            }
        }
        item.translateX?.let {
            require(it.isFinite()) { "transformItems[$idx] (${item.clipId}): translateX must be finite" }
        }
        item.translateY?.let {
            require(it.isFinite()) { "transformItems[$idx] (${item.clipId}): translateY must be finite" }
        }
        item.rotationDeg?.let {
            require(it.isFinite()) { "transformItems[$idx] (${item.clipId}): rotationDeg must be finite" }
        }
    }

    val results = mutableListOf<ClipSetActionTool.TransformResult>()
    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val hit = tracks.firstNotNullOfOrNull { track ->
                track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
            } ?: error("transformItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            val (track, clip) = hit
            val base = clip.transforms.firstOrNull() ?: Transform()
            val merged = base.copy(
                translateX = item.translateX ?: base.translateX,
                translateY = item.translateY ?: base.translateY,
                scaleX = item.scaleX ?: base.scaleX,
                scaleY = item.scaleY ?: base.scaleY,
                rotationDeg = item.rotationDeg ?: base.rotationDeg,
                opacity = item.opacity ?: base.opacity,
            )
            val rebuilt = track.clips.map { c ->
                if (c.id != clip.id) {
                    c
                } else {
                    when (c) {
                        is Clip.Video -> c.copy(transforms = listOf(merged))
                        is Clip.Audio -> c.copy(transforms = listOf(merged))
                        is Clip.Text -> c.copy(transforms = listOf(merged))
                    }
                }
            }
            tracks = tracks.map { if (it.id == track.id) withClips(track, rebuilt) else it }
            results += ClipSetActionTool.TransformResult(
                clipId = item.clipId,
                trackId = track.id.value,
                oldTransform = base,
                newTransform = merged,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }
    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "set transform × ${results.size}",
        outputForLlm = "Set transforms on ${results.size} clip(s). Snapshot: ${snapshotId.value}",
        data = ClipSetActionTool.Output(
            projectId = pid.value,
            field = "transform",
            snapshotId = snapshotId.value,
            transformResults = results,
        ),
    )
}
