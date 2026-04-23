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
        val tool = SetToolEnabledTool(sessions)

        val out = tool.execute(
            SetToolEnabledTool.Input(toolId = "generate_video", enabled = false),
            ctxFor(sid),
        ).data

        assertEquals("generate_video", out.toolId)
        assertEquals(false, out.enabled)
        assertTrue(out.changed, "first disable must be a mutation, not a no-op")
        assertEquals(setOf("generate_video"), sessions.getSession(sid)!!.disabledToolIds)
    }

    @Test fun reEnablingRemovesFromDisabledToolIds() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-re-enable")
        val tool = SetToolEnabledTool(sessions)

        tool.execute(SetToolEnabledTool.Input(toolId = "generate_video", enabled = false), ctxFor(sid))
        val out = tool.execute(
            SetToolEnabledTool.Input(toolId = "generate_video", enabled = true),
            ctxFor(sid),
        ).data

        assertTrue(out.changed, "re-enable must be a mutation")
        assertEquals(emptySet(), sessions.getSession(sid)!!.disabledToolIds)
    }

    @Test fun noopWhenAlreadyInRequestedState() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-noop")
        val tool = SetToolEnabledTool(sessions)

        // Session starts empty (tool enabled by default) → enable=true is a no-op.
        val noopEnable = tool.execute(
            SetToolEnabledTool.Input(toolId = "generate_video", enabled = true),
            ctxFor(sid),
        ).data
        assertFalse(noopEnable.changed, "enabling an already-enabled tool must be a no-op")
        assertEquals(emptySet(), sessions.getSession(sid)!!.disabledToolIds)

        tool.execute(SetToolEnabledTool.Input(toolId = "generate_video", enabled = false), ctxFor(sid))
        // Now disabled → disable=false again is a no-op.
        val noopDisable = tool.execute(
            SetToolEnabledTool.Input(toolId = "generate_video", enabled = false),
            ctxFor(sid),
        ).data
        assertFalse(noopDisable.changed, "disabling an already-disabled tool must be a no-op")
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
        val tool = SetToolEnabledTool(sessions)
        val ex = runCatching {
            tool.execute(SetToolEnabledTool.Input(toolId = "   ", enabled = false), ctxFor(sid))
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "blank toolId must fail loud")
    }
}
