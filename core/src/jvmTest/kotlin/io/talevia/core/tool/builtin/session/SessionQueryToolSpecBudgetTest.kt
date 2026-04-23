package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.EchoTool
import io.talevia.core.tool.builtin.session.query.ToolSpecBudgetRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `select=tool_spec_budget` contract (VISION §5.4 + §3a-10):
 *
 * - Reports the right `toolCount`, with `estimatedTokens` monotone in
 *   registry size.
 * - Sorts `topByTokens` descending — the heaviest tool comes first.
 * - `sessionId` is rejected because this is a registry-wide snapshot.
 * - Missing registry produces zero totals and `registryResolved=false`.
 * - Top-N entries are capped at 5 so a 105-tool registry doesn't inflate
 *   the response.
 */
class SessionQueryToolSpecBudgetTest {

    @Test
    fun reportsCountTokensAndTopHeavyTools() = runTest {
        val (store, _) = newStore()
        val registry = ToolRegistry().apply {
            register(EchoTool())
            register(HeavyTool())
        }
        val tool = SessionQueryTool(sessions = store, toolRegistry = registry)

        val out = tool.execute(
            SessionQueryTool.Input(select = SessionQueryTool.SELECT_TOOL_SPEC_BUDGET),
            stubCtx(),
        )
        val row = decodeSingle(out.data.rows)

        assertEquals(2, row.toolCount)
        assertTrue(row.registryResolved)
        assertTrue(row.estimatedTokens > 0, "expected non-zero token estimate; got ${row.estimatedTokens}")
        assertTrue(row.specBytes >= row.estimatedTokens * 3, "specBytes should roughly be 4x tokens")

        // Heaviest-first ordering: HeavyTool has a vastly larger helpText, so it
        // should dominate the EchoTool entry.
        val heavyRank = row.topByTokens.indexOfFirst { it.toolId == HeavyTool.ID }
        val echoRank = row.topByTokens.indexOfFirst { it.toolId == "echo" }
        assertTrue(heavyRank in 0..1, "HeavyTool should be in topByTokens")
        assertTrue(echoRank in 0..1, "echo should be in topByTokens")
        assertTrue(
            heavyRank < echoRank,
            "HeavyTool should outrank echo by token weight (heavyRank=$heavyRank, echoRank=$echoRank)",
        )
    }

    @Test
    fun topByTokensIsCappedAtFive() = runTest {
        val (store, _) = newStore()
        val registry = ToolRegistry().apply {
            repeat(12) { register(NumberedTool(it)) }
        }
        val tool = SessionQueryTool(sessions = store, toolRegistry = registry)

        val out = tool.execute(
            SessionQueryTool.Input(select = SessionQueryTool.SELECT_TOOL_SPEC_BUDGET),
            stubCtx(),
        )
        val row = decodeSingle(out.data.rows)

        assertEquals(12, row.toolCount)
        assertEquals(5, row.topByTokens.size, "topByTokens must be capped to avoid response bloat")
    }

    @Test
    fun zeroToolsReportsZeroTotals() = runTest {
        val (store, _) = newStore()
        val emptyRegistry = ToolRegistry()
        val tool = SessionQueryTool(sessions = store, toolRegistry = emptyRegistry)

        val out = tool.execute(
            SessionQueryTool.Input(select = SessionQueryTool.SELECT_TOOL_SPEC_BUDGET),
            stubCtx(),
        )
        val row = decodeSingle(out.data.rows)

        assertEquals(0, row.toolCount)
        assertEquals(0, row.estimatedTokens)
        assertEquals(0, row.specBytes)
        assertTrue(row.registryResolved, "empty registry is still resolved — 0 tools is not the same as no registry")
        assertTrue(row.topByTokens.isEmpty())
    }

    @Test
    fun rigWithoutRegistryReportsUnresolved() = runTest {
        val (store, _) = newStore()
        val tool = SessionQueryTool(sessions = store) // no registry passed

        val out = tool.execute(
            SessionQueryTool.Input(select = SessionQueryTool.SELECT_TOOL_SPEC_BUDGET),
            stubCtx(),
        )
        val row = decodeSingle(out.data.rows)

        assertEquals(0, row.toolCount)
        assertEquals(0, row.estimatedTokens)
        assertFalse(row.registryResolved, "registryResolved must be false when no registry was wired")
    }

    @Test
    fun sessionIdIsRejectedAsRegistryWideSnapshot() = runTest {
        val (store, _) = newStore()
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val tool = SessionQueryTool(sessions = store, toolRegistry = registry)

        assertFailsWith<IllegalStateException> {
            tool.execute(
                SessionQueryTool.Input(
                    select = SessionQueryTool.SELECT_TOOL_SPEC_BUDGET,
                    sessionId = "some-session",
                ),
                stubCtx(),
            )
        }
    }

    // --- helpers --------------------------------------------------------

    private fun decodeSingle(rows: JsonArray): ToolSpecBudgetRow {
        val list = rows.decodeRowsAs(ToolSpecBudgetRow.serializer())
        assertEquals(1, list.size, "tool_spec_budget is single-row")
        return list.single()
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private fun stubCtx(): ToolContext = ToolContext(
        sessionId = io.talevia.core.SessionId("t"),
        messageId = io.talevia.core.MessageId("m"),
        callId = io.talevia.core.CallId("c"),
        askPermission = { io.talevia.core.permission.PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
        currentProjectId = null,
        publishEvent = { },
        spendCapCents = null,
    )

    /**
     * A tool whose helpText is deliberately long so it dominates the
     * top-N ordering.
     */
    private class HeavyTool : Tool<HeavyTool.Input, HeavyTool.Output> {
        @Serializable class Input
        @Serializable class Output
        override val id: String = ID
        override val helpText: String = "heavy ".repeat(200)
        override val inputSerializer: KSerializer<Input> = serializer()
        override val outputSerializer: KSerializer<Output> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("heavy.test")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("required", JsonArray(emptyList()))
            put("additionalProperties", false)
        }

        override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> =
            ToolResult("heavy", "heavy", Output())

        companion object { const val ID: String = "heavy_test" }
    }

    private class NumberedTool(private val n: Int) : Tool<NumberedTool.Input, NumberedTool.Output> {
        @Serializable class Input
        @Serializable class Output
        override val id: String = "numbered_$n"
        override val helpText: String = "numbered tool #$n — used in the top-N cap test"
        override val inputSerializer: KSerializer<Input> = serializer()
        override val outputSerializer: KSerializer<Output> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("numbered.test")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("n") {
                    put("type", "integer")
                    put("description", "slot")
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("n"))))
            put("additionalProperties", false)
        }

        override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> =
            ToolResult("n", "n", Output())
    }
}
