package io.talevia.core.provider.openai.codex

import kotlinx.serialization.Serializable

/**
 * OAuth credentials minted by [OpenAiCodexAuthenticator] when the user signs in
 * with their ChatGPT account. Persisted by [OpenAiCodexCredentialStore], rotated
 * by [OpenAiCodexCredentialProvider].
 *
 * Field names mirror the on-disk shape used by the official Codex CLI
 * (`~/.codex/auth.json`) so credentials can be diffed across implementations
 * during debugging — Talevia stores them at `~/.talevia/openai-codex-auth.json`.
 */
@Serializable
data class OpenAiCodexCredentials(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    /**
     * `chatgpt_account_id` claim from the id_token's `https://api.openai.com/auth`
     * object. Sent on every Responses API request as the `ChatGPT-Account-ID`
     * header — the ChatGPT backend rejects requests without it.
     */
    val accountId: String,
    val lastRefreshEpochMs: Long,
)

interface OpenAiCodexCredentialStore {
    suspend fun load(): OpenAiCodexCredentials?
    suspend fun save(creds: OpenAiCodexCredentials)
    suspend fun clear()
}

/**
 * Issues an access token for the next outbound API call. Implementations are
 * responsible for proactive (exp / 8-day) and reactive (401-on-API) refreshes;
 * callers should treat the returned credentials as point-in-time valid.
 */
interface OpenAiCodexCredentialProvider {
    /** Returns valid credentials, refreshing first if needed. Throws if the user is not signed in. */
    suspend fun current(): OpenAiCodexCredentials

    /** Force a refresh round-trip and persist the result. */
    suspend fun refresh(): OpenAiCodexCredentials
}

/** Run the OAuth login flow. JVM impl is [io.talevia.core.provider.openai.codex.JvmOpenAiCodexAuthenticator]. */
interface OpenAiCodexAuthenticator {
    suspend fun login(): OpenAiCodexCredentials
}

/** Thrown when the refresh token is permanently unusable (expired/reused/invalidated). User must re-login. */
class OpenAiCodexAuthExpired(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Thrown when no credentials are stored (user has not run /login). */
class OpenAiCodexNotSignedIn(message: String = "no openai-codex credentials on disk; run /login openai-codex") :
    RuntimeException(message)
