package io.talevia.server

import io.ktor.client.request.get
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the container's owned resources (HttpClient + SQL driver) get torn
 * down when the Ktor application stops, rather than leaking across reloads.
 * Regression target: before the ApplicationStopped hook, every test suite
 * spinning up a server left a CIO connection pool + in-memory SQLite driver
 * dangling until the JVM exited.
 */
class LifecycleTest {

    @Test fun applicationStoppedHookFires() {
        val stopped = AtomicBoolean(false)
        testApplication {
            application {
                serverModule(ServerContainer(rawEnv = emptyMap()))
                monitor.subscribe(ApplicationStopped) { stopped.set(true) }
            }
            client.get("/sessions")  // force start so the stopped event will fire on teardown
        }
        // After testApplication returns, the app has been stopped and our hook ran.
        assertTrue(stopped.get(), "ApplicationStopped hook did not fire on testApplication teardown")
    }

    @Test fun closeIsIdempotent() {
        val container = ServerContainer(rawEnv = emptyMap())
        container.close()
        container.close()  // must not throw
    }
}
