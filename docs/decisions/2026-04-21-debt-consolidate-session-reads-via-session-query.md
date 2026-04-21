## 2026-04-21 — debt-consolidate-session-reads-via-session-query：6 个 session 读工具合并成 session_query 原语（Rubric 外 / §5.2 一等抽象 > patch）

Commit: `d0389cc` (pair with `docs(decisions): record choices for debt-consolidate-session-reads-via-session-query`).

**Context.** 前置几个 loop 在 session 域陆续落了 6 个 "每个维度一个工具" 的 read tool：`list_sessions`、`list_messages`、`list_parts`、`list_session_forks`、`list_session_ancestors`、`list_tool_calls`。同档背景债和 project 域当时的 `list_timeline_clips` / `list_tracks` / `list_assets` 三件套完全一样——LLM tool spec 分裂，每多一个维度就多一份 helpText + inputSchema + token 成本。当时的修复是 `unify-project-query`（2026-04-21 decision），合并成一个带 `select` 的 `project_query` 原语。现在把同一个治方用到 session 域。

P1 backlog bullet 明确点名：`session_query(select ∈ {sessions, messages, parts, forks, ancestors, tool_calls}, filter, sort, limit)`，按 select 吸收至少 6 个旧工具。`describe_session` / `describe_message` 不动——它们是单实体深看，和 list-projection 语义正交（project 域同理保留了 `describe_clip` / `describe_project` / `describe_lockfile_entry`）。

**Decision.** 新增 `SessionQueryTool`（`session_query`）作为 dispatcher，六个 select 各自落到 `session/query/` 子目录下的独立 `runXxxQuery(...)` 文件。参照 `ProjectQueryTool` 经过 `debt-split-projectquerytool` 演化后的最终形态（dispatcher + `query/*.kt` 子文件 + `query/QueryHelpers.kt`）。

工具数量变化：**-6 + 1 = 净 -5**。helpText / inputSchema 合并，LLM 每 turn tool spec token 估算从 ~2000 降到 ~650，**净省 ~1350 tokens/turn**（6 个旧 tool 各 ~330 avg vs 1 个合并后 ~650）。

文件动作：
- 新增 `SessionQueryTool.kt`（~310 行，dispatcher + `Input` / `Output` + 6 个 row data classes + `rejectIncompatibleFilters`）。
- 新增 6 个 per-select 文件 `session/query/{Sessions,Messages,Parts,Forks,Ancestors,ToolCalls}Query.kt` + `QueryHelpers.kt`（`kindDiscriminator` / `preview` / `VALID_ROLES` / `VALID_PART_KINDS` / `requireSession` / `encodeRows`）。每个 ~50–90 行，低于 R.5.3 500 阈值。
- 删除 6 个旧工具：`ListSessionsTool.kt`、`ListMessagesTool.kt`、`ListPartsTool.kt`、`ListSessionForksTool.kt`、`ListSessionAncestorsTool.kt`、`ListToolCallsTool.kt`（合计 1019 行）。
- 删除 6 个旧测试：`ListSessionsToolTest.kt`、`ListMessagesToolTest.kt`、`ListPartsToolTest.kt`、`ListSessionForksToolTest.kt`、`ListSessionAncestorsToolTest.kt`、`ListToolCallsToolTest.kt`（合计 1186 行）。
- 新增 `SessionQueryToolTest.kt`（~380 行）覆盖 6 个 select × 关键路径（happy path / filter / error / 分页 / 跨 select 互斥）。共 15 个 test 用例，取代旧 6 个 test file 的覆盖面。
- 5 端 AppContainer 同步更新：每个删 6 行旧 import + 6 行旧 `register(...)`、加 1 行新 import + 1 行新 `register(SessionQueryTool(sessions))`。
- doc / helpText 交叉引用更新：`DeleteSessionTool` / `SwitchProjectTool` / `ArchiveSessionTool` / `DescribeSessionTool` / `DescribeMessageTool` / `RenameSessionTool` / `ForkSessionTool` / `CompactSessionTool` / `EstimateSessionTokensTool` / `DefaultRules.kt` 里 `list_sessions` / `list_messages` 引用全部改成 `session_query(select=sessions)` / `session_query(select=messages)`。
- 既有 session-lane 测试的"错误消息包含 list_sessions / list_messages"断言同步改为 `session_query(...)`。

**Input 设计**：单一 `Input` 承载所有 select 的 filter，`rejectIncompatibleFilters` 在分派前验证。每个 filter 只属于一个（或两个——`includeCompacted` 同时用于 parts + tool_calls）select，误用立刻 `error()`，避免 "静默空列表 vs 真实空列表" 歧义。`sessionId` 是除 `select=sessions` 外其他 5 个 select 的必填；`select=sessions` 显式拒绝 `sessionId`（应该用 `projectId`）。

**Output 设计**：uniform `{select, total, returned, rows: JsonArray}`。`rows` 是 `JsonArray`，形状依 select 而定。Row data classes 保留在 `SessionQueryTool` 里作为 nested public `@Serializable`（因为测试 / UI 消费者通过 `SessionQueryTool.SessionRow.serializer()` 解码）。这和 `ProjectQueryTool.TrackRow.serializer()` 的公开 API 约定一致。

**Byte-identical 行为**：6 个旧工具的 happy-path outputForLlm 格式基本保留（"N session(s) on ...", "M of X message(s) ..."）；错误消息重定向到新的 tool id。新 tool 的 `Output` 字段从 `{totalSessions, returnedSessions, sessions}` 等各异形态统一成 `{total, returned, rows}` —— 这是**公开 API 变化**（向后不兼容）。合理性：旧工具 Output 形状不持久化（tool call results 存的是 `outputForLlm` + `data` JsonElement blob，不反序列化成强类型 Class），所以没有 on-disk 破坏；只有调用 `.data` 的直接消费者（测试 + 极少量 UI 代码）需要适应。作为 tool consolidation 的一次性破坏已可接受；决定同样比照 `ProjectQueryTool` 当时的做法。

**Alternatives considered.**

1. **Option A (chosen)**: 合并成 `session_query(select, ...)` 原语，删 6 个旧 tool，新增 1 个 tool。优点：净 -5 工具、LLM spec token 净省 ~1350/turn、和 `project_query` 对称易教易学、后续 session 域新 read 维度（如 `select=compaction-history`）零新 tool 代价。缺点：一次性破坏性变化（Output shape 变），5 端装配 + 10+ doc 引用同步；测试从 6 个 file 变 1 个，如果出问题也可能在单 file 里同时红多个 select——但测试每个 select 各有独立用例，定位不难。
2. **Option B**: 保留 6 个工具，但重构成共享的内部 `SessionReadQuery` 实现层，tool 仅作薄外壳。拒绝：tool count 不减 = LLM token 账单不动；净增复杂度；§3a.1 劣化信号原样保留。这是"码侧整洁但不解决债"的 antipattern（和当初 `debt-merge-pin-unpin-tool-pairs` 的 Option B 同构）。
3. **Option C**: 只合并 3–4 个 selects（例如 sessions / messages / parts / tool_calls），把 forks / ancestors 留作独立工具。拒绝：backlog 原文 "至少 6 个"，而且 forks / ancestors 已经属于 session-id 语义族，从用户视角 `session_query(select=forks, sessionId=X)` 比 `list_session_forks(sessionId=X)` 更一致。降低"LLM 记住 6 个 tool" 的认知成本比"多一个 select 比 tool 好名"的边际损失更值得。
4. **Option D**: 做成一个"通用 SQL-like" `session_query(sql: String)`，接受 LIKE 语法。拒绝：把 SQL 注入到 LLM 输出里是重大攻击面；ProjectQueryTool 当时也显式拒绝过 SQL 方向；discriminator + 封闭 filter 是更安全且可枚举的契约。
5. **Option E**: 保持独立 6 个 tool，但在 helpText 里标记 deprecated 并在下一轮删除。拒绝：Deprecated 永不清理是 R.5.7 信号；OpenCode 行为参考也没有 soft-deprecation 概念；更糟糕的是会让 LLM 看到 12 个 tool（旧 + 新）过渡期，token 成本先涨后降。直接切换更干净。

**Coverage.**

- 新增 `SessionQueryToolTest`（15 用例）：6 个 select × 典型 path + `invalidSelectFailsLoud` + `misappliedFilterFailsLoud` + `limitAndOffsetPage`。覆盖：
  - sessions: projectId 过滤、排除/含 archived、拒绝 sessionId 参数。
  - messages: role 过滤（user only）、非法 role 报错、missing session 报错、missing sessionId 报错。
  - parts: kind 过滤（text only）、非法 kind 报错。
  - forks: 父→子列举、childless session 返回空。
  - ancestors: 多层链 walk（leaf→mid→root）、root 自身返回空。
  - tool_calls: toolId 过滤（generate_image vs synthesize_speech）。
  - 跨 select: invalid select 报错、filter 误用（select=tool_calls 传 role）报错、limit+offset 分页不重叠。
- `./gradlew :core:jvmTest` 全绿（1125+ 用例，35s）。
- `./gradlew :core:ktlintCheck` 全绿（`ktlintFormat` 清了 import 顺序）。
- `./gradlew :apps:server:test` 全绿。
- 5 端构建：iOS sim / Android APK / Desktop / Server / JVM core 全部通过。

既有 session-lane 工具测试（`DescribeSessionToolTest` / `ForkSessionToolTest` / `SwitchProjectToolTest` / `DeleteSessionToolTest` / `RenameSessionToolTest` / `EstimateSessionTokensToolTest` / `DescribeMessageToolTest`）中断言错误消息含 `list_sessions` / `list_messages` 的 7 处全部同步改成 `session_query(select=sessions)` / `session_query(select=messages)` —— 如果我漏改了任何 err path 这些测试会红。

**Registration.** 5 个 `AppContainer`（CLI / Desktop / Server / Android / iOS）各自：
- 删除 6 行 import（`ListSessionsTool` / `ListMessagesTool` / `ListPartsTool` / `ListSessionForksTool` / `ListSessionAncestorsTool` / `ListToolCallsTool`）。
- 新增 1 行 import（`SessionQueryTool`）。
- 删除 6 行 `register(List*Tool(sessions))`。
- 新增 1 行 `register(SessionQueryTool(sessions))`。

iOS 自动走 SKIE，无额外 Swift 改动除了 `AppContainer.swift`。

**§3a 自查.**
1. Tool count: **-5**（-6 + 1）。PASS。最大的收益。
2. Define/Update: N/A（read tools）。PASS。
3. Project blob: 不动 Project。PASS。
4. 状态字段: N/A。PASS。
5. Core genre: 无 genre 名词；select / filter 名都是结构性。PASS。
6. Session/Project binding: `sessionId` 显式参数，将来 `tool-input-default-projectid-from-context` 的后续延伸（如 `default-sessionid-from-context`）可进一步省略——但这轮不动。PASS。
7. 序列化向前兼容: 新 `@Serializable` Input 所有字段有 default（`select` 是 required）；Output 不变 / 不持久化；删掉的 6 旧工具的 Input/Output 格式消失，但它们不存盘。PASS。
8. 5 端装配: 5 个 AppContainer 全部同步。PASS。
9. 测试语义覆盖: 15 个用例覆盖正向 + 反向 + 分页 + 跨 select 边界。PASS。
10. LLM context 成本: **净 -1350 tokens/turn**。PASS（收益方向）。

**Non-goals / 后续切片.**
- 这轮保留 `describe_session` / `describe_message` 作为单实体深看工具。后续可以考虑引入 `session_query(select=describe, sessionId=X)` 来统一，但语义分歧（list projection vs single-entity deep view）不强烈，保留分离目前是可读性更好的选择。
- `session_query` 目前只读。写操作仍走 `fork_session` / `archive_session` / `delete_session` / `rename_session` / `revert_session` / `compact_session` 等专用 tool。和 `project_query` 的"读原语"定位一致。
- `debt-consolidate-source-reads-via-source-query`（P1 backlog 下一条同构任务）之后可以照样画瓢。
