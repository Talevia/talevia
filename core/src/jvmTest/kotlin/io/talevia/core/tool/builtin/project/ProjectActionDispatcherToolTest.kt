package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Smoke coverage for [ProjectActionDispatcherTool] — phase 1a-2 of the
 * project_action consolidation. Validates that the kind discriminator
 * routes each variant to the correct underlying tool's `execute`, that
 * the Output's per-kind result field is populated (and others null),
 * and that the permission router delegates to the underlying tool's
 * `permissionFrom`. Each underlying tool has its own dedicated test
 * file for full coverage; the dispatcher's responsibility is just
 * routing.
 */
class ProjectActionDispatcherToolTest {

    /** No-op VideoEngine — dispatcher routing tests don't actually render. */
    private object NoopVideoEngine : VideoEngine {
        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO)

        override fun render(timeline: io.talevia.core.domain.Timeline, output: OutputSpec, resolver: MediaPathResolver?): Flow<RenderProgress> =
            emptyFlow()

        override suspend fun thumbnail(
            asset: AssetId,
            source: MediaSource,
            time: Duration,
        ): ByteArray = ByteArray(0)

        override suspend fun deleteMezzanine(path: String): Boolean = true
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun entry(hash: String, assetId: String, pinned: Boolean = false): LockfileEntry =
        LockfileEntry(
            inputHash = hash,
            toolId = "generate_image",
            assetId = AssetId(assetId),
            provenance = GenerationProvenance(
                providerId = "openai",
                modelId = "gpt-image-1",
                modelVersion = null,
                seed = 0,
                parameters = JsonObject(emptyMap()),
                createdAtEpochMs = 0,
            ),
            pinned = pinned,
        )

    private fun videoClip(id: String, assetId: String): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 5.seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        assetId = AssetId(assetId),
    )

    private suspend fun dispatcherWithProject(): Pair<ProjectActionDispatcherTool, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("t"),
                            clips = listOf(videoClip("c-hero", "a-hero")),
                        ),
                    ),
                ),
                lockfile = EagerLockfile(entries = listOf(entry("h-hero", "a-hero"))),
            ),
        )
        val lifecycle = ProjectLifecycleActionTool(store)
        val maintenance = ProjectMaintenanceActionTool(store, NoopVideoEngine)
        val pin = ProjectPinActionTool(store)
        val snapshot = ProjectSnapshotActionTool(store)
        return ProjectActionDispatcherTool(lifecycle, maintenance, pin, snapshot) to pid
    }

    @Test fun pinKindRoutesToProjectPinActionAndPopulatesPinResult() = runTest {
        val (dispatcher, pid) = dispatcherWithProject()
        val result = dispatcher.execute(
            ProjectActionDispatcherTool.Input.Pin(
                ProjectPinActionTool.Input(
                    projectId = pid.value,
                    target = "lockfile_entry",
                    inputHash = "h-hero",
                    pinned = true,
                ),
            ),
            ctx(),
        ).data
        assertEquals("pin", result.kind)
        assertNotNull(result.pinResult)
        assertEquals("lockfile_entry", result.pinResult!!.target)
        assertEquals("h-hero", result.pinResult!!.inputHash)
        assertEquals(true, result.pinResult!!.pinnedAfter)
        assertNull(result.lifecycleResult)
        assertNull(result.maintenanceResult)
        assertNull(result.snapshotResult)
    }

    @Test fun lifecycleKindRoutesToProjectLifecycleActionAndPopulatesLifecycleResult() = runTest {
        val (dispatcher, pid) = dispatcherWithProject()
        val result = dispatcher.execute(
            ProjectActionDispatcherTool.Input.Lifecycle(
                ProjectLifecycleActionTool.Input(
                    action = "rename",
                    projectId = pid.value,
                    title = "renamed-demo",
                ),
            ),
            ctx(),
        ).data
        assertEquals("lifecycle", result.kind)
        assertNotNull(result.lifecycleResult)
        assertEquals("rename", result.lifecycleResult!!.action)
        assertNotNull(result.lifecycleResult!!.renameResult)
        assertEquals("renamed-demo", result.lifecycleResult!!.renameResult!!.title)
        assertNull(result.pinResult)
    }

    @Test fun snapshotKindRoutesToProjectSnapshotActionAndPopulatesSnapshotResult() = runTest {
        val (dispatcher, pid) = dispatcherWithProject()
        val result = dispatcher.execute(
            ProjectActionDispatcherTool.Input.Snapshot(
                ProjectSnapshotActionTool.Input(
                    action = "save",
                    projectId = pid.value,
                    label = "before-edit",
                ),
            ),
            ctx(),
        ).data
        assertEquals("snapshot", result.kind)
        assertNotNull(result.snapshotResult)
        assertEquals("save", result.snapshotResult!!.action)
        assertEquals("before-edit", result.snapshotResult!!.label)
        assertNull(result.lifecycleResult)
        assertNull(result.maintenanceResult)
        assertNull(result.pinResult)
    }

    @Test fun maintenanceKindRoutesToProjectMaintenanceActionAndPopulatesMaintenanceResult() = runTest {
        val (dispatcher, pid) = dispatcherWithProject()
        val result = dispatcher.execute(
            ProjectActionDispatcherTool.Input.Maintenance(
                ProjectMaintenanceActionTool.Input(
                    action = "prune-lockfile",
                    projectId = pid.value,
                ),
            ),
            ctx(),
        ).data
        assertEquals("maintenance", result.kind)
        assertNotNull(result.maintenanceResult)
        assertEquals("prune-lockfile", result.maintenanceResult!!.action)
        assertNull(result.lifecycleResult)
        assertNull(result.pinResult)
        assertNull(result.snapshotResult)
    }

    @Test fun permissionFromRoutesByKindToUnderlyingTool() {
        val store = ProjectStoreTestKit.create()
        val lifecycle = ProjectLifecycleActionTool(store)
        val maintenance = ProjectMaintenanceActionTool(store, NoopVideoEngine)
        val pin = ProjectPinActionTool(store)
        val snapshot = ProjectSnapshotActionTool(store)
        val tool = ProjectActionDispatcherTool(lifecycle, maintenance, pin, snapshot)

        // lifecycle.delete → project.destructive (via lifecycle.permissionFrom).
        val deleteJson =
            """{"kind":"lifecycle","args":{"action":"delete","projectId":"p","deleteFiles":true}}"""
        assertEquals("project.destructive", tool.permission.permissionFrom(deleteJson))
        // lifecycle.open → project.read (via lifecycle.permissionFrom).
        val openJson = """{"kind":"lifecycle","args":{"action":"open","path":"/tmp/x"}}"""
        assertEquals("project.read", tool.permission.permissionFrom(openJson))
        // pin → project.write (fixed).
        val pinJson = """{"kind":"pin","args":{"projectId":"p","target":"clip","clipId":"c","pinned":true}}"""
        assertEquals("project.write", tool.permission.permissionFrom(pinJson))
        // Malformed input falls back to base tier.
        val malformed = """not even json"""
        assertEquals("project.write", tool.permission.permissionFrom(malformed))
    }
}
