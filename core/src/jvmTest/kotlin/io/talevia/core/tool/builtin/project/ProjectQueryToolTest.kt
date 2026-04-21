package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
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
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers: the three select branches, each with at least one filter + sort
 * pair, plus the cross-cutting semantic edges — misapplied filter fails
 * loud, unknown select / sortBy fail loud, limit clamp + offset, JsonArray
 * row payload round-trips via the typed row serializers.
 */
class ProjectQueryToolTest {

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
    ): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
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
                    sourceBinding = setOf(SourceNodeId("mei")),
                ),
                Clip.Video(
                    id = ClipId("c-2"),
                    timeRange = TimeRange(5.seconds, 3.seconds),
                    sourceRange = TimeRange(0.seconds, 3.seconds),
                    assetId = AssetId("v-used"),
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
                    volume = 0.7f,
                ),
            ),
        )
        val subTrack = Track.Subtitle(
            id = TrackId("sub"),
            clips = listOf(
                Clip.Text(
                    id = ClipId("c-4"),
                    timeRange = TimeRange(1.seconds, 2.seconds),
                    text = "Hello world",
                ),
            ),
        )
        val emptyEffect = Track.Effect(id = TrackId("eff"), clips = emptyList())
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(videoTrack, audioTrack, subTrack, emptyEffect),
                    duration = 8.seconds,
                ),
                assets = listOf(
                    asset("v-used", videoCodec = "h264", audioCodec = "aac", resolution = Resolution(1920, 1080), durationSec = 30),
                    asset("v-unused", videoCodec = "h264", resolution = Resolution(1280, 720), durationSec = 20),
                    asset("a-used", audioCodec = "aac", durationSec = 5),
                    asset("img", durationSec = 0),
                ),
            ),
        )
        return store to pid
    }

    // ── select = tracks ───────────────────────────────────────────────

    @Test fun tracksSelectReturnsStackingOrder() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks"),
            ctx(),
        ).data
        assertEquals("tracks", out.select)
        assertEquals(4, out.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.TrackRow.serializer()),
            out.rows,
        )
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.index })
        assertEquals(listOf("video", "audio", "subtitle", "effect"), rows.map { it.trackKind })
        assertTrue(rows.last().isEmpty)
        assertNull(rows.last().spanSeconds)
    }

    @Test fun tracksOnlyNonEmptyHidesScaffold() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks", onlyNonEmpty = true),
            ctx(),
        ).data
        assertEquals(3, out.total)
    }

    @Test fun tracksFilterByKind() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks", trackKind = "AUDIO"),
            ctx(),
        ).data
        assertEquals(1, out.total)
    }

    @Test fun tracksSortByClipCount() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "tracks",
                sortBy = "clipCount",
            ),
            ctx(),
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.TrackRow.serializer()),
            out.rows,
        )
        assertEquals(2, rows.first().clipCount)
    }

    // ── select = timeline_clips ───────────────────────────────────────

    @Test fun clipsSelectEmitsAllClips() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "timeline_clips"),
            ctx(),
        ).data
        assertEquals(4, out.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.ClipRow.serializer()),
            out.rows,
        )
        assertEquals(setOf("video", "audio", "text"), rows.map { it.clipKind }.toSet())
    }

    @Test fun clipsFilterByTimeWindowIntersects() = runTest {
        val (store, pid) = fixture()
        // Window [1..2]s: c-1 (0..5) ∩ [1..2]=yes, c-2 (5..8) no, c-3 (0..4) yes, c-4 (1..3) yes.
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                fromSeconds = 1.0,
                toSeconds = 2.0,
            ),
            ctx(),
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.ClipRow.serializer()),
            out.rows,
        )
        assertEquals(setOf("c-1", "c-3", "c-4"), rows.map { it.clipId }.toSet())
    }

    @Test fun clipsOnlySourceBoundKeepsAigcOnly() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                onlySourceBound = true,
            ),
            ctx(),
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.ClipRow.serializer()),
            out.rows,
        )
        assertEquals(listOf("c-1"), rows.map { it.clipId })
    }

    @Test fun clipsSortByDurationDescending() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                sortBy = "durationSeconds",
            ),
            ctx(),
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.ClipRow.serializer()),
            out.rows,
        )
        assertEquals("c-1", rows.first().clipId) // 5s is longest
    }

    @Test fun clipsLimitOffsetHidesRows() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                limit = 1,
                offset = 2,
            ),
            ctx(),
        ).data
        assertEquals(4, out.total)
        assertEquals(1, out.returned)
    }

    // ── select = assets ───────────────────────────────────────────────

    @Test fun assetsClassifyByCodec() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets"),
            ctx(),
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.AssetRow.serializer()),
            out.rows,
        )
        val kindsById = rows.associate { it.assetId to it.kind }
        assertEquals("video", kindsById["v-used"])
        assertEquals("video", kindsById["v-unused"])
        assertEquals("audio", kindsById["a-used"])
        assertEquals("image", kindsById["img"])
    }

    @Test fun assetsFilterByKind() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets", kind = "audio"),
            ctx(),
        ).data
        assertEquals(1, out.total)
    }

    @Test fun assetsOnlyUnusedExcludesReferenced() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets", onlyUnused = true),
            ctx(),
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.AssetRow.serializer()),
            out.rows,
        )
        assertEquals(setOf("v-unused", "img"), rows.map { it.assetId }.toSet())
    }

    @Test fun assetsSortByIdIsAlphabetic() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets", sortBy = "id"),
            ctx(),
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.AssetRow.serializer()),
            out.rows,
        )
        assertEquals(listOf("a-used", "img", "v-unused", "v-used"), rows.map { it.assetId })
    }

    @Test fun assetsRefCountIncludesDuplicates() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "assets"),
            ctx(),
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ProjectQueryTool.AssetRow.serializer()),
            out.rows,
        )
        assertEquals(2, rows.single { it.assetId == "v-used" }.inUseByClips)
    }

    // ── validation / error paths ──────────────────────────────────────

    @Test fun unknownSelectThrows() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(projectId = pid.value, select = "wat"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("select must be one of"), ex.message)
    }

    @Test fun misappliedFilterThrowsLoudly() = runTest {
        val (store, pid) = fixture()
        // `kind` only applies to select=assets, not timeline_clips.
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "timeline_clips",
                    kind = "audio",
                ),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("kind"), ex.message)
    }

    @Test fun misappliedTrackIdOnTracksSelectFails() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(projectId = pid.value, select = "tracks", trackId = "v"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("trackId"), ex.message)
    }

    @Test fun invalidTrackKindRejected() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "tracks",
                    trackKind = "bogus",
                ),
                ctx(),
            )
        }
    }

    @Test fun invalidSortForSelectRejected() = runTest {
        val (store, pid) = fixture()
        // "duration" belongs to select=assets, not timeline_clips.
        assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "timeline_clips",
                    sortBy = "duration",
                ),
                ctx(),
            )
        }
    }

    @Test fun missingProjectThrows() = runTest {
        val (store, _) = fixture()
        assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(projectId = "nope", select = "tracks"),
                ctx(),
            )
        }
    }

    @Test fun limitClampsToMaxSilently() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "timeline_clips", limit = 99_999),
            ctx(),
        ).data
        assertEquals(4, out.returned)
    }

    @Test fun limitZeroClampedToOne() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks", limit = 0),
            ctx(),
        ).data
        assertEquals(1, out.returned)
    }

    @Test fun offsetPastEndReturnsNoRows() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = "timeline_clips",
                offset = 100,
            ),
            ctx(),
        ).data
        assertEquals(4, out.total)
        assertEquals(0, out.returned)
    }

    @Test fun echoedSelectNormalisedLowercase() = runTest {
        val (store, pid) = fixture()
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "TRACKS"),
            ctx(),
        ).data
        assertEquals("tracks", out.select)
        assertFalse(out.rows.isEmpty())
    }

    @Test fun emptyTracksRowOmitsNullSpanField() = runTest {
        val (store, pid) = fixture()
        // Empty tracks (`eff`) have null span fields; encodeDefaults=false means
        // the keys are absent rather than null in the JsonArray payload.
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "tracks"),
            ctx(),
        ).data
        val emptyRow = out.rows[3].let { (it as kotlinx.serialization.json.JsonObject) }
        assertTrue("spanSeconds" !in emptyRow)
        assertTrue("lastClipEndSeconds" !in emptyRow)
    }
}
