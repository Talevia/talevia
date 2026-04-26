package io.talevia.core.provider.openai.codex

/**
 * Constants for the OpenAI Codex OAuth + ChatGPT Responses API integration.
 * Values mirror the ones embedded in the official Codex CLI
 * (`openai/codex` Rust workspace) so we slot into the same backend
 * authorization flow.
 */
internal object OpenAiCodexConstants {
    /**
     * Public OAuth client id used by the official Codex CLI. Hardcoded — the
     * ChatGPT backend recognises it together with [ORIGINATOR] as a trusted
     * caller. Substituting a different value is rejected at /oauth/authorize.
     */
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    /**
     * `originator` header / query value. Codex CLI uses `codex_cli_rs`. The
     * ChatGPT backend whitelists a small set of first-party originators; using
     * an arbitrary value here will result in 403s from the Responses API.
     */
    const val ORIGINATOR = "codex_cli_rs"

    /** OAuth issuer base URL. */
    const val AUTH_BASE = "https://auth.openai.com"
    const val AUTHORIZE_URL = "$AUTH_BASE/oauth/authorize"
    const val TOKEN_URL = "$AUTH_BASE/oauth/token"

    /** ChatGPT-backend Responses API root. */
    const val RESPONSES_BASE = "https://chatgpt.com/backend-api/codex"
    const val RESPONSES_ENDPOINT = "$RESPONSES_BASE/responses"

    /** OAuth scopes — match the Codex CLI exactly. */
    const val SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"

    /** Default loopback port for the OAuth callback server (mirrors Codex CLI). */
    const val DEFAULT_LOOPBACK_PORT = 1455

    /** Loopback callback path. */
    const val CALLBACK_PATH = "/auth/callback"

    /**
     * Proactive refresh threshold — refresh credentials when their last refresh
     * was more than this long ago, even if the access token's `exp` claim is
     * still in the future. Matches Codex CLI's `TOKEN_REFRESH_INTERVAL`.
     */
    const val PROACTIVE_REFRESH_THRESHOLD_DAYS = 8L
}
