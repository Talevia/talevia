package io.talevia.core.domain

import io.talevia.core.ClipId
import io.talevia.core.SourceNodeId
import io.talevia.core.platform.OutputSpec
import kotlinx.serialization.Serializable

/**
 * Incremental render-plan capstone (M5 §3.2 criterion #1). Folds the
 * three M-stage primitives — M1's source binding closure
 * ([clipsBoundTo]), M2's lockfile staleness ([staleClipsFromLockfile]),
 * and M5 #2's render-cache staleness ([renderStaleClips]) — into a
 * single 3-bucket classification ("re-AIGC", "only-render",
 * "unchanged") given a set of source-node edits.
 *
 * Forward / backward / render lanes live in their own sibling files:
 *   - `ProjectStalenessCommon.kt` — `clipsBoundTo`, `staleClips`,
 *     `freshClips`, `autoRegenHint`.
 *   - `ProjectStalenessLockfile.kt` — `staleClipsFromLockfile`.
 *   - `ProjectStalenessRender.kt` — `renderStaleClips`.
 *
 * Bullet: `debt-split-project-staleness`.
 */

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
