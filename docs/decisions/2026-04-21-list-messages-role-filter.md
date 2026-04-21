## 2026-04-21 — list_messages role filter (VISION §5.4 专家路径)

Commit: `87e84f6`

**Context.** The session-audit lane already has `list_messages`, but
the default call dumps both sides of the conversation interleaved.
Two recurring audit / debug flows only want one side:

- "Show me just the user prompts from session X" — auditing what the
  human asked for, independent of how the agent chose to respond.
- "Show me just what the assistant produced" — skim what the model
  decided, step past the user's restatements.

Today an audit flow has to fetch all messages and client-side filter,
which also means the `limit` knob is wrong: a `limit=10` on a mixed
session might return 8 assistants + 2 users, so the caller has to
over-fetch and discard. Filtering **before** `take(cap)` makes the
cap mean what the caller wants ("give me the 10 most recent user
messages"), and keeps `totalMessages` honest as the true *filtered*
total — matching the convention the recent `onlyPinned` /
`onlySourceBound` single-field filters established on
`list_lockfile_entries` / `list_source_nodes`.

**Decision.** Add `role: String? = null` to `ListMessagesTool.Input`.
Value is `trim()`ed and `lowercase()`d, then validated against
`{"user", "assistant"}` — anything else raises
`IllegalArgumentException` with a message listing the valid values
*and* the offending input so the agent can self-correct on the next
turn. `null` / empty after trim = return both (default unchanged).

Filter is applied **before** `sortedByDescending { createdAt }.take(cap)`:

```
all → filterByRole → sort desc → take(cap)
                   ↑ totalMessages counts this stage
```

So a session with 4 users + 4 assistants + `role="user", limit=2`
reports `totalMessages=4, returnedMessages=2`, matching the user's
intent.

The `outputForLlm` summary composes a `role=user` / `role=assistant`
scope hint into both the empty-result and populated-result branches,
following the `scopeParts` pattern already in use on
`list_lockfile_entries`.

JSON Schema surfaces `"enum": ["user", "assistant"]` on the `role`
property so Anthropic / OpenAI can constrain the value server-side
before the LLM emits it. `helpText` explicitly calls out the
filter-before-limit semantics so the agent doesn't misinterpret
`totalMessages`.

Reuses `session.read` permission — still read-only. No new registration
needed; the tool is already wired into all five AppContainers.

**Alternatives considered.**

1. *Add a single `onlyAssistant: Boolean` flag (mirror `onlyPinned`).*
   Rejected — there are only two roles today, but the boolean shape
   privileges one of them. "Show me only user prompts" then has to be
   spelled as `onlyAssistant=false`, which reads wrong and doesn't
   generalise if we ever add `system` / `tool` rows as first-class
   message kinds. A scalar `role` accommodates future roles without
   re-shaping the schema.
2. *Introduce a Kotlin `enum class Role { USER, ASSISTANT }` on the
   Input.* Rejected for this cycle — the wire format is a JSON string
   either way (`kotlinx.serialization` serialises enums as strings by
   default), and the LLM-facing schema still has to be a string with
   an `enum` constraint. Using `String?` keeps the fail-loud
   normalisation (`"USER"` / `" user "` → `"user"`) trivial and
   mirrors how `list_source_nodes` accepts `kind: String?` rather than
   a typed enum. Revisit if we ever grow a third filter dimension
   that would benefit from exhaustive `when` on the call site.
3. *Ship two separate tools `list_user_messages` /
   `list_assistant_messages` and leave `list_messages` as "both".*
   Rejected — triples the tool surface the LLM has to remember for a
   single-axis filter, and duplicates the `sessionId` / `limit` /
   ordering logic. The `list_*` house style (see `list_tool_calls`,
   `list_lockfile_entries`, `list_source_nodes`) is "one list tool
   per entity, filters as optional fields."
4. *Validate via JSON Schema `enum` alone and skip the runtime
   `require(...)`.* Rejected — not every call site goes through the
   LLM-generated schema (tests, scripted callers, SDK users invoking
   the tool directly via `RegisteredTool.dispatch`), and the
   schema-only path means a typo silently returns both roles. A loud
   `IllegalArgumentException` with the valid values + the offending
   input is the cheaper debugging path.

**Coverage.** `ListMessagesToolTest` gains six tests:

- `roleUserReturnsOnlyUserRows` — 2 user + 2 assistant seeded;
  `role="user"` returns exactly the two user rows, sorted
  most-recent-first.
- `roleAssistantReturnsOnlyAssistantRows` — symmetric check for the
  assistant side.
- `roleNullReturnsAllRows` — default (no filter) still returns all 4,
  preserving the pre-change smoke-test surface.
- `roleUppercaseIsNormalized` — `role="USER"` matches `role="user"`
  row-for-row, confirming the `trim() + lowercase()` normalisation.
- `roleInvalidFailsLoudly` — `role="ghost"` raises
  `IllegalArgumentException` whose message includes `"user"`,
  `"assistant"`, and the offending `"ghost"`.
- `roleFilterComposesWithLimit` — 4 user + 4 assistant seeded;
  `role="user", limit=2` returns exactly 2 user entries, and
  `totalMessages` reports `4` (the true filtered total), not `8`
  (pre-filter) or `2` (post-cap).

Existing tests (`emptySessionReturnsZero`,
`messagesSortedMostRecentFirst`, `assistantRoleCarriesTokensAndFinish`,
`userRoleCarriesAgentAndModel`, `limitCaps`, `missingSessionFailsLoud`,
`errorFieldRoundTrips`) are preserved unchanged.

**Registration.** No new registration — this is a pure schema extension
of the existing `ListMessagesTool`, which is already wired into
`CliContainer`, `apps/desktop/AppContainer`,
`apps/server/ServerContainer`, `apps/android/AndroidAppContainer`, and
`apps/ios/Talevia/Platform/AppContainer.swift`. Permission unchanged
(`session.read`).
