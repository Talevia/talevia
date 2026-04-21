## 2026-04-21 — compact_session manual trigger (VISION §5.4 专家路径)

Commit: `215f113`

**Context.** `Compactor` in `core/compaction/Compactor.kt` has always
run automatically from the Agent loop when the estimated history
crosses 120k tokens. The behaviour is correct but opaque to the
agent — two concrete flows want proactive manual control:

- Before a long autonomous task the agent wants to free context
  budget up-front: "compact now, then start the big render sweep."
- After an aggressive debug session the user says "clean up this
  session's context before we continue" — no reason to wait for the
  automatic threshold.

Plus an agent-reasoning flow: if the LLM sees `hasCompactionPart` on
`describe_session` and wants to trigger a second compaction to audit
what gets summarised the second time.

**Decision.** `CompactSessionTool(providers, sessions, bus)(sessionId)`.
Thin adapter on `Compactor.process`:

- Fetches the session, reads history (non-compacted view), picks the
  `ModelRef` from the **most recent assistant turn** (that's the
  provider the session has been talking to; compacting through a
  different provider would distort the summary).
- Looks that provider up in the injected `ProviderRegistry`. If
  unregistered, skip with a reason — a stale model reference can
  happen after a container swap and shouldn't 500.
- Constructs a fresh `Compactor(provider, store, bus)` with default
  thresholds and calls `.process(sessionId, history, model)`.

Output surfaces the compaction `partId` + a 200-char `summaryPreview`
on success; `skipReason` on skip. The full summary lives on the
emitted `Part.Compaction` — callers drill via `read_part`.

Permission `session.write` — compaction mutates state (`time_compacted`
stamps on older parts + a new Compaction part).

**Alternatives considered.**

1. *Require the caller to pass `model: ModelRef` explicitly.*
   Rejected — the agent already has all the info it needs to derive
   the model (latest assistant turn), and making the LLM type it out
   is friction for zero benefit. The fallback "pick latest assistant
   turn" is load-bearing.
2. *Inject a long-lived `Compactor` into the tool instead of
   constructing one per call.* Rejected — `Compactor` binds a
   specific `LlmProvider` at construction; sessions can use
   different providers (cross-container forks, config changes). Per-
   call construction is cheap (one allocation) and the provider
   lookup has to happen anyway. Matches the `RevertSessionTool`
   pattern which also builds `SessionRevert` per-call.
3. *Expose `protectUserTurns` / `pruneProtectTokens` as inputs.*
   Rejected — the defaults (2 / 40k) match what Agent uses
   automatically, and exposing them invites an agent that reasons
   about thresholds incorrectly. If a concrete flow needs them
   later, add optional fields then.
4. *Return the full summary on `data.summary` instead of a 200-char
   preview.* Rejected — `Part.Compaction.summary` can be ~2 KB; the
   agent already has the partId to drill via `read_part`, same way
   describe_message doesn't inline full payloads.

**Coverage.** `CompactSessionToolTest` — four tests: happy path with
3 large user+assistant turns proves pruning + summarisation fires
and a `Part.Compaction` lands on the store; empty session (no
assistant messages) skips with the right reason; missing sessionId
fails loud; session referencing an unregistered provider skips with
a reason instead of throwing.

**Registration.** `CompactSessionTool` registered in all five
AppContainers' init blocks — same post-`providers` init pattern the
`list_providers` cycle established. Reuses `session.write`
permission.
