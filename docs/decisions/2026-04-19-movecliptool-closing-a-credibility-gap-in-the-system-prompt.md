## 2026-04-19 — `MoveClipTool` — closing a credibility gap in the system prompt

**Context.** The system prompt's "Removing clips" section promised the model
it could chain `move_clip` on every downstream clip to simulate ripple-delete
after `remove_clip` — but the tool didn't exist. The LLM was being told to
call a nonexistent primitive, which risks hallucinated calls and broken
recovery paths. The gap was pre-existing (earlier session had authored the
tool file + tests but not wired registration).

**Decision.** Register the existing `MoveClipTool` in all four composition
roots (desktop, server, Android, iOS Swift) and teach the model explicitly
via a new "# Moving clips" system-prompt section. Add `move_clip` to the
prompt-test key phrases so removal regresses loudly next time.

**Semantics captured in the prompt.**
- Changes `timeRange.start`; duration and `sourceRange` preserved (same
  material, different timeline position).
- Same-track only — cross-track moves change rendering semantics (stack
  order, filter pipeline) and deserve a separate tool when a driver appears.
- No overlap validation — PiP, transitions, and layered effects legitimately
  need overlapping clips; refusing to move into an overlap would block
  real workflows.
- Emits a timeline snapshot, so `revert_timeline` can undo the move —
  consistency with every other timeline-mutating tool.

**Bundled with `generate_video` commit.** Linter auto-added the MoveClipTool
import to the server container when it saw the untracked file, so splitting
the commits would have required fighting the linter. Bundled into the same
commit as T6 (VideoGen); commit message calls out both pieces explicitly.
Same pattern as the earlier `remove_clip` + `apply_lut` bundle.
