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
 * Build the interactive [LineReader] with slash-command completion. Typing
 * `/<Tab>` or the unambiguous prefix + Tab expands to the matching command;
 * typing `/` on an otherwise-empty line also auto-pops the menu via a
 * custom widget bound to the `/` key so the user doesn't have to know about
 * Tab in the first place.
 */
fun buildInteractiveLineReader(terminal: Terminal): LineReader {
    val completer = slashCompleter()
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

private fun slashCompleter(): Completer = Completer { _, parsed: ParsedLine, candidates ->
    // Only offer slash-command completion on the first word of the buffer.
    if (parsed.wordIndex() != 0) return@Completer
    val word = parsed.word()
    if (word.isNotEmpty() && !word.startsWith("/")) return@Completer
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
