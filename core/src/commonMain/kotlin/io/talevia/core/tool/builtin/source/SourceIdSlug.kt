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

/**
 * Return `true` iff [id] is a well-formed source-node id slug: non-blank, ASCII lowercase
 * letters / digits / `-` only, starting + ending with an alphanumeric character. Matches
 * the shape [slugifyId] produces so ids minted by `define_*` tools round-trip through
 * this check. Permissive enough for hand-authored ids (`shot-1`, `scene-a`, `mei`)
 * without admitting whitespace, underscores, path separators, or empty strings.
 *
 * Used by [RenameSourceNodeTool] to reject pathological `newId` values before mutating —
 * the `define_*` tools never see a bad id because they slugify their own input, so no
 * retroactive backfill is needed there.
 */
internal fun isValidSourceNodeIdSlug(id: String): Boolean {
    if (id.isBlank()) return false
    if (id.startsWith('-') || id.endsWith('-')) return false
    for (c in id) {
        val ok = (c in 'a'..'z') || (c in '0'..'9') || c == '-'
        if (!ok) return false
    }
    return true
}
