# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **debt-fold-set-source-node-body-helpers** — `SetBrandPaletteTool` / `SetCharacterRefTool` / `SetStyleBibleTool` 仍作为 3 个独立 tool id 存在，尽管 kind-agnostic `UpdateSourceNodeBody` + `AddSourceNode` 已经覆盖全部写路径。硬编码 genre 名称到 tool 注册表（§3a-5）同时持续在每次 turn 里烧 LLM tool-spec token。**方向：** 废弃 3 个 Set* 工具（改为 deprecated stub 或直接删），把文档 / decision 里的用例切到 kind-agnostic upsert；测试迁移到 `AddSourceNode` + `UpdateSourceNodeBody`。Rubric §5.5 + §3a-1/§3a-5。
- **parallel-tool-dispatch** — `AgentTurnExecutor` 按序执行同一轮里的多个 `tool_use` 块。provider 返回并行 tool_calls 时本该并发（典型场景：同时读 project + 读 session、两个独立 aigc 生成）。现状把两个独立调用排成 RTT 累加。**方向：** 在 turn executor 里把一组无依赖的 tool call 包成 `coroutineScope { map { async { dispatch(it) } }.awaitAll() }`；permission 仍按调用顺序串行逐条询问，避免并发 prompt 冲突。Rubric §5.2 / §5.4。
- **provider-retry-on-transient** — 单次 429 / 5xx / 网络抖动立即冒泡为 tool error，agent 无回退策略。`AnthropicProvider` / `OpenAiProvider` 都没看到重试 wrap。**方向：** 在 provider call site 加指数退避重试（3 次，2s/4s/8s，仅对 429/5xx/IO 异常），通过 `BusEvent.ProviderRetry` 暴露尝试次数；不改 `LlmEvent` 协议语义。Rubric §5.4。
- **tool-spec-token-budget-metric** — agent 看不到自己这一轮为注册工具的 spec 付出了多少 token。随着 tool count 爬到 105+，这是最大的 silent cost。**方向：** 给 `session_query` 加 `select=tool_spec_budget`，返回 `(toolCount, estimatedSpecTokens, perToolBreakdown[top5])`。估算用现有 JSON Schema 序列化长度 / 4。Rubric §5.4 + §3a-10。

## P1 — 中优，做完 P0 再排

- **debt-resplit-project-query-tool** — `ProjectQueryTool.kt` 在 `6e7bd8f` 首次拆分至 540 行后又长回 547 行，说明上次拆分不彻底 / 漏了一个肥 select 分支。**方向：** 扫一遍各 `handle<Select>` 分支，把任何 > 40 行的单独挪到 `project/query/<select>.kt` 同级；主文件保留 dispatch + schema。Rubric §3a-3。
- **debt-resplit-session-query-tool** — `SessionQueryTool.kt` 534 行，同类症状；同一轮 repopulate 一并 split。**方向：** 和 ProjectQueryTool 同一个套路。Rubric §3a-3。
- **debt-trim-openai-provider** — `OpenAiProvider.kt` 424 行，近期高频改动（`seed` 从 image / Sora 请求体移除）显示 request-body builder 是 churn hotspot。**方向：** 把 `buildResponsesBody` / image-gen body / Sora body 三个 request-body builder 抽到 `provider/openai/body/*.kt` 独立文件；主文件只保留 SSE streaming + event mapping。Rubric §3a-3。
- **debt-trim-agent-kt** — `Agent.kt` 459 行，`agent-interrupt-via-bus` 落地后增量又塞回主类。**方向：** 把 `bus.subscribe().filterIsInstance<BusEvent.SessionCancelRequested>()` 侧通道抽到 `agent/AgentBusCancelWatcher.kt`，由 `Agent.start()` 注入。主类保留 session loop。Rubric §3a-3。
- **partial-tool-input-streaming** — provider 流式发来 tool JSON delta，core 现在闭门拼到 complete 再 dispatch；UI 只看到「工具在想…」几秒钟没有 feedback。**方向：** `LlmEvent` 新增 `ToolInputDelta(toolCallId, jsonFragment)`；provider 层直通；Part 保留 accumulated JSON 供 dispatch。不改 ToolResult 协议。Rubric §5.4。
- **tool-output-token-estimate** — `ToolResult` 没带 token 估算，`Compactor` 选哪一条来压缩只能按 part 顺序。**方向：** 在 `ToolResult` 加 `estimatedTokens: Int?`（/4 字节粗估），compaction 按大小降序取 top-N 压掉。Rubric §5.4。
- **auto-revert-on-failed-export** — `ExportTool` 中途 ffmpeg 崩溃 / 被 cancel 时 mezzanine 文件在磁盘留存，没人清。`deleteMezzanine` 存在但只在 gc 路径调用。**方向：** 在 `ExportTool.execute` try-finally 里对失败路径调 `engine.deleteMezzanine(tmpPath)`；正常成功路径不动。Rubric §5.2。
- **aigc-cache-hit-ratio-metric** — `AigcPipeline.findCached` 命中 / 未命中对外不可见；用户 / agent 都无法证明「seed 锁住、cache 真的 hit 了」。**方向：** 每次 findCached 命中 / 未命中时 publish `BusEvent.AigcCacheProbe(toolId, hit)` 并让 `CounterRegistry` 落到 `/metrics` 的 `talevia_aigc_cache_hits_total` / `talevia_aigc_cache_misses_total`。Rubric §5.3 / Observability。
- **session-tool-disabled-registry** — agent 目前没法在一个 session 内部把某个工具暂时禁掉（比如「别再用 generate_video 了，太贵」）。**方向：** `SessionStore` 加 `disabledToolIds: Set<String>`，`AgentTurnExecutor` 过滤；新增 `disable_tool` / `enable_tool` 两个 session 域工具或把它接到 `session_query` 的 mutation path。Rubric §5.4。
- **cli-resume-last-session** — `apps/cli` 退出再启动就是全新 session，上下文丢光。`TaleviaDbFactory` 已经把 session 存 DB，但 CLI Repl 启动时不会恢复。**方向：** 给 CLI 加 `--resume` / `--resume=<id>` 旗；无参时从 `SqlDelightSessionStore` 取最近一次活跃 session。Rubric §5.4 / CLI。

## P2 — 记债 / 观望

- **debt-split-fork-project-tool** — `ForkProjectTool.kt` 497 行，逼近 500 行阈值，`ForkProject` + 子 forks 继续加功能会越过。**方向：** 先把 duplicate-asset / duplicate-sessions / rename path 的子逻辑抽到 `project/fork/*.kt` 同级文件，预防性拆分。Rubric §3a-3。
- **debt-registered-tools-contract-test** — 没有 CI 检查能发现「新加了一个 Tool.kt 却忘了在 CliContainer/DesktopContainer/… 注册」。曾经漏过一次（`cb551be` fix）。**方向：** 新增 `RegisteredToolsContractTest`：扫 `core/tool/builtin/**/*Tool.kt`，assert 每个类名都在至少一个 `AppContainer` 的注册 list 里出现。Rubric §3a-8。
- **source-query-by-parent-id** — `SourceQueryTool` 能按 id / kind / search 查，但无法「列出直接 / 传递子节点」。对 propagate / fold 相关 reasoning 价值大。**方向：** 新增 `select=descendants` / `select=ancestors`，参数 `root: SourceId, depth: Int = -1`。复用 `deepContentHashOf` 的 DAG 遍历逻辑。Rubric §5.5。
- **timeline-diff-tool** — `DiffProjectsTool` 给全项目级别 diff，但没法单独看两个 snapshot 之间 Timeline 的变动。**方向：** `project_query(select=timeline_diff, fromSnapshot=..., toSnapshot=...)` 返回 clips/tracks 增删改列表。Rubric §5.1 / §5.4。
- **gemini-provider-stub** — 只有 Anthropic / OpenAI；provider abstraction 还没被第三个实现压过测试。**方向：** 给 `core/provider/gemini/` 打一个 skeleton，映射 Gemini streaming 事件到 `LlmEvent`，装配点 gated by `GOOGLE_GENAI_API_KEY`。可以只覆盖 text 一轮对话；tool-use 留给后续。Rubric §5.2 + provider 中立红线。
- **tts-provider-fallback-chain** — `SynthesizeSpeechTool` 目前一个 provider 下去，provider 挂就 tool error。**方向：** 在 Core 容器里接受多个 engine 按优先级注入，`AigcPipeline` 遇到非 cache-hit 失败时切到下一个引擎；lockfile 记录最终用的 provider。Rubric §5.2。

