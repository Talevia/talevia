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

- **session-status-snapshot-query** — 当前 Agent 状态只通过 `BusEvent.AgentRunStateChanged` 流式广播；新 subscriber（UI 冷启动、新进程 attach）拿不到当前态。**方向：** 让 `Agent` 维护 per-session `lastState: Map<SessionId, AgentRunState>`（每次 publish 时更新）+ 暴露 `Agent.currentState(sessionId): AgentRunState?`；新增 `session_query(select=status, sessionId=X)` 让 LLM / UI 都能 snapshot 当前态。Rubric §5.4。

- **debt-merge-move-clip-pair** — `MoveClipTool` + `MoveClipToTrackTool` 一对 —— 前者 same-track 移动、后者跨 track。典型 §3a.2 singular/variant 分裂。**方向：** 合并为 `move_clip(clipId, toTrackId?: String, timelineStartSeconds?: Double)`：`toTrackId` null = 原 track 内移动，非 null = 跨 track；`timelineStartSeconds` null = 保持相对位置。Rubric 外 / §R.5.4。

- **debt-merge-apply-filter-pair** — `ApplyFilterTool` + `ApplyFilterToClipsTool`（单 clip vs 多 clip 批量）同样是 §3a.2 单/批分裂。**方向：** 合并为 `apply_filter(clipIds: List<String>, filter: ...)`，单 clip 传一元素 list 即可。Rubric 外 / §R.5.4。

- **debt-merge-add-subtitle-pair** — `AddSubtitleTool` + `AddSubtitlesTool`（单条 vs 批量）同病。**方向：** 合并为 `add_subtitles(subtitles: List<SubtitleSpec>)`，单条 = 一元素 list。Rubric 外 / §R.5.4。

- **debt-merge-import-source-node-pair** — `ImportSourceNodeTool` + `ImportSourceNodeFromJsonTool`（portable envelope vs raw JSON body）。两者 output 契约基本一致，只是输入源不同。**方向：** 合并为 `import_source_node(source: {path?: String, jsonBody?: JsonElement})`，二选一。Rubric 外 / §R.5.4。

## P2 — 记债 / 观望

- **debt-split-create-project-from-template** — `CreateProjectFromTemplateTool.kt` 416 行，接近 500 行阈值（§R.5.3 preemptive）。5 个 genre 模板（narrative / vlog / ad / musicmv / tutorial）的 seed payload 都塞在同一个文件里，新加 genre 要在中间插一大段。**方向：** 把每个 genre template 提取到 `project/template/<genre>.kt`，主文件变 dispatcher。Rubric 外 / §R.5.3。

- **describe-project-fold-snapshots** — `describe_project` 输出含 `snapshotCount: Int` 但不含最近 snapshot 列表；UI 要知道 "最近 3 个保存点" 还得再 call `project_query(select=project_snapshots)`（或现有的 `list_project_snapshots`）。**方向：** 给 `describe_project.Output` 加 `recentSnapshots: List<SnapshotSummary>` 字段（cap 3-5 条，按 capturedAt DESC），UI 一次读到。Rubric §5.4。

- **agent-retry-bus-observable** — `BusEvent.AgentRetryScheduled` 已发出，但 `MetricsRegistry` 没有 per-reason counter（overload / rate_limit / http_5xx 分开计数）。ops dashboard 只能看到总 retry 数。**方向：** 给 `Metrics.counterName` 加 `agent.retry.count{reason=<slug>}` 分类 counter，`/metrics` Prometheus 端点就能按 reason label 查图。Rubric §5.4。

- **project-query-sort-by-updatedAt** — `project_query(select=tracks | timeline_clips | assets)` 当前 sortBy 都是 domain-specific 维度（index / clipCount / span / startSeconds / duration / id）；没有 "最近改过的在前" 的时间排序。**方向：** `Timeline.tracks` / `clips` / `assets` 加 optional `updatedAtEpochMs` 元数据（或从 `Project.updatedAt` 反推），在 `project_query` 里增加 `sortBy="recent"` 支持。Rubric §5.2。

- **session-query-include-compaction-summary** — Session 的 `Part.Compaction` 条目今天靠 `session_query(select=parts, kind=compaction)` 拉 raw 行；没法一次拿到 "这个 session 压缩过几次 + 每次从哪到哪 + summary"。**方向：** 给 `session_query` 加 `select=compactions`，每行带 `fromMessageId` / `toMessageId` / `summaryText` / `compactedAtEpochMs`，聚合视图。Rubric §5.2。

- **session-export-portable-envelope** — `export_project` / `export_source_node` 已有 portable envelope 导出；但 `Session` 没有——想把一段 session（agent 对话 + tool-call 历史）备份 / 迁移到另一机器没有工具。**方向：** 加 `export_session(sessionId, outputPath)` 打包 `Session` + `Message[]` + `Part[]` 到单一 JSON envelope（同 `export_project.kt` 风格）；配套 `import_session(from)` 留给后续 cycle。+1 tool 需在 decision 里说明（现有 `export_*` 家族命名一致性 + 无法用 `session_query` 替代写出文件）。Rubric §5.4。

- **compact-session-threshold-visible-in-ui** — `Compactor` 用 `compactionTokenThreshold = 120_000` 硬编码触发；UI 没有渠道展示 "当前 session 离阈值多远"。用户看不到进度条。**方向：** 扩展 `session_query(select=status)` （P1 那条的 follow-up）返回 `estimatedTokens` + `compactionThreshold` + `percent`；UI 就能渲染占比条。Rubric §5.4。

- **validate-project-auto-on-load** — `ValidateProjectTool` 存在，但项目加载路径（`SqlDelightProjectStore.get`）不会自动跑，只靠 agent 明调用。长项目可能带入未发现的 DAG 损坏而没人注意。**方向：** `SqlDelightProjectStore.get` 在返回前跑一次 light-weight validation（只检 DAG cycle / missing parent ref，不做全量校验），失败记 warning log + 发 `BusEvent.ProjectValidationWarning`，但不 throw（避免锁死存量项目）。Rubric §5.1 / §5.3。
