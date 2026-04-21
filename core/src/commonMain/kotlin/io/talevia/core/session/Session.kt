package io.talevia.core.session

import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionRule
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: SessionId,
    val projectId: ProjectId,
    val title: String,
    val parentId: SessionId? = null,
    val permissionRules: List<PermissionRule> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val compactingFrom: MessageId? = null,
    val archived: Boolean = false,
    /**
     * The project the agent is **currently** editing in this session — a
     * cwd-analogue for multi-project workflows (VISION §5.4). Distinct from
     * [projectId], which is the session's originating project and never
     * changes after creation.
     *
     * `null` means "not yet bound to a project"; the agent should pick one
     * (usually via `list_projects` → `create_project` or an explicit
     * `switch_project` call) before running timeline tools. Flipped by
     * `SwitchProjectTool`; injected into the per-turn system prompt and
     * exposed on `ToolContext.currentProjectId` so tools can default their
     * `projectId` arg from it.
     *
     * Defaulted to null so pre-binding sessions deserialize cleanly (§3a #7
     * — serialization compat).
     */
    val currentProjectId: ProjectId? = null,
)
