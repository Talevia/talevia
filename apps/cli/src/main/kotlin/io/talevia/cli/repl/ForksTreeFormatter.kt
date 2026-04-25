package io.talevia.cli.repl

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Single row in the `/forks` tree view. Mirrors the fields the formatter
 * actually renders rather than re-using `Session` directly so tests can
 * substitute deterministic values without instantiating the full domain
 * type.
 */
internal data class ForksTreeNode(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val archived: Boolean,
)

/**
 * Render `/forks` output — the active session's lineage (root → … →
 * parent → CURRENT) followed by its direct children, oldest → newest
 * inside each level (matches how `session_query(select=forks)` returns
 * children, oldest first).
 *
 * The current session line is highlighted with a `►` marker + accent
 * styling so an operator scanning the tree can immediately spot
 * "where I am right now". Ancestors render as the chain that led
 * here; children render as one-hop branches off the current node —
 * the same one-hop semantic `session_query(select=forks)` exposes
 * (deeper traversal stays the caller's job).
 *
 * Empty cases:
 *   - No ancestors AND no children → "this session is a root with no
 *     forks". Operator-actionable so they know `/fork` would create
 *     the first child.
 *   - Otherwise the tree omits absent levels naturally.
 */
internal fun formatForksTree(
    current: ForksTreeNode,
    ancestors: List<ForksTreeNode>,
    children: List<ForksTreeNode>,
): String {
    if (ancestors.isEmpty() && children.isEmpty()) {
        val titleEcho = displayTitle(current.title).take(TITLE_DISPLAY_CHARS)
        return Styles.meta(
            "session ${current.id.take(SHORT_ID_CHARS)} '$titleEcho' is a root " +
                "with no forks — `/fork` creates the first child branch.",
        )
    }

    val totalRows = ancestors.size + 1 + children.size
    val header = "${Styles.accent("forks")} $totalRows session(s) (root → current → children)"
    return buildString {
        appendLine(header)
        // Ancestors: root → … → parent. Indent grows so the chain is
        // visually anchored to the left edge with the current session
        // sitting at the deepest indent on the chain.
        ancestors.forEachIndexed { idx, ancestor ->
            appendLine(formatNodeLine(ancestor, depth = idx, marker = " ", current = false))
        }
        // Current — depth equals chain length so it visually continues
        // the indentation; gets the highlight marker.
        appendLine(formatNodeLine(current, depth = ancestors.size, marker = "►", current = true))
        // Children indent one level deeper than the current node.
        for (child in children) {
            appendLine(formatNodeLine(child, depth = ancestors.size + 1, marker = " ", current = false))
        }
    }.trimEnd()
}

private fun formatNodeLine(
    node: ForksTreeNode,
    depth: Int,
    marker: String,
    current: Boolean,
): String {
    val indent = "  ".repeat(depth)
    val time = formatLocalTime(node.createdAtEpochMs)
    val shortId = node.id.take(SHORT_ID_CHARS)
    val title = displayTitle(node.title).take(TITLE_DISPLAY_CHARS)
    val archivedNote = if (node.archived) Styles.meta(" (archived)") else ""
    val stylizedId = if (current) Styles.accent(shortId) else shortId
    val stylizedTitle = if (current) Styles.accent(title) else Styles.meta(title)
    return "$indent${Styles.meta(marker)} ${Styles.meta(time)}  $stylizedId  $stylizedTitle$archivedNote"
}

private fun displayTitle(raw: String): String =
    raw.takeIf(String::isNotBlank) ?: "(untitled)"

private const val SHORT_ID_CHARS = 12
private const val TITLE_DISPLAY_CHARS = 60

private fun formatLocalTime(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val date = "${local.year}-${pad2(local.monthNumber)}-${pad2(local.dayOfMonth)}"
    val time = "${pad2(local.hour)}:${pad2(local.minute)}"
    return "$date $time"
}

private fun pad2(n: Int): String = n.toString().padStart(2, '0')
