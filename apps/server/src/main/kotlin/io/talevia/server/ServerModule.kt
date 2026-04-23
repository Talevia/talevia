package io.talevia.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi

/**
 * Ktor `Application` module that wires the HTTP surface against [ServerContainer].
 *
 * Route groups live in sibling files per `debt-split-server-module-kt`
 * (2026-04-23):
 *  - [sessionRoutes] — session CRUD, message submission, SSE streaming
 *  - [projectRoutes] — project CRUD
 *  - [mediaRoutes] — multipart media upload
 *  - [metricsRoute] — prometheus-style /metrics scrape
 * This file keeps module-level wiring only: content negotiation, status-page
 * error mapping, optional bearer-token gate, lifecycle teardown.
 *
 * v0 endpoints covered in those route groups:
 *  - POST /sessions               → create session, return its id
 *  - GET  /sessions               → list sessions
 *  - POST /sessions/{id}/parts    → append a Part (text + part id) — the server-side
 *                                   stand-in for the Agent loop when provider keys
 *                                   aren't wired server-side
 *  - POST /sessions/{id}/messages → submit a user prompt; runs the agent loop
 *  - POST /sessions/{id}/cancel   → interrupt an in-flight run
 *  - GET  /sessions/{id}/events   → SSE stream of [io.talevia.core.bus.BusEvent]s
 *                                   scoped to session
 *  - GET  /projects               → list projects
 *  - POST /projects               → create project
 *  - GET  /projects/{id}/state    → full project JSON
 *  - DELETE /projects/{id}        → delete project
 *  - POST /media?projectId=<id>   → multipart upload into project bundle
 *  - GET  /metrics                → counters + latency histograms
 */
@OptIn(ExperimentalUuidApi::class)
fun Application.serverModule(container: ServerContainer = ServerContainer()) {
    val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Start the BusEvent -> metrics aggregation. Subscription must be active
    // before the first publish, otherwise SharedFlow(replay=0) drops it.
    container.metricsSink.attach(agentScope)

    // Tear down owned resources when the Ktor application stops. Without this
    // the HttpClient's connection pool + SQL driver leak across reloads.
    monitor.subscribe(ApplicationStopped) {
        agentScope.cancel()
        container.close()
    }

    install(ContentNegotiation) { json(Json { classDiscriminator = "type"; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            // Validation errors map to 400 so clients can distinguish "I sent bad
            // input" from "server broke". PathGuard raises IllegalArgumentException.
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
        }
    }

    // Bearer-token gate. When TALEVIA_SERVER_TOKEN is empty (dev mode) the
    // plugin is not installed; otherwise every non-health request must carry
    // a matching Authorization: Bearer header.
    if (container.authToken.isNotEmpty()) {
        val expected = "Bearer ${container.authToken}"
        val bearerAuth = createApplicationPlugin("BearerAuth") {
            on(CallSetup) { call ->
                if (call.request.path() == "/health") return@on
                val header = call.request.headers[HttpHeaders.Authorization]
                if (header != expected) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing or invalid bearer token"))
                }
            }
        }
        install(bearerAuth)
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        sessionRoutes(container, agentScope)
        projectRoutes(container)
        mediaRoutes(container)
        metricsRoute(container)
    }
}
