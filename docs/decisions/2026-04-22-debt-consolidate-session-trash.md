## 2026-04-22 — Keep ArchiveSessionTool / DeleteSessionTool as two tools (debt evaluated)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** Backlog bullet `debt-consolidate-session-trash` asked
whether `ArchiveSessionTool` + `DeleteSessionTool` should fold into a
single `retire_session(mode="archive"|"delete")` tool. Both verbs
remove a session from the active-listing surface
(`session_query(select=sessions)` filters `archived = 0`; delete
removes the row outright), and on the surface they look like siblings.
This cycle is the evaluation.

Decision: **keep the two tools unchanged.** The four structural checks
surface the same disqualifier pattern as the pinning-tools, lockfile-
maintenance, and apply/add/remove variant decisions: the permission
layer, input shape, output shape, and reversibility contract all
diverge in ways a `mode` discriminator cannot cleanly carry.

**Decision analysis (the 4 structural checks).**

Input shapes:

| Tool | Required | Optional | sessionId | Permission |
|---|---|---|---|---|
| `archive_session` | — | `sessionId?` | optional (context-resolved) | `session.write` (ALLOW) |
| `delete_session` | `sessionId` | — | required | `session.destructive` (ASK) |

1. **Permission keyword divergence is operator-facing, not cosmetic.**
   `core/permission/DefaultRules.kt:38` maps `session.write` → ALLOW
   (mundane metadata flip, mirrors `project.write` / `source.write`).
   `core/permission/DefaultRules.kt:42` maps `session.destructive` →
   ASK (mirrors `project.destructive`; permanent row+cascade delete
   warrants a prompt). A merged `retire_session` tool has a single
   `permission: PermissionSpec`. Two options, both regressions:
   - Give the merged tool `session.destructive` — every archive call
     now prompts the operator, even though archive is fully reversible
     via `unarchive_session`. Regresses the zero-friction "put away"
     UX the archive tool was built for.
   - Give the merged tool `session.write` — delete stops prompting,
     silently removing the ASK gate that protects against "mode=delete
     typo in an LLM plan". Actively unsafe.
   The split exists precisely so the ACL can differ. Collapsing it
   forces a single policy where two are correct.

2. **Input required-ness diverges.** `archive_session.Input.sessionId:
   String? = null` — omit to archive the owning session (the common
   "I'm done with this conversation, put it away" case uses
   `ctx.resolveSessionId(null)`). `delete_session.Input.sessionId:
   String` — required, no self-delete (the tool cannot delete the
   session it is dispatching from; the CASCADE would race with the
   `Agent.run` writing the result). In a merged tool, `sessionId` must
   be `String?` at the schema level (to keep archive's zero-arg
   convenience) with a runtime branch that rejects `mode="delete" &&
   sessionId == null`. That is a required-on-some-modes field — the
   exact failure mode §3a Rule 1 flags: the schema allows a call the
   tool then rejects at runtime.

3. **Output shape divergence.** `archive_session.Output` carries
   `wasArchived: Boolean` (the idempotency signal — re-archiving is a
   no-op, callers want to know). `delete_session.Output` carries
   `archived: Boolean` (informational — *was* this session archived
   when you nuked it? Useful for audit trail). Same field name would
   be misleading; different field names in a merged output force every
   consumer to branch on `mode` to decode the semantics. Matches the
   output-shape trap the lockfile-maintenance decision flagged.

4. **Reversibility contract / sibling symmetry.** Archive has an
   explicit inverse: `UnarchiveSessionTool` (same file as `ArchiveSessionTool`).
   The archive/unarchive pair shares the `wasArchived` / `wasUnarchived`
   idempotency signal and the optional `sessionId?` surface. Delete
   has **no inverse** — there is no "restore deleted session" tool and
   the store has no snapshot path for sessions. If we merged
   archive+delete into `retire_session(mode=…)`, the inverse becomes
   asymmetric: `retire_session(mode="archive")` has an inverse
   (`unarchive_session`), `retire_session(mode="delete")` does not.
   The LLM now has to learn "some modes of this tool are reversible
   and some are not" — the exact categorical confusion the pinning-
   tools decision (`2026-04-22-debt-consolidate-pinning-tools.md`)
   rejected.

**Token cost estimate.** Measured against shipped helpText + schema:
- `archive_session` ≈ 170 tokens (1 optional field, short prose).
- `delete_session` ≈ 190 tokens (1 required field, longer warning).
- **Total: ≈ 360 tokens.**
- Merged `retire_session(mode, sessionId?)` estimated ≈ 340 tokens
  (still has to cover both behaviors, document the per-mode
  required-ness, and carry the archive ↔ unarchive pointer). Saving
  ≈ 20 tokens per turn. Well below the clarity-worth-it threshold
  that killed the prior consolidations.

**Alternatives considered.**

1. **Collapse into `retire_session(mode="archive"|"delete")`** —
   rejected for the four structural reasons above. The permission-
   layer regression alone is disqualifying (either archive starts
   prompting or delete stops prompting; both are wrong).

2. **Keep two tools, introduce a pure convenience `retire_session`
   façade** — no net reduction (adds a third tool that dispatches
   either of the existing two). Rule 1 violation (tool count grows
   without removing anything equivalent).

3. **Only merge archive+unarchive into `set_session_archived(flag)`,
   leave delete separate** — a variation on the pinning-tools
   Option A pattern that `2026-04-22-debt-consolidate-pinning-tools.md`
   explicitly rejected. The idempotent-flip tools are documented
   via their verbs (`archive` / `unarchive`), not a boolean; the LLM
   picks the right verb by name. Same conclusion as the pinning
   decision applies here verbatim.

**Reference precedents (cite-worthy in future §3a sweeps).**
- `2026-04-22-debt-consolidate-pinning-tools.md` — idempotent-flip
  pairs kept as two (verb-over-boolean, same-addressing).
- `2026-04-22-debt-consolidate-lockfile-maintenance-tools.md` —
  4-check format; output-shape divergence as disqualifier.
- `2026-04-21-debt-merge-pin-unpin-tool-pairs.md` (Option E) — the
  original "cross-addressing / divergent contracts → keep split"
  precedent.
- `core/permission/DefaultRules.kt:38,42` — `session.write` ALLOW
  vs `session.destructive` ASK, the operator-facing ACL divergence
  that anchors check #1.

**Impact.**
- No code change. No tests modified.
- Backlog bullet `debt-consolidate-session-trash` removed.
- Decision doc preserves the "evaluated → kept" outcome so future
  sweeps don't re-litigate.
