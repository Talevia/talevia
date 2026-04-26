package io.talevia.cli.bootstrap

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.cli.FileSecretStore
import io.talevia.cli.repl.Styles
import io.talevia.core.provider.ProviderRegistry.SecretKeys
import io.talevia.core.provider.openai.codex.FileOpenAiCodexCredentialStore
import io.talevia.core.provider.openai.codex.JvmOpenAiCodexAuthenticator
import org.jline.reader.LineReader

/**
 * Return value of [ensureProviderKey]. [Ready] means the next
 * [io.talevia.cli.CliContainer] construction will find at least one provider
 * key via env var or secret store; [Missing] means the user opted out of
 * configuring one and the CLI should exit.
 */
sealed interface SecretBootstrapResult {
    data object Ready : SecretBootstrapResult
    data object Missing : SecretBootstrapResult
}

/**
 * Before we construct the container (which reads the secret store once at
 * startup), check if any LLM provider key is reachable. If none is, interactively
 * prompt the user for one and persist it to [FileSecretStore] so the subsequent
 * `ProviderRegistry.Builder().addSecretStore(...)` picks it up.
 *
 * Env vars checked match `ProviderRegistry.Builder.addEnv`: `ANTHROPIC_API_KEY`,
 * `OPENAI_API_KEY`, `GEMINI_API_KEY`, `GOOGLE_API_KEY`.
 */
suspend fun ensureProviderKey(env: Map<String, String>, reader: LineReader): SecretBootstrapResult {
    if (hasAnyEnvKey(env)) return SecretBootstrapResult.Ready
    val store = FileSecretStore()
    val codexStore = FileOpenAiCodexCredentialStore()
    if (hasAnyStoredKey(store) || codexStore.load() != null) return SecretBootstrapResult.Ready

    reader.printAbove(Styles.warn("No LLM provider is configured."))
    reader.printAbove(Styles.meta("Pick one now — API keys land in ~/.talevia/secrets.properties; OAuth (codex) in openai-codex-auth.json."))
    val which = runCatching {
        reader.readLine(Styles.prompt("  provider [anthropic/openai/gemini/codex, empty to skip]: "))
    }.getOrNull()?.trim()?.lowercase()
        .orEmpty()

    return when (which) {
        "anthropic", "a" -> promptForApiKey(reader, store, SecretKeys.ANTHROPIC)
        "openai", "o" -> promptForApiKey(reader, store, SecretKeys.OPENAI)
        "gemini", "g", "google" -> promptForApiKey(reader, store, SecretKeys.GEMINI)
        "codex", "openai-codex", "c" -> launchCodexLogin(reader, codexStore)
        else -> SecretBootstrapResult.Missing
    }
}

private suspend fun promptForApiKey(
    reader: LineReader,
    store: FileSecretStore,
    key: String,
): SecretBootstrapResult {
    val value = runCatching { reader.readLine(Styles.prompt("  api key (input hidden): "), '*') }
        .getOrNull()?.trim()
        .orEmpty()
    if (value.isBlank()) return SecretBootstrapResult.Missing
    store.put(key, value)
    reader.printAbove("${Styles.ok("✓")} saved $key key")
    return SecretBootstrapResult.Ready
}

private suspend fun launchCodexLogin(
    reader: LineReader,
    codexStore: FileOpenAiCodexCredentialStore,
): SecretBootstrapResult {
    reader.printAbove(Styles.meta("opening browser for ChatGPT sign-in… (waiting for callback on 127.0.0.1:1455)"))
    // Use a one-shot HTTP client so we don't hold connections open after this
    // bootstrap call; the main CLI HttpClient will be created later by the container.
    val httpClient = HttpClient(CIO)
    return try {
        val authenticator = JvmOpenAiCodexAuthenticator(
            httpClient = httpClient,
            onPrompt = { url -> System.err.println("Open this URL in your browser: $url") },
        )
        val creds = authenticator.login()
        codexStore.save(creds)
        reader.printAbove("${Styles.ok("✓")} signed in to openai-codex")
        SecretBootstrapResult.Ready
    } catch (e: Exception) {
        reader.printAbove(Styles.warn("login failed: ${e.message ?: e::class.simpleName}"))
        SecretBootstrapResult.Missing
    } finally {
        runCatching { httpClient.close() }
    }
}

private fun hasAnyEnvKey(env: Map<String, String>): Boolean =
    listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY")
        .any { !env[it].isNullOrBlank() }

private suspend fun hasAnyStoredKey(store: FileSecretStore): Boolean =
    listOf(SecretKeys.ANTHROPIC, SecretKeys.OPENAI, SecretKeys.GEMINI, SecretKeys.GOOGLE)
        .any { !store.get(it).isNullOrBlank() }
