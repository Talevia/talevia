## 2026-04-21 — debt-split-agent-kt：把 616 行的 Agent.kt 拆成 orchestrator + AgentTurnExecutor（Rubric 外 / R.5.3 长文件）

Commit: `a574a51` (pair with `docs(decisions): record choices for debt-split-agent-kt`).

**Context.** `core/agent/Agent.kt` 在最近三轮 cycle（`compaction-overflow-auto-trigger` / `agent-run-state-machine` / `streaming-tool-output-parts`）之后从 backlog 记录的 581 行涨到 616 行——每一轮都合规地加了 bus 事件发布 + 状态机跳转，但单文件也因此稳定越过 R.5.3 500 行阈值。这是 `debt-split-projectquerytool` 做完后排在 P1 最顶的同类长文件债，扫描信号明确。

三类职责挤在同一 class body 里：
- **Multi-step orchestration**（`run` / `runLoop` / `cancel` / `finalizeCancelled` + inflight map）—— 高抽象层：调度步数、压缩触发、重试策略、取消终结。
- **Per-turn streaming**（`streamTurn`，120 行）—— 消费 `LlmEvent` 流，写 `Part` 到 store，发 `PartDelta`，fan out 到 tool dispatch。
- **Tool dispatch**（`dispatchTool`，100 行）—— 权限检查 + `RegisteredTool.dispatch` + 结果/失败持久化 + `AwaitingTool` ↔ `Generating` 状态翻转。

每次在 runLoop 里加一条新状态事件就要在 600 行里找对位置；PR diff 因为上下文过长变嘈杂；新 reviewer 上手要读完全文才能区分"这是单 turn 的事"还是"跨 turn 的事"。分层信号不清。

**Decision.** 新文件 `core/src/commonMain/kotlin/io/talevia/core/agent/AgentTurnExecutor.kt`（~328 行），把"单 turn 范围"的逻辑全搬过去：

| 导出 | 说明 |
|---|---|
| `internal data class TurnResult(...)` | 被 `Agent.runLoop` 消费（判断 `finish == TOOL_CALLS/ERROR`、决定重试）— 从 `Agent` 内嵌 `private` 升为 `internal` top-level。 |
| `internal data class PendingToolCall(...)` | `streamTurn` 内部的 pending-call bookkeeping，顺带搬过来。 |
| `internal fun buildSystemPrompt(base, currentProjectId): String?` | 纯函数，拼 "Current project: …" 横幅 + base system prompt。顶层 `internal` 以便两个文件都能调。 |
| `internal class AgentTurnExecutor(provider, registry, permissions, store, bus, clock, metrics, systemPrompt)` | 封装 8 个依赖，暴露 `suspend fun streamTurn(...)` 和 `private suspend fun dispatchTool(...)` / `upsertEmptyText(...)`。 |

`Agent` 在 ctor 之后立刻 `new` 一个 executor:

```kotlin
private val executor = AgentTurnExecutor(
    provider, registry, permissions, store, bus, clock, metrics, systemPrompt,
)
```

`runLoop` 里唯一的调用点从原来的 `streamTurn(asstMsg, history, input, currentProjectId)` 变成 `executor.streamTurn(...)`。签名完全不变；`TurnResult` 字段访问（`turnResult.finish` / `.error` / `.retriable` / `.retryAfterMs` / `.emittedContent` / `.usage`）都是 same-package `internal` 访问，零代价。

拆完行数：

| 文件 | 行数 | R.5.3 阈值 |
|---|---|---|
| `Agent.kt` | 359 | < 500 ✓ |
| `AgentTurnExecutor.kt` | 328 | < 500 ✓ |

**Byte-identical 不变量.** 和 `debt-split-projectquerytool` 一样，目标是 "公开行为字节相等"：

1. `Agent` 的 public API（ctor、`run`、`cancel`、`isRunning`）签名 + 默认值完全不变。`RunInput` top-level data class 也不变。5 个 `AppContainer`（CLI / Desktop / Server / Android / iOS）构造 `Agent(...)` 的方式一行都不用改。
2. `BusEvent` 的发布时序完全保留：`AgentRunStateChanged(Generating)` → `AwaitingTool` → `Generating` → `Idle/Failed/Cancelled`，加上 `PartDelta` / `SessionCompactionAuto` / `AgentRetryScheduled` / `SessionCancelled` 的顺序、`Part.Text` / `Part.Reasoning` / `Part.Tool` / `Part.StepStart` / `Part.StepFinish` 的 upsert 顺序，全都是从 `Agent` 挪到 `AgentTurnExecutor` 的代码块原样搬运，无重排。
3. 日志 key 不变：`run.start` / `run.finish` / `run.cancelled` 留在 `Agent`；`tool.ok` / `tool.fail` / `retry.scheduled` 挪到 executor / runLoop 对应位置。logger name 都用 `Loggers.get("agent")`，同一 channel。
4. Metrics key 不变：`agent.run.ms` / `provider.<id>.tokens.*` / `tool.<id>.ms`—前两个在 `Agent.run` / `streamTurn` 内原位，`tool.*` 在 executor 的 dispatchTool 内原位。
5. Retry 路径里 `RetryClassifier.reason(...)` / `retryPolicy.delayFor(...)` / `store.deleteMessage(asstMsg.id)` 的三步顺序完全保留，没有任何 delay 被挪到 mutex 内/外或改变时序。

没有新写 byte-diff snapshot 测试——复用既有 12 个 test file 覆盖：
- `AgentLoopTest` —— 正常多 step 循环 + tool call → tool result → stop。
- `AgentCancellationTest` —— `cancel()` 调用时 inflight 终结 + bus 发 `SessionCancelled` + `AgentRunStateChanged(Cancelled)` + `FinishReason.CANCELLED`。
- `AgentRetryTest` —— 瞬时错误重试，`AgentRetryScheduled` bus event 发出，重试后 assistant message 是 clean 单条。
- `AgentCompactionTest` —— 超阈值触发 compaction，`SessionCompactionAuto` 和 `Compacting` 状态 bus event 发出。
- `AgentRunStateTest` —— 状态机完整序列 `Generating → AwaitingTool → Generating → Idle`。
- `AgentToolFailureTest` —— tool 抛异常时 `ToolState.Failed` 写入 store，日志 `tool.fail`。
- `AgentPermissionIntegrationTest` —— 权限拒绝路径：`ToolState.Failed("Permission denied: …")`。
- `SessionProjectBindingTest` —— `buildSystemPrompt` 的 "Current project: X (from session binding; …)" / "<none>" 分支；每 step 重读 session 的 currentProjectId。
- `ConcurrentSessionsIntegrationTest` —— inflight map + mutex 并发语义。
- `SessionTitlerTest` + `InstructionDiscoveryTest` + `AnthropicAgentLoopIntegrationTest` —— 其余行为覆盖。

如果任何上面的 bus / part / log / metrics / finish-reason 行为漂移，这 12 个 test 中至少一个红。

**Alternatives considered.**

1. **Option A (chosen)**: executor class 吃 8 个 deps，Agent 持有 executor 字段。优点：deps 打包一次、多个方法（streamTurn / dispatchTool / upsertEmptyText）共享；调用点从 `streamTurn(...)` 变成 `executor.streamTurn(...)` 一眼可辨层次；没有全局函数污染 package。缺点：多一个 class 间接层（单 runLoop 调用多一次 field lookup，微不足道）。
2. **Option B**: 把 `streamTurn` / `dispatchTool` 做成顶层 `internal suspend fun` + 12 个参数列表（deps 一个个传）。拒绝：call site 变成 `streamTurn(provider, registry, permissions, store, bus, clock, metrics, systemPrompt, asstMsg, history, input, currentProjectId)` —— 12-arg 调用是典型的 primitive-obsession，阅读和重构都比 executor class 差。
3. **Option C**: 继续拆 `runLoop` 到 `AgentTurnLoop` 类、把 `retry` 块抽到 `AgentRetryOrchestrator`（backlog 原建议）。拒绝：`runLoop` 的三块（append user msg + compaction + step loop + retry）共享 `handle.currentAssistantId` / `userMsg.id` / `step` 计数等可变局部状态，拆到不同类里要么 hoist 到 executor state（引入可变 field，容易并发踩坑）要么继续传参。增益和风险不匹配——`runLoop` 135 行在单文件里可读，P1 未要求进一步拆到三文件。先把 Agent.kt 压下 500 是这次 cycle 的可闭环目标，后续如果 runLoop 继续膨胀到 300+ 行再考虑 Option C。
4. **Option D**: 用 Kotlin 的 `context(...)` context receiver 传依赖（实验特性）。拒绝：实验 API 稳定性未知；团队无 context receiver 经验；和 KMP multi-target 兼容性存疑（iOS / Android / JVM 编译器版本差异）。成熟前先用普通 class 依赖注入。
5. **Option E**: 把 `streamTurn` 做成 `Agent` 的 extension function（同 package 不同文件），访问 Agent 的 `private` 字段 via 特殊可见性。拒绝：Kotlin 不允许跨文件访问 `private` —— 要改成 `internal` 就等于让 `Agent` 的 8 个依赖全部变成 package-wide 可见，encapsulation 丢失得比 Option A 还多。

**Coverage.**

- `./gradlew :core:jvmTest` — 全绿（28s），12 个 agent 相关 test + 其余 common test 全过。
- `./gradlew :core:ktlintCheck` — 全绿。
- 5 端构建：`:core:compileKotlinIosSimulatorArm64`、`:apps:android:assembleDebug`、`:apps:desktop:assemble`、`:apps:server:assemble`、JVM core 全部成功。
- 测试观察到的 pre-existing 警告（`AgentCompactionTest` / `AgentRunStateTest` 的 `ExperimentalCoroutinesApi` opt-in）和本次拆分无关——那些是已存在的 `runTest` + `advanceUntilIdle` 用法，历史遗留。

**Registration.** 无 `AppContainer` 改动——`Agent(...)` ctor 签名不变。纯内部结构调整。

**§3a 自查.**
1. Tool count: 0 变化。PASS。
2. Define/Update: N/A。PASS。
3. Project blob: 不动 Project。PASS。
4. 状态字段: N/A（无新 `stale` / `pinned` 标志位）。PASS。
5. Core genre: 不含 genre 名词。PASS。
6. Session/Project binding: `buildSystemPrompt` 仍读 `currentProjectId` from `ToolContext` / session（语义不变，只是挪了位置）。PASS。
7. 序列化向前兼容: `TurnResult` / `PendingToolCall` 不持久化，`@Serializable` 无增减。PASS。
8. 5 端装配: `Agent` ctor 签名不变 → 5 个 `AppContainer` 零改动。PASS。
9. 测试覆盖语义: 12 个 agent test file 覆盖 cancel / retry / compaction / state-machine / permission / concurrency / project-binding 边界，非 happy path。PASS。
10. LLM context 成本: prompt / tool spec 一字节未改，每 turn token 数一模一样。PASS。

**Non-goals / 后续切片.**
- 进一步把 `runLoop` 的 compaction 块 / retry 块拆出（Option C）—— 等 runLoop 跨过 300 行阈值或者再加一个正交职责再做。
- `AgentTurnExecutor` 可不可以 pub 出来给测试 mock? 现在 tests 用 real executor + mock provider + fake store，覆盖够用；不 pub 保持内部类状态。
- `RetryPolicy.kt` + `RetryClassifier`（在 `RetryPolicy.kt` 里的 object）已经是独立文件，这次拆分不碰。
