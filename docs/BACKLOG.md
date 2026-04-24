# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 iteration 的 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **debt-add-benchmark-export-tool** — R.6 #4 scan：`ExportTool` 是 VISION §3.2 "可复用渲染" 的核心路径，目前零 wall-time regression guard。Agent loop / lockfile lookup / lockfile cold-decode 都有 benchmark 了（cycle `c5daba05` + `6a47516d`）。**方向：** 新增 `ExportToolBenchmark` 走 synthetic 5-clip timeline + fake VideoEngine（或真 ffmpeg 如果装了），测 wall-time 从 `ExportTool.execute` 入口到 snapshot 写入的端到端耗时。同 bench 基础设施，soft budget 初版仅 warning。Rubric §5.7。Milestone §later.
- **debt-split-agent-kt-round-2** — `Agent.kt` 502 LOC。先前一轮已拆出 `AgentTurnExecutor`（`decisions/2026-04-21-debt-split-agent-kt.md`）；当前主类承载 run() + runLoop() + retry / compaction / cancel / titler 协调逻辑，继续膨胀会把 test-helper 耐受度磨低。**方向：** 把 retry-backoff 子状态机（当前散在 runLoop）抽 `RetryCoordinator`，compaction 触发抽 `CompactionGate`；dispatcher 保留在主类。Rubric §5.6。Milestone §later.

## P1 — 中优，做完 P0 再排

- **m2-provider-second-impl** — M2 criterion 2："provider 多元"。`ImageGenEngine` / `VideoGenEngine` / `MusicGenEngine` / `TtsEngine` 4 个接口每个都只有 1 个 prod impl（grep 印证：OpenAI 图/视/语音 + Replicate 音乐/放大，各 1 家）。**方向：** 任一 engine 长出第二个非 stub 生产 impl（如 `AnthropicImageGenEngine` 若 Claude 上线图像、`ElevenLabsTtsEngine`、`StabilityImageGenEngine` via local ComfyUI、`LocalMLXTtsEngine`）。需要专有 API key + 产品抉择，待用户决定。Rubric §5.7 / §5.2。Milestone §M2.
- **recents-registry-createdat-embed** — `FileProjectStore.listSummaries` 每次调用会 `decodeStored(fs, path, json)` 读整个 `talevia.json` 只为拿 `createdAtEpochMs`（`title` / `lastOpenedAtEpochMs` registry 已有）。相比先前 `recents-registry-list-summaries-index` bullet 要求的 "envelope 同步缓存"，具体 fix 只是 schema 增字段而已，不需要 profile 先行。**方向：** `RecentsEntry` 加 `createdAtEpochMs: Long = 0L`（default 向后兼容 recents.json 旧 schema），`upsert` / `openAt` / `createAt` 写入时带 stored.createdAtEpochMs，`listSummaries` / `summary` 优先读 registry，field == 0L fallback 到 bundle decode。Rubric §5.3 / §5.7。Milestone §later.
- **debt-add-benchmark-file-project-store-openat** — R.6 #4：`FileProjectStore.openAt` 是跨 session 冷启动的关键路径（ProjectStore.openAt → decodeStored → recents.upsert），零 wall-time regression guard。**方向：** 新增 `FileProjectStoreOpenAtBenchmark` 造 500-asset / 1000-asset 合成 bundle, 测冷打开耗时 + 每次 openAt 的第 N 次（JIT hot path）耗时。基础设施与既有 `LockfileEntriesBenchmark` 同形。Rubric §5.7。Milestone §later.
- **m3-greenfield-onboarding-e2e-test** — 上 cycle 22 落地 `PromptOnboardingLane` (`6bb68cd2`) 但行为断言（"empty project → agent 被 prompt 引导先 scaffold style_bible 再 generate"）只在单元层测了 lane 注入机制。**方向：** 加 `GreenfieldOnboardingE2ETest` 在 `core/src/jvmTest`：`FakeProvider` 脚本给一个空 project + 模糊意图 turn，断言 LLM 第 1 个 tool 分发是 `source_node_action(action="add")` 而不是 `generate_image`。覆盖 §4 小白路径的"第一个动作顺序"不变量。Rubric §5.4 / §4。Milestone §M3.
- **debt-extract-project-query-selects-plugin** — `debt-unified-dispatcher-select-plugin-shape` 的 proactive 变体。现状：session 18 selects / project 15 / source 7 / provider 3。合计 43 selects 在 4 个 dispatcher 里重复 handler-registration + reject-matrix + schema-union 模板代码。不等触发 ≥ 20 阈值才动，现在 API 形状明朗，抽出 `QuerySelectPlugin` interface（`id`, `inputSchema`, `handler`, `rejectIncompatibleFilters`）+ dispatcher loader 能在主 dispatcher 文件一次减 200+ LOC。**方向：** 先在 `session_query` / `project_query` 试点（select 数最多），确认形状再扩 source + provider。Rubric §5.6 / §5.7。Milestone §later.
- **aigc-cost-estimate-tool** — M2 exit summary §3.1 follow-up：agent 有 `session_query(select=spend_summary)` 看 post-hoc，但缺 pre-dispatch 成本预估。"generate 一个 8-second Sora 视频大概花 ¢?" 只能调 provider 看结果。**方向：** `estimate_aigc_cost(tool, inputs)` 返回 cents 估算（基于 provider pricing table + token-count heuristic），不调 provider。和 `AigcBudgetGuard` 前置检查同一套 pricing table 共享。Rubric §5.7 / §5.2。Milestone §later.
- **provider-cold-start-image-video** — `BusEvent.ProviderWarmup` cycle 21 (`6c460a10`) 给 Replicate music/upscale 上了 "Starting / Ready" 两阶段。OpenAI image / video / tts 无等效 — 第一次 session 调 `generate_image` 用户看起来就是卡住。**方向：** `ImageGenEngine` / `VideoGenEngine` / `TtsEngine` method overloads 加 `onWarmup: suspend (Phase) -> Unit` 回调 + OpenAI 实现在 POST 前 emit Starting、first successful stream 后 emit Ready。CLI `EventRouter` 已有 "warming up <provider>…" surface，sessionId + providerId 分流即可。Rubric §5.4 / §5.7。Milestone §later.
- **source-dag-visualise-tool** — Source DAG 目前可 list / describe / find-clips-for-source 但没有 "整图 DOT 导出" 或 "ASCII tree 投影"。调试跨节点 consistency drift 时没好工具。**方向：** `source_query(select=dag_graph, format=dot|ascii)` 返回 Graphviz DOT 或 ASCII tree，节点带 kind / contentHash 短缀，边带 parentId。Rubric §5.1 / §5.5。Milestone §later.

## P2 — 记债 / 观望

- **debt-todo-fixme-baseline-stable** — R.5.6 scan：32 TODO/FIXME/HACK 出现点在 core/commonMain，和前一次 repopulate 基线一致，无增长。**方向：** 继续观察；下次 repopulate > 32 → 升 P1 + 列新增行号。**触发条件：** 下次 repopulate delta > 0。Rubric §5.6。Milestone §later.
- **debt-split-clip-mutate-handlers-preempt** — `ClipMutateHandlers.kt` 490 LOC，恰在 R.5.4 500-LOC 默认阈值下缘。Phase-3 Replace/Fade 已吸收，下一批新 mutate verb 会推过阈值。**方向：** 先记 observational；触发后按 audio-mutate (fade) vs video-mutate (remove/move/split/trim/replace) 轴拆分，handler 文件的 KDoc 已预留这个 axis 注释。**触发条件：** 一个新 clip-mutate verb 的 bullet 被 draft 到 P0/P1。Rubric §5.6。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。**方向：** 拆 sub-files。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy；`BundleCrossMachineExportSmokeTest` inline 12 行 helper。**方向：** 第二个 caller 出现时 extract 到 `BundleFsTestKit`。**触发条件：** 第二 caller 出现。Rubric §5.6。Milestone §later.
- **source-node-diff-restore-composite-tool** — P0 restore tool 已落；用户会想 per-field merge-restore。**方向：** `merge_source_node_body_from_history(nodeId, revisionIndex, fieldPaths)`。**触发条件：** operator 要 per-field granularity。Rubric §5.5。Milestone §later.
- **cli-metrics-slash-command** — `CounterRegistry` 暴露 counters 只通过 server `/metrics`。**方向：** CLI `/metrics` slash-command。**触发条件：** operator 反馈。Rubric §5.4 / §5.6。Milestone §later.
- **desktop-live-render-preview** — Desktop 只在 timeline 上看 clip 变绿 + 静态 thumbnails，没有 timeline 实时 render preview。**方向：** Compose Desktop + Media3 / ffmpeg bridge 驱动一个 playback surface；subscribe `BusEvent.AigcJobProgress` 把 clip-ready 实时替换进 preview。**触发条件：** Desktop 达 CLI parity 的平台优先级窗口开。Rubric §5.4 / desktop。Milestone §later.
- **bundle-mobile-document-picker** — Android / iOS 限于 app-sandbox bundle。**方向：** Android 接 `ACTION_OPEN_DOCUMENT_TREE`；iOS 接 `UIDocumentPickerViewController`。**触发条件：** 平台优先级窗口把 mobile 从"不退化"推到主动开发。Rubric §5.4 / mobile。Milestone §later.
