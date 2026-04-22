## 2026-04-22 — Semantic search over source nodes: deferred, no concrete driver

Commit: `(docs-only — no code change; defer rationale recorded)`

**Context.** Backlog bullet `semantic-search-source-nodes` proposed adding
embedding-based semantic search on source nodes — integrate a lightweight
on-device embedding model (CoreML on iOS / ONNX Runtime on JVM desktop,
server, cli / TFLite on Android) and expose a new select branch
`source_query(select=semantic_search(query, topK))` to find concept-level
matches ("find me a character_ref similar in spirit to X") that keyword
search misses.

Decision: **defer until a concrete driver appears.** This cycle is the
evaluation, not the implementation. The rationale sits on four pillars:
(1) existing lexical search already covers the overwhelming majority of
"find node" queries with cross-project scope, (2) the delivery cost is
cross-cutting across 5 platforms with model-weight bundling and runtime
integration, (3) VISION is silent on the feature, and (4) CLAUDE.md's
anti-requirement against "designing for hypothetical future needs without
a concrete driver" directly applies.

**What we already ship.**

Two shipped lookups cover the current search surface, both via
`source_query(select=nodes, ...)`:

1. `contentSubstring: String?` — case-insensitive substring match against
   the JSON-serialized body. Snippet + matchOffset returned for each hit.
   Shipped via `2026-04-21-search-source-nodes-body-content-lookup.md`
   (commit `2e3ead8`).
2. `scope: String? = "project" | "all_projects"` — cross-project union
   with deterministic per-row `projectId`. Shipped via
   `2026-04-22-cross-project-source-similarity.md` (commit `b724d04`).

Combined, these answer the exact "find a character_ref similar to X" use
case for 80-90% of queries where the similarity is keyword-expressible:
"cyberpunk" catches every cyberpunk-flavoured body, "neon" catches
neon-adjacent nodes, brand names (SKU-12345) catch inventory refs
exactly. The residual 10-20% — true paraphrase / synonym matching where
the target body shares zero keywords with the query ("cyberpunk hacker"
vs "underground netrunner") — is the semantic-search delta. No VISION
pin and no observed user flow today depends on that delta.

**Delivery cost (why "just add it" is not cheap).**

1. **Cross-platform runtime bundling.** Five platforms, three native
   runtimes. JVM (cli / desktop / server) needs ONNX Runtime Java
   (~80MB native libs; CPU-only build). iOS needs CoreML with a
   MiniLM-variant converted to `.mlmodel` (~20-30MB compiled). Android
   needs TFLite or ONNX Runtime Android (~30MB AAR + model). Model
   weights (all-MiniLM-L6-v2 @ ~80MB FP32, ~25MB INT8) ship inside each
   app's artifact. Current app-size budget has no carve-out for this.
2. **Cross-platform embedding consistency.** Same input string through
   ONNX vs CoreML vs TFLite produces *almost* identical vectors, but
   quantization and tokenizer edge cases diverge. A user's top-K hit
   list on iOS would occasionally not match the same project's top-K on
   desktop — a "why is the agent recommending different nodes depending
   on which platform I opened the project on?" failure mode that is
   expensive to diagnose and hard to make go away without pinning a
   single runtime (which breaks the "engines are interchangeable"
   platform-impl architecture rule).
3. **Embedding cache schema + invalidation.** Vectors are expensive to
   recompute (MiniLM inference on 200 source-node bodies is 10s-30s
   cold). They must be persisted next to the source DAG with a key like
   `(projectId, nodeId, contentHash)` and invalidated whenever
   `contentHash` changes (which already happens on every body edit via
   `SourceNode.create`). That's a new SQLDelight table + a new
   invalidation hook wired into `ProjectStore.mutateSource` — not hard,
   but non-trivial to get right under the cycle-tolerant stale
   propagation invariants `ProjectStaleness.kt` already maintains.
4. **Platform-impl contract proliferation.** `core.platform` already has
   `VideoEngine`, `MediaStorage`, `MediaPathResolver`, `VideoGenEngine`,
   `ImageGenEngine`, `MusicGenEngine`, `UpscaleEngine`, `TtsEngine`,
   `AsrEngine`. An `EmbeddingEngine` would be a tenth. Each new contract
   pulls 5 `AppContainer` wiring tasks + 5 platform-impl modules;
   current engine surface is paying for itself because every one of
   those engines has a first-class tool the agent calls today. A new
   engine for a speculative query path does not.

**Alternatives considered.**

1. **Lexical with fuzzy match first.** Before reaching for embeddings,
   extend `contentSubstring` with optional fuzzy / levenshtein tolerance
   (handle typos) or a `termsAny` / `termsAll` multi-keyword branch
   (handle paraphrase-by-synonym). Cheap, stays on the existing
   `source_query(select=nodes)` surface, and closes most of the gap the
   embedding case is pointing at — at a fraction of the delivery cost.
   Worth a separate backlog bullet if the gap is felt.
2. **Server-side embeddings only.** Compute embeddings on a hosted
   embedding API (OpenAI `text-embedding-3-small`, Anthropic's planned
   embedding model, or Voyage) — avoids bundling native runtimes +
   model weights. But it (a) breaks the "agent runs on-device" VISION
   commitment for the offline lane, (b) pays per-token $ for every
   search + every source-node mutation, and (c) requires the same
   invalidation cache work as the on-device path. Not clearly cheaper
   once invalidation is factored in.
3. **Ship the whole thing now anyway.** Rejected under CLAUDE.md
   anti-requirement "designing for hypothetical future needs" — no
   concrete user driver today, no VISION pin, and lexical + cross-
   project already covers the common case. Reopen if a concrete flow
   appears where lexical provably missed the target node.

**Defer criteria — what would un-defer this.**

- A concrete user report / agent transcript where `source_query(
  contentSubstring=…, scope=all_projects)` failed to surface a node
  that was a clear conceptual match. Screenshot, transcript, or logged
  agent confusion — something specific.
- VISION §5.x revision that explicitly pins semantic retrieval as a
  gap to close.
- Token-cost regression in agent loops that is traceable to the agent
  having to fetch full bodies because lexical didn't rank well (i.e.,
  the agent is wasting context reading a dozen nodes to find the one
  it wanted).

Until any of those, the bullet is removed from BACKLOG.md and this
decision is the record of the evaluation.

**Reference precedents (cite-worthy in future sweeps).**
- `2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md`
  — same shape (legitimate debt, multi-session scope, deferred on
  "no concrete driver + stale-guard keeps correctness today").
- `2026-04-22-cross-project-source-similarity.md` — what the existing
  cross-project lexical lane can already do.
- `2026-04-21-search-source-nodes-body-content-lookup.md` — the
  original rationale for `contentSubstring` as the shipped lookup.
- CLAUDE.md anti-requirement "Designing for hypothetical future needs
  without a concrete driver".

**Impact.**
- No code change. No tests modified.
- Backlog bullet `semantic-search-source-nodes` removed.
- Decision doc preserves the "evaluated → deferred" outcome so future
  sweeps don't re-litigate; defer criteria listed so a concrete driver
  can un-defer cleanly.
