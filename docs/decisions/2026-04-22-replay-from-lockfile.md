## 2026-04-22 — `replay_lockfile` re-dispatches past AIGC generations with cache bypassed (VISION §5.2 rubric)

Commit: `937d24d`

**Context.** `Project.lockfile` already records every AIGC generation's
inputHash + toolId + seed + model version + resolvedPrompt + baseInputs
(shape finalised across the last ~8 cycles). What was missing: a primitive
that, given an existing `inputHash`, **re-runs that exact generation**.
Without it, VISION §5.2 "相同 source + 相同 toolchain 重跑产物是否
bit-identical" had no agent-visible invocation path — the recipe was
frozen in the lockfile but you couldn't ask the system "re-cook it".

Calling the original AIGC tool with the same inputs doesn't work: the
tool's own `AigcPipeline.findCached` short-circuit returns the cached
asset without ever calling the provider. So "replay" needs to signal
"bypass your cache this once" through the normal dispatch path.

`CompareAigcCandidatesTool` was the closest prior art (same pattern:
look up a target tool in the registry, dispatch with a modified input
JSON, capture results). But compare fans out across *different models*
with unique inputHashes each; replay needs to hit the *same* inputHash
and force fresh generation — a different signal.

**Decision.** New `replay_lockfile` tool + one-bit `ctx.isReplay` on
`ToolContext`, both in `core/commonMain`:

1. `ReplayLockfileTool(registry, projects)` — takes `(inputHash,
   projectId?)`. Looks up the `LockfileEntry`, rejects legacy entries
   with empty `baseInputs` and unregistered `toolId`s, then dispatches
   the target tool with `entry.baseInputs` and a child
   `ctx.forReplay()` that flips `isReplay=true`. Reads the freshly
   appended `LockfileEntry` from `project.lockfile.entries.last()` to
   compose the Output.
2. `ToolContext.isReplay: Boolean = false` + `ToolContext.forReplay()`
   helper. Defaulting to `false` means every existing test-harness
   `ToolContext(…)` call site keeps compiling — no ripple.
3. 5 AIGC tools (`generate_image`, `generate_video`, `generate_music`,
   `synthesize_speech`, `upscale_asset`) gain a one-line guard: the
   `findCached` short-circuit now requires `!ctx.isReplay`. Recording
   stays unconditional so the replay lands its own lockfile entry for
   audit + side-by-side comparison.

Registered in all 5 AppContainers (CLI / Desktop / Server / Android /
iOS) with applicability `RequiresProjectBinding` — replay without a
project-bound session is impossible by construction (no lockfile to
read).

Output includes both `originalAssetId` + `newAssetId`, both provenance
records, and a `inputHashStable` flag. `inputHashStable=false` means the
target tool's consistency fold re-ran against a drifted source graph and
computed a new inputHash — that case overlaps with
`regenerate_stale_clips` but we surface it explicitly so the agent
doesn't have to re-derive it.

**Alternatives considered.**

1. **Add `replayFromLockfile: String?` parameter to each of 5 AIGC tool
   Inputs** — the bullet's other option. Rejected: puts replay logic
   behind 5 tool surfaces with mostly duplicated "unpack baseInputs
   back into Input, run without cache" branches; bloats each Input
   schema (~40 tokens × 5 = 200 tokens added per turn, in a direction
   the Input is *supposed* to be user-facing). A dedicated primitive
   concentrates the semantic ("replay this past generation") in one
   place and leaves the 5 AIGC tools uncluttered. Kotlin / OpenCode
   convention: cross-cutting control gates (cancellation, retries,
   replay) flow through the dispatch context, not each typed Input.

2. **Verify bit-identity at the byte level (sha256 of asset bytes)** —
   rejected for this cycle. Would require a platform-level
   `BlobReader` interface we don't have in `core/commonMain` (FileSystem
   is text-only; MediaStorage only resolves paths). Added as a
   follow-up direction in the backlog bullet itself rather than
   dragging a new platform interface into this change. Today's Output
   ships both asset ids so the caller can inspect externally.

3. **Mutate the original lockfile entry in place (replace assetId)** —
   rejected. The original is a durable audit record ("this is what we
   produced on <date>"); overwriting it loses the ability to compare
   before/after. Append-only lockfile semantics are intentional
   (`Lockfile` KDoc calls this out). `byInputHash` already returns the
   most-recent entry via `associateBy`, so the replay's entry becomes
   the cache-hit target for future lookups — exactly the "this is the
   latest canonical" behaviour we want without destroying history.

4. **Piggyback on `CompareAigcCandidatesTool` by passing a single-model
   `models=[orig]`** — rejected. Compare's cache-interaction is
   "whatever the callee does"; it doesn't bypass cache. A one-model
   compare would be a cache hit and never call the provider. Different
   control signal, different tool.

5. **Skip the lockfile record on replay (diagnostic-only semantics)** —
   considered, rejected. The replay *did* call the provider and cost
   money; it should show up in `session_query(select=spend)` the same
   way a fresh `generate_image` would. Not recording would also silently
   drop the new asset from `project_query(select=lockfile)` — the user
   couldn't pin it. Appending is the honest ledger.

**Coverage.** New `ReplayLockfileToolTest` in
`core/src/jvmTest/.../aigc/` covers the full §3a rule-9 semantic surface:
cache-bypass path actually calls the provider a second time (counting
fake engine asserts `calls==2`); missing inputHash throws with a
discoverable message; legacy `baseInputs.isEmpty()` entries throw
separately; unregistered target tool throws; `ctx.forReplay()` flips the
flag and preserves other fields; `projectId: null` defaults from session
binding via `ctx.resolveProjectId`. All 6 tests pass alongside the
existing AIGC tool suites which were unchanged by the `!ctx.isReplay`
guard (default `false` preserves baseline).

**Registration.** `ReplayLockfileTool(registry, projects)` wired in all
five composition roots:

- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
- `apps/ios/Talevia/Platform/AppContainer.swift` (via SKIE-exposed
  Kotlin constructor)

Tool-count net growth: `core/tool/builtin/aigc/` goes 6 → 7 tools
(+1). §3a rule 1 threshold is "≥ +2 requires explicit defense"; +1 is
below that bar. Defense anyway: this is a cross-cutting ledger primitive,
not a new AIGC kind — it doesn't inflate the "how many generators do we
have?" axis that the rule is guarding against.

LLM context cost (§3a rule 10): new tool spec ~170 tokens per turn
(~130 helpText + ~40 schema). Below the 500-token flag threshold.
`ctx.isReplay` is invisible to the LLM.

---
