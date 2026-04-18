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
 */
data class PermissionSpec(
    val permission: String,
    val patternFrom: (inputJson: String) -> String = { "*" },
) {
    companion object {
        fun fixed(permission: String): PermissionSpec = PermissionSpec(permission)
    }
}
