package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ListTracksToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = {},
        messages = emptyList(),
    )

    private suspend fun fixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p")

        val videoA = Track.Video(
            id = TrackId("v-bg"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-1"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("a-bg"),
                ),
                Clip.Video(
                    id = ClipId("c-2"),
                    timeRange = TimeRange(6.seconds, 4.seconds),
                    sourceRange = TimeRange(0.seconds, 4.seconds),
                    assetId = AssetId("a-bg"),
                ),
            ),
        )
        val videoB = Track.Video(
            id = TrackId("v-fg"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-3"),
                    timeRange = TimeRange(1.seconds, 3.seconds),
                    sourceRange = TimeRange(0.seconds, 3.seconds),
                    assetId = AssetId("a-fg"),
                ),
            ),
        )
        val audio = Track.Audio(
            id = TrackId("aud"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-a"),
                    timeRange = TimeRange(0.seconds, 10.seconds),
                    sourceRange = TimeRange(0.seconds, 10.seconds),
                    assetId = AssetId("a-audio"),
                ),
            ),
        )
        val subtitles = Track.Subtitle(id = TrackId("sub"), clips = emptyList())
        val effects = Track.Effect(id = TrackId("fx"), clips = emptyList())

        val project = Project(
            id = pid,
            timeline = Timeline(
                tracks = listOf(videoA, videoB, audio, subtitles, effects),
                duration = 10.seconds,
            ),
        )
        store.upsert("demo", project)
        return store to pid
    }

    @Test fun returnsEveryTrackInOrder() = runTest {
        val (store, pid) = fixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(ListTracksTool.Input(projectId = pid.value), ctx()).data
        assertEquals(5, out.totalTrackCount)
        assertEquals(5, out.returnedTrackCount)
        assertEquals(listOf("v-bg", "v-fg", "aud", "sub", "fx"), out.tracks.map { it.trackId })
        assertEquals(listOf(0, 1, 2, 3, 4), out.tracks.map { it.index })
    }

    @Test fun classifiesTrackKinds() = runTest {
        val (store, pid) = fixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(ListTracksTool.Input(projectId = pid.value), ctx()).data
        val byId = out.tracks.associateBy { it.trackId }
        assertEquals("video", byId["v-bg"]!!.trackKind)
        assertEquals("video", byId["v-fg"]!!.trackKind)
        assertEquals("audio", byId["aud"]!!.trackKind)
        assertEquals("subtitle", byId["sub"]!!.trackKind)
        assertEquals("effect", byId["fx"]!!.trackKind)
    }

    @Test fun clipCountsMatchEachTrack() = runTest {
        val (store, pid) = fixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(ListTracksTool.Input(projectId = pid.value), ctx()).data
        val byId = out.tracks.associateBy { it.trackId }
        assertEquals(2, byId["v-bg"]!!.clipCount)
        assertEquals(1, byId["v-fg"]!!.clipCount)
        assertEquals(1, byId["aud"]!!.clipCount)
        assertEquals(0, byId["sub"]!!.clipCount)
        assertEquals(0, byId["fx"]!!.clipCount)
    }

    @Test fun emptyTracksFlagsAndNullSpan() = runTest {
        val (store, pid) = fixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(ListTracksTool.Input(projectId = pid.value), ctx()).data
        val sub = out.tracks.first { it.trackId == "sub" }
        assertTrue(sub.isEmpty)
        assertNull(sub.firstClipStartSeconds)
        assertNull(sub.lastClipEndSeconds)
        assertNull(sub.spanSeconds)
    }

    @Test fun spanCoversGapsBetweenClips() = runTest {
        val (store, pid) = fixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(ListTracksTool.Input(projectId = pid.value), ctx()).data
        val videoA = out.tracks.first { it.trackId == "v-bg" }
        // First clip 0..5, second 6..10 → first=0, last=10, span=10 (the 1s gap is included).
        assertEquals(0.0, videoA.firstClipStartSeconds)
        assertEquals(10.0, videoA.lastClipEndSeconds)
        assertEquals(10.0, videoA.spanSeconds)
    }

    @Test fun filtersByVideoKind() = runTest {
        val (store, pid) = fixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(
            ListTracksTool.Input(projectId = pid.value, trackKind = "video"),
            ctx(),
        ).data
        assertEquals(5, out.totalTrackCount)
        assertEquals(2, out.returnedTrackCount)
        assertTrue(out.tracks.all { it.trackKind == "video" })
        assertEquals(listOf("v-bg", "v-fg"), out.tracks.map { it.trackId })
    }

    @Test fun filtersAreCaseInsensitive() = runTest {
        val (store, pid) = fixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(
            ListTracksTool.Input(projectId = pid.value, trackKind = "  AUDIO "),
            ctx(),
        ).data
        assertEquals(1, out.returnedTrackCount)
        assertEquals("aud", out.tracks.single().trackId)
    }

    @Test fun rejectsUnknownKind() = runTest {
        val (store, pid) = fixture()
        val tool = ListTracksTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(ListTracksTool.Input(projectId = pid.value, trackKind = "bogus"), ctx())
        }
        assertTrue(ex.message!!.contains("trackKind"))
    }

    @Test fun rejectsMissingProject() = runTest {
        val (store, _) = fixture()
        val tool = ListTracksTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(ListTracksTool.Input(projectId = "nope"), ctx())
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun indexPreservesStackingOrder() = runTest {
        val (store, pid) = fixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(
            ListTracksTool.Input(projectId = pid.value, trackKind = "video"),
            ctx(),
        ).data
        // v-bg is track 0 (bottom); v-fg is track 1 (top) → the PiP layering signal.
        assertEquals(0, out.tracks.first { it.trackId == "v-bg" }.index)
        assertEquals(1, out.tracks.first { it.trackId == "v-fg" }.index)
    }

    private suspend fun mixedEmptyAndPopulatedFixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p-mixed")

        val emptyVideo = Track.Video(id = TrackId("v-empty"), clips = emptyList())
        val populatedVideo = Track.Video(
            id = TrackId("v-full"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-v"),
                    timeRange = TimeRange(0.seconds, 3.seconds),
                    sourceRange = TimeRange(0.seconds, 3.seconds),
                    assetId = AssetId("a-v"),
                ),
            ),
        )
        val emptyAudio = Track.Audio(id = TrackId("a-empty"), clips = emptyList())
        val populatedAudio = Track.Audio(
            id = TrackId("a-full"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-a"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("a-audio"),
                ),
            ),
        )

        val project = Project(
            id = pid,
            timeline = Timeline(
                tracks = listOf(emptyVideo, populatedVideo, emptyAudio, populatedAudio),
                duration = 5.seconds,
            ),
        )
        store.upsert("demo-mixed", project)
        return store to pid
    }

    @Test fun onlyNonEmptyTrueSkipsEmptyTracks() = runTest {
        val (store, pid) = mixedEmptyAndPopulatedFixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(
            ListTracksTool.Input(projectId = pid.value, onlyNonEmpty = true),
            ctx(),
        ).data
        assertEquals(4, out.totalTrackCount)
        assertEquals(2, out.returnedTrackCount)
        assertEquals(listOf("v-full", "a-full"), out.tracks.map { it.trackId })
        assertTrue(out.tracks.none { it.isEmpty })
    }

    @Test fun onlyNonEmptyFalseIsSameAsDefault() = runTest {
        val (store, pid) = mixedEmptyAndPopulatedFixture()
        val tool = ListTracksTool(store)
        val explicitFalse = tool.execute(
            ListTracksTool.Input(projectId = pid.value, onlyNonEmpty = false),
            ctx(),
        ).data
        val default = tool.execute(
            ListTracksTool.Input(projectId = pid.value),
            ctx(),
        ).data
        assertEquals(4, explicitFalse.returnedTrackCount)
        assertEquals(default.tracks.map { it.trackId }, explicitFalse.tracks.map { it.trackId })
    }

    @Test fun onlyNonEmptyComposesWithKindFilter() = runTest {
        val (store, pid) = mixedEmptyAndPopulatedFixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(
            ListTracksTool.Input(projectId = pid.value, trackKind = "video", onlyNonEmpty = true),
            ctx(),
        ).data
        // 4 total tracks in the project (pre-filter count is preserved).
        assertEquals(4, out.totalTrackCount)
        assertEquals(1, out.returnedTrackCount)
        assertEquals("v-full", out.tracks.single().trackId)
        assertEquals("video", out.tracks.single().trackKind)
    }

    private suspend fun fivePopulatedTracksFixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p-five")

        val tracks = (1..5).map { i ->
            Track.Video(
                id = TrackId("v-$i"),
                clips = listOf(
                    Clip.Video(
                        id = ClipId("c-$i"),
                        timeRange = TimeRange(0.seconds, 2.seconds),
                        sourceRange = TimeRange(0.seconds, 2.seconds),
                        assetId = AssetId("a-$i"),
                    ),
                ),
            )
        }
        val project = Project(
            id = pid,
            timeline = Timeline(tracks = tracks, duration = 2.seconds),
        )
        store.upsert("demo-five", project)
        return store to pid
    }

    @Test fun limitCapsResponse() = runTest {
        val (store, pid) = fivePopulatedTracksFixture()
        val tool = ListTracksTool(store)
        val out = tool.execute(
            ListTracksTool.Input(projectId = pid.value, limit = 2),
            ctx(),
        ).data
        assertEquals(5, out.totalTrackCount)
        assertEquals(2, out.returnedTrackCount)
        assertEquals(listOf("v-1", "v-2"), out.tracks.map { it.trackId })
    }

    @Test fun limitClampedToMax() = runTest {
        val (store, pid) = fivePopulatedTracksFixture()
        val tool = ListTracksTool(store)
        // 999_999 is well above MAX_LIMIT (500); should silently clamp and still return all 5.
        val out = tool.execute(
            ListTracksTool.Input(projectId = pid.value, limit = 999_999),
            ctx(),
        ).data
        assertEquals(5, out.totalTrackCount)
        assertEquals(5, out.returnedTrackCount)
    }

    @Test fun limitWithZeroIsClampedToMin() = runTest {
        val (store, pid) = fivePopulatedTracksFixture()
        val tool = ListTracksTool(store)
        // limit=0 is below MIN_LIMIT (1); should silently clamp up to 1 and return exactly one track.
        val out = tool.execute(
            ListTracksTool.Input(projectId = pid.value, limit = 0),
            ctx(),
        ).data
        assertEquals(5, out.totalTrackCount)
        assertEquals(1, out.returnedTrackCount)
        assertEquals("v-1", out.tracks.single().trackId)
    }
}
