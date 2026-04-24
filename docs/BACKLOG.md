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

- **debt-video-clip-consolidate-verbs-phase-3** — phase 2（2026-04-24）落地 Move+Split+Trim 后 ClipActionTool 已是 938 LOC，保留 `ReplaceClipTool` + `FadeAudioClipTool` 未并。naive fold 的全 5 合到 1 曾测得 1285 LOC（超过 950 cap）。**方向：** 把 Replace + Fade 并入 `ClipActionTool` 的 `action=replace|fade`（+~150 LOC 每个动作 = ~300 LOC），同时评估是否应**先**按轴拆 ClipActionTool（clip-create vs clip-mutate）再合 —— 若选择不拆，合入后 ClipActionTool ≈ 1100–1200 LOC，需要显式放宽 cap 或接受审阅偏大。**触发条件：** 平台优先级窗口下 Core 仍然是瓶颈 & debt-video-clip-consolidate-set-family 有进展，或 tool_spec_budget 又逼近上限。Rubric §5.6 / §5.7。Milestone §later.
- **debt-video-clip-consolidate-set-family** — `SetClipVolumeTool` / `SetClipTransformTool` / `SetClipSourceBindingTool` 三件 `set_clip_<field>` 同形状工具。**方向：** 合成 `ClipSetActionTool(field=volume|transform|sourceBinding)`，payload 用 nullable 字段 per field；删 3 个老 tool；注册 + prompt 同步。（`EditTextClipTool` 独特 — text style 字段 distinct，暂保留。）Rubric §5.6 / §5.7。Milestone §later.
- **debt-source-rename-evaluate** — `RenameSourceNodeTool` 338 LOC 单独重 —— 其实大部分行是 parent-ref 修复 + history bookkeeping。**方向：** 先评估能否拆出 `findAndRewriteParentRefs(source, oldId, newId)` helper 到 `Source` 包内（~100 LOC 纯函数）后，Rename 主体剩 ~200 LOC。若可拆，合入 `SourceNodeActionTool`；若不可拆，正式归档为"独立 tool 是最干净的解"。`debt-source-consolidate-add-remove-fork` 落地后再评。Rubric §5.6。Milestone §later. · skipped 2026-04-24: depends on debt-source-consolidate-add-remove-fork first.
- **debt-clip-render-cache-map-lookup** — `ClipRenderCache.findByFingerprint` 是 O(N) 线扫 `entries: List<ClipRenderCacheEntry>`。N 当前几百内，benchmark 已显示 1–2 ms/op lookup 在 stub 引擎下淹没 no-op render 收益。**方向：** `entries` 改 `Map<String, ClipRenderCacheEntry>`（fingerprint key），"later entry wins" 靠 `put` 覆盖保留。**触发条件：** profiling 显示 `findByFingerprint` ≥ 5% export wall time on 深 cache 项目。Rubric §5.7。Milestone §later.
- **debt-tool-spec-budget-ceiling-tight** — `tool_spec_budget` 当前 22_518 / 22_600 ceiling。剩余 82 tokens = 一个新 select / 一段 helpText 就会破。`registeredToolsSpecBudget` ratchet gate 此时变脆。**方向：** 监控态 bullet — 破时先辩护必要性，再决定整体 ratchet +500 或砍某条 helpText。别盲目 ratchet；cycles 4f50e5ce/6b7ef021 的 trim 习惯要留。Rubric §5.7。Milestone §later.
- **recents-registry-list-summaries-index** — `RecentsRegistry.listSummaries()` 每次调用都扫所有 entry + decode 每个 `talevia.json` envelope。N ≥ 数百项目时 per-call latency 可感。**方向：** registry self-cache `(title, updatedAtEpochMs, createdAtEpochMs)`；envelope 写同步 cache；`listSummaries` 读 cache，跳 bundle I/O。**触发条件：** profiling 显示 `listSummaries` ≥ 50 ms on real bundles。Rubric §5.3 / §5.7。Milestone §later.
- **bundle-mobile-document-picker** — Android / iOS 限于 app-sandbox bundle。无法从 SAF / Files.app 拿外部 bundle。**方向：** Android 接 `Intent.ACTION_OPEN_DOCUMENT_TREE`；iOS 接 `UIDocumentPickerViewController`；两端经平台 resolver 翻成 Okio `Path`，`FileProjectStore.openAt` 不改。**触发条件：** CLAUDE.md 平台优先级窗口把 mobile 从"不退化"baseline 推到主动开发。Rubric §5.4 / mobile。Milestone §later.
- **set-session-preferred-provider** — `ProviderRegistry.default` 确定性（AppContainer 构造顺序决定）。session 想 Anthropic-default 只能靠 AppContainer 的顺序巧合。**方向：** `set_session_preferred_provider(providerId)` tool 把偏好存 `Session`；`Agent.run` 先查该字段再 fall through `default`。Fallback chain 不改。**触发条件：** operator 报告 AppContainer 默认对某 session 不合适。Rubric §5.2。Milestone §later.

## P2 — 记债 / 观望

- **debt-aigc-tool-consolidation** — `aigc/` 下 7 个 tool，前 4 个是 "generate/synthesize" 家族同构。**方向：** 评估 `AigcActionTool(kind=image|video|music|speech, ...)` 折叠可行性；保留 `ReplayLockfile` / `UpscaleAsset` / `CompareAigcCandidates` 独立。谨慎：fold 会让 I/O 类型膨胀成 sum。**触发条件：** aigc/ 再长出一个 generator（第 5 个）或 per-turn tool spec 破 25k。Rubric §5.6 / §5.7。Milestone §later.
- **bundle-talevia-json-split** — `talevia.json` 一文件装 timeline + assets + source DAG + lockfile + snapshots。单次 mutation diff 可达 100+ 行；snapshot 未来可能 MB-scale。**方向：** 拆 `assets.json` / `timeline.json` / `lockfile.json` / `snapshots/<id>.json` 子文件；`talevia.json` 只剩 schemaVersion + metadata + sub-file manifest。**触发条件：** 真实用户报告 diff 噪音或 snapshot ≥ 1 MB。Rubric §3a-3。Milestone §later.
- **debt-unified-dispatcher-select-plugin-shape** — 每个新 select 于 `project_query` / `session_query` / `source_query` / `provider_query` 触 7–8 个协调点。Reject-matrix 成本 `O(n_selects × n_filters)`。**方向：** "select plugin" 形状 —— 一文件一 select，`@AppliesTo(SELECT_FOO)` annotation 驱动 filter applicability。**触发条件：** 任一 dispatcher ≥ 20 selects 或 `rejectIncompatibleFilters` ≥ 30 rules。现状：project 14 / session 17 / source 6 / provider 3。Rubric §5.6 / §5.7。Milestone §later.
- **debt-desktop-missing-asset-banner** — Cycle-3 落 CLI-side `BusEvent.AssetsMissing` warning 渲染。Desktop 的 SnapshotPanel / ExportPanel 还没视觉化同一 warning。**方向：** Desktop AppContainer 订阅 bus event；SnapshotPanel / ExportPanel 在 export 按钮旁渲一条 warning banner（count + 最多 5 条 path + 溢出 tail）。**触发条件：** Desktop 达 CLI parity（CLAUDE.md 平台优先级）。Rubric §5.4 / desktop。Milestone §later.
- **debt-bundle-fs-testkit-copy-recursive** — Okio `FakeFileSystem` 缺递归 copy。`BundleCrossMachineExportSmokeTest` inline 了 12 行 `copyDirectoryRecursive(fs, src, dst)` helper。**方向：** 第二个 bundle-level 测试需要同一 walker 时，extract 到 `ProjectStoreTestKit` 或新 `BundleFsTestKit`；N=1 不提前抽象。**触发条件：** 第二 caller 出现。Rubric §5.6。Milestone §later.
- **source-node-diff-restore-composite-tool** — P0 `update_source_node_body(restoreFromRevisionIndex=N)` 已落（4a104c19）。用户会想要 per-field merge-restore。**方向：** `merge_source_node_body_from_history(nodeId, revisionIndex, fieldPaths: List<String>)`。**触发条件：** operator 要 per-field granularity。Rubric §5.5。Milestone §later.
- **cli-metrics-slash-command** — `CounterRegistry` 暴露 counters 只通过 server 的 `GET /metrics`。CLI operator 无法 in-process 看 live count。**方向：** CLI `/metrics` slash-command 渲染当前 counters 为 2-列表。**触发条件：** operator 反馈。Rubric §5.4 / §5.6。Milestone §later.
- **cli-revoke-permission-command** — `permission-rules.json` 持久化已落；operator 无 in-REPL revoke verb。**方向：** CLI `/revoke-permission <permission> [pattern]` slash-command；drop 匹配 rule + re-save。**触发条件：** operator 反馈手改 json 是 friction。Rubric §5.4。Milestone §later.
- **desktop-permission-rules-persistence** — Desktop `PermissionDialog.kt` "Always" 仍 append 到 in-memory 未持久化。**方向：** 挂 `FilePermissionRulesPersistence` + dialog callback 调 `save()`。**触发条件：** Desktop 达 CLI parity。Rubric §5.4 / desktop。Milestone §later.
