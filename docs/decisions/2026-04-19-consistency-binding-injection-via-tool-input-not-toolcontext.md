## 2026-04-19 — Consistency-binding injection via tool input, not ToolContext

**Context.** AIGC tools need access to consistency nodes at execution time. We could
surface them on `ToolContext` (every tool sees them) or on each tool's typed input
(only tools that want them declare them).

**Decision.** Each AIGC tool declares `projectId: String?` + `consistencyBindingIds:
List<String>` on its typed input. The tool carries a `ProjectStore?` via its
constructor and resolves bindings via `Source.resolveConsistencyBindings(ids)` during
`execute`. Tools without bindings / without a store fall back to prompt-only behavior.

**Why input, not context.**
- **Discoverability for the LLM.** Input fields appear in the tool's JSON schema, which
  is what the model reads. A field on `ToolContext` would be invisible to the model.
- **Narrow blast radius.** `ToolContext` is shared by every tool (timeline edits, echo,
  etc.); loading the project source eagerly for every dispatch would be wasted work
  for the vast majority of calls.
- **Tool-by-tool opt-in.** Some AIGC tools (e.g. future TTS on a named character) want
  bindings; some (e.g. a generic SFX synth) don't. Input declaration lets each tool
  own that choice.
