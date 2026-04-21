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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FindPinnedClipsToolTest {

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

    private fun entry(
        inputHash: String,
        assetId: String,
        toolId: String = "generate_image",
        pinned: Boolean = false,
    ): LockfileEntry = LockfileEntry(
        inputHash = inputHash,
        toolId = toolId,
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

    @Test fun emptyProjectReturnsZero() = runTest {
        val rig = rig()
        rig.store.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
        val out = FindPinnedClipsTool(rig.store).execute(
            FindPinnedClipsTool.Input(projectId = "p"),
            rig.ctx,
        ).data
        assertEquals(0, out.pinnedClipCount)
        assertEquals(0, out.totalMediaClipCount)
    }

    @Test fun returnsOnlyPinnedMediaClips() = runTest {
        val rig = rig()
        val lockfile = Lockfile.EMPTY
            .append(entry("h-hero", "a-hero", pinned = true))
            .append(entry("h-other", "a-other", pinned = false))
            .append(entry("h-aud", "a-aud", toolId = "synthesize_speech", pinned = true))
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
                                    id = ClipId("c-hero"),
                                    timeRange = TimeRange(0.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = AssetId("a-hero"),
                                ),
                                Clip.Video(
                                    id = ClipId("c-other"),
                                    timeRange = TimeRange(2.seconds, 2.seconds),
                                    sourceRange = TimeRange(0.seconds, 2.seconds),
                                    assetId = AssetId("a-other"),
                                ),
                            ),
                        ),
                        Track.Audio(
                            id = TrackId("aud"),
                            clips = listOf(
                                Clip.Audio(
                                    id = ClipId("c-aud"),
                                    timeRange = TimeRange(0.seconds, 3.seconds),
                                    sourceRange = TimeRange(0.seconds, 3.seconds),
                                    assetId = AssetId("a-aud"),
                                ),
                            ),
                        ),
                    ),
                    duration = 4.seconds,
                ),
                lockfile = lockfile,
            ),
        )

        val out = FindPinnedClipsTool(rig.store).execute(
            FindPinnedClipsTool.Input(projectId = "p"),
            rig.ctx,
        ).data

        assertEquals(3, out.totalMediaClipCount)
        assertEquals(2, out.pinnedClipCount)
        val clipIds = out.reports.map { it.clipId }.toSet()
        assertEquals(setOf("c-hero", "c-aud"), clipIds)

        // Provenance fields round-trip.
        val hero = out.reports.first { it.clipId == "c-hero" }
        assertEquals("h-hero", hero.inputHash)
        assertEquals("generate_image", hero.toolId)
        val aud = out.reports.first { it.clipId == "c-aud" }
        assertEquals("synthesize_speech", aud.toolId)
    }

    @Test fun excludesTextClips() = runTest {
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
                                    timeRange = TimeRange(0.seconds, 1.seconds),
                                    text = "hello",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val out = FindPinnedClipsTool(rig.store).execute(
            FindPinnedClipsTool.Input(projectId = "p"),
            rig.ctx,
        ).data
        assertEquals(0, out.totalMediaClipCount)
        assertEquals(0, out.pinnedClipCount)
    }

    @Test fun importedMediaIsNotCountedPinned() = runTest {
        val rig = rig()
        // Video clip whose asset has NO lockfile entry — simulating imported media.
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
                                    id = ClipId("c-imp"),
                                    timeRange = TimeRange(0.seconds, 1.seconds),
                                    sourceRange = TimeRange(0.seconds, 1.seconds),
                                    assetId = AssetId("a-imp"),
                                ),
                            ),
                        ),
                    ),
                    duration = 1.seconds,
                ),
                lockfile = Lockfile.EMPTY,
            ),
        )
        val out = FindPinnedClipsTool(rig.store).execute(
            FindPinnedClipsTool.Input(projectId = "p"),
            rig.ctx,
        ).data
        assertEquals(1, out.totalMediaClipCount)
        assertEquals(0, out.pinnedClipCount)
    }

    @Test fun missingProjectFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            FindPinnedClipsTool(rig.store).execute(
                FindPinnedClipsTool.Input(projectId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun trackIdIsIncluded() = runTest {
        val rig = rig()
        val lockfile = Lockfile.EMPTY.append(entry("h-1", "a-1", pinned = true))
        rig.store.upsert(
            "demo",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("v-main"),
                            clips = listOf(
                                Clip.Video(
                                    id = ClipId("c-1"),
                                    timeRange = TimeRange(0.seconds, 1.seconds),
                                    sourceRange = TimeRange(0.seconds, 1.seconds),
                                    assetId = AssetId("a-1"),
                                ),
                            ),
                        ),
                    ),
                    duration = 1.seconds,
                ),
                lockfile = lockfile,
            ),
        )
        val out = FindPinnedClipsTool(rig.store).execute(
            FindPinnedClipsTool.Input(projectId = "p"),
            rig.ctx,
        ).data
        assertEquals("v-main", out.reports.single().trackId)
    }
}
