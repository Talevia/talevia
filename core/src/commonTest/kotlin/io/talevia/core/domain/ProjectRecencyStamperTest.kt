package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Pure-function tests for the recency stamper. The same rules are exercised
 * end-to-end through the project store in `ProjectStoreRecencyStampingTest`,
 * but those tests need a SQLDelight driver. These tests live in commonTest
 * so the rule itself is verified on every platform.
 */
class ProjectRecencyStamperTest {

    private fun videoTrack(
        id: String,
        clipIds: List<String> = emptyList(),
        trackStamp: Long? = null,
        clipStamp: Long? = null,
    ): Track.Video = Track.Video(
        id = TrackId(id),
        clips = clipIds.mapIndexed { idx, cid ->
            Clip.Video(
                id = ClipId(cid),
                timeRange = TimeRange((idx * 5).seconds, 5.seconds),
                sourceRange = TimeRange(0.seconds, 5.seconds),
                assetId = AssetId("a"),
                updatedAtEpochMs = clipStamp,
            )
        },
        updatedAtEpochMs = trackStamp,
    )

    private fun asset(id: String, stamp: Long? = null): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id"),
        metadata = MediaMetadata(duration = 5.seconds),
        updatedAtEpochMs = stamp,
    )

    private fun project(track: Track.Video, assetIds: List<String> = listOf("a")): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(tracks = listOf(track)),
        assets = assetIds.map { asset(it) },
    )

    @Test
    fun stampsAllEntitiesWhenPriorIsNull() {
        val now = 1_000L
        val incoming = project(videoTrack("t", listOf("c1", "c2")))
        val stamped = ProjectRecencyStamper.stamp(incoming, prior = null, now = now)

        val track = stamped.timeline.tracks.single() as Track.Video
        assertEquals(now, track.updatedAtEpochMs)
        assertEquals(now, track.clips.first().updatedAtEpochMs)
        assertEquals(now, track.clips.last().updatedAtEpochMs)
        assertEquals(now, stamped.assets.single().updatedAtEpochMs)
    }

    @Test
    fun preservesStampsOnStructurallyIdenticalReupsert() {
        val first = ProjectRecencyStamper.stamp(project(videoTrack("t", listOf("c1"))), prior = null, now = 1_000L)

        // Re-stamp with same shape but later wall clock; structural diff should
        // resolve to "preserve old stamp" because nothing changed.
        val second = ProjectRecencyStamper.stamp(first, prior = first, now = 9_999L)

        val track = second.timeline.tracks.single() as Track.Video
        assertEquals(1_000L, track.updatedAtEpochMs)
        assertEquals(1_000L, track.clips.single().updatedAtEpochMs)
        assertEquals(1_000L, second.assets.single().updatedAtEpochMs)
    }

    @Test
    fun restampsChangedClipAndPropagatesToTrack() {
        val first = ProjectRecencyStamper.stamp(
            project(videoTrack("t", listOf("c1", "c2"))),
            prior = null,
            now = 1_000L,
        )

        // Mutate c2 only.
        val mutatedTrack = (first.timeline.tracks.single() as Track.Video).let { tr ->
            tr.copy(
                clips = tr.clips.map { c ->
                    if (c.id.value == "c2") (c as Clip.Video).copy(timeRange = TimeRange(99.seconds, 5.seconds))
                    else c
                },
            )
        }
        val mutated = first.copy(timeline = first.timeline.copy(tracks = listOf(mutatedTrack)))

        val second = ProjectRecencyStamper.stamp(mutated, prior = first, now = 2_000L)
        val track = second.timeline.tracks.single() as Track.Video
        // c2 changed → c2 stamp = now; c1 untouched → preserved
        assertEquals(1_000L, track.clips.first { it.id.value == "c1" }.updatedAtEpochMs)
        assertEquals(2_000L, track.clips.first { it.id.value == "c2" }.updatedAtEpochMs)
        // Track cascade: a clip in the track changed → track stamp = now
        assertEquals(2_000L, track.updatedAtEpochMs)
    }

    @Test
    fun newEntitiesGetNowEvenWhenPriorPresent() {
        val first = ProjectRecencyStamper.stamp(
            project(videoTrack("t", listOf("c1"))),
            prior = null,
            now = 1_000L,
        )

        val expandedTrack = (first.timeline.tracks.single() as Track.Video).let { tr ->
            tr.copy(
                clips = tr.clips + Clip.Video(
                    id = ClipId("c2"),
                    timeRange = TimeRange(50.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("a"),
                ),
            )
        }
        val expanded = first.copy(
            timeline = first.timeline.copy(tracks = listOf(expandedTrack)),
            assets = first.assets + asset("a2"),
        )

        val second = ProjectRecencyStamper.stamp(expanded, prior = first, now = 2_000L)
        val track = second.timeline.tracks.single() as Track.Video
        // c1 untouched → preserved
        assertEquals(1_000L, track.clips.first { it.id.value == "c1" }.updatedAtEpochMs)
        // c2 brand new → now
        assertEquals(2_000L, track.clips.first { it.id.value == "c2" }.updatedAtEpochMs)
        assertEquals(1_000L, second.assets.first { it.id.value == "a" }.updatedAtEpochMs)
        assertEquals(2_000L, second.assets.first { it.id.value == "a2" }.updatedAtEpochMs)
    }

    @Test
    fun nullStampInPriorIsTreatedAsNotYetTracked() {
        // Pre-recency blob: priorProject with null stamps.
        val prior = project(videoTrack("t", listOf("c1"), trackStamp = null, clipStamp = null))

        // Re-write same content; priorClipStamp is null → new stamp = now (not preserved-as-null).
        val stamped = ProjectRecencyStamper.stamp(prior, prior = prior, now = 5_000L)
        val track = stamped.timeline.tracks.single() as Track.Video
        assertEquals(5_000L, track.updatedAtEpochMs)
        assertEquals(5_000L, track.clips.single().updatedAtEpochMs)
    }

    @Test
    fun assetMutationDoesNotAffectTrackStamp() {
        val first = ProjectRecencyStamper.stamp(
            project(videoTrack("t", listOf("c1"))),
            prior = null,
            now = 1_000L,
        )

        // Mutate the asset metadata only.
        val mutatedAsset = first.assets.single().copy(
            metadata = first.assets.single().metadata.copy(comment = "edited"),
        )
        val mutated = first.copy(assets = listOf(mutatedAsset))

        val second = ProjectRecencyStamper.stamp(mutated, prior = first, now = 2_000L)
        // Track + clip unchanged → stamps preserved
        val track = second.timeline.tracks.single() as Track.Video
        assertEquals(1_000L, track.updatedAtEpochMs)
        assertEquals(1_000L, track.clips.single().updatedAtEpochMs)
        // Asset changed → restamped
        assertEquals(2_000L, second.assets.single().updatedAtEpochMs)
        assertNotEquals(1_000L, second.assets.single().updatedAtEpochMs)
    }
}
