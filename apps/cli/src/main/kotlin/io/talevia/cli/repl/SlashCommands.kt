package io.talevia.cli.repl

/** Single source of truth for the slash command catalogue. */
data class SlashCommandSpec(
    val name: String,
    val help: String,
    val argHint: String = "",
)

val SLASH_COMMANDS: List<SlashCommandSpec> = listOf(
    SlashCommandSpec("/new", "create a fresh session in this project"),
    SlashCommandSpec("/sessions", "list sessions in this project"),
    SlashCommandSpec("/resume", "switch to the session whose id starts with <prefix>", argHint = "<prefix>"),
    SlashCommandSpec("/model", "show or override the model id (same provider)", argHint = "[<id>]"),
    SlashCommandSpec("/cost", "token + usd totals for the current session"),
    SlashCommandSpec("/todos", "show the agent's current todo list for this session"),
    SlashCommandSpec("/clear", "clear the screen (keeps the session)"),
    SlashCommandSpec("/help", "this list"),
    SlashCommandSpec("/exit", "exit"),
    SlashCommandSpec("/quit", "exit"),
)
