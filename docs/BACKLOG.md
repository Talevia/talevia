# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **aigc-cost-tracking-per-session** — 当前有 `estimate_session_tokens`（Chat token 计数）和 agent.retry 计数器，但没有 AIGC 调用（image / music / upscale / TTS）的花费累计。用户跑了一个 vlog，不知道花了多少 $；老板问"这项目烧了多少 API 费"也答不出。**方向：** 在 `BusEvent.AigcProduced` / 每个 Replicate / Anthropic / OpenAI 工具 dispatch 完成时带出 `cost: MoneyCents?`，metrics 侧聚合 per-session 和 per-project 的 `spend_cents`。通过 `session_query(select=spend)` / `project_query(select=spend)` 可读。Rubric §5.2 / §5.4。

- **debt-consolidate-project-describe-queries** — `core/tool/builtin/project/` 下有 `DescribeClipTool` / `DescribeLockfileEntryTool` / `DescribeProjectTool` 三个近似 drill-down 工具，每个都是「拿 ID，返回结构化细节」。LLM 每轮付 3 份 tool spec。**方向：** 折到 `project_query(select=clip|lockfile_entry|project_metadata, id=...)` 的 drill-down 变体里，删掉三个独立 `Describe*Tool.kt`。参考 `2026-04-21-unify-project-query.md` 的合并模式。Rubric 外 / debt。

- **debt-consolidate-session-describe-queries** — `core/tool/builtin/session/` 下有 `DescribeMessageTool` + `DescribeSessionTool` 两个近似 drill-down 工具。`session_query` 已经有 7 种 select，天然扩展位。**方向：** 添加 `session_query(select=message, messageId=...)` 和 `select=session_metadata, sessionId=...` 两个变体，删两个 `Describe*Tool.kt`。Rubric 外 / debt。

## P1 — 中优，做完 P0 再排

- **per-clip-incremental-render** — CLAUDE.md `Known incomplete` 首条：`ExportTool` 只 memoize 整时间线 export，没有"只重渲 stale 的一段 + 剩下从 cache 拼回"的增量路径。长项目一次小修改依旧全量 re-render。**方向：** 扩展 `RenderCache` 支持 per-clip-segment 级 memo（key 含 clip contentHash + source binding hash + profile）；`ExportTool` 发现 stale clip 集后只 re-ffmpeg 那几段 + concat 从 cache 拼接未变化段。参考 `docs/decisions/2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md` 里记录的方向。Rubric §5.3。

- **ab-compare-models** — §5.2 rubric: "新效果接入成本"。当前不能在一次 invocation 里用同一 prompt / 同一 source 跑两个 model（例如 `sdxl` vs `flux-dev` 对 character-ref 图）做对比。想选优得手动跑两次、手动比较。**方向：** 新增 `compare_aigc_candidates(toolId, input, models: List<String>)` 原语，内部并行 dispatch、返回 `{modelId → assetId}` 映射；产物自动入 lockfile（不 pin），UI 侧可以双帧对比。Rubric §5.2。

- **tts-regen-by-language** — `2026-04-21-generate-project-variant.md` 的 extension point：`fork_project(variantSpec)` 目前不处理 `language` 维度，需要显式重算 TTS。用户做「同一 vlog 的西班牙语版」仍需手动重新 `synthesize_speech` 每个 text clip。**方向：** 给 `fork_project.variantSpec` 加 `language: String?`；当 set 时 iterate timeline 的 text clip，每一个 re-dispatch `synthesize_speech` with target language，绑定到 fork 的新 asset。provider 路径 + lockfile 条目照常走。Rubric §5.2 / §5.5。

- **consistency-propagation-audit** — §5.5 rubric: "这些约束有没有真的传导到 AIGC 调用的 prompt / 参数 / LoRA 里?"。当前 agent 只能通过读源码推断 character_ref 是否真的影响了 shot-1 的 prompt。缺一个"审计"原语。**方向：** `project_query(select=consistency_propagation, sourceNodeId=X)` 返回 X 作为 ancestor 的所有 clip（或 AIGC lockfile entry），附带"prompt 里是否出现了 X 的 body 关键字段（name / description）"的简单命中检查。Rubric §5.5。

- **plan-dry-run-before-execute** — 小白路径 §5.4 + 专家路径共生：agent 接到"做个 30s 毕业 vlog"会连跑 10+ 工具，用户没机会中途介入。**方向：** 新增 `plan_next_actions(goalDescription)` —— agent 产出一个「我打算这么做」的 steps 列表（tool name + 关键输入摘要），返回 JSON，不执行。用户看过后可以 approve 整个 plan 或 edit 某步再 `execute_plan(planId)`。Rubric §5.4。

- **project-export-portable-envelope** — session 有 `export_session` + `SessionEnvelope`；project 没有对应的"全部带走"原语。跨机器 / 跨账号移交项目只能 `fork_project` 到新 store，无法脱离 talevia 实例分享 JSON。**方向：** `export_project(projectId) → ProjectEnvelope` 形的 JSON bundle（project blob + snapshots + lockfile entries + source DAG + 所有引用的 assets 的 id-to-inline-spec 映射，不含字节），带 `FORMAT_VERSION`。`import_project` 反向吃。参考 `2026-04-21-session-export-portable-envelope.md`。Rubric §5.1。

- **adaptive-retry-backoff** — 当前 agent.retry 计数有了分原因的 bus 事件（`bc82cee`），但实际 retry 还是 flat wait（或没 wait）。provider rate-limit / transient 网络错该按 exponential backoff with jitter 递增间隔。**方向：** `core/agent/RetryPolicy` 引入 `BackoffStrategy.ExponentialJitter(base=500ms, factor=2.0, max=30s)`；按 error kind（rate-limit vs network vs server）分 policy。provider chain（见 `provider-auto-fallback`）跑完所有 fallback 前才 escalate。Rubric §5.2。

- **debt-consolidate-provider-queries** — `core/tool/builtin/provider/` 下只有两个工具：`ListProvidersTool` + `ListProviderModelsTool`，本质是"查 provider 注册表"的一层切片。**方向：** 合为 `provider_query(select=providers | models, providerId?=...)`；删两个 List*Tool.kt。Rubric 外 / debt。

- **debt-consolidate-video-add-variants** — `video/AddClipTool` + `AddTrackTool` + `AddTransitionTool` + `AddSubtitlesTool` 四个 tools，每个有独立 Input 形但 共通语义都是"在 timeline 上添加一个东西"。**方向：** 评估是否通过 `add_to_timeline(target="clip"|"track"|"transition"|"subtitles", ...)` 合并；若分支 Input 的差异太大导致 discriminator 不划算，在 decision 里说明并保留四件套。Rubric 外 / debt。

## P2 — 记债 / 观望

- **prompt-trace-for-aigc-calls** — `lockfile` 记录了 inputHash / seed / model version，但 fully-expanded prompt（含 ancestor consistency nodes 的 fold 结果）没落到 lockfile 条目里。用户要 debug「为什么这个 image 没 respect character_ref」时只能人脑复现 prompt。**方向：** `LockfileEntry` 增加 `resolvedPrompt: String?` 字段（optional，默认 null），AIGC 工具 dispatch 时填入最终送给 provider 的 prompt 文本。`describe_lockfile_entry`（或其替代）展示。Rubric §5.4 debug。

- **copy-source-node-across-projects** — `export_source_node` + `import_source_node` 已经存在，但跨 project 复用一个 character_ref / style_bible 要两步手工。**方向：** 新增 `copy_source_node(fromProjectId, sourceNodeId, toProjectId)` 便利 wrapper，或把 `import_source_node` 扩展为从另一个项目拉取（带 `fromProject: String?`）。Rubric §5.1。

- **lockfile-history-explorer** — `project_query(select=lockfile_entries)` 可以列条目，但不能按"asset 产出时间线"或"同一 source node 的多次 generation history"筛。**方向：** 扩展 `lockfile_entries` select 支持 `groupBy="sourceNode"` 或 `sinceEpochMs` 过滤；给 UI 做一个"这个角色被生成过 5 次"的 timeline。Rubric §5.4 debug。

- **cross-project-source-similarity** — §5.1 "跨 project 复用"：当前用户有 10 个 project，想找"之前做过的类似 character_ref"没有原语，只能手动 grep。**方向：** `search_source_nodes` 已有 body 内容 lookup（`2026-04-21-search-source-nodes-body-content-lookup.md`），扩展为 `search_source_nodes(scope="all_projects", kind="character_ref", query="cyberpunk")` 返回 `(projectId, nodeId, score)`。Rubric §5.1。

- **asset-proxy-generation** — `MediaAsset.proxies` 已有 `ProxyPurpose.THUMBNAIL / LOW_RES / AUDIO_WAVEFORM` 的数据形，但没有自动生成 proxy 的 ingestion path。UI 对 4K 视频 asset 要等原片 decode 才能显示缩略图。**方向：** `ImportMediaTool` 成功后异步 dispatch 一个 `generate_proxies` job（ffmpeg -ss 取中点帧 + 缩放）填 `proxies` 列表。Rubric §5.3 性能。

- **debt-consolidate-video-remove-variants** — 配合 P1 的 `debt-consolidate-video-add-variants`：`RemoveClipTool` + `RemoveFilterTool` + `RemoveTrackTool` + `RemoveTransitionTool` 四件套。**方向：** 同 add 的判断，合为 `remove_from_timeline(target=...)` 或保留四件套并在 decision 说明。Rubric 外 / debt。

- **debt-fold-list-project-snapshots** — `ListProjectSnapshotsTool` 是 `project_query` 之外唯一还单独存在的 list_* 工具。**方向：** 加 `project_query(select=snapshots)`，删 `ListProjectSnapshotsTool.kt`。Rubric 外 / debt。
