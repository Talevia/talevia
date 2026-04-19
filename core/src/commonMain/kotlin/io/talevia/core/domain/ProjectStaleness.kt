package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.stale

/**
 * Project-layer half of the stale-propagation lane (VISION §3.2). Given a set of
 * source-node ids that changed, return the set of clip ids that must be re-rendered.
 *
 * A clip is stale iff its [Clip.sourceBinding] intersects the source-layer transitive
 * closure ([io.talevia.core.domain.source.stale]). Clips with an empty binding are
 * *always* considered stale — they opted out of incremental compilation. The
 * `ExportTool` incremental-render path reads this; non-incremental callers can ignore
 * it.
 */
fun Project.staleClips(changed: Set<SourceNodeId>): Set<ClipId> {
    if (changed.isEmpty()) return emptySet()
    val staleNodes = source.stale(changed)
    if (staleNodes.isEmpty()) return emptySet()
    val out = LinkedHashSet<ClipId>()
    for (track in timeline.tracks) {
        for (clip in track.clips) {
            if (clip.sourceBinding.isEmpty()) {
                out.add(clip.id) // unbound clip: can't prove fresh → treat as stale
                continue
            }
            if (clip.sourceBinding.any { it in staleNodes }) {
                out.add(clip.id)
            }
        }
    }
    return out
}

/**
 * Fresh clips = clips known to still be valid after [changed] propagated.
 *
 * Complement of [staleClips] on the set of clips with a non-empty [Clip.sourceBinding].
 * Clips without a binding are excluded from "fresh" because we cannot prove they're
 * valid — they simply aren't part of the incremental-compile decision.
 */
fun Project.freshClips(changed: Set<SourceNodeId>): Set<ClipId> {
    val stale = staleClips(changed)
    val out = LinkedHashSet<ClipId>()
    for (track in timeline.tracks) {
        for (clip in track.clips) {
            if (clip.sourceBinding.isEmpty()) continue
            if (clip.id !in stale) out.add(clip.id)
        }
    }
    return out
}

/**
 * Per-clip stale report — emitted by [staleClipsFromLockfile] / surfaced by the
 * `find_stale_clips` tool.
 *
 * @property clipId The stale clip on the timeline.
 * @property assetId The asset that clip plays.
 * @property changedSourceIds Source-node ids whose current `contentHash` differs
 *   from what the lockfile snapshotted at generation time. These are the
 *   *direct* causes — transitive descendants are not enumerated, so the report
 *   stays compact for chatty graphs.
 */
data class StaleClipReport(
    val clipId: ClipId,
    val assetId: AssetId,
    val changedSourceIds: Set<SourceNodeId>,
)

/**
 * Lockfile-driven stale-clip detection (VISION §3.2). Walks every clip on the
 * timeline; for each, looks up the lockfile entry that produced its asset and
 * compares the snapshotted `sourceContentHashes` to the project's *current*
 * `Source` hashes. A mismatch on any bound node flags the clip stale.
 *
 * This is the lane the agent's `find_stale_clips` tool reads. Clips without a
 * matching lockfile entry (e.g. imported media), or whose entry has an empty
 * snapshot (legacy entries written before the snapshot field existed), are
 * silently skipped — there's no anchor to compare against. Imported media is
 * never AIGC-stale by definition; legacy entries are an "unknown" bucket we
 * decline to lie about.
 *
 * Returns a list rather than a map so callers preserve the timeline ordering
 * naturally (oldest clip first). Empty list = nothing stale.
 */
fun Project.staleClipsFromLockfile(): List<StaleClipReport> {
    if (lockfile.entries.isEmpty()) return emptyList()
    val currentHashByNode: Map<SourceNodeId, String> =
        source.nodes.associate { it.id to it.contentHash }
    val out = mutableListOf<StaleClipReport>()
    for (track in timeline.tracks) {
        for (clip in track.clips) {
            val assetId = when (clip) {
                is Clip.Video -> clip.assetId
                is Clip.Audio -> clip.assetId
                is Clip.Text -> null
            } ?: continue
            val entry = lockfile.findByAssetId(assetId) ?: continue
            if (entry.sourceContentHashes.isEmpty()) continue
            val changed = LinkedHashSet<SourceNodeId>()
            for ((nodeId, snapshot) in entry.sourceContentHashes) {
                val now = currentHashByNode[nodeId] ?: continue
                if (now != snapshot) changed += nodeId
            }
            if (changed.isNotEmpty()) {
                out += StaleClipReport(
                    clipId = clip.id,
                    assetId = assetId,
                    changedSourceIds = changed,
                )
            }
        }
    }
    return out
}
