package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.ProjectStore
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

/**
 * Reorder the project's tracks without changing clip placement or track
 * contents. The timeline engines composite tracks in index order — the
 * first track is drawn on the bottom, later tracks layer on top — so the
 * agent uses this tool to fix PiP stacking, audio sub-mix priority, and
 * subtitle-language fallback order after tracks were created in the
 * wrong order.
 *
 * Input is a **partial** ordering. Listed ids move to the front in the
 * given order; unlisted tracks keep their current relative positions at
 * the tail. Partial is the cheapest contract for the agent:
 *
 * - "Move the foreground PiP track to the top" → list the fg track id.
 * - "Ensure dialogue / music / ambient in that order" → list those three.
 * - Full reorder → list every id.
 *
 * Duplicated ids and unknown ids fail loud (typos surface loudly instead
 * of producing surprising stacks).
 *
 * No clips are moved; no snapshots beyond one `Part.TimelineSnapshot` so
 * `revert_timeline` can unwind the reorder. The underlying data model
 * stores tracks as an ordered [List] — this tool is the only way the
 * agent should mutate that order (direct JSON edits would bypass
 * permission + snapshot plumbing).
 */
class ReorderTracksTool(
    private val store: ProjectStore,
) : Tool<ReorderTracksTool.Input, ReorderTracksTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /**
         * Track ids in the desired stacking order. First id becomes the
         * bottom track (index 0). Unlisted tracks keep their current
         * relative order at the tail. Empty list is a no-op (and rejected
         * as a likely mistake, so the agent learns to pass something).
         */
        val trackIds: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val newOrder: List<String>,
    )

    override val id: String = "reorder_tracks"
    override val helpText: String =
        "Reorder timeline tracks. Listed trackIds move to the front in the given order; unlisted " +
            "tracks keep their current relative positions at the tail. Use to control PiP " +
            "stacking (foreground on top), audio sub-mix priority, subtitle-language fallback. " +
            "Does not move clips. Duplicates / unknown ids fail loud. Emits a timeline snapshot."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("trackIds") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put(
                    "description",
                    "Partial or full ordering. First id becomes the bottom track; unlisted tracks keep their relative tail order.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("trackIds"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.trackIds.isNotEmpty()) {
            "trackIds must not be empty — nothing to reorder. Omit this tool entirely if the order is already correct."
        }
        val dedup = input.trackIds.toSet()
        require(dedup.size == input.trackIds.size) {
            "trackIds contains duplicates: ${input.trackIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
        }

        var newOrderOut: List<String> = emptyList()

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val tracks = project.timeline.tracks
            val byId = tracks.associateBy { it.id.value }

            val missing = input.trackIds.filter { it !in byId }
            require(missing.isEmpty()) {
                "Unknown track id(s): ${missing.joinToString(", ")}. Known: ${byId.keys.joinToString(", ")}"
            }

            val front = input.trackIds.map { byId.getValue(it) }
            val frontIdSet = input.trackIds.map { TrackId(it) }.toSet()
            val tail = tracks.filterNot { it.id in frontIdSet } // preserves existing relative order
            val reordered = front + tail
            newOrderOut = reordered.map { it.id.value }
            project.copy(timeline = project.timeline.copy(tracks = reordered))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "reorder tracks (${input.trackIds.size} pinned)",
            outputForLlm = "Track order is now: ${newOrderOut.joinToString(", ")}. " +
                "Timeline snapshot: ${snapshotId.value}",
            data = Output(projectId = input.projectId, newOrder = newOrderOut),
        )
    }
}
