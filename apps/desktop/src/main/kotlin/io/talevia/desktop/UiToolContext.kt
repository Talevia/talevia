package io.talevia.desktop

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.tool.ToolContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Minimal [ToolContext] for panels that dispatch tools directly (no agent
 * loop). Same pattern every centre-panel button uses — permissions still
 * go through the container's real permission service.
 *
 * Split out of `SourcePanel.kt` as part of `debt-split-desktop-source-panel`
 * (2026-04-23). Lives here because multiple panels call it (SourcePanel,
 * AppRoot, SnapshotPanel, LockfilePanel); keeping it in SourcePanel.kt
 * coupled "wants to dispatch a tool" to "has Source DAG rendered".
 */
@OptIn(ExperimentalUuidApi::class)
internal fun AppContainer.uiToolContext(projectId: ProjectId): ToolContext {
    val sid = SessionId(projectId.value)
    val mid = MessageId(Uuid.random().toString())
    val cid = CallId(Uuid.random().toString())
    return ToolContext(
        sessionId = sid,
        messageId = mid,
        callId = cid,
        askPermission = { permissions.check(permissionRules.toList(), it) },
        emitPart = { p -> sessions.upsertPart(p) },
        messages = emptyList(),
    )
}
