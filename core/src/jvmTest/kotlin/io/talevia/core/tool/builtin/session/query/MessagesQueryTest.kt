package io.talevia.core.tool.builtin.session.query

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [runMessagesQuery] —
 * `core/tool/builtin/session/query/MessagesQuery.kt`. The
 * SessionQueryTool's `select=messages` handler. Cycle 200
 * audit: 93 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`role` filter validation: normalises trim +
 *    lowercase, accepts only `"user"` / `"assistant"`,
 *    throws on unknown.** Drift to "case-sensitive
 *    exact match" would silently mis-handle "User" /
 *    "ASSISTANT" inputs the LLM might send.
 *
 * 2. **MessageRow shape diverges by role: User → agent,
 *    no parentId/tokens; Assistant → parentId + tokens +
 *    finish + error, no agent.** Drift to "always
 *    populate all fields" would inflate every row JSON
 *    + confuse role-disambiguating consumers.
 *
 * 3. **`finish` field encodes via `name.lowercase()` (NOT
 *    SerialName).** A FinishReason of `END_TURN` surfaces
 *    as `"end_turn"` (with underscore from Kotlin name
 *    convention), NOT `"end-turn"` (the SerialName form).
 *    Drift between the two would change consumer-side
 *    decode logic.
 */
class MessagesQueryTest {

    private val baseTime: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        sid: String,
        title: String = "session-$sid",
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = title,
                createdAt = baseTime,
                updatedAt = baseTime,
            ),
        )
        return sessionId
    }

    private suspend fun seedUser(
        store: SqlDelightSessionStore,
        sid: String,
        mid: String,
        agent: String = "test-agent",
        modelProviderId: String = "anthropic",
        modelId: String = "claude",
        createdAtEpochMs: Long = 100L,
    ) {
        store.appendMessage(
            Message.User(
                id = MessageId(mid),
                sessionId = SessionId(sid),
                createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
                agent = agent,
                model = ModelRef(modelProviderId, modelId),
            ),
        )
    }

    private suspend fun seedAssistant(
        store: SqlDelightSessionStore,
        sid: String,
        mid: String,
        parentId: String,
        modelProviderId: String = "anthropic",
        modelId: String = "claude",
        tokensInput: Long = 0,
        tokensOutput: Long = 0,
        finish: FinishReason? = null,
        error: String? = null,
        createdAtEpochMs: Long = 200L,
    ) {
        store.appendMessage(
            Message.Assistant(
                id = MessageId(mid),
                sessionId = SessionId(sid),
                createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
                parentId = MessageId(parentId),
                model = ModelRef(modelProviderId, modelId),
                tokens = TokenUsage(input = tokensInput, output = tokensOutput),
                finish = finish,
                error = error,
            ),
        )
    }

    private fun input(
        sessionId: String?,
        role: String? = null,
    ): SessionQueryTool.Input = SessionQueryTool.Input(
        select = SessionQueryTool.SELECT_MESSAGES,
        sessionId = sessionId,
        role = role,
    )

    private fun rowFields(
        rows: kotlinx.serialization.json.JsonArray,
        key: String,
    ): List<String> = rows.map { row ->
        row.toString().substringAfter("\"$key\":\"").substringBefore("\"")
    }

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingSessionIdThrows() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            runMessagesQuery(store, input(null), limit = 100, offset = 0)
        }
        assertTrue("requires sessionId" in (ex.message ?: ""))
    }

    // ── Role validation + normalisation ──────────────────────

    @Test fun unknownRoleThrowsWithValidRolesList() = runTest {
        val store = newStore()
        seedSession(store, "s1")

        val ex = assertFailsWith<IllegalStateException> {
            runMessagesQuery(
                store,
                input("s1", role = "system"),
                limit = 100,
                offset = 0,
            )
        }
        assertTrue("role must be one of" in (ex.message ?: ""))
        // Both valid roles cited.
        assertTrue("user" in (ex.message ?: ""))
        assertTrue("assistant" in (ex.message ?: ""))
        // Bad input echoed.
        assertTrue("'system'" in (ex.message ?: ""))
    }

    @Test fun roleNormalisationTrimAndLowercase() = runTest {
        // Pin: per `input.role?.trim()?.lowercase()?.takeIf
        // { it.isNotEmpty() }` — drift to "case-sensitive
        // exact match" would silently mis-handle "User" /
        // "ASSISTANT" inputs.
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "u1")

        val resultUpper = runMessagesQuery(
            store,
            input("s1", role = "USER"),
            limit = 100, offset = 0,
        )
        assertEquals(1, resultUpper.data.total, "uppercase role normalises")

        val resultMixed = runMessagesQuery(
            store,
            input("s1", role = "  User  "),
            limit = 100, offset = 0,
        )
        assertEquals(1, resultMixed.data.total, "mixed-case + whitespace normalises")
    }

    @Test fun blankRoleIsTreatedAsNoFilter() = runTest {
        // Pin: per `?.takeIf { it.isNotEmpty() }`, blank
        // role resolves to null (no filter). Drift to
        // "literal blank → no match" would fail every
        // "any role" query when agent passes "".
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "u1")
        seedAssistant(store, "s1", mid = "a1", parentId = "u1")

        val resultEmpty = runMessagesQuery(
            store,
            input("s1", role = ""),
            limit = 100, offset = 0,
        )
        assertEquals(2, resultEmpty.data.total)

        val resultBlank = runMessagesQuery(
            store,
            input("s1", role = "   "),
            limit = 100, offset = 0,
        )
        assertEquals(2, resultBlank.data.total)
    }

    @Test fun userRoleFilterReturnsOnlyUserMessages() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "u1", createdAtEpochMs = 100L)
        seedAssistant(store, "s1", mid = "a1", parentId = "u1", createdAtEpochMs = 200L)
        seedUser(store, "s1", mid = "u2", createdAtEpochMs = 300L)

        val result = runMessagesQuery(
            store,
            input("s1", role = "user"),
            limit = 100, offset = 0,
        )
        assertEquals(2, result.data.total)
        // Most-recent first: u2, u1.
        assertContentEquals(
            listOf("u2", "u1"),
            rowFields(result.data.rows, "id"),
        )
    }

    @Test fun assistantRoleFilterReturnsOnlyAssistantMessages() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "u1")
        seedAssistant(store, "s1", mid = "a1", parentId = "u1", createdAtEpochMs = 200L)
        seedAssistant(store, "s1", mid = "a2", parentId = "u1", createdAtEpochMs = 300L)

        val result = runMessagesQuery(
            store,
            input("s1", role = "assistant"),
            limit = 100, offset = 0,
        )
        assertEquals(2, result.data.total)
        assertContentEquals(
            listOf("a2", "a1"),
            rowFields(result.data.rows, "id"),
        )
    }

    // ── Sort: createdAt DESC ─────────────────────────────────

    @Test fun sortIsCreatedAtDescendingMostRecentFirst() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "old", createdAtEpochMs = 100L)
        seedUser(store, "s1", mid = "newest", createdAtEpochMs = 300L)
        seedUser(store, "s1", mid = "mid", createdAtEpochMs = 200L)

        val result = runMessagesQuery(store, input("s1"), limit = 100, offset = 0)
        assertContentEquals(
            listOf("newest", "mid", "old"),
            rowFields(result.data.rows, "id"),
        )
    }

    // ── MessageRow shape per role ────────────────────────────

    @Test fun userRowExposesAgentNotParentIdOrTokens() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedUser(
            store, "s1",
            mid = "u1",
            agent = "test-agent",
            modelProviderId = "anthropic",
            modelId = "claude-3",
        )

        val result = runMessagesQuery(store, input("s1"), limit = 100, offset = 0)
        val rowJson = result.data.rows[0].toString()
        // User-specific.
        assertTrue("\"role\":\"user\"" in rowJson, "role; got: $rowJson")
        assertTrue("\"agent\":\"test-agent\"" in rowJson, "agent populated")
        assertTrue(
            "\"modelProviderId\":\"anthropic\"" in rowJson,
            "modelProviderId; got: $rowJson",
        )
        assertTrue("\"modelId\":\"claude-3\"" in rowJson)
        // Assistant-only fields NOT populated (null/absent).
        assertTrue(
            "\"parentId\":\"" !in rowJson,
            "User row has NO non-null parentId; got: $rowJson",
        )
        assertTrue(
            "\"tokensInput\":" !in rowJson,
            "User row has NO non-null tokensInput; got: $rowJson",
        )
        assertTrue(
            "\"tokensOutput\":" !in rowJson,
            "User row has NO non-null tokensOutput; got: $rowJson",
        )
        assertTrue("\"finish\":" !in rowJson)
    }

    @Test fun assistantRowExposesParentIdTokensFinishNotAgent() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "u1")
        seedAssistant(
            store, "s1",
            mid = "a1",
            parentId = "u1",
            tokensInput = 100,
            tokensOutput = 50,
            finish = FinishReason.STOP,
        )

        val result = runMessagesQuery(
            store,
            input("s1", role = "assistant"),
            limit = 100, offset = 0,
        )
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"role\":\"assistant\"" in rowJson)
        assertTrue("\"parentId\":\"u1\"" in rowJson, "parentId; got: $rowJson")
        assertTrue("\"tokensInput\":100" in rowJson)
        assertTrue("\"tokensOutput\":50" in rowJson)
        assertTrue("\"finish\":\"stop\"" in rowJson, "finish lowercased; got: $rowJson")
        // Assistant has NO agent field populated.
        assertTrue(
            "\"agent\":\"" !in rowJson,
            "Assistant row has NO non-null agent; got: $rowJson",
        )
    }

    // ── finish field encoding (name.lowercase, NOT SerialName) ──

    @Test fun finishEncodesWithNameLowercaseUnderscoresPreserved() = runTest {
        // Marquee finish-encoding pin: per impl
        // `m.finish?.name?.lowercase()` — produces
        // "end_turn" (NOT "end-turn", the @SerialName form
        // from FinishReason.kt). Drift between name vs
        // SerialName would change consumer-side decode.
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "u1")
        seedAssistant(store, "s1", mid = "a-end", parentId = "u1", finish = FinishReason.END_TURN, createdAtEpochMs = 200L)
        seedAssistant(store, "s1", mid = "a-max", parentId = "u1", finish = FinishReason.MAX_TOKENS, createdAtEpochMs = 250L)
        seedAssistant(store, "s1", mid = "a-tool", parentId = "u1", finish = FinishReason.TOOL_CALLS, createdAtEpochMs = 300L)

        val result = runMessagesQuery(
            store,
            input("s1", role = "assistant"),
            limit = 100, offset = 0,
        )
        val rowsJson = result.data.rows.toString()
        assertTrue(
            "\"finish\":\"end_turn\"" in rowsJson,
            "END_TURN → 'end_turn' (underscore from name); NOT 'end-turn' (SerialName); got: $rowsJson",
        )
        assertTrue(
            "\"finish\":\"max_tokens\"" in rowsJson,
            "MAX_TOKENS → 'max_tokens' (underscore); NOT 'max-tokens' (SerialName)",
        )
        assertTrue(
            "\"finish\":\"tool_calls\"" in rowsJson,
            "TOOL_CALLS → 'tool_calls'; NOT 'tool-calls'",
        )
    }

    @Test fun assistantFinishNullSurfacesAsAbsent() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "u1")
        seedAssistant(store, "s1", mid = "a1", parentId = "u1", finish = null)

        val result = runMessagesQuery(
            store,
            input("s1", role = "assistant"),
            limit = 100, offset = 0,
        )
        val rowJson = result.data.rows[0].toString()
        assertTrue(
            "\"finish\":" !in rowJson,
            "null finish absent (encodeDefaults=false); got: $rowJson",
        )
    }

    @Test fun assistantErrorFieldSurfacesWhenSet() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "u1")
        seedAssistant(
            store, "s1",
            mid = "a1",
            parentId = "u1",
            finish = FinishReason.ERROR,
            error = "rate limit",
        )

        val result = runMessagesQuery(
            store,
            input("s1", role = "assistant"),
            limit = 100, offset = 0,
        )
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"error\":\"rate limit\"" in rowJson)
        assertTrue("\"finish\":\"error\"" in rowJson, "ERROR variant lowercased")
    }

    // ── Pagination ───────────────────────────────────────────

    @Test fun paginationLimitTakesFirstNRowsPostSort() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        for (i in 1..5) {
            seedUser(store, "s1", mid = "u-$i", createdAtEpochMs = i * 100L)
        }

        val result = runMessagesQuery(store, input("s1"), limit = 2, offset = 0)
        assertEquals(5, result.data.total)
        assertEquals(2, result.data.returned)
        assertContentEquals(listOf("u-5", "u-4"), rowFields(result.data.rows, "id"))
    }

    // ── outputForLlm format ─────────────────────────────────

    @Test fun emptyResultBodyOmitsRoleScopeWhenNoFilter() = runTest {
        val store = newStore()
        seedSession(store, "s1", title = "Some Title")

        val result = runMessagesQuery(store, input("s1"), limit = 100, offset = 0)
        assertTrue("has no messages." in result.outputForLlm, "no role suffix")
        assertTrue("Some Title" in result.outputForLlm)
    }

    @Test fun emptyResultBodyAppendsRoleScopeWhenFiltering() = runTest {
        val store = newStore()
        seedSession(store, "s1", title = "T")

        val result = runMessagesQuery(
            store,
            input("s1", role = "user"),
            limit = 100, offset = 0,
        )
        assertTrue("has no messages role=user" in result.outputForLlm)
    }

    @Test fun nonEmptyBodyCitesRoleAndIdPerRow() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedUser(store, "s1", mid = "u1", createdAtEpochMs = 100L)
        seedAssistant(store, "s1", mid = "a1", parentId = "u1", createdAtEpochMs = 200L)

        val result = runMessagesQuery(store, input("s1"), limit = 100, offset = 0)
        assertTrue("2 of 2 message(s)" in result.outputForLlm)
        assertTrue("most recent first" in result.outputForLlm)
        // "{role}/{id}" format.
        assertTrue("assistant/a1" in result.outputForLlm)
        assertTrue("user/u1" in result.outputForLlm)
    }

    @Test fun bodyTruncatesAfterFiveRowsWithEllipsis() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        for (i in 1..7) {
            seedUser(store, "s1", mid = "u-$i", createdAtEpochMs = i * 100L)
        }

        val result = runMessagesQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(7, result.data.total)
        assertTrue(result.outputForLlm.endsWith("; …"))
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsMessages() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        val result = runMessagesQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(SessionQueryTool.SELECT_MESSAGES, result.data.select)
    }
}
