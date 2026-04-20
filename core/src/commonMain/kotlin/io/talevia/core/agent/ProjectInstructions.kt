package io.talevia.core.agent

/**
 * A project-scoped instruction file (typically `AGENTS.md` or `CLAUDE.md`) the host
 * has chosen to inject into the system prompt. [path] is the source on disk so the
 * model can reference it back (and humans reading a trace can locate the text).
 */
data class ProjectInstruction(val path: String, val content: String)

/**
 * Render a list of [ProjectInstruction]s into a system-prompt suffix. The shape is
 * a single "# Project context" section with each file delimited by its path, so
 * the model reads them as extra rules rather than as transcript content.
 *
 * Ordering is the caller's responsibility. Convention is outermost-first /
 * innermost-last — more-specific guidance appears later so it carries more
 * weight (LLMs typically favour the tail of the system prompt on conflicts).
 *
 * Returns the empty string when [instructions] is empty or all contents are
 * blank, so callers can pass it through `extraSuffix` unconditionally.
 */
fun formatProjectInstructionsSuffix(instructions: List<ProjectInstruction>): String {
    val nonBlank = instructions.filter { it.content.isNotBlank() }
    if (nonBlank.isEmpty()) return ""
    return buildString {
        append("# Project context\n\n")
        append(
            "The host wired in the following project / user instruction file(s). Treat each " +
                "as authoritative guidance for THIS project, on top of (not instead of) the " +
                "rules above. If two files contradict, prefer the later one — convention is " +
                "outermost-first / innermost-last so the nearest file wins.\n",
        )
        for ((index, instruction) in nonBlank.withIndex()) {
            append('\n')
            append("## ").append(instruction.path).append('\n')
            append('\n')
            append(instruction.content.trimEnd())
            append('\n')
            if (index < nonBlank.size - 1) append('\n')
        }
    }
}
