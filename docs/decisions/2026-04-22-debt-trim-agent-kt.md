## 2026-04-22 — Extract bus-driven cancel watcher out of Agent.kt (VISION §3a-3)

**Context.** Backlog bullet `debt-trim-agent-kt`：`Agent.kt` 459 行，自
`agent-interrupt-via-bus` 落地后把 `bus.subscribe().filterIsInstance<BusEvent.SessionCancelRequested>()`
侧通道塞进主类的 init block + `awaitCancelSubscriptionReady` 测试钩子 +
`CompletableDeferred` ready signal —— 一共 ~30 行属于"把 bus 事件翻译成
cancel 调用"这个独立 concern，和 session loop / compaction / retry / fallback
状态机没有逻辑耦合。主类里夹着这段让阅读 orchestration flow 时要先跳过它。

Rubric delta：§3a-3 "长文件" `Agent.kt` 459 → 441 行 (−18 + 新文件 59 行)。
幅度不大但拆分合理：ring-fence 一个独立 concern 到独立文件，减少"改 Agent
必须同时 review 这段 bus 接线"的 churn 噪音。

**Decision.** 新增 `core/src/commonMain/kotlin/io/talevia/core/agent/AgentBusCancelWatcher.kt`
（internal class，59 行）承接三个职责：

1. Subscribe once to `bus.events`，filter `BusEvent.SessionCancelRequested`，
   route to injected `cancelSession: suspend (SessionId) -> Unit`。
2. `ready: CompletableDeferred<Unit>` 在 `onSubscription` 回调里 complete，
   暴露为 `awaitReady()` 测试钩子。
3. 吞掉 `runCatching` 异常（cancel during shutdown），保留原语义。

`Agent` 构造 `cancelWatcher = AgentBusCancelWatcher(bus, backgroundScope,
cancelSession = { cancel(it) })`，`awaitCancelSubscriptionReady()` 一行转发
到 `cancelWatcher.awaitReady()`。

关键行为保留：
- SharedFlow 语义的 "subscribe before publish" race：`ready.complete` 仍在
  `onSubscription` 回调里，测试靠 `awaitReady()` 同步发布 vs 订阅。
- Idle session cancel 的幂等性：`Agent.cancel(id)` 对未在飞的 session 返回
  false，watcher 里的 `runCatching` 对返回值无关心，行为一致。
- `backgroundScope` 注入：scope 生命周期仍由 Agent 拥有，watcher 不创建
  自己的 scope。

**Alternatives considered.**

1. **不拆** — 30 行 sub-concern 留在 init block。优点：零改动。缺点：每次改
   Agent session loop 的 reviewer 要先识别"这段 init 代码跟我本次修改无关"，
   长期 churn 成本 > 一次性拆分成本。否决。
2. **top-level extension function `fun Agent.wireCancelBus(scope, bus)`** ——
   过度节约文件数；extension function 无法持有 `CompletableDeferred` state，
   `awaitReady` 变得别扭。否决。
3. **完全内联到 `AgentTurnExecutor`** —— 方向错，TurnExecutor 是 per-turn
   unit，cancel watcher 是 per-Agent-instance 的 session-wide 侧通道；放到
   TurnExecutor 会让 cancel 跟 turn 生命周期绑定，打破 "cancel idle session
   is a no-op" 的语义。否决。

业界共识对照：
- OpenCode 的 `packages/opencode/src/session/prompt.ts` 把类似的"external
  cancel signal → in-flight coroutine abort"通过 Effect.js 的 Scope / Fiber
  primitives 实现；Talevia 已明确 **不抄 Effect.js 结构**，用 Kotlin 的
  `Mutex` + `Job.cancel()` + bus SharedFlow 组合是本项目已确立的模式
  （见 `docs/decisions/2026-04-21-*agent-cancel*` 系列）。本 decision 不改
  机制，只把它拎出主文件。

**Coverage.**
- `:core:jvmTest` 全绿，含 `AgentRunStateTest` / `AgentCompactionTest` 所有
  cancel-相关 case（cancel during stream, cancel idle session, publish-before-
  subscribe race via `awaitCancelSubscriptionReady()`）。
- `:apps:cli:test` ✓、`:apps:desktop:assemble` ✓、`:apps:android:assembleDebug` ✓、
  `:core:compileKotlinIosSimulatorArm64` ✓。
- `ktlintCheck` 绿（`ktlintFormat` 清掉 `CompletableDeferred` / `onSubscription` /
  `filterIsInstance` 三个 unused import）。

**Registration.** 无 —— 纯内部重构，`AgentBusCancelWatcher` 是 `internal`
visibility，不暴露到 5 个 AppContainer 或任何 public API。
