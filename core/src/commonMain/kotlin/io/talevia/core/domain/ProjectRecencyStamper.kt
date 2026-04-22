package io.talevia.core.domain

/**
 * Stamps `updatedAtEpochMs` on each clip / track / asset based on a structural
 * diff against a prior [Project] snapshot. Centralises the recency rule so the
 * 14+ mutation tools don't each have to remember to bookkeep:
 *
 *  - brand-new entity (id absent in [prior]) → `now`
 *  - structurally unchanged entity (all non-recency fields equal) →
 *    preserve old stamp (or `now` if the old stamp was null / blob predated recency)
 *  - structurally changed entity → `now`
 *
 * Track stamps cascade from clips: a track whose own fields AND clips list
 * membership are unchanged but whose individual clip content changed still
 * counts as "touched". That matches how agents use `sortBy="recent"` for
 * orientation ("what did I just edit?").
 *
 * Pure function — no I/O, no clock — so it lives in commonMain and is reused
 * by both the file-based store and any test rig. Recency only inspects
 * timeline + assets, so callers can safely pass a slim variant of [prior]
 * without snapshots / lockfile / clipRenderCache.
 */
internal object ProjectRecencyStamper {

    fun stamp(project: Project, prior: Project?, now: Long): Project {
        val oldClipsById: Map<String, Clip> = prior?.timeline?.tracks
            ?.flatMap { it.clips }
            ?.associateBy { it.id.value }
            ?: emptyMap()
        val oldTracksById: Map<String, Track> = prior?.timeline?.tracks
            ?.associateBy { it.id.value }
            ?: emptyMap()
        val oldAssetsById: Map<String, MediaAsset> = prior?.assets?.associateBy { it.id.value } ?: emptyMap()

        val newTracks = project.timeline.tracks.map { newTrack ->
            val stampedClips = newTrack.clips.map { stampClip(it, oldClipsById[it.id.value], now) }
            val trackWithClips = withClips(newTrack, stampedClips)
            stampTrack(trackWithClips, oldTracksById[newTrack.id.value], now)
        }
        val newAssets = project.assets.map { stampAsset(it, oldAssetsById[it.id.value], now) }
        return project.copy(
            timeline = project.timeline.copy(tracks = newTracks),
            assets = newAssets,
        )
    }

    private fun stampClip(new: Clip, old: Clip?, now: Long): Clip {
        val stamp = when {
            old == null -> now
            clipStructure(new) == clipStructure(old) -> old.updatedAtEpochMs ?: now
            else -> now
        }
        return applyClipStamp(new, stamp)
    }

    private fun stampTrack(new: Track, old: Track?, now: Long): Track {
        val stamp = when {
            old == null -> now
            trackStructure(new) == trackStructure(old) -> old.updatedAtEpochMs ?: now
            else -> now
        }
        return applyTrackStamp(new, stamp)
    }

    private fun stampAsset(new: MediaAsset, old: MediaAsset?, now: Long): MediaAsset {
        val stamp = when {
            old == null -> now
            new.copy(updatedAtEpochMs = null) == old.copy(updatedAtEpochMs = null) -> old.updatedAtEpochMs ?: now
            else -> now
        }
        return new.copy(updatedAtEpochMs = stamp)
    }

    private fun clipStructure(c: Clip): Clip = applyClipStamp(c, null)

    private fun trackStructure(t: Track): Track = applyTrackStamp(
        withClips(t, t.clips.map(::clipStructure)),
        null,
    )

    private fun applyClipStamp(c: Clip, stamp: Long?): Clip = when (c) {
        is Clip.Video -> c.copy(updatedAtEpochMs = stamp)
        is Clip.Audio -> c.copy(updatedAtEpochMs = stamp)
        is Clip.Text -> c.copy(updatedAtEpochMs = stamp)
    }

    private fun applyTrackStamp(t: Track, stamp: Long?): Track = when (t) {
        is Track.Video -> t.copy(updatedAtEpochMs = stamp)
        is Track.Audio -> t.copy(updatedAtEpochMs = stamp)
        is Track.Subtitle -> t.copy(updatedAtEpochMs = stamp)
        is Track.Effect -> t.copy(updatedAtEpochMs = stamp)
    }

    private fun withClips(t: Track, clips: List<Clip>): Track = when (t) {
        is Track.Video -> t.copy(clips = clips)
        is Track.Audio -> t.copy(clips = clips)
        is Track.Subtitle -> t.copy(clips = clips)
        is Track.Effect -> t.copy(clips = clips)
    }
}
