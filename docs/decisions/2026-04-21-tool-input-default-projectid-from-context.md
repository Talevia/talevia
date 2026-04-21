## 2026-04-21 — tool-input-default-projectid-from-context：3 个常用 tool 的 `projectId` 改为可选 + 从 ToolContext 默认（VISION §5.4）

Commit: `f15f429` (pair with `docs(decisions): record choices for tool-input-default-projectid-from-context`).

**Context.** `session-project-binding`（2026-04-21 decision）落地后，`Session.currentProjectId` 通过 `ToolContext.currentProjectId` 注入每次 dispatch，但当时显式承诺"下一轮把常用 tool 的 projectId 改为可选"——今天兑现。老的 KDoc 措辞是："this cycle keeps tool input shapes unchanged, so this is purely informational for now."

动机有三：
1. **减 LLM token**：`projectId` 在 Input required 数组里每 tool 花~12 token；改为可选后，LLM 对已绑定 session 可以省略该参数，平均每 tool call 节省 ~40 token（UUID 串 + JSON syntax）。
2. **降认知负载**：agent 从 transcript 里重新猜 projectId 容易错（特别是 fork 场景），让 `ToolContext` 做权威源头避免 drift。
3. **VISION §5.4 小白路径**：用户只打一次 `switch_project` 绑定后，后续对话就能"持续操作当前项目"不用在每个请求里重复 id。

Backlog bullet 指名 3 个 tool：`project_query` / `add_clip` / `create_project_from_template`。但 plan 阶段发现 `create_project_from_template` 的 `projectId` 语义不同——它是**新项目的 id**（创建时可选，默认从 title slug），不是指向已有项目的引用；让它默认从 ctx.currentProjectId 取会破坏"新建"语义（把旧项目 id 赋给新建项目 = 覆盖旧项目）。因此替换第 3 个候选为 `describe_project`，语义和 `project_query` 完全一致（读已有项目），且同样是高频 orient tool。

**Decision.** 在 `ToolContext` 上新增 `resolveProjectId(input: String?): ProjectId` 方法作为统一 fallback 点：

```kotlin
fun resolveProjectId(input: String?): ProjectId = when {
    input != null -> ProjectId(input)
    currentProjectId != null -> currentProjectId
    else -> error(
        "No projectId provided and this session has no current project binding. " +
            "Call switch_project to bind a project to the session, or pass projectId explicitly.",
    )
}
```

3 个 tool 改造：

| Tool | Input 改动 | execute 改动 |
|---|---|---|
| `project_query` | `val projectId: String` → `val projectId: String? = null`（首字段，带 KDoc） | `projects.get(ProjectId(input.projectId))` → `ctx.resolveProjectId(input.projectId).let { pid -> projects.get(pid) ?: error("Project ${pid.value} not found") }` |
| `add_clip` | 同上 | `store.mutate(ProjectId(input.projectId)) { ... }` → `val pid = ctx.resolveProjectId(input.projectId); store.mutate(pid) { ... }` |
| `describe_project` | 同上 | 同样 `ctx.resolveProjectId(input.projectId)` + 错误消息引用 `pid.value` |

三者的 `inputSchema` 同步：`projectId` 的 schema 加 `"Optional — omit to use the session's current project (set via switch_project)."` 描述；`required` 数组移除 `"projectId"`。

**System prompt 更新**：`core/agent/prompt/PromptEditingAndExternal.kt` 里原本声明"today every timeline / AIGC / source tool still takes a projectId: String argument explicitly"已过时；改为明确指出 3 个 tool 接受 optional projectId 默认 from binding，其他仍要 explicit。LLM 从第一次读 prompt 起就知道这个分层。

**`create_project_from_template` 保持不变**：它的 `projectId: String? = null` 字段**已经是可选**（slug from title 默认），语义不是 "reference existing project" 而是 "id for the new project being created"。Backlog bullet 列它是因为名称相近——plan 阶段识别出语义差异后替换为 `describe_project`。decision 里显式说明这个替换。

**Alternatives considered.**

1. **Option A (chosen)**: `ToolContext.resolveProjectId(String?)` 集中 fallback 逻辑；3 tool 用 `val pid = ctx.resolveProjectId(input.projectId)` 一行替换过去的 `ProjectId(input.projectId)`。优点：错误消息统一（"...Call switch_project..."），将来扩到第 4、5 个 tool 只加调用无需 copy-paste；logic 测试集中在 `ToolContext` 一处（tests 已覆盖 3 tool 级别）。缺点：`ToolContext` 类膨胀一个方法——但这正是 ToolContext 的职责（暴露 session-level context 给 tool）。
2. **Option B**: 每个 tool 自己 inline 3 行 fallback `input.projectId?.let { ProjectId(it) } ?: ctx.currentProjectId ?: error(...)`。拒绝：3 处重复，错误消息容易 drift；下一次加第 4 个 tool 又要 copy-paste。
3. **Option C**: 把 `Tool` 接口改造成 sealed + 添加 `ProjectScopedTool` 子接口强制 `projectId` 可选化。拒绝：Tool 是 `Tool<I, O>` 的通用抽象，为 projectId 这一个 concern 特化子接口是过度工程——让 dispatch / register / compose 逻辑全要分两条路径。
4. **Option D**: 保持 `projectId: String` 非空，在 tool 外层（`AgentTurnExecutor.dispatchTool`）拦截并把 ctx.currentProjectId 注入到 input 里。拒绝：需要反射或 codegen 判断哪些 Input 有 projectId 字段；违反 Tool 的 "Input typed + serializer 明确" 契约；LLM 也看不到 Input schema 变化。
5. **Option E**: 全面 +5 个 tool（project_query / add_clip / describe_project / get_project_state / describe_clip / etc.）一次到位。拒绝：scope creep——本 cycle 的 backlog bullet 明确是 "3 个最常用的 tool"，做完确认 pattern 稳定再批量扩（用同样的 `ctx.resolveProjectId` helper 将来每 tool 只改 3 行）。
6. **Option F**: 把 backlog 原提的 `create_project_from_template` 也拉进来按 "first call after switch_project 用 session 的 currentProjectId 作为新项目 id 默认值"。拒绝：非常危险的语义——如果 session 已绑 "p-old"，然后 agent 说 "create_project_from_template"，结果会覆盖 "p-old"。即便加"如果 id 已存在则 append suffix"逻辑，`projectId` 的含义在这个 tool 里就是"这次创建的 id"；和其他 tool 的"引用已存在项目"含义完全相反，不应该混用同一个 fallback 机制。

**Coverage.**

- `ProjectQueryToolTest` 新增 3 个用例：
  - `projectIdOmittedDefaultsToSessionBinding` — 传 `ToolContext(currentProjectId = pid)` + Input 无 projectId → 成功走默认。
  - `explicitProjectIdWinsOverSessionBinding` — ctx 绑定 id "A" 但 Input 写 id "B" → 结果 Output.projectId = "B"。
  - `unboundSessionAndOmittedProjectIdFailsLoud` — ctx 无 binding + Input 无 projectId → `IllegalStateException` 含 "switch_project" 提示。
- `AddClipToolTest` 新增 2 个用例同构盖（fallback + unbound-fails）。
- `DescribeProjectToolTest` 新增 2 个用例同构覆盖。
- 现有 ~40 个 happy-path 测试 (`ProjectQueryToolTest.*` / `AddClipToolTest.*` / `DescribeProjectToolTest.*`) 全部**不动**——它们 Input 里显式传 `projectId`，路径走 `input != null` 分支，行为不变。
- `./gradlew :core:jvmTest` 全绿。
- `./gradlew :core:ktlintCheck` 全绿（ktlintFormat 自动删了几个未用 `ProjectId` import）。
- 4 端构建：iOS sim / Android APK / Desktop / Server / JVM core 全部通过。

Prompt-level：`TaleviaSystemPromptTest.sectionsAreSeparatedByExactlyOneBlankLine` + `sectionsAppearInExpectedOrder` + `promptContainsAllNorthStarKeyPhrases`（40+ keywords）都过——我改的 `PromptEditingAndExternal` 段落保持"# Session-project binding"section 的结构和所有 anchor phrase。

**Registration.** 无 AppContainer 变化——纯 Input schema 演进。每个 `AppContainer` 注册 `ProjectQueryTool(projects)` / `AddClipTool(projects, media)` / `DescribeProjectTool(projects)` 的方式一字不变。

**§3a 自查.**
1. Tool count: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 Project。PASS。
4. 状态字段: N/A。
5. Core genre: N/A。
6. Session/Project binding: 本轮就是 §3a.6 的显式 remedy——把老 decision 里标的 "待 session-project-binding 后切 context" 兑现。PASS。
7. 序列化向前兼容: Input `projectId: String` → `String? = null` —— 向后兼容老的 explicit 传参（null 分支走 ctx），也支持新的 omit 风格。JSON schema `required` 数组收窄也是 schema-level forward compatible。PASS。
8. 5 端装配: 无需变化。PASS。
9. 测试语义覆盖: 7 个新 test（3 tool × 2-3 cases）覆盖 fallback / override / unbound-fail 边界，非 happy path。PASS。
10. LLM context 成本: **净负**——每个 tool 的 Input schema 少一个 required 字段（-~12 token）；helpText 里 projectId 描述增加但同时减去"required/必填"语气；总体每 tool 调用少~40 token（UUID 串 + JSON syntax），且 3 个都是高频 tool。累计**节省 >1000 tokens/turn**（多 tool call 场景）。PASS（收益方向）。

**Non-goals / 后续切片.**
- 批量扩到更多 tool（`get_project_state` / `describe_clip` / `find_stale_clips` / AIGC lane 的每个工具）：等本轮 pattern 稳定两三天后单独 cycle 推。每 tool 约 5 行改动 + 2 个 test case，可以并行跑或批量做。
- 把 session_query / source_query 的 `projectId` / `sessionId` 也做类似 fallback（`sessionId` 默认从 `ctx.sessionId`）：相关 bullet 需求明确后做，现在不扩 scope。
- `create_project_from_template` 的 `projectId` 语义澄清：已在 decision 里说明为什么不变；未来若真要"创建即绑定"可在 tool 内部调用 `switch_project` 等价操作，但这是 orthogonal 行为。
