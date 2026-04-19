package io.talevia.core.domain

import io.talevia.core.ProjectSnapshotId
import kotlinx.serialization.Serializable

/**
 * A named, restorable point-in-time copy of a [Project] (VISION §3.4 — "可版本化").
 *
 * Captures the **content** of the project at the moment of save (timeline, source,
 * lockfile, render cache, asset catalog ids, output profile) — *not* the underlying
 * media bytes. Asset bytes live in `MediaStorage` and are reference-counted by
 * AssetId; deleting the file out from under a snapshot is the user's problem to
 * notice via a future "snapshot integrity" tool, not a load-bearing invariant we
 * promise here. Same trade-off git makes vs. LFS — saving the manifest is cheap,
 * saving every blob copy is not.
 *
 * Stored inline in the parent [Project.snapshots] list rather than a separate
 * SQL table so that:
 *
 *  - `ProjectStore.mutate` already gives us atomicity (snapshot + revert + clear
 *    all go through the same mutex; no second store to keep in sync),
 *  - the Project JSON blob is already what we'd serialize anyway,
 *  - cross-platform (iOS/Android/JVM) gets the feature for free without porting
 *    a new schema.
 *
 * Limit is enforced socially — when a project accumulates hundreds of snapshots
 * the JSON blob grows linearly. We'll add eviction (or migrate to a sub-table)
 * when that becomes load-bearing; for v0, naive inline is the right primitive.
 *
 * **Restore semantics.** Restoring a snapshot replaces every restorable field of
 * the Project (timeline, source, lockfile, renderCache, assets, outputProfile)
 * with the snapshot's payload — but **preserves [Project.snapshots] itself** and
 * the project [Project.id]. Otherwise restore would forget all earlier snapshots,
 * making restore a one-way trapdoor. With this rule, restore behaves like
 * `git checkout <snapshot>` from the user's perspective: state changes; history
 * stays.
 *
 * @property id Stable, project-unique id assigned by the save tool.
 * @property label Free-form human handle ("final cut v1", "before re-color").
 *   Defaults to a timestamp string when the user doesn't pick one.
 * @property capturedAtEpochMs Wall-clock time at snapshot moment, for ordering /
 *   display. We store epoch-ms to match every other timestamp in this store.
 * @property project Full Project payload at save time. Snapshots-of-snapshots
 *   are ignored — the captured payload's own `snapshots` list is always emptied
 *   at save time so we don't pay quadratic blow-up.
 */
@Serializable
data class ProjectSnapshot(
    val id: ProjectSnapshotId,
    val label: String,
    val capturedAtEpochMs: Long,
    val project: Project,
)
