package io.talevia.core.provider.openai.codex

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE + state generator for the OAuth authorization-code flow. Mirrors Codex
 * CLI conventions: 64-byte verifier (~86 base64url-nopad chars), S256 challenge,
 * 32-byte state.
 */
internal object Pkce {
    private val RANDOM = SecureRandom()
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()

    data class Pair(val verifier: String, val challenge: String, val method: String = "S256")

    fun generatePair(): Pair {
        val verifierBytes = ByteArray(64).also { RANDOM.nextBytes(it) }
        val verifier = ENCODER.encodeToString(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = ENCODER.encodeToString(digest)
        return Pair(verifier = verifier, challenge = challenge)
    }

    fun generateState(): String {
        val bytes = ByteArray(32).also { RANDOM.nextBytes(it) }
        return ENCODER.encodeToString(bytes)
    }
}
