## 2026-04-19 — `regenerate_stale_clips` tool — closes the §3.2 refactor loop

**Context.** After the source edit → `find_stale_clips` → regenerate →
`replace_clip` chain had its pieces landing over many commits, the one-call
closure was still missing. The agent could see what's stale but had no way
to "just regenerate" without manually reconstructing each original tool
call — and the original base prompt isn't recoverable from
`provenance.parameters` (which holds the *folded* prompt, not the raw
input), so every agent would have to guess / hand-author this wiring.

**Decision.**
- Add `baseInputs: JsonObject` field to `LockfileEntry` (default empty for
  legacy entries). AIGC tools (`GenerateImageTool`, `GenerateVideoTool`,
  `SynthesizeSpeechTool`, `GenerateMusicTool`, `UpscaleAssetTool`) encode
  their raw `Input` via `Input.serializer()` and pass it through
  `AigcPipeline.record(baseInputs = …)`. Stored alongside the existing
  `sourceContentHashes` snapshot.
- New tool `RegenerateStaleClipsTool` under `tool/builtin/project/`. For
  each entry in `project.staleClipsFromLockfile()`:
    1. look up the lockfile entry by assetId,
    2. resolve the original `ToolRegistry` entry from `entry.toolId`,
    3. `registered.dispatch(entry.baseInputs, ctx)` — consistency folding
       re-runs against today's source graph, producing a fresh generation,
    4. identify the new lockfile entry by "size went up by one", read its
       `assetId`, and swap the clip's assetId + sourceBinding in a single
       `ProjectStore.mutate` (same inline logic as `ReplaceClipTool`),
    5. emit exactly one `TimelineSnapshot` after the batch completes.
- Skip rules (surfaced on `Output.skipped` with human-readable reasons):
  legacy entries with empty `baseInputs`, missing tool registrations,
  cache-hit regenerations (no new lockfile entry), and mid-flight clip
  vanish.
- Permission: `"aigc.generate"` — one grant covers the batch. Callers who
  say "regenerate every stale clip" are explicitly consenting to N aigc
  calls under that single grant. Chain-of-trust is acceptable here because
  the user asked for exactly this side effect.
- Wired into desktop + server containers alongside
  `FindStaleClipsTool`; the registration passes `this` (the `AppContainer`'s
  `ToolRegistry`, `tools`) so the regenerate tool sees the same registered
  set the agent sees. Registration happens after `this.register(...)` calls
  have populated the registry (registration order doesn't matter; dispatch
  resolves at call time).
- Unit coverage in `RegenerateStaleClipsToolTest`: happy path (regenerate
  one stale clip, verify assetId swap + binding copy + single engine call),
  legacy-entry skip, and empty-project no-op.

**Alternatives considered.**
- **Derive base inputs from `provenance.parameters`.** Rejected — provenance
  records the wire body (post-fold effective prompt + provider-specific
  extras), not the caller's pre-fold input. Re-dispatching with that would
  double-fold the consistency bindings.
- **Re-dispatch via Agent's normal permission flow per clip.** Rejected —
  would force N permission prompts for a batch the user already consented
  to. The batch-consent model ("one aigc.generate grant covers N
  regenerations under this call") is the right UX trade.
- **Have the tool also re-export.** Rejected — `ExportTool`'s stale-guard
  already unblocks export once the clips are fresh; tying regenerate to
  export would fuse two steps that should remain independent (user may
  want to regenerate and review before exporting).
- **Direct assetId swap without copying `sourceBinding` from the new
  entry.** Rejected — copying the binding matches `ReplaceClipTool`
  behavior so future stale-drift detection stays correct.

**Why.** VISION §6 worked example ("修改主角发色 → … 只重编译这些镜头") was
the flagship demo that had no one-call path. This tool makes it
demonstrable end-to-end in one agent turn: user edits character_ref →
agent calls `regenerate_stale_clips` → every bound AIGC clip refreshes
with the new character, the export stale-guard clears, export proceeds.
That's the §3.2 / §5.1 claim operationalised.

**How to apply.** Future AIGC tools MUST call `AigcPipeline.record` with
`baseInputs = JsonConfig.default.encodeToJsonElement(Input.serializer(),
input).jsonObject` — otherwise their outputs will become
regenerate-resistant (the tool will skip with a "legacy entry" reason
even on brand-new entries). There is no enforcement beyond convention;
if we see a second forgetting, fold `baseInputs` construction into
`AigcPipeline` directly via a helper that takes the serializer + input.

---
