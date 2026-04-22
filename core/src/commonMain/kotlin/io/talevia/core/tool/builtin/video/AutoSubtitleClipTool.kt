package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.AsrRequest
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * ASR → timeline subtitles in a single call (VISION §5.2 ML lane, closes the
 * loop between [io.talevia.core.tool.builtin.ml.TranscribeAssetTool] and
 * [AddSubtitlesTool]).
 *
 * Why this exists as its own tool, not an agent chain of `transcribe_asset` +
 * `add_subtitles`: "give this clip captions" is the load-bearing use case and
 * the two-step chain burns tokens + latency + a guaranteed intermediate
 * snapshot on every invocation. The agent composing those steps is *also*
 * responsible for mapping `TranscriptSegment.startMs/endMs` (ms-since-asset-
 * start) into `Segment.startSeconds/durationSeconds` (seconds on the project
 * timeline, offset by the clip's `timeRange.start`), and that arithmetic is
 * brittle to get right every time. This tool does the arithmetic once, in
 * one place, and emits a single [io.talevia.core.session.Part.TimelineSnapshot]
 * so `revert_timeline` sees the ASR + captioning as one atomic edit.
 *
 * Scope. The input is a `clipId` already on the timeline — the tool reads its
 * `assetId` + `timeRange` to decide what to transcribe and where to place the
 * captions. If the caller wants to transcribe an unattached asset or line
 * subtitles to a bare timeline offset, they should use `transcribe_asset` +
 * `add_subtitles` directly.
 *
 * Clipping. Each transcript segment is placed at
 * `clip.timeRange.start + segment.startMs`. If the segment would extend past
 * `clip.timeRange.end` (ASR returned content beyond the clip's sourceRange,
 * e.g. the clip is a trimmed window of a longer take), the caption is
 * truncated to end at the clip boundary. Segments whose *start* is beyond the
 * clip are dropped; we surface `droppedSegmentCount` so the agent can reason
 * about what's missing.
 *
 * Permission: `"ml.transcribe"` — the audio is uploaded to a provider, same
 * exfiltration concern as `transcribe_asset`. The timeline write is implicit
 * to the same intent.
 */
@OptIn(ExperimentalUuidApi::class)
class AutoSubtitleClipTool(
    private val engine: AsrEngine,
    private val resolver: MediaPathResolver,
    private val store: ProjectStore,
) : Tool<AutoSubtitleClipTool.Input, AutoSubtitleClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        val model: String = "whisper-1",
        val language: String? = null,
        val fontSize: Float = 48f,
        val color: String = "#FFFFFF",
        val backgroundColor: String? = null,
    )

    @Serializable data class Output(
        val trackId: String,
        val clipIds: List<String>,
        val detectedLanguage: String?,
        val segmentCount: Int,
        val droppedSegmentCount: Int,
        val preview: String,
    )

    override val id: String = "auto_subtitle_clip"
    override val helpText: String =
        "Transcribe the clip's audio via an ASR provider and drop each segment onto the " +
            "subtitle track at the correct timeline offset in one atomic edit. Combines " +
            "transcribe_asset + add_subtitles for the common 'caption this clip' case; " +
            "segments are placed at clipTimeRange.start + segment.startMs and clamped to " +
            "the clip boundary. Use transcribe_asset + add_subtitles directly for " +
            "unattached assets or bespoke offsets. Audio is uploaded to the provider — " +
            "the user is asked to confirm before each call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("ml.transcribe")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") {
                put("type", "string")
                put("description", "Clip on the timeline whose audio should be transcribed.")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped model id (default: whisper-1).")
            }
            putJsonObject("language") {
                put("type", "string")
                put("description", "Optional ISO-639-1 language hint (e.g. 'en'). Omit to auto-detect.")
            }
            putJsonObject("fontSize") { put("type", "number") }
            putJsonObject("color") { put("type", "string"); put("description", "CSS-style hex (e.g. #FFFFFF)") }
            putJsonObject("backgroundColor") {
                put("type", "string")
                put("description", "Optional background hex; null = transparent")
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val projectId = ProjectId(input.projectId)
        val project = store.get(projectId) ?: error("Project ${input.projectId} not found")

        val (sourceClip, assetId) = findClipWithAsset(project, ClipId(input.clipId))
            ?: error("Clip ${input.clipId} not found or has no associated media asset")
        val clipStart: Duration = sourceClip.timeRange.start
        val clipEnd: Duration = sourceClip.timeRange.end

        val path = resolver.resolve(assetId)
        val asr = engine.transcribe(
            AsrRequest(
                audioPath = path,
                modelId = input.model,
                languageHint = input.language?.takeIf { it.isNotBlank() },
            ),
        )

        // Translate transcript segments (ms relative to the asset) into timeline
        // placements and drop anything that falls outside the clip window.
        data class Placed(val start: Duration, val duration: Duration, val text: String)
        val placed = mutableListOf<Placed>()
        var dropped = 0
        for (seg in asr.segments) {
            val segStart = clipStart + seg.startMs.milliseconds
            if (segStart >= clipEnd) {
                dropped += 1
                continue
            }
            val segEndRaw = clipStart + seg.endMs.milliseconds
            val segEnd = if (segEndRaw > clipEnd) clipEnd else segEndRaw
            val segDuration = segEnd - segStart
            if (segDuration <= Duration.ZERO) {
                dropped += 1
                continue
            }
            placed += Placed(segStart, segDuration, seg.text)
        }

        if (placed.isEmpty()) {
            val out = Output(
                trackId = "",
                clipIds = emptyList(),
                detectedLanguage = asr.language,
                segmentCount = 0,
                droppedSegmentCount = dropped,
                preview = asr.text.take(120),
            )
            return ToolResult(
                title = "auto-subtitle (no segments placed)",
                outputForLlm = "Transcribed clip ${input.clipId} but produced no subtitles — " +
                    "ASR returned ${asr.segments.size} segment(s), all fell outside the clip window " +
                    "($clipStart..$clipEnd). Check the clip's sourceRange / timeRange.",
                data = out,
            )
        }

        val style = TextStyle(
            fontSize = input.fontSize,
            color = input.color,
            backgroundColor = input.backgroundColor,
        )
        val newClipIds = placed.map { ClipId(Uuid.random().toString()) }
        var subtitleTrackId: TrackId? = null

        val updated = store.mutate(projectId) { current ->
            val subtitleTrack = pickSubtitleTrack(current.timeline.tracks)
            val textClips = placed.mapIndexed { index, p ->
                Clip.Text(
                    id = newClipIds[index],
                    timeRange = TimeRange(p.start, p.duration),
                    text = p.text,
                    style = style,
                )
            }
            val merged = (subtitleTrack.clips + textClips).sortedBy { it.timeRange.start }
            val newTrack = subtitleTrack.copy(clips = merged)
            subtitleTrackId = newTrack.id
            val tracks = upsertTrackPreservingOrder(current.timeline.tracks, newTrack)
            val tail = textClips.maxOf { it.timeRange.end }
            val duration = maxOf(current.timeline.duration, tail)
            current.copy(timeline = current.timeline.copy(tracks = tracks, duration = duration))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val preview = asr.text.take(120).replace('\n', ' ')
        val langTail = asr.language?.let { " language=$it" }.orEmpty()
        val droppedTail = if (dropped > 0) " ($dropped segment(s) dropped outside clip window)" else ""
        return ToolResult(
            title = "auto-subtitle clip x${placed.size}",
            outputForLlm = "Captioned clip ${input.clipId} via ${asr.provenance.providerId}/${asr.provenance.modelId}$langTail. " +
                "Placed ${placed.size} segment(s) on subtitle track ${subtitleTrackId!!.value}$droppedTail. " +
                "Timeline snapshot: ${snapshotId.value}. Preview: \"$preview${if (preview.length < asr.text.length) "…" else ""}\"",
            data = Output(
                trackId = subtitleTrackId!!.value,
                clipIds = newClipIds.map { it.value },
                detectedLanguage = asr.language,
                segmentCount = placed.size,
                droppedSegmentCount = dropped,
                preview = preview,
            ),
        )
    }

    private fun findClipWithAsset(
        project: io.talevia.core.domain.Project,
        clipId: ClipId,
    ): Pair<Clip, AssetId>? {
        for (track in project.timeline.tracks) {
            for (clip in track.clips) {
                if (clip.id != clipId) continue
                val asset = when (clip) {
                    is Clip.Video -> clip.assetId
                    is Clip.Audio -> clip.assetId
                    is Clip.Text -> null
                } ?: return null
                return clip to asset
            }
        }
        return null
    }

    private fun pickSubtitleTrack(tracks: List<Track>): Track.Subtitle {
        val match = tracks.firstOrNull { it is Track.Subtitle }
        return match as? Track.Subtitle ?: Track.Subtitle(TrackId(Uuid.random().toString()))
    }
}
