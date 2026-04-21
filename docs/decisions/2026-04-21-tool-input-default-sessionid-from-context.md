## 2026-04-21 — tool-input-default-sessionid-from-context：session-side mirror of projectId default（VISION §5.4）

Commit: `27a700f`

**Context.** 上一轮 `bulk-apply-tool-input-default-projectid`（decision `2026-04-21-bulk-apply-tool-input-default-projectid.md`）把 10 个 timeline mutation tool 的 `projectId` 改为可选，`ctx.resolveProjectId(input.projectId)` 统一默认到 session 绑定。session 这一侧镜像没做：`compact_session` / `rename_session` / `fork_session` / `archive_session` / `describe_session` 等工具全都 required `sessionId: String`。但 `ToolContext.sessionId` 是 dispatch 时**必然已知**的 non-null 字段（tool 本身由 session 内的 message 触发），LLM 每次都重复 echo sessionId 是纯噪音 tokens，也容易幻觉到其他 session id。Rubric §5.4（dual-user path：让 "对当前 session 的操作"成为一等路径）。

**Decision.** 两步落地：

1. 在 `ToolContext` 上新增 `resolveSessionId(input: String?): SessionId` —— `resolveProjectId` 的镜像，但逻辑更简单（`sessionId` 非空，不存在"无 binding" error 分支）：
   ```kotlin
   fun resolveSessionId(input: String?): SessionId =
       if (input != null) SessionId(input) else sessionId
   ```
   放在 `Tool.kt` 紧挨 `resolveProjectId` 后面，KDoc 说明差异。

2. 对 5（+1）个 session tool 做同一套 5 步转换：
   - `CompactSessionTool`（`compact_session`）
   - `RenameSessionTool`（`rename_session`）
   - `ForkSessionTool`（`fork_session`）
   - `ArchiveSessionTool` + `UnarchiveSessionTool`（`archive_session` / `unarchive_session`，同文件两个 class，一起做保持对称）
   - `DescribeSessionTool`（`describe_session`）

   每个 tool 的改动：
   (a) `Input.sessionId: String` → `Input.sessionId: String? = null`，KDoc 固定文案 "Optional — omit to default to the tool's owning session (`ToolContext.sessionId`). Pass an explicit id only to act on a different session than the one currently dispatching."（UnarchiveSessionTool 的 KDoc 略调整说明"作用于当前 session = no-op"）。
   (b) JSON Schema `properties.sessionId` 加 description。
   (c) JSON Schema `required` array 中删除 `JsonPrimitive("sessionId")`（大多数变 `emptyList()`，`rename_session` 剩 `newTitle` 一个）。
   (d) `execute` 里 `val sid = SessionId(input.sessionId)` → `val sid = ctx.resolveSessionId(input.sessionId)`。
   (e) 错误消息 `${input.sessionId}` → `${sid.value}`（nullable 插值否则会打印 "null"）。

清理各文件中不再使用的 `import io.talevia.core.SessionId`（`RenameSessionTool` / `ForkSessionTool` / `ArchiveSessionTool` / `DescribeSessionTool` 删掉；`CompactSessionTool` 保留因为 `noOp` helper 仍用 `SessionId` 参数类型）。

**Alternatives considered.**

1. **Option A (chosen)**: 新增 `ctx.resolveSessionId` + 5(+1) 个 tool 同步化。优点：对称 projectId 的抽象，一个镜像函数 + 一套 mechanical transformation；LLM 每次 turn 的 session tool spec 总 tokens 下降；"对当前 session 的操作"成为 agent first-class path。缺点：加了一个新方法到 `ToolContext`，但是复用 projectId 时已经验证的模式，风险低。
2. **Option B**: `ToolContext.resolveSessionId` 做 non-null 返回 `SessionId`（当前方案）vs. 延用 `resolveProjectId` 的 when-expression 形态（三分支 error/fallback/explicit）。拒绝后者：session 侧没有"unbound" 状态（dispatch 一定挂在某个 session 上），强加 error 分支是 dead code，反而模糊了两个 helper 的真实语义差异。用 `if/else` 让 "sessionId 总能解出来" 从编译器层面显式。
3. **Option C**: 把 `ToolContext.sessionId: SessionId` 字段名改成 `currentSessionId` 和 `currentProjectId` 对齐。拒绝：这会破坏 `ToolContext` 所有消费者（Tool.kt, AgentTurnExecutor, Tool-Dispatch 测试等，N+ 处）—— 一个命名统一不值这个 blast radius。KDoc 里写清"`sessionId` = owning session = always known"更便宜。
4. **Option D**: 只改 3 个最常用 session tool（compact / rename / describe），archive/unarchive/fork 留到下次。拒绝：backlog 原话"让常用的 3-5 个 session tool..."上限是 5；archive / unarchive 是同文件同 pattern 的对称写法，合到一起做不会增量化复杂度，反而比拆开两轮 commit 更干净。净切到 5+1（unarchive 几乎零 extra effort）。
5. **Option E**: 顺便把其他 session tool（`SwitchProjectTool` / `RevertSessionTool` / `DeleteSessionTool` / `DescribeMessageTool` / `EstimateSessionTokensTool`）也做了。拒绝：backlog 明确了 3-5 个；超出范围会让本轮 diff 失控。留给下次 cycle 如果 bullet 被重新 enumerate。
6. **Option F**: 给 UnarchiveSessionTool 的 omit 路径加一个 warning "operating on the current session is a no-op — pass explicit sessionId". 拒绝：本工具已经有 `wasUnarchived=true` 返回语义表示 no-op，agent 看到返回后自会推断；加额外 warning 是状态机过设计。

**Coverage.**

- `./gradlew :core:jvmTest` 全绿（现有 `CompactSessionToolTest` / `RenameSessionToolTest` / `ForkSessionToolTest` / `ArchiveSessionToolTest` / `DescribeSessionToolTest` 都传 explicit `sessionId`，新的 `= null` default 不影响）。
- `./gradlew :apps:server:test` 全绿（server 端的 session HTTP/SSE 绕过 tool schema 层，直接消费 `Session` model，不受影响）。
- `./gradlew :core:ktlintCheck` 全绿。
- 4 端构建：`iosSimulatorArm64` / `androidAssembleDebug` / `desktopAssemble` / `serverCompileKotlin` 全绿。
- **Coverage debt**: 每个 tool 的 "omit sessionId → 走 ctx.sessionId" 独立 case 没有 per-tool 测试。`resolveSessionId` 本身逻辑简单（两分支无 error），比 `resolveProjectId` 的测试面更小；记为 follow-up，和上一轮 `bulk-apply-tool-input-default-projectid` 相同做法（复用代表性测试 + KDoc + 编译/集成绿）。

**Registration.** 无新 tool；5 端 AppContainer 无需改动。

**§3a 自查.**

1. 工具数量: 净 0 增长。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 `Project`。PASS。
4. 状态字段: `sessionId: String?` 是 optional identifier。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: 这**就是本轮目标** —— 让 session binding 同样成为 LLM 的一等路径。PASS（收益方向）。
7. 序列化向前兼容: `String` → `String? = null` 是非破坏性；旧 JSON 调用（没 sessionId 字段）现在走 ctx fallback，带 sessionId 的调用继续走 explicit。`ToolContext.resolveSessionId` 内部用已有的 `SessionId(String)` 构造。PASS。
8. 五端装配: 无新 tool。PASS。
9. 测试语义覆盖: 现有 5 个 session tool 测试 + server test 全绿。omit 路径 coverage debt 记为 follow-up。部分 PASS。
10. LLM context 成本: 每 tool `required` 少 1 字段、`properties.sessionId` 多 description ~70 tokens × 6 tools ≈ +420 tokens。但每次 turn agent 可以 omit `"sessionId": "sess_xxx"`（~20 tokens）节省 —— 6 个 session tool 交叉调用下净成本平衡偏省。PASS。

**Non-goals / 后续切片.**

- **Follow-up: 其他 session tool 的 sessionId 下沉**。`SwitchProjectTool` / `RevertSessionTool` / `DeleteSessionTool` / `DescribeMessageTool` / `EstimateSessionTokensTool` / `ReadPartTool` 都能同法处理；新开 cycle 或下次 repopulate 把它们明确列进 backlog。
- **Follow-up: per-tool omit test**。加 6 个小 test（每个 ~10 行）验证 "null sessionId → 走 ctx.sessionId" 路径，提升抗回归性。
- **Follow-up: `ToolContext.resolveMessageId` / `resolveCallId`**。`messageId` 和 `callId` 也在 ctx 上可用，但 tool 暴露面几乎没有接收这两个 id 的输入（message / call 是内部机制，agent 不直接引用），目前无 driver。不做。
- **不改 system prompt**: `taleviaSystemPrompt()` 讲 session 的章节已经描述"session 绑 project / switch_project 建立上下文"，session tool 新 schema 里的 optional 字段 description 自带说明，prompt 不用再加一段。
