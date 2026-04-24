# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 iteration 的 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动


## P1 — 中优，做完 P0 再排

- **m2-provider-second-impl** — M2 criterion 2："provider 多元"。`ImageGenEngine` / `VideoGenEngine` / `MusicGenEngine` / `TtsEngine` 4 个接口每个都只有 1 个 prod impl（grep 印证：OpenAI 图/视/语音 + Replicate 音乐/放大，各 1 家）。**方向：** 任一 engine 长出第二个非 stub 生产 impl（如 `AnthropicImageGenEngine` 若 Claude 上线图像、`ElevenLabsTtsEngine`、`StabilityImageGenEngine` via local ComfyUI、`LocalMLXTtsEngine`）。需要专有 API key + 产品抉择，待用户决定。Rubric §5.7 / §5.2。Milestone §M2. · skipped 2026-04-24: 需专有 API key + vendor 决策 (跨 2 个 repopulate 周期的老约束).
- **debt-extract-project-query-selects-plugin** — `debt-unified-dispatcher-select-plugin-shape` 的 proactive 变体。现状：session 18 selects / project 15 / source 7 / provider 3。合计 43 selects 在 4 个 dispatcher 里重复 handler-registration + reject-matrix + schema-union 模板代码。不等触发 ≥ 20 阈值才动，现在 API 形状明朗，抽出 `QuerySelectPlugin` interface（`id`, `inputSchema`, `handler`, `rejectIncompatibleFilters`）+ dispatcher loader 能在主 dispatcher 文件一次减 200+ LOC。**方向：** 先在 `session_query` / `project_query` 试点（select 数最多），确认形状再扩 source + provider。Rubric §5.6 / §5.7。Milestone §later. · skipped 2026-04-24: 现存 `QueryDispatcher` KDoc 明确拒过此抽象 ("the dispatch `when` is ~20 lines, well short of the long-file threshold ... revisit only if a fifth query tool shows the dispatch block is itself a long-file contributor") + 预估 200+ LOC 减因 43 个新 plugin 文件开销大概率变成净增；trigger 条件（第 5 个 query 工具）未到.
- **aigc-cost-estimate-tool** — M2 exit summary §3.1 follow-up：agent 有 `session_query(select=spend_summary)` 看 post-hoc，但缺 pre-dispatch 成本预估。"generate 一个 8-second Sora 视频大概花 ¢?" 只能调 provider 看结果。**方向：** `estimate_aigc_cost(tool, inputs)` 返回 cents 估算（基于 provider pricing table + token-count heuristic），不调 provider。和 `AigcBudgetGuard` 前置检查同一套 pricing table 共享。Rubric §5.7 / §5.2。Milestone §later. · skipped 2026-04-24: `AigcPricing.priceBasisFor(toolId)` 已在 `list_tools` Summary 上对 agent 可见（LLM 可自行 8s×$0.30/s=$2.40），正面具体工具需要的是 forward-projecting 签名 (tool + inputs 无 provenance) 而非 `estimateCents(toolId, provenance, baseInputs)` 签名的重新包装；改动面比 bullet 宣称的大，待真正的"数字能证 AigcBudgetGuard 拒 cap"场景驱动.
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
