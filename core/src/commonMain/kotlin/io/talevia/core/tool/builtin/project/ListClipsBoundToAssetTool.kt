package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
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
 * Asset-side forward lookup — given an [AssetId], report every clip on the
 * project timeline that references it. The complement of [ListClipsForSourceTool]:
 *
 *   - `list_clips_for_source` — "if I edit this source node, which clips go stale?"
 *     (source-DAG forward lookup).
 *   - `list_clips_bound_to_asset` — "if I delete / regenerate / replace this
 *     asset, which clips break?" (asset-side forward lookup).
 *
 * Matches [Clip.Video.assetId] and [Clip.Audio.assetId]. [Clip.Text] has no
 * asset, so text clips never match. An unreferenced asset returns an empty
 * list (the asset may simply be unused). An **unknown** asset id — one not
 * in `project.assets` — throws, so typos surface loudly instead of silently
 * returning "no matches".
 *
 * Read-only; permission `"project.read"`.
 */
class ListClipsBoundToAssetTool(
    private val projects: ProjectStore,
) : Tool<ListClipsBoundToAssetTool.Input, ListClipsBoundToAssetTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val assetId: String,
    )

    @Serializable data class Match(
        val clipId: String,
        val trackId: String,
        /** `"video"` or `"audio"`. Text clips never match (no asset). */
        val kind: String,
        val startSeconds: Double,
        val durationSeconds: Double,
    )

    @Serializable data class Output(
        val projectId: String,
        val assetId: String,
        val matchCount: Int,
        val clips: List<Match>,
    )

    override val id: String = "list_clips_bound_to_asset"
    override val helpText: String =
        "List every clip on the timeline that references the given assetId " +
            "(Clip.Video.assetId / Clip.Audio.assetId; text clips never match). Use " +
            "*before* deleting, regenerating, or replacing an asset to preview which " +
            "clips will break. Pair with list_clips_for_source for the source-DAG " +
            "forward lookup. Unreferenced asset returns an empty list (not stale, " +
            "just unused); unknown asset id throws so typos surface instead of " +
            "silently matching nothing."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("assetId") {
                put("type", "string")
                put(
                    "description",
                    "The media asset to query. Must exist in the project's assets list — " +
                        "call list_assets to discover valid ids.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("assetId"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val aid = AssetId(input.assetId)
        if (project.assets.none { it.id == aid }) {
            throw IllegalArgumentException(
                "Asset ${input.assetId} not found in project ${input.projectId} — " +
                    "call list_assets to discover valid ids.",
            )
        }

        val matches = mutableListOf<Match>()
        for (track in project.timeline.tracks) {
            val ordered = track.clips.sortedBy { it.timeRange.start }
            for (clip in ordered) {
                val (clipAsset, kind) = when (clip) {
                    is Clip.Video -> clip.assetId to "video"
                    is Clip.Audio -> clip.assetId to "audio"
                    is Clip.Text -> continue
                }
                if (clipAsset != aid) continue
                matches += Match(
                    clipId = clip.id.value,
                    trackId = track.id.value,
                    kind = kind,
                    startSeconds = clip.timeRange.start.inWholeMilliseconds / 1000.0,
                    durationSeconds = clip.timeRange.duration.inWholeMilliseconds / 1000.0,
                )
            }
        }

        val summary = if (matches.isEmpty()) {
            "No clips reference asset ${input.assetId} (unreferenced — safe to delete)."
        } else {
            val head = matches.take(5).joinToString("; ") {
                "${it.clipId} (${it.kind}/${it.trackId} @ ${it.startSeconds}s)"
            }
            val tail = if (matches.size > 5) "; …" else ""
            "${matches.size} clip(s) reference asset ${input.assetId}: $head$tail"
        }

        return ToolResult(
            title = "list clips bound to asset",
            outputForLlm = summary,
            data = Output(
                projectId = pid.value,
                assetId = input.assetId,
                matchCount = matches.size,
                clips = matches,
            ),
        )
    }
}
