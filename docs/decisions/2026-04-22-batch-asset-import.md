## 2026-04-22 — `import_media.paths` batch form (VISION §5.4 rubric)

Commit: `769a6d5`

**Context.** VISION §5.4 "小白路径摩擦项": a vlogger rsyncs 40 clips off
a phone and then has to issue 40 `import_media` calls one at a time.
Each call is a full agent turn (LLM → tool dispatch → LLM reads result →
LLM emits next tool call), so the wall-clock latency is dominated by
round-trips, not by the actual ffprobe work that's IO-bound and trivially
parallelisable. Today's single-path shape is a UX brake that scales
badly with input size.

**Decision.** Extend `ImportMediaTool.Input` with a batch shape that
shares the same probe / persist / append code path, and surface the
batch result on `Output` without breaking the pre-batch single-path
callers. Concretely:

1. **`Input.path: String? = null`** (was required; now optional) and
   **`Input.paths: List<String>? = null`** (new). Exactly one must be
   supplied — the execute path throws a clear `"pass either \`path\` or
   \`paths\`, not both"` / `"must supply \`path\` or \`paths\`"` error
   otherwise. Batch shape rejects empty list and duplicate entries to
   catch the usual caller mistakes early.

2. **Concurrent probing.** `coroutineScope { paths.map { async { probeOne(it) } }.awaitAll() }`.
   Each probe is IO-bound (ffprobe shell-out or platform equivalent);
   serialising them would linearise the bottleneck. Per-path failures
   are captured via a private `sealed interface ProbeResult { Success /
   Failure }` — `runCatching` in `probeOne` guarantees no exception
   escapes the async boundary, so one bad clip in a batch of 40 doesn't
   abort the remaining 39.

3. **Single mutate for the project.** After probes complete, all
   successful assets are appended in a single `projects.mutate { … }`
   call — the ProjectStore mutex already serialises these, and batching
   them keeps the final "project now has N assets" count deterministic
   and cache-friendly.

4. **Output evolved with backward compat.** Added two nullable lists:
   - `imported: List<ImportedAsset> = emptyList()` — per-path
     success report (path + assetId + duration + dims + proxyCount).
   - `failed: List<FailedImport> = emptyList()` — per-path failure
     (path + error string).
   Existing flat fields (`assetId`, `durationSeconds`, `width`, `height`,
   `videoCodec`, `audioCodec`, `proxyCount`) now reflect the **first**
   successful import — equal to the only import for single-path callers,
   which is what every prior test already asserts. When every path
   fails, flat fields fall back to `"" / 0.0 / null / 0` and the failure
   list holds the reasons.

5. **`outputForLlm` summary.** Single-path calls produce the pre-batch
   wording ("Imported asset X…"). Batch calls produce
   `"Batch import: M ok / N failed across K paths; project now has …"`
   with the first 3 failure basenames inlined so the LLM can diagnose
   without reading the typed Output.

No new tool. Tool count delta: 0. The existing `import_media` tool is
extended in-place.

**Alternatives considered.**

1. **Second tool `import_media_batch(paths)`** — rejected on §3a rule 1
   (no net tool growth without a compensating removal). Both would
   share 90% of the same code; a single polymorphic tool is cheaper for
   the LLM's schema bundle too (one spec with one extra field vs. two
   near-duplicate specs).

2. **Sequential probing inside the tool** — rejected. Probing a 40-file
   rsync serially at ~200ms per ffprobe = 8s per batch; parallel
   finishes at the slowest-probe wall time (typically 1-2 files × 200ms
   = ~400ms). Concurrency is pure upside when every probe is IO-bound
   and stateless.

3. **Fail the whole batch on first bad clip** — rejected. "One corrupt
   clip in 40 loses the batch" is exactly the UX paper-cut §5.4 was
   called out for. `regenerate_stale_clips` already set the precedent
   of "batch tools capture per-item failures without aborting"; this
   matches.

4. **Return only the list form, drop flat fields for back-compat** —
   rejected. Pre-batch tests + the desktop / iOS UIs read
   `Output.assetId` / `Output.durationSeconds` directly. Breaking the
   Output shape means every consumer updates; the hybrid keeps the
   surface flat while adding the lists on the side. `kotlinx.serialization`
   handles the added defaulted fields transparently.

**Coverage.** New `ImportMediaBatchTest` in
`core/src/jvmTest/.../video/` covers 8 semantic cases via a
`SelectiveVideoEngine` that succeeds for `.good` paths and throws for
`.bad`:

- `singlePathPreservesLegacyShape` — flat fields still reflect the
  single asset, `imported.size == 1`, `failed` empty.
- `batchImportsMultipleFilesAndPopulatesListed` — 3-file happy path,
  `imported.size == 3`, project asset count matches.
- `batchCapturesPerPathFailuresWithoutAbortingTheRun` — 5-file mixed
  batch (3 good + 2 bad), good 3 succeed, bad 2 captured in `failed`,
  project has only the 3 successful.
- `allFailedBatchReturnsEmptyFlatAssetId` — edge case: flat `assetId`
  = `""` when nothing succeeded, no assets added.
- `bothPathAndPathsRejected` — mutual-exclusion error.
- `neitherPathNorPathsRejected` — mutual-exclusion error.
- `emptyPathsListRejected` — misuse guard.
- `duplicatePathsRejected` — misuse guard.

Existing `ImportMediaProxyTest` keeps passing unchanged — the
single-path flat-field path is preserved verbatim. `M6FeaturesTest`
unchanged (only exercises registration).

**Registration.** No registration change — `import_media` is the same
tool id registered in all 5 AppContainers. Pre-batch call sites
(tests, external integrations pinning to the Input schema) keep
working because the only required slot is now implicit-runtime
(`path` xor `paths`), not in JSON-schema required; and both are
optional with defaults on the Input data class.

LLM context cost (§3a rule 10): `paths` field schema ~55 tokens,
`imported` + `failed` Output fields ~65 tokens ≈ 120 tokens added per
turn where `import_media`'s spec is bundled. Well below the
500-token threshold. The context saved on a 40-file batch (39
omitted turns × ~500 tokens each = ~20k tokens) dwarfs the 120-token
schema cost many times over.

---
