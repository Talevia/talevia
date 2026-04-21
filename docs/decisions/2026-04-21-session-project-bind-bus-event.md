## 2026-04-21 — session-project-bind-bus-event：`BusEvent.SessionProjectBindingChanged` fires on switch_project（VISION §5.4）

Commit: `17ea211`

**Context.** `SwitchProjectTool` 在成功改 `Session.currentProjectId` 后只能靠 `SessionStore.updateSession` 内部发出的 `BusEvent.SessionUpdated`（覆盖性 "something changed" 信号）通知，subscriber 拿不到"哪个字段变了" / "从哪个 project 切到哪个 project" 这种具体信息。UI "当前 project" 指示器、metrics pipeline、per-session 审计日志都被迫要么每次都重新 `getSession` 再 diff，要么完全忽略 binding 变化。Rubric §5.4（dual-user path：binding 是 session 的一等状态，应该有一等事件）。

**Decision.** 三步落地：

1. **新 `BusEvent.SessionProjectBindingChanged` sealed subclass** (在 `core/bus/BusEvent.kt` 的 `SessionEvent` 家族下)：
   ```kotlin
   data class SessionProjectBindingChanged(
       override val sessionId: SessionId,
       val previousProjectId: ProjectId?,   // null = first-time bind
       val newProjectId: ProjectId,          // always set (unbind not exposed on session lane)
   ) : SessionEvent
   ```
   KDoc 显式说明：fork_session 不发此事件（新 session 的初态通过 `SessionCreated` 传达，不是"change"）；same-id no-op 不发（`SwitchProjectTool` 早 return）。

2. **`SwitchProjectTool` 发事件**：
   - 构造器加 optional `bus: EventBus? = null`（默认 null 保持现有 test rig 免改；5 个生产 AppContainer 都传 `bus`）。
   - `updateSession(...)` 后 `bus?.publish(SessionProjectBindingChanged(sid, session.currentProjectId, pid))`。previousProjectId 用 `session.currentProjectId`（mutation 前的值，闭包捕获正确）；newProjectId 用本轮解出的 `pid`。
   - **`Metrics.counterName`** 增加对应分支 `"session.project.binding.changed"`（exhaustive when 编译强制）。

3. **Server SSE DTO**：
   - `ServerModule.eventType` 增 `"session.project.binding.changed"` 分支。
   - `BusEventDto` 增 `previousProjectId: String? = null` 字段；`BusEventDto.from(e)` 增 SessionProjectBindingChanged 分支 —— 用已有的 `projectId` 字段装 newProjectId（和 `SessionReverted` 的 projectId 语义一致：都是"current context 的 project"），`previousProjectId` 专门给本事件。

**关键兼容 note**: `bus: EventBus? = null` 默认值让旧 test call site `SwitchProjectTool(rig.sessions, rig.projects, fixedClock)` 不变。生产 5 端全部显式传 `bus`（desktop/android/cli/server/ios）—— forgot-to-wire 风险由 new tests（断言 event published）挡住。

**Alternatives considered.**

1. **Option A (chosen)**: 新 `SessionProjectBindingChanged` 事件 + `bus` 注入 + 3 个 new test cases。优点：event 携带 `previousProjectId` + `newProjectId` 完整对（non-ambiguous），subscriber 不用自己 diff；保持已有 `SessionEvent` 家族 KDoc 风格；metrics/SSE 接入自然；tests 覆盖 3 种关键路径（changed / first-bind / no-op-no-publish）。
2. **Option B**: 给 `BusEvent.SessionUpdated` 加 `reason: String?` 字段（"project_binding" / "title" / "archived" / ...）。拒绝：① `SessionUpdated` 已经到处 fire 且 subscriber 只关心"something"；② 加 string 标签是 typed-event 退化成 dynamic dispatch；③ SSE 消费方要切字符串 tag 做分支，失去 sealed hierarchy 的 type safety。
3. **Option C**: 不加新事件，让 UI / metrics 直接订阅 `SessionUpdated` 然后 `getSession` diff 前后 `currentProjectId`。拒绝：multi-thread / async 下先后顺序不定，diff window 可能 miss（subscriber 收到 SessionUpdated 时 session 已被下一次 update 覆盖）；每个订阅者都实现一份 diff 逻辑是重复代价；bus 应该是 "事实源" 不是 "change notification"。
4. **Option D**: fork_session 也 fire 此事件（每次 fork 一个新 session 时发）。拒绝：新 session 的 binding 是 **初态**（SessionCreated 已经承载 projectId 语义），不是 change。强加事件会让 subscriber 在 fork-many 场景出现 N×2 重复事件（SessionCreated + SessionProjectBindingChanged-with-null-previous）—— 对齐 OpenCode `SessionCreated` 的"初态=创建"语义更干净。
5. **Option E**: `bus` 做 required constructor param。拒绝：8 个测试 call site 要同步改，且旧的 `SwitchProjectToolTest` 多数 case 不关心 event；nullable default 是 "observability 可选"的诚实表达（和 `Clock.System` 默认同样是"production 必填、test 可省"语义）。
6. **Option F**: `previousProjectId` 用特殊 sentinel `ProjectId("")` 而不是 nullable。拒绝：empty string 是 invalid identifier；nullable 对 Kotlin 类型系统更友好；SSE DTO 已有 optional-field pattern。

**Coverage.**

- **新增 3 个测试** 在 `SwitchProjectToolTest.kt`:
  - `changedBindingPublishesBusEvent` — p-a → p-b 发出 event, previousProjectId=p-a, newProjectId=p-b。
  - `firstBindingFromNullPublishesEventWithNullPrevious` — null → p-first 发出 event, previousProjectId=null。
  - `sameIdNoOpDoesNotPublishEvent` — p-a → p-a no-op, 0 events（§3a.9 反直觉边界）。
- 既有 8 个测试 call site 不改（`bus: EventBus? = null` default 保护）。
- `./gradlew :core:jvmTest` 全绿。`./gradlew :apps:server:test` 全绿（BusEventDto wiring 编译 + 既有 SSE 路由 round-trip test 不受影响）。
- `./gradlew :core:ktlintCheck` 全绿。
- 4 端构建：iOS / Android / Desktop / Server 全绿。
- `Metrics.counterName` 的 exhaustive `when` 强制加了分支 —— 编译器保护漏接的 counter tag。

**Registration.** 5 个 AppContainer（`apps/desktop` / `apps/android` / `apps/cli` / `apps/server` / `apps/ios`）每个 `SwitchProjectTool(...)` 注册处加 `bus = bus`（iOS 语法 `bus: self.bus`）。`ServerModule.eventType` + `BusEventDto.from` 加 `SessionProjectBindingChanged` 分支，新 DTO 字段 `previousProjectId: String? = null`。`Metrics.counterName` 加 counter tag。

**§3a 自查.**

1. 工具数量: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 `Project`。PASS。
4. 状态字段: `previousProjectId: ProjectId?` 是 "previously unbound" 的自然三态（null / 有值），不是二元 flag。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: **这就是本轮核心** —— 让 binding 有一等事件。PASS。
7. 序列化向前兼容: `BusEvent.SessionProjectBindingChanged` 是新增 sealed subclass —— 旧代码路径没依赖它，新 subscriber 通过 `is` 检查匹配。SSE `BusEventDto` 新字段 `previousProjectId: String? = null` 有 default，既有消费方解码不受影响。`SwitchProjectTool` 构造器 `bus` 参数 nullable default — 既有调用点不改。PASS。
8. 五端装配: 全部 5 个生产 container 都传 `bus`；test rig 用 default null + 3 个专门 test 覆盖 event 路径。PASS。
9. 测试语义覆盖: 3 个测试（changed/first/no-op）覆盖 3 条关键路径。同步-cancel job 保证不泄漏；Dispatchers.Unconfined 让 `bus.publish` 在 `execute` 返回前被 subscriber 消费（单线程测试确定性）。PASS。
10. LLM context 成本: 事件是 Bus-side，LLM 看不到。`switch_project` 的 schema / helpText 不变。0 tokens/turn。PASS。

**Non-goals / 后续切片.**

- **Follow-up: fork_session 新 binding semantic 决策文档化**。目前 fork_session 继承 parent 的 binding 通过 SessionCreated 传达，本决策 KDoc 记录了"不 fire binding-changed"；如果将来有 subscriber 需要"every bind change regardless of origin" 行为，可以单开 cycle 讨论是否加 `BindingEstablished` 初态事件。
- **Follow-up: UI 侧接入**。Desktop/iOS 当前 "current project" 指示器是否订阅新事件，属于 UI 层任务，本轮不做（bullet 明确 "UI subscriber" 是消费方不是产出方）。
- **Follow-up: per-reason `agent.retry` counter**（P2 bullet `agent-retry-bus-observable`），和本轮的 counter 扩展同一家族，可以参照本轮 Metrics.counterName 的分支扩展模式直接做。
- **不动 `SessionRevert.revertToMessage`**: revert 不改 `currentProjectId` 只改 timeline，不需要 fire binding-changed。
