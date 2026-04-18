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
)
