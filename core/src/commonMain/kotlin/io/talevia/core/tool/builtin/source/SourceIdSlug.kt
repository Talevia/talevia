package io.talevia.core.tool.builtin.source

/**
 * Lowercase a name into a stable id slug — letters / digits / `-` only, with a
 * type-prefix so different node kinds with the same display name don't collide.
 *
 * Used as the default `nodeId` for the `define_*` tools when the LLM omits one.
 * Deterministic in both directions: re-defining "Mei" → `character-mei` → idempotent
 * replacement, which is the behaviour the lockfile / DAG lanes expect.
 */
internal fun slugifyId(name: String, prefix: String): String {
    val sanitised = buildString(name.length) {
        var lastWasSep = true
        for (raw in name.lowercase()) {
            val ok = raw.isLetterOrDigit()
            if (ok) {
                append(raw)
                lastWasSep = false
            } else if (!lastWasSep) {
                append('-')
                lastWasSep = true
            }
        }
    }.trimEnd('-')
    val core = sanitised.ifEmpty { "node" }
    return "$prefix-$core"
}
