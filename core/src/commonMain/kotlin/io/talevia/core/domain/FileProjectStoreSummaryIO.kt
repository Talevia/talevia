package io.talevia.core.domain

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Shared cache-first `ProjectSummary` resolver used by both
 * [FileProjectStore.summary] and [FileProjectStore.listSummaries].
 *
 * Fast path: when the [RecentsEntry.createdAtEpochMs] cache is warm
 * (populated by `openAt` / `createAt` / `upsert`), the bundle's
 * `talevia.json` is **not decoded** — we only touch the file's mtime
 * for the `updatedAtEpochMs` field. Skipping the JSON parse saves
 * 5–30 ms per project on warm registries; the 1000-bundle benchmark
 * at cycle-42 (36 ms total) depended on this fast path.
 *
 * Slow path: when the registry cache is zero (legacy `recents.json`
 * from before the createdAtEpochMs field, or an entry mutated through
 * a path that didn't thread createdAtEpochMs), decode the envelope.
 * The next `upsert` heals the entry by stamping the cache.
 *
 * Returns null when the bundle's `talevia.json` doesn't exist (the
 * registry entry outlived its bundle — stale crossed-machine pointer).
 * The caller filters nulls; [FileProjectStore.listSummaries] sorts
 * what remains. No mutex work here — the caller already decides
 * concurrency semantics (summary() is fully serialised inside
 * FileProjectStore.mutex; listSummaries() takes the mutex only per-
 * bundle decode to allow concurrent registry iteration).
 *
 * Extracted cycle-50 per `debt-split-file-project-store` — the two
 * call sites had the same ~20-line cache-first shape and were easy to
 * drift apart.
 */
internal suspend fun resolveProjectSummary(
    entry: RecentsEntry,
    taleviaJson: Path,
    fs: FileSystem,
    json: Json,
    decodeWithLock: suspend (Path) -> StoredProject?,
): ProjectSummaryInput? {
    if (!fs.exists(taleviaJson)) return null
    // Fast path: cache warm → skip envelope decode, only stat the file for
    // updatedAtEpochMs.
    if (entry.createdAtEpochMs > 0L) {
        val updated = bundleTimestamps(fs, taleviaJson, fallback = entry.lastOpenedAtEpochMs).second
        return ProjectSummaryInput(
            summary = ProjectSummary(
                id = entry.id,
                title = entry.title,
                createdAtEpochMs = entry.createdAtEpochMs,
                updatedAtEpochMs = entry.lastOpenedAtEpochMs.takeIf { it > 0L } ?: updated,
            ),
            decoded = false,
        )
    }
    // Slow path: decode envelope to fill in the fields the cache is missing.
    val bundleDir = taleviaJson.parent ?: return null
    val stored = decodeWithLock(bundleDir) ?: return null
    val (createdFromFs, updated) = bundleTimestamps(fs, taleviaJson, fallback = entry.lastOpenedAtEpochMs)
    val resolvedCreatedAt = stored.createdAtEpochMs.takeIf { it > 0L } ?: createdFromFs
    return ProjectSummaryInput(
        summary = ProjectSummary(
            id = entry.id,
            title = stored.title,
            createdAtEpochMs = resolvedCreatedAt,
            updatedAtEpochMs = entry.lastOpenedAtEpochMs.takeIf { it > 0L } ?: updated,
        ),
        decoded = true,
    )
}

/**
 * Return envelope for [resolveProjectSummary] — carries the summary plus
 * a hint about whether a decode was needed. The flag isn't consumed today
 * but exists so future callers (e.g. a bulk-warm routine that wants to
 * know which entries paid the slow path) don't have to re-derive it.
 */
internal data class ProjectSummaryInput(
    val summary: ProjectSummary,
    val decoded: Boolean,
)
