package io.talevia.core.tool.builtin.video.export

import io.talevia.core.PartId
import io.talevia.core.domain.Timeline
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Whole-timeline render path (the original flow). Collects progress from the
 * engine's [VideoEngine.render] and forwards as [Part.RenderProgress] events.
 *
 * Extracted from `ExportTool` in the `debt-split-export-tool` cycle; see
 * `docs/decisions/2026-04-22-debt-split-export-tool.md`.
 */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun runWholeTimelineRender(
    engine: VideoEngine,
    timeline: Timeline,
    output: OutputSpec,
    ctx: ToolContext,
    clock: Clock,
    resolver: MediaPathResolver? = null,
) {
    var failure: String? = null
    engine.render(timeline, output, resolver).collect { ev ->
        val partId = PartId(Uuid.random().toString())
        when (ev) {
            is RenderProgress.Started -> ctx.emitPart(
                Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = 0f, message = "started"),
            )
            is RenderProgress.Frames -> ctx.emitPart(
                Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = ev.ratio, message = ev.message),
            )
            is RenderProgress.Preview -> ctx.emitPart(
                Part.RenderProgress(
                    partId, ctx.messageId, ctx.sessionId, clock.now(),
                    jobId = ev.jobId, ratio = ev.ratio, message = "preview",
                    thumbnailPath = ev.thumbnailPath,
                ),
            )
            is RenderProgress.Completed -> ctx.emitPart(
                Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = 1f, message = "completed"),
            )
            is RenderProgress.Failed -> {
                failure = ev.message
                ctx.emitPart(
                    Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = 0f, message = "failed: ${ev.message}"),
                )
            }
        }
    }
    if (failure != null) error("export failed: $failure")
}
