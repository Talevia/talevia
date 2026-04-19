package io.talevia.core.tool.builtin.project

/**
 * Default-id derivation for `create_project`. Same shape as
 * [io.talevia.core.tool.builtin.source.slugifyId] but with a `proj-` prefix so
 * project ids are visually distinct from source-node ids in logs / diagnostics.
 *
 * "Graduation Vlog 2026" → `proj-graduation-vlog-2026`. Stable + reversible enough
 * that the LLM rarely needs to invent its own id.
 */
internal fun slugifyProjectId(title: String): String {
    val sanitised = buildString(title.length) {
        var lastWasSep = true
        for (raw in title.lowercase()) {
            if (raw.isLetterOrDigit()) {
                append(raw)
                lastWasSep = false
            } else if (!lastWasSep) {
                append('-')
                lastWasSep = true
            }
        }
    }.trimEnd('-')
    val core = sanitised.ifEmpty { "untitled" }
    return "proj-$core"
}
