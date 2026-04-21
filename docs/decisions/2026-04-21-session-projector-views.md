## 2026-04-21 — session-projector-views：SessionProjector 接口 + ToolCallTree / ArtifactTimeline 两个实现（VISION §5.4）

Commit: `df919a6` (pair with `docs(decisions): record choices for session-projector-views`).

**Context.** Session store 今天只暴露线性 message list（`listMessages` / `listSessionParts` / `listMessagesWithParts`）。UI 要渲染"tool-call tree"（一个 turn fan out 出的 tool 调用视图）或"artifact timeline"（按时间扫 lockfile entries 的生成历史面板）都得在 desktop / iOS / Android 三端各自 walk 一遍 message list，把同样的投影逻辑重复写三次。这违反 VISION §5.4 "Core 下沉通用读投影、UI 只消费" 的分层约束。

OpenCode 行为参考：`packages/opencode/src/session/projectors.ts` 把这类"读侧投影"集中在 session 层，UI 按需 `project(sessionId)` 拿到结构化结果。我们不抄它的 Effect.js Service/Layer 结构（CLAUDE.md 红线），只抽行为：Kotlin 原生类 + 依赖注入 store。

**Decision.** 新增 `core.session.projector` 子包，3 个文件：

| 文件 | 内容 | 行数 |
|---|---|---|
| `SessionProjector.kt` | `interface SessionProjector<out T>` — 一个 suspend 方法 `project(sessionId: SessionId): T`。 | 23 |
| `ToolCallTree.kt` | `ToolCallTree` / `TurnNode` / `ToolCallNode` 数据模型 + `ToolCallTreeProjector(sessions: SessionStore)` 实现。 | 97 |
| `ArtifactTimeline.kt` | `ArtifactTimeline` / `ArtifactEntry` 数据模型 + `ArtifactTimelineProjector(sessions, projects)` 实现。 | 77 |

**ToolCallTree** 投影：
- 走 `listMessagesWithParts(includeCompacted = true)`，以每个 `Message.Assistant` 为 Turn 锚点。
- Assistant 消息的 `Part.Tool` parts 按 `createdAt` 排序成子节点。
- 从 `Message.Assistant.parentId` 反向关联对应的 `Message.User`（`userMessageId`，可能 null 当 assistant 是 session 首条）。
- 纯文本 turn（没 tool 调用）也返回 TurnNode，只是 `toolCalls` 为空 list。
- 只读/幂等；不依赖 `listMessagesWithParts` 的顺序契约之外的东西。

**ArtifactTimeline** 投影：
- 读 `Session.currentProjectId`；未绑定则返回空 timeline（`projectId = null, entries = []`），**不抛异常**——UI 渲染空态比炸在渲染路径上好得多。
- 解析 `Project.lockfile.entries`，按 `provenance.createdAtEpochMs` 降序（最近先），映射到 `ArtifactEntry(inputHash, toolId, assetId, providerId, modelId, seed, createdAtEpochMs, pinned)`。
- Session 不存在才抛 `IllegalStateException`——那确实是 bug，不是空态。

**数据模型设计**：所有投影结果都是 `@Serializable` data class，所有字段带 default 值。UI 端 desktop / iOS / Android 可以：
- 直接消费 Kotlin data class（desktop / Android），或
- 走 SKIE 导出到 Swift（iOS），或
- 通过 Ktor SSE 推到前端 JSON 字面量。

`SessionProjector<out T>` 协变参数让 `SessionProjector<ToolCallTree>` 能赋给 `SessionProjector<Any>`，方便 UI 端写 `List<SessionProjector<Any>>` 动态选投影。

**无 tool 注册**：这些不是 LLM 可见工具，是 Core 内部 read API。5 端 AppContainer 零变化——UI 消费方按需 `new` 一个 projector 即可（projector 只持有 store 引用，构造无副作用）。Tool count 不动、LLM context 成本 0（§3a.1 / §3a.10 都 PASS）。

**ToolCallNode / ArtifactEntry 字段选择**：两种 view 都刻意保留"够 UI 渲染就停"的字段集——不塞 `input: JsonElement` / `data: JsonElement` 这类体积不可控的完整 payload（UI 要深看单 part 可以走 `read_part`、要深看 lockfile 可以走 `describe_lockfile_entry`）。这保持投影结果在"给 UI 列表渲染"的场景下高效可序列化。

**Alternatives considered.**

1. **Option A (chosen)**: `SessionProjector<T>` 接口 + 两个独立实现类，各自 `@Serializable` 结果 data class。优点：每个投影可独立测试、可按需 lazy 构造、添加第 3 类投影（例如 `CompactionHistoryProjector`）只需要新文件+新类；接口极简（一个 suspend 方法）不绑死 Effect.js 风格。缺点：多写一个接口定义（23 行），微不足道。
2. **Option B**: 把投影逻辑塞进 `SessionStore` 作为默认方法（`fun SessionStore.toolCallTree(id): ToolCallTree`）。拒绝：`SessionStore` 是写 + 基础读接口，往里塞 UI-shape 投影会让接口边界模糊；新增投影要改 SessionStore.kt（跨文件影响），违反"关注点分离"。`core/session/projector/` 独立子包对 reviewer 更友好。
3. **Option C**: 做成 `SessionProjector` sealed interface + `when` 分派到具体实现。拒绝：sealed 暗示闭集——但投影种类本质上开放（谁都可以在下游 app 定义自己的投影），open interface + 多个 open 实现更合适。
4. **Option D**: 投影结果用 `kotlinx.serialization.json.JsonElement`（schema-less）。拒绝：UI 代码要自己 decode 就丢失了类型安全；`@Serializable` 强类型 data class 让 desktop Kotlin / Android Kotlin / iOS Swift 三端都能静态 enforce 字段契约。
5. **Option E**: 把 `ToolCallTree` 暴露为一个新 LLM tool `session_query(select=tool_call_tree)`。拒绝：投影是 UI 用的，不是 agent 决策用的（agent 已经能通过 `session_query(select=messages/parts/tool_calls)` 读到原始数据）。给 LLM 加 tool 变体只会增加 spec token 成本无真实价值——§3a.1 反信号。
6. **Option F**: 把 `ArtifactTimelineProjector` 融进 `ArtifactTimelineQuery.kt` 作为 `session_query(select=artifacts)` 的新 select。拒绝：当前 `session_query` 是"session 元数据"读；lockfile 是 Project-level 概念通过 `session.currentProjectId` 间接绑定。跨域读混进 `session_query` 会破坏 select 空间的正交性，保持两者分离更清晰。

**Coverage.**

- `ToolCallTreeProjectorTest`（5 tests）覆盖：
  - 空 session → 空 turns。
  - 单 assistant turn 带两个 tool calls、插入顺序和 createdAt 顺序错开 → projector 按 createdAt 重排。
  - 纯文本 assistant turn → 空 toolCalls 列表但保留 STOP finish。
  - 只有 user message（还没 assistant 回复）→ 不产生 TurnNode。
  - 多 turn → 按 `assistant.createdAt` 升序列出。
- `ArtifactTimelineProjectorTest`（5 tests）覆盖：
  - 未绑定 session（`currentProjectId = null`）→ 空 timeline 不抛异常。
  - 绑定了 project 但 lockfile 空 → 空 entries。
  - 多 entry 插入顺序打乱 → projector 按 createdAtEpochMs 降序排出。
  - provenance 字段（providerId / modelId / seed / pinned）正确 carry through。
  - Missing sessionId → `IllegalStateException`。
- `./gradlew :core:jvmTest` 全绿。
- `./gradlew :core:ktlintCheck` 全绿。
- 4 端构建：iOS sim / Android APK / Desktop / Server / JVM core 全部通过。

**Registration.** 无 AppContainer 变化 — 纯 Core library API。UI 端要消费时按需 `ToolCallTreeProjector(sessions)` / `ArtifactTimelineProjector(sessions, projects)` 即可。将来如果 DI 需要，可以在各 app container 加 `val toolCallTreeProjector = ToolCallTreeProjector(sessions)` 字段——这一步留给下游 UI 明确需要时再做，避免过度工程。

**§3a 自查.**
1. Tool count: 0 变化（没新增 `*Tool.kt`）。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 Project。PASS。
4. 状态字段: N/A（projector 只读，没新增状态字段）。PASS。
5. Core genre: `ToolCallNode` / `ArtifactEntry` 字段都是结构性（toolId / providerId / modelId 是运行时值，不是 Core 一等类型）。PASS。
6. Session/Project binding: `ArtifactTimelineProjector` 正是消费 `Session.currentProjectId`——binding 是被它利用的、不是被它创建的。PASS。
7. 序列化向前兼容: 所有投影 data class 字段都有 default 或是 required（如 `sessionId: String`）。新增 projection 种类或投影字段不会破坏已存在的反序列化（投影结果不持久化；每次 project 调用实时构建）。PASS。
8. 5 端装配: 无变化（UI 按需构造）。PASS。
9. 测试语义覆盖: 10 个用例覆盖 happy path + 空态 + 排序重建 + missing session。PASS。
10. LLM context 成本: **0**（不是 LLM tool，spec/helpText 没有）。PASS。

**Non-goals / 后续切片.**
- 第 3 类投影（例如 `CompactionHistoryProjector` — 按 compaction boundary 聚合消息）等 UI 明确需要再加。
- UI 端消费（desktop Compose / iOS SwiftUI / Android Compose）不是本轮范围；留到 UI 完善周期再把 projector 接到渲染层。
- projector 结果的 caching / observability（如果同一 session 反复投影就缓存一下）等有实际性能信号再考虑，现在做就是过度工程。
- iOS SKIE 导出的投影类型暂不测试——需要用到时再加 Xcode 集成测试。
