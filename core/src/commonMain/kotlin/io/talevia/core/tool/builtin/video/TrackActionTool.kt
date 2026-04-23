package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.ProjectStore
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Add or remove tracks on the timeline — the consolidated
 * action-dispatched form that replaces `AddTrackTool` + `RemoveTrackTool`
 * (`debt-consolidate-video-add-remove-verbs-tracks`, following the same
 * pattern as cycles 19 → 23: `transition_action`, `filter_action`,
 * `project_snapshot_action`, `project_maintenance_action`,
 * `session_action`).
 *
 * ## Input contract
 *
 * - `action` = `"add"` or `"remove"` (required).
 * - `action="add"`: required `trackKind` (`video` / `audio` / `subtitle`
 *   / `effect`, case-insensitive); optional `trackId` (defaults to a
 *   fresh UUID; fails if the id already exists). Does NOT mutate clip
 *   contents — an empty track is a no-op at render time. Still emits a
 *   `Part.TimelineSnapshot` so `revert_timeline` can undo.
 * - `action="remove"`: required `trackIds` (list, ≥ 1); optional `force`
 *   (default false). Non-empty tracks require `force=true`; otherwise
 *   the whole batch aborts with the offending track's clip count so
 *   the agent can retry with `force=true` or remove clips first. One
 *   snapshot per batch.
 *
 * ## Permission tier split
 *
 * `add` uses `timeline.write` (non-destructive — empty track adds no
 * content). `remove` uses `project.destructive` (may discard clips
 * with `force=true`; even empty-track drops the agent may have
 * declared with downstream intent count as destructive). Dispatched
 * via [PermissionSpec.permissionFrom] (cycle 21 extension). Base
 * tier is `timeline.write` so an unknown / malformed action-field
 * lands on the safer (lower-friction) tier — `execute()`'s
 * `require(...)` rejects unknown actions before touching the store,
 * so the tier on unknown input is never actually exercised, and
 * biasing toward the non-destructive default keeps the common add
 * path free of ASK friction.
 */
@OptIn(ExperimentalUuidApi::class)
class TrackActionTool(
    private val store: ProjectStore,
) : Tool<TrackActionTool.Input, TrackActionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** `"add"` or `"remove"`. Case-sensitive. */
        val action: String,
        /** Add-only. `video` / `audio` / `subtitle` / `effect`; case-insensitive. */
        val trackKind: String? = null,
        /**
         * Add-only. Optional explicit id. Defaults to a generated UUID.
         * Fails if an existing track has the same id.
         */
        val trackId: String? = null,
        /** Remove-only. Track ids to drop. At least one required. */
        val trackIds: List<String> = emptyList(),
        /**
         * Remove-only. Drop tracks even when they hold clips (every clip
         * on them is discarded too). Default `false` throws if any
         * target track is non-empty, leaving the whole batch untouched.
         */
        val force: Boolean = false,
    )

    @Serializable data class RemoveItemResult(
        val trackId: String,
        val trackKind: String,
        val droppedClipCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        /** Add-only: id of the newly-created track. Empty on remove. */
        val trackId: String = "",
        /** Add-only: kind of the newly-created track. Empty on remove. */
        val trackKind: String = "",
        /** Add-only: track count after the add. Zero on remove. */
        val totalTrackCount: Int = 0,
        /** Remove-only: per-track removal summary. Empty on add. */
        val results: List<RemoveItemResult> = emptyList(),
        /** Remove-only: echoes the request's `force` flag. False on add. */
        val forced: Boolean = false,
        /** Both: id of the emitted `Part.TimelineSnapshot`. */
        val snapshotId: String = "",
    )

    override val id: String = "track_action"
    override val helpText: String =
        "Add or remove tracks on the timeline atomically. " +
            "Pick `action=\"add\"` + `trackKind` (video / audio / subtitle / effect) to create an " +
            "empty track before authoring clips — enables picture-in-picture, multi-stem audio, " +
            "localised subtitles; defaults a fresh UUID or respects a passed `trackId`. " +
            "Pick `action=\"remove\"` + required `trackIds` to drop one or many tracks; non-empty " +
            "tracks require `force=true` (every clip on them is discarded), otherwise the whole " +
            "batch aborts with the offending clip count. One timeline snapshot per call so " +
            "`revert_timeline` walks each action back in one step."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "timeline.write",
        permissionFrom = { inputJson ->
            if (isRemoveAction(inputJson)) "project.destructive" else "timeline.write"
        },
    )
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

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
                put("description", "`add` to create an empty track; `remove` to drop one or many tracks.")
                put("enum", JsonArray(listOf(JsonPrimitive("add"), JsonPrimitive("remove"))))
            }
            putJsonObject("trackKind") {
                put("type", "string")
                put("description", "Add-only. One of: video, audio, subtitle, effect (case-insensitive).")
            }
            putJsonObject("trackId") {
                put("type", "string")
                put(
                    "description",
                    "Add-only. Optional explicit id. Defaults to a generated UUID. " +
                        "Fails if an existing track has the same id.",
                )
            }
            putJsonObject("trackIds") {
                put("type", "array")
                put("description", "Remove-only. Track ids to drop. At least one required.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("force") {
                put("type", "boolean")
                put(
                    "description",
                    "Remove-only. Drop tracks even if they hold clips (discards those clips). Default false.",
                )
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
            else -> error("unknown action '${input.action}'; accepted: add, remove")
        }
    }

    private suspend fun executeAdd(
        pid: ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val trackKindRaw = input.trackKind
            ?: error("trackKind is required when action=add")
        val normalisedKind = trackKindRaw.trim().lowercase()
        require(normalisedKind in ACCEPTED_KINDS) {
            "unknown trackKind '$trackKindRaw'; accepted: ${ACCEPTED_KINDS.joinToString()}"
        }
        val requestedId = input.trackId?.trim()?.takeIf { it.isNotEmpty() }
        val newId = requestedId ?: Uuid.random().toString()

        var totalCount = 0
        val updated = store.mutate(pid) { project ->
            if (project.timeline.tracks.any { it.id.value == newId }) {
                error("trackId '$newId' already exists in project ${pid.value}")
            }
            val tid = TrackId(newId)
            val newTrack: Track = when (normalisedKind) {
                "video" -> Track.Video(id = tid)
                "audio" -> Track.Audio(id = tid)
                "subtitle" -> Track.Subtitle(id = tid)
                "effect" -> Track.Effect(id = tid)
                else -> error("unreachable")
            }
            val tracks = project.timeline.tracks + newTrack
            totalCount = tracks.size
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "add $normalisedKind track",
            outputForLlm = "Added empty $normalisedKind track $newId to project ${pid.value} " +
                "($totalCount total track(s)). Timeline snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                action = "add",
                trackId = newId,
                trackKind = normalisedKind,
                totalTrackCount = totalCount,
                snapshotId = snapshotId.value,
            ),
        )
    }

    private suspend fun executeRemove(
        pid: ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        require(input.trackIds.isNotEmpty()) { "trackIds must not be empty" }
        val results = mutableListOf<RemoveItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.trackIds.forEachIndexed { idx, trackId ->
                val target = tracks.firstOrNull { it.id.value == trackId }
                    ?: error("trackIds[$idx] '$trackId' not found in project ${pid.value}")
                if (target.clips.isNotEmpty() && !input.force) {
                    error(
                        "trackIds[$idx] '$trackId' has ${target.clips.size} clip(s); pass " +
                            "force=true to drop the track(s) and their clips, or remove the clips first",
                    )
                }
                val kind = trackKind(target)
                val droppedClips = target.clips.size
                tracks = tracks.filter { it.id.value != trackId }
                results += RemoveItemResult(
                    trackId = trackId,
                    trackKind = kind,
                    droppedClipCount = droppedClips,
                )
            }
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val summary = buildString {
            append("Dropped ${results.size} track(s)")
            val totalClips = results.sumOf { it.droppedClipCount }
            if (totalClips > 0) append(" with $totalClips clip(s)")
            append(". Timeline snapshot: ${snapshotId.value}")
        }
        return ToolResult(
            title = "remove ${results.size} track(s)",
            outputForLlm = summary,
            data = Output(
                projectId = pid.value,
                action = "remove",
                results = results,
                forced = input.force,
                snapshotId = snapshotId.value,
            ),
        )
    }

    private fun trackKind(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }

    private companion object {
        private val ACCEPTED_KINDS = setOf("video", "audio", "subtitle", "effect")

        /**
         * Regex-match `"action":"remove"` (allowing whitespace) in the raw
         * input JSON. Runs before kotlinx.serialization decode for
         * [PermissionSpec.permissionFrom]'s tier gate — we cannot rely on
         * the typed Input here because the permission tier is resolved
         * before the dispatcher deserialises. Malformed input (missing /
         * non-string action) falls through to the lower `timeline.write`
         * tier, matching the tool's safer-default rationale.
         */
        private val REMOVE_ACTION_REGEX = Regex(
            "\"action\"\\s*:\\s*\"remove\"",
        )

        private fun isRemoveAction(inputJson: String): Boolean =
            REMOVE_ACTION_REGEX.containsMatchIn(inputJson)
    }
}
