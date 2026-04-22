package io.talevia.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/**
 * Headless Talevia server entry point. Single-tenant, no auth — exposes the
 * Agent Core's HTTP and SSE surface for remote / cloud deployments.
 */
fun main() {
    val port = System.getenv("TALEVIA_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("TALEVIA_HOST") ?: "0.0.0.0"
    val container = ServerContainer(env = serverEnvWithDefaults())
    embeddedServer(CIO, host = host, port = port, module = { serverModule(container) }).start(wait = true)
}

/**
 * Real server env, plus defaults for the file-bundle ProjectStore.
 *
 * - `TALEVIA_PROJECTS_HOME` defaults to `<TALEVIA_DATA_DIR or ~/.talevia>/projects`
 *   — newly-created project bundles land here when the user doesn't pick a path.
 * - `TALEVIA_RECENTS_PATH` defaults to `<defaultRoot>/recents.json` — per-machine
 *   catalog of which bundles this server has opened. Required by [ServerContainer].
 *
 * Honours `TALEVIA_DATA_DIR` first (server deployments often want their state
 * outside `$HOME`), then falls back to `~/.talevia` to match the desktop / CLI
 * default. Anything the operator already passed in the environment wins.
 */
internal fun serverEnvWithDefaults(): Map<String, String> {
    val env = System.getenv().toMutableMap()
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
