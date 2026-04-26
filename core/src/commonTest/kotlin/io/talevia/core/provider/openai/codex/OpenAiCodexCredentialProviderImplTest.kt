package io.talevia.core.provider.openai.codex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class OpenAiCodexCredentialProviderImplTest {

    @Test
    fun returnsCachedWhenNotStale() = runTest {
        val now = Instant.fromEpochMilliseconds(2_000_000L)
        val store = InMemoryStore(creds(expEpochSec = now.toEpochMilliseconds() / 1000 + 3600, lastRefreshMs = now.toEpochMilliseconds() - 1000))
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { fail("refresh must not run when cached creds are fresh") }
            }
        }
        val provider = OpenAiCodexCredentialProviderImpl(client, store, FixedClock(now))

        val out = provider.current()
        assertEquals(store.snapshot()!!, out)
    }

    @Test
    fun refreshesWhenAccessTokenExpired() = runTest {
        val now = Instant.fromEpochMilliseconds(2_000_000L)
        val store = InMemoryStore(creds(expEpochSec = now.toEpochMilliseconds() / 1000 - 60, lastRefreshMs = now.toEpochMilliseconds() - 10_000))
        var refreshCalls = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    refreshCalls++
                    respond(
                        content = ByteReadChannel(
                            """{"access_token":"${jwt(9_999_999_999L, "acct-rotated")}","id_token":"${jwt(9_999_999_999L, "acct-rotated")}","refresh_token":"new-refresh"}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val provider = OpenAiCodexCredentialProviderImpl(client, store, FixedClock(now))

        val rotated = provider.current()
        assertEquals(1, refreshCalls)
        assertEquals("new-refresh", rotated.refreshToken)
        assertEquals("acct-rotated", rotated.accountId)
        assertEquals(now.toEpochMilliseconds(), rotated.lastRefreshEpochMs)
        assertEquals(rotated, store.snapshot())
    }

    @Test
    fun refreshesWhenLastRefreshOlderThan8Days() = runTest {
        val now = Instant.fromEpochMilliseconds(1_000_000_000_000L)
        val nineDaysAgoMs = now.toEpochMilliseconds() - 9L * 24 * 60 * 60 * 1000
        val store = InMemoryStore(creds(expEpochSec = now.toEpochMilliseconds() / 1000 + 3600, lastRefreshMs = nineDaysAgoMs))
        var calls = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    calls++
                    respond(
                        content = ByteReadChannel(
                            """{"access_token":"${jwt(9_999_999_999L, "acct-x")}","id_token":"${jwt(9_999_999_999L, "acct-x")}","refresh_token":"r2"}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val provider = OpenAiCodexCredentialProviderImpl(client, store, FixedClock(now))

        provider.current()
        assertEquals(1, calls)
    }

    @Test
    fun permanentFailureRaisesAuthExpired() = runTest {
        val now = Instant.fromEpochMilliseconds(2_000_000L)
        val store = InMemoryStore(creds(expEpochSec = now.toEpochMilliseconds() / 1000 - 1, lastRefreshMs = 0L))
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel("""{"error":"refresh_token_expired"}"""),
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val provider = OpenAiCodexCredentialProviderImpl(client, store, FixedClock(now))

        val ex = assertFailsWith<OpenAiCodexAuthExpired> { provider.current() }
        assertTrue(ex.message!!.contains("refresh_token_expired"))
    }

    @Test
    fun nestedErrorCodeShapeIsAlsoHandled() = runTest {
        val now = Instant.fromEpochMilliseconds(2_000_000L)
        val store = InMemoryStore(creds(expEpochSec = now.toEpochMilliseconds() / 1000 - 1, lastRefreshMs = 0L))
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel("""{"error":{"code":"refresh_token_reused"}}"""),
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val provider = OpenAiCodexCredentialProviderImpl(client, store, FixedClock(now))
        val ex = assertFailsWith<OpenAiCodexAuthExpired> { provider.refresh() }
        assertTrue(ex.message!!.contains("refresh_token_reused"))
    }

    @Test
    fun missingCredentialsRaisesNotSignedIn() = runTest {
        val client = HttpClient(MockEngine) { engine { addHandler { fail("must not be called") } } }
        val provider = OpenAiCodexCredentialProviderImpl(client, InMemoryStore(null), FixedClock(Instant.fromEpochMilliseconds(0)))
        assertFails { provider.current() }
    }

    @Test
    fun transientServerErrorIsRethrown() = runTest {
        val now = Instant.fromEpochMilliseconds(2_000_000L)
        val store = InMemoryStore(creds(expEpochSec = now.toEpochMilliseconds() / 1000 - 1, lastRefreshMs = 0L))
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel("""{"error":"server_unavailable"}"""),
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val provider = OpenAiCodexCredentialProviderImpl(client, store, FixedClock(now))
        val ex = assertFailsWith<RuntimeException> { provider.current() }
        // Specifically NOT OpenAiCodexAuthExpired — that's only for permanent failures.
        assertTrue(ex !is OpenAiCodexAuthExpired)
        assertTrue(ex.message!!.contains("503"))
    }

    private fun creds(expEpochSec: Long, lastRefreshMs: Long): OpenAiCodexCredentials =
        OpenAiCodexCredentials(
            accessToken = jwt(expEpochSec, "acct-1"),
            refreshToken = "refresh-1",
            idToken = jwt(expEpochSec, "acct-1"),
            accountId = "acct-1",
            lastRefreshEpochMs = lastRefreshMs,
        )

    private fun jwt(expEpochSec: Long, accountId: String): String {
        val payload = """{"exp":$expEpochSec,"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""
        val b64 = Base64Url.encode(payload.encodeToByteArray())
        val header = Base64Url.encode("""{"alg":"RS256"}""".encodeToByteArray())
        return "$header.$b64.sig"
    }

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private class InMemoryStore(initial: OpenAiCodexCredentials?) : OpenAiCodexCredentialStore {
        private var current: OpenAiCodexCredentials? = initial
        override suspend fun load() = current
        override suspend fun save(creds: OpenAiCodexCredentials) { current = creds }
        override suspend fun clear() { current = null }
        fun snapshot() = current
    }

    /** Minimal base64url-nopad encoder for fixtures (commonTest can't see java.util.Base64). */
    private object Base64Url {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        fun encode(bytes: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i + 3 <= bytes.size) {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or
                    ((bytes[i + 1].toInt() and 0xff) shl 8) or
                    (bytes[i + 2].toInt() and 0xff)
                sb.append(ALPHABET[(n ushr 18) and 0x3f])
                sb.append(ALPHABET[(n ushr 12) and 0x3f])
                sb.append(ALPHABET[(n ushr 6) and 0x3f])
                sb.append(ALPHABET[n and 0x3f])
                i += 3
            }
            when (bytes.size - i) {
                1 -> {
                    val n = (bytes[i].toInt() and 0xff) shl 16
                    sb.append(ALPHABET[(n ushr 18) and 0x3f])
                    sb.append(ALPHABET[(n ushr 12) and 0x3f])
                }
                2 -> {
                    val n = ((bytes[i].toInt() and 0xff) shl 16) or
                        ((bytes[i + 1].toInt() and 0xff) shl 8)
                    sb.append(ALPHABET[(n ushr 18) and 0x3f])
                    sb.append(ALPHABET[(n ushr 12) and 0x3f])
                    sb.append(ALPHABET[(n ushr 6) and 0x3f])
                }
            }
            return sb.toString()
        }
    }
}
