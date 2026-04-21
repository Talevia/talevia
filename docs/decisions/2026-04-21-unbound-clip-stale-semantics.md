## 2026-04-21 — unbound-clip-stale-semantics：unbound clip 改为 "out of scope for incremental tracking"（VISION §5.3 / §5.5）

Commit: `b6cbf3e` (pair with `docs(decisions): record choices for unbound-clip-stale-semantics`).

**Context.** `ProjectStaleness.staleClips(changed)` 的老语义：clip 的 `sourceBinding` 为空 → 直接加入 stale set（注释："unbound clip: can't prove fresh → treat as stale"）。这个"保守默认"在当时（VISION §3.2 刚拉通 stale 传播协议）是合理的 —— "宁可误报也别漏报"。但一旦跑起来就立刻出问题：

1. **Small-white 路径所有 clip 都会被标 stale**。VISION §4 的"小白/专家双用户"承诺里，小白路径几乎全是 `ImportMediaTool` 导入的素材（沒有 `sourceBinding`）和 `add_subtitle` / `add_subtitles` 产生的 `Clip.Text`（也沒有绑定）。UI 打开任何小白项目都是一堆红色"stale"标记，信号噪声比 1:1。
2. **无法 `regenerate_stale_clips`**。被标 stale 的 unbound clip 没有 lockfile entry 也没有 `baseInputs`，regenerate 拿不到原始 tool 调用参数。它们落在一个"系统说 stale、但系统又修不了"的不可达状态。
3. **`staleClipsFromLockfile()` 早已正确**。仔细读 `ProjectStaleness.kt:158-187`：lockfile-driven 路径对找不到 lockfile entry 的 clip（imported media、legacy entries、text clips）是**silently 跳过**的，注释明确 "Imported media is never AIGC-stale by definition; legacy entries are an 'unknown' bucket we decline to lie about"。agent 通过 `find_stale_clips` tool 真正消费的是这条路径——行为已经是对的。有 bug 的只有 `staleClips(changed)` 这个 source-node-push 变体。
4. **没有生产 caller**。`grep -rn '\.staleClips('` 在 commonMain / apps 里零命中，只有 `freshClips()` 内部调用 + 测试消费。`RenderCache.kt:38` 和 `Clip.kt:24` 的 KDoc 提到 `staleClips` 但没实际使用——这是为 VISION §3.2 "incremental render" 留的 library API，还没接入。这意味着修改是纯 library 语义调整，不会触发任何行为回归。

Backlog `unbound-clip-stale-semantics` 刚好把这个信号污染挑出来：`split-project-json-blob` 刚做完（commit `a2ecb40`、decision `2026-04-21-split-project-json-blob.md`），P0 第 1 条轮到这条。

**Decision.** 选 **option A（语义翻转为 "unbound → 不在增量追踪范围内"）** 而不是 **option B（引入 `Fresh / Stale / Unknown` 三态 enum）**。

具体改动（`core/src/commonMain/kotlin/io/talevia/core/domain/ProjectStaleness.kt`）：

- `staleClips(changed)`：老的 `if (clip.sourceBinding.isEmpty()) { out.add(clip.id); continue }` 改成 `if (clip.sourceBinding.isEmpty()) continue`。unbound clip 不再进入 stale set。
- `freshClips(changed)` 行为不变（本来就 `if (clip.sourceBinding.isEmpty()) continue`），但 KDoc 补了显式说明："clips without a binding are in neither set（the 'unknown / not-tracked' bucket）"。
- 顶层 KDoc 重写，把"unbound = 不在增量系统内"这层语义写明。

"三态语义"不通过新 enum 表达，而通过**"在哪个集合里" 隐式表达**：

- 在 `staleClips` 返回集里 → known-stale，必须重渲染。
- 在 `freshClips` 返回集里 → known-fresh，可安全跳过。
- **两个集合都不在** → 系统无法判定（"unknown / not-tracked"），由 caller 选择是否主动去重算，增量决策不参与。

这正好对齐 `staleClipsFromLockfile()` 本来的处理方式——两边现在是同一种哲学。

**Alternatives considered.**

1. **Option B — 引入显式三态 enum**：返回类型从 `Set<ClipId>` 改成 `Map<ClipId, ClipStaleState>` 或单独函数 `unknownClips()`。拒绝：
   - **API 破坏面大**：`staleClips` / `freshClips` 是 `Project` 的 extension function，已有 4 个测试 + `freshClips` 内部依赖、未来 render path 消费者会再加。换返回类型会 cascade 到每个 call site，强制所有人处理第三态。
   - **新 enum = 新二元维护面**（§3a.4 约束"状态字段不做二元"本意是"别把应该是三态的东西塞进 bool"；这里的问题不是**类型不够三态**，而是**现有二元决策把 unknown 错分到 stale**）。用"集合成员关系"表达三态更 Kotlin-native、更轻量。
   - **"known-stale, known-fresh, unknown" 本身就是偏序而不是离散枚举**：随着更多信号接入（例如 render cache 命中会独立判断 fresh），加 enum 会强制穷举所有三元组合，反而越堆越乱。集合成员关系是开放的。

2. **对 unbound clip 保留老的"always stale"行为但加 `onlyTracked = true` 参数让 caller opt-in 新语义**。拒绝：这只是把决策压给 caller，污染不会消失——`find_stale_clips` / UI / export stale-guard 每一个 call site 都得记得传 `true`，忘一个就回退到老行为。"默认安全"应该是默认干净，不是默认污染 + 逃生舱。

3. **给 unbound clip 伪造一个 `sourceBinding = {SourceNodeId("synthetic-unbound")}` 并在 Source DAG 里加个 `synthetic-unbound` 节点**。拒绝：通过结构欺骗让老代码"看起来对"本身就是反模式；任何读 sourceBinding 的代码（`list_clips_for_source`、`describe_clip`）都得学会特殊处理这个魔法节点，抽象漏出得到处都是。

4. **让 `staleClips(changed)` 按类型判断：`Clip.Text` 永远跳过、`Clip.Video` / `Clip.Audio` 按 binding 判断**。拒绝：imported `Clip.Video`（`ImportMediaTool` 加的 b-roll）和 AIGC `Clip.Video`（`generate_video` 加的）用同一个 sealed class，类型层面无法区分；真正区分它们的信号就是 `sourceBinding.isEmpty()` 本身。

**Coverage.** `core/src/jvmTest/kotlin/io/talevia/core/domain/ProjectStalenessTest.kt`：

- `clipWithEmptyBindingIsOutOfScopeForIncrementalTracking`（翻转自原 `clipWithEmptyBindingIsAlwaysStale`）—— 显式断言 unbound clip 既不在 `staleClips` 也不在 `freshClips`。这是防回归锚点，不论未来谁改回老行为都会挂。
- `mixedBoundAndUnboundOnlyReportsBoundStale`（新）—— 最典型的反直觉边界：项目里既有 AIGC 又有 imported 素材，改 character_ref 应该只标 AIGC 那几个 stale，imported b-roll 必须不进结果集。断言 `unionOfTracked` 不包含 imported clipId。
- `unboundClipAbsentEvenWhenNoNodesChanged`（新）—— 另一个反直觉边界：即便 `changed` 是空集合，unbound clip 也不应该悄悄钻进 `freshClips`（freshClips 已经正确实现，但显式锚一下避免未来回归）。
- 既有 3 个正面路径测试（绑定节点 stale、descendant stale、unrelated fresh）保持不变——行为对它们没变化，作为不回归对照组。

**Registration.** 无需注册——纯 Core 内部语义修正。`ProjectStaleness.kt` 是 extension function 文件，没有 tool / AppContainer 接触面。5 端通过 commonMain 共享代码，所有平台自动获得新行为。

**Session-project-binding 注记（§3a.6）.** 本次改动不引入 tool input——无影响。

**LLM context 成本（§3a.10）.** 零变化——没有新 tool spec / helpText / system prompt 片段。倒是会顺带省下以前 `find_stale_clips` 对 unbound clip 可能返回的噪声（实际上 `find_stale_clips` 走的是 lockfile 路径已经没问题，是将来 incremental render path 接入时受益）。

**Non-goals / 后续切片.**

- 把 `staleClips` 和 `staleClipsFromLockfile` 合并成一个 API（两者语义已经对齐、但输入不同——前者是"给定 changed set 查受影响 clip"，后者是"查当前 contentHash 漂移的 clip"，还是两个函数更清楚）。
- 给 `freshClips` 补一个 `inScopeClips` / `trackedClips` 的 "sourceBinding 非空 clip" 投影，UI 需要批量展示"哪些 clip 在增量系统内"时用。本 cycle 不做——等第一个真实 UI 需求出现再加。
