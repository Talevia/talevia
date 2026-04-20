package io.talevia.cli.bootstrap

import io.talevia.cli.FileSecretStore
import io.talevia.core.provider.ProviderRegistry.SecretKeys
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
    if (hasAnyStoredKey(store)) return SecretBootstrapResult.Ready

    reader.printAbove("No LLM provider is configured.")
    reader.printAbove("Set one now and it will persist to ~/.talevia/secrets.properties.")
    val which = runCatching { reader.readLine("  provider [anthropic/openai/gemini, empty to skip]: ") }
        .getOrNull()?.trim()?.lowercase()
        .orEmpty()

    val key = when (which) {
        "anthropic", "a" -> SecretKeys.ANTHROPIC
        "openai", "o" -> SecretKeys.OPENAI
        "gemini", "g", "google" -> SecretKeys.GEMINI
        else -> return SecretBootstrapResult.Missing
    }
    val value = runCatching { reader.readLine("  api key (input hidden): ", '*') }
        .getOrNull()?.trim()
        .orEmpty()
    if (value.isBlank()) return SecretBootstrapResult.Missing
    store.put(key, value)
    reader.printAbove("✓ saved $key key")
    return SecretBootstrapResult.Ready
}

private fun hasAnyEnvKey(env: Map<String, String>): Boolean =
    listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY")
        .any { !env[it].isNullOrBlank() }

private suspend fun hasAnyStoredKey(store: FileSecretStore): Boolean =
    listOf(SecretKeys.ANTHROPIC, SecretKeys.OPENAI, SecretKeys.GEMINI, SecretKeys.GOOGLE)
        .any { !store.get(it).isNullOrBlank() }
