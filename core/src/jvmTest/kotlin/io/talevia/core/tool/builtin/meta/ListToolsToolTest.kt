package io.talevia.core.tool.builtin.meta

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import io.talevia.core.tool.builtin.TodoWriteTool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListToolsToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun registryWith(): ToolRegistry {
        val registry = ToolRegistry()
        registry.register(ListToolsTool(registry))
        registry.register(TodoWriteTool())
        registry.register(EchoTool())
        return registry
    }

    @Test fun enumeratesRegisteredTools() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(),
            ctx(),
        ).data

        assertTrue(out.total >= 3)
        val ids = out.tools.map { it.id }.toSet()
        assertTrue("list_tools" in ids)
        assertTrue("todowrite" in ids)
        assertTrue("echo" in ids)
    }

    @Test fun prefixFilterNarrows() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(prefix = "list_"),
            ctx(),
        ).data

        assertEquals(1, out.total)
        assertEquals("list_tools", out.tools.single().id)
    }

    @Test fun limitCaps() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(limit = 2),
            ctx(),
        ).data
        assertEquals(2, out.returned)
        assertTrue(out.total >= 3)
    }

    @Test fun helpTextAndPermissionSurface() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(prefix = "list_tools"),
            ctx(),
        ).data
        val self = out.tools.single()
        assertTrue(self.helpText.isNotBlank())
        assertEquals("tool.read", self.permission)
    }

    @Test fun emptyResultOnNoMatch() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(prefix = "zzzz-no-match"),
            ctx(),
        ).data
        assertEquals(0, out.returned)
        assertTrue(out.tools.isEmpty())
    }
}
