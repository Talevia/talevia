package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaStorage
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
 * Swap the asset that an existing clip plays, preserving its position, transforms,
 * and filters. Closes the regenerate-after-stale loop (VISION §3.2):
 *
 *   1. user edits a `character_ref`
 *   2. agent calls `find_stale_clips` → list of stale clipIds
 *   3. agent calls `generate_image` (or other AIGC tool) with the same bindings → new assetId
 *   4. agent calls `replace_clip(clipId, newAssetId)` — the old asset is unhooked and
 *      the clip continues to occupy the same timeline slot.
 *
 * Without this tool the agent would have to delete-then-add (no delete tool exists)
 * or `add_clip` again (creating a duplicate) — both of which break the workflow.
 *
 * Side effect on `Clip.sourceBinding`: if the new asset has a lockfile entry with a
 * non-empty `sourceBinding`, we copy that into the replaced clip. This means
 * regenerated clips become *correctly* DAG-aware even though `add_clip` itself
 * doesn't thread bindings — the clip becomes properly reactive to *future* source
 * edits. Imported assets (no lockfile entry) leave the binding untouched.
 *
 * Only the asset id and source binding change. To resize the clip, use other tools
 * — duration / sourceRange are preserved deliberately so re-render keeps timing.
 * Audio clips are supported the same way; text clips don't have an asset and are
 * rejected loudly.
 */
class ReplaceClipTool(
    private val store: ProjectStore,
    private val media: MediaStorage,
) : Tool<ReplaceClipTool.Input, ReplaceClipTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val clipId: String,
        val newAssetId: String,
    )

    @Serializable data class Output(
        val clipId: String,
        val previousAssetId: String,
        val newAssetId: String,
        val sourceBindingIds: List<String>,
    )

    override val id: String = "replace_clip"
    override val helpText: String =
        "Swap the asset on an existing clip without changing its timeline position, " +
            "transforms, or filters. Use after find_stale_clips + a regeneration tool to " +
            "splice the regenerated asset back in. The clip's sourceBinding is updated " +
            "from the new asset's lockfile entry when present, so future stale-clip " +
            "detection sees the regenerated bindings."
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
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("newAssetId") {
                put("type", "string")
                put("description", "The replacement asset; must already exist in the project's asset catalog (e.g. from a fresh generate_image call).")
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("clipId"),
                    JsonPrimitive("newAssetId"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        val newAssetId = AssetId(input.newAssetId)
        media.get(newAssetId) ?: error("Asset ${input.newAssetId} not found; import or generate it first.")

        var previousAssetId: String? = null
        var newBinding: Set<SourceNodeId> = emptySet()

        val updated = store.mutate(pid) { project ->
            // The lockfile lookup runs *inside* mutate so we read the same Project
            // snapshot the rewrite is built on.
            val lockBinding = project.lockfile.findByAssetId(newAssetId)?.sourceBinding ?: emptySet()
            newBinding = lockBinding

            var found = false
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId } ?: return@map track
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
                    is Clip.Text -> error("replace_clip does not apply to text clips (no underlying asset).")
                }
                replaceClipOnTrack(track, target, replaced)
            }
            if (!found) error("clip ${input.clipId} not found in project ${pid.value}")
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val out = Output(
            clipId = input.clipId,
            previousAssetId = previousAssetId ?: error("internal: previousAssetId not captured"),
            newAssetId = newAssetId.value,
            sourceBindingIds = newBinding.map { it.value },
        )
        val bindingNote = if (newBinding.isEmpty()) {
            ""
        } else {
            " Copied sourceBinding ${out.sourceBindingIds} from new asset's lockfile entry."
        }
        return ToolResult(
            title = "replace clip ${input.clipId}",
            outputForLlm = "Replaced asset on clip ${input.clipId}: ${out.previousAssetId} → ${out.newAssetId}.$bindingNote " +
                "Timeline snapshot: ${snapshotId.value}",
            data = out,
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
