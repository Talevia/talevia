## 2026-04-21 — describe_clip per-clip inspection (VISION §5.4 专家路径)

Commit: `b7c8c6c`

**Context.** After the earlier cycles added pin / stale / lockfile audits,
the expert-path inspection picture had a gap: the *clip*. The existing
toolset gave the agent a paginated list of clips (`list_timeline_clips`)
and lock-file details per entry (`describe_lockfile_entry`, same loop,
cycle 2), but no "tell me everything about this single clip" call. Today's
workaround is a four-tool loop:

1. `list_timeline_clips` → find the clip + track id,
2. `list_lockfile_entries` → find the lockfile row for its assetId,
3. `find_stale_clips` / `find_pinned_clips` → check lane status,
4. manual cross-reference.

VISION §5.4 "用户能不能直接编辑 source 的每个字段、override 某一步编译、在
agent 的某个决策点接管?" presumes the expert can *see* the clip's full
state before touching it. This tool closes the read half of that loop.

**Decision.** `DescribeClipTool(projectId, clipId)` — walks the timeline,
extracts every field on the clip, adds a derived `LockfileRef` (inputHash,
toolId, pinned, `currentlyStale` computed against `Source.nodes`
contentHashes, `driftedSourceNodeIds` identifying exactly which bound
nodes drifted). Nullable shape for per-kind fields on a flat `Output`
record discriminated by `clipType` — JSON-schema friendly across
Anthropic / OpenAI / Gemini function-calling implementations, same shape
the rest of our tools use (no sealed subtypes in the tool surface).

Read-only (`project.read`). Missing clip fails loudly with a
`list_timeline_clips` hint. Registered in all five AppContainers.

**Alternatives considered.**

1. *Sealed-subtype `Output` with one variant per clip kind.* Rejected —
   kotlinx-serialization polymorphism would force a class-discriminator
   field anyway, and the JSON shape would carry the same `type` field our
   flat-record approach spells as `clipType`. A flat record avoids
   provider-specific oneOf schema handling and matches the house style
   of `list_source_nodes.NodeSummary`, which uses the same "optional
   fields conditional on kind" shape.
2. *Return `Clip` directly via `@Serializable`.* Rejected — Clip's
   sealed-class serialization uses `@SerialName` discriminators ("video",
   "audio", "text") at the kotlinx-serialization layer, which leaks
   serialization details into the tool's JSON surface; callers would
   couple to that instead of the documented tool Output. A dedicated DTO
   lets us evolve `Clip` internally without breaking tool callers.
3. *Include lockfile `baseInputs` / `sourceContentHashes` in the
   describe_clip output.* Rejected — that's `describe_lockfile_entry`'s
   job. Keep the two verbs orthogonal. The clip-describe tool returns an
   `inputHash` the agent can then pass to the lockfile-describe tool
   when deep provenance matters.
4. *Also include transitively-bound source nodes (not just the direct
   `sourceBinding`) to preempt a follow-up `list_clips_for_source` call.*
   Rejected — that's `describe_source_dag` / `list_clips_for_source`
   territory. A describe tool should surface what's on the object, not
   recompute graph-level transitives; the transitive lane is already a
   separate tool.

**Coverage.** `DescribeClipToolTest` — five tests: video-clip round-trip
with filters + transforms + lockfile ref pinned, audio-clip with volume
+ fade and no lockfile (imported media), text-clip with style and null
assetId/sourceRange, missing-clip fails loud, missing-project fails loud.

**Registration.** `DescribeClipTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`.
