package io.talevia.core.provider

/**
 * Parse a pair of `retry-after-ms` / `retry-after` HTTP response headers into
 * a single millisecond value. Prefers the explicit `-ms` form; falls back to
 * seconds. Returns `null` when neither header is usable.
 *
 * Shared across every provider so the Agent's retry policy sees consistent
 * hints regardless of which backend produced the error.
 */
fun parseRetryAfterMs(ms: String?, seconds: String?): Long? {
    ms?.trim()?.toDoubleOrNull()?.let { if (it > 0) return it.toLong() }
    seconds?.trim()?.toDoubleOrNull()?.let { if (it > 0) return (it * 1000.0).toLong() }
    return null
}
