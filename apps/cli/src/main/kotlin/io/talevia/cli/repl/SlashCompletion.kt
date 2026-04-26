package io.talevia.cli.repl

import org.jline.keymap.KeyMap
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.ParsedLine
import org.jline.reader.Reference
import org.jline.reader.Widget
import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp

/**
 * Mutable holder of completion candidates for the slash-command second
 * word. The reader is built before the [Repl] owns the
 * [io.talevia.cli.CliContainer] (it's needed for the secret-bootstrap
 * prompt), so we wire the holder empty up front and the Repl populates
 * the lambdas once it has access to a session store + a stable
 * project / session id pair.
 *
 * Each lambda is invoked synchronously per Tab press from the JLine
 * completer thread; concrete implementations wrap the suspend
 * SessionStore reads in `runBlocking`. SQLite list queries are local
 * and well under a millisecond, so the blocking call is invisible to
 * users.
 */
class SlashArgSources(
    @Volatile @JvmField var sessionIds: () -> List<String> = { emptyList() },
    @Volatile @JvmField var messageIds: () -> List<String> = { emptyList() },
)

/**
 * Build the interactive [LineReader] with slash-command + arg
 * completion. Typing `/` on an empty line auto-pops the candidate list
 * (unselected), and the list **live-refilters** as the user types extra
 * characters — Claude-Code-style. Tab / ↑ / ↓ enter menu-complete to
 * actually pick a candidate. After the command, Tab on the second word
 * completes id-prefix arguments for `/resume <session-id-prefix>` and
 * `/revert` / `/fork <message-id-prefix>` from the [argSources] producer
 * lambdas.
 */
fun buildInteractiveLineReader(
    terminal: Terminal,
    argSources: SlashArgSources = SlashArgSources(),
): LineReader {
    val completer = slashCompleter(argSources)
    val reader = LineReaderBuilder.builder()
        .terminal(terminal)
        .completer(completer)
        .option(LineReader.Option.AUTO_MENU, true)
        .option(LineReader.Option.AUTO_LIST, true)
        .option(LineReader.Option.LIST_PACKED, true)
        .option(LineReader.Option.AUTO_FRESH_LINE, true)
        // AUTO_MENU_LIST renders the interactive list during menu-complete.
        // We deliberately do NOT set Option.MENU_COMPLETE — that would make
        // even the first Tab / `/` keypress pre-select the first candidate,
        // which felt aggressive ("why is /clear already chosen before I
        // pressed anything?"). Selection is opt-in: list-choices on `/`
        // shows the menu unselected, then Tab / ↑ / ↓ enter selection mode.
        .option(LineReader.Option.AUTO_MENU_LIST, true)
        .build()
    // JLine's defaults paint the menu with a chunky background fill:
    //  - LIST_BACKGROUND defaults to `bg:bright-magenta` (the entire menu
    //    area gets a purplish bar, regardless of which row is selected).
    //  - LIST_SELECTION defaults to `inverse` (the selected row swaps fg/bg,
    //    producing yet another solid fill on top of the bg above).
    // Both are overridden so the menu reads as plain text with only the
    // selected row's font emphasised — readable on dark + light terminals.
    reader.setVariable(LineReader.COMPLETION_STYLE_LIST_BACKGROUND, "bg:default")
    reader.setVariable(LineReader.COMPLETION_STYLE_LIST_SELECTION, "fg:bright-cyan,bold")

    wireSlashAutoMenu(reader, terminal)
    return reader
}

/** One completion candidate as a (value, description) pair. */
internal data class ArgCandidate(val value: String, val description: String?)

/**
 * Compute the second-word completion candidates for a slash command —
 * pure function, no JLine types, so the test layer can drive it
 * directly. Returns `null` when the position doesn't take args (the
 * caller should then fall back to first-word slash-name completion).
 *
 * Id-prefix candidates are clipped to 12 characters because that's the
 * width every dispatcher already accepts and what /history /sessions
 * displays — keeping the suggestion length aligned with what the user
 * sees elsewhere avoids the "completed string didn't match what
 * /history showed" surprise.
 */
internal fun computeArgCandidates(
    firstWord: String,
    currentArg: String,
    sources: SlashArgSources,
): List<ArgCandidate>? = when (firstWord) {
    "/resume" -> sources.sessionIds()
        .map { it.take(12) }
        .filter { it.startsWith(currentArg) }
        .distinct()
        .map { ArgCandidate(it, "session id prefix") }
    "/revert", "/fork" -> sources.messageIds()
        .map { it.take(12) }
        .filter { it.startsWith(currentArg) }
        .distinct()
        .map { ArgCandidate(it, "message id prefix") }
    else -> null
}

private fun slashCompleter(argSources: SlashArgSources): Completer =
    Completer { _, parsed: ParsedLine, candidates ->
        when (parsed.wordIndex()) {
            0 -> completeSlashName(parsed.word(), candidates)
            1 -> {
                val firstWord = parsed.words().firstOrNull().orEmpty()
                val arg = parsed.word()
                computeArgCandidates(firstWord, arg, argSources)?.forEach { c ->
                    candidates += Candidate(
                        /* value = */ c.value,
                        /* displ = */ c.value,
                        /* group = */ null,
                        /* descr = */ c.description,
                        /* suffix = */ null,
                        /* key = */ null,
                        /* complete = */ true,
                    )
                }
            }
            else -> Unit // Third+ word — no completion today.
        }
    }

private fun completeSlashName(word: String, candidates: MutableList<Candidate>) {
    if (word.isNotEmpty() && !word.startsWith("/")) return
    SLASH_COMMANDS
        .filter { it.name.startsWith(word.ifEmpty { "/" }) }
        .forEach { cmd ->
            candidates += Candidate(
                /* value = */ cmd.name,
                /* displ = */ cmd.name,
                /* group = */ null,
                /* descr = */ cmd.help,
                /* suffix = */ null,
                /* key = */ null,
                /* complete = */ true,
            )
        }
}

/**
 * Wire the slash-command discoverability:
 *
 * 1. `/` on an empty buffer inserts `/` and shows the candidate list via
 *    `list-choices` — visible but **unselected**, so users see what's
 *    available without the menu pre-grabbing their first command.
 * 2. Every printable keystroke (and backspace) while the buffer is a
 *    slash-command name re-runs `list-choices`, giving Claude-Code-style
 *    live filtering as the user types.
 * 3. Tab enters menu-complete (selects the first candidate, then cycles
 *    on subsequent presses). With `Option.AUTO_MENU_LIST = true` ↑/↓/←/→
 *    move the highlight while the menu is active.
 * 4. ↑ / ↓ also enter menu-complete (forward / reverse), but **only when
 *    the buffer is a slash-command name**. On a normal prompt they keep
 *    the default history-navigation behaviour so non-slash flows aren't
 *    disrupted.
 *
 * Pressing `/` anywhere mid-buffer inserts a literal `/` (no menu) — that
 * preserves `file:///tmp/x` and similar paths.
 *
 * Dumb terminals (piped stdin, CI, tests) get `complete-word` as the
 * auto-pop widget and skip the live-filter / arrow bindings — the
 * cursor-driven menu only renders on real terminals.
 */
private fun wireSlashAutoMenu(reader: LineReader, terminal: Terminal) {
    val mainKeymap = reader.keyMaps[LineReader.MAIN] ?: return
    val isDumb = terminal.type == "dumb"

    // `/` auto-pop: insert the slash, then list candidates without selecting.
    val listOnlyWidget = if (isDumb) LineReader.COMPLETE_WORD else LineReader.LIST_CHOICES
    val slashAutoMenuName = "talevia-slash-auto-menu"
    reader.widgets[slashAutoMenuName] = Widget {
        val wasAtStart = reader.buffer.length() == 0
        reader.buffer.write('/'.code)
        if (wasAtStart) {
            reader.callWidget(listOnlyWidget)
        }
        true
    }
    mainKeymap.bind(Reference(slashAutoMenuName), KeyMap.translate("/"))

    if (isDumb) return

    wireLiveFilter(reader, mainKeymap)
    wireSelectionKeys(reader, mainKeymap, terminal)
}

/**
 * Wrap [LineReader.SELF_INSERT] and [LineReader.BACKWARD_DELETE_CHAR] so
 * that every typed/deleted character refreshes the candidate list when
 * the buffer is a slash-command name (`/<word>` with no spaces yet).
 *
 * The wrappers delegate to the originals first (so the visible buffer
 * mutation is unchanged) and only call `list-choices` when the buffer
 * still describes a slash-command name. Once the user types a space or
 * backspaces past the leading `/`, the wrappers no-op and JLine returns
 * to ordinary line-editing.
 */
private fun wireLiveFilter(reader: LineReader, mainKeymap: KeyMap<org.jline.reader.Binding>) {
    fun isSlashName(): Boolean {
        val buf = reader.buffer.toString()
        return buf.startsWith("/") && !buf.contains(' ')
    }
    fun refresh() {
        runCatching { reader.callWidget(LineReader.LIST_CHOICES) }
    }

    reader.widgets[LineReader.SELF_INSERT]?.let { original ->
        val wrapper = "talevia-self-insert-refresh"
        reader.widgets[wrapper] = Widget {
            val ok = original.apply()
            if (isSlashName()) refresh()
            ok
        }
        // Bind every printable ASCII slot to the wrapper. We pass the raw
        // single-character string to `bind` directly — going through
        // `KeyMap.translate` blows up on `^`, `\\`, etc. because those are
        // readline escape-sequence prefixes that expect more characters
        // (`StringIndexOutOfBoundsException` on translate).
        for (c in 0x20..0x7E) {
            // Slash is owned by the auto-pop widget above.
            if (c == '/'.code) continue
            mainKeymap.bind(Reference(wrapper), c.toChar().toString())
        }
    }

    reader.widgets[LineReader.BACKWARD_DELETE_CHAR]?.let { original ->
        val wrapper = "talevia-backspace-refresh"
        reader.widgets[wrapper] = Widget {
            val ok = original.apply()
            if (isSlashName()) refresh()
            ok
        }
        // Backspace = ASCII DEL (0x7F) on most terminals, BS (0x08) on a few.
        mainKeymap.bind(Reference(wrapper), 0x7F.toChar().toString())
        mainKeymap.bind(Reference(wrapper), 0x08.toChar().toString())
    }
}

/**
 * Bind Tab and ↑/↓ to enter menu-complete mode on slash-command names,
 * leaving normal lines undisturbed.
 *
 * `KeyMap.key(...)` returns null when the terminal doesn't expose the
 * named capability (rare for `key_up`/`key_down`, but the result is
 * checked so a missing escape sequence falls back to JLine's defaults
 * rather than nulling out the binding).
 */
private fun wireSelectionKeys(
    reader: LineReader,
    mainKeymap: KeyMap<org.jline.reader.Binding>,
    terminal: Terminal,
) {
    fun isSlashName(): Boolean {
        val buf = reader.buffer.toString()
        return buf.startsWith("/") && !buf.contains(' ')
    }

    // Tab: enter menu-complete (interactive selection). `complete-word` is
    // JLine's default; rebinding to `menu-complete` skips the "first Tab
    // lists, second Tab cycles" two-step so a single Tab on the already-
    // displayed list moves straight into selection mode.
    mainKeymap.bind(Reference(LineReader.MENU_COMPLETE), KeyMap.translate("\t"))

    val arrowUp = "talevia-arrow-up-or-history"
    reader.widgets[arrowUp] = Widget {
        val target =
            if (isSlashName()) LineReader.REVERSE_MENU_COMPLETE
            else LineReader.UP_LINE_OR_HISTORY
        reader.callWidget(target)
        true
    }
    val arrowDown = "talevia-arrow-down-or-history"
    reader.widgets[arrowDown] = Widget {
        val target =
            if (isSlashName()) LineReader.MENU_COMPLETE
            else LineReader.DOWN_LINE_OR_HISTORY
        reader.callWidget(target)
        true
    }
    KeyMap.key(terminal, InfoCmp.Capability.key_up)?.let {
        mainKeymap.bind(Reference(arrowUp), it)
    }
    KeyMap.key(terminal, InfoCmp.Capability.key_down)?.let {
        mainKeymap.bind(Reference(arrowDown), it)
    }
}
