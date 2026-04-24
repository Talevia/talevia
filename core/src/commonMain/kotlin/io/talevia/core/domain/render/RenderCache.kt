package io.talevia.core.domain.render

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
 * Lookup is O(1) via a `@Transient byFingerprint` index rebuilt from [entries] on
 * every construction (same pattern [io.talevia.core.domain.lockfile.Lockfile.byInputHash]
 * and [io.talevia.core.domain.render.ClipRenderCache.byFingerprint] use). We cap
 * implicitly via "latest wins" in [findByFingerprint]: if a fingerprint appears
 * twice the later entry is returned — [List.associateBy] overwrites on duplicate
 * keys, matching the pre-index `entries.lastOrNull { … }` semantic. No explicit
 * eviction — a project's export count per session is ≤ dozens.
 */
@Serializable
data class RenderCache(
    val entries: List<RenderCacheEntry> = emptyList(),
) {
    /**
     * Fingerprint → most recent [RenderCacheEntry] with that fingerprint.
     * `@Transient` means it's recomputed on every construction (deserialize +
     * every `copy(entries = …)`); the serialized shape is byte-identical to
     * the pre-index form so existing bundles decode without migration.
     */
    @Transient
    val byFingerprint: Map<String, RenderCacheEntry> = entries.associateBy { it.fingerprint }

    fun findByFingerprint(fingerprint: String): RenderCacheEntry? =
        byFingerprint[fingerprint]

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
