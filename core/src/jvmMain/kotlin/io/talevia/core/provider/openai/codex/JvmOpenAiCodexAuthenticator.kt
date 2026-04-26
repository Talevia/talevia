package io.talevia.core.provider.openai.codex

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.talevia.core.JsonConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.BindException
import java.net.ServerSocket
import java.net.URLEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * JVM implementation of [OpenAiCodexAuthenticator]. Drives the ChatGPT OAuth
 * authorization-code + PKCE flow:
 *
 *  1. Generate PKCE verifier/challenge + state.
 *  2. Bind a loopback HTTP server on `127.0.0.1:[loopbackPort]` (default 1455);
 *     fall back to a random port after sending `GET /cancel` to evict any
 *     stale Codex/Talevia auth server squatting on the port.
 *  3. Build the authorize URL and open it in the user's browser.
 *  4. Wait for the redirect to `/auth/callback?code=...&state=...`.
 *  5. Validate state, exchange the code for tokens via form-encoded POST to
 *     `auth.openai.com/oauth/token`.
 *  6. Decode the id_token to extract `chatgpt_account_id`.
 *  7. Persist nothing here — caller saves the returned [OpenAiCodexCredentials]
 *     via [OpenAiCodexCredentialStore].
 *
 * @param onPrompt invoked with the authorize URL when [BrowserOpener] cannot
 *   launch a browser (e.g. headless server). The CLI/desktop wrapper prints
 *   it for the user to copy/paste.
 */
class JvmOpenAiCodexAuthenticator(
    private val httpClient: HttpClient,
    private val loopbackPort: Int = OpenAiCodexConstants.DEFAULT_LOOPBACK_PORT,
    private val timeout: Duration = 5.minutes,
    private val onPrompt: (String) -> Unit = { url -> System.err.println("Open in browser: $url") },
    private val openBrowser: (String) -> Boolean = BrowserOpener::open,
    private val clock: Clock = Clock.System,
    private val json: Json = JsonConfig.default,
) : OpenAiCodexAuthenticator {

    override suspend fun login(): OpenAiCodexCredentials = coroutineScope {
        val pkce = Pkce.generatePair()
        val state = Pkce.generateState()
        val resultDeferred = CompletableDeferred<Result<ExchangedTokens>>()

        // Pick the actual port. If 1455 is taken, send /cancel to the squatter
        // and retry; on persistent failure, fall back to a random ephemeral
        // port. We MUST know the final port before constructing the authorize
        // URL because redirect_uri is part of PKCE-bound state.
        val resolvedPort = pickPort()
        // OpenAI's authorize endpoint matches the redirect_uri host as a literal
        // string against the whitelist for the Codex CLI client_id. The
        // whitelisted host is `localhost`, NOT `127.0.0.1` — using the IP
        // returns `unknown_error` from auth.openai.com. We still bind the
        // loopback server on 127.0.0.1 below; the browser resolves "localhost"
        // to that interface anyway.
        val redirectUri = "http://localhost:$resolvedPort${OpenAiCodexConstants.CALLBACK_PATH}"
        val authorizeUrl = buildAuthorizeUrl(pkce.challenge, state, redirectUri)

        val server = embeddedServer(CIO, host = "127.0.0.1", port = resolvedPort, module = {
            routing {
                get(OpenAiCodexConstants.CALLBACK_PATH) {
                    val params = parseQuery(call.request.queryString())
                    val callbackState = params["state"]
                    if (callbackState != state) {
                        call.respondText(
                            HTML_ERROR_STATE_MISMATCH,
                            ContentType.Text.Html,
                            HttpStatusCode.BadRequest,
                        )
                        resultDeferred.complete(Result.failure(IllegalStateException("oauth callback state mismatch")))
                        return@get
                    }
                    val errorParam = params["error"]
                    if (errorParam != null) {
                        val description = params["error_description"].orEmpty()
                        val msg = "OAuth callback error: $errorParam${if (description.isNotEmpty()) " — $description" else ""}"
                        call.respondText(htmlError(msg), ContentType.Text.Html, HttpStatusCode.BadRequest)
                        resultDeferred.complete(Result.failure(RuntimeException(msg)))
                        return@get
                    }
                    val code = params["code"]
                    if (code.isNullOrEmpty()) {
                        call.respondText(htmlError("Missing authorization code."), ContentType.Text.Html, HttpStatusCode.BadRequest)
                        resultDeferred.complete(Result.failure(IllegalStateException("oauth callback missing code")))
                        return@get
                    }
                    try {
                        val tokens = exchangeCodeForTokens(code = code, codeVerifier = pkce.verifier, redirectUri = redirectUri)
                        call.respondText(HTML_SUCCESS, ContentType.Text.Html, HttpStatusCode.OK)
                        resultDeferred.complete(Result.success(tokens))
                    } catch (e: Exception) {
                        call.respondText(
                            htmlError("Token exchange failed: ${e.message ?: e::class.simpleName}"),
                            ContentType.Text.Html,
                            HttpStatusCode.BadGateway,
                        )
                        resultDeferred.complete(Result.failure(e))
                    }
                }
                get("/cancel") {
                    call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
                    resultDeferred.complete(Result.failure(IllegalStateException("login cancelled")))
                }
                // Fallback for any other path
                get("/{...}") {
                    call.respondText(
                        "Talevia OAuth callback server. Path ${call.request.path()} is not handled.",
                        ContentType.Text.Plain,
                        HttpStatusCode.NotFound,
                    )
                }
            }
        })
        // Start the server in a non-blocking way; ktor's embeddedServer returns
        // an EmbeddedServer that we control via start/stop.
        server.start(wait = false)

        try {
            val opened = openBrowser(authorizeUrl)
            if (!opened) onPrompt(authorizeUrl)

            val tokens = withTimeout(timeout) { resultDeferred.await().getOrThrow() }
            val accountId = JwtClaims.chatgptAccountId(tokens.idToken)
                ?: throw IllegalStateException("id_token missing chatgpt_account_id claim — login flow not eligible for ChatGPT backend")
            OpenAiCodexCredentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                idToken = tokens.idToken,
                accountId = accountId,
                lastRefreshEpochMs = clock.now().toEpochMilliseconds(),
            )
        } finally {
            runCatching { server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000) }
        }
    }

    private fun pickPort(): Int {
        // Try preferred port first
        if (canBind(loopbackPort)) return loopbackPort
        // Send /cancel to the existing instance to evict it
        runCatching { sendCancel(loopbackPort) }
        Thread.sleep(200)
        if (canBind(loopbackPort)) return loopbackPort
        // Fall back to an ephemeral port. ServerSocket(0) hands out a random
        // free port; we close it immediately and let ktor rebind, racing only
        // a rare TOCTOU window.
        ServerSocket(0).use { return it.localPort }
    }

    private fun canBind(port: Int): Boolean = runCatching {
        ServerSocket().use { ss ->
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrElse { e ->
        if (e is BindException) false else throw e
    }

    private fun sendCancel(port: Int) {
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2000
            val out = socket.getOutputStream()
            out.write("GET /cancel HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            socket.getInputStream().readNBytes(64)
        }
    }

    private fun buildAuthorizeUrl(challenge: String, state: String, redirectUri: String): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to OpenAiCodexConstants.CLIENT_ID,
            "redirect_uri" to redirectUri,
            "scope" to OpenAiCodexConstants.SCOPE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "state" to state,
            "originator" to OpenAiCodexConstants.ORIGINATOR,
        )
        val qs = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }
        return "${OpenAiCodexConstants.AUTHORIZE_URL}?$qs"
    }

    private suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): ExchangedTokens {
        val body = listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to OpenAiCodexConstants.CLIENT_ID,
            "code_verifier" to codeVerifier,
        ).joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }
        val response = httpClient.post(OpenAiCodexConstants.TOKEN_URL) {
            contentType(ContentType.Application.FormUrlEncoded)
            headers { append(HttpHeaders.Accept, "application/json") }
            setBody(body)
        }
        val text = runCatching { response.bodyAsText() }.getOrElse { "" }
        if (response.status.value !in 200..299) {
            throw RuntimeException("token endpoint returned ${response.status.value}: $text")
        }
        val obj: JsonObject = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw RuntimeException("token endpoint returned non-JSON body: $text") }
        val accessToken = obj.string("access_token")
            ?: throw RuntimeException("token response missing access_token: $text")
        val idToken = obj.string("id_token")
            ?: throw RuntimeException("token response missing id_token: $text")
        val refreshToken = obj.string("refresh_token")
            ?: throw RuntimeException("token response missing refresh_token: $text")
        return ExchangedTokens(accessToken = accessToken, idToken = idToken, refreshToken = refreshToken)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun parseQuery(qs: String?): Map<String, String> {
        if (qs.isNullOrEmpty()) return emptyMap()
        return qs.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@mapNotNull null
            val key = java.net.URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8)
            val value = java.net.URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8)
            key to value
        }.toMap()
    }

    private data class ExchangedTokens(
        val accessToken: String,
        val idToken: String,
        val refreshToken: String,
    )

    private companion object {
        private val HTML_SUCCESS = """
            <!doctype html><html><head><meta charset="utf-8"><title>Talevia · Signed in</title>
            <style>body{font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;background:#0b0b0c;color:#f3f3f5}
            .card{padding:48px 56px;border:1px solid #2a2a2e;border-radius:12px;text-align:center;max-width:420px}
            h1{margin:0 0 12px 0;font-weight:600;font-size:20px}p{margin:0;color:#aaa;line-height:1.5}</style></head>
            <body><div class="card"><h1>Signed in</h1><p>Talevia received your ChatGPT credentials. You can close this tab and return to the terminal.</p></div></body></html>
        """.trimIndent()

        private val HTML_ERROR_STATE_MISMATCH = """
            <!doctype html><html><head><meta charset="utf-8"><title>Talevia · State mismatch</title></head>
            <body><h1>State mismatch</h1><p>The OAuth callback state did not match. Re-run /login.</p></body></html>
        """.trimIndent()

        private fun htmlError(message: String): String =
            "<!doctype html><html><body><h1>Sign-in error</h1><p>${escape(message)}</p></body></html>"

        private fun escape(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
