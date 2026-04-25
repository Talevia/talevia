package io.talevia.core.agent

import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.prompt.PROMPT_ONBOARDING_LANE

/**
 * Prepend a two-line identity banner ("Current project" + "Current session")
 * to the configured system prompt so every turn reminds the model of the
 * session's current binding (VISION §5.4) *and* the session id it should pass
 * to tools that require one (switch_project, fork_session, etc.). Without the
 * session line the model tends to invent ids like "current" or
 * "session-unknown", which every session-scoped tool correctly rejects.
 *
 * The project line comes first so existing `startsWith("Current project: …")`
 * assertions keep matching. A null project renders explicitly so the agent
 * knows to pick one before dispatching timeline tools rather than guessing.
 *
 * When [projectIsGreenfield] is true **and** the project is bound, the
 * onboarding lane from [PROMPT_ONBOARDING_LANE] is inserted between the
 * banner and the base prompt. The lane primes the model to scaffold a
 * `style_bible` + genre-specific source nodes before dispatching any AIGC
 * tool — otherwise greenfield traces tend to skip straight to
 * `generate_image` and produce output that can't participate in
 * `project_query(select=stale_clips)` later. The lane disappears as soon as the project has
 * any track or source node, so the token cost is paid only while it's
 * actually load-bearing.
 *
 * Extracted from `AgentTurnExecutor.kt` for the same-file LOC ceiling —
 * this is a pure function with no turn-local state, so lifting it keeps
 * `AgentTurnExecutor` focused on stream-plumbing. Behaviour is byte-
 * identical; `GreenfieldOnboardingPromptTest` still exercises the same
 * `io.talevia.core.agent.buildSystemPrompt` symbol.
 */
internal fun buildSystemPrompt(
    base: String?,
    currentProjectId: ProjectId?,
    sessionId: SessionId?,
    projectIsGreenfield: Boolean = false,
): String? {
    val projectLine = if (currentProjectId != null) {
        "Current project: ${currentProjectId.value} (from session binding; call switch_project to change)"
    } else {
        "Current project: <none> (session not yet bound; call list_projects / create_project / switch_project before running timeline tools)"
    }
    val sessionLine = sessionId?.let {
        "Current session: ${it.value} (pass this exact id as `sessionId` whenever a tool requires one; never invent one)"
    }
    val banner = listOfNotNull(projectLine, sessionLine).joinToString("\n")
    val head = if (projectIsGreenfield && currentProjectId != null) {
        banner + "\n\n" + PROMPT_ONBOARDING_LANE
    } else {
        banner
    }
    return when {
        base == null -> head
        base.isBlank() -> head
        else -> "$head\n\n$base"
    }
}
