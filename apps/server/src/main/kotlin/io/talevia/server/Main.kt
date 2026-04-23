package io.talevia.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/**
 * Headless Talevia server entry point. Single-tenant, no auth — exposes the
 * Agent Core's HTTP and SSE surface for remote / cloud deployments.
 *
 * Env-var defaults (bundle paths) are applied inside [ServerContainer] so
 * both production and tests go through the same fallback. See
 * [withServerDefaults].
 */
fun main() {
    val port = System.getenv("TALEVIA_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("TALEVIA_HOST") ?: "0.0.0.0"
    val container = ServerContainer()
    embeddedServer(CIO, host = host, port = port, module = { serverModule(container) }).start(wait = true)
}

/**
 * Fill in bundle-path defaults on the caller's env map.
 *
 * - `TALEVIA_PROJECTS_HOME` defaults to `<TALEVIA_DATA_DIR or ~/.talevia>/projects`
 *   — newly-created project bundles land here when the user doesn't pick a path.
 * - `TALEVIA_RECENTS_PATH` defaults to `<defaultRoot>/recents.json` — per-machine
 *   catalog of which bundles this server has opened. Required by [ServerContainer].
 *
 * Honours `TALEVIA_DATA_DIR` first (server deployments often want their state
 * outside `$HOME`), then falls back to `~/.talevia` to match the desktop / CLI
 * default. Anything the operator already passed in [raw] wins.
 *
 * Called from [ServerContainer]'s constructor — production (`Main.main()` →
 * `ServerContainer()` → `System.getenv()`) and tests
 * (`ServerContainer(env = emptyMap())`) share the same default-fill path.
 */
internal fun withServerDefaults(raw: Map<String, String>): Map<String, String> {
    val env = raw.toMutableMap()
    val explicit = env["TALEVIA_DATA_DIR"]?.takeIf { it.isNotBlank() }
    val defaultRoot = explicit?.let { java.io.File(it) }
        ?: java.io.File(System.getProperty("user.home"), ".talevia")
    if (env["TALEVIA_PROJECTS_HOME"].isNullOrBlank()) {
        env["TALEVIA_PROJECTS_HOME"] = java.io.File(defaultRoot, "projects").absolutePath
    }
    if (env["TALEVIA_RECENTS_PATH"].isNullOrBlank()) {
        env["TALEVIA_RECENTS_PATH"] = java.io.File(defaultRoot, "recents.json").absolutePath
    }
    return env
}
