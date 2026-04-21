package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.Transform
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DescribeClipToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    private fun lockfileEntry(
        inputHash: String,
        assetId: String,
        pinned: Boolean = false,
    ): LockfileEntry = LockfileEntry(
        inputHash = inputHash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = null,
            seed = 1L,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L,
        ),
        pinned = pinned,
    )

    @Test fun describesVideoClipWithFiltersAndTransforms() = runTest {
        val rig = rig()
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v"),
                            clips = listOf(
                                Clip.Video(
                                    id = ClipId("c-1"),
                                    timeRange = TimeRange(1.seconds, 3.seconds),
                                    sourceRange = TimeRange(0.seconds, 3.seconds),
                                    transforms = listOf(Transform(scaleX = 1.5f)),
                                    assetId = AssetId("a-1"),
                                    filters = listOf(Filter(name = "brightness", params = mapOf("value" to 0.2f))),
                                    sourceBinding = setOf(SourceNodeId("mei")),
                                ),
                            ),
                        ),
                    ),
                    duration = 4.seconds,
                ),
                lockfile = Lockfile.EMPTY.append(lockfileEntry("h-1", "a-1", pinned = true)),
            ),
        )

        val out = DescribeClipTool(rig.store).execute(
            DescribeClipTool.Input(projectId = "p", clipId = "c-1"),
            rig.ctx,
        ).data

        assertEquals("video", out.clipType)
        assertEquals("v", out.trackId)
        assertEquals(1_000L, out.timeRange.startMs)
        assertEquals(3_000L, out.timeRange.durationMs)
        assertEquals(4_000L, out.timeRange.endMs)
        assertEquals(3_000L, out.sourceRange!!.durationMs)
        assertEquals("a-1", out.assetId)
        assertEquals(1, out.filters!!.size)
        assertEquals("brightness", out.filters!!.single().name)
        assertEquals(listOf("mei"), out.sourceBindingIds)
        assertEquals(1, out.transforms.size)
        assertEquals(1.5f, out.transforms.single().scaleX)
        assertNull(out.volume)
        assertNull(out.text)
        assertNotNull(out.lockfile)
        assertTrue(out.lockfile!!.pinned)
        assertFalse(out.lockfile!!.currentlyStale)
    }

    @Test fun describesAudioClipWithVolumeAndFade() = runTest {
        val rig = rig()
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Audio(
                            id = TrackId("a"),
                            clips = listOf(
                                Clip.Audio(
                                    id = ClipId("c-aud"),
                                    timeRange = TimeRange(0.seconds, 5.seconds),
                                    sourceRange = TimeRange(0.seconds, 5.seconds),
                                    assetId = AssetId("a-aud"),
                                    volume = 0.8f,
                                    fadeInSeconds = 0.5f,
                                    fadeOutSeconds = 1.0f,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val out = DescribeClipTool(rig.store).execute(
            DescribeClipTool.Input(projectId = "p", clipId = "c-aud"),
            rig.ctx,
        ).data

        assertEquals("audio", out.clipType)
        assertEquals("a-aud", out.assetId)
        assertEquals(0.8f, out.volume)
        assertEquals(0.5f, out.fadeInSeconds)
        assertEquals(1.0f, out.fadeOutSeconds)
        assertNull(out.filters)
        assertNull(out.text)
        // No lockfile entry for this asset → no lockfile ref.
        assertNull(out.lockfile)
    }

    @Test fun describesTextClipWithStyle() = runTest {
        val rig = rig()
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Subtitle(
                            id = TrackId("sub"),
                            clips = listOf(
                                Clip.Text(
                                    id = ClipId("t-1"),
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    text = "Hello",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val out = DescribeClipTool(rig.store).execute(
            DescribeClipTool.Input(projectId = "p", clipId = "t-1"),
            rig.ctx,
        ).data

        assertEquals("text", out.clipType)
        assertEquals("Hello", out.text)
        assertNotNull(out.textStyle)
        assertNull(out.sourceRange)
        assertNull(out.assetId)
        assertNull(out.lockfile)
    }

    @Test fun missingClipFailsLoud() = runTest {
        val rig = rig()
        rig.store.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalStateException> {
            DescribeClipTool(rig.store).execute(
                DescribeClipTool.Input(projectId = "p", clipId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("project_query(select=timeline_clips)"), ex.message)
    }

    @Test fun missingProjectFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            DescribeClipTool(rig.store).execute(
                DescribeClipTool.Input(projectId = "ghost", clipId = "c-1"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }
}
