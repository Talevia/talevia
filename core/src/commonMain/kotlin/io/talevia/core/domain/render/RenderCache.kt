package io.talevia.core.domain.render

import kotlinx.serialization.Serializable

/**
 * Per-project memoization of full-timeline exports. VISION §3.2 "只重编译必要的部分" —
 * this is the coarse-grained first cut: when the same `(timeline, outputSpec)` has
 * already been rendered, skip the render and return the previous output.
 *
 * Per-clip incremental compilation (render one stale clip, reuse the rest) is a
 * follow-up: it requires the engine layer to expose a per-clip render path, which
 * FFmpeg / AVFoundation / Media3 don't do uniformly today. Full-render memoization
 * is a useful subset — a second `export` call in the same Agent turn with identical
 * inputs costs zero seconds instead of however long the full render takes.
 *
 * Ordered list, lookup is O(n). We cap implicitly via "latest wins" in
 * [findByFingerprint]: if a fingerprint appears twice the later entry is returned.
 * No explicit eviction — a project's export count per session is ≤ dozens.
 */
@Serializable
data class RenderCache(
    val entries: List<RenderCacheEntry> = emptyList(),
) {
    fun findByFingerprint(fingerprint: String): RenderCacheEntry? =
        entries.lastOrNull { it.fingerprint == fingerprint }

    fun append(entry: RenderCacheEntry): RenderCache = copy(entries = entries + entry)

    companion object {
        val EMPTY: RenderCache = RenderCache()
    }
}

/**
 * One memoized render. Keyed by [fingerprint], which the caller (ExportTool) computes
 * over the canonical timeline JSON + [OutputSpec] fields. Because `Clip.sourceBinding`
 * is inside the timeline, any upstream source change flows through
 * `Source.stale` → `ProjectStaleness.staleClips` → affected clip's assetId (AIGC tools
 * rewrite it on cache miss) → distinct timeline JSON → distinct fingerprint. So this
 * coarse cache respects the DAG without a separate stale check.
 *
 * [outputPath] is recorded so a "cache hit" can return the same Output/Attachment
 * shape as a fresh render. We do not re-verify the file's existence on disk here;
 * a stale file path is a user-side problem (they can delete/move) and the remedy
 * is a `forceRender` flag on the tool.
 */
@Serializable
data class RenderCacheEntry(
    val fingerprint: String,
    val outputPath: String,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val durationSeconds: Double,
    val createdAtEpochMs: Long,
)
