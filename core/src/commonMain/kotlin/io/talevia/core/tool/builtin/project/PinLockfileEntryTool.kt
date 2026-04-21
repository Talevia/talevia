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
 * Mark an AIGC lockfile entry as a hero shot (VISION §3.1 "产物可 pin").
 *
 * The lockfile is the random compiler's `package-lock.json`; by default every
 * entry is fair game for GC policy sweeps and for `regenerate_stale_clips` when a
 * bound source node changes. Users creating deliberate hero shots — "this exact
 * Mei portrait is the one, keep it" — need a one-bit escape hatch.
 *
 * Once pinned:
 *  - [GcLockfileTool] rescues the entry regardless of `maxAgeDays` /
 *    `keepLatestPerTool` / `preserveLiveAssets=false` verdicts.
 *  - [RegenerateStaleClipsTool] skips any clip whose current lockfile entry is
 *    pinned (reason `"pinned"`), leaving the clip stale-but-frozen until the
 *    user unpins it or replaces the clip outright.
 *  - [PruneLockfileTool] still drops orphan pinned entries — a pin with no
 *    surviving asset protects nothing and is dead weight.
 *
 * Idempotent: re-pinning an already-pinned entry is a no-op with
 * `alreadyPinned=true` in the output. Unknown inputHash fails loudly rather than
 * silently succeeding — we'd rather the agent see "no such entry" than think the
 * pin took and act on that assumption downstream.
 *
 * Sibling [UnpinLockfileEntryTool] is the inverse operation.
 */
class PinLockfileEntryTool(
    private val projects: ProjectStore,
) : Tool<PinLockfileEntryTool.Input, PinLockfileEntryTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /**
         * The lockfile entry's [io.talevia.core.domain.lockfile.LockfileEntry.inputHash].
         * Get it from `list_lockfile_entries` — that's the stable handle that survives
         * across session restarts (asset ids are stable too, but the user typically
         * reasons in terms of "that generation" not "that asset").
         */
        val inputHash: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        val alreadyPinned: Boolean,
    )

    override val id: String = "pin_lockfile_entry"
    override val helpText: String =
        "Mark an AIGC lockfile entry as a hero shot — gc_lockfile skips it regardless of " +
            "policy, and regenerate_stale_clips leaves its clip stale-but-frozen instead of " +
            "re-dispatching the tool. Use after the user approves a key generation (\"this exact " +
            "Mei portrait is the one\"). Idempotent. Find the inputHash via list_lockfile_entries. " +
            "Inverse: unpin_lockfile_entry."
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

        val alreadyPinned = entry.pinned
        if (!alreadyPinned) {
            projects.mutate(pid) { p ->
                p.copy(lockfile = p.lockfile.withEntryPinned(input.inputHash, pinned = true))
            }
        }

        val verb = if (alreadyPinned) "was already pinned" else "pinned"
        return ToolResult(
            title = "pin lockfile entry ${entry.toolId}/${entry.assetId.value}",
            outputForLlm = "Entry ${input.inputHash} ($verb). Asset ${entry.assetId.value} " +
                "produced by ${entry.toolId} is now a hero shot — gc_lockfile will skip it and " +
                "regenerate_stale_clips will leave its clip frozen. Use unpin_lockfile_entry to reverse.",
            data = Output(
                projectId = pid.value,
                inputHash = input.inputHash,
                toolId = entry.toolId,
                assetId = entry.assetId.value,
                alreadyPinned = alreadyPinned,
            ),
        )
    }
}
