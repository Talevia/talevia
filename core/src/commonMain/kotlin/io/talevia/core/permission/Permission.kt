package io.talevia.core.permission

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PermissionAction {
    @SerialName("allow") ALLOW,
    @SerialName("ask") ASK,
    @SerialName("deny") DENY,
}

@Serializable
data class PermissionRule(
    val permission: String,
    val pattern: String = "*",
    val action: PermissionAction,
)

/**
 * Declared by a Tool — what permission name(s) the dispatcher should check before running it.
 * Runtime-only; not serialised (Tool definitions live in code, not the DB).
 *
 * [permission] is the tool's declared "base" tier (shown by `list_tools`).
 * [permissionFrom] can override it per-input — useful for action-dispatched
 * tools whose branches have different permission tiers (e.g. a snapshot
 * tool where `action=save` is `project.write` but `action=restore|delete`
 * is `project.destructive`). Defaults to returning [permission], so every
 * tool built via `PermissionSpec.fixed` behaves exactly as before.
 */
data class PermissionSpec(
    val permission: String,
    val patternFrom: (inputJson: String) -> String = { "*" },
    val permissionFrom: (inputJson: String) -> String = { permission },
) {
    companion object {
        fun fixed(permission: String): PermissionSpec = PermissionSpec(permission)
    }
}
