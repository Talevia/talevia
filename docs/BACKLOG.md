# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

## P1 — 中优，做完 P0 再排

- **per-clip-incremental-render** — CLAUDE.md `Known incomplete` 首条：`ExportTool` 只 memoize 整时间线 export，没有"只重渲 stale 的一段 + 剩下从 cache 拼回"的增量路径。长项目一次小修改依旧全量 re-render。**方向：** 扩展 `RenderCache` 支持 per-clip-segment 级 memo（key 含 clip contentHash + source binding hash + profile）；`ExportTool` 发现 stale clip 集后只 re-ffmpeg 那几段 + concat 从 cache 拼接未变化段。参考 `docs/decisions/2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md` 里记录的方向。Rubric §5.3。

## P2 — 记债 / 观望

- **debt-consolidate-video-remove-variants** — 配合 P1 的 `debt-consolidate-video-add-variants`：`RemoveClipTool` + `RemoveFilterTool` + `RemoveTrackTool` + `RemoveTransitionTool` 四件套。**方向：** 同 add 的判断，合为 `remove_from_timeline(target=...)` 或保留四件套并在 decision 说明。Rubric 外 / debt。

- **debt-fold-list-project-snapshots** — `ListProjectSnapshotsTool` 是 `project_query` 之外唯一还单独存在的 list_* 工具。**方向：** 加 `project_query(select=snapshots)`，删 `ListProjectSnapshotsTool.kt`。Rubric 外 / debt。
