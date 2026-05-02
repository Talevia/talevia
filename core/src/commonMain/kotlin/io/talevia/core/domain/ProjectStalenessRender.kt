package io.talevia.core.domain

import io.talevia.core.ClipId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.render.clipMezzanineFingerprint
import io.talevia.core.domain.render.transitionFadesPerClip
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.platform.OutputSpec
import kotlinx.serialization.Serializable

/**
 * Render-cache staleness lane (M5 §3.2 criterion #2). Compares each
 * eligible Video clip's computed [clipMezzanineFingerprint] against
 * [Project.clipRenderCache]; mismatch flags the clip render-stale —
 * a re-export to that output spec on that engine would call
 * `renderClip` for the clip.
 *
 * Distinct from the AIGC-asset lane in `ProjectStalenessLockfile.kt`
 * ([staleClipsFromLockfile]), which gates asset reuse independently
 * of mezzanine reuse. The two lanes fold together in
 * `ProjectStalenessPlan.kt` ([incrementalPlan]).
 *
 * Bullet: `debt-split-project-staleness`.
 */

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
