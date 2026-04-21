## 2026-04-21 — agent-run-state-machine：显式 AgentRunState + 每次转移发 bus event（VISION §5.4 rubric）

Commit: `07d7f8b` (pair with `docs(decisions): record choices for agent-run-state-machine`).

**Context.** `Agent.kt` 里的状态一直是隐式的——"哪个协程在执行"、"是否撞到 compaction 分支"、"tool call 是否挂起" 四处散落在 when/if 里。UI / SSE / revert 三类消费者没法结构化回答 "agent 此刻在做什么？" / "上次停下时它在做什么？"，只能从 partial events（`PartDelta` / `PartUpdated` / `SessionCancelled`）凑一个大致图像。OpenCode `session/run-state.ts` 的做法是显式状态机 + bus 发射，是当前最成熟的行为参照（CLAUDE.md "OpenCode as a 'runnable spec'" 章节明确此映射）。

Backlog bullet 原文提到三件事：显式 sealed class、每次转移写入 session（或 bus event）、revert 能恢复到最近 Idle / AwaitingTool 边界。本 cycle **只做前两件**的 bus 路径——"写入 session" 和 revert-recovery 放到后续 cycle。见下方 Alternatives / Non-goals。

**Decision.** 新增 `core/agent/AgentRunState.kt`：

```
sealed interface AgentRunState {
    object Idle
    object Generating
    object AwaitingTool
    object Compacting
    object Cancelled
    data class Failed(val cause: String)
}
```

Transitions：
- 入 `Agent.run` → publish `Generating`
- `compactor != null && estimated > threshold` 块内 → publish `Compacting` → process → publish `Generating`
- `dispatchTool` 入口 → publish `AwaitingTool`；dispatchTool 末尾（无论 success / failure）→ publish `Generating`
- `run()` 正常收尾：`result.error == null` → `Idle`；否则 `Failed(result.error)`
- `catch (CancellationException)` → publish `Cancelled`（原有 `SessionCancelled` 保留，状态机事件是附加信号）
- `catch (Throwable)`（新增 catch arm）→ publish `Failed(t.message ?: t.className)` 再 rethrow

新 bus event `BusEvent.AgentRunStateChanged(sessionId, state: AgentRunState)` 在 `core/bus/BusEvent.kt`。`core/metrics/Metrics.kt` 给出 6 个 per-state 计数器（`agent.run.state.idle|generating|awaiting_tool|compacting|cancelled|failed`）。`apps/server/ServerModule.kt` 的 `eventName` + `BusEventDto.from` 补分支；`BusEventDto` 加 `runState: String?` + `runStateCause: String?` 两个可选字段。

**Alternatives considered.**

1. **Option A (chosen): Bus-only, no session persistence.**
   - 优点：零 SQLite 写放大。`Agent.run` 一轮可能经历 4–8 次转移（generating / tool1 / generating / tool2 / generating / compact / generating / idle），全部写 `Session.lastRunState` 会让长 session 的写压力翻倍；bus 是 in-memory pub/sub，publish 成本是 μs 级。
   - 缺点：进程重启后消费者丢失 "最后一次状态" —— revert 拿不到断点。但本来 revert 就没消费这个字段，之后真要用时再补。
2. **Option B: 写 `Session.lastRunState`。** 拒绝：当前 revert 代码（`SessionRevert.kt`）根据 message-id 裁切，不读 state 字段；在**没有真实消费者**的前提下加 state 字段是预防性工程。如果未来 revert / UI 需要 "断点恢复"，可以再加——而且那时候可能是 "最后一次 Failed / AwaitingTool 的 timestamp" 而不是 live state，shape 不一样。现在就塞会绑定错误 shape。
3. **Option C: 把 state 放进 `Message.Assistant` 的 metadata。** 拒绝：Message 是 append-only 持久化模型，state 变化却是 burst 式（每 turn 多次）；把 burst-type 数据塞 append-only 模型就是把 `Lockfile.entries` / `Project.snapshots` 的那种污染模式复刻一遍。
4. **Option D: 做 `State.onTransition(from, to)` 回调架构 + registry 注入 handler。** 拒绝：Effect.js-flavor 抽象；CLAUDE.md Anti-requirements 明确拒绝。bus event 已经提供同样的 observer 模式，没必要再开一个 hook 框架。

**Coverage.** 新 test `core/src/jvmTest/kotlin/io/talevia/core/agent/AgentRunStateTest.kt`：

- `plainTurnEmitsGeneratingThenIdle` — 最简路径。单一 text turn。断言**精确序列** `[Generating, Idle]`，确保没有多余 / 漏发事件。
- `toolTurnEmitsGeneratingAwaitingToolGeneratingIdle` — tool 调用路径。断言 `[Generating, AwaitingTool, Generating, Idle]`，特别验证 dispatchTool 末尾的 "回到 Generating" 边确实触发（漏这个回边的 bug 在早期写法里最常见）。
- `missingSessionProducesFailedTerminalState` — 错误终态。故意用不存在的 sessionId 让 runLoop 抛 IllegalStateException，断言 last state == `Failed(...)`。这也验证新增的 `catch (Throwable)` catch-arm 在 catch (CancellationException) 之**后**正确 rethrow。

既有 `AgentCompactionTest` 的 `autoCompactionPublishesSessionCompactionAutoEvent` 已经覆盖 compaction 路径的 `SessionCompactionAuto` 事件；它和 `AgentRunStateChanged` 的 Compacting 子事件配对发出但不重复（一个是 token 信号、一个是 coarse state），故不新增 compaction-focused state 测试——避免测试 redundancy。

**Registration.** 无 tool 注册变化。Bus event 新类型经 SharedFlow 自动流到所有订阅者。`/metrics` 自动多 6 个 counter（per-state）。SSE 自动序列化 `runState` + `runStateCause` 两个新字段。

**Session-project-binding 注记（§3a.6）.** 事件携带 sessionId；project 变化由 `switch_project` tool 单独触发，和 run-state 正交——本次不涉及。

**LLM context 成本（§3a.10）.** 零变化——不是 tool spec、不加 helpText、不加 system prompt 片段。新类型在 bus 上流，LLM 永远看不到。

**§3a 自查.**
- (1) Tool count: 0 变化。PASS。
- (2) Define/Update: N/A。
- (3) Project blob: 不动 Project。PASS。
- (4) **状态字段不做二元**：THIS TASK IS EXACTLY about explicit multi-state。6 个状态，非二元 flag。PASS。
- (5) Core genre: N/A。
- (6) Session ↔ Project binding: 正交。
- (7) 序列化向前兼容：`AgentRunState` 的 `@Serializable sealed interface`，`Failed(cause)` 有 required field 但 sealed interface 本身用 class discriminator。**不被持久化**（只在 bus 上流），所以旧 JSON / SQLite blob 不受影响。PASS。
- (8) 5-端装配：commonMain 定义 → 5 端通过 `core` 依赖自动获得。ServerModule DTO 更新确保 server/HTTP 端也暴露。PASS。
- (9) 语义测试：plain / tool / failed 三条路径 + 精确序列断言（不是"至少包含"，是"exactly equals"）。PASS。
- (10) LLM context：零。PASS。

**Non-goals / 后续切片.**
- **Revert recovery**：让 `SessionRevert` 读最后一次 `AwaitingTool` / `Compacting` 事件把用户带回断点前一步。需要先决定 state event 是否持久化（bus-only 现在不支持进程重启后查询），以及 `SessionRevert.revertToMessage` 的 API 怎么接收 "revert-to-state-boundary" 语义。另起 cycle。
- **Model-aware compaction threshold**（见 `docs/decisions/2026-04-21-compaction-overflow-auto-trigger.md` 的 Non-goals）。
- **`Generating → AwaitingTool` 的 concurrent tool-call 粒度**：多 parallel tool calls 当前只发一次 AwaitingTool + 一次 Generating。如果未来 parallel_tool_calls 普及，可能需要 per-call 子状态。当前没 driver 不做。
