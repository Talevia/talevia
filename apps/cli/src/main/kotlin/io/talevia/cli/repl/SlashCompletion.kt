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
 * completion. Typing `/<Tab>` or the unambiguous prefix + Tab expands
 * to the matching command; typing `/` on an otherwise-empty line also
 * auto-pops the menu via a custom widget bound to the `/` key so the
 * user doesn't have to know about Tab in the first place. After the
 * command, Tab on the second word completes id-prefix arguments for
 * `/resume <session-id-prefix>` and `/revert` / `/fork
 * <message-id-prefix>` from the [argSources] producer lambdas.
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
        .build()

    wireSlashAutoMenu(reader)
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
 * Bind the `/` key so that pressing it on an empty buffer inserts `/` and
 * immediately triggers the completion menu. Pressing `/` anywhere else inserts
 * `/` normally (no menu) so inline paths like `file:///tmp/x` aren't hijacked.
 *
 * In dumb-terminal fallback (e.g. stdin piped, tests) widgets are inert —
 * the builder still works and the key stays a plain literal insert.
 */
private fun wireSlashAutoMenu(reader: LineReader) {
    val widgetName = "talevia-slash-auto-menu"
    reader.widgets[widgetName] = Widget {
        val wasAtStart = reader.buffer.length() == 0
        reader.buffer.write('/'.code)
        if (wasAtStart) {
            reader.callWidget(LineReader.COMPLETE_WORD)
        }
        true
    }
    reader.keyMaps[LineReader.MAIN]?.bind(Reference(widgetName), KeyMap.translate("/"))
}
