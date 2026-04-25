package io.talevia.core.tool.builtin.video.export

import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project

/**
 * Output-side helpers for [io.talevia.core.tool.builtin.video.ExportTool]:
 * MIME-type classification + per-clip cost attribution. Both pure functions
 * of inputs — extracted so the dispatcher tool stays focused on permission
 * gates / cache lookup / engine handoff and not data plumbing.
 */

/** MIME type from filename extension; falls back to a generic container. */
internal fun mimeTypeFor(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "gif" -> "image/gif"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

/**
 * Build the per-clip cost map for `Output.perClipCostCents` +
 * `Output.totalCostCents`.
 *
 * Lookup is via `lockfile.byAssetId[clip.assetId]` — exact one-to-one
 * keying. A clip whose asset wasn't AIGC-produced has no lockfile entry
 * → null cents (still in the map; the agent can see "this clip is
 * unpriced" distinct from "0 cents"). Clips sharing an asset report the
 * same cents per clip; the sum is intentional because each clip
 * references the same paid output.
 *
 * Returns the (map, total) pair so the caller doesn't have to reduce
 * twice. Empty inputs and clips without `assetId` (text clips) are
 * skipped — they'd never have a lockfile entry anyway.
 */
internal fun buildPerClipCostAttribution(project: Project): Pair<Map<String, Long?>, Long> {
    val perClip = mutableMapOf<String, Long?>()
    var total = 0L
    for (track in project.timeline.tracks) {
        for (clip in track.clips) {
            val assetId = when (clip) {
                is Clip.Video -> clip.assetId
                is Clip.Audio -> clip.assetId
                is Clip.Text -> null
            }
            val cents = assetId?.let { project.lockfile.byAssetId[it]?.costCents }
            perClip[clip.id.value] = cents
            if (cents != null) total += cents
        }
    }
    return perClip to total
}
