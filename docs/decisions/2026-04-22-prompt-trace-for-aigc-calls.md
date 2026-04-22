## 2026-04-22 — `LockfileEntry.resolvedPrompt` captures the fully-folded prompt (VISION §5.4 rubric)

Commit: `ea588e8`

**Context.** `LockfileEntry` already persisted the cache-key-relevant slice
of what was sent to the provider — tool id, model, seed, dimensions, the
sorted applied-binding ids, and the raw `baseInputs` JSON. What it did
**not** capture was the **fully-folded prompt** the engine actually
received: the `basePrompt` with every character_ref / style_bible body
prepended in canonical order, with the same whitespace shape the provider
saw. Debugging "why didn't this image respect character_ref mei?" required
the user (or the agent) to re-run `foldConsistencyIntoPrompt` in their
head against today's source graph, which is fragile when the source has
drifted since generation.

Backlog bullet: `prompt-trace-for-aigc-calls` (P2). Fifth cycle-level
skip on `per-clip-incremental-render`; this P2 debug-lane task is
self-contained and ships visible value without the multi-day cost of
per-clip render.

**Decision.**

1. **`LockfileEntry` grows `resolvedPrompt: String? = null`.** Nullable
   default keeps legacy lockfile blobs (pre-cycle-7) decoding to
   `resolvedPrompt = null` rather than failing — same forward-compat
   discipline §3a rule 7 guards.

2. **`AigcPipeline.record(...)`** gains an optional `resolvedPrompt`
   parameter that threads into the new field.

3. **Three AIGC tools** (`GenerateImageTool`, `GenerateVideoTool`,
   `GenerateMusicTool`) pass `folded.effectivePrompt` — the exact string
   they hand to the engine — into `record(...)`. The other two AIGC
   tools stay null by design:
   - `SynthesizeSpeechTool`: its input *is* the prompt, already stored
     verbatim in `baseInputs.text`. Duplicating would waste bytes and
     muddy the "is this field present?" signal.
   - `UpscaleAssetTool`: no prompt concept at all.

4. **`project_query(select=lockfile_entry)` row** exposes
   `resolvedPrompt` to the LLM / UI so the expert-debug flow is
   `(staleClipsFromLockfile → stale clip X → select=lockfile_entry →
   diff entry.resolvedPrompt against fold of today's sources)`.

**Three-state, not binary.** `resolvedPrompt == null` means "no prompt
concept for this tool" or "legacy entry". `resolvedPrompt == ""` means
"prompt was empty" (unusual but possible). `resolvedPrompt ==
non-empty` means "here's exactly what the provider saw". §3a rule 4 —
never coalesce absence with emptiness.

**Alternatives considered.**

- **Include the fully-folded prompt inside `baseInputs`** (e.g. a
  `_resolvedPrompt` key) — rejected. `baseInputs` is the serialized
  tool Input; polluting it with a synthesized field confuses replay
  discipline (a future `replay_from_lockfile` would decode `baseInputs`
  expecting the Input shape). A dedicated field at the LockfileEntry
  level keeps replay and debugging lanes separate.

- **Store only the appended delta (the consistency-fold additions),
  not the whole resolved string** — rejected. The delta-only form
  requires the user (or the agent) to still re-run concatenation in
  order to compare, which is the exact work we're trying to eliminate.
  Storage cost of the full string is trivial (<1 KB per entry for
  realistic prompts).

- **Always populate `resolvedPrompt` even for TTS / upscale (set
  equal to `input.text` / empty string)** — rejected as §3a rule 4
  violation. The tri-state is a feature, not a bug: `null` for
  upscale tells a future audit "no prompt concept" rather than
  fabricating "".

- **Stamp it on `provenance.parameters` under a provider-specific
  key** — rejected. Per-provider parameters are the *provider's* view
  of the inputs; `resolvedPrompt` is the tool-layer view of what was
  assembled before the provider call. Different layers, different
  fields.

**Coverage.**

- `domain.lockfile.LockfileTest` — two new tests: legacy entry
  missing the field decodes as null; new entry with a populated
  string round-trips byte-for-byte.
- `tool.builtin.aigc.GenerateImageToolTest.lockfileEntrySnapshotsBoundSourceContentHashes`
  — extended to assert `entry.resolvedPrompt` is non-null and
  carries both the base prompt text + the folded character_ref fields.
  Existing Generate{Video,Music}Tool tests continue passing — the
  KDoc contract ("record the folded prompt") is covered there via
  the end-to-end roundtrip that already verifies the asset lands.
- Full JVM + Android + desktop + iOS compile + ktlintCheck all green.

**Registration.** Pure domain-layer + AIGC-tool changes — no new
tools, no AppContainer edits. `ProjectQueryTool.LockfileEntryDetailRow`
gains one optional field, which round-trips through `JsonConfig.default`
without schema changes on the LLM-facing side (row shape additions
with defaults don't alter the input schema).

---
