package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
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
 * Clear the pin on a lockfile entry — inverse of [PinLockfileEntryTool].
 *
 * Idempotent: unpinning an already-unpinned entry returns `wasUnpinned=true` but
 * doesn't mutate. Unknown inputHash fails loudly (same stance as the pin tool).
 *
 * Once unpinned the entry is again subject to `gc_lockfile` policy and the clip
 * can be re-dispatched by `regenerate_stale_clips` on the next stale run.
 */
class UnpinLockfileEntryTool(
    private val projects: ProjectStore,
) : Tool<UnpinLockfileEntryTool.Input, UnpinLockfileEntryTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val inputHash: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        /** True when the entry was already unpinned at call time (no-op). */
        val wasUnpinned: Boolean,
    )

    override val id: String = "unpin_lockfile_entry"
    override val helpText: String =
        "Clear the pin on a lockfile entry — inverse of pin_lockfile_entry. Once unpinned, " +
            "gc_lockfile can GC it and regenerate_stale_clips will re-dispatch its tool when " +
            "source nodes change. Idempotent. Find the inputHash via list_lockfile_entries."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("inputHash") {
                put("type", "string")
                put(
                    "description",
                    "Lockfile entry identifier from list_lockfile_entries.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("inputHash"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val entry = project.lockfile.findByInputHash(input.inputHash)
            ?: error(
                "Lockfile entry with inputHash '${input.inputHash}' not found in project " +
                    "${input.projectId}. Call list_lockfile_entries to see valid hashes.",
            )

        val wasUnpinned = !entry.pinned
        if (entry.pinned) {
            projects.mutate(pid) { p ->
                p.copy(lockfile = p.lockfile.withEntryPinned(input.inputHash, pinned = false))
            }
        }

        val verb = if (wasUnpinned) "was already unpinned" else "unpinned"
        return ToolResult(
            title = "unpin lockfile entry ${entry.toolId}/${entry.assetId.value}",
            outputForLlm = "Entry ${input.inputHash} ($verb). Asset ${entry.assetId.value} " +
                "produced by ${entry.toolId} is no longer frozen — gc_lockfile can drop it by policy " +
                "and regenerate_stale_clips will re-dispatch if its clip goes stale.",
            data = Output(
                projectId = pid.value,
                inputHash = input.inputHash,
                toolId = entry.toolId,
                assetId = entry.assetId.value,
                wasUnpinned = wasUnpinned,
            ),
        )
    }
}
