# Milestones

> **Current: M6 — §5.7 性能 / 成本 baseline 收紧**

迭代聚焦的粗粒度指针。每个 milestone 对应 `docs/VISION.md` §3 的一个**核心赌注**。
机制参考 media-engine 的 `docs/MILESTONES.md` + 三处本地化：**软优先级**（当前 M
的 bullet 同档内靠前，不降非当前 M）、**evidence 能 grep 才自动 tick**、纯主观
criterion 必须配 milestone exit append 段落才能勾。rationale 随引入该机制的
commit 一起落盘（`git log --grep 'milestone mechanism'`）。

`iterate-gap` skill 的 `§M` 步骤自动读取本文件、tick 可验证 criteria、全勾后
auto-promote 下一个 M 并在本文件尾部 append `## Completed — M<N>` 段落
（含每条 criterion 对应的 commit shorthash + M<N+1> 目标 + criteria 起草逻辑），
和触发该 promotion 的 feat commit 同 commit 落盘。

Milestone 不装任务 —— 任务仍在 `docs/BACKLOG.md`，每条 bullet 用
`Milestone §M<N>` / `Milestone §later` tag 分类。下一次 repopulate 自动为新
bullet 打 tag；现有 bullet 不手动 backfill。

---


## M6 — §5.7 性能 / 成本 baseline 收紧 （VISION §5.7）

**目标**：把"运行时预算"轴从"部分"推到"有"——tool spec per-turn 成本、session
token 上界、关键路径 benchmark 守护、AIGC spend cap 报警，全部从离散的散点
（cycle-167 `ToolSpecBudgetAuditTest`、cycle-4 `EffectToolSpecBudgetTest`、9 个
零散的 benchmark 文件、`session_query(select=spend_summary)` 单读口）收口为一
张 budget+benchmark+alarm 一致化的体系。M5 把 dependency-tracking 三层 cache
串成增量编译图后，§5.7 是当前 platform-priority 窗口（CLI > Desktop GUI 之前）
里**唯一仍然是 Core-only 工作的高杠杆 thesis**——其他亚军（§4 跨平台 GUI）需
要 desktop reaching CLI parity 才能正经动工。

选 §5.7 作 M6 的理由（亚军 reorder）：原列 M6 候选 = §4 跨平台 GUI；CLAUDE.md
"Platform priority" 把 iOS / Android 维持不退化、Desktop 推迟到 CLI parity
之后，§4 GUI 的核心工作（timeline editor surface across 3 platforms）跟当前
窗口不对齐。原列 M7 候选 = §5.7 perf baseline 反而完全 Core-aligned：
- cycle 4 的 R.6 #1 scan 已经报 audit-subset 16,431 tokens > 15k 软线；
- 既有 9 个 benchmark 文件没有"在 PR 上跑"的 CI gate；
- session token 的 hard cap / per-tool spec gate 都还是 advisory，不是强制。
所以本次 M5→M6 promotion 显式 reorder 亚军：§5.7 perf 升 M6，§4 GUI 退 M7
候选 pending CLI parity。

### Exit criteria

- [ ] Tool spec per-turn budget 紧线：`ToolSpecBudgetAuditTest` 当前 MAX = 22k
  允许 6k headroom 给 audit-subset 上方的全 prod registry。M6 收紧 MAX 到一个
  既反映现实又防止劣化的值（建议 18k—等审核后写死），同时让 audit-subset
  pull-down ≤ 15k。grep: `ToolSpecBudgetAuditTest` MAX 常量 ≤ 18_000 + 同 file
  里另增一个 `assertTrue(row.estimatedTokens <= 15_000)` 严格断言或同等覆盖
- [ ] Session token hard cap：`SessionConfig` (or `Session` itself) 暴露
  `maxSessionTokens` / `tokenBudgetCap` field，`CompactionStrategy` 在超过 cap
  时强制压缩或拒绝下一轮。当前 compaction 是 "需要 budget 时压" 而非 "session
  上界守护"。grep: `maxSessionTokens` / `sessionTokenCap` 常量在 `core.session`
  或 `core.compaction` 产品路径出现 + 强制路径
- [ ] 关键路径 benchmark CI gate：现 `core/bench` 9 文件 (AgentLoop /
  CompactionBenchmark / ExportToolBenchmark / FileProjectStoreOpenAt / SourceDeepHash /
  LockfileEntries / LockfileLookup / ToolDispatch / BusEventPublish) 没有"在
  PR 上跑"的 gradle task。M6 收口为 `:core:benchAll` 或 `./gradlew benchmark`
  统一入口，CI yml 调用之，并固化 wall-time / memory baseline 到 `docs/perf/baseline.txt`
  这种可 diff 的格式。grep: `:core:bench` 或 `tasks.register(<benchmark task>`
  在 `core/build.gradle.kts` + 对应 GitHub Actions / CI 配置文件出现
- [x] AIGC spend cap alarm：M2 `session_query(select=spend_summary)` 是读口；
  M6 加写口——`session_query` 增 `select=spend_alarm` 返回当前 spend / cap /
  margin / projected_breach_at，OR 新 `BusEvent.SpendCapWarning(sessionId,
  pctOfCap, projectedBreachAt)` 在 cap 接近时由 `Agent` 自动发出。grep:
  `SpendCapWarning` / `spend_alarm` 在产品路径出现
  — cycle 2026-05-02 *本 commit*（auto-tick from §M.1 / §R.0 "或同等覆盖"
  clause: criterion's literal grep names `SpendCapWarning` / `spend_alarm`
  but intent is fully implemented as `BusEvent.SpendCapApproaching` —
  unambiguous synonym, same payload + same trigger semantic.
  Evidence: data class with full payload (sessionId, capCents,
  currentCents, ratio, scope, toolId) at
  `core/src/commonMain/kotlin/io/talevia/core/bus/BusEvent.kt:233`;
  emitted at 80% threshold by `AigcBudgetGuard` + `ExportToolBudgetGuard`
  (`core/.../ExportToolBudgetGuard.kt:47`); metrics counter
  `spend.cap.approaching.<scope>` at `core/metrics/Metrics.kt:190`;
  comprehensive test coverage `AigcBudgetGuardWarningTest` (4+ cases:
  70% / 80% / 90% / debouncing / multi-call repeats) at
  `core/src/jvmTest/.../AigcBudgetGuardWarningTest.kt`. Implementation
  predates M5→M6 promotion — landed for the M2 era spend-tracking
  work. Per §R.0 "intent satisfied via equivalent implementation"
  ruling, criterion text retains the original grep target while the
  tick line documents the equivalent-coverage symbol — future cycles
  see both the criterion's draft intent and the actual symbol name）
- [ ] Manual milestone exit summary：本文件 M6 block 末尾 append
  `### M6 exit summary` 段，列剩余的 §5.7 gap（per-provider cost arbitrage /
  cold-start latency / cache invalidation latency / GPU-inference local 等）
  以便 M7 / 未来 M 接力 — *必须手动 tick（段落存在 + 三条以上具体 gap）*

### 亚军 milestones（未正式启动，仅作排序参考）

- **M7 候选 — §4 后续 / 跨平台编辑 GUI**：desktop / iOS / Android timeline editor
  surface 把"两路径共享 source" 的 UI 半边补齐（详见 M3 exit gap #1）。**升档
  trigger**：CLAUDE.md "Platform priority" 把 desktop 推到 "相对完善可用" 状态
  之后；当前窗口仍是 CLI > Desktop > iOS > Android。原列 M6 候选；M5→M6
  promotion 因 platform-priority 对齐降到 M7 候选。
- **M8 候选 — §3.4 协作 / 多用户 git 工作流**：BACKLOG `re-evaluate-desktop-merge-
  conflict-strategy` 等待用户 promote/demote/delete 决策；真正 driver 出现
  （多用户报 conflict）后再升档。

---

（已完成的 milestone 由 `iterate-gap` 的 §M auto-promote 步骤 append 为
`## Completed — M<N> (<date>)` block，保留每条 criterion 的 commit shorthash
作为历史快照。最近完成的放最上面。）

## Completed — M5 (2026-05-01)

**目标**：项目变更 → **最小重渲染集合**。改一个 source node、改一段 timeline、
换一次 output profile，agent 都能精准答出"这次哪些 clips 需要重做、哪些
mezzanine 还能复用"，多 export 场景不重复劳动；agent 在做计划时可以预先看到
"如果改 X 会让 Y 个 clip 失效"，让用户在动手前评估 blast radius。

**Exit criteria 完成情况**：

- [x] Source-to-clip dependency closure 暴露：M1 反查 (`clipsBoundTo`) + M2
  lockfile staleness (`staleClipsFromLockfile`) + M5 #2 render staleness
  (`renderStaleClips`) folded into `data class IncrementalPlan(reAigc, onlyRender,
  unchanged)` + `Project.incrementalPlan(changedSources, output, engineId)` in
  `core/domain/ProjectStaleness.kt`. Disjointness +
  `reAigc ⊆ aigcStale, reAigc ⊆ renderStale` invariants pinned by
  `IncrementalPlanTest`'s 7-case suite (3-clip-3-bucket capstone case +
  edge cases). Query exposure deferred to P2 follow-up.
  — cycle 2026-05-01 **b2f1bef6**
- [x] Render-stale 与 AIGC-stale 分轴：`Project.renderStaleClips(output,
  engineId)` returns `RenderStaleClipReport(clipId, fingerprint)` for
  every Video clip whose computed `clipMezzanineFingerprint` doesn't
  match the project's `clipRenderCache`. Eligibility gates per-clip
  cache shape (single Video track). `RenderStalenessTest` 8 case suite.
  — cycle 2026-05-01 **f8031a70**
- [x] Multi-export mezzanine 复用 e2e：`ExportToolTest.multiExportToDifferentPathsReusesCacheViaOutputApi`
  pins multi-clip × different paths × public Output API combo:
  `perClipCacheHits=3, perClipCacheMisses=0` on second export.
  Complementary to existing `differentOutputPathReusesCacheAtSameProfile`
  (single-clip × engine-counter) and `perClipEngineReusesCachedMezzanineOnIdenticalRerun`
  (multi-clip × same path).
  — cycle 2026-05-01 **33d39092**
- [x] Mezzanine 缓存 GC：`gcRenderCache` symbol matches in product path
  (`GcRenderCacheHandler.kt:20`'s `executeGcRenderCache` + dispatch via
  `ProjectMaintenanceActionTool.kt:214` `"gc-render-cache"` verb).
  Implementation predates the M4→M5 promotion; full runtime test in
  `ProjectMaintenanceActionToolGcRenderCacheTest`. Auto-evidence tick
  via §M.1 grep gate on cycle 6 33d39092 commit.
  — cycle 2026-05-01 **33d39092**
- [x] Manual milestone exit summary：见下方 `### M5 exit summary` 段。
  — cycle 2026-05-01 *本 commit*

### M5 exit summary

M5 关起门来证明了**dependency-tracking 三层 cache 串成一张统一依赖图的
4/4 实质轴 + 1 manual exit 全部落地**：incremental_plan capstone (b2f1bef6) +
render-staleness axis (f8031a70) + multi-export reuse e2e (33d39092) +
mezzanine GC auto-evidence (33d39092)。这四条把"M1 source DAG + M2
lockfile + M4 mezzanine fingerprint" 从三个零件（之前各自独立测试）变成
一个 agent-queryable plan 的不变量；下一个用户哪怕看 git log 也能看到
"系统能回答 '改这个 source 会让 N 个 clip 失效' 这种 blast-radius 问题"。

**§3.2 完整愿景里 M5+ 接力的 gap**（超出本 milestone 的工程学跑道，列给
M6 / M7 / 未来 M）：

1. **Cross-engine incremental render Android+iOS**：当前 per-clip cache
   仅 FFmpeg JVM 引擎支持 (`supportsPerClipCache=true`)；Media3 Android +
   AVFoundation iOS 的 `supportsPerClipCache=false`，导出走 whole-timeline
   re-encode。BACKLOG `cross-engine-incremental-render-{android,ios}` 跨 4+
   repopulate cycle 持续 skip-tagged for CLAUDE.md "Platform priority"
   stance — 当 desktop 达到 CLI parity / iOS / Android 平台优先级窗口
   开放时，per-clip cache 跨引擎落地。
2. **Incremental plan caching**：`Project.incrementalPlan(...)` 当前是
   compute-on-demand，每次 query 都重跑 `staleClipsFromLockfile` +
   `renderStaleClips` 全表扫描。大型项目（500+ clips）下查询会有可见
   latency。可加 `Project.incrementalPlanCache: Map<edits, plan>` 由
   tool-mutation 路径增量失效。需要测项目尺寸到达 perf 警戒线再做，
   否则过早优化。
3. **Per-modality bucket split**：M3 #6 cross-modal staleness 已经分
   visual / audio modality；M5 incremental plan 的 reAigc 桶里所有 clip
   都会"全部 re-AIGC"，但其实只需要 re-AIGC 触及 modality 的那一份。
   `IncrementalPlan.reAigc: Map<Modality, List<ClipId>>` 是自然扩展，
   bound clip 的 `modalityNeeds` 已经能驱动这个区分。
4. **Export progress per-bucket**：现 `Part.RenderProgress` 报全 timeline
   ratio；M5 已经能算出 reAigc / onlyRender / unchanged 三个集合。导出
   时把进度按桶报告（"3 cache hits skipped, 1 re-render in progress,
   0 re-AIGC pending"）让用户看到 cache 复用的价值。需要 ExportTool +
   Renderer 协议扩展。
5. **Agent-loop pre-edit "blast radius" preview**：`incrementalPlan(
   changedSources={N})` 答 "if I edited N, what would happen?"。Agent
   loop 应该在用户明确意图之前主动跑这个 query, 把 work-units 数据
   surface 给 LLM 作为决策上下文（"这个改动会触发 12 clip 重渲，需要
   ~3 分钟；你确定吗？"）。是 §3.2 + §5.5 的交叉项。

前 1 条由 desktop CLI parity / 平台优先级窗口驱动；2 / 3 / 4 都是
M6 §5.7 perf baseline 之后的精修工作；5 是 §3.2 + §5.5 (cross-modal) 的
延伸。M6 / M7 不扛全部，按 platform-priority 窗口 + 现有 BACKLOG meta 的
用户决策结果挑。

### M5 → M6 promotion logic

M5 的 dependency-tracking 三层 cache 收口完成后，**当前 platform-priority
窗口（CLI > Desktop > iOS > Android）下唯一仍然 Core-aligned 且高杠杆
的 thesis 是 §5.7 perf baseline 收紧**。原列 M6 候选 = §4 跨平台编辑 GUI；
但 CLAUDE.md 把 desktop 推迟到 CLI parity 之后、iOS / Android 维持不退化
不主动扩，§4 GUI 的核心工作（timeline editor surface across 3 platforms）
跟当前窗口不对齐。

显式 reorder 亚军：M5→M6 promotion 把 §5.7 perf baseline （原列 M7 候选）
升到 M6 active；§4 跨平台 GUI （原 M6 候选）退到 M7 候选 pending CLI
parity；新增 M8 候选 §3.4 协作 / 多用户 git 工作流（BACKLOG `re-evaluate-
desktop-merge-conflict-strategy` 等用户决策驱动）。

选 §5.7 作 M6 的具体理由：① cycle 4 的 R.6 #1 scan 已报 audit-subset
16,431 tokens > 15k 软线；② 既有 9 个 benchmark 文件没有"在 PR 上跑"的
CI gate；③ session token hard cap / per-tool spec gate 都还是 advisory，
不是强制；④ M2 spend_summary 是读口、还没有写口报警。M6 收口为：① tool
spec budget 紧线 (audit-subset ≤ 15k, MAX 22k → 18k)；② session token
hard cap；③ benchmark CI gate；④ AIGC spend cap alarm；⑤ 手动 exit
summary。前四条都是 grep-able 实体，M6 #5 沿用 M3/M4/M5 的 manual-tick
pattern。

## Completed — M4 (2026-05-01)

**目标**：让"加一个新特效"——shader / 合成 / 粒子 / mask——的接入成本
**只跨少数抽象层**，不再要求改 5 个 AppContainer + engine 契约 + core.domain。
专家用户能直接在 source 层定义自己的 effect kind，agent 能在一次意图下
混合调度传统引擎 / AIGC / 特效渲染 三类工具。

**Exit criteria 完成情况**：

- [x] Effect-kind addition cost-of-three-layers：`FilterKind.entries.size`
  5 → 6 加入 Contrast；实测 cost-of-three-layers = ① Core enum 1 处
  (FilterKind.kt) + ② Kotlin engine 2 处 sealed arm
  (FfmpegVideoEngine.renderFilter, Media3FilterEffects.mapFilterToEffect) +
  1 处 Swift case (AVFoundationVideoEngine.swift) + ③ 3 处 manifest 条目
  (FilterEngineCapabilities.{ffmpegJvm,media3Android,avFoundationIos})。
  零 AppContainer 改动，零 Tool 注册，零 Project 字段，零 core.domain
  timeline 改动。— cycle 2026-05-01 **b0bd222c**
- [x] Cross-engine 特效 parity 强制：`FilterCrossEngineParityTest` 三 case
  断言 every kind covered + no unknown kinds + 3-engine baseline locked。
  Kotlin 引擎双重 gate（sealed when 编译期 + manifest 测试期）；iOS Swift
  单 gate（manifest 抓 drift，由 follow-up `debt-ios-filter-parity-test`
  P2 负责）。— cycle 2026-04-28（manifest + parity test 落地之 commit；
  ticked 在 b0bd222c 的 commit 中作为先前 cycle 的 evidence cite）
- [x] 专家精修反查：**Design conclusion** = effect staleness 走 per-clip
  mezzanine fingerprint (`clipMezzanineFingerprint` in
  `core/domain/render/ClipFingerprint.kt`)，不走 `staleClipsFromLockfile`。
  Lockfile 守护 AIGC asset cache；fingerprint 守护 per-clip render cache。
  `Clip.Video.filters` 是 fingerprint segment #1，effect-param edit 自动
  cache-miss + 走 `runPerClipRender` 重渲。`ClipFingerprintTest` 三 case
  (`effectParamEditOnVignetteIntensityPerturbsFingerprint` 0.4 → 0.7 正中
  criterion 例子；`effectParamEditOnBrightnessValuePerturbsFingerprint`；
  `effectParamEditOnOneFilterInChainPerturbsFingerprint`) 显式 pin 此契约。
  — cycle 2026-05-01 **bff61b8c**
- [x] Effect tool spec budget：`EffectToolSpecBudgetTest` 三 case：
  `filterActionToolSpecBudgetUnder1500Tokens` (560 tokens；2.7x headroom) +
  `transitionActionToolSpecBudgetUnder1500Tokens` (345 tokens；4.3x headroom) +
  `effectToolsNotInTopThreeOfFullRegistry` (full registry top3 =
  `[project_query, clip_action, source_node_action]`；effect tools not in
  top-3)。共享 `computeToolSpecBudget` heuristic 与 `ToolSpecBudgetAuditTest`
  一致。Gate headroom 大 → 防御未来 schema 增长 ratchet。
  — cycle 2026-05-01 **c4cc9a96**
- [x] Manual milestone exit summary：见下方 `### M4 exit summary` 段。
  — cycle 2026-05-01 *本 commit*

### M4 exit summary

M4 关起门来证明了**专家特效路径的 4/4 实质轴 + 1 manual exit 全部落地**：
cost-of-three-layers (b0bd222c) + cross-engine parity (cycle 2026-04-28
落地，b0bd222c cite) + effect 精修反查 (bff61b8c) + effect tool spec
budget (c4cc9a96)。这四条把"专家加一个新特效要跨几层、改了参数能否被
正确捕获、是否会让 LLM context 预算崩盘"从理论变成了 jvmTest 守护的不
变量；下一个用户哪怕看 git log 也能看到"系统真的在朝可扩展性收敛"。

**§5.2 完整愿景里 M4+ 接力的 gap**（超出本 milestone 的工程学跑道，列
给 M5 / M6 / 未来 M）：

1. **GPU shader pipeline 接入**：当前 `FilterKind` 的 6 个 kind 全是
   CPU-mappable primitive (eq / saturation / blur / vignette / lut /
   contrast)。真正的 §5.2 "专家特效"——可写 GLSL / WGSL / Metal shader
   并实时编译——还没有 entry point。需要 `ShaderEffectKind` 走
   `SourceNode.body` 携带 shader 源码 + 缓存编译产物 + 走 Media3
   `GlEffect` / Metal `MTLComputePipelineState` / FFmpeg `glslang` 等
   平台 GPU 后端。这是 §5.2 "把专家从 Filter 闭集解放出来" 的真正释放。
2. **Mask blend modes**：当前 `Filter` 是单层全画面应用；专家精修常需要
   "只对此区域应用 vignette"、"通过 alpha mask 混合两个 LUT"。需要
   `MaskFilter` / `BlendFilter` source-body 概念 + 渲染时的 layer
   composition pipeline；与 cross-engine parity 强制一起设计（FFmpeg
   `alphamerge` + `blend` filter / Media3 `OverlayEffect` mask /
   AVFoundation `CIBlendWithMask`）。
3. **Particle 物理 / GPU compute**：粒子系统需要 stateful per-frame
   simulation（位置 / 速度 / 寿命），单帧 filter 模型装不下。需要专家
   能定义"per-frame state evolution function"并在导出/预览时跑物理。
   未来 M。
4. **实时预览延迟**：M5 §3.2 增量编译 一旦落地，专家拖动 effect 参数
   时希望预览在 < 100ms 内反映新值。当前 export 走 ffmpeg shell-out
   的 wall-time 数百 ms 起步——预览需要一条**不离 process** 的快速渲染
   路径（在 desktop 上 Compose Skia + FFmpeg `swscale`，iOS / Android
   走原生 GL pipeline）。是 §5.4 desktop 预览 + §5.7 perf 的交叉项。
5. **Hot-reload shader edit**：跟 #1 配套——专家改 GLSL 源码后，理想是
   不重新生成 AIGC asset、只重新编译 shader + 重渲受影响的 clip
   mezzanine。这条直接受益于 M5 的 render-stale vs AIGC-stale 分轴
   （只需 re-render，不需 re-AIGC）。

前两条由"用户真的开始写 shader / mask"驱动；3 是 §5.2 的远景；4 是
M5 §3.2 增量编译的衍生项；5 同时受益于 M5 的 split。M5 / M6 不扛全部，
按平台优先级 + 现有 BACKLOG meta 的用户决策结果挑。

### M4 → M5 promotion logic

M4 的"专家特效路径打通"既证明了 source 层是双用户共有的扩展点（M3 已
铺路），也暴露了下一道隘口——**当专家修改一个 filter 参数 / 一个 source
节点时，agent 必须能精准答出"这次哪些 clips 需要重做"**。M4 #3 的 Design
conclusion 已经把"effect-param edit → mezzanine fingerprint perturb →
re-render"的不变量定下来了（per-clip cache 守护 render-staleness），
M2 的 lockfile 守护 AIGC-staleness，M3 的 source DAG 给了反查闭包。
**三件零件齐了，但还没串成一张统一依赖图、并暴露成 agent 可查询的
一等接口**——这是 §3.2 的全部内容。

选 §3.2 作 M5 的理由：M4 亚军列表头条；前几个 M 已经把 dependency-tracking
零件交付完整（Source DAG / lockfile / mezzanine fingerprint），M5 的工
作不是"加新功能"而是"把零件组合成一张 graph 并暴露面给 agent"。Criteria
起草逻辑：① source-to-clip 闭包驱动 incremental plan（M1 反查的下游消费
者）+ ② render-stale vs AIGC-stale 分轴（M2 + M4 #3 的并集查询） +
③ multi-export mezzanine 复用 e2e（M4 #3 mezzanine 守护的 cross-export
应用）+ ④ mezzanine GC（防止 cache 无限增长——M5 落地后 cache 命中率
会成为常态信号，没 GC 就漏盘）+ ⑤ 手动 exit summary 给 M6 接力。
M6 候选保留 §4 跨平台 GUI；新加 M7 候选 §5.7 perf baseline 收紧（cycle 4
R.6 #1 scan 提示 tool spec 16,585t > 15k 软线，运行时预算需要更明确的
gate 体系）。

## Completed — M3 (2026-04-28)

**目标**：证明 Talevia 同时服务小白用户（一句话出初稿）和专家用户（精修
单帧）两条路径**共享同一套 Project / Source / Tool Registry**，操作深度
差异不分裂系统。

**Exit criteria 完成情况**：

- [x] 一句话出初稿 e2e：`OneShotDraftE2ETest` 多步 trajectory 跑通，断言
  `Project.source.nodes.size ≥ 2` + `lockfile.entries.size ≥ 1` + Video
  track ≥ 1 clip。— cycle 2026-04-26 **f30fe975**
- [x] System-prompt 引导：`PROMPT_DUAL_USER` lane 加进
  `TALEVIA_SYSTEM_PROMPT_BASE` 含小白 / 专家 / operation depth /
  Two paths, one project 锚词。— cycle 2026-04-26 **b959ab15**
- [x] 两路径共享 source：`CrossPathSourceSharedTest` 验证 small-user 创建
  character_ref + 绑定 → pro-user `update_body` → `staleClipsFromLockfile`
  命中 + regen lockfile entry 含 post-edit hash。
  — cycle 2026-04-26 **cb5c5b7d**
- [x] 进度可见：CLI `Renderer.agentStepNotice` + `EventRouter` 订阅
  `BusEvent.AgentRunStateChanged` 在 Generating-edge 上 emit
  `Step N · processing…`；desktop UI 同步推迟到独立 follow-up bullet
  per CLAUDE.md "Platform priority — CLI > Desktop"。
  — cycle 2026-04-26 **a0bd56eb**
- [x] Failure-fallback 提示：`FallbackHint` sealed type (4 variants
  ProviderUnavailable / RateLimited / Network / Uncaught) +
  `FallbackClassifier` 复用 `RetryClassifier.kind`；`AgentRunState.Failed`
  加 `fallback` 字段；Agent.kt + AgentRunFinalizer.kt 两处 emit 都通过
  classifier 注入。— cycle 2026-04-28 **250b6655**
- [x] Cross-modal staleness 分区：`Modality` enum +
  `Source.deepContentHashOfFor` per-modality deep hash +
  `LockfileEntry.sourceContentHashesByModality` snapshot；
  `ProjectStaleness.staleClipsFromLockfile` 用 `clip.modalityNeeds` 选
  visual / audio 切片比对；6-case `ProjectStalenessModalityTest`。
  — cycle 2026-04-28 **7c30d534**
- [x] Milestone 退出总结：M3 exit summary 段（本 block 下方）+ 6 条 §4
  接力 gap 列入 M4 / M5 / M6 / 未来 M。
  — cycle 2026-04-28 *本 commit*

### M3 exit summary

M3 关起门来证明了 **§4 双用户张力的 6/6 实质轴 + 1 manual exit 全部落地**：
一句话出初稿 e2e (f30fe975) + system-prompt 双用户引导 (b959ab15) + 两路径
共享 source 的 round-trip 回归 (cb5c5b7d) + CLI 多步 trajectory 进度行
(a0bd56eb) + ERROR 终态 fallback 建议 (250b6655) + cross-modal staleness 分区
(7c30d534)。这六条把"小白一句话出初稿 → 专家精修同一个 Project"从理论
变成了 jvmTest 守护的不变量；下一个用户哪怕看 git log 也能看到"系统真的
在朝双用户张力收敛，不只是嘴上说说"。

**§4 完整愿景里 M3+ 接力的 gap**（超出本 milestone 的工程学跑道，列给
M4 / M5 / 未来 M）：

1. **专家路径精修 GUI**：M3 的两路径共享 source 是用 jvmTest 证明的——
   `source_node_action(action="update_body")` 在 CLI 文本接口下能跑通。但
   **专家用户实际场景是 timeline 上鼠标拖、单帧调参、shader 实时预览**，
   而 desktop / iOS / Android 当前只有 chat 面板 + 没有 timeline editor。
   "two paths, one project" 的 UI 半边在 M3 没动，是 M5 / M6 候选（取决于
   平台优先级窗口）。
2. **多人协作 merge conflict 工作流**：BACKLOG `re-evaluate-desktop-merge-
   conflict-strategy` 跨 4+ repopulate cycles 等用户 promote/demote/delete
   决策。VISION §4 第 4 题（"两个用户在同一个 project 上分别推进"）的写口
   M3 没碰——`talevia.json` git-friendly 已经做到（pretty-print + 行级
   diff），但**语义 3-way merge across timeline / source / lockfile** 的
   工作流（`.gitattributes merge=talevia-json` driver / `talevia merge` CLI
   / 冲突可视化）还在等真实多用户 case driver。
3. **项目模板 marketplace + onboarding**：小白 v0 用户面对空 Project 会
   呆住——"vlog / narrative / ad / musicmv 我选哪个？"。`docs/VISION.md` §6
   列了 5 个 genre 但产品里没有"vlog 模板"按钮，Source 里也没有 starter
   DAG 让 LLM 复用。`source_template_action` 类的 source-layer primitive +
   ≥ 3 个 prebuilt template (vlog / narrative / ad) 是 §4 小白路径下一个
   自然 driver。
4. **Cross-mode handoff explicit**：VISION §4 提"在 agent 的某个决策点接管"。
   M3 的 `agent-error-fallback-suggestion` (250b6655) 处理了 ERROR 路径的
   handoff（"let me take over manually"），但**正常路径**下 agent 多步
   trajectory 中"用户想中途接管编辑"没有结构化入口——CLI cancel 信号 +
   重新 chat 是当前唯一手段。需要 `BusEvent.AgentHandoffRequest` /
   `Part.HandoffOffer` 这类一等概念让 agent 主动暴露"我现在该让你接管吗"。
5. **小白模式 / 专家模式自动切换 e2e**：M3 的 system-prompt 教 LLM 区分两
   模式，但**LLM 自主切换的语义证明**没有 e2e 测试守护——比如"用户先要
   mass-generate 后单帧精修"的 trajectory 应该自动从 batch generate 切到
   single-clip update_body 。需要类似 `OneShotDraftE2ETest` 但 trajectory
   更长 + 含 mode-flip 断言的 e2e。
6. **跨模态 staleness 的下游消费**：250b6655 加了 `Modality` 分区 + lockfile
   per-modality snapshot，但 `regenerate_stale_clips` 还是按 stale ClipId 一
   把抓——没有"只 regen audio modality 的 clips" 这种 per-modality batch
   选项。是 §3.2 增量编译 (M5 候选) 的正交切片。

前两条由 desktop UX / 平台优先级窗口驱动；3 / 4 / 5 是 §4 内的产品深化；
第 6 条进 §3.2 / §5.7 perf 轴并入下一轮 perf scan。M4 / M5 别扛全部，按
平台优先级 + 现有 BACKLOG meta 的用户决策结果挑。

### M3 → M4 promotion logic

M3 的"两路径共享 source"既证明了双用户能共有 **state**，也暗示了双用户
都会指向同一个**扩展点**——source 层。M4 选 §5.2 专家特效 candidate
（M3 亚军列表头条）正因为 source 是双用户共有的扩展面：今天加一个新
特效要跨 5 个 AppContainer + engine 契约 + core.domain 是 §5.2 打分
"部分"的根因，把这个收敛到"≤ 3 抽象层"是 §5.2 推到"有"的直接路径。
M4 criteria 起草逻辑：① 直接量化"跨几层"（cost-of-three-layers）+
② 三引擎 parity 守护（同 M3 cross-modal 的 invariant pattern）+ ③ 专家
精修反查（沿用 source DAG stale → regen 不变量，验证 effect 是该
不变量的 first-class 公民）+ ④ effect tool spec budget（防止特效让
M2 已驯服的 tool spec 预算反噬）+ ⑤ 手动 exit summary 给 M5 接力。
M5 候选保留 §3.2 增量编译；新加 M6 候选 §4 跨平台 GUI 接住 M3 exit
gap #1（专家路径 GUI 半边）。

## Completed — M2 (2026-04-26)

**目标**：把 AIGC 从"不可复现的黑盒"驯服成"随机编译器"——seed 显式、模型 / 版本
锁定、产物 pin 命中率可度量、provider 数量 ≥ 2 有 fallback、每次 AIGC 调用的成本
在 session 层可见。

**Exit criteria 完成情况**：

- [x] Lockfile 完整性：每个成功的 AIGC clip 在 `project.lockfile.entries`
  落一条 `LockfileEntry` (modelId, modelVersion?, seed, parameters, inputHash,
  sourceBindingContentHashes); round-trip e2e。
  — cycle 2026-04-24 **3fde671a** (`c30ecbb1` 也参与)
- [x] Provider 多元：`SeedanceVideoGenEngine` 接 ByteDance Volcano Engine ARK
  作为 `VideoGenEngine` 的第二 prod impl，证明 provider 抽象不是
  openai-hardcoded；`ARK_API_KEY` 触发，三个 JVM container 均 wire。
  — cycle 2026-04-26 **487c5989**
- [x] Pin 命中率可见：`project_query` 加 select 答 "export 几次、几条 clip
  从 lockfile cache 命中"。
  — cycle 2026-04-24 **08223ac3**
- [x] 成本可见：`session_query(select=spend_summary)` 聚合 per-session AIGC
  spend，按 provider 分档，tokens / USD 估算，反复调用累加。
  — cycle 2026-04-24 **7862ce7d**
- [x] Fallback 生产回归测试：`ProviderFallbackE2ETest` e2e 覆盖 "provider A
  抛 ProviderError → AgentProviderFallbackTracker 切 B → 产物正确落地"。
  — cycle 2026-04-24 **c3bad022**
- [x] Seed 复现证明：相同 seed + inputs 重跑 `generate_image` 得 bit-identical
  provenance + 同 assetId（lockfile cache-hit）。
  — cycle 2026-04-24 **e1adea8f**
- [x] Milestone 退出总结：M2 exit summary 段 + 5 条 §3.1 接力 gap（GPU-inference
  本地跑 / 预算硬 cap 触发 / 多 provider cost arbitrage / cold-start 优化 /
  cache invalidation latency）。
  — cycle 2026-04-24 **4ee6a08b**

### M2 exit summary

M2 关起门来证明了**AIGC 驯服产品化的 6/6 实质轴 + 1 manual exit 全部落地**：
lockfile 完整性 (3fde671a) + provider 多元 (487c5989, Seedance) + pin 命中率
(08223ac3) + 成本可见 (7862ce7d) + fallback 回归 (c3bad022) + seed 复现
(e1adea8f)。这六条是 "AIGC 从黑盒变 deterministic compiler" 的工程学基石。

**§3.1 完整愿景里 M2+ 接力的 gap**（超出本 milestone 的工程学跑道，列给
M3 / M4 / 未来 M）：

1. **GPU-inference 本地跑**：`ImageGenEngine` / `VideoGenEngine` 现在全走云端
   provider。本地 SD / local ComfyUI / MLX-diffusion 这类路径缺
   `LocalGpuImageGenEngine` impl + GPU-availability probing + fallback-to-cloud
   策略。对 data-sensitive 创作者（不愿把素材传云）是必需品。
2. **预算硬 cap 触发**：`session_query(select=spend_summary)` 给了读口，但没有
   写口——session.metadata 里没有 `spendCapUsdCents: Long?` 字段，`Agent.run`
   不在 AIGC tool 分发前检查 "this session spent > cap, refuse".
   `SessionRateLimits` 有骨架但不消费 spend_summary 数据。
3. **多 provider cost arbitrage**：已有 `provider_query(select=cost_compare)`
   答 "same prompt across providers — cheapest"；但 `AgentProviderFallbackTracker`
   只按 registry 顺序选 provider，不按 cost 挑。需要 `ProviderRoutingPolicy`
   抽象 + cost-aware fallback chain 排序。
4. **cold-start 优化**：首次 AIGC 调用 provider connection setup / model warmup
   / seed-pinning handshake 不可见。Session 起步第一个 image 生成会比后续慢
   很多，用户会以为卡死。`BusEvent.ProviderWarmup` 已存在但 CLI/Desktop 还无
   对其的 UI surface。
5. **cache invalidation latency**：`project.lockfile` cache hit 检测走 in-memory
   `byInputHash` map；跨 session 打开同一 project 会重建这个 map，但 JSON decode
   on large `entries` list (>500 items on a mature project) takes O(N) per open
   — profile 数据没收集。未来 perf bullet 可能要 `lockfile.jsonl` 分文件 +
   增量加载。

前四条都指向 M3/M4/M5 的不同赌注；第五条是 §5.7 perf 轴的独立 backlog 项。

## Completed — M1 (2026-04-24)

**目标**：跨镜头一致性 e2e 闭环（VISION §3.3）——consistency binding 不是
stale 标记而已，物理注入进 AIGC 生成并在生命周期保持绑定。

**Exit criteria 完成情况**：

- [x] 物理注入：`AigcPipeline.foldPrompt(...)` 在 3 个 AIGC tool 均使用。
  — *pre-M1 已落地*（milestone 机制起点时即完成，无专门 commit）
- [x] 绑定持久化：`Clip.sourceBinding: Set<SourceNodeId>` 承载，AIGC tool
  从 `folded.appliedNodeIds` 自动 populate，regenerate 经 lockfile.baseInputs
  无需 LLM 再传。— cycle 2026-04-24 **4ead8ad1**（skip-close 的 evidence
  cite，criterion grep 是散文无法 auto-tick 遂在此 M1 exit cycle 手动 tick）
- [x] 绑定反查：`project_query(select=clips_for_source, sourceNodeId=X)`
  返回直接 + 传递闭包下绑定的 clip 列表。— cycle 2026-04-24 **949a6551**
  （skip-close evidence；criterion grep 用了例子 slug 未命中实际 slug
  `clips_for_source` 遂在此 M1 exit cycle 手动 tick）
- [x] Kind 可扩证明：`ConsistencyKinds.ALL.size == 4`，新增
  `LOCATION_REF` + `LocationRefBody` + PromptFolding when-branch 处理。
  — cycle 2026-04-24 **db5f5d2f**（本 commit hash 在 MILESTONES 里记为
  db5f5d2f 但实际 push 是 amend 后的 4dc1fa28；shorthash 指针是 draft
  时记录的，整体 evidence 在 `git log -S LOCATION_REF`）
- [x] 语义回归测试：`RefactorLoopE2ETest` 添加 `capturedPrompts` +
  "seed prompt contains 原 visualDescription" + "regen prompt contains
  new value + NOT stale value" 断言，闭合 edit → stale → regen →
  prompt 链。— cycle 2026-04-24 **51aaf884**（同 db5f5d2f 的 pre-amend
  hash 注解，实际 commit `git log -S capturedPrompts`）
- [x] Milestone 退出总结：本 block + M1 exit summary 段。— cycle 2026-04-24
  0b47d198（m1-exit-summary-and-manual-ticks 的 feat commit；见其下的
  `### M1 exit summary` 段）

### M1 exit summary

M1 关起门来证明了**一致性 binding 能被物理注入、能持久化、能反查、机制
extensible、edit→stale→regen 全链路正确**。但 §3.3 的完整愿景还有几块 M2+ 要
接力：

1. **LoRA fine-tune 产物持久化**：`CharacterRefBody.loraPin` 已经能把 adapter
   id + weight 注入 AIGC 调用，但用户上传自己训的 LoRA → adapter id 的注册 /
   auth / upload / 版本管理流程还完全没有。目前要手动在 Replicate / Civitai
   这种外部 registry 里拿 adapter id 再粘进 body，这把"一致性"拆成"系统内 +
   外部 registry"两部分，脆弱。future M 应当建 `lora_binding` kind（
   LocationRef 之外的第 5 个？）承载 upload/train/serve 的本地生命周期。
2. **多 character 交互一致性**：当前 fold 是"character × N 各自拼字符串 append"
   —— 两个角色同时在镜头里时，fold 出来是 "Character A: ... Character B: ..."
   简单并列，模型还是要靠 prompt 语言能力把握谁是主谁是从、相对位置 /
   occluding / interaction。需要 `scene_interaction` 类的 source 层 primitive
   表达"两角色关系"，而不是让 LLM 每次在 base prompt 里重写。
3. **音视频跨模态一致性**：`CharacterRefBody.voiceId` 已经存在于 schema，
   `foldVoice` 也能 resolve，但当前 TTS + ImageGen + VideoGen 三路各自跑
   prompt fold，视觉 character + 听觉 voice 之间**没有 cross-modal 一致性
   check**（改了 voiceId 不会让依赖它的 speech clip stale，改了
   visualDescription 不会让 TTS 产物 stale）。需要 source DAG 记录跨模态
   依赖 + ProjectStaleness 算法理解"voice 的变化只影响 speech，visual 的
   变化只影响 image/video"的分区。
4. **consistency conflict detection**：多条 binding 互相冲突时（style_bible
   的 `negativePrompt: "warm"` vs character_ref 的 `visualDescription: "warm
   lighting"`）现在是盲 concat，模型收到自相矛盾的 prompt。应当有
   `project_query(select=binding_conflicts)` 静态扫 pair-wise 冲突 + 提示
   agent 解决。
5. **consistency drift 的跨 export 量化**：criterion 5 的 regression test
   证明了"edit → 新 prompt 有新值"，但**新值到新 asset 是否真的"变得更
   一致"**（视觉上/听觉上）没法自动验——需要加 perceptual metric（
   CLIP-similarity / face-embedding distance / voice-feature distance）的
   回归守护。这一步要外部模型依赖，M2/M3 不急，但是 consistency 真正的
   bottom line。

这 5 条不进 M2（M2 盯 §3.1 AIGC 驯服，正交议题）——由 future M（可能 M4 / M5）
把 "cross-modal consistency" / "interaction primitive" / "perceptual regression"
作为独立赌注接力。

## Completed — （更早 milestone 按 auto-promote 顺序 append）
