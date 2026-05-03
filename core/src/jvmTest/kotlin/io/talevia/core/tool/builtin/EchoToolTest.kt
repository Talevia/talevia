package io.talevia.core.tool.builtin

import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [EchoTool] —
 * `core/tool/builtin/EchoTool.kt`. The trivial "input
 * → output unchanged" tool used as a smoke fixture in
 * 10+ agent-loop tests. Cycle 175 audit: 57 LOC, 0 direct
 * test refs (used as fixture but never tested for its own
 * contracts — id, identity-mapping, schema shape,
 * ALLOW_RULE companion).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Identity-mapping: `output.echoed == input.text` and
 *    `outputForLlm == input.text` verbatim, no
 *    transformation.** Drift to "trim", "lowercase", or
 *    "prefix" would invalidate every agent-loop test that
 *    asserts the LLM receives the echoed text back. The
 *    title is literally `"echo"`.
 *
 * 2. **`id == "echo"` AND `permission == "echo"` (via
 *    `PermissionSpec.fixed`).** The id is the LLM-visible
 *    tool name; the permission is the rule key for
 *    `DefaultPermissionRuleset`. Drift in either would
 *    silently misdirect tool dispatch and / or break
 *    permission rule lookup — every smoke test that
 *    grants "echo" → ALLOW would suddenly require
 *    explicit ASK confirmation.
 *
 * 3. **`ALLOW_RULE` companion has `(permission="echo",
 *    pattern="*", action=ALLOW)`.** This is the explicit
 *    rule tests grant to bypass permission gating. Drift
 *    to ASK / DENY / different pattern would break every
 *    agent-loop test that registers it.
 */
class EchoToolTest {

    private val tool = EchoTool()

    private fun context(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { /* no-op */ },
        messages = emptyList(),
    )

    // ── Identity-mapping (the marquee invariant) ──────────────

    @Test fun executeReturnsInputTextVerbatimInBothFields() = runTest {
        // Marquee identity pin: both `outputForLlm` AND
        // `data.echoed` equal the input text byte-for-byte.
        // Drift to trim / lowercase / prefix would break
        // every agent-loop test that asserts the tool
        // result the LLM sees matches what was sent in.
        val input = EchoTool.Input(text = "Hello, World!")
        val result = tool.execute(input, context())
        assertEquals("Hello, World!", result.outputForLlm, "outputForLlm matches input verbatim")
        assertEquals("Hello, World!", result.data.echoed, "data.echoed matches input verbatim")
    }

    @Test fun executePreservesUnicodeAndWhitespace() = runTest {
        // Pin: no normalisation. CJK characters,
        // surrounding whitespace, embedded newlines all
        // survive. Drift to `text.trim()` or
        // `text.normalize()` would silently change every
        // smoke test that uses non-ASCII inputs.
        val input = EchoTool.Input(text = "  美 echo\n\t test  ")
        val result = tool.execute(input, context())
        assertEquals("  美 echo\n\t test  ", result.outputForLlm)
        assertEquals("  美 echo\n\t test  ", result.data.echoed)
    }

    @Test fun executeWithEmptyStringReturnsEmptyString() = runTest {
        val result = tool.execute(EchoTool.Input(text = ""), context())
        assertEquals("", result.outputForLlm)
        assertEquals("", result.data.echoed)
    }

    @Test fun executeTitleIsLiterallyEcho() = runTest {
        // Pin: title doesn't include the input text. Drift
        // to "echo: <text>" would change UI rendering of
        // tool calls.
        val result = tool.execute(EchoTool.Input(text = "anything"), context())
        assertEquals("echo", result.title)
    }

    // ── id + permission ──────────────────────────────────────

    @Test fun toolIdIsLiterallyEcho() {
        // Pin: the LLM-visible tool name. Drift would
        // silently misdirect tool dispatch.
        assertEquals("echo", tool.id)
    }

    @Test fun permissionPermissionKeyIsEcho() {
        // Pin: the permission key. Drift would break
        // DefaultPermissionRuleset lookup (which has an
        // entry for "echo" → ALLOW).
        assertEquals("echo", tool.permission.permission)
    }

    @Test fun permissionIsFixedRegardlessOfInput() {
        // Pin: PermissionSpec.fixed("echo") means
        // `permissionFrom(input)` returns "echo" no matter
        // what input string is passed. Drift to per-input
        // dispatch would mean permission rules don't
        // match consistently.
        assertEquals("echo", tool.permission.permissionFrom("anything"))
        assertEquals(
            "echo",
            tool.permission.permissionFrom("""{"text":"anything"}"""),
        )
        assertEquals("echo", tool.permission.permissionFrom(""))
    }

    @Test fun helpTextIsPresentNonBlank() {
        // Pin: helpText is non-blank (the LLM reads it as
        // the tool's "description" field). Drift to empty
        // would degrade tool discoverability.
        assertTrue(tool.helpText.isNotBlank(), "helpText must be non-blank")
        assertTrue(
            "echo" in tool.helpText.lowercase() || "smoke" in tool.helpText.lowercase(),
            "helpText mentions purpose; got: ${tool.helpText}",
        )
    }

    // ── inputSchema shape ────────────────────────────────────

    @Test fun inputSchemaHasTextRequiredField() {
        // Pin: the JSON Schema declares "text" as a
        // required string field. Drift would break tool-
        // dispatch validation OR the LLM's parameter
        // generation.
        val schema = tool.inputSchema
        assertEquals(
            JsonPrimitive("object"),
            schema["type"],
            "schema type is object",
        )
        // properties.text exists and is "string".
        val properties = schema["properties"]?.jsonObject ?: error("schema missing properties")
        val textProp = properties["text"]?.jsonObject ?: error("schema missing properties.text")
        assertEquals(JsonPrimitive("string"), textProp["type"])
        // required = ["text"]
        val required = schema["required"]?.jsonArray ?: error("schema missing required")
        assertEquals(1, required.size, "exactly one required field")
        assertEquals("text", required[0].jsonPrimitive.contentOrNull)
    }

    @Test fun inputSchemaForbidsAdditionalProperties() {
        // Pin: `additionalProperties = false` — drift to
        // true would let LLMs slip through extra fields
        // that the deserializer would reject anyway, but
        // the schema-level rejection surfaces it earlier.
        val schema = tool.inputSchema
        assertEquals(
            JsonPrimitive(false),
            schema["additionalProperties"],
            "schema must forbid additional properties",
        )
        assertEquals(
            false,
            schema["additionalProperties"]?.jsonPrimitive?.boolean,
        )
    }

    // ── Serialization round-trip ─────────────────────────────

    @Test fun inputSerializerRoundTrips() {
        // Pin: the @Serializable Input shape decodes from
        // a minimal JSON. Drift in the serializer (e.g.
        // accidental `private val text`) would break the
        // tool-dispatch JSON-decode path.
        val json = JsonConfig.default
        val original = EchoTool.Input(text = "hello")
        val encoded = json.encodeToString(tool.inputSerializer, original)
        assertTrue("\"text\":\"hello\"" in encoded, "serializes 'text'; got: $encoded")
        val decoded = json.decodeFromString(tool.inputSerializer, encoded)
        assertEquals(original, decoded, "round-trip preserves Input")
    }

    @Test fun outputSerializerRoundTrips() {
        val json = JsonConfig.default
        val original = EchoTool.Output(echoed = "world")
        val encoded = json.encodeToString(tool.outputSerializer, original)
        assertTrue("\"echoed\":\"world\"" in encoded)
        val decoded = json.decodeFromString(tool.outputSerializer, encoded)
        assertEquals(original, decoded)
    }

    // ── ALLOW_RULE companion ──────────────────────────────────

    @Test fun allowRuleHasExpectedShape() {
        // Marquee companion-rule pin. Tests register this
        // as the "grant echo permission to skip ASK
        // prompts." Drift to ASK / DENY / different
        // pattern would silently break the smoke-test
        // grant path.
        val rule = EchoTool.ALLOW_RULE
        assertEquals("echo", rule.permission)
        assertEquals("*", rule.pattern)
        assertEquals(PermissionAction.ALLOW, rule.action)
    }

    // ── Schema is structurally JsonObject (not stringified) ──

    @Test fun inputSchemaIsJsonObject() {
        // Pin: the schema is a JSON object structure, not
        // a stringified blob. The LLM tool-spec sender
        // expects a parsed shape so it can introspect /
        // re-emit. Drift to `String` or `JsonElement` of
        // the wrong kind would break ListToolsTool's
        // schema introspection.
        val schema: JsonObject = tool.inputSchema
        assertTrue(schema.isNotEmpty(), "schema is non-empty JsonObject")
    }

    @Test fun inputSchemaTextDescriptionIsPresent() {
        // Pin: helpful "description" string for "text"
        // property. The LLM uses this to generate
        // contextually-appropriate values. Drift to
        // missing description would degrade smoke-test
        // call-site quality.
        val text = tool.inputSchema["properties"]?.jsonObject?.get("text")?.jsonObject
            ?: error("missing properties.text")
        val desc = text["description"]?.jsonPrimitive?.contentOrNull
        assertTrue(
            !desc.isNullOrBlank(),
            "text property has non-blank description; got: $desc",
        )
    }
}
