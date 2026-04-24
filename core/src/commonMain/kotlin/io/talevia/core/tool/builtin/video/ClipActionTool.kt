package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Three-way clip edit verb — the consolidated action-dispatched form that
 * replaces the previous `AddClipTool` + `RemoveClipTool` + `DuplicateClipTool`
 * trio (`debt-video-clip-consolidate-verbs-phase-1`, 2026-04-23, following
 * `TransitionActionTool` / `SessionActionTool` precedent).
 *
 * The three clip verbs collapsed here share a shape — each takes a
 * `projectId?` + a per-action batch payload and mutates the timeline
 * atomically under `ProjectStore.mutate`. Folding them into one `Tool`
 * cuts two top-level LLM tool-spec entries (≈ 600 tokens per turn
 * saved) without losing any behavioural surface. Phase 2 (Move / Split
 * / Trim / Replace / FadeAudio) and the Set family (SetClipTransform /
 * SetClipVolume / SetClipSourceBinding) stay as separate bullets so
 * each cycle's diff remains bounded.
 *
 * Action-specific payload fields are nullable-per-action, same pattern
 * `TransitionActionTool` uses — kotlinx.serialization sealed-class
 * variants would blow up the JSON Schema surface that the LLM reads
 * without buying anything the per-action validation in `execute()`
 * doesn't already provide.
 *
 * Atomic semantics preserved per action: any mid-batch validation
 * failure aborts the whole call and leaves `talevia.json` untouched.
 * Each call emits exactly one `Part.TimelineSnapshot` so
 * `revert_timeline` walks back the whole batch in one step.
 *
 * ## Actions
 *
 * - `action="add"` + `addItems` — append one or many video clips to the
 *   timeline. Per-item: `assetId`, optional `timelineStartSeconds`
 *   (omit to append after the last clip on the target track),
 *   `sourceStartSeconds`, `durationSeconds`, `trackId`. Within a batch
 *   subsequent appends see each other's clips so N append-style items
 *   lay end-to-end.
 * - `action="remove"` + `clipIds` + optional `ripple` — delete one or
 *   many clips. `ripple=true` closes each gap on the removed clip's
 *   track by shifting every later non-overlapping clip left by the
 *   removed clip's duration; default `false` leaves gaps so
 *   transitions / subtitles aligned to specific timestamps don't
 *   drift. Overlapping clips on the same track (PiP) are never shifted;
 *   other tracks are never touched. Ripple cascades within the batch.
 * - `action="duplicate"` + `duplicateItems` — clone one or many clips
 *   to new timeline positions, preserving every attached field except
 *   the id and start time. Optional per-item `trackId` moves the
 *   duplicate to another track of the same kind (Video→Video,
 *   Audio→Audio, Text→Subtitle or Effect). Cross-kind moves rejected
 *   loudly.
 */
@OptIn(ExperimentalUuidApi::class)
class ClipActionTool(
    private val store: ProjectStore,
) : Tool<ClipActionTool.Input, ClipActionTool.Output> {

    /** One add request. Mirrors the legacy `AddClipTool.Item`. */
    @Serializable data class AddItem(
        val assetId: String,
        val timelineStartSeconds: Double? = null,
        val sourceStartSeconds: Double = 0.0,
        val durationSeconds: Double? = null,
        val trackId: String? = null,
    )

    /** One duplicate request. Mirrors the legacy `DuplicateClipTool.Item`. */
    @Serializable data class DuplicateItem(
        val clipId: String,
        val timelineStartSeconds: Double,
        val trackId: String? = null,
    )

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** `"add"`, `"remove"`, or `"duplicate"`. Case-sensitive. */
        val action: String,
        /** Required when `action="add"`. Clips to insert. */
        val addItems: List<AddItem>? = null,
        /** Required when `action="remove"`. Clip ids to delete. */
        val clipIds: List<String>? = null,
        /** Optional (`action="remove"` only). Close the gap on each removed clip's track. */
        val ripple: Boolean = false,
        /** Required when `action="duplicate"`. Clones to produce. */
        val duplicateItems: List<DuplicateItem>? = null,
    )

    @Serializable data class AddResult(
        val clipId: String,
        val timelineStartSeconds: Double,
        val timelineEndSeconds: Double,
        val trackId: String,
    )

    @Serializable data class RemoveResult(
        val clipId: String,
        val trackId: String,
        val durationSeconds: Double,
        val shiftedClipCount: Int,
    )

    @Serializable data class DuplicateResult(
        val originalClipId: String,
        val newClipId: String,
        val sourceTrackId: String,
        val targetTrackId: String,
        val timelineStartSeconds: Double,
        val timelineEndSeconds: Double,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        val snapshotId: String,
        /** Populated when `action="add"`. */
        val added: List<AddResult> = emptyList(),
        /** Populated when `action="remove"`. */
        val removed: List<RemoveResult> = emptyList(),
        /** Populated when `action="duplicate"`. */
        val duplicated: List<DuplicateResult> = emptyList(),
        /** `action="remove"` only — echoes the input `ripple` flag. */
        val rippled: Boolean = false,
    )

    override val id: String = "clip_action"
    override val helpText: String =
        "Three-way clip edit verb dispatching on `action`: " +
            "`action=\"add\"` + `addItems` (each: assetId, timelineStartSeconds?, sourceStartSeconds?, " +
            "durationSeconds?, trackId?) appends one or many video clips; omitted " +
            "timelineStartSeconds means 'append after the last clip on the target track'. " +
            "`action=\"remove\"` + `clipIds` + optional `ripple` (default false) deletes clips; " +
            "ripple=true closes the gap on each removed clip's track (overlapping clips and " +
            "other tracks stay put). `action=\"duplicate\"` + `duplicateItems` (each: clipId, " +
            "timelineStartSeconds, trackId?) clones clips preserving filters, transforms, source " +
            "bindings, audio envelope, and text style to a new timeline position with a fresh id; " +
            "cross-kind trackId rejected. All-or-nothing per call; one timeline snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to use the session's current project (set via switch_project).",
                )
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "`add` to insert clips, `remove` to delete, `duplicate` to clone.")
                put(
                    "enum",
                    JsonArray(
                        listOf(
                            JsonPrimitive("add"),
                            JsonPrimitive("remove"),
                            JsonPrimitive("duplicate"),
                        ),
                    ),
                )
            }
            putJsonObject("addItems") {
                put("type", "array")
                put("description", "Required when action=add. Clips to append; at least one.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("assetId") { put("type", "string") }
                        putJsonObject("timelineStartSeconds") {
                            put("type", "number")
                            put("description", "If omitted, append after the last clip on the target track.")
                        }
                        putJsonObject("sourceStartSeconds") {
                            put("type", "number")
                            put("description", "Trim offset into the source media.")
                        }
                        putJsonObject("durationSeconds") {
                            put("type", "number")
                            put("description", "If omitted, use the asset's full remaining duration.")
                        }
                        putJsonObject("trackId") {
                            put("type", "string")
                            put("description", "Optional track; defaults to the first Video track (created if absent).")
                        }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("assetId"))))
                    put("additionalProperties", false)
                }
            }
            putJsonObject("clipIds") {
                put("type", "array")
                put("description", "Required when action=remove. Clip ids to delete; at least one.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("ripple") {
                put("type", "boolean")
                put(
                    "description",
                    "action=remove only. Close the gap on each removed clip's track. Default false.",
                )
            }
            putJsonObject("duplicateItems") {
                put("type", "array")
                put("description", "Required when action=duplicate. Clones to produce; at least one.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("timelineStartSeconds") {
                            put("type", "number")
                            put("description", "New timeline start position in seconds (must be >= 0).")
                        }
                        putJsonObject("trackId") {
                            put("type", "string")
                            put(
                                "description",
                                "Optional target track id of the same kind. Defaults to the source clip's track.",
                            )
                        }
                    }
                    put(
                        "required",
                        JsonArray(
                            listOf(
                                JsonPrimitive("clipId"),
                                JsonPrimitive("timelineStartSeconds"),
                            ),
                        ),
                    )
                    put("additionalProperties", false)
                }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        return when (input.action) {
            "add" -> executeAdd(pid, input, ctx)
            "remove" -> executeRemove(pid, input, ctx)
            "duplicate" -> executeDuplicate(pid, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: add, remove, duplicate",
            )
        }
    }

    private suspend fun executeAdd(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.addItems
            ?: error("action=add requires `addItems` (omit `clipIds` / `duplicateItems`)")
        require(input.clipIds == null && input.duplicateItems == null) {
            "action=add rejects `clipIds` / `duplicateItems` — use `addItems`"
        }
        require(items.isNotEmpty()) { "addItems must not be empty" }
        items.forEachIndexed { idx, item ->
            require(item.sourceStartSeconds >= 0.0) {
                "addItems[$idx] (asset ${item.assetId}): sourceStartSeconds must be >= 0"
            }
            item.timelineStartSeconds?.let {
                require(it >= 0.0) {
                    "addItems[$idx] (asset ${item.assetId}): timelineStartSeconds must be >= 0"
                }
            }
            item.durationSeconds?.let {
                require(it > 0.0) {
                    "addItems[$idx] (asset ${item.assetId}): durationSeconds must be > 0"
                }
            }
        }

        val results = mutableListOf<AddResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            items.forEachIndexed { idx, item ->
                val asset = project.assets.firstOrNull { it.id.value == item.assetId }
                    ?: error("addItems[$idx]: asset ${item.assetId} not found; import_media first.")
                val sourceStart: Duration = item.sourceStartSeconds.seconds
                require(sourceStart < asset.metadata.duration) {
                    "addItems[$idx] (asset ${item.assetId}): sourceStartSeconds ${item.sourceStartSeconds} exceeds asset " +
                        "duration ${asset.metadata.duration.inWholeMilliseconds / 1000.0}s"
                }
                val remaining = asset.metadata.duration - sourceStart
                val clipDuration: Duration = (item.durationSeconds?.seconds ?: remaining).coerceAtMost(remaining)
                require(clipDuration > Duration.ZERO) {
                    "addItems[$idx] (asset ${item.assetId}): clip duration must be > 0"
                }

                val videoTrack = pickVideoTrack(tracks, item.trackId)
                val tlStart = item.timelineStartSeconds?.seconds
                    ?: videoTrack.clips.maxOfOrNull { it.timeRange.end }
                    ?: Duration.ZERO
                require(tlStart >= Duration.ZERO) {
                    "addItems[$idx] (asset ${item.assetId}): timelineStartSeconds must be >= 0"
                }

                val clipId = ClipId(Uuid.random().toString())
                val newClip = Clip.Video(
                    id = clipId,
                    timeRange = TimeRange(tlStart, clipDuration),
                    sourceRange = TimeRange(sourceStart, clipDuration),
                    assetId = asset.id,
                )
                val newClips = (videoTrack.clips + newClip).sortedBy { it.timeRange.start }
                val newTrack = videoTrack.copy(clips = newClips)
                tracks = upsertTrackPreservingOrder(tracks, newTrack)

                results += AddResult(
                    clipId = clipId.value,
                    timelineStartSeconds = tlStart.toDouble(DurationUnit.SECONDS),
                    timelineEndSeconds = (tlStart + clipDuration).toDouble(DurationUnit.SECONDS),
                    trackId = newTrack.id.value,
                )
            }
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "add ${results.size} clip(s)",
            outputForLlm = "Added ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                action = "add",
                snapshotId = snapshotId.value,
                added = results,
            ),
        )
    }

    private suspend fun executeRemove(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val clipIds = input.clipIds
            ?: error("action=remove requires `clipIds` (omit `addItems` / `duplicateItems`)")
        require(input.addItems == null && input.duplicateItems == null) {
            "action=remove rejects `addItems` / `duplicateItems` — use `clipIds`"
        }
        require(clipIds.isNotEmpty()) { "clipIds must not be empty" }

        val results = mutableListOf<RemoveResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            clipIds.forEachIndexed { idx, clipId ->
                var foundTrackId: String? = null
                var removedRange: TimeRange? = null
                var shifted = 0
                tracks = tracks.map { track ->
                    val target = track.clips.firstOrNull { it.id.value == clipId }
                    if (target == null) {
                        track
                    } else {
                        foundTrackId = track.id.value
                        removedRange = target.timeRange
                        val keep = track.clips.filter { it.id.value != clipId }
                        val shiftedClips = if (input.ripple) {
                            keep.map { clip ->
                                if (clip.timeRange.start >= target.timeRange.end) {
                                    shifted += 1
                                    clip.shiftStart(-target.timeRange.duration)
                                } else {
                                    clip
                                }
                            }
                        } else {
                            keep
                        }
                        when (track) {
                            is Track.Video -> track.copy(clips = shiftedClips)
                            is Track.Audio -> track.copy(clips = shiftedClips)
                            is Track.Subtitle -> track.copy(clips = shiftedClips)
                            is Track.Effect -> track.copy(clips = shiftedClips)
                        }
                    }
                }
                if (foundTrackId == null) {
                    error("clipIds[$idx] ($clipId) not found in project ${pid.value}")
                }
                results += RemoveResult(
                    clipId = clipId,
                    trackId = foundTrackId!!,
                    durationSeconds = removedRange!!.duration.inWholeMilliseconds / 1000.0,
                    shiftedClipCount = shifted,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val summary = buildString {
            append("Removed ${results.size} clip(s)")
            if (input.ripple) append(" (rippled)")
            append(". Timeline snapshot: ${snapshotId.value}")
        }
        return ToolResult(
            title = "remove ${results.size} clip(s)",
            outputForLlm = summary,
            data = Output(
                projectId = pid.value,
                action = "remove",
                snapshotId = snapshotId.value,
                removed = results,
                rippled = input.ripple,
            ),
        )
    }

    private suspend fun executeDuplicate(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.duplicateItems
            ?: error("action=duplicate requires `duplicateItems` (omit `addItems` / `clipIds`)")
        require(input.addItems == null && input.clipIds == null) {
            "action=duplicate rejects `addItems` / `clipIds` — use `duplicateItems`"
        }
        require(items.isNotEmpty()) { "duplicateItems must not be empty" }
        items.forEachIndexed { idx, item ->
            require(item.timelineStartSeconds >= 0.0) {
                "duplicateItems[$idx] (${item.clipId}): timelineStartSeconds must be >= 0 (got ${item.timelineStartSeconds})"
            }
        }

        val results = mutableListOf<DuplicateResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            items.forEachIndexed { idx, item ->
                val originalTrack = tracks.firstOrNull { t ->
                    t.clips.any { it.id.value == item.clipId }
                } ?: error("duplicateItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                val original = originalTrack.clips.first { it.id.value == item.clipId }

                val targetTrack = if (item.trackId == null || item.trackId == originalTrack.id.value) {
                    originalTrack
                } else {
                    val candidate = tracks.firstOrNull { it.id.value == item.trackId }
                        ?: error(
                            "duplicateItems[$idx] (${item.clipId}): trackId ${item.trackId} not found in project ${pid.value}",
                        )
                    require(trackKindOf(candidate) == trackKindOf(originalTrack)) {
                        "duplicateItems[$idx] (${item.clipId}): trackId ${item.trackId} is ${trackKindOf(candidate)} " +
                            "but source clip is ${trackKindOf(originalTrack)}"
                    }
                    candidate
                }

                val newStart: Duration = item.timelineStartSeconds.seconds
                val newClipId = ClipId(Uuid.random().toString())
                val cloned = cloneClip(original, newClipId, newStart)
                val newEndSeconds = (newStart + original.timeRange.duration).toDouble(DurationUnit.SECONDS)

                tracks = tracks.map { t ->
                    if (t.id == targetTrack.id) {
                        val newClips = (t.clips + cloned).sortedBy { it.timeRange.start }
                        withClips(t, newClips)
                    } else {
                        t
                    }
                }
                results += DuplicateResult(
                    originalClipId = item.clipId,
                    newClipId = newClipId.value,
                    sourceTrackId = originalTrack.id.value,
                    targetTrackId = targetTrack.id.value,
                    timelineStartSeconds = item.timelineStartSeconds,
                    timelineEndSeconds = newEndSeconds,
                )
            }
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "duplicate × ${results.size}",
            outputForLlm = "Duplicated ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                action = "duplicate",
                snapshotId = snapshotId.value,
                duplicated = results,
            ),
        )
    }

    private fun pickVideoTrack(tracks: List<Track>, requestedId: String?): Track.Video {
        val match = if (requestedId != null) {
            val requested = tracks.firstOrNull { it.id.value == requestedId }
                ?: error("trackId $requestedId not found")
            if (requested !is Track.Video) {
                error("trackId $requestedId is not a video track")
            }
            requested
        } else {
            tracks.firstOrNull { it is Track.Video }
        }
        return match as? Track.Video ?: Track.Video(TrackId(Uuid.random().toString()))
    }

    private fun cloneClip(original: Clip, newId: ClipId, newStart: Duration): Clip = when (original) {
        is Clip.Video -> original.copy(
            id = newId,
            timeRange = TimeRange(newStart, original.timeRange.duration),
        )
        is Clip.Audio -> original.copy(
            id = newId,
            timeRange = TimeRange(newStart, original.timeRange.duration),
        )
        is Clip.Text -> original.copy(
            id = newId,
            timeRange = TimeRange(newStart, original.timeRange.duration),
        )
    }

    private fun trackKindOf(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }

    private fun withClips(track: Track, clips: List<Clip>): Track = when (track) {
        is Track.Video -> track.copy(clips = clips)
        is Track.Audio -> track.copy(clips = clips)
        is Track.Subtitle -> track.copy(clips = clips)
        is Track.Effect -> track.copy(clips = clips)
    }

    private fun Clip.shiftStart(delta: Duration): Clip {
        val newRange = timeRange.copy(start = timeRange.start + delta)
        return when (this) {
            is Clip.Video -> copy(timeRange = newRange)
            is Clip.Audio -> copy(timeRange = newRange)
            is Clip.Text -> copy(timeRange = newRange)
        }
    }
}
