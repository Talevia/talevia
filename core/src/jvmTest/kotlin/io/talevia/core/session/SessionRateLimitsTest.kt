package io.talevia.core.session

import io.talevia.core.JsonConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Placeholder-class tests — the `SessionRateLimits` debt record landed in
 * 2026-04-21 without live enforcement. These tests pin the API surface the
 * future enforcement cycle will consume so it can't accidentally rename /
 * reshape the contract before the debt is paid.
 */
class SessionRateLimitsTest {

    @Test fun defaultsAreAllNull() {
        val rl = SessionRateLimits()
        assertNull(rl.maxCostPerSessionUsd)
        assertNull(rl.maxCallsPerMinute)
        assertNull(rl.maxTotalCalls)
        assertTrue(rl.isUnlimited)
    }

    @Test fun unlimitedConstantEqualsDefault() {
        assertEquals(SessionRateLimits(), SessionRateLimits.UNLIMITED)
        assertTrue(SessionRateLimits.UNLIMITED.isUnlimited)
    }

    @Test fun anySetCapFlipsIsUnlimitedFalse() {
        assertFalse(SessionRateLimits(maxCostPerSessionUsd = 10.0).isUnlimited)
        assertFalse(SessionRateLimits(maxCallsPerMinute = 5).isUnlimited)
        assertFalse(SessionRateLimits(maxTotalCalls = 100).isUnlimited)
    }

    @Test fun defaultEncodedJsonIsEmptyObject() {
        // JsonConfig has `encodeDefaults = false`; every field is null
        // by default → emit `{}`. Keeps on-disk / wire shape minimal so
        // adding the class now costs nothing in persisted bytes.
        val json = JsonConfig.default.encodeToString(SessionRateLimits.serializer(), SessionRateLimits())
        assertEquals("{}", json)
    }

    @Test fun fullConfigRoundTripsThroughJson() {
        val seeded = SessionRateLimits(
            maxCostPerSessionUsd = 25.0,
            maxCallsPerMinute = 12,
            maxTotalCalls = 500,
        )
        val json = JsonConfig.default.encodeToString(SessionRateLimits.serializer(), seeded)
        assertTrue(json.contains("\"maxCostPerSessionUsd\":25.0"), json)
        assertTrue(json.contains("\"maxCallsPerMinute\":12"), json)
        assertTrue(json.contains("\"maxTotalCalls\":500"), json)
        val decoded = JsonConfig.default.decodeFromString(SessionRateLimits.serializer(), json)
        assertEquals(seeded, decoded)
    }

    @Test fun legacyBlobWithoutFieldsDecodesToUnlimited() {
        // Future SessionStore blobs that don't yet carry rate limits
        // decode to UNLIMITED via the nullable defaults.
        val decoded = JsonConfig.default.decodeFromString(SessionRateLimits.serializer(), "{}")
        assertEquals(SessionRateLimits.UNLIMITED, decoded)
    }
}
