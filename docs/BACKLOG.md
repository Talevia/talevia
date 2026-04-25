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

- **debt-split-session-action-tool** — `SessionActionTool.kt` 422 LOC, core 当前最长 (cycle-103 scan); ALL_ACTIONS 已含 10+ verb (revert / fork / spend_cap / tool_enabled / switch_project / remove_permission_rule / …)。每个新 action 加 helper + dispatch case。**方向：** 按 verb 簇抽到 `session/action/<group>Handlers.kt` (lifecycle / permission / spend / projectBinding 四簇)，dispatcher 主类回到 ≤300 LOC。Rubric §5.6。Milestone §later.
- **debt-bound-lockfile-entries** — `Lockfile.entries: List<LockfileEntry>` append-only，cycle 越长越大；`project_query(select=lockfile_orphans)` 已识别可 GC 候选，但**没有 writer 删除它们**。长寿项目的 talevia.json blob 持续膨胀 → re-encode 成本 O(N)。**方向：** `project_action(action=gc_lockfile)` 或新 `delete_lockfile_entries` 接口接收 `inputHash[]` / 时间窗 / `onlyOrphaned` 过滤。带 dry-run + 删除前 require permission。Rubric §5.6 / §5.7 / §3a-3。Milestone §later.
- **session-text-search** — 操作者打开旧 session 时只能按 sessionId 列表 + recap 过一遍，没法按内容过滤"我那次问了 X 的 session 是哪个"。当前 fallback：手动 grep `~/.talevia/talevia.db` SQLite。**方向：** `session_query(select=text_search, query, sessionId?, role?)` 走 message body 子串匹配 (在 SqlDelight `messages.data` blob 上 LIKE) 返 (messageId, sessionId, snippet, matchOffset)。Rubric §5.4。Milestone §later.
- **agent-parallel-tool-dispatch** — assistant turn 包含 N 个 tool_use 时当前逐个串行 dispatch；其中互相不依赖的 (e.g. 两个 `read_part`) 完全可以并行。Long-tail tool 串行严重拖慢 turn。**方向：** ToolDispatcher 接收一批 tool_use → 用 `coroutineScope { ... async { ... } }` 并行；保持 result 顺序匹配 input 顺序写回 message。Rubric §5.7。Milestone §later.
- **aigc-budget-warning-at-80pct** — `aigc.budget` ASK 只在 `cumulative >= cap` 触发（cycle-101 的 export-fail-fast 同样硬 ≥），用户从 "全无感知" 跳到 "卡死"。中间没有"快超了"的 soft signal。**方向：** AigcBudgetGuard / ExportToolBudgetGuard 在 0.8×cap ≤ cumulative < cap 时发 `BusEvent.SpendCapApproaching`（不 ASK，仅事件 + CLI surface），user 自然减速。Rubric §5.2 / §5.7。Milestone §later.
- **source-dag-cycle-detection** — `Source.replaceNode` / `addLink` 假设调用方传无环图，没有 guard。一个 node body 里 referenceIds 引用自己（或后代）→ 后续 `descendants(rootId)` 遍历会无限递归 → 栈溢出。**方向：** `Source.validateAcyclic()` helper（DFS + visiting set），调入每个写入路径的入口；返红一条 `ProjectValidationWarning` 而非 throw。Rubric §5.1。Milestone §later.
- **provider-warmup-eager-prefetch** — 首个 AIGC 调用承担 cold-start latency (TLS + auth + model handshake)；`ProviderWarmup` 事件能观察但没人在 container init 时主动 fire。Session 首条 generate_image 比后续慢得多。**方向：** 各 container 在 `init {}` 里挑 default provider 的 `/models` 探针（廉价 GET）异步 kick 一次；warmup 历史照常进 ProviderWarmupStats。Rubric §5.7。Milestone §later.
- **bus-trace-cli-slash** — cycle-100 加了 `session_query(select=bus_trace)`；CLI 用户每次手敲 query 麻烦。**方向：** `/trace [kind=]` slash 命令打印当前 session 最近 N=20 行；继承 `/permissions` 风格。`SlashCompletion` 给 kind 候选（`PartDelta` / `MessageUpdated` / …）。Rubric §5.4 / cli。Milestone §later.
- **debt-consolidate-project-lifecycle-tools** — `core/tool/builtin/project/` 有 20 个 *Tool.kt，其中 6 个是单 verb lifecycle (CreateProject / OpenProject / DeleteProject / RenameProject / SetOutputProfile / RemoveAsset)。每个工具一份 helpText + schema + dispatch 是 LLM context 累计代价。**方向：** 复刻 `clip_action` 模式，折成 `project_action(action=create|open|delete|rename|set_output_profile|remove_asset)` 一个工具；同步删 6 个旧工具。净 tool count -5。Rubric §5.6 / §5.7 / §3a-1。Milestone §later.
- **compaction-strategy-prune-only** — `Compactor` 当前默认 summarize-old (LLM call + token cost)。某些 session（仅 tool-heavy，prose 极少）summarize 没意义；只 prune 最老的 `Part.Tool` results 就够了。**方向：** `CompactionStrategy.PruneToolsOnly` 选项，从 oldest 开始按 `estimatedTokens` 删 `Part.Tool` 直到回到阈值；不调 LLM。`set_compaction_strategy` 或 session metadata 切换。Rubric §5.6 / §5.7。Milestone §later.

## P2 — 记债 / 观望

- **debt-todo-fixme-baseline-32** — R.5.6 scan：32 TODO/FIXME/HACK 出现点在 core/commonMain，跨 7 个 repopulate 周期稳定。**方向：** 继续观察；下次 repopulate > 32 → 升 P1 + 列新增行号。**触发条件：** 下次 repopulate delta > 0。Rubric §5.6。Milestone §later.
- **debt-tool-count-net-growth-stable-84** — R.5.1 scan 84 tools；连续 7 cycle 稳定。**方向：** observational；触发 → 追近似群分析。**触发条件：** 下次 repopulate count > 86。Rubric §5.6。Milestone §later.
- **debt-long-file-creep-watch-422** — 当前最长 SessionActionTool 422 LOC（已有专项 P1 split bullet），第二是 ProviderQueryTool 403 LOC。距 500 LOC 硬阈值都还有 ≥78 headroom。**方向：** observational。**触发条件：** 下次 repopulate 任一文件 ≥ 500 LOC。Rubric §5.6。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。**方向：** 拆 sub-files。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy；`BundleCrossMachineExportSmokeTest` inline 12 行 helper（grep 仍只 1 caller）。**方向：** 第二个 caller 出现时 extract 到 `BundleFsTestKit`。**触发条件：** 第二 caller 出现。Rubric §5.6。Milestone §later.
- **desktop-live-render-preview** — Desktop 只在 timeline 上看 clip 变绿 + 静态 thumbnails，没有实时 render preview。**方向：** Compose Desktop + Media3/ffmpeg bridge 驱动 playback surface；subscribe `BusEvent.AigcJobProgress` 把 clip-ready 实时替换进 preview。**触发条件：** Desktop 达 CLI parity 的平台优先级窗口开。Rubric §5.4 / desktop。Milestone §later.
- **cli-cancel-indicator-tools** — Ctrl-C 显示 "(cancelling — Ctrl+C again to force quit)"，但不报正在 abort 哪些 tool。`cancellation_history.inFlightToolIds` 已有数据。**方向：** Ctrl-C 触发后多打一行 "aborting: generate_image, synthesize_speech"。**触发条件：** operator 反馈或具体场景。Rubric §5.4。Milestone §later.
- **re-evaluate-m2-provider-second-impl** — `m2-provider-second-impl` 跨 7+ repopulate 周期连续 skip-tagged（"需专有 API key + vendor 决策"），符合 §R skip-≥3-cycles 元 bullet 规则。M2 退出仅卡这一条 criterion。**方向：** 用户决定 promote (给定 vendor + key) / demote (M2 改"single provider acceptable") / delete (criterion 不再相关)。Rubric §5.7 / §5.2。Milestone §M2. · skipped 2026-04-24: meta bullet awaiting user decision (no agent action possible).
- **debt-split-bus-event-kt** — `BusEvent.kt` 394 LOC（cycle 102 scan: 394，stable since cycle 99），sealed interface 27 子类。Past 阻塞: 464 `BusEvent.X` call sites。**方向：** 当 BusEvent.kt 真正逼近 500 LOC 时，atomic commit: 子类全数 top-level data class、覆盖每个 `BusEvent.X` → `X` 用 `perl -i`。**触发条件：** BusEvent.kt 行数达 480。Rubric §5.6。Milestone §later. · skipped 2026-04-24: trigger not yet met (file at 394; 86 LOC headroom).
- **aigc-result-multi-variant** — `generate_image` / `generate_video` 每次产 1 个 asset；OpenAI / Replicate 都支持 `n` 参数返 N 个候选让人挑。当前 agent 拿不到。**方向：** AIGC tools 接收 `n: Int = 1`，返回 List<assetId>；lockfile 每个变体一条 entry，共享 inputHash + 不同 variantIndex。Permission ASK 一次性覆盖 N 次成本。Rubric §5.2。Milestone §later.
