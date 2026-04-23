package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
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
 * Swap the asset on one or many clips atomically, preserving position,
 * transforms, and filters. Per-item shape so a single call can splice in
 * several regenerated assets after a stale-clip cascade (VISION §3.2).
 *
 * Side effect per item: if the new asset has a lockfile entry with a
 * non-empty `sourceBinding`, that's copied onto the replaced clip so future
 * stale-clip detection is DAG-aware.
 *
 * Only assetId + sourceBinding change per clip — duration / sourceRange are
 * preserved. Audio clips supported; text clips rejected loudly.
 * All-or-nothing; one snapshot per call.
 */
class ReplaceClipTool(
    private val store: ProjectStore,
) : Tool<ReplaceClipTool.Input, ReplaceClipTool.Output> {

    @Serializable data class Item(
        val clipId: String,
        val newAssetId: String,
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
        val previousAssetId: String,
        val newAssetId: String,
        val sourceBindingIds: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id: String = "replace_clips"
    override val helpText: String =
        "Swap the asset on one or many clips atomically, preserving timeline position, " +
            "transforms, and filters. Each item is { clipId, newAssetId }. Useful after " +
            "find_stale_clips + regeneration to splice in the new assets. Each clip's " +
            "sourceBinding is updated from the new asset's lockfile entry when present. " +
            "All-or-nothing; one snapshot per call."
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
                put("description", "Replace operations. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("newAssetId") {
                            put("type", "string")
                            put("description", "Replacement asset; must already exist in the project's asset catalog.")
                        }
                    }
                    put(
                        "required",
                        JsonArray(listOf(JsonPrimitive("clipId"), JsonPrimitive("newAssetId"))),
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
            input.items.forEachIndexed { idx, item ->
                if (project.assets.none { it.id.value == item.newAssetId }) {
                    error(
                        "items[$idx] (${item.clipId}): asset ${item.newAssetId} not found in project " +
                            "${pid.value}; import or generate it first.",
                    )
                }
            }
            var tracks = project.timeline.tracks
            input.items.forEachIndexed { idx, item ->
                val newAssetId = AssetId(item.newAssetId)
                val lockBinding: Set<SourceNodeId> =
                    project.lockfile.findByAssetId(newAssetId)?.sourceBinding ?: emptySet()
                var previousAssetId: String? = null
                var found = false
                tracks = tracks.map { track ->
                    val target = track.clips.firstOrNull { it.id.value == item.clipId } ?: return@map track
                    found = true
                    val replaced: Clip = when (target) {
                        is Clip.Video -> {
                            previousAssetId = target.assetId.value
                            target.copy(assetId = newAssetId, sourceBinding = lockBinding)
                        }
                        is Clip.Audio -> {
                            previousAssetId = target.assetId.value
                            target.copy(assetId = newAssetId, sourceBinding = lockBinding)
                        }
                        is Clip.Text -> error(
                            "items[$idx] (${item.clipId}): replace_clips does not apply to text clips " +
                                "(no underlying asset).",
                        )
                    }
                    replaceClipOnTrack(track, target, replaced)
                }
                if (!found) error("items[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                results += ItemResult(
                    clipId = item.clipId,
                    previousAssetId = previousAssetId ?: error("internal: previousAssetId not captured"),
                    newAssetId = newAssetId.value,
                    sourceBindingIds = lockBinding.map { it.value },
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "replace × ${results.size}",
            outputForLlm = "Replaced asset on ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
        )
    }

    private fun replaceClipOnTrack(track: Track, removed: Clip, replacement: Clip): Track {
        val clips = track.clips.map { if (it.id == removed.id) replacement else it }
        return when (track) {
            is Track.Video -> track.copy(clips = clips)
            is Track.Audio -> track.copy(clips = clips)
            is Track.Subtitle -> track.copy(clips = clips)
            is Track.Effect -> track.copy(clips = clips)
        }
    }
}
