## 2026-04-19 — Content hash for Source DAG: FNV-1a 64-bit hex (upgradable to SHA-256)

**Context.** VISION §3.2 calls for cache keys indexed by `(source_hash, toolchain_version)`.
That needs a deterministic, cross-platform content fingerprint for every `SourceNode`.
The stubbed `contentHash = revision.toString()` prevents any downstream caching from
working correctly — two unrelated edits can produce the same revision string.

**Decision.** Compute `SourceNode.contentHash` as a 16-char lowercase hex of FNV-1a 64-bit
over the canonical JSON encoding of `(kind, body, parents)`, delimited by `|`. See
`core/util/ContentHash.kt`.

**Alternatives considered.**
- **SHA-256 via expect/actual.** Industry standard for content-addressed storage (Nix,
  Git-LFS, Bazel). Rejected for v1: requires a crypto dependency or platform-specific
  actuals, and FNV-1a is sufficient inside a single project (≤10³ nodes, collision
  probability negligible).
- **`String.hashCode()` (Java 32-bit MurmurHash-ish).** Rejected: not guaranteed stable
  across Kotlin / Java versions; 32-bit is too narrow for future cross-project caching.
- **Keep the revision stub.** Rejected: blocks Task 3–4 (lockfile + incremental render).

**When to upgrade.** When we build a content-addressed **remote** artifact cache — shared
across projects or users — swap `fnv1a64Hex` for SHA-256. The upgrade path is a
single-function change because every caller goes through `contentHashOf`, and every
`SourceNode` recomputes its hash on write via `SourceMutations.bumpedForWrite`. Existing
projects will see their hashes re-derive on first write, which is fine: cache entries are
keyed by the hash, so a new hash just produces a cold cache.

---
