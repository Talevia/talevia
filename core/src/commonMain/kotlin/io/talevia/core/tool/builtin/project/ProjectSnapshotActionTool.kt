package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
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
 * Capture, restore, or delete named point-in-time snapshots of a project
 * (VISION §3.4 — "可版本化, 可回滚") — the consolidated action-dispatched
 * form that replaces the `SaveProjectSnapshotTool` / `RestoreProjectSnapshotTool`
 * / `DeleteProjectSnapshotTool` triple (`debt-consolidate-project-snapshot-triple`,
 * 2026-04-23).
 *
 * Distinct from `revert_timeline`, which scrubs through *session-scoped*
 * timeline snapshots emitted as side effects of every mutating tool —
 * those die with the chat session. These snapshots persist inside the
 * project itself and survive across chat sessions, app restarts, and
 * device migrations.
 *
 * ## Actions
 *
 * - `action="save"` (permission `project.write`): capture the current
 *   project state as a new snapshot. `label` is free-form; defaults to
 *   a timestamp string when omitted. Nested snapshots are cleared from
 *   the captured payload to avoid quadratic blow-up on
 *   snapshots-of-snapshots-of-snapshots.
 * - `action="restore"` (permission `project.destructive`): roll the
 *   project back to a previously-saved snapshot. Replaces timeline /
 *   source DAG / lockfile / render cache / asset catalog / output
 *   profile with the snapshot's payload but **preserves the snapshots
 *   list itself and the project id** — restore behaves like
 *   `git checkout <snapshot>`, not like a trapdoor.
 * - `action="delete"` (permission `project.destructive`): remove one
 *   snapshot by id. Irreversible. Unknown `snapshotId` fails loud
 *   rather than no-op-silently, so the agent can't hide typos.
 *
 * Asset bytes are *not* copied by save — snapshots reference
 * [io.talevia.core.AssetId]s in the project's bundle
 * (`<bundleRoot>/media/`) or external absolute paths. Same trade-off
 * git makes vs. LFS: cheap manifest, balloon-resistant storage.
 *
 * ## Permission per action
 *
 * Save uses `project.write`; restore + delete use `project.destructive`.
 * The tool returns the per-input permission through
 * [PermissionSpec.permissionFrom]; the base `permission` field is
 * `project.write` because that's the lowest-tier (most common) action.
 * `list_tools` reports the base tier — the helpText below documents
 * the destructive tiers for restore/delete so the agent knows what to
 * expect.
 */
class ProjectSnapshotActionTool(
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
) : Tool<ProjectSnapshotActionTool.Input, ProjectSnapshotActionTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** `"save"`, `"restore"`, or `"delete"`. Case-sensitive. */
        val action: String,
        /** Save-only. Free-form label; defaults to a timestamp string when omitted. */
        val label: String? = null,
        /** Restore + delete: required snapshot id (from a prior save or list_project_snapshots). */
        val snapshotId: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        val snapshotId: String,
        val label: String,
        val capturedAtEpochMs: Long,
        /** Save: snapshot count after capture. Delete: snapshot count after removal. Restore: current count (unchanged). */
        val totalSnapshotCount: Int,
        /** Restore-only: clip count in the restored timeline. Zero otherwise. */
        val clipCount: Int = 0,
        /** Restore-only: track count in the restored timeline. Zero otherwise. */
        val trackCount: Int = 0,
    )

    override val id: String = "project_snapshot_action"
    override val helpText: String =
        "Capture, restore, or delete named point-in-time snapshots of a project. " +
            "`action=\"save\"` + optional `label` captures the current state (permission: " +
            "`project.write`). `action=\"restore\"` + `snapshotId` rolls the project back to a prior " +
            "snapshot, preserving the snapshots list so restore is reversible (permission: " +
            "`project.destructive`). `action=\"delete\"` + `snapshotId` drops one snapshot — " +
            "irreversible (permission: `project.destructive`). Use list_project_snapshots (via " +
            "project_query) to enumerate. Restore + delete refuse unknown ids."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()

    /**
     * Base tier is `project.write` (save). [permissionFrom] upgrades to
     * `project.destructive` for restore/delete by parsing the action value
     * out of the raw input JSON. Parsing is forgiving — anything that
     * isn't clearly `"save"` defaults to `project.destructive`, so even a
     * malformed input can't silently sneak past the destructive gate.
     */
    override val permission: PermissionSpec = PermissionSpec(
        permission = "project.write",
        permissionFrom = { inputJson ->
            if (isSaveAction(inputJson)) "project.write" else "project.destructive"
        },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("action") {
                put("type", "string")
                put("description", "`save` captures, `restore` rolls back, `delete` drops one snapshot.")
                put(
                    "enum",
                    JsonArray(
                        listOf(
                            JsonPrimitive("save"),
                            JsonPrimitive("restore"),
                            JsonPrimitive("delete"),
                        ),
                    ),
                )
            }
            putJsonObject("label") {
                put("type", "string")
                put("description", "Save-only. Optional human handle; defaults to a timestamp string.")
            }
            putJsonObject("snapshotId") {
                put("type", "string")
                put(
                    "description",
                    "Required for restore/delete. Id from project_snapshot_action(action=save) / project_query(select=snapshots).",
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
            "save" -> executeSave(pid, input)
            "restore" -> executeRestore(pid, input, ctx)
            "delete" -> executeDelete(pid, input)
            else -> error("unknown action '${input.action}'; accepted: save, restore, delete")
        }
    }

    private suspend fun executeSave(pid: ProjectId, input: Input): ToolResult<Output> {
        val capturedAt = clock.now().toEpochMilliseconds()
        val snapshotId = ProjectSnapshotId("snap-$capturedAt-${pid.value.take(8)}")
        val label = input.label?.takeIf { it.isNotBlank() } ?: "snapshot @ $capturedAt"

        val updated = projects.mutate(pid) { project ->
            // Clear nested snapshots before capture so snapshots-of-snapshots don't
            // grow quadratically. Restore will preserve the (current) snapshots list,
            // not whatever was nested inside the captured payload.
            val payload = project.copy(snapshots = emptyList())
            project.copy(
                snapshots = project.snapshots + ProjectSnapshot(
                    id = snapshotId,
                    label = label,
                    capturedAtEpochMs = capturedAt,
                    project = payload,
                ),
            )
        }

        return ToolResult(
            title = "save snapshot \"$label\"",
            outputForLlm = "Saved snapshot ${snapshotId.value} (\"$label\") for project ${pid.value}. " +
                "Project now has ${updated.snapshots.size} snapshot(s). " +
                "Pass snapshotId=${snapshotId.value} to project_snapshot_action(action=restore) to roll back.",
            data = Output(
                projectId = pid.value,
                action = "save",
                snapshotId = snapshotId.value,
                label = label,
                capturedAtEpochMs = capturedAt,
                totalSnapshotCount = updated.snapshots.size,
            ),
        )
    }

    private suspend fun executeRestore(pid: ProjectId, input: Input, ctx: ToolContext): ToolResult<Output> {
        val rawSnapshotId = input.snapshotId
            ?: error("action=restore requires `snapshotId`")
        val targetId = ProjectSnapshotId(rawSnapshotId)

        val updated = projects.mutate(pid) { project ->
            val snap = project.snapshots.firstOrNull { it.id == targetId }
                ?: error(
                    "Snapshot $rawSnapshotId not found on project ${pid.value}. " +
                        "Use project_query(select=snapshots) to enumerate.",
                )
            val captured = snap.project
            // Replace restorable fields; preserve the snapshots list + project id
            // so history isn't a one-way trapdoor.
            captured.copy(
                id = project.id,
                snapshots = project.snapshots,
            )
        }

        val snap = updated.snapshots.first { it.id == targetId }
        val clipCount = updated.timeline.tracks.sumOf { it.clips.size }
        val trackCount = updated.timeline.tracks.size
        return ToolResult(
            title = "restore snapshot \"${snap.label}\"",
            outputForLlm = "Restored project ${pid.value} to snapshot ${snap.id.value} " +
                "(\"${snap.label}\", $clipCount clip(s), $trackCount track(s)). " +
                "Snapshot history preserved — ${updated.snapshots.size} total snapshot(s) remain.",
            data = Output(
                projectId = pid.value,
                action = "restore",
                snapshotId = snap.id.value,
                label = snap.label,
                capturedAtEpochMs = snap.capturedAtEpochMs,
                totalSnapshotCount = updated.snapshots.size,
                clipCount = clipCount,
                trackCount = trackCount,
            ),
        )
    }

    private suspend fun executeDelete(pid: ProjectId, input: Input): ToolResult<Output> {
        val rawSnapshotId = input.snapshotId
            ?: error("action=delete requires `snapshotId`")
        val targetId = ProjectSnapshotId(rawSnapshotId)
        var removedLabel = ""
        var removedCapturedAtEpochMs = 0L

        val updated = projects.mutate(pid) { project ->
            val snap = project.snapshots.firstOrNull { it.id == targetId }
                ?: error(
                    "Snapshot $rawSnapshotId not found on project ${pid.value}. " +
                        "Use project_query(select=snapshots) to enumerate.",
                )
            removedLabel = snap.label
            removedCapturedAtEpochMs = snap.capturedAtEpochMs
            project.copy(snapshots = project.snapshots.filterNot { it.id == targetId })
        }

        return ToolResult(
            title = "delete snapshot \"$removedLabel\"",
            outputForLlm = "Deleted snapshot ${targetId.value} (\"$removedLabel\") from project ${pid.value}. " +
                "Remaining: ${updated.snapshots.size} snapshot(s).",
            data = Output(
                projectId = pid.value,
                action = "delete",
                snapshotId = targetId.value,
                label = removedLabel,
                capturedAtEpochMs = removedCapturedAtEpochMs,
                totalSnapshotCount = updated.snapshots.size,
            ),
        )
    }

    private companion object {
        /**
         * Lightweight check for `"action":"save"` in the raw input JSON. Runs
         * before the tool's normal kotlinx.serialization decode, so we can't
         * assume a well-formed input. Any match failure defaults to the
         * destructive tier via the caller — safer than the other way around.
         */
        private val SAVE_ACTION_REGEX = Regex(
            pattern = """"action"\s*:\s*"save"""",
            option = RegexOption.IGNORE_CASE,
        )

        fun isSaveAction(inputJson: String): Boolean = SAVE_ACTION_REGEX.containsMatchIn(inputJson)
    }
}
