package io.talevia.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Session lifecycle + message submission + live-event SSE routes.
 *
 * Split out of `ServerModule.kt` as part of `debt-split-server-module-kt`
 * (2026-04-23). Callers: `ServerModule.serverModule(...)` invokes
 * [sessionRoutes] from inside its `routing {}` block, passing the shared
 * [container] + [agentScope] so SubmitMessage can fire an
 * `agent.run(...)` on a background coroutine.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Routing.sessionRoutes(
    container: ServerContainer,
    agentScope: CoroutineScope,
) {
    val json = JsonConfig.default

    post("/sessions") {
        val req = call.receive<CreateSessionRequest>()
        requireReasonableId(req.projectId, "projectId")
        req.title?.let { requireLength(it, MAX_TITLE_LENGTH, "title") }
        val now = Clock.System.now()
        val sid = SessionId(Uuid.random().toString())
        val session = Session(
            id = sid,
            projectId = ProjectId(req.projectId),
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
        val providerId = body.providerId ?: container.providers.default?.id
        if (providerId == null) {
            call.respond(
                HttpStatusCode.NotImplemented,
                mapOf("error" to "No provider API key set (ANTHROPIC_API_KEY / OPENAI_API_KEY)."),
            )
            return@post
        }
        val agent = container.agentFor(providerId)
        if (agent == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Provider '$providerId' is not configured."))
            return@post
        }

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
        if (container.providers.default == null) {
            call.respond(
                HttpStatusCode.NotImplemented,
                mapOf("error" to "No provider API key set (ANTHROPIC_API_KEY / OPENAI_API_KEY)."),
            )
            return@post
        }
        if (container.cancel(sid)) {
            call.respond(HttpStatusCode.OK, CancelSessionResponse(cancelled = true))
        } else {
            call.respond(
                HttpStatusCode.Conflict,
                CancelSessionResponse(cancelled = false, reason = "no in-flight run"),
            )
        }
    }

    get("/sessions/{id}/events") {
        val sid = SessionId(call.parameters["id"]!!)
        call.respondTextWriter(ContentType.Text.EventStream) {
            container.bus.forSession(sid).collect { event ->
                write("event: ${eventName(event)}\n")
                write("data: ${json.encodeToString(BusEventDto.serializer(), BusEventDto.from(event))}\n\n")
                flush()
            }
        }
    }
}
