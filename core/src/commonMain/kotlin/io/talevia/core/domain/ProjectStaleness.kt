package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.render.clipMezzanineFingerprint
import io.talevia.core.domain.render.transitionFadesPerClip
import io.talevia.core.domain.source.Modality
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.deepContentHashOfFor
import io.talevia.core.domain.source.modalityNeeds
import io.talevia.core.domain.source.stale
import io.talevia.core.platform.OutputSpec
import kotlinx.serialization.Serializable

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
    if (lockfile.entries.isEmpty()) return emptyList()
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

/**
 * 3-bucket "what work does the next export need?" plan (M5 §3.2
 * criterion #1). Folds the three M-stage primitives — M1's source
 * binding closure ([clipsBoundTo]), M2's lockfile staleness
 * ([staleClipsFromLockfile]), and M5 #2's render-cache staleness
 * ([renderStaleClips]) — into a single classification given a set of
 * source-node edits. Each affected clip lands in exactly one bucket;
 * the buckets are pairwise disjoint and their union equals the set of
 * clips reachable from [changedSources] via the source DAG closure.
 *
 * @property reAigc clips that need their AIGC asset regenerated AND
 *   then re-rendered. The bound source's lockfile snapshot drifted
 *   (`staleClipsFromLockfile` reports them) — both the asset bytes
 *   and the mezzanine are invalid.
 * @property onlyRender clips that need only re-rendering, not re-AIGC.
 *   The bound source's deepHash drifted (perturbing the per-clip
 *   mezzanine fingerprint) but the lockfile snapshot didn't see the
 *   change (e.g. imported clip with manual binding, legacy lockfile
 *   entry with empty `sourceContentHashes`, or a binding the entry
 *   never recorded). Asset bytes still valid; mezzanine cache miss.
 * @property unchanged clips bound to changed sources that are
 *   neither AIGC- nor render-stale. The mezzanine cache holds an
 *   entry whose fingerprint matches the current state — full reuse
 *   possible. Common when a previous export already produced the
 *   mezzanine and no relevant axis (clip JSON, output, engine) has
 *   shifted since.
 *
 * Invariants:
 *   - `reAigc ∩ onlyRender = ∅`, `reAigc ∩ unchanged = ∅`,
 *     `onlyRender ∩ unchanged = ∅`.
 *   - `reAigc ⊆ aigcStaleClips` (every reAigc entry is also
 *     [staleClipsFromLockfile]-reported).
 *   - `reAigc ⊆ renderStaleClips(output, engineId)` (lockfile drift
 *     implies fingerprint drift on the clips' bound segment, so a
 *     reAigc entry is also render-stale; the function classifies
 *     reAigc first, leaving the onlyRender bucket strict-disjoint).
 *   - The total `reAigc.size + onlyRender.size + unchanged.size`
 *     equals the number of bound clips reachable from
 *     [changedSources] via [clipsBoundTo]. Empty
 *     `changedSources` → all three buckets empty (no affected
 *     clips to classify).
 */
@Serializable
data class IncrementalPlan(
    val reAigc: List<ClipId>,
    val onlyRender: List<ClipId>,
    val unchanged: List<ClipId>,
) {
    /** Total work units = reAigc + onlyRender. Convenience for "how many clips re-render?" */
    val workCount: Int get() = reAigc.size + onlyRender.size

    /** Whether the plan reports zero work (no clips need re-AIGC or re-render). */
    val isEmpty: Boolean get() = reAigc.isEmpty() && onlyRender.isEmpty()
}

/**
 * Compute the 3-bucket incremental render plan for a set of source-node
 * edits. M5 §3.2 criterion #1's capstone — joins the source DAG closure
 * (M1) with both staleness lanes (M2 lockfile + M5 #2 render-cache) so
 * the agent can reason about "what changes if I edit X?" before
 * launching an export.
 *
 * Algorithm:
 *   1. Walk [clipsBoundTo] for each id in [changedSources] and union
 *      the resulting [ClipId]s — this is the affected set.
 *   2. Compute [staleClipsFromLockfile] (lockfile-stale set, project-
 *      wide) and [renderStaleClips] (render-stale set at the given
 *      output / engineId).
 *   3. For each clip in the affected set:
 *      - if it's lockfile-stale → reAigc bucket.
 *      - else if it's render-stale → onlyRender bucket.
 *      - else → unchanged bucket.
 *
 * Note: render-staleness gating is scoped to the per-clip cache
 * eligibility (single Video track timelines per
 * [renderStaleClips]'s contract). For non-eligible shapes,
 * `renderStaleClips` returns empty — every "would-be" only-render
 * clip falls through to `unchanged`. Document the limitation; the
 * fix is to land per-clip caching on more shapes (multi-track,
 * cross-engine), not to widen the staleness query.
 *
 * Empty [changedSources] → all three buckets empty (the answer to
 * "what changes if I edit nothing?"). Unrelated source edits (no
 * clips bound to any of them) likewise → empty plan.
 *
 * `staleClipsFromLockfile` and `renderStaleClips` are computed once
 * per call regardless of `changedSources` size — they're project-wide
 * snapshots, not per-source. Callers that batch many edits in one
 * plan amortise the staleness scans.
 */
fun Project.incrementalPlan(
    changedSources: Set<SourceNodeId>,
    output: OutputSpec,
    engineId: String,
): IncrementalPlan {
    if (changedSources.isEmpty()) {
        return IncrementalPlan(emptyList(), emptyList(), emptyList())
    }

    // Affected clip set — union of clipsBoundTo() across changed
    // sources. clipsBoundTo handles transitive closure via the
    // source DAG, so a grandparent edit reaches its grandchild's
    // bound clips just like the lockfile-staleness lane does.
    val affected = LinkedHashSet<ClipId>()
    for (src in changedSources) {
        for (report in clipsBoundTo(src)) {
            affected += report.clipId
        }
    }
    if (affected.isEmpty()) {
        return IncrementalPlan(emptyList(), emptyList(), emptyList())
    }

    // Compute both staleness sets project-wide once; intersect with
    // affected during classification.
    val aigcStale = staleClipsFromLockfile().asSequence().map { it.clipId }.toSet()
    val renderStale = renderStaleClips(output, engineId).asSequence().map { it.clipId }.toSet()

    val reAigc = mutableListOf<ClipId>()
    val onlyRender = mutableListOf<ClipId>()
    val unchanged = mutableListOf<ClipId>()
    for (clipId in affected) {
        when {
            clipId in aigcStale -> reAigc.add(clipId)
            clipId in renderStale -> onlyRender.add(clipId)
            else -> unchanged.add(clipId)
        }
    }
    return IncrementalPlan(reAigc = reAigc, onlyRender = onlyRender, unchanged = unchanged)
}

/**
 * Per-clip render-staleness report — emitted by [renderStaleClips] (M5
 * §3.2 criterion #2). Distinct from [StaleClipReport] which carries
 * AIGC-staleness data: this one is the **render-cache** side of the
 * staleness story.
 *
 * @property clipId The clip whose computed [clipMezzanineFingerprint]
 *   doesn't match any entry in [Project.clipRenderCache].
 * @property fingerprint The fingerprint we'd compute for this clip at
 *   the queried [OutputSpec] + engineId given current project state
 *   (clip JSON, transition fades, bound-source deep hashes). Useful for
 *   debugging "why did this miss?" — the caller can compare against
 *   `clipRenderCache.entries.map { it.fingerprint }` to see what cached
 *   fingerprints are close. Always non-empty (the FNV-1a 64-bit hex
 *   produces 16-char strings); doesn't aim for a stable cross-cycle
 *   value beyond what the fingerprint helper itself guarantees.
 */
@Serializable
data class RenderStaleClipReport(
    val clipId: ClipId,
    val fingerprint: String,
)

/**
 * Render-cache staleness lane (M5 §3.2 criterion #2). For each Video
 * clip on the timeline whose computed [clipMezzanineFingerprint] for
 * the given [output] + [engineId] doesn't match any entry in
 * [Project.clipRenderCache], report it as render-stale: a re-export
 * to that output spec on that engine would call `renderClip` for
 * the clip. The complement (clips with a matching fingerprint) is
 * the "freely-reusable" set the per-clip cache short-circuits at
 * export time.
 *
 * Distinct from [staleClipsFromLockfile]:
 *  - [staleClipsFromLockfile] gates **AIGC asset reuse**. It walks
 *    each clip's lockfile entry's source-binding hash snapshot and
 *    flags drift — the clip's underlying asset bytes are no longer
 *    valid for its bound source state.
 *  - [renderStaleClips] gates **per-clip mezzanine reuse**. It
 *    walks the per-clip render cache and flags clips whose current
 *    fingerprint isn't memoised. A render-stale clip's *asset bytes*
 *    might be perfectly fresh (lockfile-fresh); only the *rendered
 *    mezzanine* (with current filters / fades / output profile / engine)
 *    is missing.
 *
 * The two axes are orthogonal but not independent: a lockfile-stale
 * clip is *also* render-stale on every output (drift in
 * `boundSourceDeepHashes` perturbs segment 3 of the fingerprint).
 * The reverse doesn't hold: an effect-param edit (which doesn't touch
 * any source binding) leaves the lockfile fresh but invalidates every
 * cached mezzanine for the touched clip. M5 §3.2's incremental-plan
 * primitive (M5 #1) folds both axes into a single 3-bucket query
 * ("re-AIGC", "only-render", "unchanged"); this function is the
 * "render-stale" half of that input.
 *
 * Scope: only **single-Video-track** timelines are reported on. Multi-
 * Video-track / audio-only / empty / mixed-clip-kind timelines fall
 * back to whole-timeline render at export time (see
 * [io.talevia.core.tool.builtin.video.export.timelineFitsPerClipPath])
 * — the per-clip cache doesn't apply, so per-clip staleness is
 * undefined for those shapes. We return an empty list rather than
 * "everything stale" because the whole-timeline path's [RenderCache]
 * covers caching for those shapes; reporting "all clips stale" would
 * mislead callers into thinking nothing is cacheable.
 *
 * Empty result has two meanings the caller has to disambiguate via
 * the project's shape:
 *   - eligible shape + zero return → every clip's fingerprint is in
 *     the cache; full reuse possible.
 *   - non-eligible shape → per-clip cache doesn't apply; consult
 *     [RenderCache] / `select=stale_clips` for that timeline's
 *     staleness story instead.
 */
fun Project.renderStaleClips(
    output: OutputSpec,
    engineId: String,
): List<RenderStaleClipReport> {
    // Eligibility: mirrors `timelineFitsPerClipPath` semantics —
    // exactly one Video track, all of its clips Clip.Video, at least
    // one. Replicated inline rather than imported to keep this file
    // off the export tool's package surface (the staleness lane is
    // domain, not tool).
    val videoTracks = timeline.tracks.filterIsInstance<Track.Video>()
    if (videoTracks.size != 1) return emptyList()
    val videoClips = videoTracks[0].clips.filterIsInstance<Clip.Video>()
    if (videoClips.isEmpty()) return emptyList()
    if (videoClips.size != videoTracks[0].clips.size) return emptyList()

    val fadesByClipId = timeline.transitionFadesPerClip(videoClips)
    // Deep-hash cache shared across the loop so a style_bible parent
    // bound by N character_refs walks once. Same pattern
    // `runPerClipRender` uses on the actual export hot path.
    val deepHashCache = mutableMapOf<SourceNodeId, String>()
    val out = mutableListOf<RenderStaleClipReport>()
    for (clip in videoClips) {
        val boundHashes = clip.sourceBinding
            .filter { it in source.byId }
            .associateWith { source.deepContentHashOf(it, deepHashCache) }
        val fingerprint = clipMezzanineFingerprint(
            clip = clip,
            fades = fadesByClipId[clip.id],
            boundSourceDeepHashes = boundHashes,
            output = output,
            engineId = engineId,
        )
        if (clipRenderCache.findByFingerprint(fingerprint) == null) {
            out += RenderStaleClipReport(clipId = clip.id, fingerprint = fingerprint)
        }
    }
    return out
}
