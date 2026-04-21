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
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ListAssetsToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = {},
        messages = emptyList(),
    )

    private fun asset(
        id: String,
        videoCodec: String? = null,
        audioCodec: String? = null,
        resolution: Resolution? = null,
        durationSec: Long = 10,
        source: MediaSource = MediaSource.File("/tmp/$id.mp4"),
    ): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = source,
        metadata = MediaMetadata(
            duration = durationSec.seconds,
            resolution = resolution,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
        ),
    )

    private suspend fun fixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p")

        val videoTrack = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-1"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("v-used"),
                ),
                Clip.Video(
                    id = ClipId("c-2"),
                    timeRange = TimeRange(5.seconds, 3.seconds),
                    sourceRange = TimeRange(0.seconds, 3.seconds),
                    assetId = AssetId("v-used"), // duplicate reference
                ),
            ),
        )
        val audioTrack = Track.Audio(
            id = TrackId("a"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-3"),
                    timeRange = TimeRange(0.seconds, 4.seconds),
                    sourceRange = TimeRange(0.seconds, 4.seconds),
                    assetId = AssetId("a-used"),
                ),
            ),
        )

        val project = Project(
            id = pid,
            timeline = Timeline(tracks = listOf(videoTrack, audioTrack), duration = 8.seconds),
            assets = listOf(
                asset("v-used", videoCodec = "h264", audioCodec = "aac", resolution = Resolution(1920, 1080)),
                asset("v-unused", videoCodec = "h264", resolution = Resolution(1280, 720)),
                asset("a-used", audioCodec = "aac", durationSec = 30),
                asset("a-unused", audioCodec = "mp3", durationSec = 120),
                asset(
                    "img-1",
                    durationSec = 0,
                    source = MediaSource.Http("https://example.com/still.jpg"),
                ),
            ),
        )
        store.upsert("demo", project)
        return store to pid
    }

    @Test fun allAssetsReturnedByDefault() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value), ctx()).data
        assertEquals(5, out.total)
        assertEquals(5, out.returned)
        assertEquals(setOf("v-used", "v-unused", "a-used", "a-unused", "img-1"), out.assets.map { it.assetId }.toSet())
    }

    @Test fun classifiesVideoAudioImage() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value), ctx()).data
        val byId = out.assets.associateBy { it.assetId }
        assertEquals("video", byId["v-used"]!!.kind)
        assertEquals("video", byId["v-unused"]!!.kind)
        assertEquals("audio", byId["a-used"]!!.kind)
        assertEquals("audio", byId["a-unused"]!!.kind)
        assertEquals("image", byId["img-1"]!!.kind)
    }

    @Test fun filtersByKindVideo() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value, kind = "video"), ctx()).data
        assertEquals(2, out.total)
        assertTrue(out.assets.all { it.kind == "video" })
    }

    @Test fun filtersByKindAudio() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value, kind = "audio"), ctx()).data
        assertEquals(2, out.total)
        assertTrue(out.assets.all { it.kind == "audio" })
    }

    @Test fun filtersByKindImage() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value, kind = "image"), ctx()).data
        assertEquals(1, out.total)
        assertEquals("img-1", out.assets.single().assetId)
    }

    @Test fun onlyUnusedFiltersByRefCount() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(
            ListAssetsTool.Input(projectId = pid.value, onlyUnused = true),
            ctx(),
        ).data
        assertEquals(setOf("v-unused", "a-unused", "img-1"), out.assets.map { it.assetId }.toSet())
    }

    @Test fun inUseByClipsCountsDuplicateReferences() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value), ctx()).data
        val byId = out.assets.associateBy { it.assetId }
        assertEquals(2, byId["v-used"]!!.inUseByClips) // two video clips reference it
        assertEquals(1, byId["a-used"]!!.inUseByClips)
        assertEquals(0, byId["v-unused"]!!.inUseByClips)
    }

    @Test fun sourceKindDiscriminated() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value), ctx()).data
        val byId = out.assets.associateBy { it.assetId }
        assertEquals("file", byId["v-used"]!!.sourceKind)
        assertEquals("http", byId["img-1"]!!.sourceKind)
    }

    @Test fun paginationWithLimitAndOffset() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val first = tool.execute(
            ListAssetsTool.Input(projectId = pid.value, limit = 2, offset = 0),
            ctx(),
        ).data
        assertEquals(5, first.total)
        assertEquals(2, first.returned)
        val second = tool.execute(
            ListAssetsTool.Input(projectId = pid.value, limit = 2, offset = 2),
            ctx(),
        ).data
        assertEquals(2, second.returned)
        val overlap = first.assets.map { it.assetId }.intersect(second.assets.map { it.assetId }.toSet())
        assertTrue(overlap.isEmpty(), "pages should not overlap")
    }

    @Test fun rejectsInvalidKind() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(ListAssetsTool.Input(projectId = pid.value, kind = "bogus"), ctx())
        }
        assertTrue(ex.message!!.contains("kind"))
    }

    @Test fun rejectsBadLimit() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(ListAssetsTool.Input(projectId = pid.value, limit = 0), ctx())
        }
        assertFailsWith<IllegalArgumentException> {
            tool.execute(ListAssetsTool.Input(projectId = pid.value, limit = 501), ctx())
        }
    }

    @Test fun rejectsMissingProject() = runTest {
        val (store, _) = fixture()
        val tool = ListAssetsTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(ListAssetsTool.Input(projectId = "nope"), ctx())
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun exposesResolution() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value), ctx()).data
        val byId = out.assets.associateBy { it.assetId }
        assertEquals(1920, byId["v-used"]!!.width)
        assertEquals(1080, byId["v-used"]!!.height)
        assertEquals(null, byId["img-1"]!!.width)
    }

    @Test fun sortByNullPreservesStoreInsertionOrder() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value), ctx()).data
        assertEquals(
            listOf("v-used", "v-unused", "a-used", "a-unused", "img-1"),
            out.assets.map { it.assetId },
        )
    }

    @Test fun sortByDurationOrdersLongestFirst() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(
            ListAssetsTool.Input(projectId = pid.value, sortBy = "duration"),
            ctx(),
        ).data
        // a-unused=120, a-used=30, v-used=10, v-unused=10 (stable), img-1=0
        assertEquals(
            listOf("a-unused", "a-used", "v-used", "v-unused", "img-1"),
            out.assets.map { it.assetId },
        )
    }

    @Test fun sortByDurationAscOrdersShortestFirst() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(
            ListAssetsTool.Input(projectId = pid.value, sortBy = "duration-asc"),
            ctx(),
        ).data
        // img-1=0, v-used=10, v-unused=10 (stable), a-used=30, a-unused=120
        assertEquals(
            listOf("img-1", "v-used", "v-unused", "a-used", "a-unused"),
            out.assets.map { it.assetId },
        )
    }

    @Test fun sortByIdOrdersAscending() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(
            ListAssetsTool.Input(projectId = pid.value, sortBy = "id"),
            ctx(),
        ).data
        assertEquals(
            listOf("a-unused", "a-used", "img-1", "v-unused", "v-used"),
            out.assets.map { it.assetId },
        )
    }

    @Test fun sortByAppliedBeforeOffsetAndLimit() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        // Top-2 by duration DESC should be the two audio assets, not the first
        // two store-inserted entries.
        val out = tool.execute(
            ListAssetsTool.Input(projectId = pid.value, sortBy = "duration", limit = 2, offset = 0),
            ctx(),
        ).data
        assertEquals(5, out.total)
        assertEquals(listOf("a-unused", "a-used"), out.assets.map { it.assetId })
    }

    @Test fun rejectsInvalidSortBy() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ListAssetsTool.Input(projectId = pid.value, sortBy = "newest"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("sortBy"))
        assertTrue(ex.message!!.contains("duration"))
        assertTrue(ex.message!!.contains("duration-asc"))
        assertTrue(ex.message!!.contains("id"))
    }

    @Test fun hasTrackFlagsAreAccurate() = runTest {
        val (store, pid) = fixture()
        val tool = ListAssetsTool(store)
        val out = tool.execute(ListAssetsTool.Input(projectId = pid.value), ctx()).data
        val byId = out.assets.associateBy { it.assetId }
        assertTrue(byId["v-used"]!!.hasVideoTrack)
        assertTrue(byId["v-used"]!!.hasAudioTrack) // muxed
        assertTrue(byId["a-used"]!!.hasAudioTrack)
        assertEquals(false, byId["a-used"]!!.hasVideoTrack)
        assertEquals(false, byId["img-1"]!!.hasVideoTrack)
        assertEquals(false, byId["img-1"]!!.hasAudioTrack)
    }
}
