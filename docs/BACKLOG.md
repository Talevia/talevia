# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 iteration 的 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **cli-projects-slash-cmd** — `/sessions` 列出当前 project 的会话；但 CLI 没法列"系统里有哪些 project"。`list_projects` 工具能给 agent 用，但 operator 直接想看的时候没 slash。**方向：** `/projects` slash dispatch `ListProjectsTool` (或直接读 `RecentsRegistry`)，按 `updatedAt` 倒序打 id + title + path。Rubric §5.4。Milestone §later.
- **re-evaluate-debt-aigc-tool-consolidation** — `debt-aigc-tool-consolidation` 跨 9 cycles 持续 skip-tagged（自 cycle 113 起，原因 §3a-7：`LockfileEntry.toolId` 已 stamped `"generate_image"` 等在 on-disk bundle，删旧工具会让 `ReplayLockfileTool` 对老 entry 失效）。符合 §R skip-≥3-cycles 元 bullet 规则。**方向：** 用户决定 promote (设计 alias-map: `"generate_image" → "aigc_generate" + kind="image"` 写进 ReplayLockfileTool) / demote (接受 4 个独立工具的 LLM context tax) / delete (criterion 不再相关)。Rubric §5.6 / §5.7 / §3a-1 / §3a-7。Milestone §later.

## P1 — 中优，做完 P0 再排

- **agent-system-prompt-per-session** — `Agent` 在 container 时拿到 `systemPrompt: String`，session 没法 override。意味着同一个 Agent 实例上，所有 session 共享一份 prompt —— "把这个 session 切到代码 review mode" 这类需求得另起 Agent。**方向：** `Session.systemPromptOverride: String? = null`（@Serializable default 兼容旧 SQL blob）。Agent loop 在拼 system prompt 时 `session.systemPromptOverride ?: defaultSystemPrompt`。新 tool / slash 可以 set。Rubric §5.4。Milestone §later.
- **lockfile-diff-by-snapshots** — `regenerate_stale_clips` 完成后，agent 想知道"这次重生产了几条 entry / 哪些 entry 的 inputHash 变了"，目前只能拉两次 `lockfile_entries` 自己 diff。**方向：** `project_query(select=lockfile_diff, fromSnapshotId=…, toSnapshotId=…)`；handler 比两个 snapshot 的 lockfile entries by `inputHash`，分类 added / removed / unchanged。Rubric §5.2 / §5.4。Milestone §later.
- **cli-forks-slash-cmd** — 用户在 session 之间 `/fork` 和 `/resume` 跳来跳去，没办法看"这个 session 是从哪个 session fork 来的，又被 fork 出过哪几个分支"。`session_query(select=forks)` 已存在。**方向：** `/forks` slash dispatch 该 select，按时间倒序打 fork 树（祖先 → 当前 → 子分支）；指明哪个是当前 session。Rubric §5.4。Milestone §later.
- **aigc-result-multi-variant** — `generate_image` / `generate_video` 每次产 1 个 asset；OpenAI / Replicate 都支持 `n` 参数返 N 个候选让人挑。当前 agent 拿不到。**方向：** AIGC tools 接收 `n: Int = 1`，返回 List<assetId>；lockfile 每个变体一条 entry，共享 inputHash + 不同 variantIndex。Permission ASK 一次性覆盖 N 次成本。Rubric §5.2。Milestone §later.
- **debt-aigc-test-fake-extract-phase-2** — cycle 121 落地了 5 个 `OneShot*Engine` 共享 fakes 并迁移了 5 个最简单 call site；剩 9 个 inline fake 仍未抽（`CountingImageEngine` × 3 callers、`WarmingFakeEngine`、`FailingTtsEngine`、e2e 的 `FullFieldsImageEngine` / `OneShotImageEngine` 等）。**方向：** 加 `CountingImageGenEngine`（call-count-keyed bytes）+ `WarmingMusicGenEngine`（onWarmup callback）+ `FailingTtsEngine` 到 `AigcEngineFakes.kt`，然后扫所有还没迁的 inline。**触发条件：** 出现第 10 个 inline fake（说明 phase 1 没收住），或下次有人改 `*Engine` interface 因为 fake 不一致踩坑。Rubric §5.6。Milestone §later.

## P2 — 记债 / 观望

- **debt-todo-fixme-baseline-32** — R.5.6 scan：32 TODO/FIXME/HACK 出现点在 core/commonMain，跨 9 个 repopulate 周期稳定。**方向：** 继续观察；下次 repopulate > 32 → 升 P1 + 列新增行号。**触发条件：** 下次 repopulate delta > 0。Rubric §5.6。Milestone §later.
- **debt-tool-count-net-decrease-stable-77** — R.5.1 scan 77 tools（cycle 113 后 84 → 79；cycle 113 后 79 → 77 from source-singleton fold）。**方向：** observational；触发 → 追近似群分析。**触发条件：** 下次 repopulate count > 79。Rubric §5.6。Milestone §later.
- **debt-long-file-creep-watch-482** — 当前最长 SourceNodeActionTool 482 LOC（cycle 113 后从 ~283 涨到 482，因为 update_body / set_parents 折进来），第二是 BusEvent.kt 473 LOC（cycle 114 加 AigcJobProgress 后 +52），第三是 ProviderQueryTool 403 LOC。距 500 LOC 强制阈值 SourceNodeActionTool 还有 18 行 headroom。**方向：** observational + 提示下一次 SourceNodeActionTool 改动注意阈值。**触发条件：** 下次 repopulate 任一文件 ≥ 500 LOC。Rubric §5.6。Milestone §later.
- **debt-split-source-node-action-tool** — `SourceNodeActionTool.kt` 482 LOC（cycle 121 scan，离 500 LOC 强制 P0/P1 阈值还有 18 行）。class 自己只装 dispatcher + Input/Output schema + 6-way `when`，schema 已经 inline 了 ~110 LOC；下次添加第 7 个 verb 大概率把它推过 500。**方向：** trigger 命中时 atomic commit：把 `inputSchema` 抽到 `SourceNodeActionToolSchema.kt`（mirror `ClipActionToolSchema.kt` / `ProjectActionToolSchema.kt`），单文件 split 即可。**触发条件：** SourceNodeActionTool ≥ 500 LOC。Rubric §5.6。Milestone §later.
- **debt-split-bus-event-kt** — `BusEvent.kt` 473 LOC（cycle 121 scan：473，+52 since cycle 114 baseline 421，主要是 AigcJobProgress 加进来）。sealed interface 28+ 子类。Past 阻塞: 464 `BusEvent.X` call sites。**方向：** 当 BusEvent.kt 真正逼近 500 LOC 时，atomic commit: 子类全数 top-level data class、覆盖每个 `BusEvent.X` → `X` 用 `perl -i`。**触发条件：** BusEvent.kt 行数达 480。Rubric §5.6。Milestone §later. · skipped 2026-04-25: trigger not yet met (file at 473; 7 LOC headroom — close).
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy；`BundleCrossMachineExportSmokeTest` inline 12 行 helper（grep 仍只 1 caller）。**方向：** 第二个 caller 出现时 extract 到 `BundleFsTestKit`。**触发条件：** 第二 caller 出现。Rubric §5.6。Milestone §later.
- **desktop-live-render-preview** — Desktop 只在 timeline 上看 clip 变绿 + 静态 thumbnails，没有实时 render preview。**方向：** Compose Desktop + Media3/ffmpeg bridge 驱动 playback surface；subscribe `BusEvent.AigcJobProgress` 把 clip-ready 实时替换进 preview。**触发条件：** Desktop 达 CLI parity 的平台优先级窗口开。Rubric §5.4 / desktop。Milestone §later.
- **cli-cancel-indicator-tools** — Ctrl-C 显示 "(cancelling — Ctrl+C again to force quit)"，但不报正在 abort 哪些 tool。`cancellation_history.inFlightToolIds` 已有数据。**方向：** Ctrl-C 触发后多打一行 "aborting: generate_image, synthesize_speech"。**触发条件：** operator 反馈或具体场景。Rubric §5.4。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。**方向：** 拆 sub-files。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **agent-tool-streaming-text-delta** — 当 Talevia 长出第一个会发 textual / byte-stream delta 的工具（shell exec、log-tail、chunked file read 之类），需要 generic `BusEvent.ToolStreamingPart(callId, partId?, deltaText?, sizeBytes?, message?)` 把 chunk 推上 bus。今天没有这种工具：AIGC 走 `BusEvent.AigcJobProgress`（lifecycle ticks），FFmpeg / Export 走 `Part.RenderProgress`（per-frame ratio），ReadFileTool 是同步的。**方向：** 第一个 chunk-emitting tool 落地时同步加 BusEvent + EventBusMetricsSink + ServerDtos exhaustive `when` 三处分支。**触发条件：** 出现 ≥ 1 个会发 textual/byte-stream chunk 的 tool。Rubric §5.4。Milestone §later.
- **debt-flaky-provider-warmup-test** — `ProviderWarmupKickoffTest.successfulProviderEmitsStartingThenReady` 在 cycle 115 偶发 5s 超时（passed on retry）。可能是 `Dispatchers.Default` 协程调度在 CI/loaded 机器上的 race；`runTest` + `withContext(Dispatchers.Default)` + `withTimeout(5.seconds) { while (captured.size < 2) yield() }` 组合容易在 Default 池忙时饿死。**方向：** 切到 `runTest { ... }` 内的 test dispatcher，用 `advanceUntilIdle` 替换 yield-loop；或加 `awaitReady` 屏障 (类似 `BusEventTraceRecorder.awaitReady`)。**触发条件：** 再观察 ≥ 1 次 flake。Rubric 外 / 顺手记录。Milestone §later.
- **re-evaluate-m2-provider-second-impl** — `m2-provider-second-impl` 跨 9+ repopulate 周期连续 skip-tagged（"需专有 API key + vendor 决策"），符合 §R skip-≥3-cycles 元 bullet 规则。M2 退出仅卡这一条 criterion。**方向：** 用户决定 promote (给定 vendor + key) / demote (M2 改"single provider acceptable") / delete (criterion 不再相关)。Rubric §5.7 / §5.2。Milestone §M2. · skipped 2026-04-25: meta bullet awaiting user decision (no agent action possible).
