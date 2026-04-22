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

- **debt-split-export-tool** — `ExportTool.kt` 刚过 500 线（501 行）—— cycle 32 加了 progressive-export-preview 分支让它又胖了一点。500+ 不强制，但已经是第二次碰线。**方向：** `runWholeTimelineRender` / `runPerClipRender` 各自抽到 sibling `video/export/<...>.kt`；`ExportTool` 留 dispatch + 验证 + 返回组装。Rubric §5.2。
- **debt-consolidate-lockfile-maintenance-tools** — `PruneLockfileTool` + `GcLockfileTool` 两个 tool 同用 `LockfileEntry` 做清理，语义重叠（prune = by assetId；gc = by criterion）。LLM 每 turn 多付两份 spec。**方向：** 按 add/remove/apply 变体的先例评估：合并成一件 `maintain_lockfile(action="prune"|"gc"|"all", ...)` 还是按 divergent Input 保留两件套 —— 先跑 §3a 的 4 条结构检查再定。Rubric §5.2。
- **source-graph-export-dot** — Source DAG 能查能搜能 diff 单节点，但没办法 "一眼看到整张图"。Expert path 调试 "为什么这条 character_ref 没传到那条 clip" 要 grep+脑补。**方向：** 新 `export_source_graph_dot(projectId) -> String` 工具（或 `source_query(select=dot)`）吐一张 Graphviz DOT，外部 `dot -Tsvg` 就能渲染。不给 KMP 加 graphviz 依赖，只生成文本。Rubric §5.1。
- **cross-session-spend-aggregator** — `session_query(select=spend)` 只聚合单 session 的 AIGC 花费。项目总花费（跨所有 session 的 lockfile 条目）没查询入口。**方向：** 把 `SELECT_SPEND` 扩到接受 `projectId` 且不要求 `sessionId`，或在 `project_query` 加 `select=spend`；聚合口径跟单 session 保持一致（同 `costCents`、同 groupBy）。Rubric §5.2。
- **desktop-render-preview-panel** — Cycle 32 落了 `Part.RenderProgress.thumbnailPath`，但 desktop app 的 `Main.kt:196` 对 `Part.RenderProgress` 只打一行 progress string。长 export 还是 "两头看到 started/completed" 的 UX。**方向：** desktop Compose 的 render-progress 区加一块小面板，消费最新 `thumbnailPath` 的 JPEG bytes 显示出来；监听文件 mtime 或直接用 `Part.createdAt` 换 key。Rubric §5.4 expert path。
- **cli-streaming-tool-output-renderer** — CLI `Renderer` 把 `Part.ToolCall` 当一次性打印，`Part.RenderProgress` 每条新一行。流式工具输出（cycle 里落的 streaming-tool-output-parts）在 CLI 上没有专门的"原地覆盖 / 折叠"表现。**方向：** Renderer 对同一 CallId 的连续 ToolCall parts 做就地 rewrite（ANSI `\r` 或 `\x1b[K`），RenderProgress 同 jobId 合成一行进度条 + 最新 preview 路径。Rubric §5.4。
- **tool-cost-preflight-estimate** — AIGC 工具调用前没有"本次调用大约要花多少"的入口 —— cost-budget-guard 只在**花了之后**才拦。LLM 要自己判断能不能调用这个 tool 只能凭感觉。**方向：** 新 `describe_tool_cost(toolId, input) -> CostEstimate(cents, currency, basis)` 或 `list_tools` 扩一个 `estimateCentsPer` 字段；值来自 provider-level pricing 表（Replicate / OpenAI / Anthropic 都在 MetricsRegistry 已经记录）。Rubric §5.2。
- **project-diff-source-graphs** — `DiffProjectsTool` 对比 timeline；source DAG 的 diff（哪些 SourceNode 被加 / 删 / 改）没 entry point。Fork 项目后想看"我动了哪些 source 节点"只能人肉。**方向：** 扩 `diff_projects` 的 Output 加 `sourceAdds / sourceRemoves / sourceModifies`，或单独 `diff_source_graphs(a, b)`；对比口径用 `SourceNode.id` + `contentHash`。Rubric §5.1。

## P2 — 记债 / 观望

- **debt-consolidate-pinning-tools** — `SetClipAssetPinnedTool` + `SetLockfileEntryPinnedTool` 同是"切一个布尔 pinned"的 set tool，前者 key 是 `(projectId, clipId)`，后者是 `(projectId, assetId)`。**方向：** 按 add/remove 先例评估；可能合并成 `set_pinned(target="clip"|"lockfile_entry", id, pinned)` 更合适 —— 先跑 §3a 检查。Rubric §5.2。
- **debt-consolidate-project-creation** — `CreateProjectTool` + `CreateProjectFromTemplateTool` 产出同一件东西（一个新 Project），只是前者空 source，后者带 template 种子。**方向：** 评估合为 `create_project(template: String? = null)`，null = 空源 path；或按先例保留两件套。Rubric §5.2。
- **debt-consolidate-session-trash** — `ArchiveSessionTool` + `DeleteSessionTool` 都是 session "从活跃列表移出" 的 lifecycle 操作，一个可逆一个不可逆。两件套是否合为 `retire_session(mode="archive"|"delete")` 值得评估。Rubric §5.2。
- **semantic-search-source-nodes** — `search-source-nodes-body-content-lookup` 已实现 lexical 搜索；但 "找意思相近的 character_ref" 靠关键词很脆。VISION §5.1 "改一个 source 节点，下游哪些 clip 被标为 stale" 的扩展面是概念级检索。**方向：** 集成轻量嵌入（CoreML / ONNX Runtime / 本地 MiniLM），在 `source_query` 加 `select=semantic_search(query, topK)`；platform-impl 隔离。Rubric §5.1。
- **per-clip-render-cache-gc** — `ClipRenderCache.append` 只追加，没 eviction。长寿命项目每次 source 漂移都会留一份 orphan mezzanine；磁盘会涨到人类手动删。**方向：** 新 `gc_clip_render_cache(projectId, keepLast: Int? | keepSinceEpoch: Long?)` 或 `save_project_snapshot` 时自动 prune 陈旧条目 + 对应 mp4。Rubric §5.3。
- **tool-call-provenance-in-lockfile** — `LockfileEntry` 记了 tool / input / sourceContentHashes，但不记是**哪条 Message** 触发的。审计 "这张 image 是在哪句 prompt 下生成的" 要回去 grep session parts。**方向：** `LockfileEntry.originatingMessageId: MessageId? = null`（default 向后兼容），AIGC 工具 `ctx.messageId` 填进去。Rubric §5.2。
- **project-import-validator** — `ImportProjectFromJsonTool` 解析 JSON 直接 upsert，没校验 `Clip.assetId` / `Clip.sourceBinding` 指向的对象是不是都存在。坏 JSON 能导入一个"timeline 引用不存在的 source 节点"的项目，后面 `find_stale_clips` 才报警。**方向：** import 时跑一次跟 `ValidateProjectTool` 同口径的完整性校验，reject or warn on broken refs。Rubric §5.3。
- **agent-interrupt-via-bus** — OpenCode 通过 bus 事件 trigger agent cancel（`session/prompt.ts` 的 abort 流）；我们的 Agent 支持 `Job.cancel()` 但没有"所有平台统一的取消路径"。长 provider stream 中途用户按 Ctrl+C，取消生效有多快？没有测试覆盖。**方向：** `BusEvent.SessionCancelled(sessionId)` + Agent 订阅 → 传到 provider stream 的 `cancel()`；加 E2E 测 "cancel 从发到生效 ≤ 500ms"。Rubric §5.4。

