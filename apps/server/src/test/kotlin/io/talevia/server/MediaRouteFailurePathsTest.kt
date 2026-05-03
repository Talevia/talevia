package io.talevia.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP-level tests for `POST /media` failure paths —
 * `apps/server/src/main/kotlin/io/talevia/server/MediaRoutes.kt:35-94`.
 * Cycle 231 audit: 0 direct test refs. The success path requires a
 * real FFmpeg `probe(...)` call (engine subprocess) and is exercised
 * by `MediaUploadE2ETest` style integration tests, but the route's
 * **failure-arithmetic branches** (missing param, project not found)
 * fire BEFORE the engine call and have no direct pin.
 *
 * Same audit-pattern fallback as cycles 207-230.
 *
 * Two correctness contracts pinned:
 *
 *  1. **Missing `projectId` query parameter → 400 Bad Request** with
 *     body `{"error": "missing projectId query parameter"}`. Drift to
 *     "404 / 500" would mis-classify a caller error as a server bug;
 *     drift to "auto-pick the first project" would silently mutate
 *     state on the wrong target.
 *
 *  2. **Project not found → 404 Not Found** with body `{"error":
 *     "project <id> not found"}`. The project id is named verbatim so
 *     a typo's diagnosis is "did I create this project?" rather than
 *     a generic "missing" hint. Drift to "404 with generic message"
 *     would lose the diagnostic value.
 *
 * Plus pin:
 *   - Malformed projectId (chars outside `[a-zA-Z0-9-_.]`) → 400 from
 *     `requireReasonableId` (caught by ServerModule's StatusPages
 *     handler). Pin against drift to "let bad chars through to the
 *     ProjectStore lookup" which would be a path-traversal-shape
 *     vulnerability into the file-bundle root.
 *
 * Isolation: each test stands up an isolated `ServerContainer(rawEnv =
 * isolatedEnv())` so the user's real `~/.talevia/projects` is not
 * read or written. The /media route never reaches FFmpeg-bound code
 * on these failure paths, so no engine setup is needed.
 */
class MediaRouteFailurePathsTest {

    private fun isolatedEnv(): Map<String, String> {
        val tmpDir = java.nio.file.Files.createTempDirectory("media-route-failure-test-").toFile()
        return mapOf(
            "TALEVIA_PROJECTS_HOME" to tmpDir.resolve("projects").absolutePath,
            "TALEVIA_RECENTS_PATH" to tmpDir.resolve("recents.json").absolutePath,
            "TALEVIA_DB_PATH" to ":memory:",
        )
    }

    @Test fun mediaWithoutProjectIdQueryParamReturns400() = testApplication {
        // Marquee param-missing pin: drift to 404/500 would mis-class
        // a caller error as a server bug. Drift to "auto-pick first
        // project" would silently mutate state on the wrong target.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.post("/media")
        assertEquals(
            HttpStatusCode.BadRequest,
            resp.status,
            "/media without projectId must 400 (NOT 404, NOT 500)",
        )
        val body = resp.body<Map<String, String>>()
        assertEquals(
            "missing projectId query parameter",
            body["error"],
            "canonical 400 message format pinned",
        )
    }

    @Test fun mediaWithBlankProjectIdQueryParamReturns400() = testApplication {
        // Pin: per route's `?.ifBlank { null }` chain, an empty
        // projectId is treated as missing (NOT as a literal projectId
        // ""). Drift to "treat blank as ''" would reach
        // `requireReasonableId("")` instead — different error path.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.post("/media?projectId=")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = resp.body<Map<String, String>>()
        assertEquals(
            "missing projectId query parameter",
            body["error"],
            "blank projectId triggers the same 'missing' message (ifBlank{null} chain)",
        )
    }

    @Test fun mediaWithUnknownProjectIdReturns404WithVerbatimProjectId() = testApplication {
        // Marquee project-not-found pin: missing project → 404 with
        // body naming the projectId verbatim. Drift to "404 with
        // generic message" would lose diagnostic value (typo vs
        // "I haven't created this yet" indistinguishable).
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.post("/media?projectId=ghost-project-id")
        assertEquals(
            HttpStatusCode.NotFound,
            resp.status,
            "/media on unknown projectId must 404 (NOT 400, NOT 500)",
        )
        val body = resp.body<Map<String, String>>()
        val errorMsg = body["error"] ?: ""
        assertTrue(
            "ghost-project-id" in errorMsg,
            "404 message must cite projectId verbatim; got: $errorMsg",
        )
        assertTrue(
            "not found" in errorMsg,
            "404 message must use 'not found' phrasing; got: $errorMsg",
        )
    }

    @Test fun mediaWithMalformedProjectIdReturns400FromRequireReasonableId() = testApplication {
        // Pin: `requireReasonableId(pid.value, "projectId")` enforces
        // the alphanumeric+`-_.` allowlist via require(){...}, which
        // throws IllegalArgumentException, which the ServerModule's
        // StatusPages handler catches → 400 (NOT 500). Marquee
        // path-traversal-shape pin: a projectId with `/` would
        // otherwise reach the ProjectStore lookup.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // URL-encoded space — disallowed character per requireReasonableId.
        val resp = client.post("/media?projectId=has%20space")
        assertTrue(
            resp.status.value in 400..499,
            "/media with malformed projectId must produce 4xx (NOT 5xx); got: ${resp.status}",
        )
    }

    @Test fun mediaWithOversizeProjectIdReturns400() = testApplication {
        // Pin: `requireReasonableId` also caps length at 128.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val tooLong = "a".repeat(129)
        val resp = client.post("/media?projectId=$tooLong")
        assertTrue(
            resp.status.value in 400..499,
            "/media with 129-char projectId must produce 4xx; got: ${resp.status}",
        )
    }
}
