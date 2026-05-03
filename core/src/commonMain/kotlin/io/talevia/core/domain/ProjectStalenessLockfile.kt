package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Modality
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.deepContentHashOfFor
import io.talevia.core.domain.source.modalityNeeds

/**
 * Lockfile-driven AIGC-staleness lane (VISION §3.2). Compares each clip's
 * lockfile-snapshotted `sourceContentHashes` to the project's *current*
 * `Source` deep hashes; mismatch on any bound node flags the clip
 * AIGC-stale (asset bytes invalid, must regenerate before re-rendering).
 *
 * Distinct from the render-cache lane in
 * `ProjectStalenessRender.kt` ([renderStaleClips]), which gates
 * mezzanine reuse independent of asset validity. The two lanes fold
 * together in `ProjectStalenessPlan.kt` ([incrementalPlan]). Forward-
 * index helpers (`clipsBoundTo`, `staleClips`, `autoRegenHint`) live in
 * `ProjectStalenessCommon.kt`.
 *
 * Bullet: `debt-split-project-staleness` (split out of the 528-LOC
 * `ProjectStaleness.kt` originally; lane-axis split keeps each file
 * focused on one staleness story).
 */

/**
 * Per-clip stale report — emitted by [staleClipsFromLockfile] / surfaced by the
 * `project_query(select=stale_clips)` tool.
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
 * This is the lane the agent's `project_query(select=stale_clips)` tool reads. Clips without a
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
    if (lockfile.isEmpty()) return emptyList()
    // Deep-hash comparison (VISION §5.1 transitive propagation): each
    // snapshotted binding id's **current** deep content hash (own
    // contentHash folded with ancestors') is recomputed and diffed against
    // the recorded snapshot. Missing node → deleted source → non-comparable
    // (skipped), matching the pre-transitive shallow-hash behaviour for
    // removed nodes.
    //
    // Modality lane (VISION §5.5 cross-modal staleness): when an entry
    // carries `sourceContentHashesByModality`, we compare only the slice
    // the consuming clip's modality actually depends on — flipping a
    // `character_ref.voiceId` does not stale visual-only clips bound to
    // the character, and vice versa. Legacy entries (empty modality map)
    // fall back to the whole-body comparison. Per-modality caches are
    // independent because each modality folds different shallow hashes;
    // sharing a single cache would alias across modalities.
    val fullCache = mutableMapOf<SourceNodeId, String>()
    val visualCache = mutableMapOf<SourceNodeId, String>()
    val audioCache = mutableMapOf<SourceNodeId, String>()
    val out = mutableListOf<StaleClipReport>()
    for (track in timeline.tracks) {
        for (clip in track.clips) {
            val assetId = when (clip) {
                is Clip.Video -> clip.assetId
                is Clip.Audio -> clip.assetId
                is Clip.Text -> null
            } ?: continue
            val entry = lockfile.findByAssetId(assetId) ?: continue
            val byModality = entry.sourceContentHashesByModality
            val useModality = byModality.isNotEmpty()
            if (!useModality && entry.sourceContentHashes.isEmpty()) continue
            val clipModality = clip.modalityNeeds
            val changed = LinkedHashSet<SourceNodeId>()
            if (useModality) {
                val cache = when (clipModality) {
                    Modality.Visual -> visualCache
                    Modality.Audio -> audioCache
                }
                for ((nodeId, snapshot) in byModality) {
                    if (source.byId[nodeId] == null) continue
                    val nowDeep = source.deepContentHashOfFor(nodeId, clipModality, cache)
                    if (nowDeep != snapshot.forModality(clipModality)) changed += nodeId
                }
            } else {
                for ((nodeId, snapshot) in entry.sourceContentHashes) {
                    if (source.byId[nodeId] == null) continue
                    val nowDeep = source.deepContentHashOf(nodeId, fullCache)
                    if (nowDeep != snapshot) changed += nodeId
                }
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
