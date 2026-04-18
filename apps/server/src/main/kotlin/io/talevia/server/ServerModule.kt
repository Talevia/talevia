package io.talevia.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.RunInput
import io.talevia.core.bus.BusEvent
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.TokenUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Ktor `Application` module that wires the HTTP surface against [ServerContainer].
 *
 * v0 endpoints:
 *  - POST /sessions               → create session, return its id
 *  - GET  /sessions               → list sessions
 *  - POST /sessions/{id}/parts    → append a Part (text + part id) — the server-side
 *                                   stand-in for the Agent loop until provider keys
 *                                   are wired into the server
 *  - GET  /sessions/{id}/events   → SSE stream of [BusEvent]s scoped to session
 *
 * Real agent loop (full LLM streaming) goes here once provider keys are managed
 * server-side; stubbed for now so the SSE wiring can be exercised end-to-end.
 */
@OptIn(ExperimentalUuidApi::class)
fun Application.serverModule(container: ServerContainer = ServerContainer()) {
    val json = JsonConfig.default
    val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    install(ContentNegotiation) { json(Json { classDiscriminator = "type"; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
        }
    }

    routing {
        post("/sessions") {
            val req = call.receive<CreateSessionRequest>()
            val now = Clock.System.now()
            val sid = SessionId(Uuid.random().toString())
            val session = Session(
                id = sid,
                projectId = io.talevia.core.ProjectId(req.projectId),
                title = req.title ?: "Untitled",
                createdAt = now,
                updatedAt = now,
            )
            container.sessions.createSession(session)
            call.respond(CreateSessionResponse(sid.value))
        }

        get("/sessions") {
            // No listAll on store yet (later milestone); empty placeholder.
            call.respond(emptyList<String>())
        }

        post("/sessions/{id}/parts") {
            val sid = SessionId(call.parameters["id"]!!)
            val body = call.receive<AppendTextRequest>()
            val now = Clock.System.now()
            val msg = Message.User(
                id = MessageId(Uuid.random().toString()),
                sessionId = sid,
                createdAt = now,
                agent = body.agent ?: "default",
                model = ModelRef(body.providerId ?: "anthropic", body.modelId ?: "claude-opus-4-7"),
            )
            container.sessions.appendMessage(msg)
            container.sessions.upsertPart(
                Part.Text(
                    id = PartId(Uuid.random().toString()),
                    messageId = msg.id,
                    sessionId = sid,
                    createdAt = now,
                    text = body.text,
                ),
            )
            call.respond(AppendTextResponse(msg.id.value))
        }

        /**
         * POST /sessions/{id}/messages — submit a user prompt and run the agent.
         * Returns 202 Accepted immediately with a correlation id; stream response
         * events over the session's SSE channel (`GET /sessions/{id}/events`).
         */
        post("/sessions/{id}/messages") {
            val sid = SessionId(call.parameters["id"]!!)
            val body = call.receive<SubmitMessageRequest>()
            val agent = container.agent
            if (agent == null) {
                call.respond(HttpStatusCode.NotImplemented, mapOf(
                    "error" to "No provider API key set (ANTHROPIC_API_KEY / OPENAI_API_KEY).",
                ))
                return@post
            }

            val providerId = body.providerId ?: container.providers.default!!.id
            val modelId = body.modelId ?: defaultModelFor(providerId)
            val correlationId = Uuid.random().toString()

            agentScope.launch {
                runCatching {
                    agent.run(
                        RunInput(
                            sessionId = sid,
                            text = body.text,
                            model = ModelRef(providerId, modelId),
                            permissionRules = container.permissionRules,
                        ),
                    )
                }
            }

            call.respond(HttpStatusCode.Accepted, SubmitMessageResponse(correlationId, providerId, modelId))
        }

        /**
         * POST /sessions/{id}/cancel — interrupt an in-flight Agent.run for this session.
         * Returns 200 if a run was cancelled, 409 if the session was idle.
         */
        post("/sessions/{id}/cancel") {
            val sid = SessionId(call.parameters["id"]!!)
            val agent = container.agent
            if (agent == null) {
                call.respond(HttpStatusCode.NotImplemented, mapOf(
                    "error" to "No provider API key set (ANTHROPIC_API_KEY / OPENAI_API_KEY).",
                ))
                return@post
            }
            if (agent.cancel(sid)) {
                call.respond(HttpStatusCode.OK, mapOf("cancelled" to true))
            } else {
                call.respond(HttpStatusCode.Conflict, mapOf("cancelled" to false, "reason" to "no in-flight run"))
            }
        }

        get("/sessions/{id}/events") {
            val sid = SessionId(call.parameters["id"]!!)
            call.respondTextWriter(io.ktor.http.ContentType.Text.EventStream) {
                container.bus.forSession(sid).collect { event ->
                    write("event: ${eventName(event)}\n")
                    write("data: ${json.encodeToString(BusEventDto.serializer(), BusEventDto.from(event))}\n\n")
                    flush()
                }
            }
        }
    }
}

private fun eventName(e: BusEvent): String = when (e) {
    is BusEvent.SessionCreated -> "session.created"
    is BusEvent.SessionUpdated -> "session.updated"
    is BusEvent.SessionDeleted -> "session.deleted"
    is BusEvent.SessionCancelled -> "session.cancelled"
    is BusEvent.MessageUpdated -> "message.updated"
    is BusEvent.PartUpdated -> "message.part.updated"
    is BusEvent.PartDelta -> "message.part.delta"
    is BusEvent.PermissionAsked -> "permission.asked"
    is BusEvent.PermissionReplied -> "permission.replied"
}

@Serializable data class CreateSessionRequest(val projectId: String, val title: String? = null)
@Serializable data class CreateSessionResponse(val sessionId: String)
@Serializable data class AppendTextRequest(
    val text: String,
    val agent: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
)
@Serializable data class AppendTextResponse(val messageId: String)

@Serializable data class SubmitMessageRequest(
    val text: String,
    val providerId: String? = null,
    val modelId: String? = null,
)
@Serializable data class SubmitMessageResponse(
    val correlationId: String,
    val providerId: String,
    val modelId: String,
)

private fun defaultModelFor(providerId: String): String = when (providerId) {
    "anthropic" -> "claude-opus-4-7"
    "openai" -> "gpt-4o"
    else -> "default"
}

/**
 * Wire-format BusEvent. Mirrors the Kotlin sealed hierarchy but uses plain strings
 * for IDs so existing JSON consumers don't need branded value-class handling.
 */
@Serializable
data class BusEventDto(
    val type: String,
    val sessionId: String,
    val messageId: String? = null,
    val partId: String? = null,
    val field: String? = null,
    val delta: String? = null,
    val requestId: String? = null,
    val permission: String? = null,
    val patterns: List<String>? = null,
    val accepted: Boolean? = null,
    val remembered: Boolean? = null,
) {
    companion object {
        fun from(e: BusEvent): BusEventDto = when (e) {
            is BusEvent.SessionCreated -> BusEventDto("session.created", e.sessionId.value)
            is BusEvent.SessionUpdated -> BusEventDto("session.updated", e.sessionId.value)
            is BusEvent.SessionDeleted -> BusEventDto("session.deleted", e.sessionId.value)
            is BusEvent.SessionCancelled -> BusEventDto("session.cancelled", e.sessionId.value)
            is BusEvent.MessageUpdated -> BusEventDto("message.updated", e.sessionId.value, messageId = e.messageId.value)
            is BusEvent.PartUpdated -> BusEventDto("message.part.updated", e.sessionId.value, messageId = e.messageId.value, partId = e.partId.value)
            is BusEvent.PartDelta -> BusEventDto(
                "message.part.delta", e.sessionId.value,
                messageId = e.messageId.value, partId = e.partId.value, field = e.field, delta = e.delta,
            )
            is BusEvent.PermissionAsked -> BusEventDto(
                "permission.asked", e.sessionId.value,
                requestId = e.requestId, permission = e.permission, patterns = e.patterns,
            )
            is BusEvent.PermissionReplied -> BusEventDto(
                "permission.replied", e.sessionId.value,
                requestId = e.requestId, accepted = e.accepted, remembered = e.remembered,
            )
        }
    }
}
