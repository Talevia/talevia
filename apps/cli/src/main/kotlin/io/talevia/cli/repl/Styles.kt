package io.talevia.cli.repl

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles

/**
 * Central CLI ANSI style palette. Each helper wraps its input in ANSI codes via
 * Mordant — `Styles.prompt("> ")` returns the styled string.
 *
 * Toggle off with [setEnabled] when stdout isn't a TTY (pipes, file redirection)
 * so we don't pollute downstream consumers with escape codes. When disabled,
 * each helper returns its input unchanged.
 */
internal object Styles {
    @Volatile private var enabled: Boolean = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun banner(text: String): String =
        if (enabled) (TextStyles.bold + TextColors.brightCyan).invoke(text) else text

    fun meta(text: String): String =
        if (enabled) TextStyles.dim.invoke(text) else text

    fun prompt(text: String): String =
        if (enabled) (TextStyles.bold + TextColors.brightCyan).invoke(text) else text

    fun running(text: String): String =
        if (enabled) TextColors.yellow.invoke(text) else text

    fun ok(text: String): String =
        if (enabled) TextColors.green.invoke(text) else text

    fun fail(text: String): String =
        if (enabled) TextColors.red.invoke(text) else text

    fun error(text: String): String =
        if (enabled) (TextStyles.bold + TextColors.red).invoke(text) else text

    fun warn(text: String): String =
        if (enabled) (TextStyles.bold + TextColors.yellow).invoke(text) else text

    fun toolId(text: String): String =
        if (enabled) TextStyles.bold.invoke(text) else text

    fun accent(text: String): String =
        if (enabled) TextColors.cyan.invoke(text) else text
}
