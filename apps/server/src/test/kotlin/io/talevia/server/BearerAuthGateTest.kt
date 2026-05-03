package io.talevia.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * HTTP-level drift-catcher tests for the bearer-token gate in
 * `ServerModule.kt:83-95`. Cycle 232 audit:
 * `ServerSmokeTest.authTokenRequiredWhenEnvSet` covers the happy
 * path (correct token → 200) + missing-header (→ 401) + /health
 * bypass; this file pins the **wrong-token-shape** branches that
 * sit between those — drift in the strict `header != expected`
 * comparison would silently weaken auth.
 *
 * Same audit-pattern fallback as cycles 207-231.
 *
 * Three correctness contracts pinned:
 *
 *  1. **Wrong token after correct scheme → 401.** A header that
 *     starts with "Bearer " but ends in the wrong token MUST 401.
 *     Drift to "match prefix" (typo: `header.startsWith(expected)`)
 *     would let any "Bearer X" through; drift to "split + match
 *     last" would let "Bearer wrong; Bearer secret-123" through.
 *
 *  2. **Wrong scheme → 401.** Headers like "Token secret-123",
 *     "Basic ...", or just "secret-123" (no scheme) MUST 401.
 *     Drift to lenient parse (`endsWith(token)` or "any header
 *     containing the token") would let credentials leak via other
 *     auth schemes.
 *
 *  3. **Case sensitivity in scheme + token.** "bearer secret-123"
 *     (lowercase scheme) or "Bearer SECRET-123" (uppercased token)
 *     MUST 401. The check is `header != expected` — exact equality.
 *     Drift to case-insensitive comparison would silently weaken
 *     auth for callers that capitalize differently.
 *
 * Plus pins:
 *   - /health bypasses auth even when token IS set (already
 *     covered in smoke, re-pinned here for the
 *     wrong-token-shapes panel coherence).
 *   - /metrics IS gated when auth is configured (smoke covers
 *     /sessions; /metrics belongs to the same routing block and
 *     should be subject to the same gate).
 *   - 401 body has `{"error": "missing or invalid bearer token"}`
 *     (canonical format pinned — drift to a generic message would
 *     confuse callers debugging auth setup).
 */
class BearerAuthGateTest {

    private val token = "secret-123"

    private fun isolatedEnv(): Map<String, String> {
        val tmpDir = java.nio.file.Files.createTempDirectory("bearer-auth-gate-test-").toFile()
        return mapOf(
            "TALEVIA_PROJECTS_HOME" to tmpDir.resolve("projects").absolutePath,
            "TALEVIA_RECENTS_PATH" to tmpDir.resolve("recents.json").absolutePath,
            "TALEVIA_DB_PATH" to ":memory:",
            "TALEVIA_SERVER_TOKEN" to token,
        )
    }

    // ── 1. Wrong token after correct scheme ────────────────

    @Test fun bearerSchemeWithWrongTokenReturns401() = testApplication {
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.get("/sessions") {
            header(HttpHeaders.Authorization, "Bearer wrong-token")
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            resp.status,
            "Bearer with wrong token must 401",
        )
        val body = resp.body<Map<String, String>>()
        assertEquals(
            "missing or invalid bearer token",
            body["error"],
            "401 body must surface canonical error message",
        )
    }

    @Test fun bearerSchemeWithEmptyTokenReturns401() = testApplication {
        // Pin: "Bearer " (scheme + space + nothing) is NOT the
        // expected "Bearer secret-123" → 401. Drift to "treat empty
        // token as missing → fall through" would still 401, but for
        // a different reason; pinning the strict-equality path here.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.post("/sessions") {
            header(HttpHeaders.Authorization, "Bearer ")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ── 2. Wrong scheme ────────────────────────────────────

    @Test fun nonBearerSchemeReturns401EvenWithCorrectTokenString() = testApplication {
        // Marquee scheme-mismatch pin: "Token secret-123" or
        // "Basic <base64>" or just "secret-123" all MUST 401.
        // Drift to `endsWith(token)` would let "Token secret-123"
        // through; drift to "any header containing the token"
        // would let custom auth schemes leak past.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        for (badScheme in listOf("Token $token", "Basic $token", token, "")) {
            val resp = client.get("/sessions") {
                header(HttpHeaders.Authorization, badScheme)
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "Authorization header '$badScheme' must 401 (scheme != Bearer)",
            )
        }
    }

    @Test fun bearerSchemeWithoutSpaceReturns401() = testApplication {
        // Pin: "Bearersecret-123" (no space between scheme and
        // token) MUST 401. The expected format is "Bearer
        // secret-123" exactly.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.get("/sessions") {
            header(HttpHeaders.Authorization, "Bearer$token")
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            resp.status,
            "scheme without space ('Bearer<token>') must 401",
        )
    }

    // ── 3. Case sensitivity ─────────────────────────────────

    @Test fun lowercaseSchemeReturns401() = testApplication {
        // Pin: case-sensitive equality. "bearer secret-123" with
        // lowercase scheme MUST 401. Drift to `equalsIgnoreCase`
        // would silently weaken auth.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.get("/sessions") {
            header(HttpHeaders.Authorization, "bearer $token")
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            resp.status,
            "lowercase 'bearer' scheme must 401 (header != expected is case-sensitive)",
        )
    }

    @Test fun uppercasedTokenReturns401() = testApplication {
        // Pin: case sensitivity also applies to the token. "Bearer
        // SECRET-123" (token uppercased from secret-123) MUST 401.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.get("/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${token.uppercase()}")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test fun extraWhitespaceInHeaderReturns401() = testApplication {
        // Pin: `header != expected` — no trim. "Bearer  secret-123"
        // (double space) or " Bearer secret-123" (leading space)
        // does NOT match the expected "Bearer secret-123" → 401.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        for (variant in listOf("Bearer  $token", " Bearer $token", "Bearer $token ")) {
            val resp = client.get("/sessions") {
                header(HttpHeaders.Authorization, variant)
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "extra-whitespace variant '$variant' must 401 (no trim in the comparison)",
            )
        }
    }

    // ── /health bypasses + /metrics gated ─────────────────

    @Test fun healthBypassesAuthEvenWithoutTokenHeader() = testApplication {
        // Already in smoke; re-pinned for the wrong-token-shapes
        // panel coherence — drift to "auth all routes" would also
        // break liveness probes that don't carry a token.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val resp = client.get("/health")
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test fun healthBypassesAuthEvenWithWrongTokenHeader() = testApplication {
        // Pin: /health bypass is unconditional — `if path == /health
        // return@on` returns BEFORE checking the header. Drift to
        // "still validate token on /health" would block liveness
        // probes that send a stale token.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.get("/health") {
            header(HttpHeaders.Authorization, "Bearer wrong-token")
        }
        assertEquals(
            HttpStatusCode.OK,
            resp.status,
            "/health must bypass even with a wrong-token Authorization header",
        )
    }

    @Test fun metricsRouteRequiresAuthWhenTokenConfigured() = testApplication {
        // Pin: /metrics is in the same routing block as /sessions
        // and is NOT in the bypass list. A scrape-without-bearer
        // must 401 (NOT silently expose internal metrics).
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val resp = client.get("/metrics")
        assertEquals(
            HttpStatusCode.Unauthorized,
            resp.status,
            "/metrics MUST be gated when TALEVIA_SERVER_TOKEN is set (no scrape-without-auth)",
        )
    }

    @Test fun metricsRouteAcceptsCorrectBearerToken() = testApplication {
        // Comparative: with the right Bearer header, /metrics 200s
        // even though it shares the gated routing block.
        application { serverModule(ServerContainer(rawEnv = isolatedEnv())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val resp = client.get("/metrics") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(
            HttpStatusCode.OK,
            resp.status,
            "/metrics with correct Bearer must 200",
        )
        // Sanity: status is NOT 401.
        assertNotEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
