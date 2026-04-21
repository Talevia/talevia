## 2026-04-21 — session-status-events-on-bus：既有 `BusEvent.AgentRunStateChanged` 已满足本 bullet，删 backlog 入口（VISION §5.4）

Commit: 本 cycle 无 `feat` commit；预先工作由 `07d7f8b` (`agent-run-state-machine`) 落地。

**Context.** P2 bullet `session-status-events-on-bus` 的原文方向：
> OpenCode 有 `session/status.ts` 把 session 状态变化发到 bus。Talevia 只在 Agent loop 内用局部变量追状态，UI 拿不到 "currently generating" / "awaiting tool" / "compacting" 这类信号（只能轮询 messages）。**方向：** 加 `SessionStatusEvent` 子类（BusEvent），状态转移时发到 `EventBus`。

Bullet 备注明确："和 `agent-run-state-machine` 是配对关系——P0 做完那条后再做这个才有意义。"

**Decision.** 不写任何代码。核对仓库现状后确认：
- `core.bus.BusEvent.AgentRunStateChanged(sessionId, state: AgentRunState)` 已存在（`BusEvent.kt:157`）。实现 `SessionEvent` 接口暴露 `sessionId`，UI 订阅可按 session 路由。
- `AgentRunState` sealed 覆盖 5 个语义状态 + 终态：`Idle` / `Generating` / `AwaitingTool` / `Compacting` / `Cancelled` / `Failed(cause)`。正好对应 bullet 点名的 "currently generating" / "awaiting tool" / "compacting"。
- `Agent.kt` / `AgentTurnExecutor.kt` 每个状态转移点都 `bus.publish(BusEvent.AgentRunStateChanged(...))`：
  - `Agent.run()` 起始 → `Generating`。
  - `Agent.run()` 正常完成 → `Idle` 或 `Failed(error)`。
  - `Agent.run()` 被 cancel → `Cancelled`。
  - `Agent.run()` 异常抛出 → `Failed(message)`。
  - `Agent.runLoop()` 压缩开始 → `Compacting`；压缩完 → `Generating`。
  - `AgentTurnExecutor.dispatchTool()` 进入 tool → `AwaitingTool`；退出 tool → `Generating`。
- `AgentRunStateTest`（`core/src/jvmTest/.../agent/AgentRunStateTest.kt`）含 3 个用例断言 bus 序列正确：`plainTurnEmitsGeneratingThenIdle`、`toolTurnEmitsGeneratingAwaitingToolGeneratingIdle`、`failedStateOnInvalidSession`。

原 bullet 预期的 SessionStatusEvent = AgentRunStateChanged。命名差异无技术含义（"Session" 和 "AgentRun" 在单 agent 每 session 场景下语义等价，bullet 作者大概率在引用 OpenCode 的命名而没意识到我们已有等效设施）。

**动作**：
- 不新增任何 `BusEvent` 子类。加一个 `SessionStatusEvent` alias 会是纯 churn（和 `AgentRunStateChanged` 语义重复）。
- 不新增 tool（bullet 未要求）。
- 删除 bullet，把 decision 文件当作"已完成"的归档记录。

**Alternatives considered.**

1. **Option A (chosen)**: 记为 subsumed-by-prior，删 bullet。优点：零 churn；LLM context 不改；tool count 不动。**缺点**：没有新产出的 cycle 看起来像"空跑"。但老实总比造 churn 好。
2. **Option B**: 增加 type alias `typealias SessionStatusEvent = BusEvent.AgentRunStateChanged`。拒绝：两种名字指同一事件会让新人不知道订阅哪个；grep 也要双向查。
3. **Option C**: 重命名 `AgentRunStateChanged` → `SessionStatusChanged`。拒绝：重命名成本大（5 端 container / 测试 / metrics key / SSE dto 都要改），且当前名字更精确 —— "AgentRun" 明示是 Agent.run() 生命周期，而 "session status" 会让人联想到 session archived / compacted / deleted（那些是单独 events）。
4. **Option D**: 扩展能力——加 `Agent.currentState(sessionId): AgentRunState` 查询 API，让 UI 可以在订阅前先取当前 snapshot。拒绝：bullet 未要求；引入 stateful cache（per-session 当前状态 map）增加并发边界；UI 订阅 bus 已够用（启动时订阅、收到第一个事件后知道当前态）。若真有冷启动需求，单独 cycle 做。
5. **Option E**: 增加 `session_query(select=status, sessionId=X)` 作为 read-time 查询。拒绝：同 Option D 的理由 —— 需要 state cache 作支撑，本 bullet 不要求，scope creep。

**Coverage.** 已有的 `AgentRunStateTest` 3 个用例 + `AgentCompactionTest.autoCompactionPublishesSessionCompactionAutoEvent` 的 compaction edge 覆盖 = 本 bullet 关心的全部状态迁移路径都有 bus-event 断言保护。无需新测试。

**Registration.** 无装配变化——既有 5 端 AppContainer 里 Agent 构造路径 + `BusEvent` 序列化支持（`BusEventDto.from` / `Metrics.counterName`）已经在 agent-run-state-machine cycle 里落地。

**§3a 自查.**
1. Tool count: 0 变化（零代码）。PASS。
2-10: N/A（无代码改动）。PASS 全线。

**Non-goals / 后续切片.**
- 如果 UI 消费者发现 "启动时需要当前状态 snapshot 才能渲染" 的需求，再单独加一个 `Agent.currentState(sessionId)` read API。门槛是真实 UI bug，不是猜需求。
- OpenCode 的 `session/status.ts` 如果还包含"状态持久化到数据库 → 跨重启恢复"语义，那是另一个独立的 debt（`Session.lastAgentState` 字段？），但本 bullet 只提了 bus 信号，不扩 scope。如果未来 CLI restart 丢失进度让用户困惑，单独 cycle 做。
- `agent-run-state-machine` 做了状态机，`session-status-events-on-bus` 本应是它的 UI-facing 一半但被合并落地。未来做 backlog repopulate 时把这类"子工作"在同一个 bullet 里写清楚，不要拆成两条独立 bullet 导致第二条变 stale。
