package io.talevia.core.tool

import io.talevia.core.ProjectId
import io.talevia.core.permission.PermissionSpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolApplicabilityTest {

    @Serializable data class Noop(val x: Int = 0)

    /** Baseline tool that works whether or not a project is bound. */
    private class AlwaysTool(override val id: String) : Tool<Noop, Noop> {
        override val helpText: String = "always"
        override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }
        override val inputSerializer: KSerializer<Noop> = serializer()
        override val outputSerializer: KSerializer<Noop> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("test.x")
        override suspend fun execute(input: Noop, ctx: ToolContext): ToolResult<Noop> =
            ToolResult(title = id, outputForLlm = "ok", data = Noop())
    }

    /** Project-scoped tool: hidden from the spec bundle when the session is unbound. */
    private class ProjectTool(override val id: String) : Tool<Noop, Noop> {
        override val helpText: String = "needs project"
        override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }
        override val inputSerializer: KSerializer<Noop> = serializer()
        override val outputSerializer: KSerializer<Noop> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("test.y")
        override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding
        override suspend fun execute(input: Noop, ctx: ToolContext): ToolResult<Noop> =
            ToolResult(title = id, outputForLlm = "ok", data = Noop())
    }

    @Test fun unfilteredSpecsIncludeEverything() {
        val registry = ToolRegistry().apply {
            register(AlwaysTool("a"))
            register(ProjectTool("b"))
        }
        assertEquals(setOf("a", "b"), registry.specs().map { it.id }.toSet())
    }

    @Test fun specsFilterDropsProjectToolsWhenUnbound() {
        val registry = ToolRegistry().apply {
            register(AlwaysTool("a"))
            register(ProjectTool("b"))
        }
        val ctx = ToolAvailabilityContext(currentProjectId = null)
        val visible = registry.specs(ctx).map { it.id }.toSet()
        assertEquals(setOf("a"), visible)
        assertFalse("b" in visible, "project-scoped tool must be hidden when unbound")
    }

    @Test fun specsFilterKeepsProjectToolsWhenBound() {
        val registry = ToolRegistry().apply {
            register(AlwaysTool("a"))
            register(ProjectTool("b"))
        }
        val ctx = ToolAvailabilityContext(currentProjectId = ProjectId("p"))
        val visible = registry.specs(ctx).map { it.id }.toSet()
        assertTrue("a" in visible)
        assertTrue("b" in visible)
    }

    @Test fun defaultApplicabilityIsAlways() {
        // A tool that doesn't override `applicability` must stay always-visible —
        // filtering should never hide the 90 existing tools without explicit opt-in.
        val t = AlwaysTool("default")
        assertTrue(t.applicability is ToolApplicability.Always)
        assertTrue(
            t.applicability.isAvailable(ToolAvailabilityContext(currentProjectId = null)),
        )
    }
}
