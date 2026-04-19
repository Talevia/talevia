package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.Track

/**
 * Replace an existing track in-place so UI-visible track ordering stays stable.
 * If the track does not exist yet, append it to the end of the list.
 */
internal fun upsertTrackPreservingOrder(
    tracks: List<Track>,
    replacement: Track,
): List<Track> {
    val index = tracks.indexOfFirst { it.id == replacement.id }
    if (index < 0) return tracks + replacement
    return tracks.mapIndexed { currentIndex, track ->
        if (currentIndex == index) replacement else track
    }
}
