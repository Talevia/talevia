package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.anthropic.AnthropicProvider
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end integration: real [AnthropicProvider] driven by a [MockEngine] is
 * wired into the real [Agent] + [ToolRegistry] + [SqlDelightSessionStore].
 *
 * Existing AnthropicProviderStreamTest covers SSE → LlmEvent translation in
 * isolation; existing AgentLoopTest drives the agent with a hand-coded
 * FakeProvider. Neither catches a regression where the agent serialises tool
 * results into the next request body in a way Anthropic would reject.
 *
 * Asserts:
 *  - The agent loops exactly twice (tool_use → tool_result → end_turn).
 *  - The second HTTP request includes a `tool_result` block referencing the
 *    callId produced by the first turn — proves the round-trip from
 *    LlmEvent.ToolCallReady → ToolState.Completed → outbound request body
 *    is intact.
 *  - HTTP error responses propagate as FinishReason.ERROR with the provider
 *    error string surfaced on the assistant message.
 */
class AnthropicAgentLoopIntegrationTest {

    @Test
    fun toolCallRoundTripsThroughRealProviderAndAgent() = runTest {
        val capturedBodies = mutableListOf<String>()
        val turn1 = sse(
            """{"type":"message_start","message":{"usage":{"input_tokens":10}}}""" to "message_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_abc","name":"echo"}}""" to "content_block_start",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"text\":\"ping\"}"}}""" to "content_block_delta",
            """{"type":"content_block_stop","index":0}""" to "content_block_stop",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}""" to "message_delta",
            """{"type":"message_stop"}""" to "message_stop",
        )
        val turn2 = sse(
            """{"type":"message_start","message":{"usage":{"input_tokens":18}}}""" to "message_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""" to "content_block_start",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"got "}}""" to "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ping"}}""" to "content_block_delta",
            """{"type":"content_block_stop","index":0}""" to "content_block_stop",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""" to "message_delta",
            """{"type":"message_stop"}""" to "message_stop",
        )
        val responses = mutableListOf(turn1, turn2)

        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedBodies += request.bodyText()
                    val body = responses.removeFirst()
                    respond(
                        content = ByteReadChannel(body),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                }
            }
        }

        val (store, bus) = newStore()
        val sessionId = primeSession(store)
        val agent = Agent(
            provider = AnthropicProvider(client, apiKey = "test-key"),
            registry = ToolRegistry().apply { register(EchoTool()) },
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )

        val asst = agent.run(
            RunInput(sessionId, "say ping", ModelRef("anthropic", "claude-test")),
        )

        assertEquals(FinishReason.END_TURN, asst.finish)
        assertEquals(2, capturedBodies.size, "agent should issue 2 HTTP requests for tool_use → tool_result loop")

        val toolPart = store.listSessionParts(sessionId).filterIsInstance<Part.Tool>().single()
        val state = toolPart.state
        assertTrue(state is ToolState.Completed, "expected Completed, got $state")
        assertEquals("ping", (state as ToolState.Completed).outputForLlm)

        // Assistant text deltas are streamed only via the bus (Anthropic provider does
        // not emit TextEnd, which is what the agent persists). We don't assert on
        // the streamed text here because StandardTestDispatcher subscription timing
        // is fragile — see AnthropicProviderStreamTest for direct delta coverage.

        // Round-trip proof: the second request body must serialise the tool
        // result back to Anthropic so the model can reason about it.
        val replay = capturedBodies[1]
        assertTrue(replay.contains("\"tool_result\""), "second request should include tool_result")
        assertTrue(replay.contains("\"toolu_abc\""), "tool_result must reference the original callId")
        assertTrue(replay.contains("ping"), "tool_result content should include the echoed string")
    }

    @Test
    fun providerHttpErrorPersistsAsFailedAssistantTurn() = runTest {
        // Anthropic 4xx returns a JSON envelope; provider surfaces it as LlmEvent.Error
        // and the agent must finalise the assistant message with finish=ERROR + error
        // text — never leave it pending.
        val errBody = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(errBody),
                        status = HttpStatusCode.fromValue(529),
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val (store, bus) = newStore()
        val sessionId = primeSession(store)
        val agent = Agent(
            provider = AnthropicProvider(client, apiKey = "test-key"),
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )

        val asst = agent.run(RunInput(sessionId, "anything", ModelRef("anthropic", "claude-test")))

        assertEquals(FinishReason.ERROR, asst.finish, "HTTP error must propagate as ERROR finish")
        assertTrue(
            asst.error?.contains("HTTP 529") == true || asst.error?.contains("overloaded") == true,
            "assistant.error should reflect the provider failure, got: ${asst.error}",
        )
    }

    private fun sse(vararg events: Pair<String, String>): String = buildString {
        for ((data, name) in events) {
            append("event: ").append(name).append('\n')
            append("data: ").append(data).append("\n\n")
        }
    }

    private suspend fun HttpRequestData.bodyText(): String = when (val b = body) {
        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        else -> error("unexpected outgoing content shape: ${b::class.simpleName}")
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("anthropic-int-${(0..1_000_000).random()}")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj"),
                title = "test",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }
}
