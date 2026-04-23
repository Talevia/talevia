# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **debt-add-benchmark-agent-loop** — R.6 #4 scan 子 bullet 之 2：agent loop 是 VISION §5.7 的最核心路径，一个无意把 `AgentTurnExecutor` 变成 O(N²) 的 refactor 会 green 通过整个测试套后才被发现。**方向：** 在 `debt-add-benchmark-infra` 落地后，给 `core/agent` 加 "10-turn fake-provider agent-loop" 基线 benchmark（wall-time + alloc）；baseline 数字写进 decision 文件，后续 CI diff 作为劣化信号。**前置：** `debt-add-benchmark-infra` 已落地。Rubric §5.7。
- **debt-add-benchmark-export-tool** — R.6 #4 scan 子 bullet 之 3：`ExportTool.render()` + `FileProjectStore.openAt` 是第二核心路径，直接决定用户感知的"编辑→看成片"时延。**方向：** 在 `debt-add-benchmark-infra` 落地后，给 `platform-impls/video-ffmpeg-jvm` 加 "10-clip timeline export" benchmark（wall-time + peak RSS）；baseline 写进 decision，后续驱动 `export-incremental-render` 能否上线的判断。**前置：** `debt-add-benchmark-infra` 已落地。Rubric §5.7。

## P1 — 中优，做完 P0 再排

- **debt-consolidate-video-add-remove-verbs** — `core/tool/builtin/video/` 31 个工具（最高密度 area，per R.5 #2）；其中 AddTrack + RemoveTrack + AddTransition + RemoveTransition + AddSubtitles 等 Add*/Remove* 动词对长期消耗 tool-spec 预算。**方向：** 合并为 upsert-style `set_<entity>`（用 presence / absence 做 upsert/delete）或保留 Add/Remove 但收敛到参数化一个工具（`<entity>_action(action=add|remove)`）。目标：此 cluster 净删 ≥ 3 个 `*Tool.kt`。净增长 0 也接受。`debt-shrink-tool-spec-surface` 上一轮 skip-tag 要求的"按区域小步迭代"第 1 步。Rubric §5.7。
- **debt-consolidate-video-filter-lut-apply-remove** — 同 area，ApplyFilter + RemoveFilter + ApplyLut（无 RemoveLut）+ AddSubtitles（无 RemoveSubtitle）构成不对称的 apply/remove 对。**方向：** 要么补齐 `RemoveLut` / `RemoveSubtitle` 再合并，要么把 Apply/Remove 收敛到一个 `clip_effect(op=apply|remove, kind=filter|lut|subtitle, …)`。现状的不对称语义让 LLM 每轮都要付 spec 税却只能半边操作。Rubric §5.7。
- **debt-consolidate-project-snapshot-triple** — `core/tool/builtin/project/` 25 工具（第二大 area）；SaveProjectSnapshot + RestoreProjectSnapshot + DeleteProjectSnapshot 是经典 triple。**方向：** 合并为一个 `project_snapshot(action=save|restore|delete, snapshotId?, label?)`，-2 个 `*Tool.kt`。Rubric §5.7。
- **debt-consolidate-project-lockfile-maintenance** — PruneLockfile + GcLockfile + GcClipRenderCache 都是"定期清扫"语义，3 条近似工具。**方向：** 合一为 `project_maintenance(action=prune-lockfile|gc-lockfile|gc-render-cache)` 或更高层 `project_gc(dryRun, aggressive)`，-2 个工具。Rubric §5.7。
- **debt-consolidate-session-lifecycle-verbs** — `core/tool/builtin/session/` 13 工具；Archive + Unarchive + Delete + Rename 是 session CRUD 动词群。**方向：** 合并为 `session_action(sessionId, action=archive|unarchive|delete|rename, newTitle?)`，-3 个工具。Rubric §5.7。
- **export-incremental-render** — CLAUDE.md "Known incomplete" 首条：`ExportTool` memoize 仅到 whole-timeline 层；长 project 只改一个 clip 仍然重新 render 全 timeline（且 `docs/decisions/2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md` 明确列为延期）。**方向：** 扩 `ExportTool` memoization key 到 per-clip 级别，利用 `clipRenderCache` 做 "render one stale clip + reuse the rest"；决策阶段定义 key 成分（clip spec hash × source binding hash × render profile hash）。**前置：** `debt-add-benchmark-export-tool` baseline 数字写入 decision，用来证明优化有效 + 无退化。Rubric §5.7。

## P2 — 记债 / 观望

- **debt-tool-spec-budget-ratchet-step-20k** — `ToolSpecBudgetGateTest`（cycle 6）设定 `CEILING_TOKENS = 27_000` 并记了多步 ratchet plan（27k → 20k → 15k → 10k）。目前 ceiling 仍然是 27k；五条 P1 consolidations 落地后实测 budget 应当下降，触发 ratchet 第一步。**方向：** 在 P1 的 consolidations 全部合并后，把 `CEILING_TOKENS` 收紧到 20k，重新跑 gate 验证仍绿；失败则在 decision 里解释 budget 没降到 20k 的原因。**触发条件：** 此前 5 条 consolidation bullet 全部落地。Rubric §5.7。
- **debt-audit-unbounded-mutable-collections** — R.6 #2 scan：`core/commonMain` 有 88 处 `CounterRegistry` / `AtomicLong` / `AtomicInteger` / `mutableListOf` / `mutableMapOf` 出现点，未逐个证明有上界或周期回收。**方向：** 一次性 audit，产出 `docs/decisions/<date>-unbounded-collections-audit.md` 列出 (a) 哪些已有上界（EventBus CounterRegistry 本身属于 metrics 基础设施，ok） (b) 哪些真的 unbounded 业务字段（按项目寿命累积）；对 (b) 单独开跟进 bullet。本轮只 audit，不改代码。Rubric §5.7 / §5.6。
- **compaction-drop-telemetry** — `Compactor` drop part 时只写 `store.markPartCompacted`，**没有**发 `BusEvent`；CLI / Desktop UI 无法在 compaction 发生时给用户反馈"刚丢掉 N 条旧 tool 输出"。**方向：** `BusEvent.SessionCompacted(sessionId, prunedCount, summaryLength)`；`Compactor.process` 在返回 `Result.Compacted` 前发一次；CLI 的 `EventRouter` 订阅并打印简短 notice。Rubric §5.4。
- **source-consistency-propagation-runtime-test** — `core/domain/source/consistency/` 有 propagation 规则但缺一个端到端 runtime 测试：当一个 character_ref 节点更新 → 依赖它的下游 style / clip 能看到 stale。现状只有单元级 rule 测试，没有 "父改→子 stale" 的集成测试。**方向：** 在 `core/src/jvmTest` 加一个 source-DAG 集成测试：构造 parent + child 节点，改 parent body，assert 下游 stale-clip 报告出现对应 clipId。Rubric §5.5。
- **bundle-mobile-document-picker** — Android / iOS 当前限制于 app sandbox 内的 bundle (`<filesDir>/projects/` / `Documents/projects/`)。用户没法从 SAF / Files.app 选一个外部 bundle 打开。**方向：** Android 接 `Storage Access Framework` (`Intent.ACTION_OPEN_DOCUMENT_TREE`)，iOS 接 `UIDocumentPickerViewController`，结果 URI / NSURL 通过 platform-specific resolver 转成 Okio Path 喂给 `FileProjectStore.openAt`。**触发条件：** CLAUDE.md 平台优先级窗口 mobile 从"不退化底线"升级为主动开发。Rubric §5.4 / mobile。
- **bundle-talevia-json-split** — 当前 `talevia.json` 把 timeline + assets + source DAG + lockfile + snapshots 全装一个文件，单个 mutation 的 git diff 涨几百行；snapshot 多了文件可能涨到 MB 级。**方向：** 拆 `assets.json` / `timeline.json` / `lockfile.json` / `snapshots/<id>.json` 子文件；envelope `talevia.json` 只留 schemaVersion + 元数据 + 子文件清单。**触发条件：** 出现实际用户项目超限 diff 噪声 / snapshot 文件 ≥ 1 MB 的报告。Rubric §3a-3。
- **recents-registry-list-summaries-index** — `RecentsRegistry.listSummaries()` 当前每次扫所有 entry + 解码每个 `talevia.json` envelope，N≥几百时变慢。**方向：** registry 自身缓存 `(title, updatedAtEpochMs)`，envelope 写时同步 registry；`listSummaries` 直接读 registry 不再扫 bundle。**触发条件：** 有实测 profiling 数据支持 N≥几百时 `listSummaries` 成为瓶颈。Rubric §5.3。
- **debt-bundle-fs-testkit-copy-recursive** — Okio 的 `FakeFileSystem` 没有递归 copy 原语；`BundleCrossMachineExportSmokeTest` 在测试里手写了 `copyDirectoryRecursive(fs, src, dst)`（12 行）。**方向：** 第二个 bundle-level 测试需要同一个 walker 时，抽到 `ProjectStoreTestKit` 或 `BundleFsTestKit`；别提前做（don't pre-abstract on N=1）。**触发条件：** 出现第二个 caller。Rubric 外 / 顺手记录。
- **debt-register-tool-script** — 加一个新 tool 要同步改 5 个 `AppContainer`（CLI / Desktop / Server / Android / iOS），每次 import 行手插入位置错了就挨 ktlint 一顿。当前 repopulate 周期（cycles 12-15）都是 split/refactor，没新 tool 注册；指标暂时冷却，继续观察。**方向：** 脚本 `/register-tool <ToolName> <ctorArgs>` 生成 10 行 diff 跨 5 文件 + 自动跑 `ktlintFormat`。**触发条件：** 连续 10 个新-tool cycle 都吃这份税，或最近 15 cycle 中 ≥ 60% 在注册新 tool。Rubric 外 / 顺手记录。
- **debt-unified-dispatcher-select-plugin-shape** — 每次给 `project_query` / `session_query` / `source_query` 加一个 select 要改 7-8 个协调站点（SELECT_* 常量 + ALL_SELECTS + Input 字段 + helpText + JSON Schema + `rejectIncompatibleFilters` 矩阵 + execute 分派 + sibling 文件）；reject 矩阵是 `O(n_selects × n_filters)` 成本。**方向：** 一个 select 一个文件的 "select plugin" shape（例：`@AppliesTo(SELECT_FOO)` 注解驱动 filter 适用性），把 7-8 站点收敛为 1 站点。**触发条件：** 任一 dispatcher 的 select 数达到 20，或 `rejectIncompatibleFilters` 矩阵达到 30+ 规则。当前：project 14 / session 15 / source 5 / provider 2。Rubric 外 / 顺手记录。
- **debt-desktop-missing-asset-banner** — cycle-3 落地了 CLI 侧的 `BusEvent.AssetsMissing` 警告（见 `2026-04-23-debt-export-missing-asset-warning.md`），但"Desktop 在 SnapshotPanel / ExportPanel 旁边加 banner" 仍未落地。**方向：** Desktop AppContainer 订阅同一个 bus 事件；SnapshotPanel / ExportPanel 附近加一个 warning banner，内容同 CLI 的 notice（count + 最多 5 条路径 + 溢出尾注）。**触发条件：** Desktop 达到 CLI parity 线后（CLAUDE.md 平台优先级）。Rubric 外 / 顺手记录。
