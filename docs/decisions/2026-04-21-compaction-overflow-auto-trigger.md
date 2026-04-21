## 2026-04-21 — compaction-overflow-auto-trigger：给 auto-compaction 加 bus 信号（VISION §5.4 rubric）

Commit: `5acd696` (pair with `docs(decisions): record choices for compaction-overflow-auto-trigger`).

**Context.** Backlog bullet 说"Compactor 只在显式调用时触发"——这其实是错的，`Agent.kt:250` 已经在每个 turn-start 读 `TokenEstimator.forHistory(history)` 对比 `compactionTokenThreshold`（默认 120 000），超阈值就调 `Compactor.process`。真正的缺口在**信号可观测性**：auto-compaction 期间 UI / SSE 订阅者看不到 "agent 正在 compact" 的事件，只会盯着一个 "好像卡住了" 的下一 turn。summarise pass 可能要 5-15 秒（provider 对整个 history 做一次 LLM 调用），这段沉默期如果没专门信号，用户会以为 agent hang 了。

OpenCode 对应 `session/overflow.ts` 发 overflow / compaction 事件到 bus。Talevia 本来就有 `AgentRetryScheduled` 配对事件用来让 UI 渲染 "Retrying in 4s…"，auto-compaction 是对称的第二个 "可见暂停原因"。

Bullet 里还提到 "model-aware threshold"（把 120k 换成 `model.contextWindow * 0.85`）——拒绝本轮做，理由见 Alternatives。

**Decision.** 范围收窄到 bus 信号：

1. `core/bus/BusEvent.kt` 新增 `data class SessionCompactionAuto(sessionId, historyTokensBefore, thresholdTokens) : SessionEvent`。
2. `core/agent/Agent.kt` 的 compaction 块改成先 publish 后 process：
   ```
   if (compactor != null) {
     val estimated = TokenEstimator.forHistory(history)
     if (estimated > compactionTokenThreshold) {
       bus.publish(SessionCompactionAuto(sessionId, estimated, threshold))
       compactor.process(sessionId, history, model)
       history = store.listMessagesWithParts(...)
     }
   }
   ```
   发 event **先于** `compactor.process` 开始——这样订阅者拿到的是 "即将开始 compact" 信号，而不是 "刚 compact 完"，UI 能在 summarise 那几秒里展示 "compacting…"。
3. `core/metrics/Metrics.kt` 的 `counterName(BusEvent)` when 分支加 `SessionCompactionAuto -> "session.compaction.auto"`——`/metrics` 端点自动多一个计数器，不需要改 ServerModule 的注册。
4. `apps/server/src/main/kotlin/io/talevia/server/ServerModule.kt` 两个 when-on-BusEvent（`eventName` 和 `BusEventDto.from`）也补 `SessionCompactionAuto` 分支。`BusEventDto` 新增 `historyTokensBefore` + `thresholdTokens` 两个可选字段。

**Alternatives considered.**

1. **Option A (chosen)**: 只补 bus event，阈值保持 120k 硬编码。
   - 优点：小 PR、行为变化零（Compactor 逻辑不动），新增是纯 observable。5 端冲击最小。
   - 缺点：阈值对非-Claude-200k 模型（如 Haiku 4.5 context 200k but often limited by output、Sonnet-4 1M）仍不对。
2. **Option B**: 把 120k 换成 `model.contextWindow * 0.85`。拒绝：
   - `ModelRef` 当前没有 `contextWindow` 字段。加这个字段 = 要动 `ModelRef` serialization（§3a.7 compat 风险），要给 `core/provider/models.ts` 一类的映射表，要为每个 provider / model combo 维护 context window。OpenCode `provider/models.ts` 就是这张表——port 过来是另一个 cycle 的规模。
   - 硬编码的 120k 对今天的 Claude 工作负载（默认 claude-sonnet-4.6 200k）是合理的 0.6 × 200k。不符合 "85%" OpenCode 惯例但差得不远。先发 event、后续另起 cycle 迁移阈值。
3. **Option C**: 改成 `Compactor.process` 内部 emit event。拒绝：违反单一职责——Compactor 是 pure 业务逻辑（prune + summarize），publisher 身份应该在 Agent（即 orchestrator）手里。而且 Compactor 也被 manual 路径调用（未来 `/compact` command）——那种场景不应该 emit auto event。

**Coverage.** `AgentCompactionTest.kt` 加了 `autoCompactionPublishesSessionCompactionAutoEvent`：

- 用和 `compactorFiresWhenHistoryExceedsThreshold` 相同的 seed（heavy tool output 使 history > 100 tokens、阈值设 100）触发 auto-compaction。
- `launchIn(this)` 在 agent.run 之前订阅 bus event、`yield()` 确保 collector 活动后才跑 agent（SharedFlow 无 replay，必须先订阅再 publish）。
- `advanceUntilIdle()` 在 agent.run 返回之后确保所有悬挂协程包括 collector 都推进完。
- 断言：恰好 1 个 `SessionCompactionAuto`、sessionId 对、thresholdTokens == 100、historyTokensBefore > 100。

既有的 `compactorFiresWhenHistoryExceedsThreshold` 测试保持不变——验证 compaction 真的在跑（Part.Compaction 产生 + heavy-tool 被标 compacted）。两个测试正交：一个证行为、一个证信号。

**Registration.** 无 tool 注册变化。Bus event 新类型自动经现有 SharedFlow 流到所有订阅者。`/metrics` 自动多一个 counter（`session.compaction.auto`），SSE 自动把新事件序列化出去（`BusEventDto.historyTokensBefore` / `thresholdTokens` 两个新可选字段）。

**Session-project-binding 注记（§3a.6）.** 本事件携带 sessionId 但不触 project binding——Agent 已经正确 re-read session 在 compaction 块**之后**（`Agent.kt:258`），所以 switch_project 的影响在下一轮才生效，compaction 看到的还是 "compact 前" 的 projectId。这个顺序是有意的：compaction 用旧 history，switch 用新 context。

**LLM context 成本（§3a.10）.** 零变化——没加 tool、没加 system prompt、没加 helpText。DTO 加的两个字段只在 SSE 流里占空间，不占 LLM input。

**Non-goals / 后续切片.**
- Model-aware threshold（Option B 的内容）—— 等 `ModelRef` 扩 context 字段或 `ProviderRegistry` 暴露 model metadata 那个 cycle 再做。
- "Compaction 完成" 事件 / "compaction 失败" 事件——当前 `Compactor.process` 失败会抛，Agent 循环会按普通 error 路径走；加专门事件等后续如果 UI 真需要分 sub-states 时。
- Bullet 里提的 "80% of model context" 阈值——见 Option B 拒绝理由。
