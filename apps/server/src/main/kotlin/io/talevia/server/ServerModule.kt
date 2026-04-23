package io.talevia.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.RunInput
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
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
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
            val providerId = body.providerId ?: container.providers.default?.id
            if (providerId == null) {
                call.respond(HttpStatusCode.NotImplemented, mapOf(
                    "error" to "No provider API key set (ANTHROPIC_API_KEY / OPENAI_API_KEY).",
                ))
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
                call.respond(HttpStatusCode.NotImplemented, mapOf(
                    "error" to "No provider API key set (ANTHROPIC_API_KEY / OPENAI_API_KEY).",
                ))
                return@post
            }
            if (container.cancel(sid)) {
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

        // ---- Project CRUD -------------------------------------------------------

        /**
         * GET /projects — list every project (lightweight summary rows).
         */
        get("/projects") {
            val summaries = container.projects.listSummaries()
            call.respond(summaries.map {
                ProjectSummaryDto(it.id, it.title, it.createdAtEpochMs, it.updatedAtEpochMs)
            })
        }

        /**
         * POST /projects — create a new blank project and return its id.
         */
        post("/projects") {
            val req = call.receive<CreateProjectRequest>()
            requireLength(req.title, MAX_TITLE_LENGTH, "title")
            val project = Project(
                id = ProjectId(Uuid.random().toString()),
                timeline = Timeline(),
            )
            container.projects.upsert(req.title, project)
            call.respond(HttpStatusCode.Created, CreateProjectResponse(project.id.value))
        }

        /**
         * GET /projects/{id}/state — full project JSON (timeline, sources, lockfile, …).
         */
        get("/projects/{id}/state") {
            val id = ProjectId(call.parameters["id"]!!)
            requireReasonableId(id.value, "id")
            val project = container.projects.get(id)
            if (project == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "project not found"))
            } else {
                call.respond(project)
            }
        }

        /**
         * DELETE /projects/{id} — permanently remove a project and its snapshots.
         */
        delete("/projects/{id}") {
            val id = ProjectId(call.parameters["id"]!!)
            requireReasonableId(id.value, "id")
            container.projects.delete(id)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- Media upload -------------------------------------------------------

        /**
         * POST /media?projectId=<id> — accept a `multipart/form-data` body with a
         * single file part, probe its metadata, write the bytes into the target
         * project bundle's `media/<assetId>.<ext>`, append the
         * [io.talevia.core.domain.MediaAsset] to `Project.assets`, and return
         * `{ "assetId": "<uuid>" }`. 400 when `projectId` is missing or the
         * project is not registered.
         */
        post("/media") {
            val projectIdParam = call.parameters["projectId"]?.ifBlank { null }
                ?: call.request.queryParameters["projectId"]?.ifBlank { null }
            if (projectIdParam == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "missing projectId query parameter"),
                )
                return@post
            }
            val pid = ProjectId(projectIdParam)
            requireReasonableId(pid.value, "projectId")
            if (container.projects.get(pid) == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "project ${pid.value} not found"),
                )
                return@post
            }

            val multipart = call.receiveMultipart()
            var assetId: String? = null
            multipart.forEachPart { part ->
                if (part is PartData.FileItem && assetId == null) {
                    val fileName = part.originalFileName?.ifBlank { null } ?: "upload"
                    val bytes = part.provider().readRemaining().readByteArray()
                    val tmp = File.createTempFile("talevia-upload-", "-$fileName")
                    val newAssetId = io.talevia.core.AssetId(
                        java.util.UUID.randomUUID().toString(),
                    )
                    val ext = fileName.substringAfterLast('.', missingDelimiterValue = "bin")
                        .ifBlank { "bin" }
                    try {
                        tmp.writeBytes(bytes)
                        val metadata = container.engine.probe(MediaSource.File(tmp.absolutePath))
                        val bundleSource = container.bundleBlobWriter.writeBlob(
                            projectId = pid,
                            assetId = newAssetId,
                            bytes = bytes,
                            format = ext,
                        )
                        val asset = io.talevia.core.domain.MediaAsset(
                            id = newAssetId,
                            source = bundleSource,
                            metadata = metadata,
                        )
                        container.projects.mutate(pid) { p ->
                            p.copy(assets = p.assets + asset)
                        }
                        assetId = newAssetId.value
                    } finally {
                        tmp.delete()
                    }
                }
                part.dispose()
            }
            if (assetId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file part found in multipart body"))
            } else {
                call.respond(HttpStatusCode.Created, mapOf("assetId" to assetId!!))
            }
        }

        /**
         * GET /metrics — prometheus-style text dump of counters and latency histograms.
         *
         * Counters: `talevia_<name_with_underscores> <value>`
         * Histograms: `talevia_<name>_p50/p95/p99 <ms>` (agent.run.ms, tool.*.ms)
         */
        get("/metrics") {
            val counters = container.metrics.snapshot().toSortedMap()
            val histograms = container.metrics.histogramSnapshot().toSortedMap()
            val body = buildString {
                counters.forEach { (k, v) ->
                    append("talevia_"); append(k.replace('.', '_')); append(' '); append(v); append('\n')
                }
                histograms.forEach { (k, stats) ->
                    val base = "talevia_${k.replace('.', '_')}"
                    append("${base}_count ${stats.count}\n")
                    append("${base}_p50 ${stats.p50}\n")
                    append("${base}_p95 ${stats.p95}\n")
                    append("${base}_p99 ${stats.p99}\n")
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
    is BusEvent.SessionCancelRequested -> "session.cancel.requested"
    is BusEvent.MessageUpdated -> "message.updated"
    is BusEvent.MessageDeleted -> "message.deleted"
    is BusEvent.SessionReverted -> "session.reverted"
    is BusEvent.PartUpdated -> "message.part.updated"
    is BusEvent.PartDelta -> "message.part.delta"
    is BusEvent.PermissionAsked -> "permission.asked"
    is BusEvent.PermissionReplied -> "permission.replied"
    is BusEvent.AgentRunFailed -> "agent.run.failed"
    is BusEvent.AgentRetryScheduled -> "agent.retry.scheduled"
    is BusEvent.AgentProviderFallback -> "agent.provider.fallback"
    is BusEvent.SessionCompactionAuto -> "session.compaction.auto"
    is BusEvent.AgentRunStateChanged -> "agent.run.state.changed"
    is BusEvent.SessionProjectBindingChanged -> "session.project.binding.changed"
    is BusEvent.ProjectValidationWarning -> "project.validation.warning"
    is BusEvent.AigcCostRecorded -> "aigc.cost.recorded"
    is BusEvent.AigcCacheProbe -> "aigc.cache.probe"
    is BusEvent.AssetsMissing -> "project.assets.missing"
}

@Serializable data class CreateProjectRequest(val title: String)
@Serializable data class CreateProjectResponse(val projectId: String)
@Serializable data class ProjectSummaryDto(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

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
    "openai" -> "gpt-5.4-mini"
    else -> "default"
}

/**
 * Wire-format BusEvent. Mirrors the Kotlin sealed hierarchy but uses plain strings
 * for IDs so existing JSON consumers don't need branded value-class handling.
 */
@Serializable
data class BusEventDto(
    val type: String,
    /**
     * Non-null for all session-scoped events; null for project-scoped
     * events like `project.validation.warning` that are emitted outside
     * any active session (e.g. on project load).
     */
    val sessionId: String? = null,
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
    val projectId: String? = null,
    /** Set for `session.project.binding.changed` — null when previously unbound. */
    val previousProjectId: String? = null,
    val anchorMessageId: String? = null,
    val deletedMessages: Int? = null,
    val appliedSnapshotPartId: String? = null,
    val attempt: Int? = null,
    val waitMs: Long? = null,
    val reason: String? = null,
    val historyTokensBefore: Int? = null,
    val thresholdTokens: Int? = null,
    /** `idle | generating | awaiting_tool | compacting | cancelled | failed` for `agent.run.state.changed`. */
    val runState: String? = null,
    /** Message-ish cause; set when `runState == "failed"`. */
    val runStateCause: String? = null,
    /** Human-readable DAG issues. Set for `project.validation.warning`. */
    val validationIssues: List<String>? = null,
    /** Set for `agent.provider.fallback` — provider the chain is leaving. */
    val fromProviderId: String? = null,
    /** Set for `agent.provider.fallback` — provider the chain is advancing to. */
    val toProviderId: String? = null,
    /** Set for `aigc.cost.recorded` — which tool produced the asset (e.g. `generate_image`). */
    val toolId: String? = null,
    /** Set for `aigc.cost.recorded` — the produced asset id. */
    val assetId: String? = null,
    /** Set for `aigc.cost.recorded` — USD cents (null = no pricing rule). */
    val costCents: Long? = null,
) {
    companion object {
        fun from(e: BusEvent): BusEventDto = when (e) {
            is BusEvent.SessionCreated -> BusEventDto("session.created", e.sessionId.value)
            is BusEvent.SessionUpdated -> BusEventDto("session.updated", e.sessionId.value)
            is BusEvent.SessionDeleted -> BusEventDto("session.deleted", e.sessionId.value)
            is BusEvent.SessionCancelled -> BusEventDto("session.cancelled", e.sessionId.value)
            is BusEvent.SessionCancelRequested -> BusEventDto("session.cancel.requested", e.sessionId.value)
            is BusEvent.MessageUpdated -> BusEventDto("message.updated", e.sessionId.value, messageId = e.messageId.value)
            is BusEvent.MessageDeleted -> BusEventDto("message.deleted", e.sessionId.value, messageId = e.messageId.value)
            is BusEvent.SessionReverted -> BusEventDto(
                "session.reverted", e.sessionId.value,
                projectId = e.projectId.value,
                anchorMessageId = e.anchorMessageId.value,
                deletedMessages = e.deletedMessages,
                appliedSnapshotPartId = e.appliedSnapshotPartId?.value,
            )
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
            is BusEvent.AgentRetryScheduled -> BusEventDto(
                "agent.retry.scheduled", e.sessionId.value,
                attempt = e.attempt, waitMs = e.waitMs, reason = e.reason,
            )
            is BusEvent.AgentProviderFallback -> BusEventDto(
                "agent.provider.fallback", e.sessionId.value,
                fromProviderId = e.fromProviderId,
                toProviderId = e.toProviderId,
                reason = e.reason,
            )
            is BusEvent.SessionCompactionAuto -> BusEventDto(
                "session.compaction.auto", e.sessionId.value,
                historyTokensBefore = e.historyTokensBefore, thresholdTokens = e.thresholdTokens,
            )
            is BusEvent.AgentRunStateChanged -> {
                val tag = when (val s = e.state) {
                    is io.talevia.core.agent.AgentRunState.Idle -> "idle" to null
                    is io.talevia.core.agent.AgentRunState.Generating -> "generating" to null
                    is io.talevia.core.agent.AgentRunState.AwaitingTool -> "awaiting_tool" to null
                    is io.talevia.core.agent.AgentRunState.Compacting -> "compacting" to null
                    is io.talevia.core.agent.AgentRunState.Cancelled -> "cancelled" to null
                    is io.talevia.core.agent.AgentRunState.Failed -> "failed" to s.cause
                }
                BusEventDto(
                    "agent.run.state.changed", e.sessionId.value,
                    runState = tag.first, runStateCause = tag.second,
                )
            }
            is BusEvent.SessionProjectBindingChanged -> BusEventDto(
                "session.project.binding.changed", e.sessionId.value,
                projectId = e.newProjectId.value,
                previousProjectId = e.previousProjectId?.value,
            )
            is BusEvent.ProjectValidationWarning -> BusEventDto(
                "project.validation.warning",
                sessionId = null,
                projectId = e.projectId.value,
                validationIssues = e.issues,
            )
            is BusEvent.AigcCostRecorded -> BusEventDto(
                "aigc.cost.recorded",
                e.sessionId.value,
                projectId = e.projectId.value,
                toolId = e.toolId,
                assetId = e.assetId,
                costCents = e.costCents,
            )
            is BusEvent.AigcCacheProbe -> BusEventDto(
                "aigc.cache.probe",
                sessionId = null,
                toolId = e.toolId,
            )
            is BusEvent.AssetsMissing -> BusEventDto(
                "project.assets.missing",
                sessionId = null,
                projectId = e.projectId.value,
            )
        }
    }
}
