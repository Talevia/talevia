package io.talevia.core.tool.builtin.session

import io.talevia.core.JsonConfig
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.ToolState
import kotlinx.serialization.json.Json

/**
 * Human-readable transcript renderer for
 * `session_action(action="export", format="markdown")` — the
 * markdown path of the unified export action. The JSON envelope is
 * for portability / `session_action(action="import")` /
 * cross-instance archival; this formatter is for eyeball
 * consumption (sharing a debug session in a bug report, pasting a
 * transcript into a doc, reviewing a session offline).
 *
 * Shape:
 *
 * ```
 * # Session: <title>
 *
 * - **id**: <session-id>
 * - **project**: <project-id>
 * - **format**: talevia-session-export-md-v1
 * - **messages**: <count>  · **parts**: <count>
 *
 * ---
 *
 * ## user · 2026-04-25T22:11:03Z · `m-abcd1234`
 *
 * <user text>
 *
 * ## assistant (claude-opus-4-7) · 2026-04-25T22:11:08Z · `m-1234abcd`
 *
 * <assistant text>
 *
 * > [!TOOL] generate_image · `c-9be4` · completed
 * >
 * > **input**
 * > ```json
 * > {"prompt": "...", "model": "..."}
 * > ```
 * >
 * > **output**
 * > <text the LLM saw>
 * ```
 *
 * Trade-offs:
 *
 * - Tool calls fold into blockquote-prefixed sections so a reader can
 *   visually separate "tool noise" from prose without scrolling past
 *   3 kB of JSON. Compact-printed input keeps individual tool spans
 *   short; the full data blob lives in the JSON envelope for anyone
 *   who needs the structured shape.
 * - Reasoning parts use the GitHub-flavoured `> [!REASONING]` callout
 *   so renderers that recognise admonitions render them visibly,
 *   while plain-text readers still see the labelled blockquote.
 * - Compaction parts likewise become `> [!COMPACTION]` callouts —
 *   the summary is the most informative thing on a long session
 *   timeline and shouldn't sink into the prose stream.
 * - Render-progress / step-start / step-finish are intentionally
 *   **dropped** — they're high-frequency low-signal in a transcript
 *   reader's eye. (`session_query(select=parts)` still surfaces them
 *   for forensic dives.)
 * - Media parts get a one-line marker (`[media: <assetId>]`) — the
 *   markdown isn't meant to inline binary assets; pair with
 *   `export_project` if visual reproduction matters.
 *
 * Format-version embedded in the header so future breaking changes
 * (different section layout, different callout vocabulary) bump the
 * version without quietly drifting away from old archives. Stays
 * separate from the JSON envelope's `talevia-session-export-v1`
 * version on purpose: the markdown is a derived view, not the
 * canonical persistence format.
 */
internal const val SESSION_MARKDOWN_FORMAT_VERSION: String = "talevia-session-export-md-v1"

/**
 * Render the session as a markdown transcript. Pure function; the
 * caller (the tool) is responsible for `Session` / `Message` / `Part`
 * fetch.
 */
internal fun formatSessionAsMarkdown(
    session: Session,
    messages: List<Message>,
    parts: List<Part>,
): String {
    val partsByMessage: Map<String, List<Part>> = parts.groupBy { it.messageId.value }
    val sb = StringBuilder()
    sb.append("# Session: ").append(escapeMarkdownInline(session.title)).append('\n')
    sb.append('\n')
    sb.append("- **id**: `").append(session.id.value).append("`\n")
    sb.append("- **project**: `").append(session.projectId.value).append("`\n")
    sb.append("- **created**: ").append(session.createdAt).append('\n')
    sb.append("- **updated**: ").append(session.updatedAt).append('\n')
    sb.append("- **format**: ").append(SESSION_MARKDOWN_FORMAT_VERSION).append('\n')
    sb.append("- **messages**: ").append(messages.size)
        .append("  ·  **parts**: ").append(parts.size).append('\n')
    if (session.archived) {
        sb.append("- **archived**: yes\n")
    }
    if (session.parentId != null) {
        sb.append("- **forked-from**: `").append(session.parentId.value).append("`\n")
    }
    sb.append('\n')

    if (messages.isEmpty()) {
        sb.append("---\n\n_(empty session — no messages yet)_\n")
        return sb.toString()
    }

    sb.append("---\n\n")
    for (msg in messages) {
        sb.append(formatMessageHeader(msg)).append('\n').append('\n')
        val msgParts = partsByMessage[msg.id.value].orEmpty()
        for (part in msgParts) {
            val rendered = renderPart(part) ?: continue
            sb.append(rendered)
            if (!rendered.endsWith("\n\n")) {
                if (rendered.endsWith("\n")) sb.append('\n') else sb.append("\n\n")
            }
        }
    }
    return sb.toString().trimEnd() + "\n"
}

private fun formatMessageHeader(msg: Message): String {
    val idTail = msg.id.value.takeLast(8)
    return when (msg) {
        is Message.User ->
            "## user · ${msg.createdAt} · `$idTail`"

        is Message.Assistant -> {
            val modelTag = msg.model.modelId.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            "## assistant$modelTag · ${msg.createdAt} · `$idTail`"
        }
    }
}

/**
 * Render one [Part] as a markdown chunk, or `null` to drop it. Pure;
 * caller stitches blocks together with blank lines.
 */
private fun renderPart(part: Part): String? = when (part) {
    is Part.Text -> {
        val body = part.text.trimEnd()
        if (body.isBlank()) null else body + "\n"
    }
    is Part.Reasoning -> renderCallout("REASONING", part.text)
    is Part.Compaction -> renderCallout("COMPACTION", part.summary)
    is Part.Tool -> renderToolPart(part)
    is Part.Media -> "_[media: `${part.assetId.value}`]_\n"
    is Part.Todos -> renderTodos(part)
    is Part.Plan -> renderPlan(part)
    is Part.TimelineSnapshot ->
        "_[timeline snapshot · ${part.timeline.tracks.size} track(s) · " +
            "${part.timeline.tracks.sumOf { it.clips.size }} clip(s)]_\n"
    // High-frequency low-signal in eyeball view — let JSON envelope keep them.
    is Part.RenderProgress -> null
    is Part.StepStart -> null
    is Part.StepFinish -> null
}

private fun renderToolPart(part: Part.Tool): String {
    val callTail = part.callId.value.takeLast(6)
    val state = when (part.state) {
        is ToolState.Pending -> "pending"
        is ToolState.Running -> "running"
        is ToolState.Completed -> "completed"
        is ToolState.Failed -> "failed"
        is ToolState.Cancelled -> "cancelled"
    }
    val sb = StringBuilder()
    sb.append("> [!TOOL] `").append(part.toolId).append("` · `").append(callTail).append("` · ")
        .append(state).append('\n')
    sb.append(">\n")
    val input = when (val s = part.state) {
        is ToolState.Running -> s.input
        is ToolState.Completed -> s.input
        is ToolState.Failed -> s.input
        is ToolState.Cancelled -> s.input
        is ToolState.Pending -> null
    }
    if (input != null) {
        sb.append("> **input**\n")
        sb.append("> ```json\n")
        TOOL_JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), input)
            .lines()
            .forEach { sb.append("> ").append(it).append('\n') }
        sb.append("> ```\n")
        sb.append(">\n")
    }
    when (val s = part.state) {
        is ToolState.Completed -> {
            sb.append("> **output**\n")
            blockquoteEachLine(sb, s.outputForLlm)
        }
        is ToolState.Failed -> {
            sb.append("> **failed**: ").append(s.message).append('\n')
        }
        is ToolState.Cancelled -> {
            sb.append("> **cancelled**: ").append(s.message).append('\n')
        }
        else -> Unit
    }
    return sb.toString()
}

private fun renderCallout(label: String, body: String): String {
    val sb = StringBuilder()
    sb.append("> [!").append(label).append("]\n")
    blockquoteEachLine(sb, body)
    return sb.toString()
}

private fun renderTodos(part: Part.Todos): String {
    val sb = StringBuilder()
    sb.append("**Todos** (").append(part.todos.size).append(" item(s))\n\n")
    for (t in part.todos) {
        val box = when (t.status.name.lowercase()) {
            "completed" -> "[x]"
            "in_progress" -> "[~]"
            "cancelled" -> "[-]"
            else -> "[ ]"
        }
        sb.append("- ").append(box).append(' ').append(t.content).append('\n')
    }
    return sb.toString()
}

private fun renderPlan(part: Part.Plan): String {
    val sb = StringBuilder()
    sb.append("**Plan**: ").append(escapeMarkdownInline(part.goalDescription))
        .append("  ·  status: ").append(part.approvalStatus.name.lowercase()).append("\n\n")
    for ((i, step) in part.steps.withIndex()) {
        sb.append((i + 1)).append(". `").append(step.toolName).append("` — ")
            .append(escapeMarkdownInline(step.inputSummary))
            .append("  ·  ").append(step.status.name.lowercase()).append('\n')
    }
    return sb.toString()
}

private fun blockquoteEachLine(sb: StringBuilder, body: String) {
    val lines = body.lines()
    for (line in lines) {
        if (line.isEmpty()) sb.append(">\n") else sb.append("> ").append(line).append('\n')
    }
}

/** Strip the few characters that would unambiguously break inline rendering. */
private fun escapeMarkdownInline(s: String): String =
    s.replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\n", " ")

/**
 * Compact JSON used for tool input echo — the markdown view targets a
 * one-glance overview, so `prettyPrint = false` keeps each tool block
 * short. Readers who need the full pretty payload should use the JSON
 * envelope (`format = json` or omit the parameter).
 */
private val TOOL_JSON: Json get() = JsonConfig.default
