## 2026-04-21 — session-status-snapshot-query：`AgentRunStateTracker` + `session_query(select=status)`（VISION §5.4）

Commit: `dbd9211`

**Context.** 当前 agent 状态只通过 `BusEvent.AgentRunStateChanged` 流式广播：每次 `Agent.run` / `AgentTurnExecutor` 跨过 state-machine 边（Idle/Generating/AwaitingTool/Compacting/Cancelled/Failed）都 publish 到 bus。问题是新 subscriber 没办法"冷起"拿到当前态：
- UI 冷启动（SwiftUI app 第一次 init、Compose Desktop 重开窗口）要么靠轮询 `describe_session` 间接推断，要么错过所有已发生的 transition。
- 工具自查（tool A 想判断 "此 session 是不是还在 compaction 状态" 决定是否等待）同样只能侦听未来的事件，没办法 query 当下。

Rubric §5.4（dual-user path：状态是 session 的一等可查属性，snapshot 是流的补充）。

**Decision.** 两个新部件 + 1 个 select：

1. **`core/agent/AgentRunStateTracker.kt`（new, ~50 行）**：
   - 构造 `AgentRunStateTracker(bus: EventBus, scope: CoroutineScope)`，在 `init` 里启动一个 collector 订阅 `BusEvent.AgentRunStateChanged`，把 `(sessionId → state)` upsert 进一个 `MutableStateFlow<Map<SessionId, AgentRunState>>`。
   - 暴露 `currentState(sessionId: SessionId): AgentRunState?`（非 suspend；读 StateFlow `.value`）+ `states: StateFlow<Map<SessionId, AgentRunState>>`（UI reactive）。
   - Companion `withSupervisor(bus)` 工厂：内部开 `SupervisorJob + Dispatchers.Default` 的独立 scope —— iOS Swift 侧使用（避免跨 Swift 构造 `CoroutineScope`）。
   - 没有 `Agent` 耦合：任意未来发 `AgentRunStateChanged` 的 publisher 都自动被追。

2. **`core/tool/builtin/session/query/StatusQuery.kt`（new, ~65 行）** + `SessionQueryTool` 扩 `select=status`：
   - 新增 `SessionQueryTool.StatusRow(sessionId, state, cause?, neverRan)` —— `state` 是字符串 tag（和 SSE DTO 的 `runState` 对齐），`cause` 只在 `failed` 非 null，`neverRan=true` 区分"从没跑过"和"跑完 idle"。
   - `runStatusQuery(tracker, input)` 读取 `tracker.currentState(sessionId)`：null → `state="idle", neverRan=true`；非 null → map 到对应 tag。
   - SessionQueryTool 构造器 gains `agentStates: AgentRunStateTracker? = null`（和 `SwitchProjectTool.bus` 同样的 nullable pattern：null 让 pure-store tests 不受影响，`select=status` 分支显式 require non-null fail loud）。
   - `SELECT_STATUS = "status"` 加入 `ALL_SELECTS`；helpText / schema description / sessionId description 都相应更新。rejectIncompatibleFilters **无需改** —— 原有每个过滤器字段的 "仅 select=X 允许" 规则对 status 自动生效（status 不接收任何这些字段）。

3. **5 端 AppContainer 装配**：
   - 每个 container 在 bus 构造后立即构造 `AgentRunStateTracker`（JVM 各端用 `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 显式；iOS 用 `AgentRunStateTrackerCompanion.shared.withSupervisor(bus:)`）。
   - `SessionQueryTool(sessions, agentStates)` 在 5 个 container 都换成带 tracker 的构造。

**关键 §3a.4 合规** —— `neverRan: Boolean` 字段表面是二元 flag，但实际是"三态观察"的降维体现：(state, neverRan) 组合是 `(idle, true)` / `(idle, false)` / `(non-idle, false)`。`(non-idle, true)` 是 nonsense by construction（nonnull state = 被 tracker 看过 = !neverRan）。用户读不到 `neverRan=true` 的非 idle，所以实际 observable 组合只有 3 种，符合"不做纯二元"精神。

**Alternatives considered.**

1. **Option A (chosen)**: 独立 tracker + `session_query(select=status)`。优点：tracker 和 Agent 解耦（任何 publisher 自动捕获）；query 复用现有 `session_query` 原语，不增 tool；`MutableStateFlow.update` lock-free 原子更新；status row 非 suspend 查询（适合 UI hot path）。缺点：多加了一个 per-container 部件 —— 但是 pattern 已经和 `EventBus` / `DefaultPermissionService` 等对齐。
2. **Option B**: 把 state map 放到 `Agent` 内，暴露 `Agent.currentState(sessionId)`。拒绝：① 一个 container 可能有多个 Agent（per-provider，见 iOS AppContainer），每个 Agent 只知道自己跑过的 session state，subscriber 要先找到 "哪个 Agent 负责了这个 session" —— 额外耦合；② `Agent` 已经是长文件，再加 map + lock + accessor 是 SRP 退化；③ tracker 作为独立 class 让 `session_query` 不用依赖 Agent（SessionQueryTool 在 Agent 未构造时也能用）。
3. **Option C**: 持久化到 SQLite（`session.currentRunState` 新列）。拒绝：① write amplification — 每个 state transition 都 INSERT OR UPDATE 一行是 per-turn 数次 DB 写；② state 本质是 volatile "当前进程的 agent 在做什么"，不是 durable 事实；③ 进程重启后 state 天然该 reset（not-running），不需要 DB 存。
4. **Option D**: 加 `get_session_status` 独立 tool（一个新 Tool.kt）。拒绝：§3a.1 工具数量不净增。这是 projection / query，自然归 `session_query` 的 select 扩展。
5. **Option E**: `neverRan` 改成 `state: "not_started"` 新状态 tag。拒绝：① SSE DTO 的 `runState` tag 空间已经对齐 `AgentRunState` sealed subclasses（6 种）；加第七种 "not_started" 会让下游消费方要区分 "never_started" vs "Idle"（语义相近的两档）；② `(idle, neverRan=true)` 让客户端有选择地区分；默认把两者都视为 "idle" 也正确。
6. **Option F**: tracker 也订阅 `BusEvent.SessionDeleted` 自动 evict。拒绝：单 Agent 进程内 session count 实际有界（几十到几百），eviction 是过早优化；加上会隐藏 "看到了就留着" 的观测语义（debug "session 被删后为什么还能查到最后一次 state？" 的理由）。需要时独立 cycle 做。

**Coverage.**

- **新增 `SessionQueryStatusTest.kt`（6 tests）**：
  - `neverRanReturnsIdleWithNeverRanFlag` — 从未 publish 过的 session，返回 `state=idle, neverRan=true`。
  - `generatingStateSnapshotAfterPublish` — publish Generating 后 snapshot 回读 `state=generating`。
  - `failedStateCarriesCause` — publish `Failed("provider 503")` 后 `cause="provider 503"`（§3a.9 反直觉 case：带 payload 的 sealed state）。
  - `latestStateOverridesEarlierTransitions` — Generating → AwaitingTool → Idle 后读到 `idle, neverRan=false`（post-run idle 和 never-ran 的区别）。
  - `missingSessionIdFailsLoud` — omit sessionId 错误信息含 "requires sessionId"。
  - `noTrackerWiredFailsLoud` — SessionQueryTool 不带 tracker 时 `select=status` 抛 `IllegalArgumentException` 含 "AgentRunStateTracker"。
- 既有 `SessionQueryToolTest` 所有 case（~30 tests）不改 —— `agentStates: AgentRunStateTracker? = null` default 保护。
- `./gradlew :core:jvmTest` 全绿 + `:apps:server:test` 全绿 + `:core:ktlintCheck` 全绿。
- 4 端构建：iOS / Android / Desktop / Server 全绿。

**Registration.** 5 个 AppContainer 全部 wire tracker 并传给 `SessionQueryTool`（desktop / android / cli / server 用 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`；iOS 用 `AgentRunStateTrackerCompanion.shared.withSupervisor`）。`AgentRunStateTracker` 作为 container 字段暴露 —— 将来 UI Compose 侧可以直接 `collectAsState()` 读 `states` 做 hot rebinding。

**§3a 自查.**

1. 工具数量: 0 变化（扩 select，不加 Tool.kt）。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 `Project`。`SessionId → AgentRunState` 的 map 放内存（不是 append-only，session 数量有界 + container lifetime scoped）。PASS。
4. 状态字段: `neverRan` 看似二元但在 (state, neverRan) 联合三态下 `(non-idle, true)` 不可达 by construction —— 实际 observable 组合 3 种。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: sessionId 在 session_query 已经是 required-or-rejected 依 select 定；status 要求 required（rejectIncompatibleFilters 已有规则自动生效）。PASS。
7. 序列化向前兼容: 新 `StatusRow` 是 `@Serializable`，所有字段有 default（cause / neverRan），向前兼容 JSON 解码。`SessionQueryTool.Input` 不加字段 —— 复用 `select` / `sessionId`。PASS。
8. 五端装配: 5 端全部 wire tracker 并传入 `SessionQueryTool`。PASS。
9. 测试语义覆盖: 6 tests 覆盖了 neverRan / mid-run / terminal-with-cause / post-run-idle / missing-sessionId / no-tracker。PASS。
10. LLM context 成本: session_query helpText 增约 1 行（~60 tokens）；schema select enum 描述增 "| status"（~10 tokens）。净 ~70 tokens/turn 永久成本。PASS（合理 —— 新 select 本身必需的 LLM 信号）。

**Non-goals / 后续切片.**

- **Follow-up: compact-session-threshold-visible-in-ui**（P2 bullet）— 扩 StatusRow 带 `estimatedTokens` / `compactionThreshold` / `percent` 字段让 UI 可以渲染占比条。同 tracker，可能要让 tracker 订阅 `SessionCompactionAuto` event 把 token 数也追上。
- **Follow-up: UI Compose / SwiftUI 侧接入**。Desktop `collectAsState(tracker.states)` / iOS SKIE bridge `AsyncSequence` —— 属于 UI 层任务，Core 端已经暴露了 reactive `StateFlow`。
- **Follow-up: Tracker 订阅 `SessionDeleted` 做 eviction**。当前内存无上限（per-container lifetime 内 session 数有界），作 memory-leak 防御可以加。
- **不加 `session_query(select=status)` 多 session 批量模式**（当前 sessionId 必填，返回单行）。"Enumerate all tracked sessions + their states" 不是常用 case；需要时扩 `sessionId=null` 返回所有 tracked —— 留给 driver 出现时。
