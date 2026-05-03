package io.talevia.core.provider.openai.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [OpenAiCodexConstants] —
 * `core/src/commonMain/kotlin/io/talevia/core/provider/openai/codex/OpenAiCodexConstants.kt`.
 * Cycle 241 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-240.
 *
 * Every constant in this object is a contract with an EXTERNAL
 * service (OpenAI's auth + ChatGPT backend). Drift in any single
 * value silently breaks the Codex OAuth + Responses API
 * integration in ways that surface only at runtime against the
 * production host:
 *
 *  - `CLIENT_ID` — hardcoded by the official Codex CLI; ChatGPT
 *    backend recognises this exact value paired with
 *    `ORIGINATOR`. Substituting any other value → 403 at
 *    `/oauth/authorize`. The kdoc explicitly calls this out.
 *  - `ORIGINATOR` — whitelist. Non-whitelisted originator → 403
 *    from the Responses API.
 *  - `AUTH_BASE` / `RESPONSES_BASE` — drift to wrong host →
 *    every request fails connection / DNS / TLS.
 *  - `AUTHORIZE_URL` / `TOKEN_URL` / `RESPONSES_ENDPOINT` —
 *    string composition over the *_BASE constants. Drift in the
 *    composition (typo in path / wrong slash / wrong base) →
 *    404 / wrong endpoint.
 *  - `SCOPE` — exact match required by OAuth; missing scope →
 *    refused authorization. Adding extra scopes → user prompted
 *    for unrelated permissions.
 *  - `DEFAULT_LOOPBACK_PORT` / `CALLBACK_PATH` — loopback
 *    callback URL must match what we register with the auth
 *    server. Drift → callback never hits our server.
 *  - `PROACTIVE_REFRESH_THRESHOLD_DAYS` — proactive refresh
 *    cadence. Too low → rate-limit on /oauth/token; too high →
 *    expired token at first call.
 *
 * The test pins each value explicitly. A single-constant drift
 * surfaces here BEFORE shipping to a CI run that hits the real
 * endpoints — the Replicate + Anthropic e2e tests are paid /
 * gated by env, so a unit-level pin is the only line of defense
 * for the constants.
 *
 * Plus URL-composition pins: `AUTHORIZE_URL == "$AUTH_BASE/oauth/authorize"`,
 * `TOKEN_URL == "$AUTH_BASE/oauth/token"`, `RESPONSES_ENDPOINT ==
 * "$RESPONSES_BASE/responses"`. Drift to "hardcode literal" or
 * "wrong path segment" surfaces independently from each base
 * constant's own pin.
 *
 * Plus a "constants don't drift after first publish" pin: every
 * `*_URL` / `*_PATH` is non-blank and starts with `https://` /
 * `/` respectively. Drift to "http://" would silently downgrade
 * the OAuth flow to plaintext.
 */
class OpenAiCodexConstantsTest {

    // ── 1. Codex backend handshake ──────────────────────────

    @Test fun clientIdMatchesOfficialCodexCliValue() {
        // Marquee external-contract pin: the ChatGPT backend
        // recognises THIS specific clientId together with
        // ORIGINATOR. Drift = 403 at /oauth/authorize.
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", OpenAiCodexConstants.CLIENT_ID)
    }

    @Test fun originatorMatchesCodexCliValue() {
        // Marquee whitelist pin: ORIGINATOR is a strict
        // whitelist on the backend; non-whitelisted value →
        // 403 from Responses API.
        assertEquals("codex_cli_rs", OpenAiCodexConstants.ORIGINATOR)
    }

    // ── 2. URL composition + base hosts ─────────────────────

    @Test fun authBaseIsOpenAiAuthHost() {
        assertEquals("https://auth.openai.com", OpenAiCodexConstants.AUTH_BASE)
    }

    @Test fun authorizeUrlComposesFromAuthBase() {
        // Pin: composition is exactly `$AUTH_BASE/oauth/authorize`.
        // Drift to a hardcoded literal (or different path segment)
        // would silently break OAuth even when the base is fixed.
        assertEquals(
            "https://auth.openai.com/oauth/authorize",
            OpenAiCodexConstants.AUTHORIZE_URL,
        )
        assertEquals(
            "${OpenAiCodexConstants.AUTH_BASE}/oauth/authorize",
            OpenAiCodexConstants.AUTHORIZE_URL,
            "AUTHORIZE_URL must compose from AUTH_BASE",
        )
    }

    @Test fun tokenUrlComposesFromAuthBase() {
        assertEquals(
            "https://auth.openai.com/oauth/token",
            OpenAiCodexConstants.TOKEN_URL,
        )
        assertEquals(
            "${OpenAiCodexConstants.AUTH_BASE}/oauth/token",
            OpenAiCodexConstants.TOKEN_URL,
        )
    }

    @Test fun responsesBaseIsChatGptBackendHost() {
        assertEquals(
            "https://chatgpt.com/backend-api/codex",
            OpenAiCodexConstants.RESPONSES_BASE,
        )
    }

    @Test fun responsesEndpointComposesFromResponsesBase() {
        // Pin: `$RESPONSES_BASE/responses`. Drift to e.g.
        // `/v1/responses` (mirroring the public OpenAI API path)
        // would silently 404 every Codex completion.
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            OpenAiCodexConstants.RESPONSES_ENDPOINT,
        )
        assertEquals(
            "${OpenAiCodexConstants.RESPONSES_BASE}/responses",
            OpenAiCodexConstants.RESPONSES_ENDPOINT,
        )
    }

    // ── 3. OAuth scope + loopback callback ──────────────────

    @Test fun scopeMatchesCodexCliExactly() {
        // Marquee external-contract pin: scope is space-separated.
        // OAuth servers enforce exact match — adding extras
        // prompts the user for unrelated permissions; missing
        // any breaks the integration.
        val expected =
            "openid profile email offline_access api.connectors.read api.connectors.invoke"
        assertEquals(expected, OpenAiCodexConstants.SCOPE)
    }

    @Test fun scopeIncludesAllSixCanonicalScopes() {
        // Sibling pin: split the scope on space, assert the
        // 6 canonical scopes are all present. Catches drift
        // to "openid profile email" alone (subset) or
        // "openid offline_access" (different subset) that
        // would still pass the equality pin if someone
        // updated `expected` first.
        val parts = OpenAiCodexConstants.SCOPE.split(" ").toSet()
        for (s in listOf(
            "openid",
            "profile",
            "email",
            "offline_access",
            "api.connectors.read",
            "api.connectors.invoke",
        )) {
            assertTrue(s in parts, "SCOPE must contain '$s'; got: $parts")
        }
    }

    @Test fun defaultLoopbackPortMatchesCodexCli() {
        // Pin: 1455 is the codex-cli convention. Drift to 8080
        // / 3000 would clash with common dev-server ports —
        // silently every OAuth flow on a dev box fails because
        // the user already had a server bound there.
        assertEquals(1455, OpenAiCodexConstants.DEFAULT_LOOPBACK_PORT)
    }

    @Test fun callbackPathMatchesCodexCli() {
        // Pin: `/auth/callback` — must match what the OAuth
        // server's `redirect_uri` registration expects. Drift
        // would silently never deliver the auth code.
        assertEquals("/auth/callback", OpenAiCodexConstants.CALLBACK_PATH)
    }

    // ── 4. Refresh cadence ──────────────────────────────────

    @Test fun proactiveRefreshThresholdMatchesCodexCli() {
        // Pin: 8 days. Codex CLI's TOKEN_REFRESH_INTERVAL —
        // proactively refreshes credentials whose last refresh
        // was more than this long ago, even if the access
        // token's `exp` claim is still future. Drift to 30 days
        // → token expiry hits before refresh; drift to 1 day →
        // hammer the OAuth server with refreshes.
        assertEquals(8L, OpenAiCodexConstants.PROACTIVE_REFRESH_THRESHOLD_DAYS)
    }

    // ── 5. Hygiene + format ─────────────────────────────────

    @Test fun allHttpsUrlsUseHttpsScheme() {
        // Marquee TLS-downgrade pin: drift from `https://` to
        // `http://` on either base would silently downgrade the
        // OAuth flow to plaintext, leaking the auth code +
        // access token over the wire.
        for ((name, url) in listOf(
            "AUTH_BASE" to OpenAiCodexConstants.AUTH_BASE,
            "AUTHORIZE_URL" to OpenAiCodexConstants.AUTHORIZE_URL,
            "TOKEN_URL" to OpenAiCodexConstants.TOKEN_URL,
            "RESPONSES_BASE" to OpenAiCodexConstants.RESPONSES_BASE,
            "RESPONSES_ENDPOINT" to OpenAiCodexConstants.RESPONSES_ENDPOINT,
        )) {
            assertTrue(
                url.startsWith("https://"),
                "$name MUST start with 'https://'; got: $url",
            )
            assertTrue(
                "http://" !in url.removePrefix("https://"),
                "$name must NOT contain a nested 'http://' (URL injection guard); got: $url",
            )
        }
    }

    @Test fun callbackPathBeginsWithSlash() {
        // Pin: CALLBACK_PATH must begin with `/`. Without the
        // leading slash, URL composition (e.g.
        // `"http://127.0.0.1:$port" + CALLBACK_PATH`) would
        // produce `http://127.0.0.1:1455auth/callback` — a
        // garbage URL. Drift to "auth/callback" surfaces here.
        assertTrue(
            OpenAiCodexConstants.CALLBACK_PATH.startsWith("/"),
            "CALLBACK_PATH must begin with '/'; got: ${OpenAiCodexConstants.CALLBACK_PATH}",
        )
    }

    @Test fun loopbackPortIsInUserspaceRange() {
        // Pin: well-known privileged ports (<1024) require root
        // on Linux/macOS. The OAuth flow runs as the user, so
        // the port MUST be unprivileged. Codex's 1455 is safely
        // above 1024; pin the invariant rather than just the
        // value so a future "use 80 for prettier display"
        // refactor surfaces here.
        assertTrue(
            OpenAiCodexConstants.DEFAULT_LOOPBACK_PORT > 1024,
            "DEFAULT_LOOPBACK_PORT must be >1024 (unprivileged); got: ${OpenAiCodexConstants.DEFAULT_LOOPBACK_PORT}",
        )
        assertTrue(
            OpenAiCodexConstants.DEFAULT_LOOPBACK_PORT < 65536,
            "DEFAULT_LOOPBACK_PORT must be a valid port; got: ${OpenAiCodexConstants.DEFAULT_LOOPBACK_PORT}",
        )
    }

    @Test fun proactiveRefreshThresholdIsPositive() {
        // Pin: must be a positive number of days. Drift to 0 / negative
        // would either refresh on every check (DDoS the OAuth server)
        // or never refresh (token expires).
        assertTrue(
            OpenAiCodexConstants.PROACTIVE_REFRESH_THRESHOLD_DAYS > 0L,
            "PROACTIVE_REFRESH_THRESHOLD_DAYS must be positive; got: ${OpenAiCodexConstants.PROACTIVE_REFRESH_THRESHOLD_DAYS}",
        )
    }
}
