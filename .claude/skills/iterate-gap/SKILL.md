---
name: iterate-gap
description: Autonomously pick top-priority gaps between the current repo and VISION.md, plan + implement + archive decisions + push to main. Args "<count> [parallel]" — e.g. "/iterate-gap", "/iterate-gap 3", "/iterate-gap 4 parallel". Zero user questions during execution.
---

# iterate-gap — autonomous vision-gap loop

Pick the top-priority gaps between the current repo state and the north star, design + implement each on `main`, archive the decision, push. **Do not ask the user any questions during execution** — every choice is made autonomously per `docs/VISION.md` and industry consensus, and the reasoning lands in `docs/DECISIONS.md` for asynchronous review.

## Arguments

Parse the skill args string as `<count> [parallel]`:

- no args → `count=1`, sequential
- `<N>` (e.g. `3`) → `count=N`, sequential (one full cycle after another, each rebases before starting)
- `<N> parallel` (e.g. `3 parallel`) → `count=N`, parallel via git worktrees, merged back to `main` sequentially after all finish

Caps: `count ∈ [1, 8]` sequential, `count ∈ [2, 4]` parallel. If args are malformed, default to `1` sequential — do not ask the user to clarify.

## Operating mode (both modes)

- **Branch target:** `main`. No feature branches, no PRs. (Parallel mode uses temp worktree branches internally, but they land back on `main` via merge+push inside this invocation.)
- **Questions:** zero. If a decision point arises, decide per VISION + industry consensus and record the reasoning in `docs/DECISIONS.md`. If a blocker needs the user (proprietary key, product call, brand preference), **pick a different gap** — don't stall, don't ask.
- **Plan first, then implement** — each gap has a distinct plan step before edits.
- **Decisions are mandatory.** Every shipped commit pair is `feat(...)` + `docs(decisions): record choices for <feature> (<feat-hash>)`.

---

## Sequential mode (default)

Repeat the cycle below `count` times. Between iterations, `git pull --rebase origin main` so the next gap-analysis sees your own just-pushed work plus anything a teammate pushed. If any iteration's gap-analysis yields no viable candidate (e.g. all Core rubric axes at "有" and no Core OpenCode gaps), **stop early** — don't invent make-work. Report `done N / requested M` honestly.

### 1. Sync main

```
git fetch origin
git pull --rebase origin main
```

If the working tree is dirty or the rebase fails, **stop and report** — do not discard work.

### 2. Gap analysis

Read, in order:

1. `docs/VISION.md` §5 (Gap-finding rubric) — five rubric sections are the scoring axes.
2. `CLAUDE.md` "Platform priority — 当前阶段" for priority ordering; "Known incomplete" to avoid re-flagging already-acknowledged non-regressions.
3. `docs/DECISIONS.md` top ~15 entries — recent decisions constrain what must not be redone.
4. `git log --oneline -20` — see what just shipped.

Then walk `core/domain`, `core/tool/builtin`, `core/agent`, `core/session`, `core/compaction`, `core/permission`, `core/provider`, `core/bus` and each app composition root, scoring rubric axes as 有 / 部分 / 无.

Candidates come from:

- VISION §5 axes scored "部分" / "无".
- OpenCode behavioral gaps per CLAUDE.md "OpenCode as a 'runnable spec'" — compare against the mapped files, extract behavior (never Effect.js structure).

Produce 3-5 concrete candidate gaps; each described as one sentence of what lands in the diff.

### 3. Prioritize

Hard filter order (non-negotiable):

1. **Platform priority** — Core first. Only consider non-Core gaps if every Core rubric axis is at "有".
2. **Abstraction beats patch** (VISION §5 step 3) — prefer a reusable abstraction to a single-genre/single-effect patch.
3. **Short-cycle closure** (VISION §5 step 2) — within a tier, prefer the gap that closes this cycle.

Pick exactly one. Note the runner-up for the final report.

### 4. Plan

Internal plan (no ExitPlanMode — user said no questions). Must cover:

- Target rubric axis (e.g. "§5.2 — new effect 接入成本").
- Files that change, new files, new tools registered.
- Which CLAUDE.md Architecture rules the change must respect (Core zero platform deps, Timeline owned by Core, Tool<I,O> typed + serializer + JSON Schema, `MediaPathResolver` for paths, provider-neutral `LlmEvent`, no Effect.js patterns).
- Which `./gradlew` target proves correctness (pick the tightest from CLAUDE.md Build & run).
- **Anti-requirements check** — scan CLAUDE.md "Anti-requirements". If the plan triggers any red line, **discard plan, pick a different gap.** Do not challenge the user.

If the plan requires info only the user can supply, **pick a different gap.**

### 5. Implement

- `kotlinx.serialization` + `JsonConfig.default` (no custom `Duration` serializer, no ad-hoc `Json`).
- `core/commonMain` has zero platform deps.
- New tools register at **every** `AppContainer` (CLI, Desktop, Server, Android, iOS) — check all five.
- SQLDelight migrations set `PRAGMA user_version`; downgrades refused.

### 6. Verify

Run the tightest relevant gradle test:

| Changed area | Minimum test |
|---|---|
| `core/**` | `./gradlew :core:jvmTest` |
| `platform-impls/video-ffmpeg-jvm/**` | `./gradlew :platform-impls:video-ffmpeg-jvm:test` |
| `apps/server/**` | `./gradlew :apps:server:test` |
| iOS framework surface | `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :core:compileKotlinIosSimulatorArm64` |
| Android | `./gradlew :apps:android:assembleDebug` |
| Desktop | `./gradlew :apps:desktop:assemble` |

Plus `./gradlew ktlintCheck` (auto-fix with `ktlintFormat`). Never commit red.

### 7. Archive the decision

Prepend a new entry to `docs/DECISIONS.md` (newest on top) using the existing entry shape:

```markdown
## YYYY-MM-DD — short title (VISION §X.Y rubric axis)

Commit: `<shorthash>`

**Context.** Why this gap was top of the queue. Rubric axis + what was
observed in current code. Cite OpenCode file if relevant.

**Decision.** What landed. Key type names, tool names, files.

**Alternatives considered.** At least two. Each: what + why rejected.
"Industry consensus" is only valid when named (e.g. "kotlinx.serialization
convention", "OpenCode tool-dispatch shape", "SemVer").

**Coverage.** Which tests exercise this.

**Registration.** Which composition roots were touched (or "None needed").
```

### 8. Commit + push

- Stage specific files by name (never `git add -A` — CLAUDE.md guardrail).
- Commit prefix convention from `git log --oneline -20` (current: `feat(core):`, `docs(decisions):`, `fix(...)`, `refactor(...)`).
- Two commits:
  1. `feat(...): <what>` (or `fix` / `refactor`) — code.
  2. `docs(decisions): record choices for <feature> (<shorthash-of-commit-1>)` — DECISIONS.md.
- `git push origin main`.
- Push rejected (someone else pushed) → `git pull --rebase origin main` → re-verify if rebase touched your files → retry push. Unresolvable rebase conflicts → **stop and report**. Never `--force`, never `--amend` after push.

### 9. Loop or report

If more iterations requested, go to step 1. Otherwise, report:

- Gaps addressed this invocation (one line each: rubric axis + summary).
- Commits pushed (shorthash pairs).
- Tests run + result.
- Anything surprising encountered.
- Runner-up gap from the most recent analysis, so the user can decide whether to re-invoke.

---

## Parallel mode (`<N> parallel`, N ∈ [2, 4])

Run N gap cycles concurrently in isolated git worktrees, then merge back to `main` sequentially inside this same invocation.

### P1. Sync + analyze + pick N **disjoint** gaps

Same as sequential steps 1-2, but in step 3 pick **N disjoint gaps** instead of 1:

- All must satisfy the platform-priority filter (so the first non-Core gap only enters the batch once every earlier Core axis is "有").
- Gaps are "disjoint" when their expected diff footprints don't overlap on any non-DECISIONS file. The orchestrator enumerates each gap's expected file list in step 4 (plan) and checks pairwise non-overlap. If fewer than N disjoint gaps exist, silently reduce N to the largest disjoint subset and report the reduction at the end. (DECISIONS.md overlap is expected — its conflicts resolve mechanically in step P4.)
- If only 1 disjoint gap exists, fall through to sequential mode for the rest of the run.

### P2. Dispatch N parallel agents

Use the Agent tool with `isolation: "worktree"`, **all N calls in a single message** so they run concurrently.

Each sub-agent's prompt is fully self-contained (it has no memory of this conversation). Include:

1. The specific gap assignment — rubric axis + one-sentence diff summary + expected file list from P1's plan.
2. A pasted copy of sequential-mode steps 4-8 (plan → implement → verify → archive decision → commit) with two adjustments:
   - **Branch name:** the agent commits to the auto-created worktree branch — do not rename, do not check out `main`, do not push. Leave the branch un-pushed; the orchestrator merges it.
   - **Decision file path:** the agent writes its decision entry to a staging file `docs/decisions-pending/<yyyy-mm-dd>-<slug>.md` (create the directory if missing) instead of editing `docs/DECISIONS.md`. This removes the only systematic conflict between parallel branches. The orchestrator folds these into `DECISIONS.md` in step P4.
3. The gradle test the agent must run (from the step-6 table).
4. Output contract: final message must include the commit SHA, the staging decision filename, a one-line result summary, and whether tests passed. On failure, no commit — return the error.

Cap parallelism at 4. If N > 4 was requested, silently clamp to 4.

### P3. Collect results

The Agent tool returns each sub-agent's branch + path. Triage:

- **Success** (commit present, tests green) → queue for merge.
- **Failure** (no commit, or tests red) → drop the worktree branch, do not merge, record in the final report.

If zero agents succeeded, report and stop — do not fall back to sequential to save face.

### P4. Sequential merge back to main — push after every branch

For each successful branch, in deterministic order (e.g. rubric axis then timestamp), **push before moving to the next branch** — do not batch multiple features into a single push:

1. `git checkout main`
2. `git pull --rebase origin main`
3. `git rebase main <branch>` — resolves code conflicts if any.
4. **Fold the branch's staging decision file inline.** Move the contents of that branch's `docs/decisions-pending/<yyyy-mm-dd>-<slug>.md` to prepend `docs/DECISIONS.md`, delete the staging file, then add a `docs(decisions): record choices for <feature> (<feat-hash>)` commit on top of the rebased branch. (The short-hash references the feature commit that just got rebased onto main.)
5. Fast-forward merge to main: `git checkout main && git merge --ff-only <branch>`.
6. **`git push origin main` immediately** — before starting the next branch. Each feature's feat+decision commit pair lands on the remote before the next merge begins.
7. Rebase / merge conflict handling:
   - `docs/decisions-pending/*.md` — per-branch unique filenames, should not conflict. If they do, keep both.
   - Code conflicts — **stop and report**. Do not force-resolve. Leave remaining unmerged branches intact so the user can inspect.
8. On push rejection → `git pull --rebase origin main` → retry push. Unresolvable conflicts → **stop and report**.
9. Next branch: go to step 1. Repeat until all queued branches are merged+pushed.

### P5. Cleanup

- Delete merged worktree branches.
- The Agent runtime cleans up worktrees automatically for agents that made no changes; for merged branches, remove the worktree path once the merge is in `main`.
- If any sub-agent failed or any merge was aborted, **leave its worktree + branch intact** so the user can inspect. Report the path + branch name.

### P6. Report

- Requested N, dispatched M (after disjoint filter), succeeded K, merged K' onto main.
- Commits pushed (shorthashes).
- Skipped / failed gaps + reason.
- Any leftover worktrees + branches (with paths) for manual inspection.
- Runner-up gap for next invocation.

---

## Hard rules (both modes, never violate)

1. **Zero user questions during a cycle.** Blocked → pick a different gap or stop.
2. Final state lives on `main`. Parallel mode's intermediate branches must either merge in or be reported as leftover — never silently abandoned.
3. **Commit → push, always.** The moment a cycle's commit pair (or a merged branch in parallel mode) is on local `main`, `git push origin main` before starting the next cycle / next merge. Never finish an invocation with unpushed commits on local `main`.
4. Never skip `./gradlew ktlintCheck`. Lint is hygiene-only; a violation means something real.
5. Never commit without a paired DECISIONS.md entry (staging file in parallel mode, inline in sequential) — except trivial typo fixes that almost never qualify as "top gap".
6. Never `--no-verify`, never `--force`, never `--amend` already-pushed commits, never `git add -A`.
7. Never bypass CLAUDE.md Architecture rules or Anti-requirements. If a gap requires it, pick a different gap.
8. Never port OpenCode's Effect.js structure. Behavior only.
9. Parallel mode never > 4 agents. Silently clamp.
10. Parallel mode picks **disjoint** gaps only. Overlap → reduce N silently and report.
