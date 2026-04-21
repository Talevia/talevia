package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.stale

/**
 * Project-layer half of the stale-propagation lane (VISION §3.2). Given a set of
 * source-node ids that changed, return the set of clip ids that must be re-rendered.
 *
 * A clip is reported stale iff its [Clip.sourceBinding] is **non-empty** and
 * intersects the source-layer transitive closure
 * ([io.talevia.core.domain.source.stale]). Clips with an empty binding — imported
 * media, hand-authored text, and every clip added before the source-binding
 * protocol was threaded through `add_clip` — are **out of scope for incremental
 * tracking** and returned in neither [staleClips] nor [freshClips]. The
 * "either / or" split deliberately leaves a third "unknown / not-tracked"
 * bucket: these clips don't oppose in the incremental-compile decision, so
 * callers downstream (`regenerate_stale_clips`, the `find_stale_clips` report)
 * leave them alone unless the user explicitly opts them in by binding a source
 * node.
 *
 * This mirrors [staleClipsFromLockfile], which likewise silently skips clips
 * without a lockfile anchor. The prior "empty binding → always stale" fallback
 * polluted the stale signal on every small-white project, noising up UI
 * reports with clips that had no `baseInputs` to regenerate from anyway.
 */
fun Project.staleClips(changed: Set<SourceNodeId>): Set<ClipId> {
    if (changed.isEmpty()) return emptySet()
    val staleNodes = source.stale(changed)
    if (staleNodes.isEmpty()) return emptySet()
    val out = LinkedHashSet<ClipId>()
    for (track in timeline.tracks) {
        for (clip in track.clips) {
            if (clip.sourceBinding.isEmpty()) continue // unbound → out of scope for incremental tracking
            if (clip.sourceBinding.any { it in staleNodes }) {
                out.add(clip.id)
            }
        }
    }
    return out
}

/**
 * Forward-index report — which clips bind a given source node (directly or
 * through DAG descent)? Used by the `project_query(select=clips_for_source)` tool and the
 * desktop SourcePanel downstream-clips view so users can answer "if I edit
 * this character_ref / style_bible, what will go stale?" *before* making the
 * edit.
 *
 * @property clipId the clip whose `sourceBinding` contains a node in the
 *   transitive closure of the query node.
 * @property trackId enclosing track id — handy for UI rendering / locate-on-
 *   timeline workflows.
 * @property assetId the asset the clip plays, or `null` for text clips.
 * @property directlyBound `true` iff the clip's `sourceBinding` contains the
 *   *queried* node id; `false` when the match came via a descendant.
 * @property boundVia subset of the clip's `sourceBinding` that lay inside the
 *   transitive closure of the queried node (includes the queried id itself
 *   when [directlyBound]). Lets the UI show "why is this clip bound?" with
 *   the exact mediating descendant(s).
 */
data class ClipsForSourceReport(
    val clipId: ClipId,
    val trackId: io.talevia.core.TrackId,
    val assetId: AssetId?,
    val directlyBound: Boolean,
    val boundVia: Set<SourceNodeId>,
)

/**
 * List every clip on the timeline whose `sourceBinding` intersects the
 * transitive-downstream closure of [sourceNodeId] in the project's source
 * DAG. Returns an empty list if the node is absent or no clips bind it.
 *
 * The VISION §5.1 rubric asks "改一个 source 节点（比如角色设定），下游哪些
 * clip / scene / artifact 会被标为 stale?". This is the forward answer to
 * that — `staleClipsFromLockfile` gives the backward answer ("we just
 * drifted; what's stale?"). Call this before an edit to preview impact;
 * call the stale variant after.
 *
 * Transitive descent uses [io.talevia.core.domain.source.stale] so the
 * same graph walk powers both the forward-preview and the backward-stale
 * lanes — single source of truth for "which nodes reach which other nodes".
 */
fun Project.clipsBoundTo(sourceNodeId: SourceNodeId): List<ClipsForSourceReport> {
    if (sourceNodeId !in source.byId) return emptyList()
    val closure = source.stale(setOf(sourceNodeId))
    if (closure.isEmpty()) return emptyList() // node not present (guarded above) or absent graph
    val out = mutableListOf<ClipsForSourceReport>()
    for (track in timeline.tracks) {
        for (clip in track.clips) {
            if (clip.sourceBinding.isEmpty()) continue
            val via = clip.sourceBinding intersect closure
            if (via.isEmpty()) continue
            val assetId = when (clip) {
                is Clip.Video -> clip.assetId
                is Clip.Audio -> clip.assetId
                is Clip.Text -> null
            }
            out += ClipsForSourceReport(
                clipId = clip.id,
                trackId = track.id,
                assetId = assetId,
                directlyBound = sourceNodeId in clip.sourceBinding,
                boundVia = via,
            )
        }
    }
    return out
}

/**
 * Fresh clips = clips known to still be valid after [changed] propagated.
 *
 * Complement of [staleClips] on the set of clips with a non-empty
 * [Clip.sourceBinding]. Clips without a binding are in neither set (the
 * "unknown / not-tracked" bucket) — we can neither prove they're valid nor
 * assert they're stale, and they aren't part of the incremental-compile
 * decision at all. Any clip returned in [freshClips] is known-fresh and safe
 * to skip; any clip returned in [staleClips] is known-stale and must be
 * re-rendered; everything else is outside the stale-propagation system.
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
