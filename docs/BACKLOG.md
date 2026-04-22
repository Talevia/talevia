# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **auto-author-first-project-from-intent** — 小白路径 §5.4 的硬缺口：今天用户必须手动 `create_project` + 手动 `set_character_ref` / `add_source_node` 才能给 agent 投料。北极星是 "一句话意图 → 可看初稿"。**方向：** 新增 `start_project_from_intent(intent: String)` tool：LLM 调 agent 把 intent 解析成 genre（先覆盖 narrative / vlog），生成 skeleton source graph（character / style / shot placeholders），返回 projectId。不产生任何 AIGC 资产——只是搭好骨架让 agent 继续 fill in。Rubric §5.4。

## P1 — 中优，做完 P0 再排

- **generate-project-variant** — VISION §6 叙事 / vlog 例子明确点 "30s / 竖版 variant"，但当前没有一等抽象生成变体；用户必须手动 `fork_project` + `set_output_profile` + re-export。**方向：** `generate_variant(projectId, variantSpec: {aspectRatio?, durationSeconds?, language?})`：fork project、按 spec 调整 timeline（比例裁剪 / 按 key-shot 浓缩 / 重生成 TTS 变体）、write a child project id pointing back to parent。Rubric §5.2。

- **per-clip-incremental-render** — CLAUDE.md `Known incomplete` 首条：`ExportTool` 只 memoize 整时间线 export，没有"只重渲 stale 的一段 + 剩下从 cache 拼回"的增量路径。长项目一次小修改依旧全量 re-render。**方向：** 扩展 `RenderCache` 支持 per-clip-segment 级 memo（key 含 clip contentHash + source binding hash + profile）；`ExportTool` 发现 stale clip 集后只 re-ffmpeg 那几段 + concat 从 cache 拼接未变化段。参考 `docs/decisions/2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md` 里记录的方向。Rubric §5.3。

## P2 — 记债 / 观望

- **project-query-sort-by-updatedAt** — `project_query(select=tracks | timeline_clips | assets)` 当前 sortBy 都是 domain-specific 维度（index / clipCount / span / startSeconds / duration / id）；没有 "最近改过的在前" 的时间排序。**方向：** `Timeline.tracks` / `clips` / `assets` 加 optional `updatedAtEpochMs` 元数据（或从 `Project.updatedAt` 反推），在 `project_query` 里增加 `sortBy="recent"` 支持。Rubric §5.2。

- **compact-session-threshold-visible-in-ui** — `Compactor` 用 `compactionTokenThreshold = 120_000` 硬编码触发；UI 没有渠道展示 "当前 session 离阈值多远"。用户看不到进度条。**方向：** 扩展 `session_query(select=status)` （P1 那条的 follow-up）返回 `estimatedTokens` + `compactionThreshold` + `percent`；UI 就能渲染占比条。Rubric §5.4。

- **validate-project-auto-on-load** — `ValidateProjectTool` 存在，但项目加载路径（`SqlDelightProjectStore.get`）不会自动跑，只靠 agent 明调用。长项目可能带入未发现的 DAG 损坏而没人注意。**方向：** `SqlDelightProjectStore.get` 在返回前跑一次 light-weight validation（只检 DAG cycle / missing parent ref，不做全量校验），失败记 warning log + 发 `BusEvent.ProjectValidationWarning`，但不 throw（避免锁死存量项目）。Rubric §5.1 / §5.3。
