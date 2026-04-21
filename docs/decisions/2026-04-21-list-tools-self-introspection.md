## 2026-04-21 — list_tools self-introspection (VISION §5.4 专家路径)

Commit: `735308e`

**Context.** The LLM receives every tool's spec in its
`LlmRequest.tools` payload, so it "knows" what tools exist in
principle. But the LLM has no *programmatic* path to reason about
tool availability inside a turn — it can't call a tool to verify
another tool exists, and spec lists don't always survive context
trimming once a conversation grows. Three concrete flows want this:

- The agent drafts a plan: "I'll use `generate_image` then
  `synthesize_speech`." In a container where Replicate / OpenAI keys
  aren't set, one of those tools might not be registered. A
  pre-flight `list_tools("generate_")` check prevents mid-plan
  failure.
- Debugging / docs: the user asks "what tools does this build
  ship?" and the agent answers in one call instead of guessing.
- Subagent dispatch prep — once that lane lands, the parent needs
  to enumerate tools to decide which to grant to the child.

**Decision.** `ListToolsTool(registry: ToolRegistry)(prefix?, limit?)`
— returns `Summary(id, helpText, permission)` per registered tool,
sorted by id, optionally filtered by `prefix`. New `tool.read`
permission keyword defaulted to ALLOW (pure local introspection, no
external calls).

**Registration pattern** is tricky: the tool needs a reference to
the `ToolRegistry` it lives inside. Solved by registering it from
within the existing `ToolRegistry().apply { register(...) }` block
using `register(ListToolsTool(this))` — `this` is the registry
being populated. `all()` is called at tool *execute* time, not
construction, so by the time the LLM invokes it, every other tool
has already landed. On iOS the Swift variant passes `registry:
registry` the same way.

**Alternatives considered.**

1. *Use a two-phase init: declare the registry, populate it, then
   register list_tools at the end.* Rejected — every other tool is
   registered in the `apply {}` block for consistency. Splitting
   one tool out for lifecycle reasons creates an outlier the next
   reader has to understand. Reg-from-inside-apply is the tighter
   shape.
2. *Return full `ToolSpec` (with inputSchema JSON).* Rejected — the
   inputSchema is verbose and duplicates what the LLM already has
   on `LlmRequest.tools`. The agent's follow-up question after
   `list_tools` is almost always "what does it do?" — helpText +
   permission keyword answer that without the JSON-schema bulk.
3. *Expose a richer filter (by permission keyword, by category
   path).* Rejected — prefix match covers the main use case
   (`generate_*`, `list_*`, `source.*`). Richer filters are a
   natural follow-up if concrete flows appear.
4. *Hide this tool from its own output (skip `list_tools` when it
   lists itself).* Rejected — meta-tools are tools; filtering self
   is misleading. The LLM can reason about it normally.

**Coverage.** `ListToolsToolTest` — five tests: enumerates
registered tools including itself + TodoWriteTool + EchoTool;
prefix filter narrows to one match; limit caps output; helpText +
permission surface correctly for the tool's own row; empty result
on no-match prefix.

**Registration.** `ListToolsTool` registered in all five
AppContainers via `register(ListToolsTool(this))` at the start of
their `ToolRegistry().apply {}` block. New permission
`tool.read=ALLOW` added to `DefaultPermissionRuleset`.
