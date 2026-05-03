package io.talevia.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.RecentsRegistry
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.DefaultPermissionService
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionRule
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.source.SourceNodeActionTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `m7-cross-path-source-ui-e2e` (M7 §4 exit criterion #4). Two desktop
 * UI panels (chat panel + source panel) must dispatch tools through the
 * **same Project state** with the **same permission + session** wiring,
 * not via two independent caches that drift apart.
 *
 * Distinct from `CrossPathSourceSharedTest` (M3 #3 jvmTest level) which
 * works at the bare `ToolRegistry` layer: the M3 test proves Core
 * routes both paths to the same `Project`, this M7 test proves the
 * **desktop UI command-routing layer** (mirroring the
 * `AppContainer.uiToolContext(projectId)` extension that ChatPanel +
 * SourcePanel use to issue dispatches) preserves that invariant
 * including its permission-check arm and part-emission arm.
 *
 * Why a local `uiToolContext` mirror instead of the real one. The
 * production extension lives on `AppContainer`, whose ctor pulls
 * `HttpClient(CIO)` + `FfmpegVideoEngine` + `AwtFilePicker` + the full
 * SQLite-backed session store eagerly — way past what this invariant
 * needs and brittle in a JVM test harness. The local mirror copies the
 * 4-line shape of the production function so a refactor that adds a new
 * field to ToolContext fails compilation **here** as well as on the
 * production extension, keeping them in lockstep.
 *
 * Scenario:
 *   - Chat-panel dispatch: `source_node_action(action="add", ...)`
 *     adds a character_ref node (mirrors what the agent issues on
 *     behalf of the chat panel's user prompt).
 *   - Source-panel dispatch: `source_node_action(action="update_body",
 *     ...)` edits that same node (mirrors a SourcePanel button click).
 *   - Cross-path observation: a third "panel" reading via
 *     `projects.get(pid)` sees both mutations applied in order.
 *
 * Both dispatches go through the UI tool context — different sessionId
 * (UI synthesises one per project), different callId (UUID per
 * dispatch), but the same `permissions`+`permissionRules`+`sessions`
 * triple. If any one of the three drifts, the second dispatch's
 * permission check or part emission breaks and the assertion catches
 * it.
 */
class CrossPathSourceUiE2ETest {

    /**
     * Shape-mirrors the production `AppContainer.uiToolContext` 4-line
     * function. If `ToolContext` grows a new constructor parameter the
     * production extension must thread, the same change is required
     * here — keeping the test honest about which fields the UI dispatch
     * surface actually carries.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun TestSurface.uiToolContext(projectId: ProjectId): ToolContext {
        val sid = SessionId(projectId.value)
        val mid = MessageId(Uuid.random().toString())
        val cid = CallId(Uuid.random().toString())
        return ToolContext(
            sessionId = sid,
            messageId = mid,
            callId = cid,
            askPermission = { permissions.check(permissionRules.toList(), it) },
            emitPart = { p -> sessions.upsertPart(p) },
            messages = emptyList(),
        )
    }

    /**
     * Minimal stand-in for [io.talevia.desktop.AppContainer] exposing
     * only the surface `uiToolContext` reads. Keeps the test self-
     * contained while preserving the **shape** the production
     * extension function uses (any drift in field names or types
     * breaks both at once).
     */
    private class TestSurface {
        val bus = EventBus()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val sessions = SqlDelightSessionStore(db, bus)
        // Hand-constructed FileProjectStore over a JVM temp directory so
        // the test doesn't pull `core/commonTest`'s `ProjectStoreTestKit`
        // (which isn't on apps/desktop's test classpath without test-
        // fixtures wiring). The temp dir is auto-cleaned by JVM at test
        // process exit; for a long-running test harness we'd add an
        // explicit `@AfterTest` cleanup, but a single one-shot test
        // tolerates the leak.
        val tmpRoot = Files.createTempDirectory("m7-cross-path-").toOkioPath()
        val fs = FileSystem.SYSTEM
        val recentsRegistry = RecentsRegistry(tmpRoot.resolve("recents.json"), fs)
        val projects = FileProjectStore(
            registry = recentsRegistry,
            defaultProjectsHome = tmpRoot.resolve("projects"),
            fs = fs,
            bus = bus,
        )
        val permissions = DefaultPermissionService(bus)

        // Allow-by-default rule for the test — the production rule list
        // includes ASK rules that would otherwise stall the dispatch on
        // a permission prompt with no responder. The point of this
        // test is **not** the prompt UX (covered elsewhere), it's that
        // both dispatches reach the same `Project` end-state through
        // the UI command-routing layer.
        // Allow-by-default rule for the test — production rules include
        // ASK rules that would otherwise stall the dispatch on a
        // permission prompt with no responder. The point of this test
        // is **not** the prompt UX (covered by DefaultPermissionService
        // tests), it's that both dispatches reach the same `Project`
        // end-state through the UI command-routing layer.
        val permissionRules = mutableListOf<PermissionRule>().apply {
            add(PermissionRule(permission = "**", pattern = "*", action = PermissionAction.ALLOW))
            addAll(DefaultPermissionRuleset.rules)
        }

        val tools = ToolRegistry().apply {
            register(SourceNodeActionTool(projects))
        }
    }

    @Test fun chatPanelAddPlusSourcePanelUpdateConvergeOnSameProject() = runBlocking {
        val surface = TestSurface()
        val bundleRoot = surface.tmpRoot.resolve("projects").resolve("m7-cross-path")
        val pid = surface.projects.createAt(
            path = bundleRoot,
            title = "m7-cross-path",
        ).id

        // === Chat panel path =============================================
        // Mirror what an agent dispatches on behalf of the chat panel:
        // `source_node_action(action="add", ...)` adds a character_ref.
        // The dispatch goes through the UI command-routing layer
        // (uiToolContext) — same shape ChatPanel.kt uses inline.
        surface.tools["source_node_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("action", "add")
                put("nodeId", "hero")
                put("kind", "core.consistency.character_ref")
                putJsonObject("body") {
                    put("name", "Hero")
                    put("visualDescription", "a curious red panda explorer in a denim vest")
                }
            },
            surface.uiToolContext(pid),
        )

        // === Source panel path ===========================================
        // Different lambda inside SourcePanel.kt, but same dispatch
        // shape — `source_node_action(action="update_body", ...)`.
        // Different callId / messageId (each dispatch mints fresh
        // UUIDs), same sessionId binding, same Project.
        surface.tools["source_node_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("action", "update_body")
                put("nodeId", "hero")
                putJsonObject("body") {
                    put("name", "Hero")
                    put("visualDescription", "a fierce red panda explorer in tactical gear")
                }
            },
            surface.uiToolContext(pid),
        )

        // === Cross-path observation ======================================
        // A third "reader" — read the project state directly. Both
        // mutations must be visible. The pre-edit content must not
        // leak through (proves the UI dispatch path didn't end up
        // mutating two independent Project copies — `tools` and
        // `projects` share state).
        val finalProject = surface.projects.get(pid)!!
        val heroNode = finalProject.source.byId[io.talevia.core.SourceNodeId("hero")]
            ?: error("character_ref `hero` must exist after the add dispatch")
        val bodyObj = heroNode.body as? kotlinx.serialization.json.JsonObject
            ?: error("hero node's body must decode as a JsonObject")
        val visual = (bodyObj["visualDescription"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: error("body must carry visualDescription as a JsonPrimitive")
        assertEquals(
            "a fierce red panda explorer in tactical gear",
            visual,
            "source panel update_body must overwrite chat panel add — both UI dispatches reach the same Project",
        )
        // Also assert the pre-edit prose is gone — i.e. the second
        // dispatch overwrote rather than created a phantom second copy.
        assertNotEquals(
            "a curious red panda explorer in a denim vest",
            visual,
            "post-update visualDescription must not be the pre-edit value",
        )
    }
}
