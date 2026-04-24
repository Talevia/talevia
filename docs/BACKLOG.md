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

- **m2-provider-second-impl** — M2 criterion 2："provider 多元"。`ImageGenEngine` / `VideoGenEngine` / `MusicGenEngine` / `TtsEngine` 4 个接口每个都只有 1 个 prod impl（grep 印证：OpenAI 图/视/语音 + Replicate 音乐/放大，各 1 家）。**方向：** 任一 engine 长出第二个非 stub 生产 impl（如 `AnthropicImageGenEngine` 若 Claude 上线图像、`ElevenLabsTtsEngine`、`StabilityImageGenEngine`、`LocalMLXTtsEngine`）。需要专有 API key + 产品抉择，待用户决定。Rubric §5.7 / §5.2。Milestone §M2. · skipped 2026-04-24: 需专有 API key + vendor 决策 (跨 4 个 repopulate 周期的老约束).
- **agent-cancel-tool-state-cancelled-variant** — 本 cycle cancel 修复用了 `ToolState.Failed("cancelled: <reason>")` 包装——合理但 downstream 想 distinguish "tool 真失败" vs "tool 被 cancel" 得匹配消息前缀。现在 47 个 when-on-ToolState 站点都走 Failed 通道。**方向：** 加 `ToolState.Cancelled(input)` 作为第 5 variant，把 47 站点的 exhaustive when 分支补齐；`finalizeCancelled` 改为 upsert Cancelled 而非 Failed-with-prefix。Rubric §5.4 / §5.6。Milestone §later.
- **source-query-leaves** — `dag_summary.leafNodeIds` 已可得但没 dedicated select（镜像 `orphans` / `ascii_tree` 的 cleanup-surface 思路）。**方向：** `source_query(select=leaves)` 返无 children 的节点 (id / kind / revision / parentCount)，供 agent 逐 leaf 做 `describe_source_node` / regenerate 决策。Rubric §5.1 / §5.5。Milestone §later.

## P2 — 记债 / 观望

- **debt-split-clip-action-tool-preempt** — `ClipActionTool.kt` 418 LOC（core 第 3 长）。一次已拆（cycle-24 extract Create/Mutate handlers + Helpers 降到 343 LOC，后续 replace/fade 又 +75）。下一批 action 再上来会再推。**方向：** Mutate handlers 已独立；把 dispatcher 本身的 schema / reject-matrix 按 `ClipActionToolSchema.kt` 抽（镜像 ImportMediaTool/SourceQueryTool 的 Schema extract pattern）。Rubric §5.6。Milestone §later.
- **debt-split-anthropic-provider** — `AnthropicProvider.kt` 384 LOC。request transcoding (Kotlin messages → Anthropic wire format) + SSE parsing + stream event translation 混在一个 class。**方向：** 抽 `AnthropicWireEncoder`（一边）+ `AnthropicSseParser`（另一边）；主 class 只保留 HTTP client 调用 + Flow emit 流控。Rubric §5.6。Milestone §later.
- **debt-split-prompt-editing-and-external** — `core/agent/prompt/PromptEditingAndExternal.kt` 383 LOC。两个独立 prompt lane 共享一个文件。**方向：** 拆成 `PromptEditingLane.kt` + `PromptExternalLane.kt` —— 两个 const 互相不引用。Rubric §5.6。Milestone §later.
- **debt-todo-fixme-baseline-32** — R.5.6 scan：32 TODO/FIXME/HACK 出现点在 core/commonMain，和前两次 repopulate 基线一致，无增长（跨 3 周期稳定）。**方向：** 继续观察；下次 repopulate > 32 → 升 P1 + 列新增行号。**触发条件：** 下次 repopulate delta > 0。Rubric §5.6。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。**方向：** 拆 sub-files。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy；`BundleCrossMachineExportSmokeTest` inline 12 行 helper。**方向：** 第二个 caller 出现时 extract 到 `BundleFsTestKit`。**触发条件：** 第二 caller 出现（本 cycle 扫 grep 确认还是 1 caller）。Rubric §5.6。Milestone §later.
