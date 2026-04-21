package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaStorage
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
 * Apply a 3D LUT (`.cube` / `.3dl`) to a video clip. The LUT comes from *either*:
 *
 *  - `lutAssetId` — the asset id of an imported LUT file (direct-use), or
 *  - `styleBibleId` — a `core.consistency.style_bible` node that carries
 *    `lutReference`. The tool resolves the style_bible's current LUT at apply time.
 *
 * VISION §3.3 names `style_bible.lutReference` as the canonical home for a
 * project-global LUT, but until this tool existed nothing read it — the field was
 * data without a consumer. Going through the style_bible path also attaches the
 * style_bible's nodeId to the clip's `sourceBinding`, so future staleness
 * machinery can propagate edits (e.g. swapping the LUT inside the style_bible)
 * through the DAG the same way AIGC clips already participate.
 *
 * Implementation details:
 * - `Filter.assetId` carries the LUT asset id (v0: a dedicated filter name
 *   `"lut"` avoids fighting the existing numeric-param contract on `Filter`).
 * - FFmpeg renders this as `lut3d=file=<resolved path>`. Android Media3 and iOS
 *   AVFoundation engines currently no-op filters entirely (see CLAUDE.md
 *   "Known incomplete") — that gap is inherited here rather than re-opened.
 * - Exactly one of `lutAssetId` / `styleBibleId` must be provided; both or
 *   neither fail loudly so the LLM can't quietly pick one.
 */
class ApplyLutTool(
    private val store: ProjectStore,
    private val media: MediaStorage,
) : Tool<ApplyLutTool.Input, ApplyLutTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        val lutAssetId: String? = null,
        val styleBibleId: String? = null,
    )

    @Serializable data class Output(
        val clipId: String,
        val lutAssetId: String,
        val styleBibleId: String?,
        val filterCount: Int,
    )

    override val id: String = "apply_lut"
    override val helpText: String =
        "Apply a 3D LUT to a video clip. Pass lutAssetId for a direct LUT, or styleBibleId " +
            "to pull the LUT from a style_bible node's lutReference. Exactly one of the two. " +
            "The style_bible path also binds the clip to the style_bible node so future " +
            "edits cascade through stale-clip detection."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("lutAssetId") {
                put("type", "string")
                put("description", "Asset id of an imported LUT (.cube / .3dl). Mutually exclusive with styleBibleId.")
            }
            putJsonObject("styleBibleId") {
                put("type", "string")
                put("description", "Source-node id of a core.consistency.style_bible. Its lutReference is resolved at apply time. Mutually exclusive with lutAssetId.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val lutArg = input.lutAssetId?.takeIf { it.isNotBlank() }
        val styleArg = input.styleBibleId?.takeIf { it.isNotBlank() }
        require((lutArg == null) xor (styleArg == null)) {
            "exactly one of lutAssetId or styleBibleId must be provided"
        }

        val pid = ProjectId(input.projectId)
        var resolvedLutId: AssetId? = null
        var filterCount = 0

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
                    ?: error("style_bible '${sid.value}' has no lutReference; set one via set_style_bible first")
            }
            media.get(lutId) ?: error("LUT asset '${lutId.value}' not found in the project's asset catalog")
            resolvedLutId = lutId

            var found = false
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId } ?: return@map track
                found = true
                if (target !is Clip.Video) error("apply_lut only supports video clips; clip ${input.clipId} is ${target::class.simpleName}")
                val newFilters = target.filters + Filter(name = "lut", assetId = lutId)
                val newBinding = if (styleArg != null) target.sourceBinding + SourceNodeId(styleArg) else target.sourceBinding
                val replaced = target.copy(filters = newFilters, sourceBinding = newBinding)
                filterCount = newFilters.size
                replaceClipOnTrack(track, target, replaced)
            }
            if (!found) error("clip ${input.clipId} not found in project ${input.projectId}")
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val out = Output(
            clipId = input.clipId,
            lutAssetId = resolvedLutId!!.value,
            styleBibleId = styleArg,
            filterCount = filterCount,
        )
        val sourceNote = if (styleArg != null) " (from style_bible '$styleArg')" else ""
        return ToolResult(
            title = "apply LUT to ${input.clipId}",
            outputForLlm = "Applied LUT '${out.lutAssetId}'$sourceNote to clip ${input.clipId}; " +
                "$filterCount filter(s) now attached. Timeline snapshot: ${snapshotId.value}",
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
