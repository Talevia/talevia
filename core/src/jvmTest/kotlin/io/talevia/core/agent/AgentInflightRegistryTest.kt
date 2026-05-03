package io.talevia.core.agent

import io.talevia.core.SessionId
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [AgentInflightRegistry] — the per-session handle
 * registry that lets [Agent.cancel] reach into a running coroutine and
 * finalise the assistant message with `FinishReason.CANCELLED`. Coverage
 * was previously transitive only through `AgentLoopTest` /
 * `AgentRunStateTest`. These tests pin the basic register / unregister
 * / cancel / isRunning contracts as a refactor safety net.
 */
class AgentInflightRegistryTest {

    @Test fun registerThenIsRunningReturnsTrueUntilUnregister() = runTest {
        val r = AgentInflightRegistry()
        val sid = SessionId("s")
        val handle = AgentRunHandle(Job())
        assertFalse(r.isRunning(sid), "fresh registry has no inflight runs")

        r.register(sid, handle)
        assertTrue(r.isRunning(sid), "after register, isRunning becomes true")

        r.unregister(sid)
        assertFalse(r.isRunning(sid), "after unregister, isRunning becomes false")
    }

    @Test fun registerReturnsTheSameHandle() = runTest {
        // Pin the kdoc contract: "Returns the registered handle so the
        // caller holds onto its mutable fields." Important for Agent.run
        // which keeps the handle reference for retry attempt updates.
        val r = AgentInflightRegistry()
        val handle = AgentRunHandle(Job())
        val returned = r.register(SessionId("s"), handle)
        assertTrue(returned === handle, "register must return the same instance, not a copy/wrapper")
    }

    @Test fun registerSameSessionTwiceThrowsCheck() = runTest {
        // Pin the kdoc contract: "Throws if the session already has an
        // in-flight run." Same-session concurrent runs are a programmer
        // error — Agent.run wraps the IllegalStateException so the
        // caller-of-run sees a clean error, but the registry's job is
        // to fail fast.
        val r = AgentInflightRegistry()
        val sid = SessionId("s")
        r.register(sid, AgentRunHandle(Job()))
        val ex = assertFailsWith<IllegalStateException> {
            r.register(sid, AgentRunHandle(Job()))
        }
        assertTrue(
            "already has an in-flight" in (ex.message ?: ""),
            "error message must hint at the duplicate-registration root cause; got: ${ex.message}",
        )
    }

    @Test fun unregisterAbsentSessionIsNoOp() = runTest {
        // Pin: "No-op when the session isn't registered (covers double-
        // finally races)". Critical because Agent.run's `finally` may
        // run after a competing cancel-driven cleanup already removed
        // the entry.
        val r = AgentInflightRegistry()
        // Should not throw or affect any state.
        r.unregister(SessionId("never-registered"))
        // And after the no-op, fresh register still works.
        val handle = AgentRunHandle(Job())
        r.register(SessionId("s"), handle)
        assertTrue(r.isRunning(SessionId("s")))
    }

    @Test fun cancelOnIdleSessionReturnsFalse() = runTest {
        // Pin: "Returns true when a run was found and a cancel token
        // was sent; false when the session is idle."
        val r = AgentInflightRegistry()
        val cancelled = r.cancel(SessionId("idle"), reason = "user-stop")
        assertFalse(cancelled, "cancel on idle session must return false (signals 'nothing to do')")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test fun cancelOnInflightSessionReturnsTrueAndCancelsTheJob() = runBlocking {
        // The cancel path: registry holds a Job; cancel invokes
        // job.cancel(...) with a CancellationException carrying the
        // reason. Pin the side-effect on a real coroutine.
        val r = AgentInflightRegistry()
        val sid = SessionId("s")
        // Long-running job: completes only when cancelled.
        val job = GlobalScope.launch {
            try {
                delay(60_000)
            } catch (e: Exception) {
                // expected on cancel
            }
        }
        r.register(sid, AgentRunHandle(job))

        val cancelled = r.cancel(sid, reason = "user-stop-test")
        assertTrue(cancelled, "cancel on inflight session must return true")
        // The job should now be in the process of cancelling — wait
        // briefly for the cancel to propagate (delay throws).
        job.join()
        assertTrue(job.isCancelled, "underlying Job.cancel was called")
    }

    @Test fun cancelOfRecentlyUnregisteredSessionReturnsFalse() = runTest {
        // Race window: session unregisters → external cancel arrives
        // late. Must return false (no-op), not throw.
        val r = AgentInflightRegistry()
        val sid = SessionId("s")
        r.register(sid, AgentRunHandle(Job()))
        r.unregister(sid)
        assertFalse(r.cancel(sid, reason = "late"), "post-unregister cancel returns false")
    }

    @Test fun differentSessionsAreIndependent() = runTest {
        // Pin per-session isolation: registering / cancelling one
        // session doesn't affect another.
        val r = AgentInflightRegistry()
        val a = SessionId("a")
        val b = SessionId("b")
        r.register(a, AgentRunHandle(Job()))
        r.register(b, AgentRunHandle(Job()))

        assertTrue(r.isRunning(a))
        assertTrue(r.isRunning(b))

        // Unregister one — the other remains.
        r.unregister(a)
        assertFalse(r.isRunning(a))
        assertTrue(r.isRunning(b), "other sessions unaffected by single-session unregister")

        // Cancel the other — first stays absent.
        assertEquals(true, r.cancel(b, "cleanup"))
        assertFalse(r.isRunning(a))
    }
}
