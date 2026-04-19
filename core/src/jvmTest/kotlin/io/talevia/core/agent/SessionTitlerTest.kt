package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Session
import io.talevia.core.session.SessionStore
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTitlerTest {

    @Test
    fun overwritesPlaceholderTitleWithGeneratedTitle() = runTest {
        val (store, _) = newStore()
        val sid = createSession(store, title = "Untitled")

        val partId = PartId("t1")
        val provider = FakeProvider(
            listOf(
                listOf(
                    LlmEvent.TextStart(partId),
                    LlmEvent.TextDelta(partId, "Split clip "),
                    LlmEvent.TextDelta(partId, "at 10s"),
                    LlmEvent.TextEnd(partId, "Split clip at 10s."),
                    LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 5, output = 4)),
                ),
            ),
        )

        SessionTitler(provider = provider, store = store, modelId = "fake-mini")
            .generate(sid, "please split the clip at 10s")

        val updated = store.getSession(sid)!!
        assertEquals("Split clip at 10s", updated.title)
    }

    @Test
    fun skipsGenerationWhenTitleWasAlreadySet() = runTest {
        val (store, _) = newStore()
        val sid = createSession(store, title = "User chose this name")

        // Provider is never called — an empty turn list would throw if stream() ran.
        val provider = FakeProvider(emptyList())

        SessionTitler(provider = provider, store = store, modelId = "fake-mini")
            .generate(sid, "please split the clip at 10s")

        assertEquals("User chose this name", store.getSession(sid)!!.title)
        assertEquals(0, provider.requests.size)
    }

    @Test
    fun stripsSurroundingQuotesAndTrailingPunctuation() = runTest {
        val (store, _) = newStore()
        val sid = createSession(store, title = "Untitled")

        val partId = PartId("t1")
        val provider = FakeProvider(
            listOf(
                listOf(
                    LlmEvent.TextStart(partId),
                    LlmEvent.TextDelta(partId, "\"Short video recap\"!"),
                    LlmEvent.TextEnd(partId, "\"Short video recap\"!"),
                    LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 3, output = 3)),
                ),
            ),
        )

        SessionTitler(provider = provider, store = store, modelId = "fake-mini")
            .generate(sid, "make a recap")

        assertEquals("Short video recap", store.getSession(sid)!!.title)
    }

    @Test
    fun skipsOnEmptyUserText() = runTest {
        val (store, _) = newStore()
        val sid = createSession(store, title = "Untitled")

        val provider = FakeProvider(emptyList())
        SessionTitler(provider = provider, store = store, modelId = "fake-mini")
            .generate(sid, "   ")

        assertEquals("Untitled", store.getSession(sid)!!.title)
        assertEquals(0, provider.requests.size)
    }

    private fun newStore(): Pair<SessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun createSession(store: SessionStore, title: String): SessionId {
        val sid = SessionId("s-${title.hashCode()}")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = title,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }
}
