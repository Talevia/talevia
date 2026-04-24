package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Project-state maintenance sweeps — consolidated action-dispatched form that
 * replaces `PruneLockfileTool` + `GcLockfileTool` + `GcClipRenderCacheTool`
 * (`debt-consolidate-project-lockfile-maintenance`, 2026-04-23).
 *
 * Three sweep flavours, all under one LLM-facing tool:
 *
 * - `action="prune-lockfile"` — drop lockfile rows whose `assetId` is no
 *   longer in `project.assets` (orphan sweep; no policy knobs). Input:
 *   `projectId`, `dryRun`.
 * - `action="gc-lockfile"` — policy-based GC of the lockfile. Input:
 *   `projectId`, `dryRun`, optional `maxAgeDays`, optional
 *   `keepLatestPerTool`, optional `preserveLiveAssets` (default `true`).
 *   Policies AND; pinned rows always survive; live-asset guard rescues rows
 *   whose asset is still referenced (unless `preserveLiveAssets=false`).
 *   Both-null is a no-op with a pointer to `prune-lockfile`.
 * - `action="gc-render-cache"` — policy-based GC of the per-clip mezzanine
 *   cache (`Project.clipRenderCache`). Input: `projectId`, `dryRun`,
 *   optional `maxAgeDays`, optional `keepLastN`. Each pruned row also
 *   deletes its `.mp4` mezzanine file via `VideoEngine.deleteMezzanine`.
 *   Both-null is a no-op. No `preserveLiveAssets` equivalent — mezzanine
 *   cache is pure-cache (pruned entry = cache miss on next export, never
 *   broken state).
 *
 * All three actions share permission `project.write` (none of them destroy
 * anything the user can't regenerate) and default `dryRun=false` with a
 * uniform preview path. Policies / shape differences live in the
 * per-action handler files
 * (`PruneLockfileHandler.kt` / `GcLockfileHandler.kt` /
 * `GcRenderCacheHandler.kt`) — this class is the dispatcher + shape
 * definitions + the LLM-facing spec (helpText + inputSchema). The split
 * axis is "new maintenance action" — a fourth sweep flavour lands as a
 * fourth handler file rather than puffing this class up again past the
 * 500-line long-file threshold
 * (`debt-split-project-maintenance-action-tool`, 2026-04-23).
 *
 * Output carries a per-action sub-list populated on its branch; unused
 * branches stay empty rather than nullable. Shared headers (`totalEntries`,
 * `prunedCount`, `keptCount`, `dryRun`, `policiesApplied`) work for all
 * three actions.
 */
class ProjectMaintenanceActionTool(
    private val projects: ProjectStore,
    private val engine: VideoEngine,
    private val clock: Clock = Clock.System,
) : Tool<ProjectMaintenanceActionTool.Input, ProjectMaintenanceActionTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** `"prune-lockfile"`, `"gc-lockfile"`, or `"gc-render-cache"`. */
        val action: String,
        /** Report without mutating. Default false. Applies to all actions. */
        val dryRun: Boolean = false,
        /** `gc-lockfile` + `gc-render-cache`: drop entries strictly older than `now - maxAgeDays`. `>= 0`. */
        val maxAgeDays: Int? = null,
        /** `gc-lockfile` only: keep the N most recent entries per `toolId`. `>= 0`. */
        val keepLatestPerTool: Int? = null,
        /** `gc-lockfile` only: rescue rows whose `assetId` is still in `project.assets`. Default true. */
        val preserveLiveAssets: Boolean = true,
        /** `gc-render-cache` only: keep the N most recent entries overall. `>= 0`. */
        val keepLastN: Int? = null,
    )

    /** `prune-lockfile` row. */
    @Serializable data class PrunedOrphanLockfileRow(
        val inputHash: String,
        val toolId: String,
        val assetId: String,
    )

    /** `gc-lockfile` row. */
    @Serializable data class PrunedGcLockfileRow(
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        val createdAtEpochMs: Long,
        /** One of `"age"`, `"count"`, `"age+count"`. */
        val reason: String,
    )

    /** `gc-render-cache` row. */
    @Serializable data class PrunedRenderCacheRow(
        val fingerprint: String,
        val mezzaninePath: String,
        val createdAtEpochMs: Long,
        /** One of `"age"`, `"count"`, `"age+count"`. */
        val reason: String,
        /** True iff the on-disk mp4 was actually deleted. False on dry-run or no-op engines. */
        val fileDeleted: Boolean,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        val totalEntries: Int,
        val prunedCount: Int,
        val keptCount: Int,
        val dryRun: Boolean,
        /** Only populated on `action="prune-lockfile"`. */
        val prunedOrphanLockfileRows: List<PrunedOrphanLockfileRow> = emptyList(),
        /** Only populated on `action="gc-lockfile"`. */
        val prunedGcLockfileRows: List<PrunedGcLockfileRow> = emptyList(),
        /** Only populated on `action="gc-render-cache"`. */
        val prunedRenderCacheRows: List<PrunedRenderCacheRow> = emptyList(),
        /** `gc-lockfile`-only: entries rescued because their asset is still in project.assets. */
        val keptByLiveAssetGuardCount: Int = 0,
        /** `gc-lockfile`-only: entries rescued because `pinned=true`. */
        val keptByPinCount: Int = 0,
        /** Which policies were applied — e.g. `["age", "count", "pinGuard", "liveAssetGuard"]`. */
        val policiesApplied: List<String> = emptyList(),
    )

    override val id: String = "project_maintenance_action"
    override val helpText: String =
        "Project-state maintenance: action=prune-lockfile drops lockfile rows whose " +
            "assetId isn't in project.assets. action=gc-lockfile policy GC (maxAgeDays + " +
            "keepLatestPerTool both AND; pinned always kept; preserveLiveAssets=true keeps " +
            "rows whose asset is referenced). action=gc-render-cache policy GC on mezzanine " +
            "cache (maxAgeDays + keepLastN both AND) + deletes .mp4 via engine. All actions " +
            "support dryRun=true. Both-null policy args on gc-* is no-op."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "`prune-lockfile`, `gc-lockfile`, or `gc-render-cache`.",
                )
                put(
                    "enum",
                    JsonArray(
                        listOf(
                            JsonPrimitive("prune-lockfile"),
                            JsonPrimitive("gc-lockfile"),
                            JsonPrimitive("gc-render-cache"),
                        ),
                    ),
                )
            }
            putJsonObject("dryRun") {
                put("type", "boolean")
                put("description", "Report without mutating. Default false. Applies to all actions.")
            }
            putJsonObject("maxAgeDays") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "gc-lockfile + gc-render-cache: drop entries strictly older than now - maxAgeDays.",
                )
            }
            putJsonObject("keepLatestPerTool") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "gc-lockfile only: keep the N most-recent entries per toolId; drop the rest.",
                )
            }
            putJsonObject("preserveLiveAssets") {
                put("type", "boolean")
                put(
                    "description",
                    "gc-lockfile only: rescue rows whose assetId is still in project.assets. Default true.",
                )
            }
            putJsonObject("keepLastN") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "gc-render-cache only: keep the N most-recent entries overall.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("action"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        return when (input.action) {
            "prune-lockfile" -> executePruneLockfile(projects, pid, input)
            "gc-lockfile" -> executeGcLockfile(projects, pid, input, clock)
            "gc-render-cache" -> executeGcRenderCache(projects, engine, pid, input, clock)
            else -> error(
                "unknown action '${input.action}'; accepted: prune-lockfile, gc-lockfile, gc-render-cache",
            )
        }
    }
}
