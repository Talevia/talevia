## 2026-04-21 — estimate_tokens meta utility (VISION §5.4 Agent/UX)

Commit: `34f9f83`

**Context.** VISION §5.4 asks whether the agent can *anticipate* context-window
pressure before it commits to a large prompt or pastes a bulky artefact into
the session. Today the agent has two signals: (a) `TokenUsage` on a prior
assistant message, which is the provider's real tokenizer count but only
exists *after* a turn has completed, and (b) the compactor's internal
`TokenEstimator.forText` (~4 chars/token), which already drives
"should-we-compact?" heuristics in `core.compaction`. (b) was not reachable
from a tool call, so a planning-phase agent asking "does this 30k char doc
fit in my remaining budget?" had to guess or just throw it in and hope. That
pairs badly with `list_tools` / `list_providers`, which already let the agent
introspect *static* capabilities but not the *dynamic* size of what it's
about to say.

**Decision.** Add `EstimateTokensTool` in `core.tool.builtin.meta` — a thin
wrapper around `TokenEstimator.forText(input.text)`. Input is a single
required `text: String`, empty/blank rejected via `require(...)`. Output is
`{ tokens: Int, characters: Int, approxCharsPerToken: Double }` — the ratio
field is a sanity check (should be ~4 for normal text) that also helps the
agent notice when the heuristic might be off (e.g. dense structured JSON
where real tokenizers differ more). `outputForLlm` is a one-liner that
flags the heuristic nature and nods at `TokenUsage` as the real source. Read-only,
permission `tool.read` (default ALLOW in `DefaultPermissionRuleset`) —
same as `list_tools`, for the same reason: pure local computation, no I/O,
no side effects.

**Alternatives considered.**

1. *Ship a real tokenizer (tiktoken-kotlin or similar).* Rejected — would
   drag a ~1MB BPE merges table into `core/commonMain`, couple the core to
   a specific provider's vocabulary (Anthropic vs OpenAI disagree), and
   duplicate work the provider already does for free on every turn. The
   heuristic has been good enough to drive compaction decisions; it's also
   good enough to answer "will this fit?" at planning time. The tool's help
   text explicitly points at `TokenUsage` for exact numbers.
2. *Return only `tokens`.* Rejected — `characters` and
   `approxCharsPerToken` cost nothing to compute and let the agent spot
   when a blob is token-dense (lots of structured JSON or CJK) versus
   prose-dense. A single scalar would have been strictly less useful.
3. *Accept a list of texts / a whole `MessageWithParts`.* Rejected for v1
   — `TokenEstimator.forHistory` already covers conversation-wide
   estimates internally during compaction, but exposing it as a tool would
   require the agent to reconstruct `MessageWithParts` JSON, which is
   worse UX than just asking the agent to sum several single-text calls.
   Revisit if we see the agent repeatedly batching.
4. *Fold into `list_tools` as an overload.* Rejected — violates the
   "grep and ls are separate for a reason" principle we applied to
   `search_source_nodes` vs `list_source_nodes`. Mixing introspection of
   registered capabilities with text measurement would conflate two mental
   models in one tool.

**Coverage.** `EstimateTokensToolTest` — five tests: `estimatesShortText`
(non-zero tokens, correct `characters`), `longerTextGetsMoreTokens`
(exact `(1000+3)/4 = 250` — guards the heuristic against silent drift),
`blankTextFailsLoudly` (empty + whitespace both `IllegalArgumentException`
with message mentioning `text`), `outputTitleAndSummaryMentionTokenCount`
(ensures the LLM-facing surface carries the number, not just the typed
payload), `approxCharsPerTokenIsComputed` (ratio of 4.0 for `"x"*1000`,
and sanity-bounds check for ASCII text).

**Registration.** `EstimateTokensTool` registered next to `ListToolsTool`
in `apps/cli/CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`, `apps/android/AndroidAppContainer.kt`,
and `apps/ios/Talevia/Platform/AppContainer.swift`.
