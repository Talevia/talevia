package io.talevia.core.tool.builtin.video.export

import io.talevia.core.JsonConfig
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.render.ProvenanceManifest
import io.talevia.core.platform.OutputSpec
import io.talevia.core.util.fnv1a64Hex

/**
 * Pure deterministic helpers for [io.talevia.core.tool.builtin.video.ExportTool] +
 * [io.talevia.core.tool.builtin.video.ExportDryRunTool]. Both tools key off the
 * same `(timeline, output spec)` fingerprint and stamp the same provenance
 * manifest into rendered files; keeping the math here eliminates the drift
 * window where one tool's bytes can flip but not the other's.
 */

/**
 * Cache fingerprint over the canonical timeline JSON plus the load-bearing
 * fields of the [OutputSpec]. `fnv1a64Hex` is platform-stable and gives a
 * 16-char hex string — short enough to embed in filenames, long enough to
 * collide only at astronomical project counts. AIGC regeneration produces a
 * new assetId on the timeline, so fresh regenerations invalidate the cache
 * naturally; the stale-guard catches the orthogonal case where source
 * drifted but the agent hasn't regenerated yet.
 */
internal fun fingerprintOf(timeline: Timeline, output: OutputSpec): String {
    val json = JsonConfig.default
    val canonical = buildString {
        append(json.encodeToString(Timeline.serializer(), timeline))
        append('|')
        append("path=").append(output.targetPath)
        append("|res=").append(output.resolution.width).append('x').append(output.resolution.height)
        append("|fps=").append(output.frameRate)
        append("|vc=").append(output.videoCodec)
        append("|ac=").append(output.audioCodec)
    }
    return fnv1a64Hex(canonical)
}

/**
 * Build the [ProvenanceManifest] for [project]. Two hashes:
 *  - `timelineHash` over the canonical Timeline JSON alone (no output
 *    spec, no lockfile) — a Timeline edit flips it, nothing else.
 *  - `lockfileHash` over the canonical `Lockfile` JSON — new AIGC
 *    generation or pin/unpin flips it.
 *
 * Both via `JsonConfig.default` so bit-exact re-exports produce
 * bit-exact hashes (ExportDeterminismTest relies on this).
 */
internal fun provenanceOf(project: Project): ProvenanceManifest {
    val json = JsonConfig.default
    val timelineHash = fnv1a64Hex(json.encodeToString(Timeline.serializer(), project.timeline))
    val lockfileHash = fnv1a64Hex(json.encodeToString(Lockfile.serializer(), project.lockfile))
    return ProvenanceManifest(
        projectId = project.id.value,
        timelineHash = timelineHash,
        lockfileHash = lockfileHash,
    )
}
