package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
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
import kotlin.time.DurationUnit

/**
 * Focused, paginated view of a project's [Project.assets] catalog — the
 * missing companion to [ListTimelineClipsTool]. Until this existed an
 * agent asking "what media do I have?" had to call `get_project_state`
 * and page through the full JSON blob, which also includes the entire
 * timeline, lockfile, snapshots, render cache, source graph… every
 * token of that is wasted if the agent only wants to know whether a
 * given imported file is still around or whether a stale AIGC asset is
 * still referenced.
 *
 * Fields returned match what an agent can actually reason about:
 * asset id, a coarse media `kind` classification (video / audio /
 * image), duration, resolution if known, the [MediaSource] variant
 * discriminator, and an `inUseByClips` count so the agent can tell
 * which assets are dangling (zero clips reference them). Raw codec
 * strings and bitrates are intentionally omitted — engines care,
 * agents don't.
 *
 * Input filters:
 *   - `kind` ∈ {video, audio, image, all} — classify by metadata.
 *   - `onlyUnused = true` — return only assets no clip references.
 *   - `limit` / `offset` — pagination. Default limit 50 (keeps
 *     transcripts tight; `total` gives the real count).
 *
 * Matches the `list_timeline_clips` + `list_source_nodes` stance of
 * "cheap, paginated, opinionated projection; dump the full JSON only
 * when you actually need it."
 */
class ListAssetsTool(
    private val projects: ProjectStore,
) : Tool<ListAssetsTool.Input, ListAssetsTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** "video" | "audio" | "image" | "all" (default). */
        val kind: String = "all",
        /** Filter to only assets that are not referenced by any clip. */
        val onlyUnused: Boolean = false,
        val limit: Int = 50,
        val offset: Int = 0,
        /**
         * Deterministic ordering applied before offset+limit. Accepted values:
         *   - `"duration"`     — metadata.duration DESC (longest first).
         *   - `"duration-asc"` — metadata.duration ASC (shortest first).
         *   - `"id"`           — asset id ASC (stable pagination).
         *
         * Null / omitted preserves store-insertion order (today's behaviour).
         * `MediaAsset` carries no creation timestamp, so `"newest"` is not
         * offered — adding one would require extending the domain model.
         */
        val sortBy: String? = null,
    )

    @Serializable data class AssetInfo(
        val assetId: String,
        /** "video" | "audio" | "image". */
        val kind: String,
        val durationSeconds: Double,
        val width: Int? = null,
        val height: Int? = null,
        val hasVideoTrack: Boolean,
        val hasAudioTrack: Boolean,
        /** "file" | "http" | "platform". */
        val sourceKind: String,
        val inUseByClips: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val total: Int,
        val returned: Int,
        val assets: List<AssetInfo>,
    )

    override val id: String = "list_assets"
    override val helpText: String =
        "List media assets in a project (paginated). Filter by kind (video/audio/image), " +
            "or only show unused assets. Optional sortBy (duration | duration-asc | id) applies " +
            "a deterministic ordering before offset+limit; omit to keep store-insertion order. " +
            "Cheap alternative to get_project_state when the agent just needs to know what media " +
            "is available or which assets are dangling."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("kind") {
                put("type", "string")
                put("description", "video | audio | image | all (default).")
            }
            putJsonObject("onlyUnused") {
                put("type", "boolean")
                put("description", "Only include assets referenced by zero clips (useful for pruning).")
            }
            putJsonObject("limit") { put("type", "integer"); put("description", "Default 50, max 500.") }
            putJsonObject("offset") { put("type", "integer"); put("description", "Skip this many matches before returning. Default 0.") }
            putJsonObject("sortBy") {
                put("type", "string")
                put(
                    "description",
                    "Deterministic ordering applied before offset+limit. One of: " +
                        "duration (longest first) | duration-asc (shortest first) | id (asset id asc). " +
                        "Omit to preserve store-insertion order.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.limit in 1..500) { "limit must be in [1, 500] (got ${input.limit})" }
        require(input.offset >= 0) { "offset must be >= 0 (got ${input.offset})" }
        val kindFilter = input.kind.lowercase()
        require(kindFilter in setOf("video", "audio", "image", "all")) {
            "kind must be one of video|audio|image|all (got '${input.kind}')"
        }
        if (input.sortBy != null) {
            require(input.sortBy in SORT_BY_ALLOWED) {
                "sortBy must be one of duration|duration-asc|id (got '${input.sortBy}')"
            }
        }

        val project = projects.get(ProjectId(input.projectId))
            ?: error("project ${input.projectId} not found")

        val refCount: Map<String, Int> = buildMap<String, Int> {
            project.timeline.tracks.forEach { track ->
                track.clips.forEach { clip ->
                    val assetId = when (clip) {
                        is Clip.Video -> clip.assetId.value
                        is Clip.Audio -> clip.assetId.value
                        is Clip.Text -> null
                    }
                    if (assetId != null) put(assetId, (get(assetId) ?: 0) + 1)
                }
            }
        }

        val filtered = project.assets.asSequence()
            .map { asset -> asset to classify(asset) }
            .filter { (_, kind) -> kindFilter == "all" || kind == kindFilter }
            .map { (asset, kind) -> buildInfo(asset, kind, refCount[asset.id.value] ?: 0) }
            .filter { !input.onlyUnused || it.inUseByClips == 0 }
            .toList()

        val matching = sorted(filtered, input.sortBy)

        val page = matching.drop(input.offset).take(input.limit)

        val title = if (input.onlyUnused) "list unused assets" else "list assets (${input.kind})"
        return ToolResult(
            title = title,
            outputForLlm = "Project ${input.projectId}: ${matching.size} matching assets, " +
                "returning ${page.size} (offset ${input.offset}).",
            data = Output(
                projectId = input.projectId,
                total = matching.size,
                returned = page.size,
                assets = page,
            ),
        )
    }

    private fun sorted(assets: List<AssetInfo>, sortBy: String?): List<AssetInfo> =
        when (sortBy) {
            null -> assets
            "duration" -> assets.sortedByDescending { it.durationSeconds }
            "duration-asc" -> assets.sortedBy { it.durationSeconds }
            "id" -> assets.sortedBy { it.assetId }
            else -> error("unreachable — validated above: '$sortBy'")
        }

    private fun classify(asset: MediaAsset): String {
        val hasV = asset.metadata.videoCodec != null
        val hasA = asset.metadata.audioCodec != null
        return when {
            hasV -> "video" // includes video+audio — a muxed clip is a video asset
            hasA -> "audio"
            else -> "image" // no codec metadata → treat as still (single-frame media or LUT file)
        }
    }

    private fun buildInfo(asset: MediaAsset, kind: String, refCount: Int): AssetInfo {
        val res = asset.metadata.resolution
        val sourceKind = when (asset.source) {
            is MediaSource.File -> "file"
            is MediaSource.Http -> "http"
            is MediaSource.Platform -> "platform"
        }
        return AssetInfo(
            assetId = asset.id.value,
            kind = kind,
            durationSeconds = asset.metadata.duration.toDouble(DurationUnit.SECONDS),
            width = res?.width,
            height = res?.height,
            hasVideoTrack = asset.metadata.videoCodec != null,
            hasAudioTrack = asset.metadata.audioCodec != null,
            sourceKind = sourceKind,
            inUseByClips = refCount,
        )
    }

    private companion object {
        val SORT_BY_ALLOWED = setOf("duration", "duration-asc", "id")
    }
}
