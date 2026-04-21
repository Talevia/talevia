## 2026-04-21 — pin_clip_asset / unpin_clip_asset clip-level shortcuts (VISION §5.4 专家路径)

Commit: `e923125`

**Context.** Cycle 1 of the prior loop landed `pin_lockfile_entry` keyed on
`inputHash` — the right shape for the *model* of pinning, since the
lockfile ledger is keyed on inputHash and `gc_lockfile` /
`regenerate_stale_clips` dispatch off it. But the user's mental model is a
clip on the timeline ("pin this hero shot"), not a 16-char hash. To pin one
clip today the expert path demands a three-step resolution chain:
  1. `describe_project` / `list_timeline_clips` to find the clip's assetId,
  2. `list_lockfile_entries` to find the lockfile row whose `assetId` matches,
  3. copy `inputHash` and call `pin_lockfile_entry`.
VISION §5.4 专家路径 explicitly calls out "精准执行，不越权猜意图" — a
tool that takes the exact handle the user is pointing at (ClipId) is what
*precise* means here. Three rounds of indirection is the opposite.

**Decision.** A pair of tools `pin_clip_asset(projectId, clipId)` and
`unpin_clip_asset(projectId, clipId)` that do the resolution chain
atomically: walk the timeline to find the clip, pull its `assetId` from
`Clip.Video.assetId` / `Clip.Audio.assetId`, look up the latest matching
`LockfileEntry` via `Lockfile.findByAssetId`, and call the existing
`Lockfile.withEntryPinned` primitive from cycle 1. Idempotency and error
surface carry over verbatim from `pin_lockfile_entry`.

Three failure modes, each loud with a distinct diagnostic so the agent can
branch:

- Clip not on the timeline → "call list_timeline_clips" hint.
- Clip is `Clip.Text` → "no asset; pinning only applies to AIGC-generated
  media" (text is hand-authored, so the mental model simply doesn't apply).
- Clip has an asset but no matching lockfile entry → "likely imported
  media (not AIGC); use pin_lockfile_entry with an explicit inputHash if
  you know what you're doing." Imported-media clips lack provenance, so
  there's literally no random-compiler row to freeze.

Shared `assetIdForClip` helper kept `private` to the file — two callers
and duplicating the 15 lines would invite drift in the failure messages.

**Alternatives considered.**

1. *Overload `pin_lockfile_entry` to accept either `inputHash` or
   `clipId`.* Rejected — the JSON-schema `oneOf` pattern plays poorly
   across LLM providers (Anthropic, OpenAI, Gemini all parse it
   differently) and the help text gets bifurcated for two equally valid
   paths. Two focused tools each with a single `required` shape is the
   OpenAI function-calling house style and what the rest of this codebase
   follows (cf. `add_clip` vs `import_media`, `describe_source_dag` vs
   `describe_source_node`).
2. *Also accept `trackId + clipId` to disambiguate.* Rejected —
   `ClipId` is globally unique across a project (all existing `find_*` /
   `describe_*` tools already treat it so), and the user's mental model is
   a clip, not a track-slot pair. Adding the extra handle would be ceremony.
3. *Fall through to `pin_lockfile_entry` when the resolution fails.*
   Rejected — silently promoting a "clip not found" error into an
   inputHash-hunt is worse than a precise failure. We'd rather surface
   "you pointed at the wrong clip" than have the agent think it pinned
   something.
4. *Bundle into a single pin/unpin tool with a `mode` enum.* Rejected —
   two verbs (pin, unpin) are each already well-scoped at the lockfile
   layer, and mirroring the `pin_lockfile_entry` / `unpin_lockfile_entry`
   pair keeps the mental model consistent. Cargo cult alignment with
   OpenCode's one-tool-per-action convention (`edit.ts` vs `write.ts`,
   `grep.ts` vs `glob.ts`).

**Coverage.** `PinClipAssetToolTest` — eight tests covering video-clip pin,
pinning-already-pinned idempotency, unpin-clears-pin, unpin-unpinned
idempotency, text-clip-fails (by kind), missing-clip-fails (with
`list_timeline_clips` hint), imported-media-fails (with
`pin_lockfile_entry` hint), audio-clip pin round-trip.

**Registration.** Both tools registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`.
