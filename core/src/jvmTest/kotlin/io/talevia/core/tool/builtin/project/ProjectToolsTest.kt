package io.talevia.core.tool.builtin.project

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectToolsTest {

    private fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
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

    private data class Rig(val store: FileProjectStore, val ctx: ToolContext)

    private fun createInput(
        title: String,
        projectId: String? = null,
        resolutionPreset: String? = null,
        fps: Int? = null,
    ) = ProjectLifecycleActionTool.Input(
        action = "create",
        title = title,
        projectId = projectId,
        resolutionPreset = resolutionPreset,
        fps = fps,
    )

    @Test fun createProjectSlugsTitleAndPersistsDefaults() = runTest {
        val rig = rig()
        val tool = ProjectLifecycleActionTool(rig.store)
        val out = tool.execute(createInput(title = "Graduation Vlog 2026"), rig.ctx).data
        val create = assertNotNull(out.createResult)
        assertEquals("proj-graduation-vlog-2026", out.projectId)
        assertEquals(1920, create.resolutionWidth)
        assertEquals(1080, create.resolutionHeight)
        assertEquals(30, create.fps)
        val project = rig.store.get(ProjectId(out.projectId))
        assertNotNull(project)
        assertEquals(1920, project.outputProfile.resolution.width)
        assertEquals(0, project.timeline.tracks.size)
    }

    @Test fun createProjectAcceptsResolutionAndFpsOverrides() = runTest {
        val rig = rig()
        val tool = ProjectLifecycleActionTool(rig.store)
        val out = tool.execute(
            createInput(title = "promo", resolutionPreset = "4k", fps = 60),
            rig.ctx,
        ).data
        val create = assertNotNull(out.createResult)
        assertEquals(3840, create.resolutionWidth)
        assertEquals(2160, create.resolutionHeight)
        assertEquals(60, create.fps)
    }

    @Test fun createProjectRejectsUnknownPreset() = runTest {
        val rig = rig()
        val tool = ProjectLifecycleActionTool(rig.store)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(createInput(title = "x", resolutionPreset = "8k"), rig.ctx)
        }
    }

    @Test fun createProjectFailsLoudOnDuplicateId() = runTest {
        val rig = rig()
        val tool = ProjectLifecycleActionTool(rig.store)
        tool.execute(createInput(title = "Mei", projectId = "proj-mei"), rig.ctx)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(createInput(title = "Mei v2", projectId = "proj-mei"), rig.ctx)
        }
        assertTrue("already exists" in ex.message.orEmpty())
    }

    @Test fun createProjectRejectsBlankTitle() = runTest {
        val rig = rig()
        val tool = ProjectLifecycleActionTool(rig.store)
        assertFailsWith<IllegalStateException> {
            tool.execute(ProjectLifecycleActionTool.Input(action = "create"), rig.ctx)
        }
    }

    @Test fun listProjectsReturnsCatalogMetadata() = runTest {
        val rig = rig()
        val create = ProjectLifecycleActionTool(rig.store)
        create.execute(createInput(title = "Alpha"), rig.ctx)
        create.execute(createInput(title = "Beta"), rig.ctx)

        val tool = ListProjectsTool(rig.store)
        val out = tool.execute(ListProjectsTool.Input(), rig.ctx)
        assertEquals(2, out.data.totalCount)
        val titles = out.data.projects.map { it.title }.toSet()
        assertEquals(setOf("Alpha", "Beta"), titles)
    }

    @Test fun listProjectsEmptyHasFriendlyMessage() = runTest {
        val rig = rig()
        val out = ListProjectsTool(rig.store).execute(ListProjectsTool.Input(), rig.ctx)
        assertEquals(0, out.data.totalCount)
        assertTrue("create_project" in out.outputForLlm)
    }

    @Test fun projectMetadataReportsCountsAndProfile() = runTest {
        // Migrated from the deleted GetProjectStateTool tests (cycle 129).
        // `project_query(select=project_metadata)` is now the single
        // whole-project orientation snapshot; the new sourceRevision +
        // renderCacheEntryCount fields preserve the prior tool's surface.
        val rig = rig()
        ProjectLifecycleActionTool(rig.store).execute(
            createInput(title = "snap", resolutionPreset = "720p", fps = 24),
            rig.ctx,
        )
        val out = io.talevia.core.tool.builtin.project.ProjectQueryTool(rig.store).execute(
            io.talevia.core.tool.builtin.project.ProjectQueryTool.Input(
                projectId = "proj-snap",
                select = io.talevia.core.tool.builtin.project.ProjectQueryTool.SELECT_PROJECT_METADATA,
            ),
            rig.ctx,
        )
        val rows = io.talevia.core.JsonConfig.default.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(
                io.talevia.core.tool.builtin.project.query.ProjectMetadataRow.serializer(),
            ),
            out.data.rows,
        )
        val row = rows.single()
        assertEquals("snap", row.title)
        assertEquals(1280, row.outputProfile?.resolutionWidth)
        assertEquals(720, row.outputProfile?.resolutionHeight)
        assertEquals(24, row.outputProfile?.frameRate)
        assertEquals(0, row.assetCount)
        assertEquals(0, row.sourceNodeCount)
        assertEquals(0, row.lockfileEntryCount)
        assertEquals(0, row.renderCacheEntryCount)
        assertEquals(0L, row.sourceRevision)
        assertEquals(0, row.trackCount)
    }

    @Test fun projectMetadataFailsForMissingProject() = runTest {
        val rig = rig()
        assertFailsWith<IllegalStateException> {
            io.talevia.core.tool.builtin.project.ProjectQueryTool(rig.store).execute(
                io.talevia.core.tool.builtin.project.ProjectQueryTool.Input(
                    projectId = "proj-nope",
                    select = io.talevia.core.tool.builtin.project.ProjectQueryTool.SELECT_PROJECT_METADATA,
                ),
                rig.ctx,
            )
        }
    }

    @Test fun deleteProjectRemovesRowAndReportsTitle() = runTest {
        val rig = rig()
        val tool = ProjectLifecycleActionTool(rig.store)
        tool.execute(createInput(title = "doomed"), rig.ctx)
        val out = tool.execute(
            ProjectLifecycleActionTool.Input(action = "delete", projectId = "proj-doomed"),
            rig.ctx,
        ).data
        val delete = assertNotNull(out.deleteResult)
        assertEquals("doomed", delete.title)
        assertNull(rig.store.get(ProjectId("proj-doomed")))
    }

    @Test fun deleteProjectFailsLoudWhenMissing() = runTest {
        val rig = rig()
        assertFailsWith<IllegalStateException> {
            ProjectLifecycleActionTool(rig.store).execute(
                ProjectLifecycleActionTool.Input(action = "delete", projectId = "proj-ghost"),
                rig.ctx,
            )
        }
    }

    @Test fun deleteRequiresProjectId() = runTest {
        val rig = rig()
        assertFailsWith<IllegalStateException> {
            ProjectLifecycleActionTool(rig.store).execute(
                ProjectLifecycleActionTool.Input(action = "delete"),
                rig.ctx,
            )
        }
    }

    @Test fun unknownActionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectLifecycleActionTool(rig.store).execute(
                ProjectLifecycleActionTool.Input(action = "frobnicate"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("unknown action"))
    }

    @Test fun slugifierHandlesPunctuationAndCollapses() {
        assertEquals("proj-hello-world", slugifyProjectId("Hello, World!!!"))
        // No usable ASCII alphanumerics → null. Caller falls back to a UUID
        // (see `resolveDefaultHomeProjectId`).
        assertNull(slugifyProjectId("***"))
        assertEquals("proj-a-b-c", slugifyProjectId("  a---b---c  "))
    }

    @Test fun slugifierRejectsNonAsciiLetters() {
        // CJK / accented letters pass `Char.isLetterOrDigit()` but break the
        // SAFE_ID regex used as a default bundle directory name in
        // FileProjectStore. The slugifier must drop them as separators rather
        // than emit a `proj-<cjk>` id that crashes downstream.
        assertNull(slugifyProjectId("新视频项目"))
        assertNull(slugifyProjectId("…!?"))
        // Mixed: ASCII portion survives, non-ASCII portion drops.
        assertEquals("proj-vlog", slugifyProjectId("vlog 视频"))
        assertEquals("proj-caf", slugifyProjectId("café"))
    }

    @Test fun resolveDefaultHomeProjectIdFallsBackToUuidForUnslugableTitle() {
        // Two CJK-only creates back-to-back must each get a unique id (no
        // `proj-untitled` collision). The UUID branch matches what
        // FileProjectStore.createAt mints internally.
        val first = resolveDefaultHomeProjectId(explicitId = null, title = "新视频项目")
        val second = resolveDefaultHomeProjectId(explicitId = null, title = "新视频项目")
        assertTrue(first != second, "expected unique UUIDs, got '$first' and '$second'")
        // Sanity: should NOT start with `proj-` (UUID branch, not slug branch).
        assertTrue(!first.startsWith("proj-"))
    }

    @Test fun createProjectWithCjkTitleSucceedsViaUuidFallback() = runTest {
        val rig = rig()
        val tool = ProjectLifecycleActionTool(rig.store)
        val out = tool.execute(createInput(title = "新视频项目"), rig.ctx).data
        // Id is a UUID (no `proj-` prefix), and the project is retrievable.
        assertTrue(!out.projectId.startsWith("proj-"))
        assertNotNull(rig.store.get(ProjectId(out.projectId)))
    }

    @Test fun createProjectAutoBindsSessionWhenSessionStoreProvided() = runTest {
        // Reproduces the user-reported flow: model calls
        // `project_lifecycle_action(action="create")` and immediately needs the session
        // bound to the new project. Pre-fix the model had to call
        // `switch_project` afterwards, which the mid-run guard rejected
        // because the agent was still `AwaitingTool` for the create dispatch.
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        io.talevia.core.db.TaleviaDb.Schema.create(driver)
        val db = io.talevia.core.db.TaleviaDb(driver)
        val bus = io.talevia.core.bus.EventBus()
        val sessions = io.talevia.core.session.SqlDelightSessionStore(db, bus)
        val past = kotlinx.datetime.Instant.fromEpochMilliseconds(1_600_000_000_000L)
        sessions.createSession(
            io.talevia.core.session.Session(
                id = SessionId("s"),
                projectId = ProjectId("p-origin"),
                title = "Untitled",
                createdAt = past,
                updatedAt = past,
                currentProjectId = null,
            ),
        )
        // Wire the ToolContext's publishEvent so we can assert the
        // `SessionProjectBindingChanged` fanout.
        val publishedEvents = mutableListOf<io.talevia.core.bus.BusEvent>()
        val rigStore = ProjectStoreTestKit.create()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
            publishEvent = { publishedEvents.add(it) },
        )
        val tool = ProjectLifecycleActionTool(rigStore, sessions = sessions)

        val out = tool.execute(createInput(title = "Bound"), ctx).data
        val pid = ProjectId(out.projectId)

        val refreshed = assertNotNull(sessions.getSession(SessionId("s")))
        assertEquals(pid, refreshed.currentProjectId)
        assertTrue(refreshed.updatedAt > past)

        val event = publishedEvents.filterIsInstance<
            io.talevia.core.bus.BusEvent.SessionProjectBindingChanged,
        >().single()
        assertEquals(SessionId("s"), event.sessionId)
        assertNull(event.previousProjectId)
        assertEquals(pid, event.newProjectId)
    }

    @Test fun createProjectIsNoOpAutoBindWhenSessionAlreadyBound() = runTest {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        io.talevia.core.db.TaleviaDb.Schema.create(driver)
        val db = io.talevia.core.db.TaleviaDb(driver)
        val bus = io.talevia.core.bus.EventBus()
        val sessions = io.talevia.core.session.SqlDelightSessionStore(db, bus)
        val past = kotlinx.datetime.Instant.fromEpochMilliseconds(1_600_000_000_000L)
        sessions.createSession(
            io.talevia.core.session.Session(
                id = SessionId("s"),
                projectId = ProjectId("p-origin"),
                title = "Untitled",
                createdAt = past,
                updatedAt = past,
                // Already bound to the same id we'll create with — no rebind expected.
                currentProjectId = ProjectId("proj-foo"),
            ),
        )
        val publishedEvents = mutableListOf<io.talevia.core.bus.BusEvent>()
        val rigStore = ProjectStoreTestKit.create()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
            publishEvent = { publishedEvents.add(it) },
        )
        ProjectLifecycleActionTool(rigStore, sessions = sessions).execute(
            createInput(title = "Foo", projectId = "proj-foo"),
            ctx,
        )
        // No-op rebind → no SessionProjectBindingChanged event.
        assertTrue(
            publishedEvents.none { it is io.talevia.core.bus.BusEvent.SessionProjectBindingChanged },
            "expected no rebind event for same-id create, got: $publishedEvents",
        )
    }
}
