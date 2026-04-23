package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.DurationUnit

@Serializable data class AssetRow(
    val assetId: String,
    val kind: String,
    val durationSeconds: Double,
    val width: Int? = null,
    val height: Int? = null,
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val sourceKind: String,
    val inUseByClips: Int,
    /** Stamped by [io.talevia.core.domain.FileProjectStore]; null on pre-recency blobs. */
    val updatedAtEpochMs: Long? = null,
)

/**
 * `select=assets` — one row per asset in the project catalog with kind,
 * duration, resolution, codec flags, source kind, reference count.
 */
internal fun runAssetsQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val kindFilter = (input.kind ?: "all").trim().lowercase()
    if (kindFilter !in ASSET_KINDS) {
        error("kind must be one of ${ASSET_KINDS.joinToString(", ")} (got '${input.kind}')")
    }
    val sortBy = input.sortBy?.trim()?.lowercase()
    if (sortBy != null && sortBy !in ASSET_SORTS) {
        error("sortBy for select=assets must be one of ${ASSET_SORTS.joinToString(", ")} (got '${input.sortBy}')")
    }

    val refCount: Map<String, Int> = buildMap {
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

    // Broader "any reference" set — includes clip refs, LUT filter refs,
    // and lockfile provenance. Matches the old `find_unreferenced_assets`
    // tool's safe-to-delete semantics when onlyReferenced=false.
    val anyRef: Set<String> = buildSet {
        addAll(refCount.keys)
        project.timeline.tracks.forEach { track ->
            track.clips.forEach { clip ->
                if (clip is Clip.Video) {
                    clip.filters.forEach { f -> f.assetId?.value?.let { add(it) } }
                }
            }
        }
        project.lockfile.entries.forEach { add(it.assetId.value) }
    }

    val filtered = project.assets.asSequence()
        .map { asset -> asset to classifyAsset(asset) }
        .filter { (_, kind) -> kindFilter == "all" || kind == kindFilter }
        .map { (asset, kind) -> buildAssetRow(asset, kind, refCount[asset.id.value] ?: 0) }
        .filter { input.onlyUnused != true || it.inUseByClips == 0 }
        .filter {
            when (input.onlyReferenced) {
                null -> true
                true -> it.assetId in anyRef
                false -> it.assetId !in anyRef
            }
        }
        .toList()

    val sorted = when (sortBy) {
        null, "insertion" -> filtered
        "duration" -> filtered.sortedByDescending { it.durationSeconds }
        "duration-asc" -> filtered.sortedBy { it.durationSeconds }
        "id" -> filtered.sortedBy { it.assetId }
        "recent" -> filtered.sortedWith(recentComparator({ it.updatedAtEpochMs }, { it.assetId }))
        else -> error("unreachable")
    }

    val page = sorted.drop(offset).take(limit)
    val rows = encodeRows(ListSerializer(AssetRow.serializer()), page)
    val scopeBits = buildList {
        add("kind=$kindFilter")
        if (input.onlyUnused == true) add("unused-only")
        when (input.onlyReferenced) {
            true -> add("only-referenced")
            false -> add("only-orphans")
            null -> Unit
        }
        sortBy?.let { add("sort=$it") }
    }.joinToString(", ")
    return ToolResult(
        title = "project_query assets ($kindFilter)",
        outputForLlm = "Project ${project.id.value}: ${filtered.size} matching assets, " +
            "returning ${page.size} (offset $offset, $scopeBits).",
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_ASSETS,
            total = filtered.size,
            returned = page.size,
            rows = rows,
        ),
    )
}

private fun classifyAsset(asset: MediaAsset): String {
    val hasV = asset.metadata.videoCodec != null
    val hasA = asset.metadata.audioCodec != null
    return when {
        hasV -> "video"
        hasA -> "audio"
        else -> "image"
    }
}

private fun buildAssetRow(asset: MediaAsset, kind: String, refCount: Int): AssetRow {
    val res = asset.metadata.resolution
    val sourceKind = when (asset.source) {
        is MediaSource.File -> "file"
        is MediaSource.BundleFile -> "bundle_file"
        is MediaSource.Http -> "http"
        is MediaSource.Platform -> "platform"
    }
    return AssetRow(
        assetId = asset.id.value,
        kind = kind,
        durationSeconds = asset.metadata.duration.toDouble(DurationUnit.SECONDS),
        width = res?.width,
        height = res?.height,
        hasVideoTrack = asset.metadata.videoCodec != null,
        hasAudioTrack = asset.metadata.audioCodec != null,
        sourceKind = sourceKind,
        inUseByClips = refCount,
        updatedAtEpochMs = asset.updatedAtEpochMs,
    )
}
