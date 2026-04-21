package io.talevia.core.tool.builtin.video

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
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ApplyFilterToolTest {

    private fun ctx(parts: MutableList<Part>): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { parts += it },
        messages = emptyList(),
    )

    private suspend fun fixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p")
        val video = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-1"),
                    timeRange = TimeRange(0.seconds, 1.seconds),
                    sourceRange = TimeRange(0.seconds, 1.seconds),
                    assetId = AssetId("a-1"),
                ),
                Clip.Video(
                    id = ClipId("c-2"),
                    timeRange = TimeRange(1.seconds, 1.seconds),
                    sourceRange = TimeRange(0.seconds, 1.seconds),
                    assetId = AssetId("a-2"),
                ),
            ),
        )
        val audio = Track.Audio(
            id = TrackId("a"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-audio"),
                    timeRange = TimeRange(0.seconds, 2.seconds),
                    sourceRange = TimeRange(0.seconds, 2.seconds),
                    assetId = AssetId("a-audio"),
                ),
            ),
        )
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(tracks = listOf(video, audio), duration = 2.seconds)),
        )
        return store to pid
    }

    @Test fun allVideoClipsAppliesUniformly() = runTest {
        val (store, pid) = fixture()
        val tool = ApplyFilterTool(store)
        val parts = mutableListOf<Part>()
        val out = tool.execute(
            ApplyFilterTool.Input(
                projectId = pid.value,
                filterName = "vignette",
                params = mapOf("intensity" to 0.5f),
                allVideoClips = true,
            ),
            ctx(parts),
        ).data

        assertEquals(2, out.appliedCount)
        assertEquals(listOf("c-1", "c-2"), out.appliedClipIds)

        val project = store.get(pid)!!
        val clips = project.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>()
        clips.forEach { assertEquals(listOf("vignette"), it.filters.map { f -> f.name }) }
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }

    @Test fun clipIdsHonorsExplicitList() = runTest {
        val (store, pid) = fixture()
        val tool = ApplyFilterTool(store)
        val out = tool.execute(
            ApplyFilterTool.Input(
                projectId = pid.value,
                filterName = "blur",
                params = mapOf("radius" to 4f),
                clipIds = listOf("c-2"),
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(1, out.appliedCount)
        assertEquals(listOf("c-2"), out.appliedClipIds)

        val clips = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>()
        assertTrue(clips.single { it.id.value == "c-1" }.filters.isEmpty())
        assertEquals("blur", clips.single { it.id.value == "c-2" }.filters.single().name)
    }

    @Test fun audioClipIdListedAsSkipped() = runTest {
        val (store, pid) = fixture()
        val tool = ApplyFilterTool(store)
        val out = tool.execute(
            ApplyFilterTool.Input(
                projectId = pid.value,
                filterName = "brightness",
                clipIds = listOf("c-1", "c-audio", "nope"),
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(1, out.appliedCount)
        val reasons = out.skipped.associate { it.clipId to it.reason }
        assertTrue(reasons["c-audio"]!!.contains("not a video"))
        assertTrue(reasons["nope"]!!.contains("not found"))
    }

    @Test fun multipleSelectorsFailLoud() = runTest {
        val (store, pid) = fixture()
        val tool = ApplyFilterTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ApplyFilterTool.Input(
                    projectId = pid.value,
                    filterName = "blur",
                    allVideoClips = true,
                    clipIds = listOf("c-1"),
                ),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("exactly one"))
    }

    @Test fun trackIdScopesToTrack() = runTest {
        val (store, pid) = fixture()
        val tool = ApplyFilterTool(store)
        val out = tool.execute(
            ApplyFilterTool.Input(
                projectId = pid.value,
                filterName = "saturation",
                params = mapOf("amount" to 0.3f),
                trackId = "v",
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(2, out.appliedCount, "both video clips on track v should be targeted")
    }

    @Test fun singleClipViaOneElementList() = runTest {
        // Primary merge validation: single-clip is just `clipIds=[id]` — no second
        // tool required. Closes the old apply_filter + apply_filter_to_clips split.
        val (store, pid) = fixture()
        val tool = ApplyFilterTool(store)
        val out = tool.execute(
            ApplyFilterTool.Input(
                projectId = pid.value,
                filterName = "blur",
                params = mapOf("radius" to 4f),
                clipIds = listOf("c-1"),
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(1, out.appliedCount)
        assertEquals(listOf("c-1"), out.appliedClipIds)
        assertTrue(out.skipped.isEmpty())
        val refreshed = store.get(pid)!!
        val c1 = refreshed.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c-1" } as Clip.Video
        assertEquals(1, c1.filters.size)
        assertEquals("blur", c1.filters.single().name)
        // c-2 untouched.
        val c2 = refreshed.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c-2" } as Clip.Video
        assertTrue(c2.filters.isEmpty())
    }
}
