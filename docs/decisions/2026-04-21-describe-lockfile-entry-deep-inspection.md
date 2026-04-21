## 2026-04-21 — describe_lockfile_entry (VISION §3.1 lockfile audit / §5.3 artifact inspection)

Commit: `93f5195`

**Context.** `list_lockfile_entries` (cycle from earlier) surfaces the
lockfile as a paginated bird's-eye view, returning the common fields
across up to 200 rows. For single-entry audit the user needs the fields
the list trims for breadth:

- `baseInputs` — the raw pre-fold tool inputs. The agent needs this to
  understand why `regenerate_stale_clips` did or didn't re-dispatch an
  entry (legacy/no-baseInputs is a documented skip reason; see
  [RegenerateStaleClipsTool] kdoc).
- `sourceContentHashes` — the snapshot of each bound source node's hash
  at generation time. Comparing against current hashes answers "which
  source edit caused this clip to go stale?" cleanly.
- Derived signals — is the entry currently stale, which nodes drifted,
  which timeline clips reference the asset.

Without this tool the agent had to `list_lockfile_entries` → client-side
filter for the row → reconstruct the staleness query by walking the
project's source + lockfile manually. Three mental round-trips per debug.

**Decision.** `DescribeLockfileEntryTool` — single-entry read keyed on
`inputHash` (same semantics as `Lockfile.findByInputHash` so the two tools
agree on which row they mean). Returns:

- full `provenance` (providerId, modelId, modelVersion, seed, createdAt),
- `sourceBindingIds` (sorted) and `sourceContentHashes`,
- raw `baseInputs` + `baseInputsEmpty` boolean for the legacy-skip question,
- `pinned` boolean (cycle 1 of a prior loop),
- **derived** `currentlyStale` computed by comparing each snapshotted hash
  to the current `Source.nodes` state — same lane `find_stale_clips` uses,
- **derived** `driftedNodes` — per-node `(nodeId, snapshotHash, currentHash)`
  triples so the agent can point at the exact node that changed,
- **derived** `clipReferences` — clips on the current timeline whose
  `assetId` equals this entry's `assetId`. Zero means the entry is
  orphaned w.r.t. the timeline (candidate for `prune_lockfile`).

Missing hash fails loudly with a `list_lockfile_entries` hint. Permission
`project.read`; registered in all five AppContainers.

**Alternatives considered.**

1. *Expose these fields in `list_lockfile_entries` unconditionally.*
   Rejected — doubles the per-row payload across up to 200 rows, of
   which the agent typically needs only one row's detail. The list / describe
   split is the standard *ls* vs *stat* ergonomic every system toolkit
   adopts (git ls-files vs git cat-file, docker ps vs docker inspect).
2. *Return derived staleness + clipRefs only when an `include_derived=true`
   flag is passed.* Rejected — a describe tool with a single-row return is
   the natural place to do the derivation work. The cost is one pass through
   `Source.nodes` + one pass through `Timeline.tracks` — O(N+M), negligible
   on any real project size. Optional flags add caller ceremony for no
   meaningful budget saving.
3. *Fold this into `find_stale_clips` by returning lockfile metadata
   alongside clip ids.* Rejected — `find_stale_clips` is the scan-tool;
   conflating it with per-row detail would bloat the scan output and
   force callers to filter for the row they cared about anyway. Keep the
   scan/describe verb pair orthogonal, same as `list_lockfile_entries` vs
   `describe_lockfile_entry`.

**Coverage.** `DescribeLockfileEntryToolTest` — six tests: live pinned row
round-trips provenance + baseInputs + clipReferences; source drift flips
`currentlyStale` + populates `driftedNodes`; fresh matching hashes stay
not-stale; orphan entry reports zero clipReferences; legacy (no
baseInputs) entry reports `baseInputsEmpty=true`; missing hash fails loud.

**Registration.** `DescribeLockfileEntryTool` registered in
`CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`, `apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`.
