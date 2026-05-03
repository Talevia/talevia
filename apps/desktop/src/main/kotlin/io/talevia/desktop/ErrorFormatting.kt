package io.talevia.desktop

/**
 * Friendlier one-line error text for the activity log / chat — maps common
 * Kotlin / Ktor / IO exception flavours to terse, actionable strings that
 * don't leak stack-trace mechanics into the user's view.
 *
 * Not a wire-protocol: the actual exception + stack is still reachable via
 * the chat panel's tool-result JSON and the store's Part.Tool.Failed state
 * when deeper debugging is needed. This is purely a presentation layer
 * over `Throwable.message` so the activity log reads like a diary instead
 * of an IDE stacktrace.
 */
internal fun friendly(t: Throwable): String {
    val raw = (t.message ?: t::class.simpleName ?: "unknown error").trim()
    // Permission denials commonly surface as IllegalStateException with
    // "Permission denied: …" from Agent.kt. Keep those punchy.
    if (raw.startsWith("Permission denied")) return raw
    // Missing env-var / provider configuration — surface the hint, not the
    // raw ClassCastException / NullPointerException callers see on unwrap.
    if (raw.contains("OPENAI_API_KEY", ignoreCase = true) ||
        raw.contains("ANTHROPIC_API_KEY", ignoreCase = true) ||
        raw.contains("REPLICATE_API_TOKEN", ignoreCase = true)
    ) {
        return raw
    }
    // HTTP-ish errors from Ktor tend to be "HTTP <code> <body>" shapes; keep the
    // first line so the provider's own error message survives but layout doesn't.
    val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: raw
    return when (t) {
        // SerializationException must precede IllegalArgumentException
        // because kotlinx.serialization.SerializationException :
        // IllegalArgumentException — the more-specific arm needs to fire
        // first for "schema mismatch — ..." to surface. Cycle 89 found
        // this arm was dead code; fixed here.
        is kotlinx.serialization.SerializationException -> "schema mismatch — $firstLine"
        is IllegalArgumentException -> "bad input — $firstLine"
        is NoSuchElementException -> "not found — $firstLine"
        // FileNotFoundException + UnknownHostException must precede
        // IOException for the same reason — both extend IOException.
        is java.io.FileNotFoundException -> "file not found — $firstLine"
        is java.net.UnknownHostException -> "network — host not reachable ($firstLine)"
        is java.io.IOException -> "i/o — $firstLine"
        else -> firstLine
    }
}
