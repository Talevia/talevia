## 2026-04-22 — session_query(select=cache_stats) aggregates prompt-cache usage (VISION §5.4 debug)

Commit: `b56af7c`

**Context.** `TokenUsage` already carries `cacheRead` / `cacheWrite` on
every assistant message; Anthropic / OpenAI / Gemini providers all
populate it (the Anthropic impl explicitly notes "Normalise to match
OpenAI's semantics: `input` is the total input, with `cacheRead` /
`cacheWrite` as subsets" — so `cacheRead / input` is provider-agnostic).
The data was there but the agent had no programmatic way to ask "what
fraction of this session's input tokens was cache-served?". Only path
was grepping `BusEvent.MessageUpdated` for token updates and summing
by hand. VISION §5.4 debug lane covers "is my prompt cache firing?" —
this is the missing read.

**Decision.** New `select=cache_stats` on `SessionQueryTool`, mirroring
the `SpendQuery` shape (single-row aggregate, sessionId-required):

1. **New row type `SessionQueryTool.CacheStatsRow`** with
   `(sessionId, assistantMessageCount, totalInputTokens, cacheReadTokens,
   cacheWriteTokens, hitRatio)`. `hitRatio` is `cacheRead /
   totalInput`, clamped to `[0.0, 1.0]`, and explicitly `0.0` (not
   `NaN`) when `totalInput == 0`.

2. **New file
   `core/.../tool/builtin/session/query/CacheStatsQuery.kt`** with
   `runCacheStatsQuery(sessions, input)` that:
   - Requires non-null `sessionId` (error-out pattern mirrors
     SpendQuery).
   - Walks `sessions.listMessages(sessionId)` and sums `TokenUsage`
     across `Message.Assistant` entries only. User / tool-result
     messages don't carry token counts — providers attribute cost to
     the assistant response they powered.
   - Zero-message / zero-input edge case reports `hitRatio=0.0`
     explicitly (divide-by-zero guard — surfacing `NaN` to the LLM
     would confuse the model).

3. **Dispatcher + schema + constant** additions in
   `SessionQueryTool.kt` (`SELECT_CACHE_STATS = "cache_stats"`, added
   to `ALL_SELECTS`, routed in `execute()`) and
   `SessionQueryToolSchema.kt` (the `select` field's description
   enumerates the new value). No `rejectIncompatibleFilters` changes
   — cache_stats uses only `sessionId`, and the existing cross-filter
   guards already reject `role`/`kind`/`includeCompacted`/`toolId`/
   `messageId` when `select != their-respective-select`.

4. **No new tool.** One new `select` on the existing unified
   `session_query` primitive — the same consolidation move already
   made for spend, status, session_metadata, etc. Zero net tool-count
   growth.

**Alternatives considered.**

1. **Expose as a separate `get_session_cache_stats` tool** — rejected
   on §3a rule 1 (no net tool growth). `session_query` is the designated
   unified read primitive for session state; adding a sibling tool
   would split the read surface again, exactly the kind of fragmentation
   the `session_query` consolidation fought against. Discriminator
   `select=cache_stats` is ~20 tokens of schema vs. ~150 for a new
   tool spec.

2. **Weight by recency (last N turns only)** — rejected. The agent can
   already drill into recent messages via `select=messages` or
   `select=session_metadata` and compute a windowed ratio itself.
   Baseline "entire-session aggregate" is the debug-friendly default;
   windowing is a different question (cache effectiveness for the last
   burst) that belongs in a later cycle if it surfaces.

3. **Include user / tool-result messages in the count** — rejected.
   Those messages don't carry `TokenUsage` on their Kotlin shape;
   Provider-side, user and tool-result tokens count toward the *next*
   assistant's input, which is what `Message.Assistant.tokens.input`
   already reflects. Counting them again would double-count the input.

4. **Report `hitRatio` as a percent rather than a 0.0..1.0 double** —
   rejected. The typed field stays unambiguous in its unit (fraction);
   the prose `outputForLlm` already renders a percentage
   (`hitRatio=53.3%`) for human / model readability. Typed clients
   prefer fractions (no unit-doc needed); text clients get the %.

**Coverage.** New `SessionQueryCacheStatsTest` — 5 cases:

- `missingSessionIdRejected` — select requires sessionId.
- `missingSessionErrors` — non-existent session id rejected.
- `emptySessionReturnsZerosAndRatioZero` — zero-message edge case
  (§3a rule 9: the "totalInput=0 → ratio=0.0 not NaN" invariant has a
  dedicated assertion).
- `singleMessageHitRatioComputedCorrectly` — 500/1000 = 0.5.
- `multipleMessagesAggregateAcrossTurns` — 3-message aggregate with
  different `cacheRead` / `cacheWrite` patterns; asserts the
  `1600/3000` = ~0.533 ratio and the cacheWrite sum.
- `sessionWithNoCacheSignalReportsZeroRatio` — totalInput > 0 but
  cacheRead = 0 (provider didn't populate cache info) → hitRatio=0.

Existing `SessionQueryToolTest` / `SessionQuerySpendTest` / other
`select=` tests pass unchanged — no change to their code paths.

**Registration.** No registration change — same `SessionQueryTool`
already wired in all 5 AppContainers (CLI / Desktop / Server /
Android / iOS). The new select is a pure additive extension behind
the existing dispatcher.

LLM context cost (§3a rule 10): `SELECT_CACHE_STATS` enum-value in
the schema doc plus the new row shape adds ~35 tokens to the
`session_query` spec bundle per turn. Well under the 500-token
threshold.

---
