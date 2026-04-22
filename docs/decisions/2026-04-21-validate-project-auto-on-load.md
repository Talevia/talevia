## 2026-04-21 — validate-project-auto-on-load：`SqlDelightProjectStore.get` 载入时自动跑 DAG 校验（VISION §5.1 / §5.3）

Commit: `70dbf1d`

**Context.** `ValidateProjectTool` 做结构性 lint（dangling asset / source binding / clip duration / audio envelope / timeline duration mismatch / source DAG integrity），但只靠 agent 显式调用。长项目可能在一次 `remove_source_node` / 手动 blob edit / 旧版本迁移后留下 DAG 损坏（dangling parent ref、parent cycle），无人察觉直到下一次 export 或 `find_stale_clips` 才报错。Rubric §5.1 / §5.3。

Backlog bullet 原文："`SqlDelightProjectStore.get` 在返回前跑一次 light-weight validation（只检 DAG cycle / missing parent ref，不做全量校验），失败记 warning log + 发 `BusEvent.ProjectValidationWarning`，但不 throw（避免锁死存量项目）。"

Fifth cycle-in-a-row skipping `project-query-sort-by-updatedAt` — same §3a reason as prior decisions (14+ mutation tools' stamp burden, `Project.updatedAt` fallback 对 intra-project entity 排序无信号). 等具体 UI driver 再动。

**Decision.** 三部件落地：

1. **新 helper `ProjectSourceDagValidator.validate(source): List<String>`**（`core/domain/ProjectSourceDagValidator.kt`，~100 行）：
   - 从 `ValidateProjectTool.sourceDagIssues` 提取的最小子集 —— **只**查 dangling parent refs + parent cycles（非 clip / asset / audio 这类涉及 timeline 的 full check）。
   - 算法和 ValidateProjectTool 保持一致：tier-1 per-edge dangling check，tier-2 iterative DFS with white/grey/black 三态判环。
   - `internal object` 可见性 —— 被 ProjectStore + ValidateProjectTool（follow-up 可切换）共用，避免 DFS 逻辑漂移。
   - 返回 human-readable messages —— 每条含 node id 指向，和 `ValidateProjectTool.Issue.message` 格式对齐，下游 UI 可以同构渲染。

2. **新 `BusEvent.ProjectValidationWarning(projectId, issues: List<String>)`**（`core/bus/BusEvent.kt`）：
   - **不是** `SessionEvent` —— validation 发生在 project 载入时，和 session 无关。是直接 `sealed interface BusEvent` 的一等成员。
   - KDoc 说明：非 throwing signal。project 仍然原样返回。
   - `Metrics.counterName` exhaustive when 加 `"project.validation.warning"` 分支。
   - `ServerModule.BusEventDto` `sessionId` 改为 nullable（之前全员必填，现在 project-scoped event 需要 null），新增 `validationIssues: List<String>?` 字段 + `"project.validation.warning"` 分支 + `eventName` enum。

3. **`SqlDelightProjectStore` 构造器扩 `bus: EventBus? = null` 参数 + `get()` 调用 `maybeEmitValidationWarning`**：
   - nullable default 让既有 test rigs（直接 `SqlDelightProjectStore(db)` 调用）保持工作。5 端 AppContainer（desktop / android / cli / server / ios）全部切成 `SqlDelightProjectStore(db, bus = bus)`。
   - `maybeEmitValidationWarning`：调 `ProjectSourceDagValidator.validate` 过 `project.source`；空结果 → return；非空 → `logger.warn` (structured logging, includes projectId / issueCount / firstIssue) + `bus?.publish(BusEvent.ProjectValidationWarning(...))`。
   - **永远不 throw** —— bullet 明确要求避免"锁死存量项目"。这个契约测试 `projectWithDanglingParentPublishesWarningButStillReturns` 显式守护。

**Alternatives considered.**

1. **Option A (chosen)**: 提 `ProjectSourceDagValidator` 独立 helper，只做 DAG 两类检查；其他 validation（clip / asset / audio / timeline duration）继续留给 `ValidateProjectTool` 按需显式调用。优点：load-path cost 保持 O(nodes + edges) 而非 O(clips + assets + bindings)；长项目每次 load 不付 full-validate 账单；bullet 明确"不做全量校验"；helper 可复用（ValidateProjectTool 的 sourceDagIssues 未来可以切换用它代替，减少重复代码）。缺点：DAG check 通过但 clip-level corruption 存在的情况 UI 不会立刻警告——用户还是要调 `validate_project`。这是合理取舍：DAG corruption 会导致**静默错误**（stale 穿透 / 拓扑序错乱），clip-level corruption 会在 export 时明确报错。
2. **Option B**: load 时跑 full `ValidateProjectTool.execute` 的所有检查。拒绝：① 每个 project load 都做 timeline / asset / audio / DAG 全量 walk —— 在 AIGC pipeline 频繁 get 的场景（agent 每个 tool dispatch 都可能 get）非线性累积 CPU；② bullet 明确 "light-weight validation（只检 DAG cycle / missing parent ref）"；③ full 检查的 issue code 有 warn 级（duration-mismatch），load-path 噪音大。
3. **Option C**: load 失败时 throw。拒绝：bullet 明确 "不 throw（避免锁死存量项目）"；用户没法 `validate_project` 修 (tool 需要 get 到 project 才能跑)，变死锁。
4. **Option D**: 把 `maybeEmitValidationWarning` 也调用在 `upsert` 路径（写的时候也验证）。拒绝：upsert 路径由 `mutate` 组织，每次 tool 写一次 validate 就是 O(tools × nodes)，累积成本高；且 upsert 是 mutation 源头，tool 负责自己的一致性（既有契约），store 不该干预。load path 检查是 "兜底网"，不是 "每次写都验"。
5. **Option E**: `BusEvent.ProjectValidationWarning` 做成 `SessionEvent` 子类（挂 sessionId）。拒绝：validation 和 session 无关（load 可能由任何 path 触发 —— tool、agent、test rig、server SSE endpoint），塞 sessionId 要求 store 拿不到的上下文。top-level `BusEvent` 最诚实。
6. **Option F**: 用 `kotlinx.serialization` 的 decode 失败当 signal，在 `assembleProject` 里 catch。拒绝：JSON schema 错误（`SerializationException`）和 DAG 结构错误（parent 丢失 / cycle）是不同层次 —— JSON 层出问题是 "blob corrupt"，要 fail fast；DAG 层出问题是 "data present but not well-formed"，要 warn。当前代码就是这么分层的：`assembleProject` 让 JSON 异常抛出（catches nothing），`maybeEmitValidationWarning` 只处理结构性问题。

**Coverage.**

- **新增 `ProjectSourceDagValidatorTest.kt` 6 tests**：
  - `cleanDagReturnsEmpty` — 线性 a→b→c 无 issue。
  - `emptySourceReturnsEmpty` — 0 nodes。
  - `danglingParentReported` — child 引用 ghost parent。
  - `cycleReported` — a→b→c→a 三节点 cycle。
  - `multipleIssuesCombined` — dangling + cycle 混合。
  - `cycleOnlyReportedOncePerDistinctLoop` — self-cycle a→a 去重报告。
- **新增 `ProjectStoreAutoValidationTest.kt` 4 tests**：
  - `cleanProjectDoesNotPublish` — 干净 project load 0 warning。
  - `projectWithDanglingParentPublishesWarningButStillReturns` — 合约：`get()` 返回 project + 发一次 warning。§3a.9 反直觉 case：load 不抛异常也不 silent 吞。
  - `nullBusRigStillReadsCleanly` — 不带 bus 的 test rig 得 project。
  - `getOfMissingProjectReturnsNullWithNoWarning` — 未知 pid 返回 null，不触发 warning。
- 既有 ValidateProjectToolTest 不改（`sourceDagIssues` 继续独立实现，duplication 留给 follow-up 合并）。
- `./gradlew :core:jvmTest` + `:apps:server:test` + `:core:ktlintCheck` 全绿。
- 4 端构建：iOS sim / Android debug APK / Desktop / Server 全绿。

**Registration.** 5 端 AppContainer 全部切 `SqlDelightProjectStore(db, bus = bus)`。iOS 版是 Swift 语法。`Metrics.counterName` + `ServerModule.eventName` / `BusEventDto.from` 都加了 exhaustive branch。

**§3a 自查.**

1. 工具数量: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 Project. PASS。
4. 状态字段: 无新 flag. PASS。
5. Core genre: 无. PASS。
6. Session/Project binding: ProjectValidationWarning 是 project-scoped BusEvent，不再假设 session 上下文。PASS。
7. 序列化向前兼容: `BusEventDto.sessionId` 从 required String 改为 nullable — JSON 消费方（已有客户端）对 missing field 按 null 处理；`additionalProperties` 也已为 `false`。**潜在 breaking**：如果有 client 反序列化时要求 `sessionId` non-null 会失败；但 SSE 客户端通常拿到 String? 处理未知 type 直接跳过 —— 已检查，无已知消费方显式要求 non-null。`validationIssues` 新字段有 `= null` default。PASS。
8. 五端装配: 全部 5 端更新 ProjectStore 构造. PASS。
9. 测试语义覆盖: 10 tests 覆盖 validator 算法细节 (6) + store auto-validation 合约 (4) 含 §3a.9 边界 (load-with-issue 返回 + 发 warning)。PASS。
10. LLM context 成本: 0（`ProjectValidationWarning` 是 bus event，LLM 不直接看；agent 可以通过 UI metrics 间接看到）。PASS。

**Non-goals / 后续切片.**

- **Follow-up: 把 `ValidateProjectTool.sourceDagIssues` 切换复用 `ProjectSourceDagValidator.validate`** —— 当前两份实现功能等价但代码 duplicated。重构独立 cycle 做（本 cycle 保持既有 ValidateProjectTool 完全不动，避免同 PR scope 膨胀）。
- **Follow-up: 扩展 auto-validation 的检查类**（e.g. also check 音频 envelope）—— 目前仅 DAG；如果 load-path 发现其他 silent corruption 类也值得兜底，独立 cycle。
- **Follow-up: UI consumer `BusEvent.ProjectValidationWarning`** —— Desktop / iOS toast or status-bar banner。UI 层工作。
- **Fifth skip of `project-query-sort-by-updatedAt`** —— 仍然 §3a 反对；等 driver。
