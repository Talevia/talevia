package io.talevia.core.tool.builtin.video

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
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

/**
 * Rebind one or many existing clips to a new set of
 * [io.talevia.core.domain.source.SourceNode] ids — or clear the binding.
 * Per-item shape: each entry carries its own clipId + replacement binding list.
 *
 * Semantics:
 *  - `sourceBinding` is the full replacement set per item. An empty list
 *    clears that clip's binding.
 *  - Every provided source-node id must resolve in `project.source.byId`.
 *    Unknown ids (across any item) fail-loud with the full missing set.
 *  - Works uniformly across all three Clip variants (Video / Audio / Text).
 *  - Everything else on the clip — timeRange, sourceRange, filters,
 *    transforms, asset, volume, text, style — is preserved.
 *  - All-or-nothing. One `Part.TimelineSnapshot` per call.
 */
class SetClipSourceBindingTool(
    private val store: ProjectStore,
) : Tool<SetClipSourceBindingTool.Input, SetClipSourceBindingTool.Output> {

    @Serializable data class Item(
        val clipId: String,
        /** Full replacement set of source-node ids. Empty list clears the binding. */
        val sourceBinding: List<String>,
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
        val clipId: String,
        val previousBinding: List<String>,
        val newBinding: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id: String = "set_clip_source_bindings"
    override val helpText: String =
        "Replace (or clear) one or many clips' sourceBinding — the set of source-node ids each " +
            "clip derives from. Use this to retroactively tie hand-authored clips to a " +
            "character_ref, or swap which upstream nodes clips depend on, without losing clip " +
            "ids, filters, or transforms. Empty list clears a clip's binding. All-or-nothing: " +
            "unknown source-node ids in any item abort the whole batch."
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
            putJsonObject("items") {
                put("type", "array")
                put("description", "Rebind operations. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("sourceBinding") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                            put(
                                "description",
                                "Full replacement set of source-node ids. Empty list clears the binding.",
                            )
                        }
                    }
                    put(
                        "required",
                        JsonArray(listOf(JsonPrimitive("clipId"), JsonPrimitive("sourceBinding"))),
                    )
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
                val newBindingSet = item.sourceBinding.map { SourceNodeId(it) }.toSet()
                val missing = newBindingSet.filter { it !in project.source.byId }
                require(missing.isEmpty()) {
                    "items[$idx] (${item.clipId}): unknown source node ids: " +
                        missing.joinToString(", ") { it.value }
                }

                val hit = tracks.firstNotNullOfOrNull { track ->
                    track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
                } ?: error("items[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                val (track, clip) = hit
                val previousBinding = clip.sourceBinding.map { it.value }.sorted()

                val rebound: Clip = when (clip) {
                    is Clip.Video -> clip.copy(sourceBinding = newBindingSet)
                    is Clip.Audio -> clip.copy(sourceBinding = newBindingSet)
                    is Clip.Text -> clip.copy(sourceBinding = newBindingSet)
                }
                val rebuilt = track.clips.map { if (it.id == clip.id) rebound else it }
                val newTrack = when (track) {
                    is Track.Video -> track.copy(clips = rebuilt)
                    is Track.Audio -> track.copy(clips = rebuilt)
                    is Track.Subtitle -> track.copy(clips = rebuilt)
                    is Track.Effect -> track.copy(clips = rebuilt)
                }
                tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
                results += ItemResult(
                    clipId = item.clipId,
                    previousBinding = previousBinding,
                    newBinding = newBindingSet.map { it.value }.sorted(),
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "rebind × ${results.size}",
            outputForLlm = "Rebound source bindings on ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
        )
    }
}
