## 2026-04-23 — Cross-machine bundle-portability smoke test (VISION §3.1 产物可 pin / §5.3 bundle-reproducibility)

**Context.** `baad43f` introduced the file-bundle `ProjectStore` with the
explicit goal that a `git push` sends everything a collaborator needs to
reproduce an export — AIGC products stay inside the bundle, the project id
lives in `talevia.json` not in any per-machine registry, and bundle-local
`MediaSource.BundleFile` paths resolve via a per-load `BundleMediaPathResolver`
rebased on the new machine's bundle root. Until this cycle that end-to-end
invariant had no guard; `FileBundleBlobWriterTest` and `BundleMediaPathResolverTest`
each cover their own slice, but nothing exercised "alice creates → cp -r →
bob opens → same bytes reproduce". Rubric delta §5.3: cross-machine
reproducibility **no → 部分** (now guarded by a smoke test, not yet by
a true CI matrix run on two physical machines — that's follow-up).

**Decision.** Added `core/src/jvmTest/kotlin/io/talevia/core/e2e/
BundleCrossMachineExportSmokeTest.kt`. The test stands up two separate
`FileProjectStore` instances sharing a single `FakeFileSystem` but each
holding its own `RecentsRegistry` + `defaultProjectsHome` — a precise
model of two machines that happen to share a filesystem for the sake of
the test. Alice creates a project with an AIGC-produced asset (written
via `FileBundleBlobWriter`, stored as `MediaSource.BundleFile`), adds a
clip referencing it, and computes a deterministic "render fingerprint":
canonical JSON of (timeline, sorted assets) + `contentHashCode()` of
every bundle-local asset's bytes resolved via `BundleMediaPathResolver`.
The test then `cp -r`s the bundle directory tree to bob's projects dir
on the shared fake fs, bob opens it with *his* store, and the test
asserts `aliceId == bobId` + fingerprints byte-equal + resolver
rebases to bob's path (not alice's).

A fake-fs deep-copy helper lives inside the test because Okio's
`FakeFileSystem` has no recursive `cp -r` primitive. Both stores use a
frozen `Clock` so `updatedAtEpochMs` stamps land identically, letting
the fingerprint comparison include the recency surface (the invariant
we want: bob's `openAt` must not restamp; it doesn't).

**Bullet says "apps/cli or apps/server"; this lives in `core/e2e/`.**
The bullet's literal flow is `talevia new /tmp/a → edit → export → cp -r
→ talevia open /tmp/b → re-export → hash equal`, expressed in CLI terms.
Implementing that literally requires either (a) driving the CLI's REPL
from a test (needs a fake LLM stream — heavyweight) or (b) standing up a
`CliContainer` inside the test and calling `project_query` / `export` tools
directly (identical to a core test except with 200 lines of DB + bus +
session-store wiring for no added coverage). The invariant — "bundle on
disk is self-contained and machine-local state lives only in `recents.json`
/ home path" — is a Core-level contract of `FileProjectStore` +
`BundleMediaPathResolver`, and `core/e2e/` is where the analogous
`RefactorLoopE2ETest` already lives. Placing the test there exercises
the exact surface the bullet's invariant protects, without pulling
CLI-only dependencies.

A follow-up that truly drives the CLI (for platform-priority reasons)
can layer on top later if we add an LLM fake that surfaces a
`/project` slash command.

**Alternatives considered.**
- **Invoke a real `VideoEngine`** (FFmpeg) and hash actual output mp4 bytes.
  Would be the most faithful "same export bytes" guarantee. Rejected:
  depends on ffmpeg being on PATH (same CI caveat already noted in
  `platform-impls/video-ffmpeg-jvm/test` — subtitle tests skip without
  libfreetype), slow (~seconds per render) for a smoke test, and the
  invariant we care about is strictly upstream of the engine (if
  (Timeline, asset bytes) is identical on both machines, a bitexact
  engine produces identical bytes — this is what the engine's
  `supportsPerClipCache` contract already assumes). Catching a
  regression where FFmpeg itself becomes non-deterministic is a
  separate concern for a `FfmpegBitexactTest`, not this smoke test.
- **Use `ExportTool` + a fake `VideoEngine` that hashes the resolver
  calls.** Would test the same invariant with more wiring. Rejected:
  doesn't add coverage beyond the direct-hash approach, and couples the
  test to `ExportTool`'s internals (which already has its own tests).
  The fingerprint function in this test is intentionally small and
  self-documenting — it explicitly enumerates what "same inputs"
  means.
- **Real temp directory instead of `FakeFileSystem`.** Would catch
  ordering quirks between `java.nio.file` and `okio`. Rejected for
  this cycle: the bullet's invariant is filesystem-agnostic, and the
  existing `FileBundleBlobWriterTest` already exercises
  `FileSystem.SYSTEM`. A real-fs variant can be added if a regression
  appears that the fake doesn't catch.

**Coverage.** `:core:jvmTest` green, including the new test
specifically. `./gradlew ktlintCheck` green.

**Registration.** No registration change — test-only addition.
