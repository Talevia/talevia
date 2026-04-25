package io.talevia.core.permission

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.SqlDelightSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-restart coverage for [PermissionHistoryRecorder] persistence.
 *
 * Backlog: permission-history-persist-cross-restart. The recorder
 * dual-writes every Asked / Replied event to SQLite via SessionStore;
 * a fresh recorder built against the same store hydrates from SQL so
 * the agent doesn't re-ask on the next process boot.
 *
 * Each test uses two recorders constructed sequentially against the
 * same TaleviaDb — first records events, second hydrates and reads —
 * which simulates the "process kill + restart" lifecycle.
 */
class PermissionHistoryPersistenceTest {

    private fun newDb(): TaleviaDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return TaleviaDb(driver)
    }

    @Test fun pendingAskSurvivesRestart(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val db = newDb()
            val bus = EventBus()
            val store = SqlDelightSessionStore(db, bus)
            val sid = SessionId("persist-pending")

            // First "process": record the ask, no reply yet.
            val first = PermissionHistoryRecorder(
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                store = store,
            )
            first.awaitReady()
            bus.publish(BusEvent.PermissionAsked(sid, "rid-pending", "fs.write", listOf("/tmp/*")))
            withTimeout(5.seconds) {
                while (first.snapshot(sid.value).isEmpty()) yield()
            }

            // Second "process": fresh recorder against the same store.
            // Use a fresh bus so we know snapshot rows are coming from
            // SQL hydration, not from re-played bus events.
            val second = PermissionHistoryRecorder(
                bus = EventBus(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                store = store,
            )
            second.hydrateFromStore(sid)
            val rows = second.snapshot(sid.value)
            assertEquals(1, rows.size, "pending ask must survive restart")
            val r = rows.single()
            assertEquals("fs.write", r.permission)
            assertEquals(listOf("/tmp/*"), r.patterns)
            assertEquals(null, r.accepted, "still pending across restart")
            assertEquals(null, r.repliedEpochMs)
        }
    }

    @Test fun repliedDecisionSurvivesRestart(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val db = newDb()
            val bus = EventBus()
            val store = SqlDelightSessionStore(db, bus)
            val sid = SessionId("persist-replied")

            val first = PermissionHistoryRecorder(
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                store = store,
            )
            first.awaitReady()
            bus.publish(
                BusEvent.PermissionAsked(sid, "rid-replied", "network.fetch", listOf("https://example.com/*")),
            )
            // Wait for the ask round-trip to land in the in-memory map
            // so the subsequent reply has something to update.
            withTimeout(5.seconds) {
                while (first.snapshot(sid.value).isEmpty()) yield()
            }
            bus.publish(
                BusEvent.PermissionReplied(sid, "rid-replied", accepted = false, remembered = true),
            )
            withTimeout(5.seconds) {
                while (first.snapshot(sid.value).single().accepted == null) yield()
            }

            val second = PermissionHistoryRecorder(
                bus = EventBus(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                store = store,
            )
            second.hydrateFromStore(sid)
            val rows = second.snapshot(sid.value)
            assertEquals(1, rows.size)
            val r = rows.single()
            assertEquals("network.fetch", r.permission)
            assertEquals(false, r.accepted, "rejection must survive across restart — that's the whole point")
            assertEquals(true, r.remembered)
            assertNotNull(r.repliedEpochMs)
        }
    }

    @Test fun multipleSessionsHydrateIndependently(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val db = newDb()
            val bus = EventBus()
            val store = SqlDelightSessionStore(db, bus)

            val first = PermissionHistoryRecorder(
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                store = store,
            )
            first.awaitReady()
            bus.publish(BusEvent.PermissionAsked(SessionId("a"), "ra", "p", listOf("*")))
            bus.publish(BusEvent.PermissionAsked(SessionId("b"), "rb", "p", listOf("*")))
            withTimeout(5.seconds) {
                while (first.snapshot("a").isEmpty() || first.snapshot("b").isEmpty()) yield()
            }

            val second = PermissionHistoryRecorder(
                bus = EventBus(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                store = store,
            )
            // Hydrate only session 'a' — session 'b' should stay empty
            // (the recorder hydrates per-session lazily, not all at once).
            second.hydrateFromStore(SessionId("a"))
            assertEquals(1, second.snapshot("a").size)
            assertEquals(0, second.snapshot("b").size, "non-hydrated session must not bleed across")

            // Now hydrate 'b'.
            second.hydrateFromStore(SessionId("b"))
            assertEquals(1, second.snapshot("b").size)
        }
    }

    @Test fun nullStoreFallsBackToInMemoryOnly(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            // Sanity: a recorder without a store still works as before
            // (memory-only). hydrateFromStore is a no-op.
            val bus = EventBus()
            val recorder = PermissionHistoryRecorder(
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
            recorder.awaitReady()
            bus.publish(BusEvent.PermissionAsked(SessionId("s"), "r1", "p", listOf("*")))
            withTimeout(5.seconds) {
                while (recorder.snapshot("s").isEmpty()) yield()
            }
            recorder.hydrateFromStore(SessionId("s")) // no-op
            assertEquals(1, recorder.snapshot("s").size)
        }
    }
}
