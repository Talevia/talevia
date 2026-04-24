package io.talevia.core.domain.render

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.platform.OutputSpec
import io.talevia.core.util.fnv1a64Hex

/**
 * Stable fingerprint for [ClipRenderCache] entries. A mezzanine is reusable iff
 * the same clip renders the same bytes at the same output profile — which
 * requires:
 *
 * 1. **Clip shape.** Canonical JSON over the full [Clip.Video] record: assetId,
 *    sourceRange, filters, transforms, sourceBinding. Any field change (e.g.
 *    user tweaks brightness) perturbs the JSON and forces a re-render.
 *
 * 2. **Transition context.** [TransitionFades] describes head / tail fades
 *    inherited from neighbouring transition clips. The clip's own JSON knows
 *    nothing about its neighbours, so this must be folded in explicitly — an
 *    edit that moves a transition onto/off this clip's boundary changes the
 *    fade envelope without touching the clip's fields.
 *
 * 3. **Bound-source deep hashes.** When a [Clip.Video] is AIGC-produced, its
 *    visual content depends on upstream [SourceNode]s (character_ref,
 *    style_bible, …). A source edit that drifts the clip's bound deep hash
 *    must invalidate the mezzanine even if the clip's JSON (assetId, filters)
 *    stays byte-identical — the file on disk behind the assetId is now stale.
 *    Caller supplies the per-bound-node deep hash map from
 *    [io.talevia.core.domain.source.deepContentHashOf]; an empty map (imported
 *    media, pre-lockfile clips) is fine and folds to an empty segment.
 *
 * 4. **Output profile essentials.** Resolution, fps, video/audio codec +
 *    bitrate — the encode parameters baked into the mezzanine. A re-export at
 *    a different profile invalidates; a re-export at the same profile hits
 *    the cache. `outputPath` and `container` are intentionally excluded so
 *    mezzanines survive moving the export target within the same profile.
 *
 * 5. **Engine id.** Two engines produce byte-different mezzanines at the same
 *    [OutputSpec] (FFmpeg's x264 ≠ Media3's hardware-accelerated codec ≠
 *    AVFoundation's AVAssetWriter default), so a FFmpeg-produced `.mp4`
 *    must NOT serve a Media3 request even when every other axis matches.
 *    Phase-1 decision (`docs/decisions/2026-04-23-export-incremental-render-phase-1-cache-key-design.md`)
 *    specified this as the missing axis; pre-engine-id entries in live
 *    `ClipRenderCache` instances will ghost-miss (fingerprint won't
 *    recompute to the old value), `mezzaninePresent(path)` catches them
 *    as the safety net, and the next `gc-render-cache` sweeps the
 *    orphans off disk. One-time migration cost.
 *
 * Hash is FNV-1a 64-bit hex (matches [RenderCache]'s fingerprint derivation
 * — the 2^64 space is plenty for per-project entry counts in the dozens to
 * low thousands).
 *
 * Segment order is load-bearing: `clip | fades | src | out | engine`. Any
 * reorder mass-invalidates the cache for zero behaviour change. New axes
 * append at the end.
 */
fun clipMezzanineFingerprint(
    clip: Clip.Video,
    fades: TransitionFades?,
    boundSourceDeepHashes: Map<SourceNodeId, String>,
    output: OutputSpec,
    engineId: String,
): String {
    val json = JsonConfig.default
    val canonical = buildString {
        append("clip=")
        append(json.encodeToString(Clip.Video.serializer(), clip))
        append("|fades=")
        append("h=").append(fades?.headFade?.inWholeNanoseconds ?: -1L)
        append(",t=").append(fades?.tailFade?.inWholeNanoseconds ?: -1L)
        append("|src=")
        // Sort for determinism — Map iteration order is implementation-defined
        // on some targets (JS, historically) and we want the fingerprint to
        // be invariant under equivalent binding reorderings.
        val sorted = boundSourceDeepHashes.entries.sortedBy { it.key.value }
        for ((k, v) in sorted) {
            append(k.value).append("=").append(v).append(";")
        }
        append("|out=")
        append("res=").append(output.resolution.width).append('x').append(output.resolution.height)
        append(",fps=").append(output.frameRate)
        append(",vc=").append(output.videoCodec)
        append(",vb=").append(output.videoBitrate)
        append(",ac=").append(output.audioCodec)
        append(",ab=").append(output.audioBitrate)
        append("|engine=").append(engineId)
    }
    return fnv1a64Hex(canonical)
}
