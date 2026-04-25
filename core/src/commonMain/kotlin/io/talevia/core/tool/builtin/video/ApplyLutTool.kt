package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.consistency.asStyleBible
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
 * Apply a 3D LUT (`.cube` / `.3dl`) to one or many video clips in a single
 * atomic edit. The same LUT source is broadcast to every `clipIds` entry —
 * matching `apply_filter`'s broadcast pattern.
 *
 * The LUT comes from *either*:
 *  - `lutAssetId` — the asset id of an imported LUT file (direct-use), or
 *  - `styleBibleId` — a `core.consistency.style_bible` node that carries
 *    `lutReference`. Resolved once per call at apply time.
 *
 * VISION §3.3 names `style_bible.lutReference` as the canonical home for a
 * project-global LUT. The style_bible path also attaches the style_bible's
 * nodeId to each clip's `sourceBinding` so future staleness machinery can
 * propagate edits through the DAG.
 *
 * Implementation details:
 * - `Filter.assetId` carries the LUT asset id (a dedicated filter name
 *   `"lut"` avoids fighting the existing numeric-param contract on `Filter`).
 * - Exactly one of `lutAssetId` / `styleBibleId` must be provided.
 * - Non-video clipIds or unresolvable clipIds abort the whole batch.
 * - One `Part.TimelineSnapshot` per call.
 */
class ApplyLutTool(
    private val store: ProjectStore,
) : Tool<ApplyLutTool.Input, ApplyLutTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val clipIds: List<String>,
        val lutAssetId: String? = null,
        val styleBibleId: String? = null,
    )

    @Serializable data class ItemResult(
        val clipId: String,
        val filterCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val lutAssetId: String,
        val styleBibleId: String?,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id: String = "apply_lut"
    override val helpText: String =
        "Apply a 3D LUT to one or many video clips atomically. Pass `lutAssetId` for a direct " +
            "LUT, or `styleBibleId` to pull the LUT from a style_bible node's lutReference. " +
            "Exactly one of the two. The style_bible path also binds each clip to the style_bible " +
            "node so future edits cascade through stale-clip detection. All-or-nothing."
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
            putJsonObject("clipIds") {
                put("type", "array")
                put("description", "Video clip ids to apply the LUT to. At least one.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("lutAssetId") {
                put("type", "string")
                put("description", "Asset id of an imported LUT (.cube / .3dl). Mutually exclusive with styleBibleId.")
            }
            putJsonObject("styleBibleId") {
                put("type", "string")
                put(
                    "description",
                    "Source-node id of a core.consistency.style_bible. Its lutReference is resolved at apply time. " +
                        "Mutually exclusive with lutAssetId.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("clipIds"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.clipIds.isNotEmpty()) { "clipIds must not be empty" }
        val lutArg = input.lutAssetId?.takeIf { it.isNotBlank() }
        val styleArg = input.styleBibleId?.takeIf { it.isNotBlank() }
        require((lutArg == null) xor (styleArg == null)) {
            "exactly one of lutAssetId or styleBibleId must be provided"
        }

        val pid = ctx.resolveProjectId(input.projectId)
        var resolvedLutId: AssetId? = null
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            val lutId: AssetId = if (lutArg != null) {
                AssetId(lutArg)
            } else {
                val sid = SourceNodeId(styleArg!!)
                val node = project.source.byId[sid]
                    ?: error("style_bible node '${sid.value}' not found in project ${pid.value}")
                val style = node.asStyleBible()
                    ?: error("node '${sid.value}' exists but is not a style_bible (kind=${node.kind})")
                style.lutReference
                    ?: error(
                        "style_bible '${sid.value}' has no lutReference; set one by updating the node's body " +
                            "(source_query(select=node_detail) → set body.lutReference → update_source_node_body) first",
                    )
            }
            if (project.assets.none { it.id == lutId }) {
                error("LUT asset '${lutId.value}' not found in the project's asset catalog")
            }
            resolvedLutId = lutId

            var tracks = project.timeline.tracks
            input.clipIds.forEachIndexed { idx, clipId ->
                val hit = tracks.firstNotNullOfOrNull { t ->
                    t.clips.firstOrNull { it.id.value == clipId }?.let { t to it }
                } ?: error("clipIds[$idx] ($clipId) not found in project ${pid.value}")
                val (track, clip) = hit
                if (clip !is Clip.Video) {
                    error("clipIds[$idx] ($clipId): apply_lut only supports video clips; clip is ${clip::class.simpleName}")
                }
                val newFilters = clip.filters + Filter(name = "lut", assetId = lutId)
                val newBinding = if (styleArg != null) clip.sourceBinding + SourceNodeId(styleArg) else clip.sourceBinding
                val replaced = clip.copy(filters = newFilters, sourceBinding = newBinding)
                val newTrack = replaceClipOnTrack(track, clip, replaced)
                tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
                results += ItemResult(clipId = clipId, filterCount = newFilters.size)
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val sourceNote = if (styleArg != null) " (from style_bible '$styleArg')" else ""
        return ToolResult(
            title = "apply LUT × ${results.size}",
            outputForLlm = "Applied LUT '${resolvedLutId!!.value}'$sourceNote to ${results.size} clip(s). " +
                "Snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                lutAssetId = resolvedLutId!!.value,
                styleBibleId = styleArg,
                results = results,
                snapshotId = snapshotId.value,
            ),
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
