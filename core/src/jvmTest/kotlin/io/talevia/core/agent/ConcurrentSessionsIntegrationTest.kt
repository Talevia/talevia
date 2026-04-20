package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import io.talevia.core.TrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Combined-concurrency integration tests that the per-component tests do not
 * cover. Three independent concerns:
 *
 *  1. Multiple projects under parallel mutation are isolated — one project's
 *     critical section must not block another project's progress, and writes
 *     must not bleed across projects.
 *  2. Multiple sessions running agent.run() in parallel each persist their own
 *     parts cleanly; the SessionStore + EventBus do not interleave across
 *     sessions in a way that loses or misroutes parts.
 *  3. A second agent.run() on a session that already has an in-flight run is
 *     rejected — protects against the OpenCode `runLoop` reentrance footgun.
 *
 * Uses runBlocking + Dispatchers.Default so the coroutines genuinely run on
 * parallel threads (runTest's single-threaded scheduler would mask races).
 */
class ConcurrentSessionsIntegrationTest {

    @Test
    fun multipleProjectsParallelMutationsAreIsolated() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val store = SqlDelightProjectStore(db)

        val projectIds = (1..4).map { ProjectId("proj-$it") }
        for (pid in projectIds) store.upsert("p", Project(id = pid, timeline = Timeline()))

        val mutationsPerProject = 16
        withContext(Dispatchers.Default) {
            projectIds.flatMap { pid ->
                (1..mutationsPerProject).map { i ->
                    async {
                        store.mutate(pid) { current ->
                            // Tiny work inside the critical section to maximise
                            // chance of cross-project interleave if the lock is
                            // shared (it must not be).
                            delay(1)
                            current.copy(
                                timeline = current.timeline.copy(
                                    tracks = current.timeline.tracks +
                                        Track.Video(TrackId("${pid.value}-t-$i")),
                                ),
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        for (pid in projectIds) {
            val final = store.get(pid)!!
            assertEquals(
                mutationsPerProject,
                final.timeline.tracks.size,
                "project ${pid.value} lost updates",
            )
            // Every track ID belongs to *this* project — no cross-contamination.
            assertTrue(final.timeline.tracks.all { it.id.value.startsWith(pid.value) })
        }
    }

    @Test
    fun parallelAgentRunsOnDifferentSessionsAreIsolated() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val registry = ToolRegistry().apply { register(EchoTool()) }

        val sessions = (1..6).map { SessionId("conc-sess-$it") }
        val now = Clock.System.now()
        for (sid in sessions) {
            store.createSession(
                Session(
                    id = sid,
                    projectId = ProjectId("p"),
                    title = sid.value,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        // Each session gets its own agent (per-session inflight tracking lives
        // on the Agent instance) and its own provider so we can correlate the
        // tool result back to the session.
        val results = withContext(Dispatchers.Default) {
            sessions.map { sid ->
                async {
                    val agent = Agent(
                        provider = scriptedEchoProvider(promptForSession = sid.value),
                        registry = registry,
                        store = store,
                        permissions = AllowAllPermissionService(),
                        bus = bus,
                    )
                    val asst = agent.run(
                        RunInput(sid, "go-${sid.value}", ModelRef("fake", "test")),
                    )
                    sid to asst
                }
            }.awaitAll()
        }

        for ((sid, asst) in results) {
            assertEquals(FinishReason.END_TURN, asst.finish, "session ${sid.value} did not finish")
            val parts = store.listSessionParts(sid)
            val toolPart = parts.filterIsInstance<Part.Tool>().single()
            val state = toolPart.state
            assertTrue(state is ToolState.Completed, "session ${sid.value}: $state")
            // Every persisted Part should belong to a message in *this* session.
            // listSessionParts already filters by sessionId; if isolation broke
            // the cross-session parts would appear here.
            assertEquals(sid.value, (state as ToolState.Completed).outputForLlm)
        }
    }

    @Test
    fun secondConcurrentRunOnSameSessionIsRejected() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val sid = SessionId("conc-single")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = sid.value,
                createdAt = now,
                updatedAt = now,
            ),
        )

        // Provider blocks until we release the gate, so the first run is
        // genuinely in-flight when the second run starts.
        val gate = CompletableDeferred<Unit>()
        val provider = object : io.talevia.core.provider.LlmProvider {
            override val id: String = "fake"
            override suspend fun listModels() = emptyList<io.talevia.core.provider.ModelInfo>()
            override fun stream(request: io.talevia.core.provider.LlmRequest): Flow<LlmEvent> = flow {
                gate.await()
                emit(LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage()))
            }
        }
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )

        val firstRun = launch(Dispatchers.Default) {
            agent.run(RunInput(sid, "first", ModelRef("fake", "test")))
        }

        // Wait for Agent to register the inflight handle. There's no public
        // hook so we poll briefly — the registration happens before the first
        // suspension on `gate.await()`.
        repeat(50) {
            if (agent.isRunning(sid)) return@repeat
            delay(2)
        }
        assertTrue(agent.isRunning(sid), "first run never registered")

        val ex = assertFailsWith<IllegalStateException> {
            agent.run(RunInput(sid, "second", ModelRef("fake", "test")))
        }
        assertTrue(
            ex.message?.contains("already has an in-flight") == true,
            "expected reentrance message, got: ${ex.message}",
        )

        gate.complete(Unit)
        firstRun.join()
        // Once the first run finishes, a fresh run must be allowed.
        agent.run(RunInput(sid, "third", ModelRef("fake", "test")))
        Unit
    }

    private fun scriptedEchoProvider(promptForSession: String): io.talevia.core.provider.LlmProvider {
        // Two-turn script: turn 1 calls echo, turn 2 finishes.
        val turn1 = listOf(
            LlmEvent.ToolCallStart(PartId("p-$promptForSession"), CallId("c-$promptForSession"), "echo"),
            LlmEvent.ToolCallReady(
                PartId("p-$promptForSession"),
                CallId("c-$promptForSession"),
                "echo",
                buildJsonObject { put("text", promptForSession) },
            ),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 2, output = 1)),
        )
        val turn2 = listOf(
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 5, output = 1)),
        )
        return FakeProvider(listOf(turn1, turn2))
    }
}
