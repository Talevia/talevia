package io.talevia.core.domain.render

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Per-clip mezzanine memo — the fine-grained counterpart to [RenderCache].
 *
 * VISION §3.2 "只重编译必要的部分" at clip granularity: a small edit to clip C in
 * a 30-clip project should re-render C's mezzanine and concat the rest from
 * cache, not re-encode every clip from scratch. [RenderCache] memoises the
 * **whole-timeline** export; this one memoises each clip's encoded bitstream
 * at the output profile's resolution / fps / codecs so the final step can
 * concat-demux them bit-for-bit.
 *
 * Keyed by a fingerprint the caller ([io.talevia.core.tool.builtin.video.ExportTool])
 * computes over: canonical clip JSON + neighbour-aware [TransitionFades] + the
 * deep content hashes of the clip's [io.talevia.core.domain.Clip.sourceBinding]
 * (so an upstream source edit invalidates the mezzanine even when the clip's
 * own JSON is byte-identical) + output profile essentials (resolution, fps,
 * video/audio codec + bitrate).
 *
 * Not keyed on transient knobs like `outputPath` — the same mezzanine is
 * reused across multiple export targets in the same directory, as long as
 * the profile matches.
 *
 * Storage: [ClipRenderCacheEntry.mezzaninePath] points at an absolute path
 * on disk, conventionally under `<outputDir>/.talevia-render-cache/<projectId>/<fingerprint>.mp4`.
 * The cache entry is informational — if the file is missing at cache-hit
 * time, the caller treats it as a miss and re-renders. No eviction policy
 * for the MVP; a project's clip count × distinct output profiles bounds the
 * entry count in practice.
 *
 * The `Project.clipRenderCache` field defaults to [EMPTY] so pre-cache
 * project blobs decode without migration.
 */
@Serializable
data class ClipRenderCache(
    val entries: List<ClipRenderCacheEntry> = emptyList(),
) {
    /**
     * Fingerprint → most recent [ClipRenderCacheEntry] with that fingerprint.
     *
     * Reconstructed from [entries] on deserialize via the default lazy-init
     * pattern [io.talevia.core.domain.lockfile.Lockfile.byInputHash] already
     * uses. [List.associateBy] overwrites on duplicate keys, so the resulting
     * entry is the last one in insertion order — matching the original
     * `entries.lastOrNull { … }` last-wins semantic exactly.
     *
     * `@Transient` means it's recomputed on every construction (including
     * deserialize + every `copy(entries = …)`); the field doesn't touch the
     * serialized shape, so pre-existing `clip-render-cache.json` files
     * decode as before.
     */
    @Transient
    val byFingerprint: Map<String, ClipRenderCacheEntry> = entries.associateBy { it.fingerprint }

    fun findByFingerprint(fingerprint: String): ClipRenderCacheEntry? =
        byFingerprint[fingerprint]

    fun append(entry: ClipRenderCacheEntry): ClipRenderCache = copy(entries = entries + entry)

    /**
     * Keep only entries whose `fingerprint` is in [keep]; drop the rest. Used
     * by [io.talevia.core.tool.builtin.project.ProjectMaintenanceActionTool] to prune
     * policy-selected rows. Preserves append order among survivors so the
     * "latest wins" contract in [findByFingerprint] keeps matching the same
     * entry after the prune.
     */
    fun retainByFingerprint(keep: Set<String>): ClipRenderCache =
        copy(entries = entries.filter { it.fingerprint in keep })

    companion object {
        val EMPTY: ClipRenderCache = ClipRenderCache()
    }
}

/**
 * One memoized clip mezzanine. See [ClipRenderCache] for fingerprint composition.
 *
 * [mezzaninePath] is the absolute path the FFmpeg engine wrote the encoded
 * clip to. The concat step reads it back and stream-copies (or re-encodes
 * when a post-concat filter like subtitles is involved) into the final
 * export. The path is recorded rather than a byte blob because mezzanines
 * are typically 100s of MB — inline storage would bloat the project blob.
 */
@Serializable
data class ClipRenderCacheEntry(
    val fingerprint: String,
    val mezzaninePath: String,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val durationSeconds: Double,
    val createdAtEpochMs: Long,
)
