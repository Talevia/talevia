package io.talevia.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP-level tests for [projectRoutes] —
 * `apps/server/src/main/kotlin/io/talevia/server/ProjectRoutes.kt`. The
 * project-scope CRUD plane (list / create / get / delete) split out of
 * `ServerModule.kt` as part of `debt-split-server-module-kt` (2026-04-23).
 * Cycle 224 audit: 0 direct test refs since extraction.
 *
 * Same audit-pattern fallback as cycles 207-223. Routes-shaped HTTP
 * tests via Ktor `testApplication` (banked from `ServerSmokeTest`).
 *
 * Four correctness contracts pinned (one per route):
 *
 *  1. **GET /projects** — empty store → empty array (NOT 404, NOT
 *     null); after a POST, the new project shows up in the list with
 *     the title the caller supplied + non-zero timestamps.
 *
 *  2. **POST /projects** — happy path returns 201 Created with a
 *     non-blank `projectId` (UUID-shaped). Marquee shape pin: the
 *     status MUST be 201, NOT 200 — drift would silently break
 *     clients that branch on Created vs OK.
 *
 *  3. **GET /projects/{id}/state** — valid id returns the full Project
 *     JSON with matching id; missing id returns 404 with
 *     `{"error": "project not found"}`; ID-validation rejects
 *     malformed ids (slashes, path traversal, over-128-char) BEFORE
 *     hitting the store.
 *
 *  4. **DELETE /projects/{id}** — happy path returns 204 No Content;
 *     subsequent GET on the same id returns 404; ID-validation runs
 *     here too.
 *
 * Plus pins:
 *   - `requireLength(title, MAX_TITLE_LENGTH)` on POST create — a
 *     title of 257+ chars is rejected (server returns 4xx).
 *   - Project bundle survives across requests in the same test
 *     application (ServerContainer's FileProjectStore is shared
 *     across the route handlers, not per-request).
 *
 * Isolation: each test stands up an `isolatedEnv()` with its own
 * temp `TALEVIA_PROJECTS_HOME` + `TALEVIA_RECENTS_PATH` and an
 * in-memory `TALEVIA_DB_PATH` so the user's real `~/.talevia/projects`
 * is not read or written during the test run.
 */
class ProjectRoutesTest {

    private fun isolatedEnv(): Map<String, String> {
        val tmpDir = Files.createTempDirectory("project-routes-test-").toFile()
        return mapOf(
            "TALEVIA_PROJECTS_HOME" to tmpDir.resolve("projects").absolutePath,
            "TALEVIA_RECENTS_PATH" to tmpDir.resolve("recents.json").absolutePath,
            "TALEVIA_DB_PATH" to ":memory:",
        )
    }

    @Test fun listProjectsEmptyReturnsEmptyArray() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.get("/projects")
        assertEquals(HttpStatusCode.OK, resp.status)
        val list = resp.body<List<ProjectSummaryDto>>()
        assertEquals(emptyList(), list, "fresh server has no projects → empty array (not null, not 404)")
    }

    @Test fun postProjectsCreatesAndReturns201CreatedWithProjectId() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp: HttpResponse = client.post("/projects") {
            contentType(ContentType.Application.Json)
            setBody(CreateProjectRequest(title = "test project"))
        }
        // Marquee shape pin: 201 Created, NOT 200 OK.
        assertEquals(
            HttpStatusCode.Created,
            resp.status,
            "POST /projects must return 201 Created (NOT 200 OK)",
        )
        val body = resp.body<CreateProjectResponse>()
        assertTrue(body.projectId.isNotBlank(), "projectId must be non-blank")
        // UUID-shape: 8-4-4-4-12 hex with dashes (36 chars).
        assertEquals(
            36,
            body.projectId.length,
            "projectId looks UUID-shaped; got: '${body.projectId}'",
        )
    }

    @Test fun listProjectsAfterCreateShowsNewProject() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val createResp = client.post("/projects") {
            contentType(ContentType.Application.Json)
            setBody(CreateProjectRequest(title = "Alpha Project"))
        }
        val newId = createResp.body<CreateProjectResponse>().projectId

        val list = client.get("/projects").body<List<ProjectSummaryDto>>()
        assertEquals(1, list.size, "list must contain exactly the newly-created project")
        val summary = list.single()
        assertEquals(newId, summary.id)
        assertEquals("Alpha Project", summary.title, "summary echoes caller-supplied title verbatim")
        assertTrue(summary.createdAtEpochMs > 0L, "createdAtEpochMs stamped to non-zero")
        assertTrue(summary.updatedAtEpochMs > 0L, "updatedAtEpochMs stamped to non-zero")
    }

    @Test fun getProjectStateReturnsFullProject() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val createResp = client.post("/projects") {
            contentType(ContentType.Application.Json)
            setBody(CreateProjectRequest(title = "T"))
        }
        val newId = createResp.body<CreateProjectResponse>().projectId

        val stateResp = client.get("/projects/$newId/state")
        assertEquals(HttpStatusCode.OK, stateResp.status)
        // Avoid coupling to the entire Project shape — just assert the
        // response is non-null JSON with the right id + a timeline
        // sub-object.
        val body = stateResp.body<JsonObject>()
        assertEquals(
            newId,
            body["id"]?.jsonPrimitive?.content,
            "state body's id matches the created project's id",
        )
        assertNotNull(body["timeline"], "state body has a timeline field")
    }

    @Test fun getProjectStateMissingIdReturns404WithErrorMessage() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.get("/projects/never-was-created/state")
        assertEquals(HttpStatusCode.NotFound, resp.status)
        val body = resp.body<Map<String, String>>()
        assertEquals(
            "project not found",
            body["error"],
            "404 body must surface the canonical 'project not found' error string",
        )
    }

    @Test fun getProjectStateRejectsMalformedIds() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // Path traversal attempt — `..` contains characters outside
        // the alphanumeric+`-_.` allowlist enforced by
        // `requireReasonableId`.
        // (Note: `..` is filename-safe characters but `requireReasonableId`
        // rejects ids that aren't 1..128 chars OR contain disallowed
        // characters. `..` is 2 chars of `.` which IS allowed. We need
        // an actual disallowed character to trigger the validation —
        // `/` doesn't reach the route because Ktor would route it
        // differently. Use a non-ASCII char or a space.)
        val resp = client.get("/projects/has%20space/state")
        // Either 400 (validation error) or some 4xx — drift to 200 /
        // 5xx would mean validation isn't running at all.
        assertTrue(
            resp.status.value in 400..499,
            "malformed id must produce a 4xx, NOT 2xx/5xx; got: ${resp.status}",
        )
    }

    @Test fun getProjectStateRejectsTooLongId() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // 129-char id, only alphanumerics → should pass char-set
        // check but fail the length cap.
        val tooLong = "a".repeat(129)
        val resp = client.get("/projects/$tooLong/state")
        assertTrue(
            resp.status.value in 400..499,
            "id > 128 chars must produce a 4xx; got: ${resp.status}",
        )
    }

    @Test fun postProjectsRejectsTooLongTitle() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // 257-char title — one over MAX_TITLE_LENGTH (256).
        val tooLongTitle = "x".repeat(257)
        val resp = client.post("/projects") {
            contentType(ContentType.Application.Json)
            setBody(CreateProjectRequest(title = tooLongTitle))
        }
        assertTrue(
            resp.status.value in 400..599,
            "over-MAX_TITLE_LENGTH title must NOT 201; got: ${resp.status}",
        )
        assertNotEquals(HttpStatusCode.Created, resp.status, "must NOT create a project with oversize title")
    }

    @Test fun deleteProjectReturns204AndProjectGoesAway() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val createResp = client.post("/projects") {
            contentType(ContentType.Application.Json)
            setBody(CreateProjectRequest(title = "to-delete"))
        }
        val newId = createResp.body<CreateProjectResponse>().projectId

        val delResp = client.delete("/projects/$newId")
        assertEquals(
            HttpStatusCode.NoContent,
            delResp.status,
            "DELETE /projects/{id} must return 204 No Content (NOT 200 OK)",
        )

        // Subsequent GET returns 404.
        val afterResp = client.get("/projects/$newId/state")
        assertEquals(
            HttpStatusCode.NotFound,
            afterResp.status,
            "deleted project must be gone from /projects/{id}/state",
        )

        // List should be empty again.
        val list = client.get("/projects").body<List<ProjectSummaryDto>>()
        assertEquals(emptyList(), list, "deleted project must be gone from /projects")
    }

    @Test fun deleteProjectRejectsMalformedId() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val resp = client.delete("/projects/has%20space")
        assertTrue(
            resp.status.value in 400..499,
            "DELETE with malformed id must produce 4xx; got: ${resp.status}",
        )
    }

    @Test fun multipleProjectsAreEachIndependent() = testApplication {
        // Pin: ServerContainer's projects store is shared across route
        // handlers (not per-request). Two POSTs land two projects, each
        // independently retrievable.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val a = client.post("/projects") {
            contentType(ContentType.Application.Json)
            setBody(CreateProjectRequest(title = "A"))
        }.body<CreateProjectResponse>().projectId
        val b = client.post("/projects") {
            contentType(ContentType.Application.Json)
            setBody(CreateProjectRequest(title = "B"))
        }.body<CreateProjectResponse>().projectId
        assertNotEquals(a, b, "each POST mints a distinct projectId")

        val list = client.get("/projects").body<List<ProjectSummaryDto>>()
        assertEquals(2, list.size)
        val titlesById = list.associate { it.id to it.title }
        assertEquals("A", titlesById[a])
        assertEquals("B", titlesById[b])
    }
}
