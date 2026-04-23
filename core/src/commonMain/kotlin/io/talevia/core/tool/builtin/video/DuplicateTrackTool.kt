package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
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
 * Clone one or many whole tracks atomically. Per-item shape: each entry
 * carries its own sourceTrackId + optional newTrackId.
 *
 * Per item: same kind, fresh ids, every clip copied with a fresh [ClipId] but
 * all attached state preserved (filters, transforms, sourceBinding, audio
 * envelope, text style).
 *
 * Cloned tracks are **appended** to the timeline in the order listed. Optional
 * per-item `newTrackId` must not collide with any existing track or any
 * earlier-in-batch clone; omit to auto-generate `${sourceTrackId}-copy-${n}`.
 *
 * All-or-nothing; one snapshot per call.
 */
@OptIn(ExperimentalUuidApi::class)
class DuplicateTrackTool(
    private val store: ProjectStore,
) : Tool<DuplicateTrackTool.Input, DuplicateTrackTool.Output> {

    @Serializable data class Item(
        val sourceTrackId: String,
        /**
         * Optional explicit id for the cloned track. Must not collide with an
         * existing track id nor with another item's newTrackId in the same batch.
         * Omit to auto-generate `${sourceTrackId}-copy-${n}`.
         */
        val newTrackId: String? = null,
    )

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val items: List<Item>,
    )

    @Serializable data class ItemResult(
        val sourceTrackId: String,
        val newTrackId: String,
        val clipCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id: String = "duplicate_tracks"
    override val helpText: String =
        "Clone one or many whole tracks atomically. Each item is { sourceTrackId, newTrackId? }. " +
            "Every clip on the source gets a fresh ClipId but all attached state preserved. Cloned " +
            "tracks are appended in the order listed. Optional newTrackId must not collide with " +
            "existing or earlier-in-batch tracks; omit to auto-generate '<sourceTrackId>-copy-<n>'. " +
            "All-or-nothing; one snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")

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
            putJsonObject("items") {
                put("type", "array")
                put("description", "Track duplications. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sourceTrackId") { put("type", "string") }
                        putJsonObject("newTrackId") {
                            put("type", "string")
                            put(
                                "description",
                                "Optional explicit id for the cloned track. Defaults to " +
                                    "'<sourceTrackId>-copy-<n>'. Fails if collides with existing " +
                                    "or earlier-in-batch track.",
                            )
                        }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("sourceTrackId"))))
                    put("additionalProperties", false)
                }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("items"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.items.isNotEmpty()) { "items must not be empty" }
        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.items.forEachIndexed { idx, item ->
                val source = tracks.firstOrNull { it.id.value == item.sourceTrackId }
                    ?: error("items[$idx]: sourceTrackId '${item.sourceTrackId}' not found in project ${pid.value}")

                val existingIds = tracks.map { it.id.value }.toSet()
                val chosenId = if (item.newTrackId != null) {
                    val requested = item.newTrackId
                    require(requested !in existingIds) {
                        "items[$idx] (${item.sourceTrackId}): newTrackId '$requested' collides with an " +
                            "existing track in project ${pid.value}"
                    }
                    requested
                } else {
                    generateCopyId(item.sourceTrackId, existingIds)
                }

                val clonedClips = source.clips.map { cloneClip(it) }
                val cloned: Track = rebuildTrackWithNewId(source, TrackId(chosenId), clonedClips)

                tracks = tracks + cloned
                results += ItemResult(
                    sourceTrackId = item.sourceTrackId,
                    newTrackId = chosenId,
                    clipCount = clonedClips.size,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "duplicate ${results.size} track(s)",
            outputForLlm = "Duplicated ${results.size} track(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
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
}
