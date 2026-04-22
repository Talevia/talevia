## 2026-04-21 — session-query-include-compaction-summary：`session_query(select=compactions)` 聚合视图（VISION §5.2）

Commit: `913186b`

**Context.** Session 的压缩记录（`Part.Compaction`）今天只有两条读路径：
1. `session_query(select=parts, kind=compaction)` —— 返回通用 `PartRow(partId, kind, messageId, createdAtEpochMs, compactedAtEpochMs, preview)`，其中 `preview` 按 80 字符截断（`PREVIEW_CHARS` 常量），丢掉完整 summary。
2. `ReadPartTool(partId)` —— 读单条 full payload，但要先知道具体 partId。

两条都不支持 "这个 session 压缩过几次 + 每次 from→to 范围 + 完整 summary" 的聚合视图。Backlog bullet 原文："给 `session_query` 加 `select=compactions`，每行带 `fromMessageId` / `toMessageId` / `summaryText` / `compactedAtEpochMs`，聚合视图。Rubric §5.2。"

**Note on跳过的亚军 bullet**: `project-query-sort-by-updatedAt` 被 plan 阶段 §3a 自查 skip —— 要求在 `Track` / `Clip` / `MediaAsset` 三个 @Serializable 一等类型上加 `updatedAtEpochMs: Long?`，然后 14+ 个 mutation tool 每个都要 stamp 一次，测试面 explode，且 "Project.updatedAt 反推" 的 fallback 方案项目内所有 entity 时间戳相同 → sortBy="recent" 实际语义为 no-op。§3a hard rule 12 明确要求触发即换 backlog。bullet 保留，等有具体 UI driver 再动。

**Decision.** 给 `SessionQueryTool` 加 `select=compactions`，新 `CompactionRow` 数据类 + 新 per-select 文件 `CompactionsQuery.kt`：

**New `CompactionRow`**:
```kotlin
@Serializable data class CompactionRow(
    val partId: String,
    val messageId: String,
    val fromMessageId: String,       // Part.Compaction.replacedFromMessageId
    val toMessageId: String,         // Part.Compaction.replacedToMessageId
    val summaryText: String,          // full summary, NOT 80-char preview
    val compactedAtEpochMs: Long,    // createdAt.toEpochMilliseconds()
)
```

**新文件 `CompactionsQuery.kt`**（~66 行）：
- `runCompactionsQuery(sessions, input, limit, offset)` 过滤所有 `Part.Compaction`，按 `createdAtEpochMs DESC` 排序，page 后 map 到 `CompactionRow`。
- `includeCompacted` 始终 true —— compaction parts 本身是"压缩的 meta"，不会被再次压缩；filter them out 是 nonsense。
- 空结果时 outputForLlm 返回 `"Session <id> '<title>' has not been compacted yet."`；非空时渲染前 3 条摘要 "from→to (summary-40-char...)"。

**SessionQueryTool.kt** 扩展：
- `SELECT_COMPACTIONS = "compactions"` 常量 + 加入 `ALL_SELECTS`。
- `Input.sessionId` description 扩展：`Required for messages/parts/forks/ancestors/tool_calls/compactions/status`。
- helpText 加新 select 的描述一行（区分与 `parts(kind=compaction)` 的差异：全 summary + message-range metadata）。
- `execute` 的 `when` 加 `SELECT_COMPACTIONS -> runCompactionsQuery(...)` 分支。
- Schema `select` description 枚举扩展。

**无需改 `rejectIncompatibleFilters`**：
- `compactions` 不接受任何 select-specific filter（kind / role / projectId / includeArchived / toolId / includeCompacted 都和它无关），既有逻辑的 "X filter 仅 select=Y 允许" 规则自然将它们 reject —— 对 compactions 来说 pass-through。
- `sessionId` 被 `requireSession` 在 `runCompactionsQuery` 里强制，error 消息 "select='compactions' requires sessionId" 来自 `QueryHelpers.requireSession`.

**Alternatives considered.**

1. **Option A (chosen)**: 新 `select=compactions` 独立 per-select 文件 + 专属 Row 类型。优点：和 `tool_calls` / `status` 模式对齐（已有的 "sparse filter + dedicated row"）；`summaryText` 完整输出（对比 preview 80-char 截断）；fromMessageId/toMessageId 结构化曝露 message-range；零 tool count 变化。
2. **Option B**: 在 `select=parts&kind=compaction` 上扩 `PartRow` 加 `summaryText` / `fromMessageId` / `toMessageId` optional 字段。拒绝：PartRow 是泛型 row（10 种 kind 共用），特定 kind 字段污染通用 row；每个 PartRow 解码都要带这些 optional 字段即使是其他 kind；破坏 "single generic row per select" 的设计契约。
3. **Option C**: 新 tool `list_compactions` 或 `describe_session_compactions`。拒绝：§3a.1 工具数量不净增。session_query 已经是 unified query primitive；compactions 属于同家族 aggregate view，扩 select 是自然归属。
4. **Option D**: 把 `includeCompacted` 继续作为 filter 暴露给 compactions（让调用方过滤）。拒绝：compaction parts 本身是 meta，`compactedAt` 字段对 Part.Compaction 永远 null —— 暴露该 filter 对 compactions 无意义，会让 LLM 误以为有此轴可控。当前 `rejectIncompatibleFilters` 已经禁止（filter 只允许 parts/tool_calls），保持现状。
5. **Option E**: Row 改成 `List<String>` 表 summary 行（LLM 更易消费）。拒绝：`summaryText: String` 保留 Compaction part 的原始形态；LLM 要 line-split 自己做（和 `ReadPartTool` 返回的 payload shape 一致，避免两套格式）。
6. **Option F**: 加 `summaryPreviewChars: Int?` 参数让调用方选择要 preview 还是 full。拒绝：bullet 明确要 "full summary"；两种视图让 schema 复杂；调用方要 preview 还有 `parts&kind=compaction` 可用。

**Coverage.**

- **新增 3 tests** 在 `SessionQueryToolTest.kt`:
  - `compactionsAggregatesFullSummaryMostRecentFirst` — 2 个 compaction parts（`cp-1@1000ms`, `cp-2@5000ms`），验证 DESC 排序 + full summary（没被 80 字符截断）+ fromMessageId/toMessageId 正确。
  - `compactionsEmptyWhenSessionHasNoCompactionParts` — 无 compaction → `total=0`, `outputForLlm` 含 "not been compacted"。
  - `compactionsRequiresSessionId` — omit sessionId → `IllegalStateException` 含 "requires sessionId"（§3a.9 边界 case）。
- `./gradlew :core:jvmTest` + `:apps:server:test` + `:core:ktlintCheck` 全绿；4 端构建全绿。

**Registration.** 无 tool 变更；5 端 AppContainer 无变动。SessionQueryTool 已在 5 端注册并消费 `agentStates` tracker（cycle 19），本 cycle 纯内部 select 扩展。

**§3a 自查.**

1. 工具数量: 0 变化。PASS。
2. Define/Update: N/A。
3. Project blob: 不动 Project。PASS。
4. 状态字段: 无新 flag。PASS。
5. Core genre: 无 genre 名词。PASS。
6. Session/Project binding: sessionId 走 `requireSession`；和 parts/tool_calls/status 一致。PASS。
7. 序列化向前兼容: 新 @Serializable CompactionRow 是独立类型，仅被 `select=compactions` 的 Output.rows JsonArray decode 消费；不影响既有 Row 类型。PASS。
8. 五端装配: 无新 tool。PASS。
9. 测试语义覆盖: 3 tests 覆盖 happy path（DESC + full summary + message-range）+ empty + missing-sessionId。PASS。
10. LLM context 成本: +1 select ("compactions") in enum、+1 helpText bullet（~60 tokens）、+1 line in sessionId description（~10 tokens）。每次调用 session_query 的 schema overhead ~70 tokens 增量。实际调用 `select=compactions` 的 Output.rows 随 compaction 数量增长（每行 summaryText 可能数百字符）—— 这是功能本身的信号价值，非 overhead。PASS（收益方向）。

**Non-goals / 后续切片.**

- **不扩 `Part.Compaction` schema** —— fromMessageId / toMessageId 已在 existing domain type；本改动仅暴露到 query 层。
- **不并 `compact-session-threshold-visible-in-ui`**（P2 bullet）—— 那个想加 `estimatedTokens` / `compactionThreshold` / `percent` 到 `select=status`，属于 status select 的扩展，独立 bullet。
- **跳过 `project-query-sort-by-updatedAt`** bullet 保留在 backlog 未动，等到有 UI driver 或更小 scope 的实现方式（例如单独加 `Clip.updatedAtEpochMs` 而不是三类一起）再动。
