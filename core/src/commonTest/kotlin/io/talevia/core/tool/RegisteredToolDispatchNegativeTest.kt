package io.talevia.core.tool

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.builtin.EchoTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Guards [RegisteredTool.dispatch]'s cast boundary on the negative paths the
 * normal happy-path tests do not exercise. The agent loop wraps these failures
 * via `runCatching` and turns them into [io.talevia.core.session.ToolState.Failed],
 * but that wrapping must NOT mask serialisation errors with a misleading message.
 */
class RegisteredToolDispatchNegativeTest {

    private val ctx = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { /* drop */ },
        messages = emptyList(),
    )

    private fun registry() = ToolRegistry().apply { register(EchoTool()) }

    @Test
    fun missingRequiredFieldFails() = runTest {
        val tool = registry()["echo"]!!
        val ex = assertFailsWith<SerializationException> {
            tool.dispatch(buildJsonObject { /* no "text" field */ }, ctx)
        }
        // Message should mention the missing property by name so users can fix the call.
        check(ex.message?.contains("text") == true) {
            "expected SerializationException to mention missing 'text' field, got: ${ex.message}"
        }
    }

    @Test
    fun wrongTypeForFieldFails() = runTest {
        val tool = registry()["echo"]!!
        assertFailsWith<SerializationException> {
            tool.dispatch(buildJsonObject { put("text", 42) }, ctx)
        }
    }

    @Test
    fun jsonNullInputFails() = runTest {
        val tool = registry()["echo"]!!
        assertFailsWith<SerializationException> {
            tool.dispatch(JsonNull, ctx)
        }
    }

    @Test
    fun jsonArrayInsteadOfObjectFails() = runTest {
        val tool = registry()["echo"]!!
        assertFailsWith<SerializationException> {
            tool.dispatch(JsonArray(listOf(JsonPrimitive("ping"))), ctx)
        }
    }

    @Test
    fun jsonPrimitiveInsteadOfObjectFails() = runTest {
        val tool = registry()["echo"]!!
        assertFailsWith<SerializationException> {
            tool.dispatch(JsonPrimitive("ping"), ctx)
        }
    }

    @Test
    fun unknownExtraFieldsAreIgnored() = runTest {
        // JsonConfig.default has ignoreUnknownKeys = true; extras must NOT fail dispatch.
        val tool = registry()["echo"]!!
        val result = tool.dispatch(
            buildJsonObject {
                put("text", "ping")
                put("nope", true)
            },
            ctx,
        )
        assertEquals("ping", result.outputForLlm)
    }

    @Test
    fun encodeOutputRoundTripsTypedPayload() = runTest {
        // Locks the second cast in RegisteredTool — the output side. Regression guard:
        // if encodeOutput stops emitting JSON faithfully, this catches it.
        val tool = registry()["echo"]!!
        val result = tool.dispatch(buildJsonObject { put("text", "pong") }, ctx)
        val encoded = tool.encodeOutput(result)
        check(encoded.toString().contains("\"echoed\":\"pong\"")) {
            "expected encoded output to include echoed=\"pong\", got: $encoded"
        }
    }
}
