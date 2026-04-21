# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **agent-run-state-machine** — `Agent.kt` 里的状态是隐式的（running / awaiting tool / emitting text 散落在 when/if）。revert session 拿不到 "上次在哪一步"。OpenCode `session/run-state.ts` 是显式状态机 + 持久化。**方向：** 加 `sealed class AgentRunState { Idle; Generating; AwaitingTool; Compacting; Cancelled; Failed(cause) }`，每次状态转移写入 session（或 bus 事件），revert 能恢复到最近的 Idle / AwaitingTool 边界。Rubric §5.4。

- **auto-author-first-project-from-intent** — 小白路径 §5.4 的硬缺口：今天用户必须手动 `create_project` + 手动 `set_character_ref` / `add_source_node` 才能给 agent 投料。北极星是 "一句话意图 → 可看初稿"。**方向：** 新增 `start_project_from_intent(intent: String)` tool：LLM 调 agent 把 intent 解析成 genre（先覆盖 narrative / vlog），生成 skeleton source graph（character / style / shot placeholders），返回 projectId。不产生任何 AIGC 资产——只是搭好骨架让 agent 继续 fill in。Rubric §5.4。

- **streaming-tool-output-parts** — 长跑 tool（`generate_video` 30s+、`export` 可能 1min+）没有中间进度回传，UI 看到的是 "等待 → 突然完成"。`ToolContext.emitPart` 已存在但几乎没 tool 用。OpenCode `session/processor.ts` 流式消费 intermediate parts。**方向：** 给 `generate_video` / `generate_image` / `export_project` / `export` 4 个关键 tool 加 `ctx.emitPart(RenderProgress(...))` 调用点，bus 把 Part 发出去，UI 能画 progress bar。Rubric §5.2。

## P1 — 中优，做完 P0 再排

- **debt-split-taleviasystemprompt** — `core/agent/TaleviaSystemPrompt.kt` 743 行。每个 LLM turn 都要重算 + 传输全量 system prompt，长文件也阻碍维护。**方向：** 拆成 composable 片段（build-system / consistency / export / lockfile / session-project / permissions 各一段），`Agent` 按需组装（未来可以按当前 session 的 project 类型选片段）。Rubric 外 / R.5.3 长文件。

- **debt-split-projectquerytool** — `core/tool/builtin/project/ProjectQueryTool.kt` 602 行。一个 select 一个 runXxx + 对应 Row 类型，全塞一个文件。**方向：** 把 `runTracks` + `TrackRow` 拆到 `project/query/Tracks.kt`，timeline_clips / assets 同理；`ProjectQueryTool` 保留 dispatch 壳。Rubric 外 / R.5.3 长文件。

- **debt-split-agent-kt** — `core/agent/Agent.kt` 581 行。turn loop + prompt builder + tool dispatch + 错误重试都挤一个类。**方向：** 拆 `AgentTurnLoop` / `AgentPromptBuilder` / `AgentErrorHandler`（或 `AgentRetry`），`Agent` 变成 orchestrator。Rubric 外 / R.5.3 长文件。

- **debt-merge-pin-unpin-tool-pairs** — `PinClipAssetTool` + `UnpinClipAssetTool`、`PinLockfileEntryTool` + `UnpinLockfileEntryTool` 4 个成对工具。对 LLM 是两个互斥分支。**方向：** 合并为 `set_clip_asset_pinned(clipId, pinned: Boolean)` / `set_lockfile_entry_pinned(inputHash, pinned: Boolean)` upsert 形态（和 `set_character_ref` 同类）。Rubric 外 / §3a.2 类似。

- **debt-consolidate-session-reads-via-session-query** — `core/tool/builtin/session/` 下 8 个 List* / Describe* tool（`list_sessions`、`list_messages`、`list_parts`、`list_session_forks`、`list_session_ancestors`、`list_tool_calls`、`describe_session`、`describe_message`）。和 project 域犯同一个"每个维度一个工具"病。**方向：** 参考 `project_query` 模式引入 `session_query(select ∈ {sessions, messages, parts, forks, ancestors, tool_calls}, filter, sort, limit)`，按 select 吸收至少 6 个旧工具。Rubric 外 / §5.2。

- **debt-consolidate-source-reads-via-source-query** — `core/tool/builtin/source/` 下 `list_source_nodes` + `search_source_nodes` + `describe_source_node` + `describe_source_dag` 4 个 read tool。**方向：** 引入 `source_query(select ∈ {nodes, dag_summary}, filter ∈ {kind, kindPrefix, contentSubstring, id}, limit)`，吸收 list + search，describe_source_node 留作单实体深看。Rubric 外。

- **session-projector-views** — `Session` 当前只暴露一份 linear message list，UI 自己拼 "tool-call tree" / "artifact timeline" view。OpenCode `session/projectors.ts` 把投影下沉到 session 层。**方向：** `core.session.SessionProjector` 接口 + 2 个实现：`ToolCallTreeProjector`（把嵌套 tool-call 展开成树）、`ArtifactTimelineProjector`（扫 lockfile entries 按时间聚合）。UI 按需选投影。Rubric §5.4。

- **generate-project-variant** — VISION §6 叙事 / vlog 例子明确点 "30s / 竖版 variant"，但当前没有一等抽象生成变体；用户必须手动 `fork_project` + `set_output_profile` + re-export。**方向：** `generate_variant(projectId, variantSpec: {aspectRatio?, durationSeconds?, language?})`：fork project、按 spec 调整 timeline（比例裁剪 / 按 key-shot 浓缩 / 重生成 TTS 变体）、write a child project id pointing back to parent。Rubric §5.2。

- **provider-auth-state** — 5 个 AppContainer 各自 `env["OPENAI_API_KEY"]?.takeIf(...)` 判断是否注册 OpenAI 相关 tool。逻辑散落 + UI 没法展示 "哪些 key 缺了"。OpenCode `provider/auth.ts` 集中管理。**方向：** `core.provider.ProviderAuth` 单点，暴露 `authStatus(providerId): Present | Missing | Invalid`，container 按 status 决定注册；UI 能显示具体缺什么。Rubric §5.2。

- **tool-input-default-projectid-from-context** — `session-project-binding` 刚落地（decision 2026-04-21-session-project-binding.md）承诺下一轮按 context 默认 `projectId`。**方向：** 挑 3 个最常用的 tool（`project_query` / `add_clip` / `create_project_from_template`），把 input `projectId: String` 改为可选（null → 从 `ToolContext.currentProjectId` 取），JSON schema 对应 optional，helpText 说清楚 "omit to use current session project"。Rubric §5.4。

## P2 — 记债/观望

- **promote-find-tools-to-project-query** — `find_pinned_clips` / `find_unreferenced_assets` 两个 tool 可以折进 `project_query` 的新 filter（`onlyPinned`, `onlyReferenced=false`）。`find_stale_clips` **不动** — DAG 推导逻辑不是 pure projection。**方向：** 给 `project_query(select=timeline_clips)` 加 `onlyPinned` filter、`select=assets` 加 `onlyReferenced` filter；删 2 个 find tool。Rubric §5.2。

- **session-status-events-on-bus** — OpenCode 有 `session/status.ts` 把 session 状态变化发到 bus。Talevia 只在 Agent loop 内用局部变量追状态，UI 拿不到 "currently generating" / "awaiting tool" / "compacting" 这类信号（只能轮询 messages）。**方向：** 加 `SessionStatusEvent` 子类（BusEvent），状态转移时发到 `EventBus`。和 `agent-run-state-machine` 是配对关系——P0 做完那条后再做这个才有意义。Rubric §5.4。

- **message-v2-schema-versioning** — `Message` / `Part` 当前没有 `schemaVersion` 字段。OpenCode `session/message-v2.ts` 是显式 v2 迁移产物。未来任何字段重构（譬如 Part 的 `MediaAttachment` shape 变化）会踩 §3a.7 序列化向前兼容。**方向：** 加 `schemaVersion: Int = 1` 到 Message + Part，decode 时 detect 版本 → route 到对应 migrator。现在不迁移，只为未来准备。Rubric 外 / §3a.7。

- **rate-limit-aigc-per-session** — 防止长跑 session 失控消耗 AIGC 额度。当前无上限。**方向：** 先记债：加 `SessionRateLimits` 占位类 + `rate_limit_aigc-per-session-recorded.md` 触发条件（cost/session 超 $X、每分钟 >Y calls）。暂不实现。Rubric 外 / 操作债务。

- **export-variant-deterministic-hash** — 同一 Project + 同 output profile 两次 export 是否 bit-identical？ffmpeg 默认非 deterministic（encoder order、timestamps）。`RenderCache` 假设一致性；如果实际不 bit-identical，cache 命中但产物不完全一样，可能破坏 regression 测试。**方向：** 写一个测试：同项目 export 两次，对比 SHA256。不一致就加 `-fflags +bitexact` 到 ffmpeg 命令、文档化哪些 codec option 必须固定。Rubric §5.2 / §5.3。

- **integration-test-real-provider-smoke** — 当前 provider 测试全 mock，真 API 没 smoke test。Anthropic SSE 格式变动 / OpenAI retire 旧 model 时测试察觉不到。**方向：** 加 `@EnabledIfEnvironmentVariable("ANTHROPIC_API_KEY")` 的一轮端到端 smoke test（创建 session、发一句话、断言返回含 text + stop reason），CI 不跑，本地可选。Rubric 外。
