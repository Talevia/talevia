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

- **m2-provider-second-impl** — M2 criterion 2："provider 多元"。`ImageGenEngine` / `VideoGenEngine` / `MusicGenEngine` / `TtsEngine` 4 个接口每个都只有 1 个 prod impl（grep 印证：OpenAI 图/视/语音 + Replicate 音乐/放大，各 1 家）。**方向：** 任一 engine 长出第二个非 stub 生产 impl（如 `AnthropicImageGenEngine` 若 Claude 上线图像、`ElevenLabsTtsEngine`、`StabilityImageGenEngine`、`LocalMLXTtsEngine`）。需要专有 API key + 产品抉择，待用户决定。Rubric §5.7 / §5.2。Milestone §M2. · skipped 2026-04-24: 需专有 API key + vendor 决策 (跨 3 个 repopulate 周期的老约束).
- **agent-mid-turn-cancel** — `agent.run()` 一旦启动要等 turn 结束才能响应用户二次输入。OpenCode `session/prompt.ts` 有 `cancel(sessionId)` 中断正在运行的 turn，同时 mark 输出为 canceled。当前用户 Ctrl-C 只能杀整进程。**方向：** `agent.cancel(runId)` 取消 inflight provider SSE + 跳出 retry loop + upsert `Part.Cancellation` + emit `BusEvent.AgentRunStateChanged(Cancelled)`；CLI REPL 的 Ctrl-C 挂这条。Rubric §5.4。Milestone §later.

## P2 — 记债 / 观望

- **debt-consolidate-pin-tools** — `SetClipAssetPinnedTool` + `SetLockfileEntryPinnedTool` + `SetOutputProfileTool` 三个工具同一 shape："set single field on project sub-entity"；tool 数 85 的主要膨胀源之一，且后续 pin / unpin / toggle 语义会叠加更多。**方向：** `project_set_action(field=clip_pinned|lockfile_pinned|output_profile, target, value)` 套 `ClipSetActionTool(field=...)` 模板，一次 -200 LOC。**触发条件：** 第 4 个 set-field 工具出现。Rubric §5.6。Milestone §later.
- **debt-todo-fixme-baseline-stable** — R.5.6 scan：32 TODO/FIXME/HACK 出现点在 core/commonMain，和前一次 repopulate 基线一致，无增长。**方向：** 继续观察；下次 repopulate > 32 → 升 P1 + 列新增行号。**触发条件：** 下次 repopulate delta > 0。Rubric §5.6。Milestone §later.
- **cli-spend-slash-command** — CLI 无 `/spend` —— 要看本 session 花了多少必须手打 `session_query(select=spend_summary)`。已落地的 `session_query` spend row 没有前端捷径。**方向：** CLI `/spend` slash = `session_query(spend_summary, currentSessionId)` + 渲染 spend row。**触发条件：** operator 反馈 typing 烦 / 或作为 `/metrics` 一并开发。Rubric §5.4。Milestone §later.
- **cli-metrics-slash-command** — `CounterRegistry` 暴露 counters 只通过 server `/metrics`。CLI 用户只能间接看。**方向：** CLI `/metrics` slash-command 读 in-process CounterRegistry。**触发条件：** operator 反馈。Rubric §5.4 / §5.6。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。**方向：** 拆 sub-files。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy；`BundleCrossMachineExportSmokeTest` inline 12 行 helper。**方向：** 第二个 caller 出现时 extract 到 `BundleFsTestKit`。**触发条件：** 第二 caller 出现。Rubric §5.6。Milestone §later.
- **source-node-diff-restore-composite-tool** — P0 restore tool 已落；用户会想 per-field merge-restore。**方向：** `merge_source_node_body_from_history(nodeId, revisionIndex, fieldPaths)`。**触发条件：** operator 要 per-field granularity。Rubric §5.5。Milestone §later.
- **tool-spec-budget-remeasure** — 85 tools 累积中；上次 audit 在 398fb0ec（22700 token ceiling）。期间新增 warmup events / ascii_tree / 多个 benchmarks 会轻微 push budget 上涨。**方向：** 重跑 `session_query(select=tool_spec_budget)` + 写进 repopulate commit body 作为监控曲线下一点。**触发条件：** 下次 repopulate delta > 1500 token。Rubric §5.7。Milestone §later.
