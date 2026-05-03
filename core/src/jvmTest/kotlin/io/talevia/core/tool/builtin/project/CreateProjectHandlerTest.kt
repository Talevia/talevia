package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [executeCreateProject] —
 * `core/tool/builtin/project/CreateProjectHandler.kt`. The
 * `project_lifecycle_action(action="create")` handler.
 * Cycle 199 audit: 99 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`resolutionPreset` accept-list: null/""/1080p →
 *    1920x1080; 720p → 1280x720; 4k or 2160p →
 *    3840x2160; case-insensitive.** Drift in any preset
 *    would silently change every newly-created project's
 *    resolution. Pinned per documented preset; unknown
 *    rejected with documented accept-list message.
 *
 * 2. **`fps` accept-list: null/30 → FPS_30; 24 →
 *    FPS_24; 60 → FPS_60; other → throw with documented
 *    list.** Plus default-when-null pin (drift to "throw
 *    on null fps" would force agents to always specify).
 *
 * 3. **Two persistence branches: explicit `path` →
 *    `createAt`; null path → slug + collision-check +
 *    upsert.** Plus marquee `autoBindSessionToProject`
 *    side effect — null sessions store is a no-op (test
 *    rigs); non-null binds the session.
 */
class CreateProjectHandlerTest {

    private val ctx: ToolContext = ToolContext(
        sessionId = SessionId("ctx-session"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun newSessionStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(store: SqlDelightSessionStore, sid: String): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("seed"),
                title = "test",
                createdAt = Instant.fromEpochMilliseconds(1L),
                updatedAt = Instant.fromEpochMilliseconds(1L),
            ),
        )
        return sessionId
    }

    private fun input(
        title: String?,
        path: String? = null,
        resolutionPreset: String? = null,
        fps: Int? = null,
        projectId: String? = null,
    ): ProjectLifecycleActionTool.Input = ProjectLifecycleActionTool.Input(
        action = "create",
        title = title,
        path = path,
        resolutionPreset = resolutionPreset,
        fps = fps,
        projectId = projectId,
    )

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingTitleThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalStateException> {
            executeCreateProject(
                projects = store,
                sessions = null,
                clock = Clock.System,
                input = input(title = null),
                ctx = ctx,
            )
        }
        assertTrue(
            "requires non-blank `title`" in (ex.message ?: ""),
            "expected requires phrase; got: ${ex.message}",
        )
    }

    @Test fun blankTitleThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        assertFailsWith<IllegalStateException> {
            executeCreateProject(
                projects = store,
                sessions = null,
                clock = Clock.System,
                input = input(title = "   "),
                ctx = ctx,
            )
        }
    }

    // ── resolutionPreset accept-list ────────────────────────

    @Test fun nullResolutionPresetDefaultsTo1080p() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test"),
            ctx = ctx,
        )
        val createResult = result.data.createResult!!
        assertEquals(1920, createResult.resolutionWidth)
        assertEquals(1080, createResult.resolutionHeight)
    }

    @Test fun emptyStringResolutionPresetAlsoDefaultsTo1080p() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", resolutionPreset = ""),
            ctx = ctx,
        )
        assertEquals(1920, result.data.createResult!!.resolutionWidth)
        assertEquals(1080, result.data.createResult!!.resolutionHeight)
    }

    @Test fun explicit1080pResolves() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", resolutionPreset = "1080p"),
            ctx = ctx,
        )
        assertEquals(1920, result.data.createResult!!.resolutionWidth)
        assertEquals(1080, result.data.createResult!!.resolutionHeight)
    }

    @Test fun resolution720pResolvesTo1280x720() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", resolutionPreset = "720p"),
            ctx = ctx,
        )
        assertEquals(1280, result.data.createResult!!.resolutionWidth)
        assertEquals(720, result.data.createResult!!.resolutionHeight)
    }

    @Test fun resolution4kResolvesTo3840x2160() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", resolutionPreset = "4k"),
            ctx = ctx,
        )
        assertEquals(3840, result.data.createResult!!.resolutionWidth)
        assertEquals(2160, result.data.createResult!!.resolutionHeight)
    }

    @Test fun resolution2160pIsAliasFor4k() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", resolutionPreset = "2160p"),
            ctx = ctx,
        )
        assertEquals(3840, result.data.createResult!!.resolutionWidth)
        assertEquals(2160, result.data.createResult!!.resolutionHeight)
    }

    @Test fun resolutionPresetIsCaseInsensitive() = runTest {
        // Pin: per `preset?.lowercase()`, "1080P" / "720P"
        // / "4K" all resolve.
        val store = ProjectStoreTestKit.create()
        val resultUpper = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "T1", resolutionPreset = "720P"),
            ctx = ctx,
        )
        assertEquals(1280, resultUpper.data.createResult!!.resolutionWidth)
        val resultMixed = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "T2", resolutionPreset = "4K"),
            ctx = ctx,
        )
        assertEquals(3840, resultMixed.data.createResult!!.resolutionWidth)
    }

    @Test fun unknownResolutionPresetThrowsWithAcceptList() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalArgumentException> {
            executeCreateProject(
                projects = store,
                sessions = null,
                clock = Clock.System,
                input = input(title = "Test", resolutionPreset = "8k"),
                ctx = ctx,
            )
        }
        assertTrue(
            "unknown resolutionPreset" in (ex.message ?: ""),
            "expected unknown phrase; got: ${ex.message}",
        )
        assertTrue(
            "8k" in (ex.message ?: ""),
            "expected bad input cited; got: ${ex.message}",
        )
        assertTrue(
            "720p" in (ex.message ?: "") &&
                "1080p" in (ex.message ?: "") &&
                "4k" in (ex.message ?: ""),
            "expected accept-list cited; got: ${ex.message}",
        )
    }

    // ── fps accept-list ─────────────────────────────────────

    @Test fun nullFpsDefaultsTo30() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", fps = null),
            ctx = ctx,
        )
        assertEquals(30, result.data.createResult!!.fps)
    }

    @Test fun explicit30FpsResolves() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", fps = 30),
            ctx = ctx,
        )
        assertEquals(30, result.data.createResult!!.fps)
    }

    @Test fun fps24Resolves() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", fps = 24),
            ctx = ctx,
        )
        assertEquals(24, result.data.createResult!!.fps)
    }

    @Test fun fps60Resolves() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", fps = 60),
            ctx = ctx,
        )
        assertEquals(60, result.data.createResult!!.fps)
    }

    @Test fun unsupportedFpsThrowsWithAcceptList() = runTest {
        val store = ProjectStoreTestKit.create()
        val ex = assertFailsWith<IllegalArgumentException> {
            executeCreateProject(
                projects = store,
                sessions = null,
                clock = Clock.System,
                input = input(title = "Test", fps = 120),
                ctx = ctx,
            )
        }
        assertTrue(
            "unsupported fps=120" in (ex.message ?: ""),
            "expected unsupported phrase + value; got: ${ex.message}",
        )
        assertTrue(
            "24" in (ex.message ?: "") &&
                "30" in (ex.message ?: "") &&
                "60" in (ex.message ?: ""),
            "expected accept-list cited; got: ${ex.message}",
        )
    }

    // ── Two persistence branches ────────────────────────────

    @Test fun explicitPathBranchUsesCreateAt() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Bundle Project", path = "/projects/p1"),
            ctx = ctx,
        )
        // Project created at the documented path.
        val pid = ProjectId(result.data.projectId)
        assertEquals("/projects/p1", store.pathOf(pid)?.toString())
        assertEquals("Bundle Project", store.summary(pid)!!.title)
    }

    @Test fun nullPathBranchSlugsTitle() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Graduation Vlog 2026", path = null),
            ctx = ctx,
        )
        // Project id derived from title slug.
        assertEquals("proj-graduation-vlog-2026", result.data.projectId)
    }

    @Test fun nullPathWithExplicitProjectIdHonorsId() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", path = null, projectId = "user-chosen-id"),
            ctx = ctx,
        )
        assertEquals("user-chosen-id", result.data.projectId)
    }

    @Test fun nullPathDuplicateIdThrows() = runTest {
        val store = ProjectStoreTestKit.create()
        // Create first.
        executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", projectId = "duplicate-id"),
            ctx = ctx,
        )
        // Attempt second create with same id → require fails.
        val ex = assertFailsWith<IllegalArgumentException> {
            executeCreateProject(
                projects = store,
                sessions = null,
                clock = Clock.System,
                input = input(title = "Different", projectId = "duplicate-id"),
                ctx = ctx,
            )
        }
        assertTrue("already exists" in (ex.message ?: ""))
        assertTrue("list_projects" in (ex.message ?: ""), "discoverability hint cited")
    }

    // ── autoBindSessionToProject side effect ────────────────

    @Test fun nullSessionsStoreIsNoOpForBinding() = runTest {
        // Pin: sessions=null → autoBindSessionToProject
        // returns early (test rig pattern). No throw, no
        // mutation needed. Body omits the bind note.
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test"),
            ctx = ctx,
        )
        // Body does NOT cite "Session ... bound".
        assertTrue(
            "now bound" !in result.outputForLlm,
            "no bind note when sessions=null; got: ${result.outputForLlm}",
        )
    }

    @Test fun nonNullSessionsStoreBindsSessionToProject() = runTest {
        // Pin: sessions present → autoBindSessionToProject
        // updates ctx session's currentProjectId.
        val projectStore = ProjectStoreTestKit.create()
        val sessionStore = newSessionStore()
        val sid = seedSession(sessionStore, "ctx-session")

        val result = executeCreateProject(
            projects = projectStore,
            sessions = sessionStore,
            clock = Clock.System,
            input = input(title = "Bound Test"),
            ctx = ctx,
        )
        // Session's currentProjectId now points at the new project.
        val updatedSession = sessionStore.getSession(sid)!!
        assertEquals(result.data.projectId, updatedSession.currentProjectId?.value)
        // Body cites the bind note.
        assertTrue(
            "Session ctx-session now bound" in result.outputForLlm,
            "body cites bind note; got: ${result.outputForLlm}",
        )
    }

    // ── outputForLlm format ─────────────────────────────────

    @Test fun outputForLlmCitesProjectIdTitleResolutionAndFpsAndProjectIdHint() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Vlog Test", resolutionPreset = "720p", fps = 60),
            ctx = ctx,
        )
        assertTrue("Created project" in result.outputForLlm)
        assertTrue("Vlog Test" in result.outputForLlm)
        assertTrue(
            "1280x720@60fps" in result.outputForLlm,
            "resolution+fps format; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Pass projectId=" in result.outputForLlm,
            "next-step hint; got: ${result.outputForLlm}",
        )
    }

    @Test fun outputForLlmIncludesPathNoteWhenPathProvided() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test", path = "/projects/p1"),
            ctx = ctx,
        )
        assertTrue(
            "at /projects/p1" in result.outputForLlm,
            "path note appended; got: ${result.outputForLlm}",
        )
    }

    @Test fun outputForLlmOmitsPathNoteWhenPathAbsent() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test"),
            ctx = ctx,
        )
        // outputForLlm has " at WIDTHxHEIGHT@FPS" but NOT
        // " at /path".
        assertTrue(
            " at /" !in result.outputForLlm,
            "no path note; got: ${result.outputForLlm}",
        )
    }

    // ── Output shape ────────────────────────────────────────

    @Test fun outputCarriesCreateResultNotOtherResultFields() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Test"),
            ctx = ctx,
        )
        assertTrue(result.data.createResult != null, "createResult populated")
        assertTrue(result.data.deleteResult == null)
        assertTrue(result.data.openResult == null)
        assertTrue(result.data.renameResult == null)
        assertEquals("create", result.data.action)
    }

    @Test fun toolResultTitleCitesProjectTitle() = runTest {
        val store = ProjectStoreTestKit.create()
        val result = executeCreateProject(
            projects = store,
            sessions = null,
            clock = Clock.System,
            input = input(title = "Memorable Title"),
            ctx = ctx,
        )
        assertTrue("create project Memorable Title" in result.title!!)
    }
}
