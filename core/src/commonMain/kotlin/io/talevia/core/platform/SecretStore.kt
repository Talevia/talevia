package io.talevia.core.platform

/**
 * Cross-platform secret storage for provider API keys, OAuth tokens, and
 * similar credentials. Each platform supplies a backend:
 *  - iOS: Keychain (SecItem…)
 *  - Android: EncryptedSharedPreferences backed by Android Keystore
 *  - Desktop: keychain bridges (macOS `security`, Windows Credential Manager,
 *    libsecret on Linux) with a fallback to a user-config file
 *  - Server: process-env backed; writes are rejected (operators manage secrets
 *    out-of-band)
 *
 * Keys are opaque strings; the Core's convention is `providerId` (e.g.
 * `anthropic`, `openai`) → the provider's API key. Downstream features may
 * namespace freely.
 */
interface SecretStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
    suspend fun keys(): Set<String>
}

/**
 * In-memory [SecretStore] seeded from a read-only map — useful for tests and
 * the server (where secrets live in env vars and writes should be refused).
 * Writes mutate the internal map in process memory only and are lost on exit.
 */
class InMemorySecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {
    private val entries: MutableMap<String, String> = initial.toMutableMap()

    override suspend fun get(key: String): String? = entries[key]
    override suspend fun put(key: String, value: String) { entries[key] = value }
    override suspend fun remove(key: String) { entries.remove(key) }
    override suspend fun keys(): Set<String> = entries.keys.toSet()
}
