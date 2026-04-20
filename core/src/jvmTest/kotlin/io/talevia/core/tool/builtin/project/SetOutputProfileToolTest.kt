package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetOutputProfileToolTest {

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
        val profile = OutputProfile(
            resolution = Resolution(1920, 1080),
            frameRate = FrameRate.FPS_30,
            videoCodec = "h264",
            audioCodec = "aac",
            videoBitrate = 8_000_000,
            audioBitrate = 192_000,
            container = "mp4",
        )
        store.upsert("demo", Project(id = pid, timeline = Timeline(), outputProfile = profile))
        return store to pid
    }

    @Test fun patchesResolutionOnly() = runTest {
        val (store, pid) = fixture()
        val out = SetOutputProfileTool(store).execute(
            SetOutputProfileTool.Input(
                projectId = pid.value,
                resolutionWidth = 3840,
                resolutionHeight = 2160,
            ),
            ctx(),
        ).data
        assertEquals(listOf("resolution"), out.updatedFields)
        assertEquals(3840, out.resolutionWidth)
        assertEquals(2160, out.resolutionHeight)
        assertEquals(30, out.fps)
        assertEquals("h264", out.videoCodec)
        val persisted = store.get(pid)!!.outputProfile
        assertEquals(Resolution(3840, 2160), persisted.resolution)
        assertEquals(FrameRate.FPS_30, persisted.frameRate)
    }

    @Test fun patchesMultipleFields() = runTest {
        val (store, pid) = fixture()
        val out = SetOutputProfileTool(store).execute(
            SetOutputProfileTool.Input(
                projectId = pid.value,
                fps = 60,
                videoCodec = "h265",
                videoBitrate = 16_000_000,
            ),
            ctx(),
        ).data
        assertEquals(setOf("frameRate", "videoCodec", "videoBitrate"), out.updatedFields.toSet())
        assertEquals(60, out.fps)
        assertEquals("h265", out.videoCodec)
        assertEquals(16_000_000L, out.videoBitrate)
        assertEquals("aac", out.audioCodec)
        assertEquals(192_000L, out.audioBitrate)
    }

    @Test fun changeContainer() = runTest {
        val (store, pid) = fixture()
        val out = SetOutputProfileTool(store).execute(
            SetOutputProfileTool.Input(projectId = pid.value, container = "mov"),
            ctx(),
        ).data
        assertEquals(listOf("container"), out.updatedFields)
        assertEquals("mov", out.container)
    }

    @Test fun reportsEmptyUpdatedFieldsWhenValuesMatch() = runTest {
        val (store, pid) = fixture()
        val out = SetOutputProfileTool(store).execute(
            SetOutputProfileTool.Input(
                projectId = pid.value,
                videoCodec = "h264", // same as current
            ),
            ctx(),
        ).data
        assertTrue(out.updatedFields.isEmpty())
    }

    @Test fun rejectsEmptyInput() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalArgumentException> {
            SetOutputProfileTool(store).execute(
                SetOutputProfileTool.Input(projectId = pid.value),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("at least one field"))
    }

    @Test fun rejectsWidthWithoutHeight() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            SetOutputProfileTool(store).execute(
                SetOutputProfileTool.Input(projectId = pid.value, resolutionWidth = 3840),
                ctx(),
            )
        }
    }

    @Test fun rejectsHeightWithoutWidth() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            SetOutputProfileTool(store).execute(
                SetOutputProfileTool.Input(projectId = pid.value, resolutionHeight = 2160),
                ctx(),
            )
        }
    }

    @Test fun rejectsNonPositiveResolution() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            SetOutputProfileTool(store).execute(
                SetOutputProfileTool.Input(projectId = pid.value, resolutionWidth = 0, resolutionHeight = 1080),
                ctx(),
            )
        }
    }

    @Test fun rejectsNonPositiveFps() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            SetOutputProfileTool(store).execute(
                SetOutputProfileTool.Input(projectId = pid.value, fps = 0),
                ctx(),
            )
        }
    }

    @Test fun rejectsBlankCodec() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            SetOutputProfileTool(store).execute(
                SetOutputProfileTool.Input(projectId = pid.value, videoCodec = "   "),
                ctx(),
            )
        }
    }

    @Test fun rejectsNonPositiveBitrate() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            SetOutputProfileTool(store).execute(
                SetOutputProfileTool.Input(projectId = pid.value, audioBitrate = -1),
                ctx(),
            )
        }
    }

    @Test fun rejectsMissingProject() = runTest {
        val (store, _) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            SetOutputProfileTool(store).execute(
                SetOutputProfileTool.Input(projectId = "nope", fps = 60),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun timelineAuthoringResolutionIsUntouched() = runTest {
        val (store, pid) = fixture()
        val beforeTimeline = store.get(pid)!!.timeline
        SetOutputProfileTool(store).execute(
            SetOutputProfileTool.Input(
                projectId = pid.value,
                resolutionWidth = 3840,
                resolutionHeight = 2160,
                fps = 60,
            ),
            ctx(),
        )
        val afterTimeline = store.get(pid)!!.timeline
        assertEquals(beforeTimeline.resolution, afterTimeline.resolution)
        assertEquals(beforeTimeline.frameRate, afterTimeline.frameRate)
    }
}
