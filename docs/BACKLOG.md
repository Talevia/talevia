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


## P2 — 记债 / 观望

- **debt-consolidate-pinning-tools** — `SetClipAssetPinnedTool` + `SetLockfileEntryPinnedTool` 同是"切一个布尔 pinned"的 set tool，前者 key 是 `(projectId, clipId)`，后者是 `(projectId, assetId)`。**方向：** 按 add/remove 先例评估；可能合并成 `set_pinned(target="clip"|"lockfile_entry", id, pinned)` 更合适 —— 先跑 §3a 检查。Rubric §5.2。
- **debt-consolidate-project-creation** — `CreateProjectTool` + `CreateProjectFromTemplateTool` 产出同一件东西（一个新 Project），只是前者空 source，后者带 template 种子。**方向：** 评估合为 `create_project(template: String? = null)`，null = 空源 path；或按先例保留两件套。Rubric §5.2。
- **debt-consolidate-session-trash** — `ArchiveSessionTool` + `DeleteSessionTool` 都是 session "从活跃列表移出" 的 lifecycle 操作，一个可逆一个不可逆。两件套是否合为 `retire_session(mode="archive"|"delete")` 值得评估。Rubric §5.2。
- **semantic-search-source-nodes** — `search-source-nodes-body-content-lookup` 已实现 lexical 搜索；但 "找意思相近的 character_ref" 靠关键词很脆。VISION §5.1 "改一个 source 节点，下游哪些 clip 被标为 stale" 的扩展面是概念级检索。**方向：** 集成轻量嵌入（CoreML / ONNX Runtime / 本地 MiniLM），在 `source_query` 加 `select=semantic_search(query, topK)`；platform-impl 隔离。Rubric §5.1。
- **per-clip-render-cache-gc** — `ClipRenderCache.append` 只追加，没 eviction。长寿命项目每次 source 漂移都会留一份 orphan mezzanine；磁盘会涨到人类手动删。**方向：** 新 `gc_clip_render_cache(projectId, keepLast: Int? | keepSinceEpoch: Long?)` 或 `save_project_snapshot` 时自动 prune 陈旧条目 + 对应 mp4。Rubric §5.3。
- **tool-call-provenance-in-lockfile** — `LockfileEntry` 记了 tool / input / sourceContentHashes，但不记是**哪条 Message** 触发的。审计 "这张 image 是在哪句 prompt 下生成的" 要回去 grep session parts。**方向：** `LockfileEntry.originatingMessageId: MessageId? = null`（default 向后兼容），AIGC 工具 `ctx.messageId` 填进去。Rubric §5.2。
- **project-import-validator** — `ImportProjectFromJsonTool` 解析 JSON 直接 upsert，没校验 `Clip.assetId` / `Clip.sourceBinding` 指向的对象是不是都存在。坏 JSON 能导入一个"timeline 引用不存在的 source 节点"的项目，后面 `find_stale_clips` 才报警。**方向：** import 时跑一次跟 `ValidateProjectTool` 同口径的完整性校验，reject or warn on broken refs。Rubric §5.3。
- **agent-interrupt-via-bus** — OpenCode 通过 bus 事件 trigger agent cancel（`session/prompt.ts` 的 abort 流）；我们的 Agent 支持 `Job.cancel()` 但没有"所有平台统一的取消路径"。长 provider stream 中途用户按 Ctrl+C，取消生效有多快？没有测试覆盖。**方向：** `BusEvent.SessionCancelled(sessionId)` + Agent 订阅 → 传到 provider stream 的 `cancel()`；加 E2E 测 "cancel 从发到生效 ≤ 500ms"。Rubric §5.4。

