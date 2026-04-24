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

- **m2-provider-second-impl** — M2 criterion 2："provider 多元"。`ImageGenEngine` / `VideoGenEngine` / `MusicGenEngine` / `TtsEngine` 除 Replicate + test stub 外，产品路径下 0 个第二 impl。**方向：** 任一 engine 长出第二个非 stub 生产 impl（如 `OpenAiImageGenEngine` via DALL-E、`ElevenLabsTtsEngine`）。需要专有 API key + 产品抉择，待用户决定。Rubric §5.7 / §5.2。Milestone §M2. · skipped 2026-04-24: 需专有 API key + 产品抉择（vendor 选型），待用户决定.
- **recents-registry-list-summaries-index** — `RecentsRegistry.listSummaries()` 每次调用扫所有 entry + decode 每个 envelope。**方向：** registry self-cache；envelope 写同步 cache；`listSummaries` 读 cache。**触发条件：** profiling 显示 ≥ 50 ms on real bundles。Rubric §5.3 / §5.7。Milestone §later.
- **bundle-mobile-document-picker** — Android / iOS 限于 app-sandbox bundle。**方向：** Android 接 `ACTION_OPEN_DOCUMENT_TREE`；iOS 接 `UIDocumentPickerViewController`。**触发条件：** 平台优先级窗口把 mobile 从"不退化"推到主动开发。Rubric §5.4 / mobile。Milestone §later.
- **debt-todo-fixme-net-growth** — R.5 #6 scan：32 个 `TODO|FIXME|HACK|XXX` 出现点在 `core/commonMain`。没记上次快照 → 本轮作为 baseline。**方向：** 取 `grep -rnE 'TODO|FIXME|HACK|XXX' core/src/commonMain/kotlin` 全量落到 commit body 作为 baseline；之后每次 repopulate 比 delta > 0 → append cleanup bullet，否则保持本 bullet observational。**触发条件：** 下次 repopulate 扫到 > 32。Rubric §5.6。Milestone §later.
- **debt-lockfile-entries-index** — M2 exit summary §3.1 follow-up #5：`project.lockfile` cache hit 检测走 in-memory `byInputHash` map，但跨 session 打开同一 project 会 O(N) JSON decode 整个 `entries` list。mature project（> 500 entries）冷开 latency 无 profile 数据。**方向：** 先加 `LockfileEntriesBenchmark` 测实际成本；确有显著开销再考虑 `lockfile.jsonl` 分文件 / 增量加载。**触发条件：** benchmark 显示 > 50 ms cold open。Rubric §5.7。Milestone §later.
- **desktop-aigc-job-progress-panel** — Desktop app 对 `BusEvent.AigcCostRecorded` / `AigcCacheProbe` 无 UI surface。CLI 有 `EventRouter` 行流，Desktop 只在 timeline 上看 clip 变绿。**方向：** Compose panel 订阅 `bus.subscribe<AigcCostRecorded>()` + `subscribe<AigcCacheProbe>()`，渲染一列 "job N | provider X | cache hit/miss | cost ¢". **触发条件：** Desktop 达 CLI parity 的平台优先级窗口开。Rubric §5.4 / desktop。Milestone §later.

## P2 — 记债 / 观望

- **debt-aigc-tool-consolidation** — `aigc/` 下 9 个 tool（4 generator + replay + upscale + compare + synth + budget guard），前 4 个是 generator 同构。**方向：** 评估 `AigcActionTool(kind=image|video|music|speech)` 折叠可行性。**触发条件：** aigc/ 再长出一个 generator 或 per-turn tool spec 破 25k。Rubric §5.6 / §5.7。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。**方向：** 拆 sub-files。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **debt-unified-dispatcher-select-plugin-shape** — 每个新 select 触 7–8 个协调点。Reject-matrix `O(n_selects × n_filters)`。**方向：** "select plugin" 形状。**触发条件：** 任一 dispatcher ≥ 20 selects 或 rules ≥ 30。现状：session 18 / project 15 / source 7 / provider 3；session 逼近 20 阈值。Rubric §5.6 / §5.7。Milestone §later.
- **debt-desktop-missing-asset-banner** — Desktop 未视觉化 `BusEvent.AssetsMissing`。**方向：** AppContainer 订阅 bus event + panel 渲 warning banner。**触发条件：** Desktop 达 CLI parity。Rubric §5.4 / desktop。Milestone §later.
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy；`BundleCrossMachineExportSmokeTest` inline 12 行 helper。**方向：** 第二个 caller 出现时 extract 到 `BundleFsTestKit`。**触发条件：** 第二 caller 出现。Rubric §5.6。Milestone §later.
- **source-node-diff-restore-composite-tool** — P0 restore tool 已落；用户会想 per-field merge-restore。**方向：** `merge_source_node_body_from_history(nodeId, revisionIndex, fieldPaths)`。**触发条件：** operator 要 per-field granularity。Rubric §5.5。Milestone §later.
- **cli-metrics-slash-command** — `CounterRegistry` 暴露 counters 只通过 server `/metrics`。**方向：** CLI `/metrics` slash-command。**触发条件：** operator 反馈。Rubric §5.4 / §5.6。Milestone §later.
- **debt-render-cache-map-lookup** — `RenderCache.findByFingerprint` uses the same O(N) `entries.lastOrNull { … }` scan that `ClipRenderCache` just shed. Identical mechanical fix applies (add `@Transient byFingerprint` via `associateBy`). Bucketed P2 rather than folded into the ClipRenderCache cycle to keep scope tight and let the Lockfile + ClipRenderCache precedents settle first. **方向：** 镜像 `ClipRenderCache.byFingerprint` 的 Transient-map 模式。Rubric 外 / 顺手记录。Milestone §later。
