package io.talevia.core.tool.builtin.project

/**
 * Default-id derivation for `project_action(kind="lifecycle", args={action="create"})` and friends.
 *
 * "Graduation Vlog 2026" → `proj-graduation-vlog-2026`. Stable + reversible
 * enough that the LLM rarely needs to invent its own id.
 *
 * **Restricted to ASCII alphanumerics on purpose**: the resulting id ends up
 * as a default directory name in [io.talevia.core.domain.FileProjectStore],
 * which validates against `[A-Za-z0-9_.-]{1,200}`. `Char.isLetterOrDigit()`
 * is true for CJK / Cyrillic / accented letters, which would slip past slug
 * sanitisation and then crash at the bundle-path step. Returning `null` for
 * "no usable ASCII" (CJK-only titles, all-symbol titles like `***`) lets the
 * caller fall through to a UUID-derived id rather than die mid-dispatch.
 */
internal fun slugifyProjectId(title: String): String? {
    val sanitised = buildString(title.length) {
        var lastWasSep = true
        for (raw in title.lowercase()) {
            if (raw in 'a'..'z' || raw in '0'..'9') {
                append(raw)
                lastWasSep = false
            } else if (!lastWasSep) {
                append('-')
                lastWasSep = true
            }
        }
    }.trimEnd('-')
    if (sanitised.isEmpty()) return null
    return "proj-$sanitised"
}

/**
 * Resolve a project id for a `default-home` create path: prefer an explicit
 * caller-supplied id, then a title-derived slug, finally a fresh UUID. The
 * UUID branch matches what [io.talevia.core.domain.FileProjectStore]'s
 * `createAt` mints internally, so default-home and explicit-path creates
 * produce the same id shape for unslug-able titles (CJK, all-symbol, …).
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal fun resolveDefaultHomeProjectId(explicitId: String?, title: String): String {
    val explicit = explicitId?.takeIf { it.isNotBlank() }
    if (explicit != null) return explicit
    return slugifyProjectId(title) ?: kotlin.uuid.Uuid.random().toString()
}
