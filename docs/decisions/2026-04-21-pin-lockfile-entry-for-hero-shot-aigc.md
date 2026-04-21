## 2026-04-21 — pin_lockfile_entry / unpin_lockfile_entry (VISION §3.1 AIGC 产物可 pin)

Commit: `e93cd05`

**Context.** VISION §3.1 lists four ingredients for "驯服 random compiler": seed
lock, model+version lock, pin of AIGC 产物 as artifact, bounded randomness. The
first three landed months ago — every `generate_*` call writes a
`LockfileEntry` with seed + model + inputHash + baseInputs, and cache hits
return the same `assetId` on matching inputs. The pin leg was still implicit:
the lockfile behaved like an LRU cache, and `gc_lockfile` / `regenerate_stale_clips`
treated every entry the same. A user who made a deliberate "this exact Mei
portrait is the hero shot" call had no one-bit escape hatch — edit an upstream
character_ref and `regenerate_stale_clips` would happily rebake the hero
through the model.

**Decision.** Add `pinned: Boolean = false` to `LockfileEntry`. Two new tools
`pin_lockfile_entry` and `unpin_lockfile_entry` flip the flag on the most
recent entry matching a given `inputHash`. Downstream:

- `GcLockfileTool` gains a "pinGuard" stage that runs *before* the live-asset
  guard: pinned rows are always rescued regardless of `maxAgeDays` /
  `keepLatestPerTool` / `preserveLiveAssets=false`. Accounting surfaces
  `keptByPinCount` separately from `keptByLiveAssetGuardCount` so the agent
  can tell which guard rescued a row.
- `RegenerateStaleClipsTool` early-skips clips whose current lockfile entry is
  pinned, emitting `reason="pinned"`. The clip stays stale-but-frozen until
  the user unpins or replaces the clip outright.
- `ListLockfileEntriesTool.Entry` exposes `pinned` so the agent can show pin
  state when orienting.
- `PruneLockfileTool` is intentionally unchanged: an orphan pinned entry (no
  surviving asset) protects nothing and is dead weight; prune should sweep it.

Registered in all five AppContainers.

**Alternatives considered.**

1. *A second kinds table keyed by assetId instead of inputHash.* Rejected —
   would force every downstream consumer to join two structures and doubles
   the invariants to maintain (pin without entry, entry without pin). A
   single field on `LockfileEntry` keeps it atomic. The OpenCode `permissions`
   pattern (a dedicated per-session store) was tempting as a precedent but
   that store has complex lifecycle needs we don't share here.
2. *Pin on the clip instead of the lockfile entry.* Rejected — the artifact
   lives in the lockfile (the model seed/version/inputs that produced it).
   Pinning the *clip* would either force us to copy all the provenance into
   the clip or create a multi-hop pin resolution. SemVer-style "pin the
   artifact, not the consumer" is the established industry norm (lockfiles
   in npm, Cargo, Bazel remote cache).
3. *Implicit pinning via `provenance.parameters` metadata.* Rejected — burying
   user intent in a free-form JSON blob makes GC behavior fragile. A typed
   boolean on the same row as the rest of the entry's invariants is simpler
   and survives schema evolution.

**Coverage.**

- `PinLockfileEntryToolTest` — pin + idempotency + missing-hash guard +
  no-cross-entry mutation + unpin variants in the same file.
- `GcLockfileToolTest.pinnedRowSurvivesStrictPolicySweep` — pinned ancient
  row keeps when `preserveLiveAssets=false` and age drops siblings.
- `GcLockfileToolTest.pinGuardAttributionTakesPriorityOverLiveAssetGuard` —
  rescue accounting goes to `keptByPinCount` when both guards would fire.
- `ListLockfileEntriesToolTest.pinnedFlagIsSurfaced` — pinned field round-trips.
- `RegenerateStaleClipsToolTest.skipsPinnedEntriesWithoutDispatching` —
  engine never runs for a pinned stale clip; reason is `"pinned"`.

**Registration.** Registered `PinLockfileEntryTool` / `UnpinLockfileEntryTool`
in `CliContainer`, `apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`, `apps/ios/Talevia/Platform/AppContainer.swift`.
