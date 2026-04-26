package io.talevia.core.provider

/**
 * Env-variable-backed [ProviderAuth]. Each provider id maps to a list of
 * env var names tried in declaration order (first non-blank, non-
 * whitespace wins). Several providers accept multiple alias env vars in
 * the wild (Gemini honours both `GEMINI_API_KEY` and `GOOGLE_API_KEY`);
 * the list lets callers express that without special-casing.
 *
 * [envLookup] is the injection point: JVM containers pass
 * `System::getenv`, Android can stack `System.getProperty` over
 * `System.getenv`, iOS passes a wrapper around
 * `NSProcessInfo.processInfo.environment`. Testing is trivial — hand in
 * an in-memory `Map::get`.
 *
 * Kept in `commonMain` with zero platform imports (CLAUDE.md red line).
 */
class EnvProviderAuth(
    private val envLookup: (String) -> String?,
    private val envVars: Map<String, List<String>> = DEFAULT_ENV_VARS,
) : ProviderAuth {

    override val providerIds: List<String>
        get() = envVars.keys.toList()

    override fun authStatus(providerId: String): AuthStatus {
        val candidates = envVars[providerId]
            ?: return AuthStatus.Invalid("unknown provider '$providerId'")
        var sawBlankAlias = false
        for (name in candidates) {
            val raw = envLookup(name) ?: continue
            if (raw.isBlank()) {
                sawBlankAlias = true
                continue
            }
            if (raw.any { it.isWhitespace() }) {
                // Whitespace inside a non-blank key is almost always a copy-paste
                // bug (trailing newline from `echo $KEY > file`). Flag it.
                return AuthStatus.Invalid("$name contains whitespace — likely malformed")
            }
            return AuthStatus.Present
        }
        // No candidate yielded a usable value. If any was present but blank,
        // the user attempted to configure something — report Invalid with a
        // pointer; otherwise nothing was set.
        return if (sawBlankAlias) {
            AuthStatus.Invalid("${candidates.joinToString(", ")} set but blank")
        } else {
            AuthStatus.Missing
        }
    }

    override fun apiKey(providerId: String): String? {
        val candidates = envVars[providerId] ?: return null
        for (name in candidates) {
            val raw = envLookup(name) ?: continue
            if (raw.isBlank()) continue
            if (raw.any { it.isWhitespace() }) return null
            return raw
        }
        return null
    }

    companion object {
        /**
         * Default env var mappings for the provider ids currently in use.
         * Matches the hand-rolled lookups that used to be duplicated across
         * CLI / Desktop / Server / Android / iOS composition roots.
         */
        val DEFAULT_ENV_VARS: Map<String, List<String>> = mapOf(
            "anthropic" to listOf("ANTHROPIC_API_KEY"),
            "openai" to listOf("OPENAI_API_KEY"),
            "google" to listOf("GEMINI_API_KEY", "GOOGLE_API_KEY"),
            "replicate" to listOf("REPLICATE_API_TOKEN"),
            "tavily" to listOf("TAVILY_API_KEY"),
            "volcano" to listOf("ARK_API_KEY"),
        )
    }
}
