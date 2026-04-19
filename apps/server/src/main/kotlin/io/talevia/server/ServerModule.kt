package io.talevia.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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
import io.talevia.core.permission.PermissionRule
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
/** Max length for user-supplied text fields (prompts, titles). Anything above this
 *  is almost certainly an accident or an attack — the largest Anthropic context
 *  window is 200k tokens ≈ 600k chars, and we never want a single request body to
 *  approach that. 128KB is the break-point: real prompts stay well under, and
 *  an adversary can't stuff unbounded JSON into a single field. */
internal const val MAX_TEXT_FIELD_LENGTH = 128 * 1024

/** Max length for free-form short strings like session titles. */
internal const val MAX_TITLE_LENGTH = 256

@OptIn(ExperimentalUuidApi::class)
fun Application.serverModule(container: ServerContainer = ServerContainer()) {
    val json = JsonConfig.default
    val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Start the BusEvent -> metrics aggregation. Subscription must be active
    // before the first publish, otherwise SharedFlow(replay=0) drops it.
    container.metricsSink.attach(agentScope)

    // Tear down owned resources when the Ktor application stops. Without this
    // the HttpClient's connection pool + SQL driver leak across reloads.
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
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

        post("/sessions") {
            val req = call.receive<CreateSessionRequest>()
            requireReasonableId(req.projectId, "projectId")
            req.title?.let { requireLength(it, MAX_TITLE_LENGTH, "title") }
            val now = Clock.System.now()
            val sid = SessionId(Uuid.random().toString())
            val session = Session(
                id = sid,
                projectId = io.talevia.core.ProjectId(req.projectId),
                title = req.title ?: "Untitled",
                permissionRules = req.permissionRules.orEmpty(),
                createdAt = now,
                updatedAt = now,
            )
            container.sessions.createSession(session)
            call.respond(CreateSessionResponse(sid.value))
        }

        get("/sessions") {
            val projectId = call.request.queryParameters["projectId"]?.let(::ProjectId)
            val sessions = container.sessions.listSessions(projectId)
            call.respond(sessions.map(SessionSummary::from))
        }

        post("/sessions/{id}/parts") {
            val sid = SessionId(call.parameters["id"]!!)
            val body = call.receive<AppendTextRequest>()
            requireLength(body.text, MAX_TEXT_FIELD_LENGTH, "text")
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
            requireLength(body.text, MAX_TEXT_FIELD_LENGTH, "text")
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

            // Merge session-specific rules with the container baseline. Session
            // rules come first so an explicit DENY on a session overrides a
            // broader ALLOW in the defaults during evaluation.
            val session = container.sessions.getSession(sid)
            val rules = (session?.permissionRules.orEmpty()) + container.permissionRules

            agentScope.launch {
                try {
                    agent.run(
                        RunInput(
                            sessionId = sid,
                            text = body.text,
                            model = ModelRef(providerId, modelId),
                            permissionRules = rules,
                        ),
                    )
                } catch (t: Throwable) {
                    // Surface the failure to SSE subscribers; the Logger keeps a full trace
                    // for operators. Rethrow CancellationException so coroutine cancel
                    // still propagates normally.
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    io.talevia.core.logging.Loggers.get("server.agent.run").log(
                        io.talevia.core.logging.LogLevel.ERROR,
                        "agent run failed",
                        fields = mapOf(
                            "session" to sid.value,
                            "correlationId" to correlationId,
                            "error" to (t.message ?: t::class.simpleName),
                        ),
                        cause = t,
                    )
                    container.bus.publish(
                        BusEvent.AgentRunFailed(
                            sessionId = sid,
                            correlationId = correlationId,
                            message = t.message ?: t::class.simpleName ?: "agent run failed",
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

        /**
         * GET /metrics — prometheus-style text dump of the counter registry.
         * Format: `talevia_<counter_with_underscores> <value>` per line.
         */
        get("/metrics") {
            val snapshot = container.metrics.snapshot().toSortedMap()
            val body = buildString {
                snapshot.forEach { (k, v) ->
                    append("talevia_")
                    append(k.replace('.', '_'))
                    append(' ')
                    append(v)
                    append('\n')
                }
            }
            call.respondText(body, io.ktor.http.ContentType.Text.Plain)
        }
    }
}

private fun requireLength(text: String, max: Int, fieldName: String) {
    require(text.length <= max) { "$fieldName exceeds max length ($max); was ${text.length}" }
}

/** Project / session IDs are used in URL paths and SQL — reject anything that
 *  isn't a short, filename-safe string. */
private fun requireReasonableId(value: String, fieldName: String) {
    require(value.isNotEmpty() && value.length <= 128) { "$fieldName must be 1..128 chars" }
    require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
        "$fieldName must be alphanumeric plus -_."
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
    is BusEvent.AgentRunFailed -> "agent.run.failed"
}

@Serializable data class CreateSessionRequest(
    val projectId: String,
    val title: String? = null,
    val permissionRules: List<PermissionRule>? = null,
)

@Serializable data class SessionSummary(
    val id: String,
    val projectId: String,
    val title: String,
    val parentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        fun from(s: Session): SessionSummary = SessionSummary(
            id = s.id.value,
            projectId = s.projectId.value,
            title = s.title,
            parentId = s.parentId?.value,
            createdAt = s.createdAt.toEpochMilliseconds(),
            updatedAt = s.updatedAt.toEpochMilliseconds(),
        )
    }
}
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
    val correlationId: String? = null,
    val message: String? = null,
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
            is BusEvent.AgentRunFailed -> BusEventDto(
                "agent.run.failed", e.sessionId.value,
                correlationId = e.correlationId, message = e.message,
            )
        }
    }
}
