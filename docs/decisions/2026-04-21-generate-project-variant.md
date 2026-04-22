## 2026-04-21 — fork_project gains variantSpec (aspect + duration) + parent lineage (VISION §5.2 rubric)

Commit: `6f45ef0`

**Context.** VISION §6's narrative / vlog examples call out "30s / 竖版
variant" as a first-class output — "here's a story, now give me a short
vertical cut for the phone and a 30s cut for the ad slot." Today the
agent has to hand-assemble that from three primitives: `fork_project`
(verbatim copy), `set_output_profile` (aspect change), and a sequence
of clip removals / trims (duration cap). Three tool calls to produce
one user-visible "variant", with no record that the variants descend
from the same trunk. Backlog bullet (`generate-project-variant`, P1)
called for a new `generate_variant` tool. §3a #1 and the actual
semantic shape pushed the implementation toward extending
`ForkProjectTool` instead.

**Decision.** Extend `ForkProjectTool.Input` with optional
`variantSpec: VariantSpec?`. `VariantSpec` carries two independent
reshape fields — `aspectRatio: String?` + `durationSecondsMax: Double?`
— both pure in-memory transforms applied post-fork on the copied
project:

1. `aspectRatio` — resolves a preset name (`16:9`, `9:16`, `1:1`,
   `4:5`, `21:9`, case-insensitive) to a target `Resolution` and
   overwrites both `timeline.resolution` and
   `outputProfile.resolution`. Fails loud on unknown preset (same
   discipline as the template tool).
2. `durationSecondsMax` — caps the timeline at N seconds: tail clips
   (start ≥ cap) drop; straddlers (start < cap < end) truncate their
   `timeRange` AND their source range by the same delta; timeline
   duration clamps to min(orig, cap). Must be `> 0`.

Plus a `Project.parentProjectId: ProjectId?` field that every fork
sets (both plain-fork and variant-fork paths) so lineage is queryable
without threading a sibling table. Pre-recency blobs decode fine via
the nullable default (§3a #7).

`Output` gains `appliedVariantSpec`, `variantResolutionWidth/Height`,
`clipsDroppedByTrim`, `clipsTruncatedByTrim` — all nullable / defaulted
so plain-fork callers see the same shape as before.

No new `Tool.kt` file. §3a #1 net-zero tool growth: the variant
capability is a parameter on the existing "branch a project" surface,
not a parallel tool the LLM has to choose between.

**Alternatives considered.**

- *New `GenerateVariantTool` per the bullet's literal wording*: +1
  `Tool.kt`. Rejected — fork and variant are "start a new project
  from A", the only axis is "identically or with reshape"; two tools
  means the LLM pays spec cost for both forever, and the bullet's
  direction text is a suggestion (per CLAUDE.md: "task descriptions
  give gap + direction, details decided in plan"). The semantic shape
  — variantSpec is a strict extension to fork — fits one tool.
- *Apply variant via a post-fork pipeline of existing tools
  (`fork_project` + `set_output_profile` + `trim_clips`)*: rejected
  — three tool calls for one user intent, and nothing records the
  lineage. The whole reason §5.2 scores this "partial" is that the
  abstraction doesn't exist as a single thing the LLM can call.
- *Include `language` in VariantSpec now*: rejected for v1 — TTS
  regeneration needs provider calls, input parameter mutation on
  text clips, and source-binding propagation; all out of scope for
  a pure in-memory reshape. A follow-up cycle can add `language` to
  VariantSpec once the speech regen path has a "re-emit TTS for all
  text clips bound to source node X" primitive. Documented as an
  extension point, not a gap.
- *Proportional time-scale instead of hard trim*: rejected — scaling
  timestamps by k changes audio pitch and loses semantics (a 6s
  narrative scaled to 3s is not "a 3s narrative"). Hard-trim is
  predictable and the caller can follow up with a condensation
  strategy if they want smarter behavior.
- *Parent lineage via a sibling `ProjectLineage` table instead of an
  inline field*: rejected — lineage is a single scalar pointer per
  project, not an append-only list. Inline nullable field is the
  same shape as `ProjectSummary.createdAtEpochMs` — no write
  amplification worries (§3a #3 compliant).

Industry consensus referenced: `git`'s `branch` + `cherry-pick` model
— branches carry a parent pointer, subcommands compose. Adobe
Premiere's "Duplicate → Override Sequence Settings" is the same UX
flow. `kotlinx.serialization` default-value convention preserves
backward compat for the new Project field (§3a #7).

**Coverage.**

- `ForkProjectToolTest.variantSpecAspectRatioReframesResolution` —
  9:16 preset overrides both timeline + outputProfile, keeps all
  clips, sets parentProjectId.
- `ForkProjectToolTest.variantSpecDurationDropsTailClipsAndTruncatesStraddlers`
  — with three 2s clips and cap=3s: c-1 whole, c-2 truncated, c-3
  dropped; both `timeRange` and `sourceRange` shorten in lock-step;
  `clipsDroppedByTrim=1`, `clipsTruncatedByTrim=1`; timeline.duration
  caps.
- `ForkProjectToolTest.variantSpecCombinedAspectAndDuration` — both
  reshape branches apply independently and compose.
- `ForkProjectToolTest.variantSpecRejectsUnknownAspect` — `"3:7"`
  fails loud.
- `ForkProjectToolTest.variantSpecRejectsNonPositiveDuration` —
  `0.0` fails loud.
- `ForkProjectToolTest.plainForkHasNoVariantMetadata` — regression
  guard that variantSpec=null path preserves existing Output shape
  (nulls / zeros) while still setting parentProjectId.
- `ForkProjectToolTest.variantOnEmptyTimelineIsNoopSafe` — empty
  timeline + both variant fields produces a valid empty fork with
  reframed resolution and no errors.

**Registration.** No new tools. `ForkProjectTool` is already
registered in all five `AppContainer`s (CLI / Desktop / Server /
Android / iOS per `grep ForkProjectTool apps/*/src apps/ios`). The
variantSpec path inherits registration.

§3a checklist pass:
- #1 zero new tools — parameter extension on existing branch
  semantics. ✓
- #2 no Define/Update pair. ✓
- #3 `Project.parentProjectId` is a scalar `ProjectId?`, not an
  append-only list — inline is correct. ✓
- #4 `parentProjectId` is a nullable reference, not a binary state
  flag; null = "root project", non-null = "forked from X". Third
  state not needed. ✓
- #5 variant vocabulary (aspectRatio, durationSecondsMax) is
  genre-neutral. Deliberately skipped `language` + `key-shot
  condensation` from the bullet's direction text because both
  require genre-aware logic that would violate §3a #5. ✓
- #6 no session-binding surface added. ✓
- #7 new `Project.parentProjectId` and all new Output fields default
  to null / zero → pre-lineage blobs decode unchanged. ✓
- #8 existing tool, no AppContainer changes needed. ✓
- #9 seven new tests cover both happy paths (aspect only, duration
  only, combined) AND edges (unknown aspect, zero duration, empty
  timeline, regression guard for plain fork). ✓
- #10 helpText + inputSchema diff adds ~180 tokens. Under the 500
  threshold; fork is called often enough that the spec cost is
  amortised over many turns vs. the alternative (separate tool
  paying its own base cost forever). ✓
