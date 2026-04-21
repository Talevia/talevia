package io.talevia.core.provider

import kotlinx.serialization.Serializable

/**
 * Status of credentials for an LLM / AIGC provider. Three-way (not binary)
 * on purpose — "provided but malformed" (`Invalid`) is a distinct state
 * from "not provided" (`Missing`) because the UI advice diverges: an
 * unset env var prompts the user to "add your key", a whitespace-padded
 * one prompts "fix your existing key".
 *
 * See `docs/decisions/2026-04-21-provider-auth-state.md`.
 */
@Serializable
sealed class AuthStatus {
    /** Credential is present and looks well-formed. */
    @Serializable
    data object Present : AuthStatus()

    /** No credential provided for this provider. */
    @Serializable
    data object Missing : AuthStatus()

    /**
     * A credential was provided but failed a cheap sanity check (e.g. it
     * contains whitespace, which no real API key does). Not a promise the
     * key is valid against the remote service — that only the provider
     * itself can know on first call.
     */
    @Serializable
    data class Invalid(val reason: String) : AuthStatus()
}

/**
 * Central, platform-neutral auth status lookup for providers. Inspired by
 * OpenCode's `packages/opencode/src/provider/auth.ts` — **behavior only**,
 * not its Effect.js structure.
 *
 * Replaces scattered `env["OPENAI_API_KEY"]?.takeIf(String::isNotBlank)`
 * calls across the 5 `AppContainer`s + the iOS bridge. Containers now ask
 * "does this provider have a usable key?" through a single interface, and
 * UI code can enumerate [providerIds] to render "missing X, missing Y"
 * setup prompts without guessing which env vars to look at.
 */
interface ProviderAuth {
    /** Fast status check for [providerId]. Unknown providers return [AuthStatus.Invalid]. */
    fun authStatus(providerId: String): AuthStatus

    /**
     * Returns the credential string when [authStatus] is [AuthStatus.Present],
     * otherwise `null`. Never returns a blank or whitespace-padded string —
     * the same sanity check that drives [authStatus] is applied here.
     */
    fun apiKey(providerId: String): String?

    /** Every provider id this auth source is configured to serve. Stable order. */
    val providerIds: List<String>
}
