package io.talevia.core.provider.openai.codex

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {

    @Test
    fun verifierIs86CharsBase64UrlNoPad() {
        val pair = Pkce.generatePair()
        assertEquals(86, pair.verifier.length, "64-byte verifier base64url-nopad-encodes to 86 chars")
        assertTrue(pair.verifier.all { it.isBase64UrlChar() }, "verifier must use base64url alphabet")
        assertTrue('=' !in pair.verifier, "verifier must not be padded")
    }

    @Test
    fun challengeIsSha256OfVerifier() {
        val pair = Pkce.generatePair()
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pair.verifier.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(expected, pair.challenge)
        assertEquals("S256", pair.method)
    }

    @Test
    fun verifierIsUnique() {
        val one = Pkce.generatePair()
        val two = Pkce.generatePair()
        assertNotEquals(one.verifier, two.verifier)
        assertNotEquals(one.challenge, two.challenge)
    }

    @Test
    fun stateIs43CharsBase64UrlNoPad() {
        val state = Pkce.generateState()
        // 32 random bytes → 43 base64url-nopad chars
        assertEquals(43, state.length)
        assertTrue(state.all { it.isBase64UrlChar() })
        assertTrue('=' !in state)
    }

    @Test
    fun stateIsUnique() {
        assertNotEquals(Pkce.generateState(), Pkce.generateState())
    }

    private fun Char.isBase64UrlChar(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_'
}
