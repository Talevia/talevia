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
    val container = ServerContainer()
    embeddedServer(CIO, host = host, port = port, module = { serverModule(container) }).start(wait = true)
}
