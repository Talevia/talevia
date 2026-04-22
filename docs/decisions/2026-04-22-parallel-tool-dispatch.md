## 2026-04-22 — parallel-tool-dispatch：同一 turn 里多个 tool call 并发调度（VISION §5.2 / §5.4）

Commit: `b7ce6ab`

**Context.** Providers 常在同一 step 返回多个 `tool_use` / `tool_calls[]`（Anthropic content-block list、OpenAI `tool_calls` 数组）；模型也被鼓励把独立的读（`list_projects` + `session_query`）或独立的生成（两张不同镜头的 `generate_image`）打包到一轮里。`AgentTurnExecutor.streamTurn` 原实现在 `.collect { event -> when (event) is LlmEvent.ToolCallReady -> dispatchTool(...) }` 中**同步 await** 每个 `dispatchTool`，于是 N 个独立 tool 的 turn 延迟是 `sum(t_i)`，本应是 `max(t_i)`。当其中一条是 ffmpeg 导出或 AIGC 生成这种多秒级操作时，累加误差放大到分钟级。对应 rubric §5.2（工具集调度效率）+ §5.4（agent UX）。

**Decision.** 把 `.collect { }` 外包进 `supervisorScope { }`，`ToolCallReady` 事件触发时用 `launch { dispatchTool(...) }` 派生子协程，把 Job 存进本地 `dispatchJobs` 列表；stream `collect { }` 返回（看到 `StepFinish` 或 provider 关闭流）后 `dispatchJobs.joinAll()`，再返回 `TurnResult`。关键不变式：
- **权限检查串行化**：`streamTurn` 本地持有一把 `permissionMutex: Mutex`；`dispatchTool` 在 `permissions.check(...)` 调用外 `permissionMutex.withLock { }`。工具 body 本身仍并发执行——只有权限审问（可能唤起交互 prompt）被串起来。同一终端不会看到多条 prompt 互相打断。
- **sibling 失败隔离**：用 `supervisorScope` 而非 `coroutineScope`。某个 dispatch 内部（例如 `runCatching` 未能拦截的非 `Throwable`）异常上抛时不会取消兄弟 dispatch。
- **map 竞态消除**：`dispatchTool` 不再读写共享的 `pending: MutableMap<CallId, PendingToolCall>`；`ToolCallReady` 事件在流线程上先解析 `handle`，作为参数显式传入 `launch { dispatchTool(..., handle, permissionMutex) }`。
- **TurnResult 语义不变**：`joinAll()` 落在 `supervisorScope` 内、`return TurnResult(...)` 之前，确保上层 `Agent.runLoop` 开始下一轮 `MessageWithParts` history 快照时，本轮所有 tool Part 都已由 `store.upsertPart` 落库，不会出现未决 `ToolState.Pending` 漏进下一轮。

Files touched: `core/src/commonMain/kotlin/io/talevia/core/agent/AgentTurnExecutor.kt`（+18 / -4 大致）。新测试 `core/src/jvmTest/kotlin/io/talevia/core/agent/ParallelToolDispatchTest.kt`。

**Alternatives considered.**

- *Option A (chosen): `supervisorScope` + `launch` + `Mutex` 方案*。每 dispatchTool 一个 Job；`joinAll` 汇聚；权限检查点串行。理由：(1) 行为改动最小——`TurnResult` 合同不变，`Agent.runLoop` 不改；(2) 复用 Kotlin 结构化并发的语义（父协程取消→子协程自动取消），和 `agent-interrupt-via-bus` 的 cancel 路径自然正交；(3) 仅动 `AgentTurnExecutor` 一个文件。
- *Option B: `async { }` + `awaitAll()` over a `List<Deferred<Unit>>`*。拒绝：`dispatchTool` 返回 `Unit`（所有 side effect 是 `store.upsertPart` / `bus.publish`），用 `Deferred` 反而要求 `.await()` 在 `collect { }` 内部维护——等价于顺序 await，丢了并发收益。`launch` + `joinAll` 语义更贴切。
- *Option C: 改 `Agent.runLoop`，在 `streamTurn` 返回后统一 dispatch 所有 tool call*。拒绝：需要把 tool-call 数组从 `streamTurn` 暴露出来，扩张 `TurnResult` API；且会把 tool 执行时机推迟到流完全读完之后，放弃 "ToolCall 一边流式进来一边执行" 的机会（未来如果 provider 发 `ToolCallReady` 早于 `StepFinish`，串行版会白白等待）。Option A 保持 "ready 即 dispatch" 语义。
- *Option D: 全局 `Dispatchers.Default` 池里 `GlobalScope.launch`*。拒绝：违反结构化并发原则，`Agent.cancel(sessionId)` 不能传递到 tool 任务上，与现有 cancel 路径（`currentCoroutineContext().job.cancel()`）背道而驰。

**Coverage.**

`core/src/jvmTest/kotlin/io/talevia/core/agent/ParallelToolDispatchTest.kt` 三个 case：

1. `twoIndependentToolsDispatchConcurrently` — 注册一个 `SlowTool(delay=1_000ms)` 并让 FakeProvider 返回 2 个 `ToolCallReady`，断言 `testScheduler.currentTime` 涨幅 < 1_500ms（并发模式下 ≈1_000；串行会 ≥2_000），并断言 tool 内部共享 `AtomicInteger` 的 `maxConcurrency == 2`。
2. `siblingDispatchSurvivesOneFailure` — 注册 `SlowTool` + `ThrowingTool`，断言 `SlowTool` 依然 `ToolState.Completed`、`ThrowingTool` 是 `ToolState.Failed`。验证 `supervisorScope` 的 sibling 隔离语义。
3. `permissionPromptsSerialiseAcrossConcurrentDispatches` — 用 `SynchronisingPermissionService` 在 `check()` 内部递增 / 递减 active counter 并在中间 `delay(20)`，断言 `maxActivePermissionChecks == 1`。验证 `permissionMutex` 确实串行化了权限检查。

现有 `AgentLoopTest` / `AgentCancellationTest` / `AgentToolFailureTest` / `AgentCancelViaBusTest` 全部继续通过——`TurnResult` 合同、`ToolState` 生命周期、cancel 语义未改。`./gradlew :core:jvmTest` 绿、`DEVELOPER_DIR=… :core:compileKotlinIosSimulatorArm64` 绿、`:core:ktlintCheck` 绿。

**Registration.** 无需注册——纯 Core `AgentTurnExecutor` 内部调度改造，不加新 tool，不动 `AppContainer`，不动 session / permission / provider 协议。
