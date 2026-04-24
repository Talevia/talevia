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
import kotlinx.serialization.json.JsonObjectBuilder
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
 * Six-way clip edit verb — consolidates `AddClipTool` + `RemoveClipTool` +
 * `DuplicateClipTool` (phase-1, 2026-04-23) AND `MoveClipTool` +
 * `SplitClipTool` + `TrimClipTool` (phase-2, this iteration).
 *
 * Phase 2 was originally scoped to fold five legacy tools (Move / Split /
 * Trim plus Replace + FadeAudio). The naive consolidation landed at
 * 1,285 LOC, past the "land above 950 → step back" ceiling. This
 * iteration keeps the three simplest verbs (Move / Split / Trim — their
 * shapes line up cleanly with the phase-1 pattern) and defers
 * `ReplaceClipTool` + `FadeAudioClipTool` to phase-3
 * (`debt-video-clip-consolidate-verbs-phase-3`). Phase 3 will also need
 * to decide whether to split ClipActionTool by axis first — see the
 * commit body for the budget analysis.
 *
 * The six clip verbs share a shape: each takes a `projectId?` + a
 * per-action batch payload and mutates the timeline atomically under
 * `ProjectStore.mutate`. Folding them into one `Tool` cuts five
 * top-level LLM tool-spec entries from the turn budget without losing
 * any behavioural surface. The Set family (SetClipTransform /
 * SetClipVolume / SetClipSourceBinding) stays separate
 * (`debt-video-clip-consolidate-set-family`).
 *
 * Action-specific payload fields are nullable-per-action, matching the
 * `TransitionActionTool` precedent. Per-action validation in `execute()`
 * rejects foreign fields loudly; atomic all-or-nothing batch semantics
 * preserved per verb; each call emits exactly one
 * `Part.TimelineSnapshot` so `revert_timeline` walks back the whole
 * batch in one step.
 *
 * Per-action contract summary (details in the legacy docstring on the
 * tool each branch replaced; git-blame points there):
 * - `add` / `remove` / `duplicate` — phase-1 shapes, unchanged.
 * - `move` + `moveItems` (clipId, timelineStartSeconds?, toTrackId?;
 *   at least one of the latter two): same-track and/or cross-track
 *   reposition; target track kind must match clip kind.
 * - `split` + `splitItems` (clipId, atTimelineSeconds): split point
 *   must lie strictly inside the clip.
 * - `trim` + `trimItems` (clipId, newSourceStartSeconds?,
 *   newDurationSeconds?): retrim video/audio clips preserving filters;
 *   `timeRange.start` is preserved; text clips rejected.
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

    /** One move request. Mirrors the legacy `MoveClipTool.Item`. */
    @Serializable data class MoveItem(
        val clipId: String,
        val timelineStartSeconds: Double? = null,
        val toTrackId: String? = null,
    )

    /** One split request. Mirrors the legacy `SplitClipTool.Item`. */
    @Serializable data class SplitItem(
        val clipId: String,
        val atTimelineSeconds: Double,
    )

    /** One trim request. Mirrors the legacy `TrimClipTool.Item`. */
    @Serializable data class TrimItem(
        val clipId: String,
        val newSourceStartSeconds: Double? = null,
        val newDurationSeconds: Double? = null,
    )

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /**
         * One of `"add"`, `"remove"`, `"duplicate"`, `"move"`, `"split"`,
         * `"trim"`. Case-sensitive.
         */
        val action: String,
        /** Required when `action="add"`. Clips to insert. */
        val addItems: List<AddItem>? = null,
        /** Required when `action="remove"`. Clip ids to delete. */
        val clipIds: List<String>? = null,
        /** Optional (`action="remove"` only). Close the gap on each removed clip's track. */
        val ripple: Boolean = false,
        /** Required when `action="duplicate"`. Clones to produce. */
        val duplicateItems: List<DuplicateItem>? = null,
        /** Required when `action="move"`. Clips to reposition. */
        val moveItems: List<MoveItem>? = null,
        /** Required when `action="split"`. Clips to split. */
        val splitItems: List<SplitItem>? = null,
        /** Required when `action="trim"`. Clips to re-trim. */
        val trimItems: List<TrimItem>? = null,
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

    @Serializable data class MoveResult(
        val clipId: String,
        val fromTrackId: String,
        val toTrackId: String,
        val oldStartSeconds: Double,
        val newStartSeconds: Double,
        val changedTrack: Boolean,
    )

    @Serializable data class SplitResult(
        val originalClipId: String,
        val leftClipId: String,
        val rightClipId: String,
    )

    @Serializable data class TrimResult(
        val clipId: String,
        val trackId: String,
        val newSourceStartSeconds: Double,
        val newDurationSeconds: Double,
        val newTimelineEndSeconds: Double,
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
        /** Populated when `action="move"`. */
        val moved: List<MoveResult> = emptyList(),
        /** Populated when `action="split"`. */
        val split: List<SplitResult> = emptyList(),
        /** Populated when `action="trim"`. */
        val trimmed: List<TrimResult> = emptyList(),
        /** `action="remove"` only — echoes the input `ripple` flag. */
        val rippled: Boolean = false,
    )

    override val id: String = "clip_action"
    override val helpText: String =
        "Six-way clip edit verb dispatching on `action`: " +
            "`add` + `addItems` (assetId, timelineStartSeconds?, sourceStartSeconds?, " +
            "durationSeconds?, trackId?) appends clips. " +
            "`remove` + `clipIds` + optional `ripple` (default false) deletes clips; " +
            "ripple closes the gap on the removed clip's track. " +
            "`duplicate` + `duplicateItems` (clipId, timelineStartSeconds, trackId?) clones " +
            "clips preserving all attached state; cross-kind trackId rejected. " +
            "`move` + `moveItems` (clipId, timelineStartSeconds?, toTrackId?, at least one) " +
            "repositions clips in time and/or across same-kind tracks. " +
            "`split` + `splitItems` (clipId, atTimelineSeconds) splits each clip at the given " +
            "timeline position; split point must lie strictly inside the clip. " +
            "`trim` + `trimItems` (clipId, newSourceStartSeconds?, newDurationSeconds?) " +
            "retrims video/audio clips preserving filters; text clips rejected. " +
            "All-or-nothing per call; one timeline snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Optional — omit to use the session's current project (set via switch_project).")
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "One of: add, remove, duplicate, move, split, trim.")
                put(
                    "enum",
                    JsonArray(listOf("add", "remove", "duplicate", "move", "split", "trim").map(::JsonPrimitive)),
                )
            }
            putJsonObject("addItems") {
                itemArray("Required when action=add. Clips to append; at least one.", required = listOf("assetId")) {
                    stringProp("assetId")
                    numberProp("timelineStartSeconds", "If omitted, append after the last clip on the target track.")
                    numberProp("sourceStartSeconds", "Trim offset into the source media.")
                    numberProp("durationSeconds", "If omitted, use the asset's full remaining duration.")
                    stringProp("trackId", "Optional track; defaults to the first Video track (created if absent).")
                }
            }
            putJsonObject("clipIds") {
                put("type", "array")
                put("description", "Required when action=remove. Clip ids to delete; at least one.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("ripple") {
                put("type", "boolean")
                put("description", "action=remove only. Close the gap on each removed clip's track. Default false.")
            }
            putJsonObject("duplicateItems") {
                itemArray(
                    "Required when action=duplicate. Clones to produce; at least one.",
                    required = listOf("clipId", "timelineStartSeconds"),
                ) {
                    stringProp("clipId")
                    numberProp("timelineStartSeconds", "New timeline start position in seconds (must be >= 0).")
                    stringProp("trackId", "Optional target track id of the same kind. Defaults to the source clip's track.")
                }
            }
            putJsonObject("moveItems") {
                itemArray(
                    "Required when action=move. Reposition operations; at least one.",
                    required = listOf("clipId"),
                ) {
                    stringProp("clipId")
                    numberProp(
                        "timelineStartSeconds",
                        "New timeline start position in seconds (>= 0). Omit to keep current (valid only when toTrackId is set).",
                    )
                    stringProp(
                        "toTrackId",
                        "Optional target track id. Omit for same-track reposition. Must be same kind as the clip.",
                    )
                }
            }
            putJsonObject("splitItems") {
                itemArray(
                    "Required when action=split. Split operations; at least one.",
                    required = listOf("clipId", "atTimelineSeconds"),
                ) {
                    stringProp("clipId")
                    numberProp(
                        "atTimelineSeconds",
                        "Absolute timeline position to split at (strictly between clip's start and end).",
                    )
                }
            }
            putJsonObject("trimItems") {
                itemArray(
                    "Required when action=trim. Trim operations; at least one.",
                    required = listOf("clipId"),
                ) {
                    stringProp("clipId")
                    numberProp(
                        "newSourceStartSeconds",
                        "New trim offset into the source media (>= 0). Omit to keep current.",
                    )
                    numberProp(
                        "newDurationSeconds",
                        "New duration in seconds (> 0). Applied to both timeRange and sourceRange. Omit to keep current.",
                    )
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
            "move" -> executeMove(pid, input, ctx)
            "split" -> executeSplit(pid, input, ctx)
            "trim" -> executeTrim(pid, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: add, remove, duplicate, move, split, trim",
            )
        }
    }

    /**
     * Ensure only the fields for [action] are present; reject the others loudly.
     * Central shared validator for all six action branches.
     */
    private fun rejectForeign(action: String, input: Input) {
        val foreign = buildList {
            if (action != "add" && input.addItems != null) add("addItems")
            if (action != "remove" && input.clipIds != null) add("clipIds")
            if (action != "duplicate" && input.duplicateItems != null) add("duplicateItems")
            if (action != "move" && input.moveItems != null) add("moveItems")
            if (action != "split" && input.splitItems != null) add("splitItems")
            if (action != "trim" && input.trimItems != null) add("trimItems")
        }
        require(foreign.isEmpty()) {
            "action=$action rejects ${foreign.joinToString(" / ")} — use this action's own payload field"
        }
    }

    private suspend fun executeAdd(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.addItems ?: error("action=add requires `addItems`")
        rejectForeign("add", input)
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
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = recomputeDuration(tracks)))
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
        val clipIds = input.clipIds ?: error("action=remove requires `clipIds`")
        rejectForeign("remove", input)
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
                        withClips(track, shiftedClips)
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
        val items = input.duplicateItems ?: error("action=duplicate requires `duplicateItems`")
        rejectForeign("duplicate", input)
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
                        withClips(t, (t.clips + cloned).sortedBy { it.timeRange.start })
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
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = recomputeDuration(tracks)))
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

    private suspend fun executeMove(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.moveItems ?: error("action=move requires `moveItems`")
        rejectForeign("move", input)
        require(items.isNotEmpty()) { "moveItems must not be empty" }
        items.forEachIndexed { idx, item ->
            if (item.timelineStartSeconds == null && item.toTrackId == null) {
                error("moveItems[$idx] (${item.clipId}): at least one of timelineStartSeconds / toTrackId must be set")
            }
            item.timelineStartSeconds?.let {
                if (it < 0.0) error("moveItems[$idx] (${item.clipId}): timelineStartSeconds must be >= 0 (got $it)")
            }
        }

        val results = mutableListOf<MoveResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            items.forEachIndexed { idx, item ->
                val sourceHit = tracks
                    .firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id.value == item.clipId }?.let { t to it } }
                    ?: error("moveItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                val (sourceTrack, clip) = sourceHit

                val targetTrack: Track = when {
                    item.toTrackId == null || item.toTrackId == sourceTrack.id.value -> sourceTrack
                    else -> tracks.firstOrNull { it.id.value == item.toTrackId }
                        ?: error("moveItems[$idx] (${item.clipId}): target track ${item.toTrackId} not found")
                }

                if (targetTrack.id != sourceTrack.id && !isKindCompatible(clip, targetTrack)) {
                    error(
                        "moveItems[$idx] (${item.clipId}): cannot move ${clipKindOf(clip)} clip onto " +
                            "${trackKindOf(targetTrack)} track ${targetTrack.id.value}.",
                    )
                }

                val oldStartSeconds = clip.timeRange.start.toDouble(DurationUnit.SECONDS)
                val newStart: Duration = item.timelineStartSeconds?.seconds ?: clip.timeRange.start
                val moved = withTimeRange(clip, TimeRange(newStart, clip.timeRange.duration))

                tracks = if (targetTrack.id == sourceTrack.id) {
                    tracks.map { track ->
                        if (track.id != sourceTrack.id) track else replaceClip(track, moved)
                    }
                } else {
                    tracks.map { track ->
                        when (track.id) {
                            sourceTrack.id -> withClips(track, track.clips.filterNot { it.id.value == clip.id.value })
                            targetTrack.id -> withClips(track, (track.clips + moved).sortedBy { it.timeRange.start })
                            else -> track
                        }
                    }
                }

                results += MoveResult(
                    clipId = item.clipId,
                    fromTrackId = sourceTrack.id.value,
                    toTrackId = targetTrack.id.value,
                    oldStartSeconds = oldStartSeconds,
                    newStartSeconds = newStart.toDouble(DurationUnit.SECONDS),
                    changedTrack = sourceTrack.id.value != targetTrack.id.value,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = recomputeDuration(tracks)))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "move ${results.size} clip(s)",
            outputForLlm = "Moved ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                action = "move",
                snapshotId = snapshotId.value,
                moved = results,
            ),
        )
    }

    private suspend fun executeSplit(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.splitItems ?: error("action=split requires `splitItems`")
        rejectForeign("split", input)
        require(items.isNotEmpty()) { "splitItems must not be empty" }

        val results = mutableListOf<SplitResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            items.forEachIndexed { idx, item ->
                var found = false
                var leftId: ClipId? = null
                var rightId: ClipId? = null
                val splitAt = item.atTimelineSeconds.seconds
                tracks = tracks.map { track ->
                    val target = track.clips.firstOrNull { it.id.value == item.clipId }
                        ?: return@map track
                    found = true
                    if (splitAt <= target.timeRange.start || splitAt >= target.timeRange.end) {
                        error(
                            "splitItems[$idx] (${item.clipId}): split point ${item.atTimelineSeconds}s " +
                                "is outside clip ${target.timeRange.start}..${target.timeRange.end}",
                        )
                    }
                    val offset = splitAt - target.timeRange.start
                    val (l, r) = splitClip(target, offset)
                    leftId = l.id
                    rightId = r.id
                    withClips(
                        track,
                        (track.clips.filter { it.id != target.id } + listOf(l, r)).sortedBy { it.timeRange.start },
                    )
                }
                if (!found) error("splitItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                results += SplitResult(
                    originalClipId = item.clipId,
                    leftClipId = leftId!!.value,
                    rightClipId = rightId!!.value,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "split × ${results.size}",
            outputForLlm = "Split ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                action = "split",
                snapshotId = snapshotId.value,
                split = results,
            ),
        )
    }

    private suspend fun executeTrim(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.trimItems ?: error("action=trim requires `trimItems`")
        rejectForeign("trim", input)
        require(items.isNotEmpty()) { "trimItems must not be empty" }
        items.forEachIndexed { idx, item ->
            if (item.newSourceStartSeconds == null && item.newDurationSeconds == null) {
                error(
                    "trimItems[$idx] (${item.clipId}): at least one of newSourceStartSeconds / newDurationSeconds required",
                )
            }
            item.newSourceStartSeconds?.let {
                require(it >= 0.0) {
                    "trimItems[$idx] (${item.clipId}): newSourceStartSeconds must be >= 0 (got $it)"
                }
            }
            item.newDurationSeconds?.let {
                require(it > 0.0) {
                    "trimItems[$idx] (${item.clipId}): newDurationSeconds must be > 0 (got $it)"
                }
            }
        }

        val results = mutableListOf<TrimResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            items.forEachIndexed { idx, item ->
                val hit = tracks.firstNotNullOfOrNull { t ->
                    t.clips.firstOrNull { it.id.value == item.clipId }?.let { t to it }
                } ?: error("trimItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                val (track, clip) = hit

                val (assetId, currentSourceRange) = when (clip) {
                    is Clip.Video -> clip.assetId to clip.sourceRange
                    is Clip.Audio -> clip.assetId to clip.sourceRange
                    is Clip.Text -> error(
                        "trimItems[$idx] (${item.clipId}): action=trim cannot trim a text/subtitle clip; " +
                            "use add_subtitles to reset its time range.",
                    )
                }
                val newSourceStart: Duration = item.newSourceStartSeconds?.seconds ?: currentSourceRange.start
                val newDuration: Duration = item.newDurationSeconds?.seconds ?: currentSourceRange.duration
                val assetDuration = project.assets.firstOrNull { it.id == assetId }?.metadata?.duration
                    ?: error(
                        "trimItems[$idx] (${item.clipId}): asset ${assetId.value} bound to clip not found in project " +
                            "${pid.value} — import it (or restore the clip's source binding) first.",
                    )
                require(newSourceStart < assetDuration) {
                    "trimItems[$idx] (${item.clipId}): newSourceStartSeconds " +
                        "${newSourceStart.toDouble(DurationUnit.SECONDS)} exceeds asset duration " +
                        "${assetDuration.toDouble(DurationUnit.SECONDS)}s."
                }
                require(newSourceStart + newDuration <= assetDuration) {
                    "trimItems[$idx] (${item.clipId}): trim window (start=" +
                        "${newSourceStart.toDouble(DurationUnit.SECONDS)}s + duration=" +
                        "${newDuration.toDouble(DurationUnit.SECONDS)}s) extends past asset duration " +
                        "${assetDuration.toDouble(DurationUnit.SECONDS)}s."
                }
                val timelineRange = TimeRange(clip.timeRange.start, newDuration)
                val sourceRange = TimeRange(newSourceStart, newDuration)
                val trimmed = withTrim(clip, timelineRange, sourceRange)
                tracks = tracks.map { t ->
                    if (t.id == track.id) {
                        withClips(t, t.clips.map { if (it.id == clip.id) trimmed else it }.sortedBy { it.timeRange.start })
                    } else {
                        t
                    }
                }
                results += TrimResult(
                    clipId = item.clipId,
                    trackId = track.id.value,
                    newSourceStartSeconds = newSourceStart.toDouble(DurationUnit.SECONDS),
                    newDurationSeconds = newDuration.toDouble(DurationUnit.SECONDS),
                    newTimelineEndSeconds = timelineRange.end.toDouble(DurationUnit.SECONDS),
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = recomputeDuration(tracks)))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "trim ${results.size} clip(s)",
            outputForLlm = "Trimmed ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                action = "trim",
                snapshotId = snapshotId.value,
                trimmed = results,
            ),
        )
    }

    // --- helpers -------------------------------------------------------------

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

    private fun cloneClip(original: Clip, newId: ClipId, newStart: Duration): Clip {
        val r = TimeRange(newStart, original.timeRange.duration)
        return when (original) {
            is Clip.Video -> original.copy(id = newId, timeRange = r)
            is Clip.Audio -> original.copy(id = newId, timeRange = r)
            is Clip.Text -> original.copy(id = newId, timeRange = r)
        }
    }

    private fun splitClip(c: Clip, offset: Duration): Pair<Clip, Clip> {
        val leftId = ClipId(Uuid.random().toString())
        val rightId = ClipId(Uuid.random().toString())
        val lt = TimeRange(c.timeRange.start, offset)
        val rt = TimeRange(c.timeRange.start + offset, c.timeRange.duration - offset)
        return when (c) {
            is Clip.Video -> {
                val ls = TimeRange(c.sourceRange.start, offset)
                val rs = TimeRange(c.sourceRange.start + offset, c.sourceRange.duration - offset)
                c.copy(id = leftId, timeRange = lt, sourceRange = ls) to c.copy(id = rightId, timeRange = rt, sourceRange = rs)
            }
            is Clip.Audio -> {
                val ls = TimeRange(c.sourceRange.start, offset)
                val rs = TimeRange(c.sourceRange.start + offset, c.sourceRange.duration - offset)
                c.copy(id = leftId, timeRange = lt, sourceRange = ls) to c.copy(id = rightId, timeRange = rt, sourceRange = rs)
            }
            is Clip.Text -> c.copy(id = leftId, timeRange = lt) to c.copy(id = rightId, timeRange = rt)
        }
    }

    private fun trackKindOf(track: Track): String = when (track) {
        is Track.Video -> "video"; is Track.Audio -> "audio"; is Track.Subtitle -> "subtitle"; is Track.Effect -> "effect"
    }

    private fun clipKindOf(clip: Clip): String = when (clip) {
        is Clip.Video -> "video"; is Clip.Audio -> "audio"; is Clip.Text -> "text"
    }

    private fun isKindCompatible(clip: Clip, track: Track): Boolean = when (clip) {
        is Clip.Video -> track is Track.Video
        is Clip.Audio -> track is Track.Audio
        is Clip.Text -> track is Track.Subtitle
    }

    private fun withClips(track: Track, clips: List<Clip>): Track = when (track) {
        is Track.Video -> track.copy(clips = clips)
        is Track.Audio -> track.copy(clips = clips)
        is Track.Subtitle -> track.copy(clips = clips)
        is Track.Effect -> track.copy(clips = clips)
    }

    private fun withTimeRange(clip: Clip, range: TimeRange): Clip = when (clip) {
        is Clip.Video -> clip.copy(timeRange = range)
        is Clip.Audio -> clip.copy(timeRange = range)
        is Clip.Text -> clip.copy(timeRange = range)
    }

    private fun withTrim(c: Clip, timelineRange: TimeRange, sourceRange: TimeRange): Clip = when (c) {
        is Clip.Video -> c.copy(timeRange = timelineRange, sourceRange = sourceRange)
        is Clip.Audio -> c.copy(timeRange = timelineRange, sourceRange = sourceRange)
        is Clip.Text -> error("action=trim cannot trim a text clip")
    }

    private fun replaceClip(track: Track, replacement: Clip): Track = withClips(
        track,
        track.clips.map { if (it.id == replacement.id) replacement else it }.sortedBy { it.timeRange.start },
    )

    private fun Clip.shiftStart(delta: Duration): Clip =
        withTimeRange(this, timeRange.copy(start = timeRange.start + delta))

    private fun recomputeDuration(tracks: List<Track>): Duration =
        tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
}

/** DSL helpers for the JSON Schema builder — keep the schema block compact. */
private fun JsonObjectBuilder.stringProp(name: String, description: String? = null) = putJsonObject(name) {
    put("type", "string")
    if (description != null) put("description", description)
}

private fun JsonObjectBuilder.numberProp(name: String, description: String? = null) = putJsonObject(name) {
    put("type", "number")
    if (description != null) put("description", description)
}

/** `type=array` + `items=object(properties, required, additionalProperties=false)` — one shape per *Items payload. */
private fun JsonObjectBuilder.itemArray(
    description: String,
    required: List<String>,
    props: JsonObjectBuilder.() -> Unit,
) {
    put("type", "array")
    put("description", description)
    putJsonObject("items") {
        put("type", "object")
        putJsonObject("properties", props)
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
}
