package io.talevia.core.permission

/**
 * Top-level alias for the permission round-trip row format
 * [PermissionHistoryRecorder.Entry] uses.
 *
 * Why an alias rather than the type directly: `SessionStore` (in the
 * `session` package) carries this shape across the
 * persist-to-SQL / hydrate-from-SQL boundary without needing to
 * import the recorder class. Tests + production code can use either
 * name interchangeably; new code should prefer
 * `PermissionDecisionRow` since the recorder's `Entry` reads as a
 * recorder-private detail.
 */
typealias PermissionDecisionRow = PermissionHistoryRecorder.Entry
