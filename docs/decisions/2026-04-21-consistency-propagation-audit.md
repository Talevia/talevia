## 2026-04-21 — project_query(select=consistency_propagation) audits source-node → prompt flow (VISION §5.5)

Commit: `1d07e8a`

**Context.** VISION §5.5 rubric: "这些约束有没有真的传导到 AIGC 调用
的 prompt / 参数 / LoRA 里?". Today a character_ref can be listed as
a clip's `sourceBinding` without its visual description actually
reaching the image prompt — a silent propagation miss that surfaces
only when the user eyeballs the generated frame and notices "that's
not my character." `project_query(select=clips_for_source)` tells
the agent which clips are bound but not whether the binding actually
landed in the prompt. The backlog bullet (`consistency-propagation-
audit`, P1 #4) asked for an audit primitive.

(Why this cycle and not earlier P0/P1 tops: `aigc-cost-tracking` is
still blocked on pricing-table product decisions; `per-clip-
incremental-render` is deferred per
`2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md`;
`tts-regen-by-language` turned out multi-cycle — requires synchronised
extensions to `synthesize_speech` + `fork_project` + lockfile
traversal + language→voice mapping — skipped for a dedicated cycle.)

**Decision.** New select on `ProjectQueryTool`:
`SELECT_CONSISTENCY_PROPAGATION = "consistency_propagation"` — drill-
down by existing `sourceNodeId` input field (filter-rejection relaxed
to allow both `clips_for_source` and `consistency_propagation`).

Handler in `core/tool/builtin/project/query/ConsistencyPropagationQuery.kt`:
1. Resolve the source node via `project.source.byId`; fail loud on
   unknown id.
2. Extract keyword set: top-level string values from the node's
   `body: JsonObject`, de-duplicated + order-preserving, blanks
   dropped.
3. For every clip in `project.clipsBoundTo(nodeId)` (transitive
   closure, already used by `clips_for_source`):
   - If the clip has an asset, look up the most-recent
     `LockfileEntry` for it.
   - If found, read `baseInputs.prompt` and check each keyword as a
     case-insensitive substring.
4. Emit one `ConsistencyPropagationRow(clipId, trackId, assetId?,
   directlyBound, boundVia, aigcEntryFound, lockfileInputHash?,
   aigcToolId?, keywordsInBody, keywordsMatchedInPrompt,
   promptContainsKeywords)` per clip, whether AIGC-backed or not.
   Non-AIGC clips get `aigcEntryFound=false` + empty `matched` —
   the auditor sees the complete bound set, not just the "has
   lockfile entry" slice.

`ProjectQueryTool.ConsistencyPropagationRow` + helpText + schema
updated. No new tool; no new AppContainer wiring.

**Alternatives considered.**

- *Add a separate `audit_consistency` tool instead of a new select*:
  rejected — §3a #1 "工具数量不净增". The pattern `project_query`
  consolidated 7 list_* tools + 3 describe_* tools is specifically
  designed to absorb read-only queries; this is the same shape.
- *Regex-tokenise each body value and match individual words*:
  rejected for v1 — substring match is honest about what it
  guarantees (no, it didn't respect word boundaries; yes, "Mei" still
  matches "Meir"). Regex raises false positives subtly
  (e.g. "Mei" matching inside any "me"). Callers who want stricter
  matching can post-process `keywordsInBody` vs the clip's
  `baseInputs.prompt` themselves.
- *Walk nested objects / arrays in body*: rejected for v1 — current
  consistency node kinds (character_ref / style_bible / brand_palette)
  keep load-bearing fields at the top level. Nested walking would
  expand false-positive surface with little real-world hit rate.
  Documented as an extension point in the handler.
- *Return only clips where propagation failed (inverted filter)*:
  rejected — an agent LLM can filter client-side, and hiding the
  "propagated OK" rows would make "why did the audit return zero?"
  ambiguous ("zero failures" vs "zero bound clips").
- *Inspect the render engine's actual prompt send instead of the
  lockfile*: rejected — engines don't expose a post-send hook and
  re-running them would burn cost just for audit.

Industry consensus referenced: `terraform plan` and `kubectl
apply --dry-run` both expose "what WOULD this do" audits as
first-class verbs alongside the doers. OpenCode's
`codebase-search` uses substring-match-first for the same honest-
simple-default reason. `JsonObject` top-level traversal is the
same discipline `kotlinx.serialization` applies to its own default
polymorphic layouts.

**Coverage.**

- `ProjectQueryToolTest.consistencyPropagationReportsHitAndMissPerClip`
  — character_ref with `name=Mei` + `visualDescription=young traveler`
  bound to two clips. c-hit's prompt contains "Mei" → matched, flag
  true. c-miss's prompt is a plain background → no match, flag
  false. c-unbound (bound to a different node) is not in scope.
- `ProjectQueryToolTest.consistencyPropagationSkipsClipsWithoutLockfileEntry`
  — emptied lockfile → both bound clips still reported with
  `aigcEntryFound=false`, no false-positive matches.
- `ProjectQueryToolTest.consistencyPropagationUnknownNodeFailsLoud` —
  unknown sourceNodeId yields "Source node ghost not found" + a
  `source_query(select=nodes)` discovery hint.
- `ProjectQueryToolTest.consistencyPropagationRequiresSourceNodeId` —
  missing field fails loud.

**Registration.** No new tools; `project_query` is already registered
in all five `AppContainer`s (CLI / Desktop / Server / Android / iOS).
The new select inherits registration automatically.

§3a checklist pass:
- #1 zero new tools; extends existing `project_query`. ✓
- #2 not a Define/Update pair. ✓
- #3 no Project blob changes. ✓
- #4 `promptContainsKeywords: Boolean` is a derived summary of the
  matched list; `keywordsMatchedInPrompt` is the honest three-state
  view (empty = miss, non-empty = hit, entire row skipped for
  non-AIGC clips via `aigcEntryFound=false`). ✓
- #5 node-body extraction is generic — doesn't special-case
  character_ref / style_bible / brand_palette kinds. ✓
- #6 no session-binding surface added. ✓
- #7 new row fields have explicit defaults; existing `Input.sourceNodeId`
  continues to default null. ✓
- #8 no new tool registration needed. ✓
- #9 four tests cover hit, miss (AIGC), non-AIGC fallback, unknown
  node, missing field. Beyond happy-path; exercises the
  `aigcEntryFound=false` branch explicitly. ✓
- #10 +~150 tokens for helpText + schema addition. Under cap. ✓
