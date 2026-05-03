package io.talevia.core.tool.builtin.project

import io.talevia.core.JsonConfig
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `project_action(kind=lifecycle|maintenance|pin|snapshot)` —
 * kind-discriminated dispatcher consuming the four standalone
 * `project_*_action` tools (cycle 55 design Option A; phase 1a-2 impl).
 *
 * Cycle 60 phase 1a-1 freed the `project_action` namespace by renaming
 * the original lifecycle tool to `project_lifecycle_action`. This cycle
 * (61) restores `project_action` as a unified surface backed by a sealed
 * Input → underlying-tool `execute` routing — mirroring the AIGC arc's
 * `aigc_generate(kind=...)` shape from `aigc-tool-consolidation-phase1`.
 *
 * The four underlying tools STAY registered for now (per AIGC arc phase
 * 1 precedent: `generate_image` etc. coexisted with `aigc_generate` until
 * cycle 27 unregistered them). Phase 2 unregisters once agents have
 * adopted the dispatcher form across the test suite + system prompts.
 *
 * Permission: tier resolves at dispatch time via [permissionFrom],
 * extracting the outer `kind` + inner `args` JSON object and delegating
 * to the underlying tool's `permissionFrom`. Lifecycle's `delete` →
 * `project.destructive`; lifecycle's `open` → `project.read`; snapshot's
 * `restore`/`delete` → `project.destructive`; everything else →
 * `project.write` base. Malformed input falls through to the base tier.
 *
 * No state held beyond the four underlying tool references — the
 * dispatcher is a pure router.
 */
class ProjectActionDispatcherTool(
    private val lifecycle: ProjectLifecycleActionTool,
    private val maintenance: ProjectMaintenanceActionTool,
    private val pin: ProjectPinActionTool,
    private val snapshot: ProjectSnapshotActionTool,
) : Tool<ProjectActionDispatcherTool.Input, ProjectActionDispatcherTool.Output> {

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("kind")
    sealed interface Input {
        @Serializable
        @SerialName("lifecycle")
        data class Lifecycle(val args: ProjectLifecycleActionTool.Input) : Input

        @Serializable
        @SerialName("maintenance")
        data class Maintenance(val args: ProjectMaintenanceActionTool.Input) : Input

        @Serializable
        @SerialName("pin")
        data class Pin(val args: ProjectPinActionTool.Input) : Input

        @Serializable
        @SerialName("snapshot")
        data class Snapshot(val args: ProjectSnapshotActionTool.Input) : Input
    }

    /**
     * Flat unified output — `kind` discriminator tells consumers which
     * kind-specific result is populated. Mirrors `AigcGenerateTool.Output`.
     * Sealed Output was rejected for the same reason as the AIGC arc:
     * result-handling glue is uniform across kinds; sealed shape would
     * duplicate boilerplate without typing wins.
     */
    @Serializable
    data class Output(
        val kind: String,
        val lifecycleResult: ProjectLifecycleActionTool.Output? = null,
        val maintenanceResult: ProjectMaintenanceActionTool.Output? = null,
        val pinResult: ProjectPinActionTool.Output? = null,
        val snapshotResult: ProjectSnapshotActionTool.Output? = null,
    )

    override val id: String = "project_action"

    override val helpText: String =
        "Project action dispatcher with `kind` discriminator routing to one of four " +
            "underlying tools: " +
            "`kind=lifecycle` + args (action=create|create_from_template|open|delete|rename|" +
            "set_output_profile|remove_asset, …) → project lifecycle (formerly the standalone " +
            "`project_action` tool, renamed to `project_lifecycle_action` cycle 60). " +
            "`kind=maintenance` + args → housekeeping (gc / prune / clear). " +
            "`kind=pin` + args → toggle the `pinned` flag on lockfile entries. " +
            "`kind=snapshot` + args (action=save|restore|delete) → project snapshots. " +
            "Args are typed per kind; consult each underlying tool's helpText for the args " +
            "field shape (project_lifecycle_action / project_maintenance_action / " +
            "project_pin_action / project_snapshot_action). Until phase 2 unregisters them, " +
            "the four underlying tools remain directly callable too — the dispatcher is " +
            "additive."

    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()

    /**
     * Permission resolution: parse the outer `kind` field, extract the
     * `args` JSON object, delegate to the underlying tool's
     * `permissionFrom` for tier resolution. Malformed input → base
     * `project.write` (the safe default — never below the underlying
     * tools' minimum write tier).
     */
    override val permission: PermissionSpec = PermissionSpec(
        permission = "project.write",
        permissionFrom = { rawInputJson ->
            val parsed = runCatching {
                JsonConfig.default.parseToJsonElement(rawInputJson) as? JsonObject
            }.getOrNull()
            val kind = (parsed?.get("kind") as? JsonPrimitive)?.content
            val argsObject = parsed?.get("args") as? JsonObject
            val argsJsonString = if (argsObject != null) {
                JsonConfig.default.encodeToString(JsonObject.serializer(), argsObject)
            } else {
                "{}"
            }
            when (kind) {
                "lifecycle" -> lifecycle.permission.permissionFrom(argsJsonString)
                "maintenance" -> maintenance.permission.permissionFrom(argsJsonString)
                "pin" -> pin.permission.permissionFrom(argsJsonString)
                "snapshot" -> snapshot.permission.permissionFrom(argsJsonString)
                else -> "project.write"
            }
        },
    )

    /**
     * Minimal hand-written schema. The four `args` shapes are typed via
     * the underlying tools' input schemas — but JSON Schema doesn't
     * trivially express a discriminated union of cross-tool input
     * shapes, so the schema here describes the discriminator + a generic
     * `args` object. The agent learns the per-kind `args` shape from
     * the underlying tools' schemas (still registered) plus this tool's
     * helpText. Phase 2 will inline per-kind schemas via JSON Schema's
     * `oneOf` when the four underlying tools unregister.
     */
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "kind",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            kotlinx.serialization.json.buildJsonArray {
                                add(JsonPrimitive("lifecycle"))
                                add(JsonPrimitive("maintenance"))
                                add(JsonPrimitive("pin"))
                                add(JsonPrimitive("snapshot"))
                            },
                        )
                        put(
                            "description",
                            JsonPrimitive(
                                "Discriminator selecting which underlying tool's args to route to.",
                            ),
                        )
                    },
                )
                put(
                    "args",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "description",
                            JsonPrimitive(
                                "Args object matching the underlying tool's Input shape: " +
                                    "kind=lifecycle → project_lifecycle_action.Input; " +
                                    "kind=maintenance → project_maintenance_action.Input; " +
                                    "kind=pin → project_pin_action.Input; " +
                                    "kind=snapshot → project_snapshot_action.Input.",
                            ),
                        )
                    },
                )
            },
        )
        put(
            "required",
            kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("kind"))
                add(JsonPrimitive("args"))
            },
        )
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> = when (input) {
        is Input.Lifecycle -> {
            val r = lifecycle.execute(input.args, ctx)
            ToolResult(
                title = r.title,
                outputForLlm = r.outputForLlm,
                data = Output(kind = "lifecycle", lifecycleResult = r.data),
            )
        }
        is Input.Maintenance -> {
            val r = maintenance.execute(input.args, ctx)
            ToolResult(
                title = r.title,
                outputForLlm = r.outputForLlm,
                data = Output(kind = "maintenance", maintenanceResult = r.data),
            )
        }
        is Input.Pin -> {
            val r = pin.execute(input.args, ctx)
            ToolResult(
                title = r.title,
                outputForLlm = r.outputForLlm,
                data = Output(kind = "pin", pinResult = r.data),
            )
        }
        is Input.Snapshot -> {
            val r = snapshot.execute(input.args, ctx)
            ToolResult(
                title = r.title,
                outputForLlm = r.outputForLlm,
                data = Output(kind = "snapshot", snapshotResult = r.data),
            )
        }
    }
}
