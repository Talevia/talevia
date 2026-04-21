package io.talevia.core.session.projector

import io.talevia.core.SessionId

/**
 * Read-side adapter that turns a [io.talevia.core.session.Session]'s linear
 * message list into a structured view suitable for a specific UI affordance.
 *
 * Talevia's session store is append-only and flat: messages + parts stream in
 * chronologically. UI code today reconstructs richer shapes ("tool-call tree",
 * "artifact timeline", "compaction history") by walking that stream client-side
 * — duplicated across Desktop / iOS / Android renderers and prone to drift when
 * the underlying model evolves.
 *
 * Inspired by OpenCode's `packages/opencode/src/session/projectors.ts`, which
 * centralises that derivation logic on the session-store side. **Only behavior
 * is borrowed** — no Effect.js Service/Layer/Context carry-over (CLAUDE.md red
 * line). Each projector is a plain Kotlin class with an injected store.
 *
 * [T] is the projection's native shape; concrete projectors pick an
 * `@Serializable` data class so the result can be sent across `core →
 * apps/desktop` / `core → apps/ios` boundaries untouched.
 */
interface SessionProjector<out T> {
    suspend fun project(sessionId: SessionId): T
}
