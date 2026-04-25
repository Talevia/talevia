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

- **re-evaluate-m2-provider-second-impl** — `m2-provider-second-impl` 跨 5+ repopulate 周期连续 skip-tagged（"需专有 API key + vendor 决策"），符合 §R skip-≥3-cycles 元 bullet 规则。M2 退出仅卡这一条 criterion。**方向：** 用户决定 promote (给定 vendor + key) / demote (M2 改"single provider acceptable") / delete (criterion 不再相关)。Rubric §5.7 / §5.2。Milestone §M2. · skipped 2026-04-24: meta bullet awaiting user decision (no agent action possible)
- **debt-split-bus-event-kt** — `BusEvent.kt` 385 LOC, sealed interface 27 子类。Past 阻塞: 464 `BusEvent.X` call sites。**方向：** kotlin nested-vs-top-level 限制下，把每个子类做独立顶层 data class，`BusEvent` 留 marker interface。Rubric §5.6。Milestone §later. · 历史阻塞 — 见 `git log --grep skip-close.*bus-event.kt`. · skipped 2026-04-24: trigger not met (file still 385 LOC, well under 500-LOC threshold; past skip-close at 1765a11f documents the trade-off — touching ~496 call sites for an observational preempt is the wrong cost / risk ratio while the file isn't growing).
- **benchmark-export-tool-end-to-end** — `ExportTool` 是用户感知最慢的入口（per-clip render + transitions + subtitles + LUT），但 7 bench 里只测组件，没有"小项目 export 整流程"端到端 wall-time guard。**方向：** `ExportEndToEndBenchmark` —— 5-clip 1080p 项目，full export 一次 + 一次缓存命中再跑，soft budget。Rubric §5.7。Milestone §later.

## P2 — 记债 / 观望

- **debt-todo-fixme-baseline-32** — R.5.6 scan：32 TODO/FIXME/HACK 出现点在 core/commonMain，跨 5 个 repopulate 周期稳定。**方向：** 继续观察；下次 repopulate > 32 → 升 P1 + 列新增行号。**触发条件：** 下次 repopulate delta > 0。Rubric §5.6。Milestone §later.
- **debt-tool-count-net-growth** — R.5.1 scan 84 tools；连续 5 cycle 稳定。**方向：** observational；触发 → 追近似群分析。**触发条件：** 下次 repopulate count > 86。Rubric §5.6。Milestone §later.
- **debt-long-file-creep-watch** — 当前最长 6 文件（excluding tests）落在 360-417 LOC 区间，距 500 LOC 硬阈值都还有 80+ headroom。但若任一冲过 500 即升 P1（per R.5.4）。**方向：** observational。**触发条件：** 下次 repopulate 任一文件 ≥ 500 LOC。Rubric §5.6。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。**方向：** 拆 sub-files。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy；`BundleCrossMachineExportSmokeTest` inline 12 行 helper（grep 仍只 1 caller）。**方向：** 第二个 caller 出现时 extract 到 `BundleFsTestKit`。**触发条件：** 第二 caller 出现。Rubric §5.6。Milestone §later.
- **desktop-live-render-preview** — Desktop 只在 timeline 上看 clip 变绿 + 静态 thumbnails，没有实时 render preview。**方向：** Compose Desktop + Media3/ffmpeg bridge 驱动 playback surface；subscribe `BusEvent.AigcJobProgress` 把 clip-ready 实时替换进 preview。**触发条件：** Desktop 达 CLI parity 的平台优先级窗口开。Rubric §5.4 / desktop。Milestone §later.
- **cli-cancel-indicator-tools** — Ctrl-C 显示 "(cancelling — Ctrl+C again to force quit)"，但不报正在 abort 哪些 tool。`cancellation_history.inFlightToolIds` 已有数据。**方向：** Ctrl-C 触发后多打一行 "aborting: generate_image, synthesize_speech"。**触发条件：** operator 反馈或具体场景。Rubric §5.4。Milestone §later.
