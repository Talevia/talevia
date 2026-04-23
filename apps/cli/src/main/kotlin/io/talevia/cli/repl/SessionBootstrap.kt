package io.talevia.cli.repl

import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.session.Session
import io.talevia.core.session.SessionStore
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * How the CLI should pick (or create) a session at startup (VISION §5.4 CLI
 * ergonomics). The default is auto-resume so `talevia` with no flags drops
 * you back into your last conversation; explicit flags override.
 */
sealed interface BootstrapMode {
    /**
     * Default. Resume the most-recently-updated non-archived session for
     * this project; create a fresh session only when the project has none
     * (or all are archived). Matches how developer shells remember your
     * last working context.
     */
    object Auto : BootstrapMode

    /** Force a fresh session regardless of project history. Mapped from `--new`. */
    object ForceNew : BootstrapMode

    /**
     * Resume a session whose id starts with [prefix] (matches the `/resume
     * <id-prefix>` slash command's semantics for uniqueness; ambiguous or
     * missing prefixes fall back to fresh with a notice). Mapped from
     * `--session=<prefix>`.
     */
    data class ByPrefix(val prefix: String) : BootstrapMode
}

/** What the CLI decided to do, plus a human-readable justification for the banner. */
data class BootstrapResult(
    val sessionId: SessionId,
    /**
     * "resumed", "fresh (no prior sessions)", "fresh (prefix not found)",
     * "fresh (--new)", "resumed by prefix". Surfaced in the startup banner so
     * the user can tell at a glance whether they landed on an existing
     * session or a new one.
     */
    val reason: String,
    /** True when a brand-new session was created (needs a createSession call). */
    val createdFresh: Boolean,
)

/**
 * Resolve startup session. Pure delegation over [SessionStore] — no terminal
 * I/O, no clocks other than the injected one — so it's unit-testable without
 * a REPL harness.
 *
 * Contract:
 *  - Auto: pick max(updatedAt) of non-archived sessions scoped to the project;
 *    if none, create fresh.
 *  - ForceNew: always create fresh.
 *  - ByPrefix: walk non-archived sessions, keep ones whose id starts with the
 *    prefix; pick the single match if unique; if zero or many, fall back to
 *    fresh (reason explains which). Matching the `/resume` in-REPL semantics
 *    keeps CLI and in-session behavior aligned.
 *
 * Archived sessions are never auto-resumed — if the user explicitly archived
 * something, they don't want it to come back silently on next launch.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun bootstrapSession(
    sessions: SessionStore,
    projectId: ProjectId,
    mode: BootstrapMode,
    clock: Clock = Clock.System,
): BootstrapResult {
    suspend fun fresh(reason: String): BootstrapResult {
        val id = SessionId(Uuid.random().toString())
        val now = clock.now()
        sessions.createSession(
            Session(
                id = id,
                projectId = projectId,
                title = "Chat",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return BootstrapResult(id, reason, createdFresh = true)
    }

    return when (mode) {
        is BootstrapMode.ForceNew -> fresh("fresh (--new)")
        is BootstrapMode.ByPrefix -> {
            val prefix = mode.prefix.trim()
            if (prefix.isBlank()) return fresh("fresh (empty --session prefix)")
            val matches = sessions.listSessions(projectId)
                .filter { !it.archived && it.id.value.startsWith(prefix) }
            when (matches.size) {
                0 -> fresh("fresh (no session starts with '$prefix')")
                1 -> BootstrapResult(matches.single().id, "resumed by --session '$prefix'", createdFresh = false)
                else -> fresh("fresh (prefix '$prefix' is ambiguous — ${matches.size} sessions matched)")
            }
        }
        is BootstrapMode.Auto -> {
            val existing = sessions.listSessions(projectId)
                .filter { !it.archived }
                .maxByOrNull { it.updatedAt }
            if (existing != null) {
                BootstrapResult(existing.id, "resumed", createdFresh = false)
            } else {
                fresh("fresh (no prior sessions)")
            }
        }
    }
}
