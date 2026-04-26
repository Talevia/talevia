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
import io.talevia.core.JsonConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Default [OpenAiCodexCredentialProvider]. On every [current] call:
 *
 * 1. Loads the latest credentials from [store]; throws [OpenAiCodexNotSignedIn] if absent.
 * 2. Decides whether a refresh is needed — proactively if the access token's
 *    `exp` claim is in the past OR `last_refresh` is older than
 *    [OpenAiCodexConstants.PROACTIVE_REFRESH_THRESHOLD_DAYS]; otherwise reuses
 *    the cached value.
 * 3. If refreshing, POSTs JSON to `auth.openai.com/oauth/token`, persists the
 *    rotated tokens, returns the new bundle.
 *
 * Permanent refresh failures (`refresh_token_expired` / `_reused` /
 * `_invalidated`) raise [OpenAiCodexAuthExpired]; the caller surfaces a
 * "please /login again" prompt.
 *
 * A [Mutex] serialises concurrent refresh attempts so a burst of in-flight
 * Responses API calls all returning 401 doesn't trigger N parallel refreshes.
 */
class OpenAiCodexCredentialProviderImpl(
    private val httpClient: HttpClient,
    private val store: OpenAiCodexCredentialStore,
    private val clock: Clock = Clock.System,
    private val json: Json = JsonConfig.default,
) : OpenAiCodexCredentialProvider {

    private val mutex = Mutex()

    override suspend fun current(): OpenAiCodexCredentials = mutex.withLock {
        val loaded = store.load() ?: throw OpenAiCodexNotSignedIn()
        if (needsRefresh(loaded)) doRefresh(loaded) else loaded
    }

    override suspend fun refresh(): OpenAiCodexCredentials = mutex.withLock {
        val loaded = store.load() ?: throw OpenAiCodexNotSignedIn()
        doRefresh(loaded)
    }

    private fun needsRefresh(creds: OpenAiCodexCredentials): Boolean {
        val nowMs = clock.now().toEpochMilliseconds()
        val expSec = JwtClaims.exp(creds.accessToken)
        if (expSec != null && expSec * 1000L <= nowMs) return true
        val staleAfterMs = OpenAiCodexConstants.PROACTIVE_REFRESH_THRESHOLD_DAYS * 24 * 60 * 60 * 1000L
        return nowMs - creds.lastRefreshEpochMs >= staleAfterMs
    }

    private suspend fun doRefresh(stale: OpenAiCodexCredentials): OpenAiCodexCredentials {
        val body: JsonObject = buildJsonObject {
            put("client_id", OpenAiCodexConstants.CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", stale.refreshToken)
        }

        val response = httpClient.post(OpenAiCodexConstants.TOKEN_URL) {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Accept, "application/json") }
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }
        val text = runCatching { response.bodyAsText() }.getOrElse { "" }

        if (response.status == HttpStatusCode.Unauthorized) {
            val code = extractErrorCode(text)
            throw OpenAiCodexAuthExpired(
                "refresh failed: ${code ?: "unauthorized"} (body: $text)",
            )
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("openai-codex token refresh HTTP ${response.status.value}: $text")
        }

        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw RuntimeException("openai-codex token refresh returned non-JSON body: $text")
        }
        // The refresh response may omit any of the three fields — only update what came back.
        val rotated = stale.copy(
            accessToken = parsed.string("access_token") ?: stale.accessToken,
            idToken = parsed.string("id_token") ?: stale.idToken,
            refreshToken = parsed.string("refresh_token") ?: stale.refreshToken,
            lastRefreshEpochMs = clock.now().toEpochMilliseconds(),
            // accountId is encoded in id_token; refresh re-derive it whenever possible.
            accountId = parsed.string("id_token")
                ?.let { JwtClaims.chatgptAccountId(it) }
                ?: stale.accountId,
        )
        store.save(rotated)
        return rotated
    }

    private fun extractErrorCode(body: String): String? {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        // The error payload may be either {"error":"refresh_token_expired"} or
        // {"error":{"code":"refresh_token_expired"}} — accept both.
        return when (val err = parsed["error"]) {
            is JsonPrimitive -> err.contentOrNull
            is JsonObject -> (err["code"] as? JsonPrimitive)?.contentOrNull
            else -> (parsed["code"] as? JsonPrimitive)?.contentOrNull
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
}
