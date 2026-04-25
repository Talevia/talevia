# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 iteration 的 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **debt-aigc-tool-consolidation** — `core/tool/builtin/aigc/` 4 个单 verb 工具 (`generate_image` 356 LOC / `generate_video` 367 / `generate_music` 310 / `synthesize_speech` 363) 各带一份 helpText + JSON schema + dispatch boilerplate；LLM 每轮付 4 份 spec token。**方向：** 复刻 `clip_action` / `project_action` 模式，折成 `aigc_generate(kind=image|video|music|speech)` 一个 dispatcher + 4 个 sibling handler 文件；同步删 4 个旧工具。净 tool count -3。Rubric §5.6 / §5.7 / §3a-1。Milestone §later. · skipped 2026-04-25: §3a-7 hit — `LockfileEntry.toolId` already stamped `"generate_image"` 等 in on-disk bundles; removing those tool ids breaks ReplayLockfileTool. Needs alias-map design first.
## P1 — 中优，做完 P0 再排

- **session-summary-slash-cmd** — `Part.Compaction` 每次 auto-compaction 写一段摘要进 session，但 CLI 没办法直接 print "上次压缩后的 summary 是什么"，只能翻 log。**方向：** `/summary` slash 调用 `session_query(select=parts, partType=compaction, limit=1)` 取最新 compaction 摘要，按时间格式化打印。Rubric §5.4。Milestone §later.
- **bundle-write-blob-streaming** — `BundleBlobWriter.writeBlob(bytes: ByteArray)` 把整字节数组送进内存；AIGC 4K 视频 30 s 可达 200+ MB。**方向：** 暴露 `writeBlobStreaming(assetId, ext, source: okio.Source)` 路径让 provider 流式写盘，老 `writeBlob(ByteArray)` 改 thin wrapper（小文件通路保持便利）。Rubric §5.7。Milestone §later.
- **lockfile-reverse-lookup-by-asset** — `LockfileEntry` 由 `inputHash` 索引；给一个 assetId 反查"产它的那次 generate 用了哪个 model / seed / sourceBindings"目前要遍历所有 entries。debug + reproducibility 路径常用。**方向：** 在 `project_query` 加 `select=lockfile_entry_for_asset, assetId=…`，handler 拉 timeline 里 clip 引用的 assetId 反向搜 entries。Rubric §5.2 / §5.4。Milestone §later.
- **agent-tool-streaming-bus-event** — Tool 长时间运行（FFmpeg export、AIGC poll、large file read）期间 LLM 看不到中间状态；只有 done / failed 两个终态。**方向：** `BusEvent.ToolStreamingPart(callId, partId, deltaText?, sizeBytes?, message?)` 泛化"工具流式输出"，AIGC pipeline / Export tool / FFmpeg engine 都可发；Renderer 显示 inline progress 行（与 #aigc-streaming-progress-events 共享 UX surface 但更通用）。Rubric §5.4 / §5.6。Milestone §later.
- **debt-aigc-test-fake-extract** — `core/src/jvmTest/.../aigc/`、`tool/builtin/project/` 多处 inline `private class FakeImageEngine / FakeMusicGenEngine / FakeTtsEngine` impls（≥ 6 个独立点）。各副本字段不一致是未来 refactor 的踩点。**方向：** 抽到 `core/src/jvmTest/.../aigc/AigcEngineFakes.kt`（或 `testFixtures` 等价），给最常用的 stub 命名（OneShot / Deterministic / Failing）。Rubric §5.6。Milestone §later.

## P2 — 记债 / 观望

- **debt-todo-fixme-baseline-32** — R.5.6 scan：32 TODO/FIXME/HACK 出现点在 core/commonMain，跨 8 个 repopulate 周期稳定。**方向：** 继续观察；下次 repopulate > 32 → 升 P1 + 列新增行号。**触发条件：** 下次 repopulate delta > 0。Rubric §5.6。Milestone §later.
- **debt-tool-count-net-decrease-stable-79** — R.5.1 scan 79 tools（上轮 84，project lifecycle consolidation 砍了 5）。**方向：** observational；触发 → 追近似群分析。**触发条件：** 下次 repopulate count > 81。Rubric §5.6。Milestone §later.
- **debt-long-file-creep-watch-421** — 当前最长 BusEvent.kt 421 LOC（上轮 394，+27），第二是 ProviderQueryTool 403 LOC，第三是 SqlDelightSessionStore 387 LOC。距 500 LOC 硬阈值 BusEvent 还有 79 行 headroom。**方向：** observational。**触发条件：** 下次 repopulate 任一文件 ≥ 500 LOC。Rubric §5.6。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。**方向：** 拆 sub-files。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy；`BundleCrossMachineExportSmokeTest` inline 12 行 helper（grep 仍只 1 caller）。**方向：** 第二个 caller 出现时 extract 到 `BundleFsTestKit`。**触发条件：** 第二 caller 出现。Rubric §5.6。Milestone §later.
- **desktop-live-render-preview** — Desktop 只在 timeline 上看 clip 变绿 + 静态 thumbnails，没有实时 render preview。**方向：** Compose Desktop + Media3/ffmpeg bridge 驱动 playback surface；subscribe `BusEvent.AigcJobProgress` 把 clip-ready 实时替换进 preview。**触发条件：** Desktop 达 CLI parity 的平台优先级窗口开。Rubric §5.4 / desktop。Milestone §later.
- **cli-cancel-indicator-tools** — Ctrl-C 显示 "(cancelling — Ctrl+C again to force quit)"，但不报正在 abort 哪些 tool。`cancellation_history.inFlightToolIds` 已有数据。**方向：** Ctrl-C 触发后多打一行 "aborting: generate_image, synthesize_speech"。**触发条件：** operator 反馈或具体场景。Rubric §5.4。Milestone §later.
- **re-evaluate-m2-provider-second-impl** — `m2-provider-second-impl` 跨 8+ repopulate 周期连续 skip-tagged（"需专有 API key + vendor 决策"），符合 §R skip-≥3-cycles 元 bullet 规则。M2 退出仅卡这一条 criterion。**方向：** 用户决定 promote (给定 vendor + key) / demote (M2 改"single provider acceptable") / delete (criterion 不再相关)。Rubric §5.7 / §5.2。Milestone §M2. · skipped 2026-04-25: meta bullet awaiting user decision (no agent action possible).
- **debt-split-bus-event-kt** — `BusEvent.kt` 421 LOC（cycle 113 scan: 421，+27 since cycle 102 baseline 394），sealed interface 27+ 子类。Past 阻塞: 464 `BusEvent.X` call sites。**方向：** 当 BusEvent.kt 真正逼近 500 LOC 时，atomic commit: 子类全数 top-level data class、覆盖每个 `BusEvent.X` → `X` 用 `perl -i`。**触发条件：** BusEvent.kt 行数达 480。Rubric §5.6。Milestone §later. · skipped 2026-04-25: trigger not yet met (file at 421; 59 LOC headroom).
- **aigc-result-multi-variant** — `generate_image` / `generate_video` 每次产 1 个 asset；OpenAI / Replicate 都支持 `n` 参数返 N 个候选让人挑。当前 agent 拿不到。**方向：** AIGC tools 接收 `n: Int = 1`，返回 List<assetId>；lockfile 每个变体一条 entry，共享 inputHash + 不同 variantIndex。Permission ASK 一次性覆盖 N 次成本。Rubric §5.2。Milestone §later.
- **debt-flaky-provider-warmup-test** — `ProviderWarmupKickoffTest.successfulProviderEmitsStartingThenReady` 在 cycle 115 偶发 5s 超时（passed on retry）。可能是 `Dispatchers.Default` 协程调度在 CI/loaded 机器上的 race；`runTest` + `withContext(Dispatchers.Default)` + `withTimeout(5.seconds) { while (captured.size < 2) yield() }` 组合容易在 Default 池忙时饿死。**方向：** 切到 `runTest { ... }` 内的 test dispatcher，用 `advanceUntilIdle` 替换 yield-loop；或加 `awaitReady` 屏障 (类似 `BusEventTraceRecorder.awaitReady`)。**触发条件：** 再观察 ≥ 1 次 flake。Rubric 外 / 顺手记录。Milestone §later.
