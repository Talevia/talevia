package io.talevia.core.session

import io.talevia.core.SessionId

/**
 * Helpers for reading the agent-scratchpad todo list out of a session's Parts.
 *
 * The [io.talevia.core.tool.builtin.TodoWriteTool] emits a [Part.Todos] each
 * time the agent updates its plan; the most recent `Part.Todos` in a session
 * is the current state. We ride the existing Parts table rather than minting
 * a new one (see OpenCode's `session/todo.ts` for the separate-table
 * alternative — equivalent functionally, more schema surface).
 */
suspend fun SessionStore.currentTodos(sessionId: SessionId): List<TodoInfo> {
    val latest = listSessionParts(sessionId, includeCompacted = true)
        .filterIsInstance<Part.Todos>()
        .maxByOrNull { it.createdAt }
        ?: return emptyList()
    return latest.todos
}
