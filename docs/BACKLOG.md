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

- **export-incremental-render** — CLAUDE.md "Known incomplete" 首条：`ExportTool` memoize 仅到 whole-timeline 层；长 project 只改一个 clip 仍然重新 render 全 timeline（且 `docs/decisions/2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md` 明确列为延期）。**方向：** 扩 `ExportTool` memoization key 到 per-clip 级别，利用 `clipRenderCache` 做 "render one stale clip + reuse the rest"；决策阶段定义 key 成分（clip spec hash × source binding hash × render profile hash）。**前置：** `debt-add-benchmark-export-tool` baseline 数字写入 decision，用来证明优化有效 + 无退化。Rubric §5.7。 · skipped 2026-04-23: 复杂度 × 风险超出单 cycle scope — 需要拆 (1) per-clip cache-key 设计 decision；(2) ExportTool memoization 扩展；(3) 跨引擎 cache-invalidation 反直觉边界测试。benchmark 前置已满足（cycle 18 baseline 0.913ms/op）但实施本身仍是多 cycle 工程。下次 repopulate 按 3-split 重新排。

## P2 — 记债 / 观望

- **bundle-mobile-document-picker** — Android / iOS 当前限制于 app sandbox 内的 bundle (`<filesDir>/projects/` / `Documents/projects/`)。用户没法从 SAF / Files.app 选一个外部 bundle 打开。**方向：** Android 接 `Storage Access Framework` (`Intent.ACTION_OPEN_DOCUMENT_TREE`)，iOS 接 `UIDocumentPickerViewController`，结果 URI / NSURL 通过 platform-specific resolver 转成 Okio Path 喂给 `FileProjectStore.openAt`。**触发条件：** CLAUDE.md 平台优先级窗口 mobile 从"不退化底线"升级为主动开发。Rubric §5.4 / mobile。
- **bundle-talevia-json-split** — 当前 `talevia.json` 把 timeline + assets + source DAG + lockfile + snapshots 全装一个文件，单个 mutation 的 git diff 涨几百行；snapshot 多了文件可能涨到 MB 级。**方向：** 拆 `assets.json` / `timeline.json` / `lockfile.json` / `snapshots/<id>.json` 子文件；envelope `talevia.json` 只留 schemaVersion + 元数据 + 子文件清单。**触发条件：** 出现实际用户项目超限 diff 噪声 / snapshot 文件 ≥ 1 MB 的报告。Rubric §3a-3。
- **recents-registry-list-summaries-index** — `RecentsRegistry.listSummaries()` 当前每次扫所有 entry + 解码每个 `talevia.json` envelope，N≥几百时变慢。**方向：** registry 自身缓存 `(title, updatedAtEpochMs)`，envelope 写时同步 registry；`listSummaries` 直接读 registry 不再扫 bundle。**触发条件：** 有实测 profiling 数据支持 N≥几百时 `listSummaries` 成为瓶颈。Rubric §5.3。
- **debt-bundle-fs-testkit-copy-recursive** — Okio 的 `FakeFileSystem` 没有递归 copy 原语；`BundleCrossMachineExportSmokeTest` 在测试里手写了 `copyDirectoryRecursive(fs, src, dst)`（12 行）。**方向：** 第二个 bundle-level 测试需要同一个 walker 时，抽到 `ProjectStoreTestKit` 或 `BundleFsTestKit`；别提前做（don't pre-abstract on N=1）。**触发条件：** 出现第二个 caller。Rubric 外 / 顺手记录。
- **debt-register-tool-script** — 加一个新 tool 要同步改 5 个 `AppContainer`（CLI / Desktop / Server / Android / iOS），每次 import 行手插入位置错了就挨 ktlint 一顿。当前 repopulate 周期（cycles 12-15）都是 split/refactor，没新 tool 注册；指标暂时冷却，继续观察。**方向：** 脚本 `/register-tool <ToolName> <ctorArgs>` 生成 10 行 diff 跨 5 文件 + 自动跑 `ktlintFormat`。**触发条件：** 连续 10 个新-tool cycle 都吃这份税，或最近 15 cycle 中 ≥ 60% 在注册新 tool。Rubric 外 / 顺手记录。
- **debt-unified-dispatcher-select-plugin-shape** — 每次给 `project_query` / `session_query` / `source_query` 加一个 select 要改 7-8 个协调站点（SELECT_* 常量 + ALL_SELECTS + Input 字段 + helpText + JSON Schema + `rejectIncompatibleFilters` 矩阵 + execute 分派 + sibling 文件）；reject 矩阵是 `O(n_selects × n_filters)` 成本。**方向：** 一个 select 一个文件的 "select plugin" shape（例：`@AppliesTo(SELECT_FOO)` 注解驱动 filter 适用性），把 7-8 站点收敛为 1 站点。**触发条件：** 任一 dispatcher 的 select 数达到 20，或 `rejectIncompatibleFilters` 矩阵达到 30+ 规则。当前：project 14 / session 15 / source 5 / provider 2。Rubric 外 / 顺手记录。
- **debt-desktop-missing-asset-banner** — cycle-3 落地了 CLI 侧的 `BusEvent.AssetsMissing` 警告（见 `2026-04-23-debt-export-missing-asset-warning.md`），但"Desktop 在 SnapshotPanel / ExportPanel 旁边加 banner" 仍未落地。**方向：** Desktop AppContainer 订阅同一个 bus 事件；SnapshotPanel / ExportPanel 附近加一个 warning banner，内容同 CLI 的 notice（count + 最多 5 条路径 + 溢出尾注）。**触发条件：** Desktop 达到 CLI parity 线后（CLAUDE.md 平台优先级）。Rubric 外 / 顺手记录。
- **debt-consolidate-video-add-remove-verbs-tracks** — 同 cycle-19 决策 `2026-04-23-debt-consolidate-video-add-remove-verbs.md` 落地的 transition 半部分，track 半部分尚未做：AddTrack + RemoveTracks 仍是两个独立 `*Tool.kt`。**方向：** 沿同一模式建 `TrackActionTool(action=add|remove)`，-1 tool。测试：`AddTrackToolTest.kt` + `RemoveTrackToolTest.kt` 合并为 `TrackActionToolTest.kt`。Rubric 外 / 顺手记录。
- **debt-apply-lut-remove-pad** — `ApplyLutTool` 无 `RemoveLutTool` 配对；用户给 clip 上了 LUT 之后无法单独撤，只能 `filter_action(action=remove, filterName=lut)` — 但这样不会清理 ApplyLut 附带写入的 `clip.sourceBinding` (style_bible cascade)。**方向：** 先决定 `RemoveLutTool` 是否值得造（style_bible 移除语义不显然 —— clip 如果 bind 到多个 style_bibles 怎么处理？），或者把 `filter_action` 的 remove 路径扩展识别 lut 并清理 sourceBinding。Rubric 外 / 顺手记录。
- **debt-subtitle-add-remove-pad** — `AddSubtitlesTool` 创建 `Clip.Text` subtitle clips 但无 `RemoveSubtitlesTool` 配对；清除只能走通用的 `remove_clips`。**方向：** 评估是否值得造 `SubtitleActionTool(action=add|remove)`，或者坚持"subtitles 是普通 clip，用 remove_clips 即可"的设计。Rubric 外 / 顺手记录。
- **debt-bound-metrics-histogram-ring-buffer** — 2026-04-23 audit (`debt-audit-unbounded-mutable-collections` decision, finding B1) 发现 `Metrics.histograms` 的内层 `MutableList<Long>` 每个 `observe()` 调用 append 一条，零 eviction；`reset()` 仅测试调用。**方向：** 改 `MutableList<Long>` 为 fixed-capacity ring buffer（例如 1024 obs/histogram），或加"每 N 分钟周期性保留百分位 snapshot 并清空原始观测"的 retention policy。测试覆盖：观测数超 cap 后 `histogramSnapshot()` 的 P50/P95/P99 仍然 sane。Rubric §5.7。
- **debt-bound-agent-run-state-tracker-evict-on-delete** — 2026-04-23 audit (同上，finding B2) 发现 `AgentRunStateTracker._states` + `historyFlowInternal` 两个 MAP 随 session 数单调增长。`SessionActionTool(action=delete)` 的 `deleteSession(sid)` 不通知 tracker 驱逐对应 entry，process 长寿下累积 stale session-state。**方向：** tracker 订阅 `BusEvent.SessionDeleted`（或让 SessionStore 发类似事件），从 `_states` + `historyFlowInternal` 删除对应 sessionId entry。测试：create + fire delete → tracker 的 `states.value[sid]` == null。Rubric §5.7。
