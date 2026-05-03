package io.talevia.core.tool

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [ToolRegistry] semantics. Coverage that was previously
 * only exercised transitively through `ToolApplicabilityTest` (filter
 * paths) and individual tool tests (dispatch path). Pins the basic
 * invariants: register/unregister/get/specs/dispatch/encodeOutput.
 */
class ToolRegistryTest {

    @Serializable data class Echo(val v: Int = 0)

    /** Minimal tool — echoes input.v through to output.v. */
    private class EchoTool(override val id: String) : Tool<Echo, Echo> {
        override val helpText: String = "echo $id"
        override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }
        override val inputSerializer: KSerializer<Echo> = serializer()
        override val outputSerializer: KSerializer<Echo> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("test.echo")
        override suspend fun execute(input: Echo, ctx: ToolContext): ToolResult<Echo> =
            ToolResult(title = id, outputForLlm = "v=${input.v}", data = Echo(input.v))
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun registerAddsToolByIdAndGetReturnsIt() {
        val r = ToolRegistry()
        val tool = EchoTool("a")
        r.register(tool)

        val registered = r["a"]
        assertTrue(registered != null, "register-then-get should succeed by id")
        assertEquals("a", registered.id)
    }

    @Test fun registerOverwritesPriorEntryForSameId() {
        // Cycle 73: register(tool) writes tools[tool.id] = ...; a second
        // register with the same id replaces (does NOT throw / accumulate).
        // Pin the contract — DefaultBuiltinRegistrations + iOS AppContainer
        // depend on this when re-running registration in test rigs.
        val r = ToolRegistry()
        val first = EchoTool("a")
        val second = EchoTool("a") // same id, fresh instance
        r.register(first)
        r.register(second)

        // After the second register, get("a") returns a registered tool —
        // we don't expose the underlying instance directly, but `all()`
        // should contain exactly one entry for id "a" (no duplication).
        assertEquals(1, r.all().size, "register with duplicate id must not accumulate")
        assertEquals("a", r.all()[0].id)
    }

    @Test fun unregisterRemovesIdAndGetReturnsNull() {
        // Cycle 63's phase-2 unregister relies on this: removing 4 tool ids
        // from the registry hides them from the LLM-facing spec bundle.
        val r = ToolRegistry()
        r.register(EchoTool("a"))
        r.register(EchoTool("b"))
        assertEquals(2, r.all().size)

        r.unregister("a")
        assertNull(r["a"], "unregister(id) makes get(id) return null")
        assertEquals(1, r.all().size)
        assertEquals("b", r.all()[0].id, "remaining tool unaffected")
    }

    @Test fun unregisterNonExistentIdIsNoOp() {
        val r = ToolRegistry()
        r.register(EchoTool("a"))
        // Removing an id that was never registered must not throw.
        r.unregister("nope")
        assertEquals(1, r.all().size, "unregister of unknown id is a no-op")
        assertEquals("a", r.all()[0].id)
    }

    @Test fun getReturnsNullForUnknownId() {
        val r = ToolRegistry()
        r.register(EchoTool("a"))
        assertNull(r["nope"], "get(unknownId) returns null, not throw")
    }

    @Test fun unfilteredSpecsReturnsEveryRegisteredTool() {
        val r = ToolRegistry()
        r.register(EchoTool("a"))
        r.register(EchoTool("b"))
        r.register(EchoTool("c"))
        val specs = r.specs()
        assertEquals(setOf("a", "b", "c"), specs.map { it.id }.toSet())
    }

    @Test fun ctxFilteredSpecsExcludesDisabledToolIdsEvenWhenApplicable() {
        // The filter pipeline is: applicability.isAvailable(ctx) AND
        // id !in ctx.disabledToolIds. Both must pass. ToolApplicabilityTest
        // covers the applicability path; this case pins the AND with
        // disabledToolIds for an Always-applicable tool.
        val r = ToolRegistry()
        r.register(EchoTool("a"))
        r.register(EchoTool("b"))
        r.register(EchoTool("c"))

        val ctx = ToolAvailabilityContext(
            currentProjectId = null,
            disabledToolIds = setOf("b"),
        )
        val visible = r.specs(ctx).map { it.id }.toSet()
        assertEquals(setOf("a", "c"), visible, "disabledToolIds drops 'b' even though it's Always applicable")
    }

    @Test fun ctxFilteredSpecsHidesDisabledToolEvenIfRegisteredAfterDisableSet() {
        // Disable-set semantics are applied at filter time, not at
        // register time. Order of register vs ctx construction must not
        // matter — the ctx is just a query.
        val ctx = ToolAvailabilityContext(
            currentProjectId = null,
            disabledToolIds = setOf("late-arrival"),
        )
        val r = ToolRegistry()
        r.register(EchoTool("late-arrival"))
        assertEquals(emptyList(), r.specs(ctx).map { it.id })
    }

    @Test fun dispatchDeserializesInputAndExecutesUnderType() = runTest {
        // The cast inside RegisteredTool.dispatch is the one
        // type-erasure boundary. Pin: rawInput JSON → typed Echo →
        // execute → ToolResult<Echo>.
        val r = ToolRegistry()
        r.register(EchoTool("echo"))
        val rt = r["echo"]!!
        val raw: kotlinx.serialization.json.JsonElement = buildJsonObject { put("v", 42) }
        val result = rt.dispatch(raw, ctx())
        assertEquals(Echo(42), result.data)
        assertEquals("v=42", result.outputForLlm)
    }

    @Test fun encodeOutputRoundTripsTypedDataToJson() {
        // Pin: RegisteredTool.encodeOutput round-trips data through the
        // tool's outputSerializer. Lets the agent / persistence
        // surface re-encode a Result without holding the tool's class.
        val r = ToolRegistry()
        r.register(EchoTool("e"))
        val rt = r["e"]!!
        val sample = ToolResult(title = "t", outputForLlm = "ok", data = Echo(7))
        val encoded = rt.encodeOutput(sample) as JsonObject
        assertEquals(JsonPrimitive(7), encoded["v"], "encodeOutput must preserve the data field")
    }
}
