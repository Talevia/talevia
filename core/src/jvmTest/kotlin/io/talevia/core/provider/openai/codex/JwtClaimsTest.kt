package io.talevia.core.provider.openai.codex

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwtClaimsTest {

    @Test
    fun extractsChatgptAccountIdAndExp() {
        val payload = """
            {"exp":1735689600,"https://api.openai.com/auth":{"chatgpt_account_id":"acct-test-12345","chatgpt_plan_type":"plus"},"email":"u@example.com"}
        """.trimIndent()
        val jwt = fakeJwt(payload)

        assertEquals("acct-test-12345", JwtClaims.chatgptAccountId(jwt))
        assertEquals(1735689600L, JwtClaims.exp(jwt))
    }

    @Test
    fun returnsNullWhenAuthClaimMissing() {
        val payload = """{"exp":1735689600,"email":"u@example.com"}"""
        assertNull(JwtClaims.chatgptAccountId(fakeJwt(payload)))
    }

    @Test
    fun returnsNullForMalformedJwt() {
        assertNull(JwtClaims.chatgptAccountId("not-a-jwt"))
        assertNull(JwtClaims.chatgptAccountId(""))
        assertNull(JwtClaims.exp("a.b"))
    }

    @Test
    fun toleratesMissingPaddingInPayload() {
        // base64url-nopad input: Codex CLI emits these without padding.
        val payload = """{"exp":1,"https://api.openai.com/auth":{"chatgpt_account_id":"x"}}"""
        val noPadHeader = "eyJhbGciOiJSUzI1NiJ9" // {"alg":"RS256"}
        val noPadPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val jwt = "$noPadHeader.$noPadPayload.signature"
        assertEquals("x", JwtClaims.chatgptAccountId(jwt))
        assertEquals(1L, JwtClaims.exp(jwt))
    }

    private fun fakeJwt(payloadJson: String): String {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.toByteArray())
        return "$header.$payload.placeholder-signature"
    }
}
