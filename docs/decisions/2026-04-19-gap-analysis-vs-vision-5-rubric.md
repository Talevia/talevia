## 2026-04-19 — Gap analysis vs VISION §5 rubric (desktop-first pass)

**Context.** Kicking off a new autonomous "find-gap → fill-gap" cycle. Per
`CLAUDE.md` platform priority, macOS desktop must reach "相对完善可用"
before iOS / Android get new features. Scored each VISION §5 rubric section
against current code and picked the candidates that (a) score lowest on the
desktop path and (b) fit a short-cycle close-the-loop.

**State read (what's green):**
- §5.1 Source layer — Source DAG, ref / bible / palette nodes, mutation
  tools, parentIds, content hashing, import / list / remove all landed in
  Core.
- §5.2 Compiler — traditional / AIGC / ML / filter lanes all covered by
  tools; transitions + subtitles + LUT render on all three engines; only
  Android `vignette` remains a known gap.
- §5.3 Artifact — Lockfile pins AIGC inputs, content-hash cache keys,
  stale detection + `find_stale_clips` + `replace_clip`.
- §5.5 Cross-shot consistency — character refs + style bibles + brand
  palettes flow into prompt folding and LoRA / reference arrays.

**What's red on the desktop path:**
- Desktop SQLite is `JdbcSqliteDriver.IN_MEMORY` — every project / session /
  source node / lockfile entry / snapshot evaporates on restart. The VISION
  §3.4 claim ("Project / Timeline is a codebase: 可读 / 可 diff / 可版本化 /
  可组合") can't hold if the codebase disappears when you quit the editor.
- Desktop UI exposes three buttons (import / add_clip / export) plus a chat
  panel. Every other ability — filters, transitions, subtitles, AIGC,
  source editing, snapshots, fork, lockfile, stale — is chat-only. VISION
  §4 expert path ("用户直接编辑 source 的每个字段、override 某一步编译") has
  no UI surface.
- No in-app preview. Users export an mp4 and open it in an external
  player. Blocks VISION §5.4 "agent 能跑出可看初稿" from being a tight loop.
- Timeline view is a flat list of clips — can't see tracks, applied
  filters / LUT / subtitles / transitions, or stale state.
- No project browser. App boots with one random project each time.

**Prioritised task list (high → low).** Each task closes a concrete rubric
gap on the desktop path. Implement in order; don't parallelise.

1. **Persistent SQLite for desktop.** Without it the next five tasks are
   all "felt experience disappears on quit". Smallest diff, biggest
   unblock. VISION §3.4 codebase invariant.
2. **In-app video preview.** Closes the agent iteration loop — "make
   change → watch result" shouldn't require a file browser. VISION §5.4.
3. **Source-node panel.** Surfaces the §5.1 DAG to the expert path.
4. **Rich Timeline inspector.** Tracks, applied effects per clip, stale
   badges. Expert path per VISION §4 / §5.2.
5. **Project browser + snapshot / fork / restore UI.** VISION §3.4
   (可版本化 / 可分支).
6. **Lockfile + stale-clip panel.** VISION §3.1 / §3.2 visibility.
7. **Android vignette filter.** Final cross-engine parity gap — lower
   priority because Android is "don't regress" per current platform
   priority.

**Why this order, not something else.**
- **Persistence before UI polish.** UI that lets the user build real work
  and then throws it away is worse than no UI at all — it trains them not
  to trust the system. Ordering persistence #1 respects the VISION §3.4
  first-class "codebase" claim.
- **Preview before editor richness.** Without a preview the edit ↔ see
  loop is so slow that features on top of it don't really get used. Every
  subsequent UI task is validated by "can I now iterate on this visually?"
- **Source → Timeline → Project → Lockfile.** Expert-path visibility goes
  from the smallest surface (source, which is 3-4 kinds of nodes) up to
  the largest (timeline, many tracks × many clips × many effects), then
  project-level operations, then the most advanced (lockfile / stale). A
  user can get real work done after #3-#4; #5-#6 promote the expert
  workflow from "possible via chat" to "direct-manipulate".
- **Android parity last.** Per `CLAUDE.md` platform priority, Android is
  explicitly "不退化" during this phase — the vignette gap is already
  documented as a "Known incomplete", not a red-line break.

**Process rules for this cycle.**
- Execute directly on `main`. Plan → implement → push per task.
- Every decision made autonomously gets a new entry here — this log is the
  async review channel.
- Red lines from `CLAUDE.md` stand (CommonMain zero platform dependency,
  Tool registration over Core edits, Timeline is owned by Core, etc.). If
  a task seems to require breaking one, stop and challenge per VISION
  §"发现不符".

---
