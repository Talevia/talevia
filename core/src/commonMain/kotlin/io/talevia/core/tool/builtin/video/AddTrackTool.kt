package io.talevia.core.tool.builtin.video

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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Create an empty track of a given kind. `add_clip` / `add_subtitles` auto-create
 * the *first* track of the needed kind on demand, which is convenient for
 * single-layer edits but makes it impossible to express "I want N parallel
 * tracks" up front:
 *
 *  - Picture-in-picture: two video tracks, foreground over background.
 *  - Multi-stem audio: dialogue / music / ambient on separate tracks so level
 *    changes on one don't bleed into another.
 *  - Subtitle variants: localised subtitles on separate tracks switchable at
 *    render time.
 *
 * With this tool the agent can declare the track layout explicitly before
 * authoring clips, and `add_clip(trackId=…)` lands clips on the named track.
 * Track ids are free-form strings (uuid by default); pass `trackId` to control
 * the value so later references are stable.
 *
 * Does NOT mutate clip contents or timeline duration — an empty track is a
 * no-op at render time. Still emits a `Part.TimelineSnapshot` so
 * `revert_timeline` can undo (the next track it would auto-create might have
 * a different id, and the agent may have told the user about this one).
 */
@OptIn(ExperimentalUuidApi::class)
class AddTrackTool(
    private val store: ProjectStore,
) : Tool<AddTrackTool.Input, AddTrackTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** `video`, `audio`, `subtitle`, or `effect`. Case-insensitive. */
        val trackKind: String,
        val trackId: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val trackId: String,
        val trackKind: String,
        val totalTrackCount: Int,
    )

    override val id: String = "add_track"
    override val helpText: String =
        "Create an empty track of a given kind (video / audio / subtitle / effect). Use when you " +
            "need multiple parallel tracks — picture-in-picture, multi-stem audio, localised " +
            "subtitles — before adding clips. Rejects a trackId that already exists. Emits a " +
            "timeline snapshot so revert_timeline can undo."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
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
            putJsonObject("trackKind") {
                put("type", "string")
                put("description", "One of: video, audio, subtitle, effect (case-insensitive).")
            }
            putJsonObject("trackId") {
                put("type", "string")
                put(
                    "description",
                    "Optional explicit id. Defaults to a generated UUID. Fails if an existing track has the same id.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("trackKind"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val normalisedKind = input.trackKind.trim().lowercase()
        require(normalisedKind in ACCEPTED_KINDS) {
            "unknown trackKind '${input.trackKind}'; accepted: ${ACCEPTED_KINDS.joinToString()}"
        }
        val requestedId = input.trackId?.trim()?.takeIf { it.isNotEmpty() }
        val newId = requestedId ?: Uuid.random().toString()

        val pid = ctx.resolveProjectId(input.projectId)
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
        val out = Output(
            projectId = pid.value,
            trackId = newId,
            trackKind = normalisedKind,
            totalTrackCount = totalCount,
        )
        return ToolResult(
            title = "add $normalisedKind track",
            outputForLlm = "Added empty $normalisedKind track $newId to project ${pid.value} " +
                "($totalCount total track(s)). Timeline snapshot: ${snapshotId.value}",
            data = out,
        )
    }

    private companion object {
        private val ACCEPTED_KINDS = setOf("video", "audio", "subtitle", "effect")
    }
}
