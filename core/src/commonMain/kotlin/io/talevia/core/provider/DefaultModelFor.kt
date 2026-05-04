package io.talevia.core.provider

/**
 * Canonical default model id per provider, consumed by every app's
 * composition root when the user / agent hasn't explicitly chosen
 * a model. Single source of truth for the desktop, server, and CLI
 * apps — replaces the three byte-identical copies that lived in
 * `apps/desktop/.../ExportPresets.kt`, `apps/server/.../ServerDtos.kt`,
 * and `apps/cli/.../Repl.kt` (cycles 238 / 240 / 273 pinned each
 * separately; cycle 274 consolidated them here per
 * `debt-consolidate-defaultModelFor-three-copies`).
 *
 * Returns:
 *   - `"claude-opus-4-7"` for `"anthropic"` (per CLAUDE.md model
 *     table — the latest Claude Opus generation).
 *   - `"gpt-5.4-mini"` for `"openai"`.
 *   - `"default"` for any other provider id (the literal sentinel
 *     consumed by `ProviderRegistry` as "use the container's default
 *     ProviderRegistry pick").
 *
 * Case-sensitive matching (`Anthropic` / `ANTHROPIC` / blank /
 * unknown all fall through to `"default"`). Trailing/leading
 * whitespace is NOT trimmed — callers should pass the canonical
 * provider id; non-canonical input falls through to `"default"`
 * to surface mismatches at a single layer rather than silently
 * normalising.
 */
fun defaultModelFor(providerId: String): String = when (providerId) {
    "anthropic" -> "claude-opus-4-7"
    "openai" -> "gpt-5.4-mini"
    else -> "default"
}
