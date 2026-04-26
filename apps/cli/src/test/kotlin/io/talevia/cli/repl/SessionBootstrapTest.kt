package io.talevia.cli.repl

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.cli.resolveBootstrapMode
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionBootstrapTest {

    private val projectId = ProjectId("p")
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun freshSessions(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun SqlDelightSessionStore.seed(
        id: String,
        updatedAt: Instant = baseTime,
        archived: Boolean = false,
        projectId: ProjectId = this@SessionBootstrapTest.projectId,
    ): SessionId {
        val sid = SessionId(id)
        createSession(
            Session(
                id = sid,
                projectId = projectId,
                title = "Chat",
                createdAt = baseTime,
                updatedAt = updatedAt,
                archived = archived,
            ),
        )
        return sid
    }

    // region resolveBootstrapMode precedence

    @Test fun forceNewWinsOverEverything() {
        assertEquals(
            BootstrapMode.ForceNew,
            resolveBootstrapMode(resume = true, sessionPrefix = "abc", forceNew = true),
        )
    }

    @Test fun sessionPrefixBeatsResumeFlag() {
        val mode = resolveBootstrapMode(resume = true, sessionPrefix = "abc", forceNew = false)
        assertTrue(mode is BootstrapMode.ByPrefix)
        assertEquals("abc", (mode as BootstrapMode.ByPrefix).prefix)
    }

    @Test fun resumeFlagMapsToAuto() {
        // --resume is the explicit opt-in to the "pick up where I left off"
        // path — without it (and without --session) the CLI starts fresh.
        assertEquals(
            BootstrapMode.Auto,
            resolveBootstrapMode(resume = true, sessionPrefix = "", forceNew = false),
        )
    }

    @Test fun noFlagsMapsToForceNew() {
        // Default flipped from Auto → ForceNew (commit cycle "default fresh
        // session"): a no-flag launch should drop the user into a clean
        // session rather than silently resume a possibly-stale chat from
        // hours/days ago.
        assertEquals(
            BootstrapMode.ForceNew,
            resolveBootstrapMode(resume = false, sessionPrefix = "", forceNew = false),
        )
    }

    @Test fun blankSessionPrefixFallsThroughToFresh() {
        // `--session=""` (empty / whitespace) shouldn't be treated as a pick.
        // It falls through to whatever the other flags say — here, no other
        // flag set, so the new fresh-session default applies.
        assertEquals(
            BootstrapMode.ForceNew,
            resolveBootstrapMode(resume = false, sessionPrefix = "   ", forceNew = false),
        )
    }

    @Test fun blankSessionPrefixWithResumeFlagStillResumes() {
        // Same blank-prefix scenario, but with `--resume` set: the resume
        // flag becomes the deciding signal, mapping to Auto.
        assertEquals(
            BootstrapMode.Auto,
            resolveBootstrapMode(resume = true, sessionPrefix = "   ", forceNew = false),
        )
    }

    // endregion

    // region bootstrapSession behavior

    @Test fun autoResumesMostRecentNonArchived() = runTest {
        val sessions = freshSessions()
        sessions.seed("old", updatedAt = baseTime)
        val recent = sessions.seed("recent", updatedAt = baseTime.plus(DAY))
        sessions.seed("archived", updatedAt = baseTime.plus(DAY).plus(DAY), archived = true)

        val result = bootstrapSession(sessions, projectId, BootstrapMode.Auto)

        assertEquals(recent, result.sessionId, "archived session must not be chosen even when newer")
        assertFalse(result.createdFresh)
        assertEquals("resumed", result.reason)
    }

    @Test fun autoCreatesFreshWhenNoSessions() = runTest {
        val sessions = freshSessions()

        val result = bootstrapSession(sessions, projectId, BootstrapMode.Auto)

        assertTrue(result.createdFresh)
        assertEquals("fresh (no prior sessions)", result.reason)
        // Session exists in store post-bootstrap.
        assertEquals(1, sessions.listSessions(projectId).size)
    }

    @Test fun autoCreatesFreshWhenEveryExistingIsArchived() = runTest {
        val sessions = freshSessions()
        sessions.seed("archived-1", archived = true)
        sessions.seed("archived-2", archived = true)

        val result = bootstrapSession(sessions, projectId, BootstrapMode.Auto)

        assertTrue(result.createdFresh, "archived-only history must still produce a fresh session")
    }

    @Test fun forceNewCreatesFreshEvenWhenActiveExists() = runTest {
        val sessions = freshSessions()
        sessions.seed("active", updatedAt = baseTime.plus(DAY))

        val result = bootstrapSession(sessions, projectId, BootstrapMode.ForceNew)

        assertTrue(result.createdFresh)
        assertEquals("fresh", result.reason)
        assertEquals(2, sessions.listSessions(projectId).size, "ForceNew must create a net-new session")
    }

    @Test fun byPrefixPicksUniqueMatch() = runTest {
        val sessions = freshSessions()
        sessions.seed("abc-111", updatedAt = baseTime)
        val target = sessions.seed("xyz-222", updatedAt = baseTime)

        val result = bootstrapSession(sessions, projectId, BootstrapMode.ByPrefix("xyz"))

        assertEquals(target, result.sessionId)
        assertFalse(result.createdFresh)
        assertTrue(result.reason.startsWith("resumed by --session"))
    }

    @Test fun byPrefixFallsBackToFreshOnZeroMatches() = runTest {
        val sessions = freshSessions()
        sessions.seed("abc-111", updatedAt = baseTime)

        val result = bootstrapSession(sessions, projectId, BootstrapMode.ByPrefix("zzz"))

        assertTrue(result.createdFresh)
        assertTrue(result.reason.contains("no session starts with 'zzz'"))
    }

    @Test fun byPrefixFallsBackToFreshOnAmbiguousMatches() = runTest {
        val sessions = freshSessions()
        sessions.seed("abc-111")
        sessions.seed("abc-222")

        val result = bootstrapSession(sessions, projectId, BootstrapMode.ByPrefix("abc"))

        assertTrue(result.createdFresh, "ambiguous prefix must not silently pick one — fall back to fresh")
        assertTrue(result.reason.contains("ambiguous"))
    }

    @Test fun byPrefixSkipsArchivedMatches() = runTest {
        // Archived sessions are never auto-resumed by prefix either — keeps
        // --session and --resume's archive-filter behavior consistent.
        val sessions = freshSessions()
        sessions.seed("abc-archived", archived = true)

        val result = bootstrapSession(sessions, projectId, BootstrapMode.ByPrefix("abc"))

        assertTrue(result.createdFresh)
        assertTrue(result.reason.contains("no session starts with"))
    }

    @Test fun autoDoesNotLeakAcrossProjects() = runTest {
        val sessions = freshSessions()
        val otherProject = ProjectId("other")
        sessions.seed("for-other", projectId = otherProject)

        val result = bootstrapSession(sessions, projectId, BootstrapMode.Auto)

        assertTrue(result.createdFresh, "sessions on other projects must not be picked for this project")
    }

    // endregion

    companion object {
        private val DAY = kotlin.time.Duration.parseIsoString("PT24H")
    }
}
