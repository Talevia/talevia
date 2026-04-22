## 2026-04-21 — compact-session-threshold-visible-in-ui：扩 `StatusRow` 三字段 (`estimatedTokens` / `compactionThreshold` / `percent`)（VISION §5.4）

Commit: `3e461dc`

**Context.** `Compactor` 用 `compactionTokenThreshold = 120_000` 硬编码触发 auto-compaction；UI 没有渠道展示 "当前 session 的 token 占用到阈值多少百分比了"。用户看不到进度条 → 临近 120k 才在 compaction 事件发生那一刻才"惊讶"地看到界面状态抖一下。Rubric §5.4（observability：UI 需要能提前告诉用户 "context budget 在用完的路上"）。

Backlog bullet 原文："扩展 `session_query(select=status)` （P1 那条的 follow-up）返回 `estimatedTokens` + `compactionThreshold` + `percent`；UI 就能渲染占比条。"

亚军 bullet 继续跳过：`project-query-sort-by-updatedAt` 第三轮 skip —— §3a #9 测试面 explode 问题未变；`Project.updatedAt` 反推 fallback 对项目内 entity 排序仍无信号。

**Decision.** 给 `SessionQueryTool.StatusRow` 加 3 个 optional 字段，在 `runStatusQuery` 里计算并填入：

```kotlin
@Serializable data class StatusRow(
    // ...既有字段...
    val neverRan: Boolean = false,
    /** TokenEstimator.forHistory(includeCompacted=false). Null 仅 backward-compat。 */
    val estimatedTokens: Int? = null,
    /** Default Agent threshold. */
    val compactionThreshold: Int? = null,
    /** estimatedTokens / compactionThreshold, clamped [0.0, 1.0]. */
    val percent: Float? = null,
)
```

**计算语义**（`StatusQuery.runStatusQuery`）：
1. `sessions.listMessagesWithParts(sid, includeCompacted = false)` —— 和 `Agent.autoCompactIfNeeded` 判定口径严格一致（已压缩的 parts 通过 summary 折进去、不再独立占 token）。Unknown session 下 store 返回空列表 → estimatedTokens=0、percent=0，test 覆盖。
2. `TokenEstimator.forHistory(history)` → `estimatedTokens`。
3. `DEFAULT_COMPACTION_TOKEN_THRESHOLD = 120_000` 常量（新加在 `StatusQuery.kt`） → `compactionThreshold`。
4. `(estimatedTokens.toFloat() / threshold.toFloat()).coerceIn(0f, 1f)` → `percent`。

**为什么常量而不是穿透 Agent 实例的值**：
- `Agent.compactionTokenThreshold` 是 constructor 参数，默认 120_000；五端 AppContainer 都用默认值（未观察到任何 AppContainer 传入 non-default）。
- 让 `StatusQuery` 依赖 `Agent` 实例会引入新的 wire-through（agentStates tracker 已经做了一次），每 session 要找到"哪个 Agent 跑它"—— 多 Agent per container（iOS 每个 provider 一个 Agent）情况下还要选对 Agent。
- UI 拿到 const 120_000 和 Agent 实际默认值一致；如果将来某个 container 自定义了阈值，那次改动再把 threshold wire 到 StatusRow，`DEFAULT_COMPACTION_TOKEN_THRESHOLD` 的 KDoc 已经标记"kept in sync by convention"作为未来 cycle 的 touchpoint。
- Alternative：加 `agentThreshold` 参数到 `SessionQueryTool` 构造器，五端容器各自决定。拒绝见下文 Option B。

**outputForLlm enrich**：summary text 在 estimatedTokens > 0 时加 `", X% of compaction threshold (N/M tokens)"`，让 LLM 看 session 的 `session_query(select=status)` 直接就知道 budget 状态（不必二次 quote data 字段）。

**Coverage**：
- 既有 5 tests 全绿（`neverRanReturnsIdleWithNeverRanFlag` / `generatingStateSnapshotAfterPublish` / `failedStateCarriesCause` / `latestStateOverridesEarlierTransitions` / `missingSessionIdFailsLoud` / `noTrackerWiredFailsLoud`）—— 新字段 default null，既有断言只看 `state` / `cause` / `neverRan`，不动。
- **新增 2 tests**：
  - `compactionProgressFieldsPresentWithDefaultThreshold` — 空 session，`estimatedTokens=0` / `compactionThreshold=120_000` / `percent=0f`。edge case：字段**永远 non-null** 即使计数为 0，UI 能无条件渲染 bar。
  - `compactionProgressReflectsRealTokenCount` — 种 1 user + 1 assistant message + 1 Part.Text 含 400 chars，heuristic 估 ~100 tokens。assertions 用 `> 0` / `< threshold` / `percent 近似 expected` 三道防线（heuristic 4-char-per-token + 结构化字段 fudge，exact match 太脆）。§3a.9 反直觉边界：`listMessagesWithParts(includeCompacted=false)` 的调用语义，而不是 `listMessages` 纯消息。

**5 端装配**：无新 tool，`SessionQueryTool` 已经在 5 端（cycle 19 时 tracker wire 一并做了）；本 cycle 纯内部 select 扩展。

**Alternatives considered.**

1. **Option A (chosen)**: 在 `StatusRow` 加 3 个 optional 字段，`DEFAULT_COMPACTION_TOKEN_THRESHOLD = 120_000` 作为内部常量。优点：unified `select=status` 一次拿所有 status 信号（state + token progress）；zero tool count increase；forward-compat （null default）；UI / LLM 都能直接用；测试 surface 小。缺点：常量 vs 从 Agent 穿透有一次性手动同步 debt，但 KDoc 标明 + 120_000 是 stable 数值（历史未改）。
2. **Option B**: wire `agentThreshold` 从 AppContainer 到 SessionQueryTool 构造器。拒绝：① 五端各自存在但当前默认都是同一个值 → 额外 plumbing 零收益；② iOS 每个 provider 一个 Agent 实例，如果 threshold 从 Agent 穿出，StatusQuery 要 per-session 找到"哪个 Agent 跑这个 session" —— agentStates tracker 已经做了这个映射但只映射 state，不映射 threshold。分两轮把 wire-through 做完比一次性塞进当前 cycle 好。
3. **Option C**: 新 tool `session_token_status(sessionId)` 专门返回 budget 信号。拒绝：§3a.1 工具数量不净增；`select=status` 已经是 per-session 聚合视图的合理归属。
4. **Option D**: 用 `listMessagesWithParts(includeCompacted=true)` 让 estimatedTokens 反映 "假如没有压缩过"的总数。拒绝：和 Agent 的实际触发逻辑脱钩（Agent 用 `includeCompacted=false` 判 threshold）—— UI 显示的进度和实际 trigger 时机会不一致，用户看到 80% 却 compaction 已经 fire 过一次，语义混乱。
5. **Option E**: 返回 `tokensUntilThreshold: Int` 而不是 `percent: Float`。拒绝：两者等价（UI 自行计算），但 `percent` 对 UI 更直接（bar.progress = percent），`tokensUntilThreshold` 需要 UI 再做一次除法 + clamp；bullet 原文也明确 "percent"。
6. **Option F**: 精确 tokenization（调用 BPE/tiktoken 或 Anthropic SDK 的准确 counter）。拒绝：Core 是 MultiPlatform，tokenization 是 provider-specific，引入大量 deps；`TokenEstimator` 明确自定位为 "estimate for auto-compact decisions"，UI 展示只需要粗略信号 —— 精确度提升零收益且打破 `commonMain` 零平台依赖红线。

**Coverage.**

- `./gradlew :core:jvmTest` + `:apps:server:test` + `:core:ktlintCheck` 全绿；4 端构建全绿。
- 7 tests total on `SessionQueryStatusTest` (5 既有 + 2 new)。

**Registration.** 无装配变更。

**§3a 自查.**

1. 工具数量: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动。PASS。
4. 状态字段: percent 是 derived ratio 非 flag；threshold 是 const；estimatedTokens 是 derived 计数。PASS。
5. Core genre: 无。PASS。
6. Session/Project binding: sessionId 继续 required（select=status 的已有契约）。PASS。
7. 序列化向前兼容: 3 个新字段都 `= null` default；旧 Output JSON decoders 缺字段正常（得到 null）。PASS。
8. 五端装配: 无 tool 变更，装配不动。PASS。
9. 测试语义覆盖: 新加 2 tests 覆盖空 session / real-token ratio；§3a.9 边界包括 includeCompacted=false 口径正确。PASS。
10. LLM context 成本: Output schema 加 3 nullable 字段，每次 `session_query(select=status)` 调用 wire 多 ~30 tokens（数值 encoded inline）。outputForLlm summary 多 ~30 tokens `"X% of compaction threshold ..."`。每次调用 net +60 tokens。PASS（UI 收益方向 + LLM 也能从 summary 直接看到）。

**Non-goals / 后续切片.**

- **Follow-up: threshold 从 Agent 穿透**。当前 `DEFAULT_COMPACTION_TOKEN_THRESHOLD` 常量和 `Agent.compactionTokenThreshold` 默认值约定同步；若 future container 自定义 Agent threshold，需要 wire `threshold` 穿过 tracker 或 SessionQueryTool 构造器。独立 cycle 的 wire 任务。
- **Follow-up: multi-provider threshold**（不同 model 可能有不同 context window，例如 200k 和 1M）。需要 `ModelRef → threshold` lookup，超出本 cycle 范围。
- **不动 TokenEstimator**。它已经被 Compactor 用作 auto-trigger 决策，改 heuristic 会影响已有行为。本 cycle 纯消费现有 API。
- **第四轮跳过 `project-query-sort-by-updatedAt`**（P2 top）—— §3a 风险不变，等具体 UI driver / 小范围实现出现再动。
