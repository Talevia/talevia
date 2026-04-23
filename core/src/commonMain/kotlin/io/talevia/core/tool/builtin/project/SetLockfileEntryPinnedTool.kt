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
 * Upsert a lockfile entry's pinned flag — hero-shot escape hatch for VISION
 * §3.1 "产物可 pin".
 *
 * Replaces the pre-split `pin_lockfile_entry` + `unpin_lockfile_entry` pair
 * with a single [Input.pinned]-driven upsert. Two mutually-exclusive tool
 * specs for a boolean toggle are pure LLM token overhead (§3a.2). See
 * `docs/decisions/2026-04-21-debt-merge-pin-unpin-tool-pairs.md`.
 *
 * When `pinned=true`:
 *  - [ProjectMaintenanceActionTool] rescues the entry regardless of `maxAgeDays` /
 *    `keepLatestPerTool` / `preserveLiveAssets=false` verdicts.
 *  - [RegenerateStaleClipsTool] skips any clip whose current lockfile entry is
 *    pinned (reason `"pinned"`), leaving the clip stale-but-frozen until the
 *    user unpins it or replaces the clip outright.
 *  - [ProjectMaintenanceActionTool] still drops orphan pinned entries — a pin with no
 *    surviving asset protects nothing and is dead weight.
 *
 * When `pinned=false`: entry is again subject to gc_lockfile policy and
 * regenerate_stale_clips can re-dispatch its clip on the next stale run.
 *
 * Idempotent: calling with the same pinned state is a no-op; [Output.changed]
 * is `false`. Unknown inputHash fails loudly rather than silently succeeding.
 */
class SetLockfileEntryPinnedTool(
    private val projects: ProjectStore,
) : Tool<SetLockfileEntryPinnedTool.Input, SetLockfileEntryPinnedTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /**
         * The lockfile entry's [io.talevia.core.domain.lockfile.LockfileEntry.inputHash].
         * Get it from `list_lockfile_entries` — that's the stable handle that survives
         * across session restarts (asset ids are stable too, but the user typically
         * reasons in terms of "that generation" not "that asset").
         */
        val inputHash: String,
        /** `true` freezes the entry as a hero shot; `false` clears the pin. */
        val pinned: Boolean,
    )

    @Serializable data class Output(
        val projectId: String,
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        /** Pinned flag before the call (for idempotency inspection). */
        val pinnedBefore: Boolean,
        /** Pinned flag after the call — always equals [Input.pinned]. */
        val pinnedAfter: Boolean,
        /** True when the call actually flipped the flag (`pinnedBefore != pinnedAfter`). */
        val changed: Boolean,
    )

    override val id: String = "set_lockfile_entry_pinned"
    override val helpText: String =
        "Upsert the pinned flag on an AIGC lockfile entry — a hero-shot escape hatch. With " +
            "pinned=true, gc_lockfile skips the entry regardless of policy and " +
            "regenerate_stale_clips leaves its clip stale-but-frozen instead of re-dispatching. " +
            "With pinned=false, the entry is again subject to policy. Use after the user approves " +
            "(or disavows) a key generation — \"this exact Mei portrait is the one\" / \"go " +
            "ahead and re-roll this one\". Idempotent. Find the inputHash via list_lockfile_entries."
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
            putJsonObject("pinned") {
                put("type", "boolean")
                put(
                    "description",
                    "true to freeze the entry as a hero shot; false to clear the pin.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("inputHash"), JsonPrimitive("pinned"))),
        )
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

        val pinnedBefore = entry.pinned
        val changed = pinnedBefore != input.pinned
        if (changed) {
            projects.mutate(pid) { p ->
                p.copy(lockfile = p.lockfile.withEntryPinned(input.inputHash, pinned = input.pinned))
            }
        }

        val verbNow = if (input.pinned) "pinned" else "unpinned"
        val verbBefore = if (changed) "" else "(was already $verbNow) "
        val gcHint = if (input.pinned) {
            "gc_lockfile will skip it and regenerate_stale_clips will leave its clip frozen."
        } else {
            "gc_lockfile can drop it by policy and regenerate_stale_clips will re-dispatch if its clip goes stale."
        }
        return ToolResult(
            title = "${if (input.pinned) "pin" else "unpin"} lockfile entry ${entry.toolId}/${entry.assetId.value}",
            outputForLlm = "Entry ${input.inputHash} ${verbBefore}$verbNow. " +
                "Asset ${entry.assetId.value} produced by ${entry.toolId} — $gcHint",
            data = Output(
                projectId = pid.value,
                inputHash = input.inputHash,
                toolId = entry.toolId,
                assetId = entry.assetId.value,
                pinnedBefore = pinnedBefore,
                pinnedAfter = input.pinned,
                changed = changed,
            ),
        )
    }
}
