package io.talevia.core.session

/**
 * Schema-version discriminators for [Message] and [Part] JSON blobs.
 *
 * Forward-compatibility scaffold landed in 2026-04-21 (decision
 * `message-v2-schema-versioning.md`). Current value is `1` everywhere;
 * no live migration runs — the field exists so future refactors that
 * rename / reshape persisted fields have a discriminator to route on
 * without a separate `"schemaV2"` sibling type.
 *
 * **Migration convention** (for future selves):
 * 1. Leave [CURRENT] in this object pointing at whatever version the
 *    *persisted* blobs should decode as when the `schemaVersion` field
 *    is **absent** (= written by pre-versioning code). It started at 1
 *    and **should not move** — old blobs don't grow a field when the
 *    constant changes.
 * 2. New writes that require a schema bump should pass an explicit
 *    `schemaVersion = 2` (or higher) on construction. Because
 *    `JsonConfig.default.encodeDefaults = false`, the field will be
 *    encoded into JSON iff it differs from the data class's default —
 *    which is [CURRENT]=1. So v2+ blobs carry an explicit field,
 *    v1 / pre-versioning blobs don't.
 * 3. A decode-time dispatcher ("when the decoded `schemaVersion` is 1,
 *    apply migrator12; when 2, pass through; etc.") is out of scope
 *    for this cycle — it lands at the same time as the first real
 *    migration. For now the field is a hook, not an active route.
 *
 * Do NOT change [MessageSchema.CURRENT] / [PartSchema.CURRENT] to
 * anything other than 1 without writing a corresponding migrator —
 * bumping the default would invisibly re-tag all pre-existing
 * on-disk blobs as the new version.
 */
object MessageSchema {
    const val CURRENT: Int = 1
}

object PartSchema {
    const val CURRENT: Int = 1
}
