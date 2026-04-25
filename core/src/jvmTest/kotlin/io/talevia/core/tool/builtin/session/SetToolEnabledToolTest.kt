package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolAvailabilityContext
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cycle 142 folded `set_tool_enabled` into
 * `session_action(action="set_tool_enabled")`. This suite continues
 * to pin the upsert + no-op semantics, the registry-filter
 * downstream effect, and the blank-toolId guard, but routes through
 * the unified action dispatcher.
 */
class SetToolEnabledToolTest {

    private val now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun freshSessions(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun SqlDelightSessionStore.seed(id: String): SessionId {
        val sid = SessionId(id)
        createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }

    private fun ctxFor(sid: SessionId): ToolContext = ToolContext(
        sessionId = sid,
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun setToolEnabledInput(toolId: String, enabled: Boolean) = SessionActionTool.Input(
        action = "set_tool_enabled",
        toolId = toolId,
        enabled = enabled,
    )

    /** Minimal test tool used to verify registry-level filtering. */
    @Serializable data class Nothing(val n: Int = 0)
    private class TestTool(override val id: String) : Tool<Nothing, Nothing> {
        override val helpText: String = "test"
        override val inputSchema: JsonObject = buildJsonObject { }
        override val inputSerializer: KSerializer<Nothing> = serializer()
        override val outputSerializer: KSerializer<Nothing> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("test")
        override suspend fun execute(input: Nothing, ctx: ToolContext): ToolResult<Nothing> =
            ToolResult("t", "ok", Nothing(0))
    }

    @Test fun disablingATollAddsItToDisabledToolIds() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-disable")
        val tool = SessionActionTool(sessions)

        val out = tool.execute(
            setToolEnabledInput(toolId = "generate_video", enabled = false),
            ctxFor(sid),
        ).data

        assertEquals("generate_video", out.toolId)
        assertEquals(false, out.enabled)
        assertTrue(out.toolEnabledChanged, "first disable must be a mutation, not a no-op")
        assertEquals(setOf("generate_video"), sessions.getSession(sid)!!.disabledToolIds)
    }

    @Test fun reEnablingRemovesFromDisabledToolIds() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-re-enable")
        val tool = SessionActionTool(sessions)

        tool.execute(setToolEnabledInput(toolId = "generate_video", enabled = false), ctxFor(sid))
        val out = tool.execute(
            setToolEnabledInput(toolId = "generate_video", enabled = true),
            ctxFor(sid),
        ).data

        assertTrue(out.toolEnabledChanged, "re-enable must be a mutation")
        assertEquals(emptySet(), sessions.getSession(sid)!!.disabledToolIds)
    }

    @Test fun noopWhenAlreadyInRequestedState() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-noop")
        val tool = SessionActionTool(sessions)

        // Session starts empty (tool enabled by default) → enable=true is a no-op.
        val noopEnable = tool.execute(
            setToolEnabledInput(toolId = "generate_video", enabled = true),
            ctxFor(sid),
        ).data
        assertFalse(noopEnable.toolEnabledChanged, "enabling an already-enabled tool must be a no-op")
        assertEquals(emptySet(), sessions.getSession(sid)!!.disabledToolIds)

        tool.execute(setToolEnabledInput(toolId = "generate_video", enabled = false), ctxFor(sid))
        // Now disabled → disable=false again is a no-op.
        val noopDisable = tool.execute(
            setToolEnabledInput(toolId = "generate_video", enabled = false),
            ctxFor(sid),
        ).data
        assertFalse(noopDisable.toolEnabledChanged, "disabling an already-disabled tool must be a no-op")
    }

    /**
     * The point of this feature: a disabled tool MUST be absent from the LLM's
     * tool bundle the next turn. Exercise the registry filter directly rather
     * than going through the agent — integration tests are heavier but wouldn't
     * cover this specific invariant more precisely.
     */
    @Test fun registrySpecsHidesDisabledTools() = runTest {
        val registry = ToolRegistry()
        registry.register(TestTool("alpha"))
        registry.register(TestTool("generate_video"))
        registry.register(TestTool("zeta"))

        val allSpecs = registry.specs(
            ToolAvailabilityContext(currentProjectId = null, disabledToolIds = emptySet()),
        )
        assertEquals(setOf("alpha", "generate_video", "zeta"), allSpecs.map { it.id }.toSet())

        val filteredSpecs = registry.specs(
            ToolAvailabilityContext(
                currentProjectId = null,
                disabledToolIds = setOf("generate_video"),
            ),
        )
        assertEquals(setOf("alpha", "zeta"), filteredSpecs.map { it.id }.toSet())
        // Dispatch still works — the tool isn't unregistered, just hidden.
        // This is important: a tool disabled mid-session can still finish an
        // in-flight call that had already been dispatched.
        assertTrue(registry["generate_video"] != null, "tool stays registered even when hidden")
    }

    @Test fun rejectsBlankToolId() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-blank")
        val tool = SessionActionTool(sessions)
        val ex = runCatching {
            tool.execute(setToolEnabledInput(toolId = "   ", enabled = false), ctxFor(sid))
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "blank toolId must fail loud")
    }

    @Test fun rejectsMissingEnabled() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-no-enabled")
        val tool = SessionActionTool(sessions)
        val ex = runCatching {
            tool.execute(
                SessionActionTool.Input(action = "set_tool_enabled", toolId = "generate_video"),
                ctxFor(sid),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException, "missing enabled flag must fail loud")
        assertTrue(ex.message!!.contains("enabled"), ex.message)
    }
}
