package io.talevia.core.domain

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
