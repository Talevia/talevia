package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.stale
import kotlinx.serialization.Serializable

/**
 * Common-lane staleness primitives shared across the AIGC / render-cache /
 * incremental-plan lanes that live in sibling files. This file holds:
 *   - [AutoRegenHint] + [Project.autoRegenHint] — VISION §5.5 mutation-tool
 *     nudge.
 *   - [Project.staleClips] / [Project.freshClips] — forward source-DAG
 *     transitive closure.
 *   - [ClipsForSourceReport] + [Project.clipsBoundTo] — forward index used
 *     by `project_query(select=clips_for_source)` and the incremental plan.
 *
 * Split out of `ProjectStaleness.kt` (528 LOC) into 4 lane-specific files —
 * see [staleClipsFromLockfile] in `ProjectStalenessLockfile.kt`,
 * [renderStaleClips] in `ProjectStalenessRender.kt`, and [incrementalPlan]
 * in `ProjectStalenessPlan.kt`. Top-level extension functions stay in the
 * same `io.talevia.core.domain` package so all callers' imports are
 * unchanged. Bullet: `debt-split-project-staleness`.
 */

/**
 * VISION §5.5 auto-regen hint — attached to the `Output` of every
 * source-mutation tool that can plausibly stale existing clips. Lets the
 * agent follow up a source edit with a batched regeneration call
 * **without** having to first dispatch `project_query(select=stale_clips)` to see if it's
 * worth it. `null` hint = no stale clips (agent can skip the suggestion);
 * non-null = "here are N stale clips bound to the lockfile — the single
 * suggested next tool is [suggestedTool]".
 *
 * Why a typed field instead of just prose in `outputForLlm`: the desktop
 * + iOS UIs subscribe to typed Parts and show a "3 clips need regen"
 * banner without re-parsing English. The prose is also emitted (the LLM
 * consumes it), so the agent + the human UI stay in sync.
 *
 * @property staleClipCount Total stale clips in the project **after**
 *   this mutation landed — computed via [Project.staleClipsFromLockfile].
 *   Includes every clip whose bound source node(s) drifted from their
 *   lockfile snapshot, not just ones freshly staled by this call — we
 *   can't cleanly distinguish "staled by this edit" from "was already
 *   stale" without snapshotting before the mutation, and the practical
 *   answer the agent needs is "should I run regenerate_stale_clips
 *   now?" which is the same in both cases.
 * @property suggestedTool Always `"regenerate_stale_clips"` today. Kept
 *   as a field (not a bare hint) so a later cycle can vary the suggestion
 *   (e.g. point at `export(allowStale=true)` when the user's intent is
 *   "publish now, regen later") without changing the Output shape.
 */
@Serializable
data class AutoRegenHint(
    val staleClipCount: Int,
    val suggestedTool: String = "regenerate_stale_clips",
)

/**
 * Derive an [AutoRegenHint] from the current project state, or `null`
 * when nothing is stale. Centralises the "how should a source-mutation
 * tool nudge the agent toward regeneration?" decision in one place so
 * every mutation tool gets identical semantics. Call after the mutation
 * lands; the hint reflects post-mutation state.
 */
fun Project.autoRegenHint(): AutoRegenHint? {
    val stale = staleClipsFromLockfile()
    if (stale.isEmpty()) return null
    return AutoRegenHint(staleClipCount = stale.size)
}

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
 * callers downstream (`regenerate_stale_clips`, the `project_query(select=stale_clips)` report)
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
