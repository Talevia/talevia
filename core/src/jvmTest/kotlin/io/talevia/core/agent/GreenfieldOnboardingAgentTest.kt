package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.ProjectSummary
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end cover: `AgentTurnExecutor` reads the bound project from the
 * ProjectStore on every turn and flips the `projectIsGreenfield` gate based
 * on `timeline.tracks.isEmpty() && source.nodes.isEmpty()`. The onboarding
 * lane appears only while the gate is on, so token cost is paid just while
 * the signal is load-bearing.
 */
class GreenfieldOnboardingAgentTest {

    @Test
    fun greenfieldProjectInjectsOnboardingLaneOnFirstTurn() = runTest {
        val (store, bus) = newStore()
        val pid = ProjectId("p-empty")
        val sid = primeSession(store, currentProjectId = pid)
        val projects = SingleProjectStore(Project(id = pid, timeline = Timeline()))

        val sp = runOneTurnAndCaptureSystemPrompt(store, bus, projects, sid)
        assertNotNull(sp)
        assertTrue(
            "# Greenfield onboarding" in sp!!,
            "greenfield project must inject the onboarding lane, got:\n$sp",
        )
        assertTrue(sp.startsWith("Current project: p-empty"), "banner still leads")
    }

    @Test
    fun addingTimelineTrackDropsGreenfieldLaneOnNextTurn() = runTest {
        val (store, bus) = newStore()
        val pid = ProjectId("p-tracked")
        val sid = primeSession(store, currentProjectId = pid)
        val projects = SingleProjectStore(
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(Track.Video(id = TrackId("v0")))),
                source = Source.EMPTY,
            ),
        )

        val sp = runOneTurnAndCaptureSystemPrompt(store, bus, projects, sid)
        assertNotNull(sp)
        assertFalse(
            "# Greenfield onboarding" in sp!!,
            "project with any track must NOT carry the onboarding lane, got:\n$sp",
        )
    }

    @Test
    fun addingSourceNodeAlsoDropsGreenfieldLane() = runTest {
        val (store, bus) = newStore()
        val pid = ProjectId("p-sourced")
        val sid = primeSession(store, currentProjectId = pid)
        val source = Source.EMPTY.copy(
            nodes = listOf(
                SourceNode.create(
                    id = SourceNodeId("style-warm"),
                    kind = "core.consistency.style_bible",
                ),
            ),
        )
        val projects = SingleProjectStore(
            Project(id = pid, timeline = Timeline(), source = source),
        )

        val sp = runOneTurnAndCaptureSystemPrompt(store, bus, projects, sid)
        assertNotNull(sp)
        assertFalse(
            "# Greenfield onboarding" in sp!!,
            "project with any source node must NOT carry the onboarding lane",
        )
    }

    @Test
    fun unboundSessionNeverShowsOnboardingLane() = runTest {
        val (store, bus) = newStore()
        val sid = primeSession(store, currentProjectId = null)
        // Projects store is unused because the session has no binding to look up.
        val projects = SingleProjectStore(Project(id = ProjectId("unused"), timeline = Timeline()))

        val sp = runOneTurnAndCaptureSystemPrompt(store, bus, projects, sid)
        assertNotNull(sp)
        assertFalse(
            "# Greenfield onboarding" in sp!!,
            "unbound session must never show the onboarding lane",
        )
        assertTrue(sp!!.startsWith("Current project: <none>"))
    }

    private suspend fun runOneTurnAndCaptureSystemPrompt(
        store: SqlDelightSessionStore,
        bus: EventBus,
        projects: ProjectStore,
        sid: SessionId,
    ): String? {
        val turn = listOf(
            LlmEvent.TextStart(PartId("t")),
            LlmEvent.TextEnd(PartId("t"), "ok"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 1, output = 1)),
        )
        val provider = FakeProvider(listOf(turn))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            systemPrompt = "BASE_PROMPT_BODY",
            projects = projects,
        )
        agent.run(RunInput(sid, "hi", ModelRef("fake", "test")))
        return provider.requests.single().systemPrompt
    }

    /** Minimal single-project ProjectStore — only `get(id)` is exercised. */
    private class SingleProjectStore(private val project: Project) : ProjectStore {
        override suspend fun get(id: io.talevia.core.ProjectId): Project? =
            if (id == project.id) project else null
        override suspend fun upsert(title: String, project: Project) = error("not used")
        override suspend fun list(): List<Project> = listOf(project)
        override suspend fun delete(id: io.talevia.core.ProjectId, deleteFiles: Boolean) = error("not used")
        override suspend fun setTitle(id: io.talevia.core.ProjectId, title: String) = error("not used")
        override suspend fun summary(id: io.talevia.core.ProjectId): ProjectSummary? = null
        override suspend fun listSummaries(): List<ProjectSummary> = emptyList()
        override suspend fun mutate(id: io.talevia.core.ProjectId, block: suspend (Project) -> Project): Project =
            error("not used")
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(
        store: SqlDelightSessionStore,
        currentProjectId: ProjectId?,
    ): SessionId {
        val sid = SessionId("onb-session")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj-origin"),
                title = "test",
                createdAt = now,
                updatedAt = now,
                currentProjectId = currentProjectId,
            ),
        )
        return sid
    }
}
