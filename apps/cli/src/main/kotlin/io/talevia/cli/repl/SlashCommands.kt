package io.talevia.cli.repl

/** Single source of truth for the slash command catalogue. */
data class SlashCommandSpec(
    val name: String,
    val help: String,
    val argHint: String = "",
    val category: SlashCategory = SlashCategory.META,
)

/**
 * Buckets `/help` prints as sub-headings so the command list reads like a
 * reference card instead of a flat wall. Ordered by how often an operator
 * reaches for each group.
 */
enum class SlashCategory(val title: String) {
    SESSION("session"),
    HISTORY("history + branching"),
    MODEL("model + stats"),
    META("meta"),
}

val SLASH_COMMANDS: List<SlashCommandSpec> = listOf(
    SlashCommandSpec(
        "/new",
        "create a fresh session in this project",
        category = SlashCategory.SESSION,
    ),
    SlashCommandSpec(
        "/sessions",
        "list sessions in this project",
        category = SlashCategory.SESSION,
    ),
    SlashCommandSpec(
        "/resume",
        "switch to the session whose id starts with <prefix>",
        argHint = "<prefix>",
        category = SlashCategory.SESSION,
    ),
    SlashCommandSpec(
        "/status",
        "one-line snapshot of the active session, model, and token totals",
        category = SlashCategory.SESSION,
    ),
    SlashCommandSpec(
        "/history",
        "list turns with the message ids /revert + /fork accept as anchors",
        category = SlashCategory.HISTORY,
    ),
    SlashCommandSpec(
        "/revert",
        "rewind session + timeline to an earlier turn (deletes later turns)",
        argHint = "<messageId-prefix>",
        category = SlashCategory.HISTORY,
    ),
    SlashCommandSpec(
        "/fork",
        "branch this session (optionally at a past turn) and switch to the new branch",
        argHint = "[<messageId-prefix>]",
        category = SlashCategory.HISTORY,
    ),
    SlashCommandSpec(
        "/model",
        "show or override the model id (same provider)",
        argHint = "[<id>]",
        category = SlashCategory.MODEL,
    ),
    SlashCommandSpec(
        "/cost",
        "token + usd totals for the current session",
        category = SlashCategory.MODEL,
    ),
    SlashCommandSpec(
        "/spend",
        "AIGC cost roll-up for the current session (per-provider breakdown)",
        category = SlashCategory.MODEL,
    ),
    SlashCommandSpec(
        "/metrics",
        "dump in-process counters + wall-time histograms (ops visibility)",
        category = SlashCategory.MODEL,
    ),
    SlashCommandSpec(
        "/todos",
        "show the agent's current todo list for this session",
        category = SlashCategory.MODEL,
    ),
    SlashCommandSpec("/clear", "clear the screen (keeps the session)", category = SlashCategory.META),
    SlashCommandSpec("/help", "this list", category = SlashCategory.META),
    SlashCommandSpec("/exit", "exit", category = SlashCategory.META),
    SlashCommandSpec("/quit", "exit", category = SlashCategory.META),
)

/**
 * Levenshtein edit distance — used by the REPL to suggest "did you mean ..."
 * for unknown slash commands. Bounded by input length so the O(n*m) cost is
 * bounded at ~400 ops for any realistic slash command.
 */
internal fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    val m = a.length
    val n = b.length
    if (m == 0) return n
    if (n == 0) return m
    val prev = IntArray(n + 1) { it }
    val curr = IntArray(n + 1)
    for (i in 1..m) {
        curr[0] = i
        for (j in 1..n) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(
                curr[j - 1] + 1,
                prev[j] + 1,
                prev[j - 1] + cost,
            )
        }
        for (k in 0..n) prev[k] = curr[k]
    }
    return prev[n]
}

/**
 * Find the best "did you mean" candidate for an unknown `/name` the user
 * typed. Returns the first command within [maxDistance] edits, or `null`
 * when nothing close enough matches — so we don't badger the user with
 * wildly irrelevant suggestions.
 */
fun suggestSlash(typed: String, maxDistance: Int = 2): SlashCommandSpec? {
    val needle = if (typed.startsWith("/")) typed else "/$typed"
    return SLASH_COMMANDS
        .asSequence()
        .map { it to editDistance(it.name, needle) }
        .filter { it.second <= maxDistance }
        .minByOrNull { it.second }
        ?.first
}
