package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaAsset
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.DurationUnit

/**
 * Read-only counterpart to `prune_lockfile`: scans `Project.assets` for ids that
 * are NOT referenced anywhere — no clip, no lockfile entry, no LUT filter. Lets
 * the agent answer the professional workflow question "I imported 20 clips, used
 * 3, what can I safely delete?" without dumping the entire project JSON.
 *
 * The counterpart reverses `prune_lockfile`'s direction: that tool sweeps
 * lockfile entries whose asset has been removed from the catalog (stale
 * provenance); this one sweeps catalog entries that no part of the project
 * references (orphan media). Together they keep the asset / lockfile tables
 * mutually consistent.
 *
 * Reference lanes scanned:
 *  - `Clip.Video.assetId` / `Clip.Audio.assetId` on any track.
 *  - `project.lockfile.entries[*].assetId` — an asset keeps its "we generated
 *    this" provenance even after every clip using it is deleted; preserve the
 *    audit trail rather than treating lockfile-only assets as garbage.
 *  - `Clip.Video.filters[*].assetId` — LUT `.cube` files are loaded by the
 *    engine through `MediaPathResolver` but never sit on a clip as a primary
 *    asset; without this lane every LUT would look unreferenced.
 *
 * Sorting + paging:
 *  - `sortBy` applies BEFORE `limit` so the returned slice is always the
 *    top-N of the requested ordering, not a truncated head of the raw list.
 *    Default `"duration-desc"` (biggest orphans first) matches the decluttering
 *    workflow — agents asking "what can I delete?" almost always want the big
 *    wins first. `"id"` gives deterministic alphabetical ordering for
 *    pagination-style follow-ups.
 *  - `limit` (default 50, clamped silently to `[1, 500]`) caps transcript
 *    bloat on catalogs with dozens of orphan imports. Pre-limit totals
 *    (`totalAssets`, `referencedCount`, `unreferencedCount`) are preserved
 *    so the headline numbers still reflect the real scan — `returnedCount`
 *    reports the post-limit slice size.
 *
 * Read-only (`project.read`). Consumers compose this with an explicit
 * per-id `remove_asset` call — this tool never mutates.
 */
class FindUnreferencedAssetsTool(
    private val projects: ProjectStore,
) : Tool<FindUnreferencedAssetsTool.Input, FindUnreferencedAssetsTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /**
         * Ordering applied to the unreferenced list BEFORE `limit` clamps the slice.
         * Accepted values (case-insensitive, trim+lowercase):
         *   - `"duration-desc"` (default) — longest orphans first; matches the
         *     "biggest KB win" decluttering workflow.
         *   - `"duration-asc"` — shortest first.
         *   - `"id"` — asset id ASC (alphanumeric, stable for pagination).
         *
         * Invalid values raise `IllegalArgumentException` listing the accepted set
         * — silently degrading to a default would hide a sort-typo bug.
         */
        val sortBy: String? = null,
        /**
         * Cap on returned orphans. Defaults to 50; clamped silently to `[1, 500]`.
         * Out-of-range values don't throw — `limit=0` / `limit=999_999` just get
         * `coerceIn`'d — because the agent's intent is clear (as many as possible,
         * bounded) and failing loud here would regress decluttering UX.
         *
         * Pre-limit totals (`totalAssets`, `referencedCount`, `unreferencedCount`)
         * are unaffected — they reflect the real scan, not the returned slice.
         */
        val limit: Int? = null,
    )

    @Serializable data class Summary(
        val assetId: String,
        val durationSeconds: Double? = null,
        val widthPx: Int? = null,
        val heightPx: Int? = null,
        /** "video" | "audio" | "image" — coarse classification from codec metadata. */
        val kind: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val totalAssets: Int,
        val referencedCount: Int,
        /** Pre-limit orphan count — the real number from the scan, unaffected by `limit`. */
        val unreferencedCount: Int,
        /** Post-sort, post-limit slice size (i.e. `unreferenced.size`). */
        val returnedCount: Int,
        val unreferenced: List<Summary>,
    )

    override val id: String = "find_unreferenced_assets"
    override val helpText: String =
        "Report assets in the project catalog that are not referenced by any clip, lockfile entry, " +
            "or LUT filter. Read-only counterpart to prune_lockfile for the catalog direction. " +
            "Use to surface orphan imports for deletion via remove_asset. Orphans are sorted by " +
            "sortBy (duration-desc default / duration-asc / id) and capped by limit (default 50, " +
            "max 500); totals in the response always reflect the pre-limit scan."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("sortBy") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("duration-desc"))
                    add(JsonPrimitive("duration-asc"))
                    add(JsonPrimitive("id"))
                }
                put(
                    "description",
                    "Orphan ordering applied before limit. One of: duration-desc (default, longest first) | " +
                        "duration-asc (shortest first) | id (asset id asc).",
                )
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Max orphans returned. Default 50; silently clamped to [1, 500].")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val normalizedSort = input.sortBy?.trim()?.lowercase()
        if (normalizedSort != null) {
            require(normalizedSort in SORT_BY_ALLOWED) {
                "sortBy must be one of duration-desc|duration-asc|id (got '${input.sortBy}')"
            }
        }
        val sortKey = normalizedSort ?: DEFAULT_SORT_BY
        val cappedLimit = (input.limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)

        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("project ${input.projectId} not found")

        val referencedByClip: Set<AssetId> = buildSet {
            project.timeline.tracks.forEach { track ->
                track.clips.forEach { clip ->
                    when (clip) {
                        is Clip.Video -> add(clip.assetId)
                        is Clip.Audio -> add(clip.assetId)
                        is Clip.Text -> Unit
                    }
                }
            }
        }
        val referencedByFilter: Set<AssetId> = buildSet {
            project.timeline.tracks.forEach { track ->
                track.clips.forEach { clip ->
                    if (clip is Clip.Video) {
                        clip.filters.forEach { f -> f.assetId?.let { add(it) } }
                    }
                }
            }
        }
        val referencedByLockfile: Set<AssetId> =
            project.lockfile.entries.map { it.assetId }.toSet()

        val referenced = referencedByClip + referencedByFilter + referencedByLockfile
        val unreferencedAll = project.assets.filter { it.id !in referenced }

        // Sort BEFORE limit so the slice is always the top-N of the chosen ordering.
        val sortedUnreferenced = sortOrphans(unreferencedAll, sortKey)
        val truncated = sortedUnreferenced.take(cappedLimit)

        val out = Output(
            projectId = pid.value,
            totalAssets = project.assets.size,
            // Pre-limit totals — don't let the filter rewrite the headline numbers.
            referencedCount = project.assets.size - unreferencedAll.size,
            unreferencedCount = unreferencedAll.size,
            returnedCount = truncated.size,
            unreferenced = truncated.map { asset -> summarize(asset) },
        )
        val summary = if (unreferencedAll.isEmpty()) {
            "All ${project.assets.size} asset(s) referenced (by clip / lockfile / filter). Nothing to prune."
        } else {
            val scopeNote = when {
                truncated.size < unreferencedAll.size ->
                    "${truncated.size} of ${unreferencedAll.size} orphan(s) shown (${scopeLabel(sortKey)}): "
                else -> "${unreferencedAll.size} of ${project.assets.size} asset(s) unreferenced (${scopeLabel(sortKey)}): "
            }
            scopeNote + truncated.take(5).joinToString(", ") { it.id.value } +
                if (truncated.size > 5) ", …" else ""
        }
        return ToolResult(
            title = "find unreferenced assets",
            outputForLlm = summary,
            data = out,
        )
    }

    private fun sortOrphans(assets: List<MediaAsset>, sortBy: String): List<MediaAsset> =
        when (sortBy) {
            "duration-desc" -> assets.sortedByDescending { it.metadata.duration }
            "duration-asc" -> assets.sortedBy { it.metadata.duration }
            "id" -> assets.sortedBy { it.id.value }
            else -> error("unreachable — validated above: '$sortBy'")
        }

    private fun scopeLabel(sortBy: String): String = when (sortBy) {
        "duration-desc" -> "biggest first"
        "duration-asc" -> "shortest first"
        "id" -> "id asc"
        else -> "sorted"
    }

    private fun summarize(asset: MediaAsset): Summary {
        val meta = asset.metadata
        val hasV = meta.videoCodec != null
        val hasA = meta.audioCodec != null
        val kind = when {
            hasV -> "video"
            hasA -> "audio"
            else -> "image"
        }
        return Summary(
            assetId = asset.id.value,
            durationSeconds = meta.duration.toDouble(DurationUnit.SECONDS),
            widthPx = meta.resolution?.width,
            heightPx = meta.resolution?.height,
            kind = kind,
        )
    }

    private companion object {
        const val DEFAULT_SORT_BY = "duration-desc"
        const val DEFAULT_LIMIT = 50
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 500
        val SORT_BY_ALLOWED = setOf("duration-desc", "duration-asc", "id")
    }
}
