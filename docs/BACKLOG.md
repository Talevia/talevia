# Backlog

`/iterate-gap` 的驱动源。每个 cycle 的流程：

1. 读这个文件，挑**最靠上的未完成**任务（P0 → P1 → P2，同档内按出现顺序）。
2. plan → 实现 → 验证 → 归档决策 → commit + push。
3. 在 iteration 的 commit 里顺手把本任务的 bullet 从这个文件里删掉（不是打钩，直接删），保持列表紧凑。
4. 如果这个文件的 P0/P1/P2 三档都已清空（或文件不存在），`/iterate-gap` 会按 skill 里的 rubric 分析一次性生成 **20 条新任务**写回这个文件、commit，然后**在同一个 cycle 里继续**挑新生成列表里第 1 条开干。

任务描述只给 **Gap（现状 / 痛点）** 和 **方向（期望动的东西）** —— 细节留给 cycle 里的 plan 阶段现读代码决定。Rubric 轴用 `§5.x` 标。

---

## P0 — 高杠杆、下一步就该动

- **source-node-diff-restore-composite-tool** — P0 restore tool（按整 revision 回滚）已落 `revision_index` 路径。但 user "把 character_ref 的 hair 字段还原成 3 版本前，prompt 字段保持当前" 的 per-field 合并需求当前仍要走读 history → 手编 body → update_source_node_body 的 3-step 链路。**方向：** `merge_source_node_body_from_history(nodeId, revisionIndex, fieldPaths)` 一次完成；history 已经是 JSONL append-only，per-field 抽取走 `JsonObject.merge` patch。Rubric §5.5。Milestone §later.

## P1 — 中优，做完 P0 再排

- **m2-provider-second-impl** — M2 criterion 2："provider 多元"。`ImageGenEngine` / `VideoGenEngine` / `MusicGenEngine` / `TtsEngine` 4 个接口每个都只有 1 个 prod impl（grep 印证：OpenAI 图/视/语音 + Replicate 音乐/放大，各 1 家）。**方向：** 任一 engine 长出第二个非 stub 生产 impl（如 `AnthropicImageGenEngine` 若 Claude 上线图像、`ElevenLabsTtsEngine`、`StabilityImageGenEngine`、`LocalMLXTtsEngine`）。需要专有 API key + 产品抉择，待用户决定。Rubric §5.7 / §5.2。Milestone §M2. · skipped 2026-04-24: 需专有 API key + vendor 决策 (跨 5 个 repopulate 周期的老约束).
- **debt-split-fork-project-tool** — `ForkProjectTool.kt` 373 LOC。fork shape variant matrix（time / language / style）+ project-state copy + lockfile rewrite + source DAG re-id 都在一个 class。**方向：** 抽 `ForkVariantApplier.kt`（per-variant transform）+ `ForkProjectIdRewrite.kt`（id 替换 + lockfile re-keying），主 class 留 schema + dispatch。Rubric §5.6。Milestone §later.
- **debt-split-import-source-node-tool** — `ImportSourceNodeTool.kt` 387 LOC。merge / replace / append 三种 strategy + parent rewriting + cycle detection 在一个文件。**方向：** strategy → handler 文件（mirror of `ClipCreateHandlers` / `ClipMutateHandlers` axis split），主 class 留 dispatch。Rubric §5.6。Milestone §later.
- **debt-split-update-source-node-body-tool** — `UpdateSourceNodeBodyTool.kt` 379 LOC。 patch / replace / merge mode + history append + downstream stale propagation 在一个 class。**方向：** Schema extract + history-write helper 抽到 sibling。Rubric §5.6。Milestone §later.
- **aigc-cost-estimate-tool** — 之前 5 cycle 前 skip-tagged 因 `list_tools` Summary 上有 `priceBasisFor`。但 agent 看到的是 "rate per second" 字符串，仍要自己算 `8s × $0.30 = $2.40`。前置 cost 估算工具能让 agent 在 propose 阶段直接拿到 cents 数字。**方向：** `estimate_aigc_cost(toolId, inputs)` 返 cents。基于 `AigcPricing` table + 推断 (videoSeconds / imageCount / textChars)。共享 `AigcBudgetGuard` 用的 pricing entry。Rubric §5.7 / §5.2。Milestone §later.
- **cli-tab-completion-args** — JLine LineReader 当前只补全 slash command 名字，不补全 arg。`/resume <prefix>`、`/revert <messageId>`、`/fork <messageId>` 三个 command 都需要从 store 读 id 列表来补；现在 user 必须手 copy-paste。**方向：** 按 command 注册 arg-completer（read sessions / messages 列表的前 12 chars），挂到 JLine 的 `Completer` 接口。Rubric §5.4。Milestone §later.
- **session-query-permission-history** — `BusEvent.PermissionAsked` / `PermissionReplied` 已发射但 agent 读不到 "本 session 用户拒绝过哪些 tool"。`select=cancellation_history` 解决了 cancel 的同类需求；permission 是平行 axis。**方向：** `session_query(select=permission_history)` 返 `{messageId, partId, toolId, permission, decision, reason?, epochMs}`，oldest-first；agent 可以 "记得 user 拒过的 tool 不要二次问"。Rubric §5.4。Milestone §later.
- **project-query-source-binding-stats** — 当前只有 `dag_summary` 一行带 `nodesByKind`。"我项目里 12 个 character_ref 中 8 个被实际 clip 引用，4 个 orphan" 这种"使用率"画像没暴露。**方向：** `project_query(select=source_binding_stats)` 返 per-kind row：`{kind, totalNodes, boundDirectly, boundTransitively, orphans}`，让 agent 一眼看到 "stylebible 覆盖率不足"。Rubric §5.1 / §5.5。Milestone §later.
- **benchmark-source-deep-content-hash** — `Source.deepContentHashOf` 是 per-clip render fingerprint 的关键计算 (parent traversal × Json encode)。无 wall-time guard，下次某 source 改动让 hash 不缓存就退化 4-10× 没人发现。**方向：** `SourceDeepHashBenchmark` —— 100-node DAG (depth 6 mix), per-node hash + 多 leaf 重叠 cache —— wall-time soft budget。和现有 7 bench 同 pattern。Rubric §5.7。Milestone §later.
- **debt-tool-count-net-growth** — R.5.1 scan 84 tools；连续 4 cycle 稳定（之前 85 → 84 在 pin merge）。下次 repopulate net + 2 视为膨胀信号 → 升 P1 + 列新 tool 列表。**方向：** observational；触发 → 追近似群分析。**触发条件：** 下次 repopulate count > 86。Rubric §5.6。Milestone §later.

## P2 — 记债 / 观望

- **debt-todo-fixme-baseline-32** — R.5.6 scan：32 TODO/FIXME/HACK 出现点在 core/commonMain，跨 4 个 repopulate 周期稳定。**方向：** 继续观察；下次 repopulate > 32 → 升 P1 + 列新增行号。**触发条件：** 下次 repopulate delta > 0。Rubric §5.6。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。**方向：** 拆 sub-files。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy；`BundleCrossMachineExportSmokeTest` inline 12 行 helper（本 cycle 扫 grep 仍只 1 caller）。**方向：** 第二个 caller 出现时 extract 到 `BundleFsTestKit`。**触发条件：** 第二 caller 出现。Rubric §5.6。Milestone §later.
- **desktop-live-render-preview** — Desktop 只在 timeline 上看 clip 变绿 + 静态 thumbnails，没有 timeline 实时 render preview。**方向：** Compose Desktop + Media3 / ffmpeg bridge 驱动一个 playback surface；subscribe `BusEvent.AigcJobProgress` 把 clip-ready 实时替换进 preview。**触发条件：** Desktop 达 CLI parity 的平台优先级窗口开。Rubric §5.4 / desktop。Milestone §later.
- **cli-cancel-indicator-tools** — 当前 Ctrl-C 显示 "(cancelling — Ctrl+C again to force quit)"，但不报正在 abort 哪些 tool。`cancellation_history.inFlightToolCallCount` 数据已齐；CLI cancel banner 可以取同源数据展示。**方向：** Ctrl-C 触发后多打一行 "aborting: generate_image, synthesize_speech"。**触发条件：** operator 反馈或具体场景。Rubric §5.4。Milestone §later.
- **agent-mid-turn-cancel-retry-loop-early-exit** — 如果 retry loop 在 backoff sleep 中 cancel 触发，理想是立即唤醒退出。当前实测未必（retry 用 `delay(...)` 应该响应 CancellationException）；需 e2e test 验证。**方向：** 写测试 — provider 抛 retriable error → backoff 中 cancel → assert cancel resolves within 100ms（不是 retry budget 余下时间）。**触发条件：** 测试发现实测延迟过长，或 user 反馈 "Ctrl-C 半天才退"。Rubric §5.4。Milestone §later.
