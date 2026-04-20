package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
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
 * Clone a whole track — same kind, fresh ids, every clip copied with a fresh
 * [ClipId] but all attached state preserved (filters, transforms,
 * sourceBinding, audio envelope, text style).
 *
 * [DuplicateClipTool] handles the single-clip case and [AddTrackTool] creates
 * an empty track — whole-track cloning sat between them with no direct path.
 * Concretely it unlocks:
 *
 *  - A/B a whole dialogue track: duplicate "dialogue", tweak volume / fade on
 *    the copy, compare by muting one or the other.
 *  - Mirror a subtitle track for a localisation variant: duplicate the English
 *    subtitles, then edit each text clip's body — timing and placement are
 *    carried across verbatim.
 *
 * The cloned track is **appended** to the timeline (not inserted adjacent to
 * the source). Appending is predictable; the agent can re-order afterwards if
 * needed, and a stable position avoids surprising off-by-one track indexing
 * when the LLM refers back to an existing layout.
 *
 * [Input.newTrackId] is optional. When omitted a fresh stable id is chosen of
 * the form `${sourceTrackId}-copy-${n}` where `n` is the smallest non-negative
 * integer that doesn't collide with an existing track id — this stays
 * readable in transcripts and survives replay, unlike a random UUID.
 * When provided explicitly, a collision with any existing track id throws.
 *
 * Emits a [io.talevia.core.session.Part.TimelineSnapshot] so `revert_timeline`
 * can undo the clone in one call.
 */
@OptIn(ExperimentalUuidApi::class)
class DuplicateTrackTool(
    private val store: ProjectStore,
) : Tool<DuplicateTrackTool.Input, DuplicateTrackTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val sourceTrackId: String,
        /**
         * Optional explicit id for the cloned track. Must not collide with an
         * existing track id. Omit to auto-generate `${sourceTrackId}-copy-${n}`.
         */
        val newTrackId: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val sourceTrackId: String,
        val newTrackId: String,
        val clipCount: Int,
    )

    override val id: String = "duplicate_track"
    override val helpText: String =
        "Clone a whole track — same kind, fresh ids, every clip copied with a fresh ClipId but " +
            "all attached state preserved (filters, transforms, source bindings, audio envelope, " +
            "text style). The new track is appended to the timeline. Optional newTrackId must not " +
            "collide with an existing track. Emits a timeline snapshot."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("sourceTrackId") { put("type", "string") }
            putJsonObject("newTrackId") {
                put("type", "string")
                put(
                    "description",
                    "Optional explicit id for the cloned track. Defaults to '<sourceTrackId>-copy-<n>'. " +
                        "Fails if an existing track has the same id.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("sourceTrackId"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var resolvedNewId = ""
        var clipCount = 0
        var sourceKind = ""

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val source = project.timeline.tracks.firstOrNull { it.id.value == input.sourceTrackId }
                ?: error("sourceTrackId '${input.sourceTrackId}' not found in project ${input.projectId}")

            val existingIds = project.timeline.tracks.map { it.id.value }.toSet()
            val chosenId = if (input.newTrackId != null) {
                val requested = input.newTrackId
                require(requested !in existingIds) {
                    "newTrackId '$requested' collides with an existing track in project ${input.projectId}"
                }
                requested
            } else {
                generateCopyId(input.sourceTrackId, existingIds)
            }

            val clonedClips = source.clips.map { cloneClip(it) }
            val cloned: Track = rebuildTrackWithNewId(source, TrackId(chosenId), clonedClips)

            resolvedNewId = chosenId
            clipCount = clonedClips.size
            sourceKind = trackKindOf(source)

            val tracks = project.timeline.tracks + cloned
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val out = Output(
            projectId = input.projectId,
            sourceTrackId = input.sourceTrackId,
            newTrackId = resolvedNewId,
            clipCount = clipCount,
        )
        return ToolResult(
            title = "duplicate track ${input.sourceTrackId} → $resolvedNewId",
            outputForLlm = "Duplicated $sourceKind track ${input.sourceTrackId} as $resolvedNewId " +
                "with $clipCount clip(s) in project ${input.projectId}. Timeline snapshot: ${snapshotId.value}",
            data = out,
        )
    }

    private fun generateCopyId(sourceId: String, existing: Set<String>): String {
        var n = 1
        while (true) {
            val candidate = "$sourceId-copy-$n"
            if (candidate !in existing) return candidate
            n++
        }
    }

    private fun cloneClip(original: Clip): Clip {
        val newId = ClipId(Uuid.random().toString())
        return when (original) {
            is Clip.Video -> original.copy(id = newId)
            is Clip.Audio -> original.copy(id = newId)
            is Clip.Text -> original.copy(id = newId)
        }
    }

    private fun rebuildTrackWithNewId(source: Track, newId: TrackId, clips: List<Clip>): Track = when (source) {
        is Track.Video -> Track.Video(id = newId, clips = clips)
        is Track.Audio -> Track.Audio(id = newId, clips = clips)
        is Track.Subtitle -> Track.Subtitle(id = newId, clips = clips)
        is Track.Effect -> Track.Effect(id = newId, clips = clips)
    }

    private fun trackKindOf(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }
}
