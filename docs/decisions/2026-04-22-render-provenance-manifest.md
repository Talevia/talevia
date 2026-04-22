## 2026-04-22 — ProvenanceManifest baked into exported mp4 container metadata (VISION §5.3)

Commit: `be0cb15`

**Context.** VISION §5.3 "可复现的确定性产物" — exported artifacts
must be traceable back to the source Project + Timeline that
produced them. Before this change, a `project.mp4` sitting in a
user's Downloads folder 3 months from now carried no record of which
Project id it came from, which Timeline hash it corresponds to, or
which lockfile fingerprint was active when it was rendered. The
only way to triangulate was grep-logging `ExportTool` invocations —
painful and often impossible if the session has been compacted.

Backlog bullet (`render-provenance-manifest`, P0) called for
`ExportTool` to bake `projectId / timelineHash / lockfileHash` into
the mp4 via `ffmpeg -metadata comment=…`, and for `probe` to decode
it back. This cycle implements exactly that.

**Decision.** Pure-function manifest, deterministic across
re-renders, surfaced both in the `Output` and inside the file
itself. Five changes:

1. **`core.domain.render.ProvenanceManifest`** — new `@Serializable`
   data class: `(projectId: String, timelineHash: String,
   lockfileHash: String, schemaVersion: Int = 1)`. No timestamps, no
   random ids, no session identifiers — a pure function of its
   inputs, so two identical exports mint byte-identical manifests
   (ExportDeterminismTest precondition). Encoded via a
   `MANIFEST_PREFIX = "talevia/v1:"` header + JSON body inside a
   single `comment` metadata entry. Decoder (`decodeFromComment`)
   is null-safe, rejects non-prefixed strings, and `runCatching`-
   wraps the JSON decode so corrupted / truncated metadata on a
   user-edited file doesn't throw past a probe call.

2. **`OutputSpec.metadata: Map<String, String> = emptyMap()`** —
   new field on the engine contract. Engines that can write
   container metadata (FFmpeg) wire each entry as `-metadata
   key=value`; engines that can't (Media3, AVFoundation today)
   silently ignore. Default empty keeps existing callers working.

3. **`MediaMetadata.comment: String? = null`** — probe result
   surfaces the container's `comment` tag verbatim. Consumers run
   `ProvenanceManifest.decodeFromComment(probed.comment)` to get a
   typed manifest back, or null when the file wasn't produced by
   Talevia.

4. **`ExportTool.provenanceOf(project)`** — computes the two
   hashes. `timelineHash` = `fnv1a64Hex` over the canonical Timeline
   JSON alone (a Timeline edit flips it). `lockfileHash` =
   `fnv1a64Hex` over the canonical `Lockfile` JSON (pin / append
   flips it). Both go through `JsonConfig.default` for
   canonicalisation. ExportTool assembles the manifest, stamps
   `OutputSpec.metadata["comment"]` with the encoded string, and
   echoes the typed `provenance` back on `Output` so downstream tools
   don't have to re-probe the rendered file.

5. **`FfmpegVideoEngine.render`** — walks `output.metadata` and
   appends `-metadata key=value` pairs just before the target
   output path (ffmpeg applies `-metadata` to the next output file
   in its arg list). **Critically**: also adds `-map_metadata -1`
   before the `-metadata` entries. Without it, ffmpeg's default
   behaviour is to copy global metadata from input 0 into the
   output, which can leak non-deterministic tags (creation_time
   from probed sources, provider-specific encoder fingerprints) that
   `+bitexact` alone doesn't strip when user metadata is being
   written. `-1` tells ffmpeg "carry nothing from the input", so the
   output's only container metadata is what we explicitly set.
   ExportDeterminismTest (bit-exact re-render across two outputs)
   and the new `FfmpegProvenanceManifestTest.rerenderWithSameManifestProducesBitExactOutput`
   both exercise this end-to-end.

`FfmpegVideoEngine.probe` now reads `format.tags.comment` from
ffprobe's JSON and populates `MediaMetadata.comment`.

**Alternatives considered.**

1. **Bake timestamped manifest (`exportedAtEpochMs`, machine id,
   session id).** Rejected — would immediately break
   `ExportDeterminismTest` (re-render of same project must be
   byte-identical for `RenderCache` correctness). Timestamps are
   available from the file's mtime anyway, so the manifest adds no
   value by carrying one and costs the bit-exact property by doing
   so. The two hashes plus projectId are enough to fully identify
   the source state; "when was this exported" is a filesystem
   concern.

2. **Separate `-metadata` entries per field (`projectId=X`,
   `talevia_timeline_hash=Y`, …).** Rejected — mp4 containers have
   a small whitelist of standard metadata keys that survive
   round-trips across tools (comment, title, artist, date…);
   custom keys get silently dropped by some downstream viewers /
   conversion tools (QuickTime's "ilst" muxing in particular). One
   `comment` entry with a JSON body + prefix is the lowest-risk
   shape and matches how other tools (FFmpeg itself, encoders like
   x264 / HandBrake) bake provenance into the comment tag. The
   prefix lets consumers distinguish a Talevia comment from user-
   authored text.

3. **Per-AppContainer wiring — each container supplies its own
   `manifest: (Project) -> ProvenanceManifest` factory.** Rejected
   as over-engineering. The manifest is a function of Project
   state; no container has a reason to customise it. Putting the
   computation in `ExportTool.provenanceOf(project)` keeps the
   behaviour in one place and keeps the tool constructor signature
   simple (unchanged). A future cycle that wants to add container-
   specific fields (e.g. a "produced by server v1.2.3" stamp) can
   add a factory parameter then — we're not designing for
   hypothetical needs.

4. **Bake at the engine layer instead of ExportTool.** Rejected —
   the engine doesn't know about `Project` / `Lockfile`, only
   Timeline + OutputSpec. Putting the logic there would require
   threading a `Project`-like context into every engine or
   duplicating the serialisation. Keeping it in ExportTool means
   the engine contract stays Project-agnostic and the provenance
   feature is one read-compute-pass at export time.

**Coverage.**

- **`core/src/jvmTest/kotlin/io/talevia/core/domain/render/ProvenanceManifestTest.kt`** —
  7 cases: round-trip, default schemaVersion, null comment, blank
  comment, non-Talevia / wrong-prefix comment, malformed JSON body,
  single-line guarantee (ffmpeg requires no raw newlines), and a
  default-preservation test for future schemaVersion changes.

- **`core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/video/ExportToolTest.kt`** —
  4 new cases added alongside the existing ExportTool suite:
  `exportStampsProvenanceManifestIntoOutputAndOutputSpec` (fresh
  render echoes the manifest in `Output` + stamps the engine's
  `OutputSpec.metadata["comment"]`);
  `timelineMutationChangesTimelineHashButNotLockfileHash` (hash
  axis decomposition);
  `lockfileMutationChangesLockfileHashButNotTimelineHash` (same,
  orthogonal);
  `cacheHitExportStillReportsProvenance` (RenderCache short-circuit
  still echoes the manifest so downstream tools get the same shape
  on fresh + cached paths);
  `reexportProducesIdenticalProvenance` (determinism within a single
  fixture — mirrors ExportDeterminismTest's "same project twice"
  contract for the manifest alone).

- **`platform-impls/video-ffmpeg-jvm/src/test/kotlin/io/talevia/platform/ffmpeg/FfmpegProvenanceManifestTest.kt`** —
  3 E2E cases driving real ffmpeg + ffprobe:
  `manifestRoundTripsThroughRenderAndProbe` (render → probe →
  decode → equality);
  `rerenderWithSameManifestProducesBitExactOutput` (two renders
  with same manifest produce byte-identical mp4s — the
  `-map_metadata -1` determinism guarantee);
  `probeOfNonTaleviaFileReturnsNullCommentOrNonTaleviaString` (the
  "don't claim unrelated comments" decoder contract).

- **Pre-existing `ExportDeterminismTest.sameProjectTwiceProducesBitIdenticalMp4`**
  — still green after the refactor. The test's shared-fixture
  pattern (one store, two renders) is the correct scope for the
  bit-exact claim; the manifest's `projectId` field is stable
  across the two calls.

- Full test run: `:core:jvmTest`, `:platform-impls:video-ffmpeg-jvm:test`,
  `:apps:server:test`, `:apps:desktop:assemble`,
  `:apps:android:assembleDebug`,
  `:core:compileKotlinIosSimulatorArm64`, `ktlintCheck` — all
  green.

**Registration.** No AppContainer change needed. `ExportTool` is
already wired in all 5 AppContainers; the change is internal to the
tool + a contract extension on the engine interface. FFmpeg engine
handles the new metadata map; iOS/Android engines see the field but
ignore it (silent no-op until those engines pick up a container-
metadata write path — future backlog).

§3a rundown:
- Rule 1 (tool growth): 0 new tools. ✓
- Rule 3 (`Project` blob): 0 new fields on Project. ✓
- Rule 4 (binary state): `provenance: ProvenanceManifest?` is
  naturally tri-valued (null on non-FFmpeg engines, present on
  FFmpeg). ✓
- Rule 7 (serialization): all new fields have defaults (`metadata`
  = empty map, `comment` = null, `provenance` = null,
  `schemaVersion` = 1). ✓
- Rule 8 (5-platform wiring): no new tool → no AppContainer
  change. `OutputSpec.metadata` is silently ignored by engines
  that can't write it. ✓
- Rule 10 (LLM context): one new optional field on Output
  (`provenance`) — ~80 tokens in the tool's output schema. Well
  under the 500-token threshold. ✓

**Follow-ups not in scope.**

- Media3 + AVFoundation engines to actually write the metadata
  map to their container. Kept out of this cycle because the
  container-metadata API on those engines isn't uniform and there's
  no concrete consumer today; backlog it when a driver surfaces.
- A "read provenance from imported mp4" tool (`describe_provenance`
  or `source_query(select=provenance)`) — trivial on top of this
  cycle's `probe().comment` + `decodeFromComment` but needs a
  concrete agent use case to justify the spec token cost.

---
