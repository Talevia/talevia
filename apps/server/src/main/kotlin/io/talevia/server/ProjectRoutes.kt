package io.talevia.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.talevia.core.ProjectId
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Project-scope CRUD routes.
 *
 * Split out of `ServerModule.kt` as part of `debt-split-server-module-kt`
 * (2026-04-23). Invoked from `ServerModule.serverModule(...)` inside
 * `routing {}`.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Routing.projectRoutes(container: ServerContainer) {
    /**
     * GET /projects — list every project (lightweight summary rows).
     */
    get("/projects") {
        val summaries = container.projects.listSummaries()
        call.respond(
            summaries.map {
                ProjectSummaryDto(it.id, it.title, it.createdAtEpochMs, it.updatedAtEpochMs)
            },
        )
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
}
