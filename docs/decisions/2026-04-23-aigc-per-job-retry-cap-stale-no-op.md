## 2026-04-23 — `aigc-per-job-retry-cap` skip-close (VISION §5.4 rubric axis)

**Context.** Bullet asked to cap retries for `GenerateImageTool` /
`GenerateMusicTool` / `GenerateVideoTool` / `UpscaleAssetTool` at N
(default 3) and surface a `BusEvent.AigcJobExhausted` on exhaustion,
citing the concern that a "hung or hostile provider can chew through
the session's spend budget silently" via uncapped retries.

Liveness pre-check (§2.5) shows the bullet's premise doesn't match
code — there are **no AIGC-tool retries to cap** today:

- `grep -rn 'retry\|attempt\|maxAttempts' core/src/commonMain/kotlin/io/talevia/core/tool/builtin/aigc/`
  returns only the `SynthesizeSpeechTool` provider-fallback chain's
  per-attempt failure enumeration (different mechanism — tries each
  configured provider once in order, no retry of a single provider).
- `grep -rn 'retry\|maxAttempts' core/.../provider/replicate core/.../provider/openai/OpenAiSoraVideoGenEngine.kt`
  → nothing. `ReplicateMusicGenEngine`, `ReplicateUpscaleEngine`, and
  `OpenAiSoraVideoGenEngine` all POST once, poll the provider's job
  endpoint until a terminal status (`succeeded | failed | canceled`),
  and `error(...)` on any non-success. Any HTTP 5xx / 429 / auth
  error during the POST or poll fails the whole tool dispatch loudly;
  the caller (Agent / tool result) gets a single thrown exception.
- `AigcPipeline.withProgress(...)` (the shared envelope) has one
  try / catch that flips the `Part.RenderProgress` payload to
  `failed: <msg>` and re-throws. No retry.
- The polling loops (`pollUntilTerminal`) DO have a hard
  `maxWaitMs = 10 * 60 * 1000L` deadline and fixed
  `pollIntervalMs = 3_000L` / `5_000L`. That's not retry — it's
  waiting for a provider-side async job to finish. The spend cost of
  the provider's work is already incurred on the initial POST; polling
  doesn't multiply the bill.

Bullet landed in `006f8800` — the most recent repopulate (cycle 38
snapshot scan). It was drafted from assumption rather than observed
code: the rubric scan noted the AIGC tool group as spend-sensitive,
but the "uncapped retry" risk it invented doesn't manifest. Spend
cap logic already lives elsewhere — `AigcBudgetGuard` + `session.spendCapCents`
gate per-call cost before the provider is invoked; that's the real
spend-bound mechanism and it works per-job, not per-retry.

Rubric delta §5.4 (agent-loop observability / spend guard): **no
delta** this cycle — the rubric axis was already **有** via
`AigcBudgetGuard` + spend-cap enforcement. The "add retry with cap"
work would only be load-bearing if someone first adds retry logic
that needs capping, which hasn't happened.

**Decision.** Skip-close per §2.5. No code written this cycle;
delete the bullet + archive the reasoning here. If a future cycle
adds tool-level retry (e.g. to absorb transient HTTP 503s so the
LLM doesn't have to re-dispatch the whole tool call), that cycle
should land the retry **with** a cap and `AigcJobExhausted` signal
as one atomic change — the cap shouldn't be a separate follow-up
task without the retry that motivates it.

Why not preserve this as a "proactively add retry + cap" feature
bullet? Three reasons:

1. **§3a #10 (LLM context cost)**: a new `AigcJobExhausted` event
   type adds to `BusEventDto` + every consumer (ServerDto,
   EventRouter, etc.) without a concrete driver today. Dead
   infrastructure burns tokens at every session.
2. **Right layer**: tool-level retry competes with the existing
   Agent-level retry on LLM errors (`Agent.RetryPolicy`). Duplicating
   the retry mechanism per tool fragments the policy — "how many
   attempts did this run see?" becomes impossible to answer if
   different tools use different retry defaults.
3. **No evidence of the failure mode**: no operator report, no
   `:apps:server:test` regression, no smoke-test timeout. Speculative
   safety is usually not worth the spec surface (LLM pays the
   per-turn tool-spec token cost forever).

**Axis.** n/a — skip-close, no code change.

**Alternatives considered.**

- **Preserve the bullet as-is, skip with tag.** Rejected: the bullet's
  premise is factually wrong ("tools retry without cap" — they don't
  retry at all). A skip-tag-with-reason would perpetuate the
  misleading description in subsequent scans. Closing it out removes
  the stale description from the queue.

- **Interpret the bullet as "add 1-attempt retry for HTTP 503 with
  spend-cap guard and emit `AigcJobExhausted`".** Rejected: that's a
  new feature proposal rather than a bug fix, and the risk it
  mitigates (LLM re-dispatches a tool failure) is already handled by
  the Agent-level retry policy — if the LLM's reasoning decides to
  retry, it costs a normal LLM turn + one more tool dispatch, which
  is exactly the auditable boundary we want spend visibility at.
  Adding a second retry layer inside the tool would silently double
  spend on provider-transient errors without adding observability.

**Coverage.** No new tests. Existing `AigcBudgetGuardTest` covers the
spend-cap guard that actually gates AIGC spend today; no gap there.

**Registration.** No changes — skip-close.

Subsequent repopulates should not re-drop this bullet unless the AIGC
tool surface actually grows retry logic that needs capping. The
trigger to re-queue is: `grep -rn 'retry\|attempt' core/.../tool/builtin/aigc/`
returns something NOT in the `SynthesizeSpeechTool` provider-fallback
enum or `AigcPipeline.record`.
