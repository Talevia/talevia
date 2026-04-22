## 2026-04-22 — Stamp `LockfileEntry.originatingMessageId` from `ToolContext.messageId` (VISION §5.2 audit trail)

**Context.** Every AIGC tool dispatch appends a `LockfileEntry`
through `AigcPipeline.record(...)` — shared helper in
`core/src/commonMain/kotlin/io/talevia/core/tool/builtin/aigc/AigcPipeline.kt`.
The row carries `toolId`, canonical input hash, `baseInputs`,
`sourceBinding`, `sourceContentHashes`, `sessionId`,
`resolvedPrompt` … but not *which message in the session*
issued the tool call. Answering "which prompt generated this
image?" requires grepping session parts for a matching tool-call
payload — fragile, slow, and breaks after compaction when
intermediate tool-call parts get dropped. VISION §5.2 calls for
an audit lane that the lockfile alone can satisfy.

Backlog bullet `tool-call-provenance-in-lockfile` (P2) named
the fix explicitly: add
`LockfileEntry.originatingMessageId: MessageId? = null`
(`null` default for forward-compat), fill it from
`ctx.messageId` at every AIGC write site.

**Decision.** Three surgical edits — no tool registration
change.

1. **`LockfileEntry.originatingMessageId: MessageId? = null`**
   — new nullable field on the canonical entry (`Lockfile.kt`).
   Default `null` preserves every pre-existing serialized blob
   (forward-compat per §3a rule 7). Field carries the
   `io.talevia.core.MessageId` value class directly — no
   string-typing leak at the storage layer.

2. **`AigcPipeline.record(... , originatingMessageId: MessageId? = null)`**
   — single new optional parameter on the shared pipeline
   helper. Every existing call site compiles unchanged; five
   AIGC tools (`generate_image`, `generate_video`,
   `synthesize_speech`, `generate_music`, `upscale_asset`) pass
   `originatingMessageId = ctx.messageId` after the existing
   `sessionId = ctx.sessionId` line. Uniform across the five —
   no provider-specific or tool-specific logic.

3. **`ProjectQueryTool.LockfileEntryDetailRow.originatingMessageId: String? = null`**
   — propagate the field through the `select=lockfile_entry`
   drill-down row so audit callers (CLI, desktop, LLM tool
   calls) see it alongside `resolvedPrompt` and `provenance`.
   Stored as `String?` on the output DTO (the query output shape
   is already string-typed; callers can wrap in `MessageId` if
   they need the type).

**Why a per-call parameter rather than threading `ToolContext`
through `AigcPipeline.record`.** Considered passing `ctx` and
letting `record` pull `ctx.messageId` internally. Rejected —
`AigcPipeline` is otherwise a stateless pure-data helper with
narrow typed inputs (`toolId`, `projectId`, `inputHash`, etc.).
Adding a `ToolContext` parameter would grow the helper's
coupling surface for a single string value and conflict with
the existing `sessionId = io.talevia.core.SessionId?` pattern —
both provenance fields already pass through as scalar
parameters. Matching that shape keeps the helper's contract
clean: "callers explicitly pass what they want recorded".

**Why `MessageId?` not `String?` on the entity.** Considered
`originatingMessageId: String? = null` to match
`sessionId: String?` on `LockfileEntry`. Rejected — `sessionId`
is a string today because of a historical legacy-entry
decoding decision (pre-cycle-7 entries had `String?` at the
storage layer and the migration never bumped it). For a
brand-new field there's no legacy-compat constraint, so we use
the value-class type directly. Kotlinx-serialization
`@JvmInline value class MessageId(val value: String)` serializes
transparently as a JSON string, so the wire format is identical
to `String?` and nothing about storage changes. This gives the
typed Kotlin side a stronger guarantee (no accidental `assetId
.value` -> `messageId` mix-ups at call sites).

**Why not backfill legacy entries.** Considered a one-time
migration pass that walks every project's lockfile and maps
pre-existing entries to `null` -> a "reconstructed"
`MessageId` via session/tool-call cross-reference. Rejected —
legacy entries were written before the provenance lane existed;
there's no authoritative source to reconstruct from (session
parts may have been compacted away, tool-call payloads may not
match current `inputHash` canonicalization). `null` is the
correct "unknown" value; audit UI should render "unknown
(pre-provenance cycle)" rather than guess. This also matches
how `resolvedPrompt`, `costCents`, and `sourceContentHashes`
were rolled out.

**Write-site uniformity.** All five AIGC tools now pass
`originatingMessageId = ctx.messageId` — same line shape in the
same place (right after the existing `sessionId = ctx.sessionId`
line, before the closing paren of `AigcPipeline.record(...)`).
No tool-type-specific branching. §3a rule 8 — five-platform
wiring is not applicable here because the write sites are
shared core code that every platform executes identically; the
change is backend-transparent to every `AppContainer`.

**Tests.** Three new cases pinning the contract:

- `LockfileTest.entryMissingOriginatingMessageIdDecodesAsNull`
  — legacy JSON blob (pre-field) decodes with
  `originatingMessageId == null`. Locks in the forward-compat
  guarantee.
- `LockfileTest.entryWithOriginatingMessageIdRoundTrips` —
  encode-then-decode preserves the `MessageId` value.
- `GenerateImageToolTest.lockfileEntryStampsOriginatingMessageIdFromContext`
  — end-to-end: call `generate_image` with a ctx carrying
  `MessageId("msg-42")`, read the store, assert the single
  appended entry has `originatingMessageId == MessageId("msg-42")`.
  Single tool is sufficient coverage — all five tools route
  through the same helper line, so the remaining four tools
  inherit the behavior trivially (tested implicitly by the
  compile-time requirement that `originatingMessageId` reaches
  `AigcPipeline.record` from each call site).
- `ProjectQueryToolTest.lockfileEntryDrillDownSurfacesOriginatingMessageId`
  — end-to-end read path: seed a store with a stamped entry,
  call `project_query(select=lockfile_entry, inputHash=...)`,
  assert the output row surfaces the message id as a string.

All existing tests pass unchanged — the new field's `null`
default and the five tools' otherwise-unchanged call shapes
preserve backward compat.

**Coverage runs.**
- `:core:jvmTest` — all green.
- `:core:compileKotlinIosSimulatorArm64` — passes (no
  kotlinx-serialization complaints about the new field).
- `:apps:desktop:assemble`, `:apps:server:test`,
  `:apps:android:assembleDebug` — passes.
- `ktlintCheck` — clean.

**Registration.** No new tool, no `AppContainer` change. The
field propagates through existing code paths.

**Alternatives considered.**

1. **Per-tool struct-level `originatingMessageId` on each
   generator's result type.** Rejected — the lockfile entry is
   the canonical storage, and every AIGC tool already writes to
   it via the shared pipeline. Duplicating the field into each
   tool's output type would violate DRY and force every reader
   (stale-clip detector, cost aggregator, audit UI) to stitch
   across two sources.

2. **A separate `LockfileEntryAudit` sibling table keyed by
   `inputHash`.** Rejected as over-engineering for one
   nullable string. Sibling tables earn their complexity when
   (a) the payload is large enough that unrelated mutations
   write-amplify it (as with `ProjectClipRenderCache` per
   2026-04-22 debt extract), or (b) the lookup shape wants an
   index the main table can't serve. A single `MessageId?` is
   neither — it's one token of JSON next to the other
   provenance fields.

3. **Record the `CallId` instead of `MessageId`.** Rejected —
   `CallId` identifies a single tool call instance; multiple
   tool calls can share a `MessageId` (one assistant message
   dispatches two generations in parallel, e.g. "generate an A
   and a B of the same scene"). The backlog bullet asks "in
   which *prompt* was this generated?" — the prompt belongs to
   a message, not a call. If the audit story later needs
   per-call traceability, `CallId` can layer on as a second
   field.

**Non-goals / follow-ups.**

- **CLI / UI rendering.** The new field surfaces in
  `project_query(select=lockfile_entry)` output; CLI / desktop
  rendering just surfaces whatever the query returns. A
  dedicated "audit drill-down" UI pane is a follow-up if the
  driver surfaces.

- **Cross-session provenance.** When a project is forked, the
  `originatingMessageId` from the parent session is preserved
  (the lockfile entries are copied verbatim). Readers viewing
  the fork's lockfile see message ids that point at the
  parent's session — by design; the audit trail should point
  at where the artifact was actually generated, not where it
  was later observed.

- **Backfill via session-part scan.** If a user wants to
  retrofit pre-provenance entries, a follow-up tool could scan
  session parts for matching tool-call payloads and stamp the
  missing `originatingMessageId` on a best-effort basis. Not
  shipped this cycle — null handles "unknown" cleanly, and
  backfill accuracy would depend on session part retention
  (which compaction deliberately doesn't guarantee).

---
