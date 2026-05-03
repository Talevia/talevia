package io.talevia.core.tool.builtin.project

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    private suspend fun fixture(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
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

    private fun input(
        projectId: String,
        resolutionWidth: Int? = null,
        resolutionHeight: Int? = null,
        fps: Int? = null,
        videoCodec: String? = null,
        audioCodec: String? = null,
        videoBitrate: Long? = null,
        audioBitrate: Long? = null,
        container: String? = null,
    ) = ProjectLifecycleActionTool.Input(
        action = "set_output_profile",
        projectId = projectId,
        resolutionWidth = resolutionWidth,
        resolutionHeight = resolutionHeight,
        fps = fps,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        videoBitrate = videoBitrate,
        audioBitrate = audioBitrate,
        container = container,
    )

    private suspend fun execSetOutput(
        store: FileProjectStore,
        i: ProjectLifecycleActionTool.Input,
    ): ProjectLifecycleActionTool.SetOutputProfileResult {
        val out = ProjectLifecycleActionTool(store).execute(i, ctx()).data
        return assertNotNull(out.setOutputProfileResult)
    }

    @Test fun patchesResolutionOnly() = runTest {
        val (store, pid) = fixture()
        val out = execSetOutput(
            store,
            input(pid.value, resolutionWidth = 3840, resolutionHeight = 2160),
        )
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
        val out = execSetOutput(
            store,
            input(pid.value, fps = 60, videoCodec = "h265", videoBitrate = 16_000_000),
        )
        assertEquals(setOf("frameRate", "videoCodec", "videoBitrate"), out.updatedFields.toSet())
        assertEquals(60, out.fps)
        assertEquals("h265", out.videoCodec)
        assertEquals(16_000_000L, out.videoBitrate)
        assertEquals("aac", out.audioCodec)
        assertEquals(192_000L, out.audioBitrate)
    }

    @Test fun changeContainer() = runTest {
        val (store, pid) = fixture()
        val out = execSetOutput(store, input(pid.value, container = "mov"))
        assertEquals(listOf("container"), out.updatedFields)
        assertEquals("mov", out.container)
    }

    @Test fun reportsEmptyUpdatedFieldsWhenValuesMatch() = runTest {
        val (store, pid) = fixture()
        val out = execSetOutput(store, input(pid.value, videoCodec = "h264"))
        assertTrue(out.updatedFields.isEmpty())
    }

    @Test fun rejectsEmptyInput() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalArgumentException> {
            ProjectLifecycleActionTool(store).execute(input(pid.value), ctx())
        }
        assertTrue(ex.message!!.contains("at least one field"))
    }

    @Test fun rejectsWidthWithoutHeight() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            ProjectLifecycleActionTool(store).execute(input(pid.value, resolutionWidth = 3840), ctx())
        }
    }

    @Test fun rejectsHeightWithoutWidth() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            ProjectLifecycleActionTool(store).execute(input(pid.value, resolutionHeight = 2160), ctx())
        }
    }

    @Test fun rejectsNonPositiveResolution() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            ProjectLifecycleActionTool(store).execute(
                input(pid.value, resolutionWidth = 0, resolutionHeight = 1080),
                ctx(),
            )
        }
    }

    @Test fun rejectsNonPositiveFps() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            ProjectLifecycleActionTool(store).execute(input(pid.value, fps = 0), ctx())
        }
    }

    @Test fun rejectsBlankCodec() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            ProjectLifecycleActionTool(store).execute(input(pid.value, videoCodec = "   "), ctx())
        }
    }

    @Test fun rejectsNonPositiveBitrate() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            ProjectLifecycleActionTool(store).execute(input(pid.value, audioBitrate = -1), ctx())
        }
    }

    @Test fun rejectsMissingProject() = runTest {
        val (store, _) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectLifecycleActionTool(store).execute(input("nope", fps = 60), ctx())
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun timelineAuthoringResolutionIsUntouched() = runTest {
        val (store, pid) = fixture()
        val beforeTimeline = store.get(pid)!!.timeline
        ProjectLifecycleActionTool(store).execute(
            input(pid.value, resolutionWidth = 3840, resolutionHeight = 2160, fps = 60),
            ctx(),
        )
        val afterTimeline = store.get(pid)!!.timeline
        assertEquals(beforeTimeline.resolution, afterTimeline.resolution)
        assertEquals(beforeTimeline.frameRate, afterTimeline.frameRate)
    }

    @Test fun rejectsMissingProjectId() = runTest {
        val (store, _) = fixture()
        assertFailsWith<IllegalStateException> {
            ProjectLifecycleActionTool(store).execute(
                ProjectLifecycleActionTool.Input(action = "set_output_profile", fps = 30),
                ctx(),
            )
        }
    }
}
