package io.talevia.core.domain

import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.logging.Logger
import io.talevia.core.logging.warn
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * On-open audit hooks — extracted from [FileProjectStore] so the facade
 * only calls out when a bundle is successfully loaded, and the validation
 * logic stays readable in its own file.
 *
 * Both operations are best-effort and short-circuit fast when no event bus
 * is wired (pure-persistence test rigs) — the enclosing store never blocks
 * on them.
 */

/**
 * Run source-DAG validation on the loaded project and publish a
 * [BusEvent.ProjectValidationWarning] when issues surface. Warning is
 * also logged so CLI / server runs surface the signal even without a bus
 * subscriber.
 */
internal suspend fun emitValidationWarningIfAny(
    project: Project,
    bus: EventBus?,
    logger: Logger,
) {
    val issues = ProjectSourceDagValidator.validate(project.source)
    if (issues.isEmpty()) return
    logger.warn(
        "project ${project.id.value} failed source-DAG validation on load",
        "projectId" to project.id.value,
        "issueCount" to issues.size.toString(),
        "firstIssue" to issues.first(),
    )
    bus?.publish(BusEvent.ProjectValidationWarning(project.id, issues))
}

/**
 * Scan `project.assets` for [MediaSource.File] paths that don't exist on
 * the current machine. Every cross-machine bundle open flows through
 * `openAt` / `get`; this is where alice's `/Users/alice/raw.mp4` fails
 * to resolve on bob's machine. Publishes one [BusEvent.AssetsMissing]
 * carrying every missing asset so UI / CLI can show a single "relink
 * these before export" panel instead of N independent events.
 *
 * Scope: only [MediaSource.File] (absolute host paths) is checked.
 * [MediaSource.BundleFile] paths resolve inside the bundle (a missing
 * bundle-file is a different failure — bundle corruption).
 * [MediaSource.Http] / [MediaSource.Platform] sources aren't filesystem-
 * checkable. `bus == null` short-circuits so pure-store tests don't pay
 * for the scan.
 */
internal suspend fun emitMissingAssetsIfAny(
    project: Project,
    bus: EventBus?,
    fs: FileSystem,
    logger: Logger,
) {
    val publisher = bus ?: return
    val missing = project.assets.mapNotNull { asset ->
        val src = asset.source
        if (src !is MediaSource.File) return@mapNotNull null
        if (fs.exists(src.path.toPath())) return@mapNotNull null
        BusEvent.MissingAsset(assetId = asset.id.value, originalPath = src.path)
    }
    if (missing.isEmpty()) return
    logger.warn(
        "project ${project.id.value} has ${missing.size} missing file-asset(s) on open",
        "projectId" to project.id.value,
        "missingCount" to missing.size.toString(),
        "firstAsset" to missing.first().assetId,
    )
    publisher.publish(BusEvent.AssetsMissing(project.id, missing))
}
