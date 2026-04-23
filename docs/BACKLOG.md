# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 `docs(decisions)` 那次 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **debt-shrink-tool-spec-surface** — 106 个 `*Tool.kt`（per R.5 #1）；按保守估算每个 ~500 token spec → tool-spec 总预算 ~50k+ token，远超 R.6 "> 20k token → P0" 阈值。LLM 每一次 turn 都付这份 cost，且 `session_query(select=tool_spec_budget)` 已能实时读出数值但没被用作守护。**方向：** 把最相似的 verb-pair / single-purpose tool 收敛为一个 `set_*` / `project_query(select=...)` 参数维度；或引入更细粒度 `ToolApplicability` 剪枝让 LLM 在无关 session 里看不到一整类 tool。目标：实测 `tool_spec_budget` ≤ 20k token。Rubric §5.7。 · skipped 2026-04-23: 单 cycle scope 过大（106 → <40 tool 的 consolidation 要多轮），需要先按 area 拆成若干个 `debt-consolidate-<area>-*` 子 bullet。下一次 repopulate 应拆分此 bullet 为按区域的小步迭代。
- **debt-add-benchmark-core-paths** — `find core platform-impls apps -name '*Benchmark*.kt' -o -name '*Perf*.kt' -o -name '*Latency*.kt'` 返回空；agent loop / `ExportTool.render()` / `AgentTurnExecutor` / `FileProjectStore.openAt` 都是 VISION §5.7 的核心路径，零 wall-time / memory 回归守护。一个无意把 whole-timeline render 变成 O(N²) 的 bug 可以 green 通过整个测试套后才被发现。**方向：** 加 kotlinx-benchmark 风格的 `:core:benchmark` / `:platform-impls:video-ffmpeg-jvm:benchmark` task（与 `jvmTest` 分离），基线覆盖 (a) agent loop 10-turn fake-provider wall-time + alloc (b) 10-clip timeline export wall-time + peak RSS；结果 json 写 `build/reports/benchmarks/` 方便 CI diff。Rubric §5.7。 · skipped 2026-04-23: 单 cycle scope 过大 — 引入 kotlinx-benchmark 插件 + 2 个 benchmark class + baselining + CI diff 需要多轮。下一次 repopulate 应拆为 `debt-add-benchmark-infra` + `debt-add-benchmark-agent-loop` + `debt-add-benchmark-export-tool` 三个子 bullet。

## P1 — 中优，做完 P0 再排

- **debt-split-server-module-kt** — `apps/server/src/main/kotlin/io/talevia/server/ServerModule.kt` 667 行（per R.5 #4）。HTTP route group（agent / sessions / projects / SSE stream）全装一个 `install(Routing) { … }` 块。**方向：** 按路由分组拆 sibling `configureAgentRoutes()` / `configureSessionRoutes()` 等顶级 Ktor module 函数；ServerModule 只保留 `Application.module()` 入口与 feature install 顺序。Rubric §5.6。
- **debt-split-desktop-source-panel** — `apps/desktop/.../SourcePanel.kt` 635 行（per R.5 #4）。Source DAG 的列表 / inspector / 图 view 全在一个 Composable。**方向：** 按语义拆 `SourceNodeList` / `SourceNodeInspector` / `SourceDagGraph`，SourcePanel 顶层只管路由 + 状态 hoist。Rubric §5.6。
- **debt-split-android-media3-video-engine** — `apps/android/.../Media3VideoEngine.kt` 614 行（per R.5 #4）；filters / transitions / subtitles / LUT / overlay 四五类 `Effect` dispatch 全挤在一个类里，三平台对齐扩新 transition 的 cycle 已经多次踩到这个坑。**方向：** 按 Effect 类别拆 sibling（`Media3FilterEffects.kt` / `Media3TransitionEffects.kt` / `Media3SubtitleEffects.kt` / `Media3LutEffect.kt`），engine 保留 render(timeline) 入口 + dispatch 矩阵。Rubric §5.6。
- **debt-split-cli-repl** — `apps/cli/.../repl/Repl.kt` 589 行（per R.5 #4）。输入循环 + 斜杠命令分派 + 生命周期钩子 + SessionBootstrap 耦合在一个类里。**方向：** 抽 `SlashCommandDispatcher` 到 sibling；REPL 循环保留输入读 / 生命周期；SessionBootstrap 已是独立文件无需动。Rubric §5.6。
- **debt-split-desktop-timeline-panel** — `apps/desktop/.../TimelinePanel.kt` 584 行（per R.5 #4）；clip drag / track layout / playhead 全塞一个 Composable。**方向：** 抽 `TrackRow` / `ClipDrag` / `PlayheadScrubber` 到 sibling Composable。Rubric §5.6。
- **debt-split-server-container** — `apps/server/.../ServerContainer.kt` 555 行（per R.5 #4）。按 tool 注册 block 线性增长，每次新 tool 都往这里加注册行。**方向：** 复刻 Desktop AppContainer 的 "register tools by category" 分组模式；或提取一个小 `registerAigcTools()` / `registerProjectTools()` helper。Rubric §5.6。
- **debt-split-cli-container** — `apps/cli/.../CliContainer.kt` 524 行（per R.5 #4）。同 server container，tool 注册堆积。**方向：** 同上。Rubric §5.6。
- **export-incremental-render** — CLAUDE.md "Known incomplete" 首条：`ExportTool` memoize 仅到 whole-timeline 层；长 project 只改一个 clip 仍然重新 render 全 timeline（且 `docs/decisions/2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md` 明确列为延期）。**方向：** 扩 `ExportTool` memoization key 到 per-clip 级别，利用 `clipRenderCache` 做 "render one stale clip + reuse the rest"。先补一个 benchmark（和 `debt-add-benchmark-core-paths` 合并做），证明 baseline；再扩缓存。决策阶段定义 key 成分（clip spec hash × source binding hash × render profile hash）。Rubric §5.7。
- **debt-add-runtime-test-session-compaction-bounds** — `Compactor` 有 `pruneProtectTokens = 40_000` 硬上界，但没有单元测试证明"history 超过上界时至少会 drop 一个 part"。一个未来 refactor 不小心把 prune 分支短路会默默失效。**方向：** 给 `Compactor` 加一个"超长 history 必 drop"的 runtime 测试（用 mock provider + 50k+ token 预估的 history，断言 `Result.Compacted` with prunedCount > 0）。Rubric §5.7。

## P2 — 记债 / 观望

- **bundle-mobile-document-picker** — Android / iOS 当前限制于 app sandbox 内的 bundle (`<filesDir>/projects/` / `Documents/projects/`)。用户没法从 SAF / Files.app 选一个外部 bundle 打开。**方向：** Android 接 `Storage Access Framework` (`Intent.ACTION_OPEN_DOCUMENT_TREE`)，iOS 接 `UIDocumentPickerViewController`，结果 URI / NSURL 通过 platform-specific resolver 转成 Okio Path 喂给 `FileProjectStore.openAt`。Rubric §5.4 / mobile。 · skipped 2026-04-23: CLAUDE.md 平台优先级窗口未开 — mobile 仅维持"不退化底线"，此 bullet 属于超出底线的主动扩展。
- **bundle-talevia-json-split** — 当前 `talevia.json` 把 timeline + assets + source DAG + lockfile + snapshots 全装一个文件，单个 mutation 的 git diff 涨几百行；snapshot 多了文件可能涨到 MB 级。**方向：** 当真出现 diff 噪声时拆 `assets.json` / `timeline.json` / `lockfile.json` / `snapshots/<id>.json` 子文件；envelope `talevia.json` 只留 schemaVersion + 元数据 + 子文件清单。先写 decision 评估触发条件再动。Rubric §3a-3。 · skipped 2026-04-23: trigger 未触发 — 无实际用户项目超限 diff 噪声的报告；anti-requirement"不为假想未来需要设计"命中。
- **recents-registry-list-summaries-index** — `RecentsRegistry.listSummaries()` 当前每次扫所有 entry + 解码每个 `talevia.json` envelope，N≥几百时变慢。**方向：** registry 自身缓存 `(title, updatedAtEpochMs)`，envelope 写时同步 registry；`listSummaries` 直接读 registry 不再扫 bundle。Rubric §5.3 / 性能（profiling 触发再做）。 · skipped 2026-04-23: trigger 未触发 — 无实测 profiling 数据支持 N≥几百；anti-requirement"不为假想未来需要设计"命中。
- **debt-bundle-fs-testkit-copy-recursive** — Okio 的 `FakeFileSystem` 没有递归 copy 原语；`BundleCrossMachineExportSmokeTest` 在测试里手写了 `copyDirectoryRecursive(fs, src, dst)`（12 行）。**方向：** 第二个 bundle-level 测试需要同一个 walker 时，抽到 `ProjectStoreTestKit` 或 `BundleFsTestKit`；别提前做（don't pre-abstract on N=1）。**触发条件：** 出现第二个 caller。Rubric 外 / 顺手记录。
- **debt-register-tool-script** — 加一个新 tool 要同步改 5 个 `AppContainer`（CLI / Desktop / Server / Android / iOS），每次 import 行手插入位置错了就挨 ktlint 一顿，有 9/15 ≈ 60% 的 cycle 吃这份税。**方向：** 脚本 `/register-tool <ToolName> <ctorArgs>` 生成 10 行 diff 跨 5 文件 + 自动跑 `ktlintFormat`。**触发条件：** 连续 10 个新-tool cycle 都吃这份税，或 60% 占比在下一次 repopulate 时仍然持有。Rubric 外 / 顺手记录。
- **debt-unified-dispatcher-select-plugin-shape** — 每次给 `project_query` / `session_query` / `source_query` 加一个 select 要改 7-8 个协调站点（SELECT_* 常量 + ALL_SELECTS + Input 字段 + helpText + JSON Schema + `rejectIncompatibleFilters` 矩阵 + execute 分派 + sibling 文件）；reject 矩阵是 `O(n_selects × n_filters)` 成本。**方向：** 一个 select 一个文件的 "select plugin" shape（例：`@AppliesTo(SELECT_FOO)` 注解驱动 filter 适用性），把 7-8 站点收敛为 1 站点。**触发条件：** 任一 dispatcher 的 select 数达到 20，或 `rejectIncompatibleFilters` 矩阵达到 30+ 规则。当前：project 14 / session 15 / source 5 / provider 2。Rubric 外 / 顺手记录。
- **debt-desktop-missing-asset-banner** — cycle-3 落地了 CLI 侧的 `BusEvent.AssetsMissing` 警告（见 `2026-04-23-debt-export-missing-asset-warning.md`），但 bullet 里同样要求的 "Desktop 在 SnapshotPanel / ExportPanel 旁边加 banner" 仍未落地。**方向：** Desktop AppContainer 订阅同一个 bus 事件；SnapshotPanel / ExportPanel 附近加一个 warning banner，内容同 CLI 的 notice（count + 最多 5 条路径 + 溢出尾注）。**触发条件：** Desktop 达到 CLI parity 线后（CLAUDE.md 平台优先级）。Rubric 外 / 顺手记录。

