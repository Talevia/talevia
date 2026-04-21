package io.talevia.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvProviderAuthTest {

    private fun auth(vararg pairs: Pair<String, String?>): EnvProviderAuth {
        val map = pairs.toMap()
        return EnvProviderAuth({ name -> map[name] })
    }

    @Test fun presentKeyReportsPresentAndReturnsValue() {
        val a = auth("ANTHROPIC_API_KEY" to "sk-ant-abc123")
        assertEquals(AuthStatus.Present, a.authStatus("anthropic"))
        assertEquals("sk-ant-abc123", a.apiKey("anthropic"))
    }

    @Test fun missingKeyReportsMissing() {
        val a = auth()
        assertEquals(AuthStatus.Missing, a.authStatus("openai"))
        assertNull(a.apiKey("openai"))
    }

    @Test fun blankKeyReportsInvalid() {
        val a = auth("OPENAI_API_KEY" to "   ")
        val status = a.authStatus("openai")
        assertIs<AuthStatus.Invalid>(status)
        assertTrue(status.reason.contains("blank"), status.reason)
        assertNull(a.apiKey("openai"))
    }

    @Test fun whitespaceInsideKeyReportsInvalid() {
        val a = auth("OPENAI_API_KEY" to "sk-abc 123") // embedded space
        val status = a.authStatus("openai")
        assertIs<AuthStatus.Invalid>(status)
        assertTrue(status.reason.contains("whitespace"), status.reason)
        assertNull(a.apiKey("openai"))
    }

    @Test fun trailingNewlineReportsInvalid() {
        val a = auth("OPENAI_API_KEY" to "sk-valid\n")
        assertIs<AuthStatus.Invalid>(a.authStatus("openai"))
    }

    @Test fun unknownProviderReportsInvalid() {
        val a = auth()
        val status = a.authStatus("unicorn")
        assertIs<AuthStatus.Invalid>(status)
        assertTrue(status.reason.contains("unknown provider"), status.reason)
        assertNull(a.apiKey("unicorn"))
    }

    @Test fun googleHonoursBothAliases() {
        val withGemini = auth("GEMINI_API_KEY" to "gemini-key")
        assertEquals(AuthStatus.Present, withGemini.authStatus("google"))
        assertEquals("gemini-key", withGemini.apiKey("google"))

        val withGoogle = auth("GOOGLE_API_KEY" to "google-key")
        assertEquals(AuthStatus.Present, withGoogle.authStatus("google"))
        assertEquals("google-key", withGoogle.apiKey("google"))
    }

    @Test fun firstAliasWinsWhenBothSet() {
        val a = auth(
            "GEMINI_API_KEY" to "gemini-first",
            "GOOGLE_API_KEY" to "google-second",
        )
        // DEFAULT_ENV_VARS lists GEMINI_API_KEY before GOOGLE_API_KEY.
        assertEquals("gemini-first", a.apiKey("google"))
    }

    @Test fun blankFirstAliasFallsThroughToSecond() {
        val a = auth(
            "GEMINI_API_KEY" to "",
            "GOOGLE_API_KEY" to "google-fallback",
        )
        assertEquals(AuthStatus.Present, a.authStatus("google"))
        assertEquals("google-fallback", a.apiKey("google"))
    }

    @Test fun providerIdsListsDefaults() {
        val a = auth()
        val ids = a.providerIds.toSet()
        assertTrue("anthropic" in ids, ids.toString())
        assertTrue("openai" in ids, ids.toString())
        assertTrue("google" in ids, ids.toString())
        assertTrue("replicate" in ids, ids.toString())
    }

    @Test fun customEnvVarsMapOverridesDefault() {
        val auth = EnvProviderAuth(
            envLookup = { name -> if (name == "MY_VENDOR_KEY") "vendor-key" else null },
            envVars = mapOf("vendor" to listOf("MY_VENDOR_KEY")),
        )
        assertEquals(AuthStatus.Present, auth.authStatus("vendor"))
        assertEquals(listOf("vendor"), auth.providerIds)
        // Defaults are gone under a custom map.
        assertIs<AuthStatus.Invalid>(auth.authStatus("anthropic"))
    }
}
